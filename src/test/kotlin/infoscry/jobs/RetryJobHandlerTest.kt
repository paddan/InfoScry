package infoscry.jobs

import infoscry.AppContext
import infoscry.document.RetryPrerequisites
import infoscry.document.RetryService
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.JobState
import infoscry.domain.SourceLocation
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.GpuRuntime
import infoscry.embedding.GpuUnavailableException
import infoscry.embedding.ModelManager
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DocumentExtractor
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.ExternalDispatchPermitRequest
import infoscry.ocr.ImageLlmException
import infoscry.ocr.PageDispatchIdentity
import infoscry.ocr.PublicationDisposition
import infoscry.ocr.RecordedImageResponse
import infoscry.ocr.RecordingImageLlmEngine
import infoscry.ocr.ReviewerRecommendation
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.RetryPolicy
import infoscry.storage.Instants
import infoscry.storage.JobStore
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * What retrying a document does with the bytes the archive already holds.
 *
 * The promises this file pins are the ones that make Retry different from an ordinary import and from a
 * crash resume: the document keeps its identity and its compatible committed units, the failed units are
 * read again, a moved or missing source never matters, and nothing here can resurrect a document a deletion
 * has taken. The tests run the real job runner over a real data directory; only the extractor and the
 * embedder are fakes, because formats and accelerators belong to their own suites.
 */
class RetryJobHandlerTest {

    @Test
    fun `a retry records the collection's OCR selection in the settings it runs with`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            val snapshot = OcrSettingsSnapshot(
                engine = OcrEngine.SURYA,
                mode = OcrImportMode.CHECK_AND_IMPROVE,
                language = "eng",
                extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
            )

            // What admission does: the collection's selection is resolved into the attempt's snapshot, and the
            // retry's settings carry it, so the fingerprint a resumed retry looks its checkpoints up by names
            // the engine and mode that produced them.
            val prerequisites = AppContext.open(harness.dataDir).use { context ->
                runBlocking {
                    RetryPrerequisites.probe(
                        collection = context.collections.get(CollectionId("default"))!!,
                        modelsDir = context.paths.modelsDir,
                        ocrSnapshot = { snapshot },
                    )
                }
            }

