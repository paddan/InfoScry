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
import infoscry.ocr.RecordedImageResponse
import infoscry.ocr.RecordingImageLlmEngine
import infoscry.llm.RetryPolicy
import infoscry.storage.Instants
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
                mode = OcrImportMode.READ_ALL,
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
            assertEquals(OcrImportMode.READ_ALL, prerequisites.settings.ocrMode)
            // The snapshot itself travels with the prerequisites, not only folded into the settings: the
            // payload's `ocr` is what the attempt dispatches its external pages through.
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
    fun `a legacy retry keeps the snapshotted language after the collection default changes`() {
        withHarness { harness ->
            val source = harness.writeText("language-snapshot.txt", "first unit\nsecond unit\n")
            val snapshot = OcrSettingsSnapshot(
                engine = OcrEngine.TESSERACT,
                mode = OcrImportMode.FILL_MISSING,
                language = "eng",
                extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
            )
            val documentId = harness.failedDocument(
                source,
                RefusingUnits(unitsBeforeRefusing = 1),
                snapshot = snapshot,
            )
            val changedDefaults = AppContext.open(harness.dataDir).use { context ->
                val collection = context.collections.get(CollectionId("default"))!!
                context.collections.updateOcrSettings(
                    collection.id,
                    collection.ocrSettings().copy(language = "swe"),
                )
                ExtractionSettings(ocrLanguages = context.collections.get(CollectionId("default"))!!.ocrLanguages)
            }
            val document = AppContext.open(harness.dataDir).use { context -> context.documents.get(documentId)!! }
            val oldFingerprint = ExtractionFingerprint.of(
                document.sha256,
                ExtractionSettings(ocrLanguages = "eng"),
            )
            assertEquals(
                oldFingerprint,
                ExtractionFingerprint.of(document.sha256, changedDefaults.forOcrSettings(snapshot)),
                "legacy fill-missing keeps its original fingerprint shape and language",
            )

            val extractor = RecordingUnits(units = 2)
            val run = harness.retry(
                listOf(documentId),
                extractor,
                settings = changedDefaults,
                ocr = snapshot,
            )

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(listOf("unit-1"), extractor.produced)
            assertEquals(listOf("unit-0"), extractor.skipped)
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
    fun `external transcription of one retry page counts one distinct page and one call`() = runBlocking {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            val recorder = RecordingImageLlmEngine(
                script = listOf(RecordedImageResponse(body = transcriptionEnvelope("name 128"))),
            )
            try {
                val profile = harness.externalProfile(
                    endpoint = "https://transcriber.example.invalid/v1",
                    keyVariable = "HOME",
                )
                val snapshot = externalRetrySnapshot(profile.revision.revisionId, allowance = 1)
                val extractor = RecordingLlmUnits(
                    snapshot = snapshot,
                    revision = profile.revision,
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

                assertEquals(listOf(documentId), extractor.dispatched)
                assertEquals(listOf("https://transcriber.example.invalid/v1/chat/completions"), recorder.requests.map { it.url })
                val account = harness.retryExternalAccountOf(run.job.id, documentId, snapshot)
                assertEquals(1, account.distinctPages)
                assertEquals(1, account.calls)
                assertEquals(JobState.COMPLETE, run.job.state)
                assertEquals(DocumentStatus.COMPLETE, run.documents.getValue(documentId).status)
            } finally {
                recorder.close()
            }
        }
    }

    @Test
    fun `a retry over its confirmed page count fails before dispatch`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            val extractor = RecordingDispatchingUnits()
            val profile = harness.externalProfile()
            val snapshot = externalRetrySnapshot(profile.revision.revisionId, allowance = 0)

            val run = harness.retry(listOf(documentId), extractor, ocr = snapshot)

            assertTrue(extractor.dispatched.isEmpty(), "a page beyond the confirmed count is refused before dispatch")
            assertEquals(JobState.FAILED, run.job.state)
            val document = run.documents.getValue(documentId)
            assertEquals(DocumentStatus.FAILED, document.status)
            assertEquals("MORE_PAGES_THAN_CONFIRMED", document.errorCode)
            val account = harness.retryExternalAccountOf(run.job.id, documentId, snapshot)
            assertEquals(0, account.distinctPages)
            assertEquals(0, account.calls)
        }
    }

    @Test
    fun `a retry resumes durable work and keeps its admitted reading snapshot`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "one good unit and one bad\n")
            val profile = harness.externalProfile()
            val snapshot = externalRetrySnapshot(profile.revision.revisionId, allowance = 1)
                .copy(externalConfirmedSourceScope = true)
            val documentId = harness.failedDocument(
                source,
                RefusingUnits(unitsBeforeRefusing = 1),
                snapshot = snapshot,
                confirmedSource = true,
            )
            val extractor = RecordingUnits(units = 2)

            val run = harness.retry(listOf(documentId), extractor, ocr = snapshot)

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(listOf("unit-1"), extractor.produced, "the compatible committed page is not repeated")
            assertEquals(listOf("unit-0"), extractor.skipped)
            val payload = RetryJobPayload.decode(run.job.payload)
            assertEquals(snapshot, payload.documentSnapshots[documentId.value] ?: payload.ocr)
            assertEquals(DocumentStatus.COMPLETE, run.documents.getValue(documentId).status)
        }
    }

    @Test
    fun `a confirmed source scope permits retry pages only while the managed bytes match`() {
        withHarness { harness ->
            val source = harness.writeText("confirmed.txt", "one committed unit\n")
            val profile = harness.externalProfile()
            val snapshot = externalRetrySnapshot(profile.revision.revisionId, allowance = 0)
                .copy(externalConfirmedSourceScope = true)
            val documentId = harness.failedDocument(
                source,
                RefusingUnits(unitsBeforeRefusing = 1),
                snapshot = snapshot,
                confirmedSource = true,
            )
            val extractor = RecordingDispatchingUnits()

            val run = harness.retry(listOf(documentId), extractor, ocr = snapshot)

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(listOf(documentId), extractor.dispatched)
            assertEquals(DocumentStatus.COMPLETE, run.documents.getValue(documentId).status)
        }
    }

    @Test
    fun `a changed managed copy is rejected before retry even with a numeric allowance`() {
        withHarness { harness ->
            val source = harness.writeText("confirmed.txt", "one committed unit\n")
            val profile = harness.externalProfile()
            val snapshot = externalRetrySnapshot(profile.revision.revisionId, allowance = 1)
            val documentId = harness.failedDocument(
                source,
                RefusingUnits(unitsBeforeRefusing = 1),
                snapshot = snapshot,
                confirmedSource = true,
            )
            Files.writeString(harness.managedOriginal(documentId), "changed managed bytes")
            val extractor = RecordingDispatchingUnits()

            val run = harness.retry(listOf(documentId), extractor, ocr = snapshot)

            assertEquals(JobState.COMPLETE, run.job.state)
            assertTrue(extractor.dispatched.isEmpty(), "mismatched managed bytes never reach the OCR engine")
            val document = run.documents.getValue(documentId)
            assertEquals(DocumentStatus.FAILED, document.status)
            assertEquals("MANAGED_COPY_CHANGED", document.errorCode)
        }
    }

    @Test
    fun `a confirmed limit of one page per document admits two documents`() {
        withHarness { harness ->
            val first = harness.failedDocument(harness.writeText("first.txt", "First page\n"), RefusingUnits(unitsBeforeRefusing = 0))
            val second = harness.failedDocument(harness.writeText("second.txt", "Second page\n"), RefusingUnits(unitsBeforeRefusing = 0))
            val profile = harness.externalProfile()
            val snapshot = externalRetrySnapshot(profile.revision.revisionId, allowance = 1)
            val extractor = RecordingDispatchingUnits()

            val run = harness.retry(listOf(first, second), extractor, ocr = snapshot)

            assertEquals(listOf(first, second), extractor.dispatched)
            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(DocumentStatus.COMPLETE, run.documents.getValue(first).status)
            assertEquals(DocumentStatus.COMPLETE, run.documents.getValue(second).status)
            assertEquals(1, harness.retryExternalAccountOf(run.job.id, first, snapshot).distinctPages)
            assertEquals(1, harness.retryExternalAccountOf(run.job.id, first, snapshot).calls)
            assertEquals(1, harness.retryExternalAccountOf(run.job.id, second, snapshot).distinctPages)
            assertEquals(1, harness.retryExternalAccountOf(run.job.id, second, snapshot).calls)
        }
    }

    @Test
    fun `bounded retries add calls without adding distinct pages`() = runBlocking {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val documentId = harness.failedDocument(source, RefusingUnits(unitsBeforeRefusing = 0))
            val recorder = RecordingImageLlmEngine(
                script = listOf(RecordedImageResponse(statusCode = 429), RecordedImageResponse(body = transcriptionEnvelope("name 128"))),
            )
            try {
                val profile = harness.externalProfile(
                    endpoint = "https://transcriber.example.invalid/v1",
                    keyVariable = "HOME",
                )
                val snapshot = externalRetrySnapshot(profile.revision.revisionId, allowance = 1)
                val extractor = RecordingLlmUnits(
                    snapshot = snapshot,
                    revision = profile.revision,
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
                assertEquals(2, recorder.requestCount)
                val account = harness.retryExternalAccountOf(run.job.id, documentId, snapshot)
                assertEquals(1, account.distinctPages)
                assertEquals(2, account.calls)
                assertEquals(DocumentStatus.COMPLETE, run.documents.getValue(documentId).status)
            } finally {
                recorder.close()
            }
        }
    }

    @Test
    fun `retry eligibility does not broaden to completed documents`() {
        // Reaching again for Retry may not widen what Retry is offered for: a finished document has no
        // unfinished reading to resume, and the statuses with unfinished work are the ones admission accepts.
        assertTrue(DocumentStatus.FAILED in RetryService.ELIGIBLE_STATUSES)
        assertTrue(DocumentStatus.CANCELLED in RetryService.ELIGIBLE_STATUSES)
        assertTrue(DocumentStatus.NEEDS_TOOL in RetryService.ELIGIBLE_STATUSES)
        assertFalse(DocumentStatus.COMPLETE in RetryService.ELIGIBLE_STATUSES)
        assertFalse(DocumentStatus.COMPLETE_WITH_WARNINGS in RetryService.ELIGIBLE_STATUSES)
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

private fun Harness.retryExternalAccountOf(
    jobId: infoscry.domain.JobId,
    documentId: DocumentId,
    snapshot: OcrSettingsSnapshot,
): infoscry.ocr.OcrExternalAccount = AppContext.open(dataDir).use { context ->
    val owner = infoscry.ocr.OcrExternalOwner.job("${jobId.value}:${documentId.value}")
    infoscry.ocr.OcrExternalAccount(
        distinctPages = context.ocrOperations.distinctPageCount(owner),
        calls = context.ocrOperations.callCount(owner),
        allowance = snapshot.externalPageLimit,
    )
}

/** One external transcription snapshot whose page count was confirmed before retry began. */
private fun externalRetrySnapshot(profileRevisionId: String, allowance: Int): OcrSettingsSnapshot = OcrSettingsSnapshot(
    engine = OcrEngine.LLM,
    mode = OcrImportMode.READ_ALL,
    language = "eng",
    extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
    transcriptionProfileRevisionId = profileRevisionId,
    externalPageLimit = allowance,
)

/**
 * A recording extractor that dispatches its page through the attempt's authority before it emits it, as the
 * external image engine does: the permit is asked and the call is counted while the page is on its way.
 * [dispatched] records the pages that were actually sent under their confirmed limits.
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
    snapshot: OcrSettingsSnapshot? = null,
    confirmedSource: Boolean = false,
): DocumentId {
    val run = if (snapshot == null) {
        importDurably(listOf(source), extractor)
    } else {
        val manifest = if (confirmedSource) {
            val bytes = Files.readAllBytes(source)
            ConfirmedImportSource(
                path = source.toRealPath().toString(),
                sizeBytes = bytes.size.toLong(),
                sha256 = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
                ),
            )
        } else {
            null
        }
        AppContext.open(dataDir).use { context ->
            val job = enqueueForTest(
                context,
                listOf(source),
                settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                ocr = snapshot,
                confirmedSources = manifest?.let(::listOf),
            )
            attach(context, storedPipeline(context, extractor))
            ImportRun(
                job = awaitJob(context, job.id),
                items = context.importItems.listForJob(job.id),
                documents = context.documents.listByCollection(CollectionId("default"), limit = 100)
                    .associateBy { it.id },
            )
        }
    }
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