            assertEquals(OcrEngine.SURYA, prerequisites.settings.ocrAttempt?.engine)
            assertEquals(OcrImportMode.CHECK_AND_IMPROVE, prerequisites.settings.ocrMode)
            // The snapshot itself travels with the prerequisites, not only folded into the settings: the
            // payload's `ocr` is what the attempt dispatches its external pages and its reviewer through.
            assertEquals(snapshot, prerequisites.ocr, "admission keeps the snapshot, not just its settings")
            // The document is still retryable: recording the selection changes the reading identity, not
            // whether the document may be read again.
            assertNotNull(documentId)
        }
    }

    @Test
    fun `a retry finishes from the managed copy after the external original is gone`() {
        withHarness { harness ->
            val source = harness.writeText("moved.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))

            // The user moved or deleted the file they imported: the managed copy is the only place these
            // bytes exist now, and a retry that needed the source would be useless exactly when it matters.
            Files.delete(source)

            val extractor = RecordingUnits(units = 2)
            val run = harness.retry(listOf(documentId), extractor)

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(listOf("unit-0", "unit-1"), extractor.produced)
            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.get(documentId)
                assertNotNull(document, "a retry keeps the document's identity")
                assertEquals(DocumentStatus.COMPLETE, document.status)
                assertEquals(2, context.index().chunkCount(CollectionId("default"), documentId))
                assertNull(document.errorCode, "a document that now succeeds keeps no stale failure")
            }
        }
    }

    @Test
    fun `a retry reuses compatible committed units instead of reading them again`() {
        withHarness { harness ->
            val source = harness.writeText("partial.txt", "half readable\n")
            // The first attempt committed unit-0 and then refused the rest of the document.
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 1))

            val extractor = RecordingUnits(units = 2)
            harness.retry(listOf(documentId), extractor)

            assertEquals(listOf("unit-1"), extractor.produced, "a committed unit must not be read again")
            assertEquals(listOf("unit-0"), extractor.skipped, "the same fingerprint makes unit-0 reusable")
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(DocumentStatus.COMPLETE, context.documents.get(documentId)!!.status)
                assertEquals(
                    listOf(0, 1),
                    context.content.listUnits(documentId, -1, 10).map { it.ordinal },
                    "the reused unit and the new one are both part of the document",
                )
            }
        }
    }

    @Test
    fun `an explicit retry revisits the unit a crash resume would skip`() {
        withHarness { harness ->
            val source = harness.writeText("warned.txt", "one good unit and one bad\n")
            // A pass that finished with a failed unit: that failure is a durable result, which is exactly
            // what a crash resume skips and what an explicit retry has to read again.
            val run = harness.importDurably(listOf(source), FailedUnitsThenFinish(goodUnits = 1, failedUnits = 1))
            val documentId = run.items.single().documentId!!
            val document = run.documents.getValue(documentId)
            val fingerprint = ExtractionFingerprint.of(document.sha256, ExtractionSettings(ocrLanguages = "eng"))

            AppContext.open(harness.dataDir).use { context ->
                val sink = StoredUnitsSink(context.paths, context.documents, context.content)
                val root = context.paths.artifactsDir(CollectionId("default"), documentId)
                val resume = runBlocking { sink.committedKeys(documentId, fingerprint) }
                val retry = runBlocking { sink.retryKeys(documentId, fingerprint) }
                assertTrue(FAILED_UNIT_KEY in resume, "a crash resume treats a failed unit as a known result")
                assertFalse(FAILED_UNIT_KEY in retry, "a retry must revisit the unit that failed")
                assertTrue("unit-0" in retry, "a unit that succeeded is still reused")
                assertTrue(context.content.reusableCheckpoints(documentId, fingerprint, root).skipKeys.isNotEmpty())
            }

            val extractor = FailedUnitRevisited()
            val retryRun = harness.retry(listOf(documentId), extractor)

            assertEquals(JobState.COMPLETE, retryRun.job.state)
            assertEquals(listOf(FAILED_UNIT_KEY), extractor.produced, "the failed unit was read again")
            assertTrue(extractor.skipped.isEmpty())
            assertEquals(DocumentStatus.COMPLETE, retryRun.documents.getValue(documentId).status)
        }
    }

    @Test
    fun `changed OCR languages repeat the extraction instead of reusing another fingerprint's units`() {
        withHarness { harness ->
            val source = harness.writeText("report.txt", "text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 1))

            val extractor = RecordingUnits(units = 2)
            // The collection's OCR languages changed after the first attempt. The units committed under the
            // old fingerprint are not evidence for the new one, so every unit is read again — and the UI says
            // that a retry may need to repeat extraction for exactly this reason.
            val run = harness.retry(
                listOf(documentId),
                extractor,
                settings = ExtractionSettings(ocrLanguages = "swe+eng"),
            )

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(listOf("unit-0", "unit-1"), extractor.produced)
            assertTrue(extractor.skipped.isEmpty(), "another fingerprint's unit was reused")
            assertEquals(DocumentStatus.COMPLETE, run.documents.getValue(documentId).status)
        }
    }

    @Test
    fun `re-embedding alone never invalidates compatible extraction checkpoints`() {
        withHarness { harness ->
            val source = harness.writeText("retry-embed.txt", "Ordinary text\n")

            // A pass whose extraction finished and whose embedding could not run: the missing-model remedy is
            // ticket 04's per-document failure, and the committed OCR/text work is what has to survive.
            AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(source.toRealPath()))
                ImportJobHandler.attachTo(
                    context,
                    harness.storedPipeline(context, RecordingUnits(units = 2)),
                    documentEmbedder = { null },
                )
                harness.awaitJob(context, job.id)
            }
            val documentId = AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.listByCollection(CollectionId("default"), limit = 10).single()
                assertEquals(DocumentStatus.FAILED, document.status)
                assertEquals(ModelManager.MODEL_NOT_INSTALLED_CODE, document.errorCode)
                assertNotNull(context.content.extractionMarker(document.id), "the extraction pass finished")
                document.id
            }

            val extractor = RecordingUnits(units = 2)
            val run = harness.retry(listOf(documentId), extractor)

            assertEquals(JobState.COMPLETE, run.job.state)
            assertTrue(extractor.produced.isEmpty(), "re-embedding must not re-read the document")
            assertEquals(listOf("unit-0", "unit-1"), extractor.skipped)
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(DocumentStatus.COMPLETE, context.documents.get(documentId)!!.status)
                assertEquals(2, context.index().chunkCount(CollectionId("default"), documentId))
            }
        }
    }

    @Test
    fun `a managed copy that is gone fails the document rather than the attempt`() {
        withHarness { harness ->
            val source = harness.writeText("lost.txt", "text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))

            // The bytes this retry was admitted for are not on disk any more: a safe failure with a code,
            // not a crash and not a silent no-op.
            Files.walk(harness.managedDirectory(documentId)).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }

            val extractor = RecordingUnits(units = 1)
            val run = harness.retry(listOf(documentId), extractor)

            assertEquals(JobState.COMPLETE, run.job.state, "one document's loss is not the attempt's failure")
            assertTrue(extractor.produced.isEmpty(), "there were no bytes to read")
            val document = run.documents.getValue(documentId)
            assertEquals(DocumentStatus.FAILED, document.status)
            assertEquals("MANAGED_COPY_MISSING", document.errorCode)
            assertTrue(
                document.errorMessage.orEmpty().contains("managed copy"),
                "the message says what is missing, was ${document.errorMessage}",
            )
        }
    }

    @Test
    fun `a deletion that won the race stops the retry from publishing or recreating the target`() {
        withHarness { harness ->
            val source = harness.writeText("doomed.txt", "text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))

            val extractor = RecordingUnits(units = 1)
            AppContext.open(harness.dataDir).use { context ->
                // The state a deletion leaves between its admission and its destructive phases: the target
                // row is durable and the document's own row is still there. Nothing in the retry may publish
                // it again, and nothing may recreate it once the deletion has removed it.
                harness.recordDocumentDeletionTarget(context, documentId)
                val job = harness.enqueueRetry(context, listOf(documentId))
                harness.attach(context, harness.storedPipeline(context, extractor))
                val finished = runBlocking { awaitTerminal(context, job.id) }

                assertEquals(JobState.COMPLETE, finished.state)
                assertTrue(extractor.produced.isEmpty(), "a document being deleted must not be read again")
                val document = context.documents.get(documentId)
                assertNotNull(document, "a retry never recreates a row the archive still holds")
                assertEquals(
                    DocumentStatus.FAILED,
                    document.status,
                    "the deletion owns the document, so the retry writes nothing to it",
                )
                assertEquals(0, context.index().chunkCount(CollectionId("default"), documentId))
            }
        }
    }

    @Test
    fun `a cancelled attempt leaves its interrupted document honestly cancelled`() {
        withHarness { harness ->
            val source = harness.writeText("interrupted.txt", "text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            val parked = CompletableDeferred<Unit>()

            AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueRetry(context, listOf(documentId))
                harness.attach(context, harness.storedPipeline(context, ParkedUnits(parked, units = 3)))
                runBlocking { withTimeout(RETRY_PARK_TIMEOUT_MILLIS) { parked.await() } }

                // The attempt is mid-extraction, so the document's own progress reads as work underway: the
                // retry's attempt is what the existing document views show, under the same identity.
                val midFlight = context.documents.get(documentId)!!
                assertEquals(DocumentStatus.EXTRACTING, midFlight.status)
                assertEquals(
                    1,
                    context.content.documentProgress(listOf(documentId)).getValue(documentId).processedUnits,
                    "the unit committed before the park is what the document's progress counts",
                )

                runBlocking { context.cancelJob(job.id) }
                val finished = runBlocking { awaitTerminal(context, job.id) }

                assertEquals(JobState.CANCELLED, finished.state)
                val cancelled = context.documents.get(documentId)!!
                assertEquals(
                    DocumentStatus.CANCELLED,
                    cancelled.status,
                    "an interrupted document must not keep looking permanently active",
                )
                assertEquals("RETRY_CANCELLED", cancelled.errorCode)
            }
        }
    }

    @Test
    fun `a missing accelerator is recorded as the document's failure with its remedy`() {
        withHarness { harness ->
            val source = harness.writeText("gpu.txt", "text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))

            val failing = object : DocumentEmbedder {
                override fun embedDocuments(texts: List<String>): List<FloatArray> =
                    throw GpuUnavailableException(GpuRuntime.GPU_UNAVAILABLE_CODE, "no usable accelerator")
            }
            val run = harness.retry(listOf(documentId), RecordingUnits(units = 1), embedder = failing)

            assertEquals(JobState.COMPLETE, run.job.state)
            val document = run.documents.getValue(documentId)
            assertEquals(DocumentStatus.FAILED, document.status)
            assertEquals(GpuRuntime.GPU_UNAVAILABLE_CODE, document.errorCode)
        }
    }

    @Test
    fun `a differing check-and-improve retry calls the reviewer and leaves a proposal pending`() = runBlocking {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            // A document a user would press Retry on: stored bytes, a failed status.
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            val reviewer = FakeOpenAiServer(
                listOf(FakeOpenAiResponse(body = reviewEnvelope(reviewAnswer("B_BETTER")))),
            )
            try {
                val snapshot = reviewingRetrySnapshot(harness.reviewProfile(reviewer.url).revision.revisionId)

                val run = harness.retryStaging(
                    documentIds = listOf(documentId),
                    extractor = CheckAndImprovePages(text = "name 123", reading = "name 128"),
                    settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                    ocr = snapshot,
                )

                // The reading was staged and judged exactly as an import's reading would be: the retry runs
                // under the snapshotted reviewer it was admitted with, not under no reviewer at all.
                assertEquals(
                    DocumentStatus.NEEDS_REVIEW,
                    run.documents.getValue(documentId).status,
                    "the reading was staged and judged",
                )
                AppContext.open(harness.dataDir).use { context ->
                    val review = context.ocrReviews.pending(documentId).single()
                    // The page's two readings differ, so a person has to decide which one stands — pilot mode
                    // proposes, whatever the reviewer answered.
                    assertEquals(PublicationDisposition.PROPOSE, review.disposition)
                    assertEquals(ReviewerRecommendation.NEW_BETTER, review.recommendation)
                    assertEquals("page:1", review.unitId)
                    assertEquals(0, review.ordinal)
                    assertTrue(review.reasons.isNotEmpty(), "the reason about the difference is kept")
                }
                assertEquals(1, reviewer.handledRequests, "the page's two readings were judged by the reviewer")
            } finally {
                reviewer.close()
            }
        }
    }

    @Test
    fun `a check-and-improve retry whose reading matches the page's own text records no review`() = runBlocking {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            val reviewer = FakeOpenAiServer(
                listOf(FakeOpenAiResponse(body = reviewEnvelope(reviewAnswer("B_BETTER")))),
            )
            try {
                val snapshot = reviewingRetrySnapshot(harness.reviewProfile(reviewer.url).revision.revisionId)

                val run = harness.retryStaging(
                    documentIds = listOf(documentId),
                    extractor = CheckAndImprovePages(text = "name 123", reading = "name 123"),
                    settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                    ocr = snapshot,
                )

                AppContext.open(harness.dataDir).use { context ->
                    assertTrue(
                        context.ocrReviews.pending(documentId).isEmpty(),
                        "two identical readings are nothing anybody has to decide, so no review is written",
                    )
                }
                assertEquals(0, reviewer.handledRequests, "a pair that does not differ is not sent to a reviewer")
            } finally {
                reviewer.close()
            }
        }
    }

    @Test
    fun `external transcription and review of one retry page count one distinct page and two calls`() = runBlocking {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            // Both stages dispatch off this machine — and neither leaves it: the transcription goes out
            // of the real image engine through the injected recording transport, and the reviewer's
            // request is recorded by the same engine through the attempt's review authority.
            val recorder = RecordingImageLlmEngine(
                script = listOf(
                    RecordedImageResponse(body = transcriptionEnvelope("name 128")),
                    RecordedImageResponse(body = reviewEnvelope(reviewAnswer("B_BETTER"))),
                ),
            )
            try {
                val transcriptionProfile = harness.externalProfile(
                    endpoint = "https://transcriber.example.invalid/v1",
                    keyVariable = "HOME",
                )
                val reviewerProfile = harness.externalReviewProfile(
                    endpoint = "https://reviewer.example.invalid/v1",
                )
                val snapshot = OcrSettingsSnapshot(
                    engine = OcrEngine.LLM,
                    mode = OcrImportMode.CHECK_AND_IMPROVE,
                    language = "eng",
                    extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
                    transcriptionProfileRevisionId = transcriptionProfile.revision.revisionId,
                    reviewProfileRevisionId = reviewerProfile.revision.revisionId,
                    externalPageLimit = 1,
                )
                val extractor = RecordingLlmUnits(
                    snapshot = snapshot,
                    revision = transcriptionProfile.revision,
                    engine = recorder,
                    directText = "name 123",
                )

                val run = harness.retryStaging(
                    documentIds = listOf(documentId),
                    extractor = extractor,
                    settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                    ocr = snapshot,
                    clientEngine = recorder,
                )

                assertEquals(
                    listOf(documentId),
                    extractor.dispatched,
                    "the transcription stage dispatched the page",
                )
                assertEquals(
                    listOf(
                        "https://transcriber.example.invalid/v1/chat/completions",
                        "https://reviewer.example.invalid/v1/chat/completions",
                    ),
                    recorder.requests.map { it.url },
                    "one recorded request per stage, each at the endpoint production classified EXTERNAL",
                )
                val account = harness.externalAccountOf(run.job.id, snapshot)
                assertEquals(1, account.distinctPages, "one page of one document, counted once across both stages")
                assertEquals(2, account.calls, "both stages dispatched a call against the job's one scope")
                assertEquals(JobState.COMPLETE, run.job.state)
                assertEquals(DocumentStatus.NEEDS_REVIEW, run.documents.getValue(documentId).status)
                AppContext.open(harness.dataDir).use { context ->
                    assertTrue(
                        context.ocrReviews.pending(documentId).isNotEmpty(),
                        "the recorded reviewer's answer still leaves a proposal a person must decide",
                    )
                }
            } finally {
                recorder.close()
            }
        }
    }

    @Test
    fun `a retry with no external allowance waits for approval before any dispatch`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            // The safe engineering default: no page may leave the machine without an approval, and the
            // reviewer is external too, so both stages would leave if anything were dispatched. The
            // injected recording transport is what proves nothing did.
            val recorder = RecordingImageLlmEngine(
                script = listOf(
                    RecordedImageResponse(body = transcriptionEnvelope("name 128")),
                    RecordedImageResponse(body = reviewEnvelope(reviewAnswer("B_BETTER"))),
                ),
            )
            try {
                val transcriptionProfile = harness.externalProfile(
                    endpoint = "https://transcriber.example.invalid/v1",
                    keyVariable = "HOME",
                )
                val reviewerProfile = harness.externalReviewProfile(
                    endpoint = "https://reviewer.example.invalid/v1",
                )
                val snapshot = OcrSettingsSnapshot(
                    engine = OcrEngine.LLM,
                    mode = OcrImportMode.CHECK_AND_IMPROVE,
                    language = "eng",
                    extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
                    transcriptionProfileRevisionId = transcriptionProfile.revision.revisionId,
                    reviewProfileRevisionId = reviewerProfile.revision.revisionId,
                    externalPageLimit = 0,
                )
                val extractor = RecordingLlmUnits(
                    snapshot = snapshot,
                    revision = transcriptionProfile.revision,
                    engine = recorder,
                    directText = "name 123",
                )

                val run = harness.retryStaging(
                    documentIds = listOf(documentId),
                    extractor = extractor,
                    settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                    ocr = snapshot,
                    clientEngine = recorder,
                )

                // The refusal comes first because that is what the allowance promises: nothing left, nothing was
                // counted, and the wait is a durable state rather than a failure without a remedy.
                assertTrue(
                    extractor.dispatched.isEmpty(),
                    "a page left this machine before its scope was approved",
                )
                assertEquals(0, recorder.requestCount, "the recorder saw nothing for the refused page")
                val account = harness.externalAccountOf(run.job.id, snapshot)
                assertEquals(0, account.distinctPages)
                assertEquals(0, account.calls)
                assertEquals(JobState.COMPLETE, run.job.state, "a wait is not a failed attempt")
                assertEquals(
                    JobStore.AWAITING_APPROVAL_STAGE,
                    run.job.stage,
                    "a retry that may not dispatch has to say what it waits for",
                )
                val document = run.documents.getValue(documentId)
                assertTrue(
                    document.status != DocumentStatus.FAILED,
                    "waiting for an approval is not a document failure, was ${document.status}",
                )
                AppContext.open(harness.dataDir).use { context ->
                    assertTrue(
                        context.ocrReviews.pending(documentId).isEmpty(),
                        "nothing was dispatched, so nothing was reviewed",
                    )
                }
            } finally {
                recorder.close()
            }
        }
    }

    @Test
    fun `a resumed retry continues its job's counters and keeps the snapshot it was admitted with`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            // The recording transport is shared by both runs of this job: the paused run must touch it
            // not at all, and the resumed run must be recorded dispatching exactly one page twice —
            // transcription and review — into the same account the pause left behind.
            val recorder = RecordingImageLlmEngine(
                script = listOf(
                    RecordedImageResponse(body = transcriptionEnvelope("name 128")),
                    RecordedImageResponse(body = reviewEnvelope(reviewAnswer("B_BETTER"))),
                ),
            )
            try {
                val transcriptionProfile = harness.externalProfile(
                    endpoint = "https://transcriber.example.invalid/v1",
                    keyVariable = "HOME",
                )
                val reviewerProfile = harness.externalReviewProfile(
                    endpoint = "https://reviewer.example.invalid/v1",
                )
                val snapshot = OcrSettingsSnapshot(
                    engine = OcrEngine.LLM,
                    mode = OcrImportMode.CHECK_AND_IMPROVE,
                    language = "eng",
                    extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
                    transcriptionProfileRevisionId = transcriptionProfile.revision.revisionId,
                    reviewProfileRevisionId = reviewerProfile.revision.revisionId,
                    externalPageLimit = 0,
                )
                val settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot)
                val extractor = RecordingLlmUnits(
                    snapshot = snapshot,
                    revision = transcriptionProfile.revision,
                    engine = recorder,
                    directText = "name 123",
                )

                val waiting = harness.retryStaging(
                    documentIds = listOf(documentId),
                    extractor = extractor,
                    settings = settings,
                    ocr = snapshot,
                    clientEngine = recorder,
                )
                assertEquals(JobStore.AWAITING_APPROVAL_STAGE, waiting.job.stage)
                assertEquals(0, harness.externalAccountOf(waiting.job.id, snapshot).distinctPages)
                assertEquals(0, recorder.requestCount, "the paused run touched no transport")

                // The approval is the scope this job was admitted with, and it puts the same job back in the
                // queue — a fresh process over the same durable state is what runs it.
                harness.approveJobScope(waiting.job.id, snapshot, maxDistinctPages = 1)
                val resumed = AppContext.open(harness.dataDir).use { context ->
                    context.jobs.resumeAwaitingApproval(waiting.job.id)
                    harness.attach(
                        context,
                        harness.stagingPipeline(context, extractor),
                        clientEngine = recorder,
                    )
                    RetryRun(
                        job = harness.awaitJob(context, waiting.job.id),
                        documents = context.documents.listByCollection(CollectionId("default"), limit = 100)
                            .associateBy { it.id },
                    )
                }

                // The same job's account, continued rather than restarted: the pages the resumed attempt sends
                // are counted into the scope that waited, and both stages of the page account for themselves.
                assertEquals(listOf(documentId), extractor.dispatched, "the approved page was sent")
                assertEquals(
                    listOf(
                        "https://transcriber.example.invalid/v1/chat/completions",
                        "https://reviewer.example.invalid/v1/chat/completions",
                    ),
                    recorder.requests.map { it.url },
                    "the resumed attempt's two dispatches are the recorder's only two requests",
                )
                val account = harness.externalAccountOf(resumed.job.id, snapshot)
                assertEquals(1, account.distinctPages, "resume never resets the counter")
                assertEquals(2, account.calls)

                // And the attempt still runs under what it was admitted with: the snapshot and settings decode
                // unchanged from the job's own durable payload after the restart.
                val payload = RetryJobPayload.decode(resumed.job.payload)
                assertEquals(snapshot, payload.ocr, "the resumed attempt runs under its admission snapshot")
                assertEquals(settings, payload.settings, "the admitted settings are what resume re-reads with")
                assertEquals(DocumentStatus.NEEDS_REVIEW, resumed.documents.getValue(documentId).status)
            } finally {
                recorder.close()
            }
        }
    }

    @Test
    fun `a resumed retry does not re-process the document the paused run already finished`() {
        withHarness { harness ->
            val first = harness.writeText("first.txt", "First page\n")
            val second = harness.writeText("second.txt", "Second page\n")
            val firstId = harness.failedDocument(first, RefusingUnits(unitsBeforeRefusing = 0))
            val secondId = harness.failedDocument(second, RefusingUnits(unitsBeforeRefusing = 0))
            // One distinct page: the allowance belongs to the job, so the first document's page consumes it
            // and the second document's page is the one that would exceed it — the pause of the import
            // test's `one allowance covers two files of one job`, over documents instead of files.
            val snapshot = harness.externalSnapshot(profile = harness.externalProfile(), allowance = 1)
            val extractor = RecordingDispatchingUnits()

            val waiting = harness.retry(listOf(firstId, secondId), extractor, ocr = snapshot)

            assertEquals(JobStore.AWAITING_APPROVAL_STAGE, waiting.job.stage)
            assertEquals(
                listOf(firstId),
                extractor.dispatched,
                "only the first document's page was dispatched; the second document's page was refused",
            )
            val paused = harness.externalAccountOf(waiting.job.id, snapshot)
            assertEquals(1, paused.distinctPages, "one page left before the wait")
            assertEquals(1, paused.calls)
            assertEquals(DocumentStatus.COMPLETE, waiting.documents.getValue(firstId).status)

            // The approval is the scope this job was admitted with, and it puts the same job back in the
            // queue — a fresh process over the same durable state is what runs it.
            harness.approveJobScope(waiting.job.id, snapshot, maxDistinctPages = 2)
            val resumed = AppContext.open(harness.dataDir).use { context ->
                context.jobs.resumeAwaitingApproval(waiting.job.id)
                harness.attach(context, harness.storedPipeline(context, extractor))
                RetryRun(
                    job = harness.awaitJob(context, waiting.job.id),
                    documents = context.documents.listByCollection(CollectionId("default"), limit = 100)
                        .associateBy { it.id },
                )
            }

            // The wait paused after the first document had already finished. The resumed run must skip it
            // rather than repeat its committed work: each document's page dispatched exactly once across
            // both runs of the one job.
            assertEquals(
                1,
                extractor.dispatched.count { it == firstId },
                "the finished document was read again when the approved job ran",
            )
            assertEquals(1, extractor.dispatched.count { it == secondId })
            assertEquals(listOf(firstId, secondId), extractor.dispatched)
            assertEquals(DocumentStatus.COMPLETE, resumed.documents.getValue(firstId).status)
            assertEquals(DocumentStatus.COMPLETE, resumed.documents.getValue(secondId).status)
            assertEquals(JobState.COMPLETE, resumed.job.state)
            val account = harness.externalAccountOf(resumed.job.id, snapshot)
            assertEquals(2, account.distinctPages, "one page of each document, counted once across both runs")
            assertEquals(2, account.calls, "one call per page each run actually sent")
        }
    }

    @Test
    fun `bounded retries add calls without adding distinct pages against the job's scope`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            // The first attempt is throttled: the recording transport answers 429, the client's bounded
            // retry sends again, and then the reviewer's stage dispatches — three recorded calls for the
            // one page, which is exactly what the two counters must tell apart.
            val recorder = RecordingImageLlmEngine(
                script = listOf(
                    RecordedImageResponse(statusCode = 429),
                    RecordedImageResponse(body = transcriptionEnvelope("name 128")),
                    RecordedImageResponse(body = reviewEnvelope(reviewAnswer("B_BETTER"))),
                ),
            )
            try {
                val transcriptionProfile = harness.externalProfile(
                    endpoint = "https://transcriber.example.invalid/v1",
                    keyVariable = "HOME",
                )
                val reviewerProfile = harness.externalReviewProfile(
                    endpoint = "https://reviewer.example.invalid/v1",
                )
                val snapshot = OcrSettingsSnapshot(
                    engine = OcrEngine.LLM,
                    mode = OcrImportMode.CHECK_AND_IMPROVE,
                    language = "eng",
                    extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
                    transcriptionProfileRevisionId = transcriptionProfile.revision.revisionId,
                    reviewProfileRevisionId = reviewerProfile.revision.revisionId,
                    externalPageLimit = 1,
                )
                val extractor = RecordingLlmUnits(
                    snapshot = snapshot,
                    revision = transcriptionProfile.revision,
                    engine = recorder,
                    directText = "name 123",
                    retryPolicy = RetryPolicy(maxRetries = 1, retryDelay = { }),
                )

                val run = harness.retryStaging(
                    documentIds = listOf(documentId),
                    extractor = extractor,
                    settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                    ocr = snapshot,
                    clientEngine = recorder,
                )

                assertEquals(JobState.COMPLETE, run.job.state)
                assertEquals(
                    3,
                    recorder.requestCount,
                    "the throttled attempt, its retry and the review are three recorded calls",
                )
                assertEquals(
                    List(2) { "https://transcriber.example.invalid/v1/chat/completions" } +
                        "https://reviewer.example.invalid/v1/chat/completions",
                    recorder.requests.map { it.url },
                )
                val account = harness.externalAccountOf(run.job.id, snapshot)
                assertEquals(
                    1,
                    account.distinctPages,
                    "a retry is another call for the same page, not another page",
                )
                assertEquals(3, account.calls, "every network attempt counts as a call")
                assertEquals(DocumentStatus.NEEDS_REVIEW, run.documents.getValue(documentId).status)
            } finally {
                recorder.close()
            }
        }
    }

    @Test
    fun `retry eligibility does not broaden to completed or review-pending documents`() {
        // Reaching again for Retry may not widen what Retry is offered for: a finished document is read, a
        // review-pending document owes a decision rather than another reading, and the statuses with
        // unfinished business are the ones admission accepts.
        assertTrue(DocumentStatus.FAILED in RetryService.ELIGIBLE_STATUSES)
        assertTrue(DocumentStatus.CANCELLED in RetryService.ELIGIBLE_STATUSES)
        assertTrue(DocumentStatus.NEEDS_TOOL in RetryService.ELIGIBLE_STATUSES)
        assertFalse(DocumentStatus.COMPLETE in RetryService.ELIGIBLE_STATUSES)
        assertFalse(DocumentStatus.COMPLETE_WITH_WARNINGS in RetryService.ELIGIBLE_STATUSES)
        assertFalse(DocumentStatus.NEEDS_REVIEW in RetryService.ELIGIBLE_STATUSES)
    }

    private fun withHarness(block: (Harness) -> Unit) {
        val directory = Files.createTempDirectory("infoscry-retry")
        try {
            Harness(directory).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

/** The unit key a finished pass recorded as failed, which a crash resume skips and a retry revisits. */
private const val FAILED_UNIT_KEY = "unit-failed-0"

/**
 * The OCR selection a check-and-improve retry is admitted with when [reviewerRevisionId] judges its pages:
 * every page is read by the local engine, and a reviewer is configured — the retry mirror of the import
 * test's `reviewingSnapshot`.
 */
private fun reviewingRetrySnapshot(reviewerRevisionId: String): OcrSettingsSnapshot = OcrSettingsSnapshot(
    engine = OcrEngine.TESSERACT,
    mode = OcrImportMode.CHECK_AND_IMPROVE,
    language = "eng",
    extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
    reviewProfileRevisionId = reviewerRevisionId,
)

/**
 * An external image-model *reviewer* profile, created through the real store.
 *
 * Its endpoint leaves the machine — `endpointScope` classifies it EXTERNAL, which is the rule that decides
 * whether a review dispatch is counted against an allowance at all — while a loopback endpoint would be
 * local and counted by nobody. The host is the reserved `.invalid` name: DNS fails immediately (measured:
 * ~24 ms on this machine) and no image or text is ever delivered anywhere. The variable named is one every
 * test environment has set, so the client's external-credential gate passes and what such a test pins is the
 * dispatch accounting rather than a missing key.
 */
internal fun Harness.externalReviewProfile(
    endpoint: String = "https://example.invalid/v1",
    keyVariable: String = "HOME",
): infoscry.ocr.OcrProfile = AppContext.open(dataDir).use { context ->
    context.ocrProfiles.create(
        name = "External reviewer",
        draft = infoscry.ocr.OcrProfileRevisionDraft(
            provider = infoscry.llm.LlmProvider.OPENAI_COMPATIBLE,
            model = "external-reviewer",
            contextWindow = 32_000,
            maxOutputTokens = 1_024,
            endpoint = endpoint,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 1.0,
            apiKeyEnvironmentVariable = keyVariable,
        ),
        enabled = true,
    )
}

/**
 * A check-and-improve extractor that dispatches its page through the attempt's authority before it emits
 * it, the way an external image engine does: the transcription-stage permit is asked and the call counted
 * while the pixels are on their way, and the unit that comes back carries both readings so the staged
 * comparison has its pair. [sent] is what actually left, which is what one job's allowance bounds.
 */
/** How long a test waits for a parked attempt before calling it a failure rather than a hang. */
private const val RETRY_PARK_TIMEOUT_MILLIS = 30_000L

/**
 * An engine that records which document's page it actually dispatched, in order.
 *
 * Every extraction invocation asks the attempt's authority for its page where a real external engine does,
 * and records the document only once the page is admitted: the invocation whose page the allowance refuses
 * throws before it reads, so [dispatched] holds exactly the documents this attempt read. The permit is asked
 * even when an earlier attempt already committed the page — an already-counted page is admitted again
 * without another count — because "was this finished document read a second time" is the question this
 * extractor exists to answer.
 */
internal class RecordingDispatchingUnits : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    /** The document of every page this engine dispatched, in order, across every run of one job. */
    val dispatched = mutableListOf<DocumentId>()

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val authority = input.dispatch
        if (authority != null) {
            val request = ExternalDispatchPermitRequest(
                profileRevisionId = authority.profileRevisionId,
                page = PageDispatchIdentity(
                    unitId = DISPATCHED_UNIT_KEY,
                    ordinal = 0,
                    documentId = input.documentId.value,
                ),
            )
            if (!authority.isPermitted(request)) {
                throw ImageLlmException(
                    ImageLlmException.EXTERNAL_DISPATCH_NOT_PERMITTED,
                    "this page is not covered by an external dispatch permit for this profile revision",
                )
            }
            authority.attemptAboutToBeSent(request)
            dispatched += input.documentId
        }
        if (!input.isCommitted(DISPATCHED_UNIT_KEY)) {
            input.boundary.unit {
                emit(
                    ExtractionEvent.UnitReady(
                        key = DISPATCHED_UNIT_KEY,
                        ordinal = 0,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.TextLines(start = 1, end = 1),
                            extractedText = "read by the model",
                            searchText = "read by the model",
                            method = ExtractionMethod.OCR,
                        ),
                    ),
                )
            }
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1)) }
    }
}

/** The one unit key [RecordingDispatchingUnits] dispatches and commits. */
private const val DISPATCHED_UNIT_KEY = "unit-0"

/**
 * An extractor whose only question is whether the unit that failed last time is being read again.
 *
 * It asks the resume point directly — `isCommitted` is the same question the real extractors ask — so what
 * it records is the pipeline's own answer rather than a count derived from the outside.
 */
internal class FailedUnitRevisited : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    val produced = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        if (input.isCommitted(FAILED_UNIT_KEY)) {
            skipped += FAILED_UNIT_KEY
        } else {
            input.boundary.unit {
                produced += FAILED_UNIT_KEY
                emit(
                    ExtractionEvent.UnitReady(
                        key = FAILED_UNIT_KEY,
                        ordinal = 1,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.TextLines(start = 2, end = 2),
                            extractedText = "unit 1",
                            searchText = "unit 1",
                            method = ExtractionMethod.DIRECT_TEXT,
                        ),
                    ),
                )
            }
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 2)) }
    }
}

/** The managed directory of one document, found the way the archive lays it out. */
internal fun Harness.managedDirectory(documentId: DocumentId): java.nio.file.Path =
    dataDir.resolve("library/default/${documentId.value}")

/**
 * Seeds a document in the failed state a user would press Retry on: a real managed copy, a committed unit
 * when the extractor committed one, and the document's own failure code.
 */
internal fun Harness.failedDocument(
    source: java.nio.file.Path,
    extractor: DocumentExtractor,
): DocumentId {
    val run = importDurably(listOf(source), extractor)
    val documentId = run.items.single().documentId!!
    val document = run.documents.getValue(documentId)
    assertEquals(DocumentStatus.FAILED, document.status, "the seeding attempt must leave a failed document")
    assertTrue(Files.isRegularFile(managedOriginal(documentId)))
    return documentId
}

/**
 * Records the durable state of a document deletion that has been admitted but has not yet run.
 *
 * That is the race a retry has to lose: the target row exists, the document's own row is still there, and
 * every write boundary in the archive refuses the document. The rows are written directly because the
 * deletion machine's own phases would remove the document entirely, which is a different test — and
 * deliberately on an *open* context, because opening one runs the deletion recovery that would roll this
 * state forward before the retry ever sees it.
 */
internal fun Harness.recordDocumentDeletionTarget(context: AppContext, documentId: DocumentId) {
    val operationId = UUID.randomUUID().toString()
    val now = Instants.now()
    context.database.transaction { connection ->
        connection.prepareStatement(
            "INSERT INTO deletion_operations (id, collection_id, collection_name, trash_basename, " +
                "managed_originals_existed, phase, kind, created_at, updated_at) " +
                "VALUES (?, 'default', 'Default', ?, 1, 'PREPARED', 'DOCUMENT', ?, ?)",
        ).use { statement ->
            statement.setString(1, operationId)
            statement.setString(2, ".deleted-${UUID.randomUUID()}")
            statement.setString(3, now)
            statement.setString(4, now)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO document_deletion_targets (operation_id, document_id, managed_existed) " +
                "VALUES (?, ?, 1)",
        ).use { statement ->
            statement.setString(1, operationId)
            statement.setString(2, documentId.value)
            statement.executeUpdate()
        }
    }
    assertTrue(context.documents.isDeletionTarget(documentId), "the guard has to be readable")
}

/** One attempt with a controller-supplied embedder, for the failure paths that are not about the model. */
internal class ThrowingEmbedder(private val failure: Exception) : DocumentEmbedder {

    override fun embedDocuments(texts: List<String>): List<FloatArray> = throw failure
}
