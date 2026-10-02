package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DocumentExtractor
import infoscry.extract.DOCUMENT_REFUSED_KEY
import infoscry.extract.ENCRYPTED_DOCUMENT_CODE
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.ExtractionSink
import infoscry.extract.ExtractorRegistry
import infoscry.extract.EXTRACTOR_SCHEMA_VERSION
import infoscry.extract.MediaTypeDetector
import infoscry.extract.TextualFallbackExtractor
import infoscry.extract.emitDocumentRefusal
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.E5Embedder
import infoscry.embedding.ModelManager
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.OCR_FAILED_CODE
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.ocr.CandidateRevisionSink
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrExternalAccount
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.ExternalDispatchPermitRequest
import infoscry.ocr.ImageLlmException
import infoscry.ocr.PageDispatchIdentity
import infoscry.ocr.PageImage
import infoscry.ocr.PublicationDisposition
import infoscry.ocr.ReviewerRecommendation
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import infoscry.storage.JobStore
import infoscry.storage.OcrOperationStore
import infoscry.storage.PageApproval
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What an import does with a directory of real files.
 *
 * The import path is where the archive's promises are made: the user's files are read and never touched,
 * one unreadable document does not stop the rest, and an attempt that was interrupted resumes instead of
 * starting over. These tests run the real job runner, the real managed library, and the real SQLite state
 * on a temporary data directory; only the extractors and the unit store are fakes, because the formats and
 * the durable unit store belong to their own tasks.
 */
class ImportJobHandlerTest {

    @Test
    fun `a good document is imported while an unreadable one fails without aborting the job`() {
        withHarness { harness ->
            val good = harness.writeText("good.txt", "Alpha\nBeta\n")
            val blob = harness.writeBinary("blob.bin")

            val run = harness.import(listOf(good, blob), harness.pipeline(RecordingUnits(units = 2)))

            assertEquals(JobState.COMPLETE, run.job.state)
            val byName = run.items.associateBy { Path.of(it.sourcePath).fileName.toString() }
            assertEquals(ImportItemOutcome.IMPORTED, byName.getValue("good.txt").outcome)
            assertEquals(ImportItemOutcome.FAILED, byName.getValue("blob.bin").outcome)
            assertEquals("UNSUPPORTED_MEDIA_TYPE", byName.getValue("blob.bin").errorCode)
            assertEquals(2, run.items.size)

            // The unreadable document is stored and marked with the reason, rather than pretending to be
            // usable or disappearing from the collection.
            val failedId = byName.getValue("blob.bin").documentId!!
            assertEquals(DocumentStatus.FAILED, run.documents.getValue(failedId).status)
            assertEquals("UNSUPPORTED_MEDIA_TYPE", run.documents.getValue(failedId).errorCode)
            assertTrue(Files.exists(harness.managedOriginal(failedId)))
        }
    }

    @Test
    fun `the same bytes from two paths are stored once and the second item is a duplicate`() {
        withHarness { harness ->
            val first = harness.writeText("first.txt", "identical\n")
            val second = harness.writeText("second.txt", "identical\n")

            val run = harness.import(listOf(first, second), harness.pipeline(RecordingUnits(units = 1)))

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(1, run.documents.size)
            assertEquals(
                listOf(ImportItemOutcome.IMPORTED, ImportItemOutcome.DUPLICATE),
                run.items.map { it.outcome }.sorted(),
            )
            assertEquals(1, run.items.mapNotNull { it.documentId }.distinct().size)
        }
    }

    @Test
    fun `a resumed import reuses committed units instead of doing their work again`() {
        withHarness { harness ->
            val source = harness.writeText("minutes.txt", "Ordinary text\n")

            // The first attempt commits two of its three units and then dies where a child tool would.
            val interrupted = RecordingUnits(units = 3, failProducingUnit = 2)
            val first = harness.importDurably(listOf(source), interrupted)

            assertEquals(listOf("unit-0", "unit-1"), interrupted.produced)
            assertEquals(ImportItemOutcome.FAILED, first.items.single().outcome)
            val documentId = first.items.single().documentId!!
            val firstDocument = first.documents.getValue(documentId)
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(
                    setOf("unit-0", "unit-1"),
                    context.content
                        .loadCheckpoints(
                            documentId,
                            ExtractionFingerprint.of(
                                firstDocument.sha256,
                                ExtractionSettings(ocrLanguages = "eng"),
                            ),
                        )
                        .map { it.key }
                        .toSet(),
                )
            }

            // The document is not finished, so importing the same file again resumes it rather than
            // declaring a duplicate and walking away from the unfinished extraction.
            val resumed = RecordingUnits(units = 3)
            val second = harness.importDurably(listOf(source), resumed)

            assertEquals(listOf("unit-2"), resumed.produced, "a committed unit was extracted again")
            assertEquals(listOf("unit-0", "unit-1"), resumed.skipped)
            // The bytes were already stored, so this item is a duplicate; what it did was finish them —
            // chunks, vectors and index entries included.
            assertEquals(ImportItemOutcome.DUPLICATE, second.items.single().outcome)
            assertEquals(documentId, second.items.single().documentId)
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(DocumentStatus.COMPLETE, context.documents.get(documentId)!!.status)
                assertEquals(3, context.index().chunkCount(CollectionId("default"), documentId))
            }
        }
    }

    @Test
    fun `changed extraction settings do not reuse another fingerprint's units`() {
        withHarness { harness ->
            val source = harness.writeText("report.txt", "text\n")

            // The first pass fails after committing its first unit, so the second pass has a checkpoint
            // to either reuse or refuse: a completed document would never re-enter extraction at all,
            // which would test the wrong half of the fingerprint promise.
            val first = RecordingUnits(units = 2, failProducingUnit = 1)
            harness.importDurably(listOf(source), first, settings = ExtractionSettings(ocrLanguages = "eng"))

            val changed = RecordingUnits(units = 2)
            harness.importDurably(
                listOf(source),
                changed,
                settings = ExtractionSettings(ocrLanguages = "swe+eng"),
            )

            assertEquals(
                listOf("unit-0", "unit-1"),
                changed.produced,
                "another fingerprint's unit was reused",
            )
            assertTrue(changed.skipped.isEmpty())
            assertNotEquals(first.fingerprints.single(), changed.fingerprints.single())
        }
    }

    @Test
    fun `an explicitly named symlink resolves once and a directory walk does not follow one`() {
        withHarness { harness ->
            val real = harness.writeText("real.txt", "linked\n")
            val outside = harness.writeText("outside/elsewhere.txt", "outside\n")
            val linkedFile = harness.sourcesDir.resolve("linked.txt")
            Files.createSymbolicLink(linkedFile, real)
            val tree = Files.createDirectories(harness.sourcesDir.resolve("tree"))
            Files.createSymbolicLink(tree.resolve("through.txt"), outside)

            val run = harness.import(listOf(linkedFile, tree), harness.pipeline(RecordingUnits(units = 1)))

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(listOf(real.toRealPath().toString()), run.items.map { it.sourcePath })
            assertEquals(1, run.documents.size)
        }
    }

    @Test
    fun `each file is queued once, in a stable order`() {
        withHarness { harness ->
            harness.writeText("charlie.txt", "c\n")
            harness.writeText("alpha.txt", "a\n")
            harness.writeText("bravo.txt", "b\n")

            val run = harness.import(
                listOf(harness.sourcesDir.resolve("alpha.txt"), harness.sourcesDir),
                harness.pipeline(RecordingUnits(units = 1)),
            )

            assertEquals(
                listOf("alpha.txt", "bravo.txt", "charlie.txt"),
                run.items.map { Path.of(it.sourcePath).fileName.toString() },
            )
            assertEquals(3, run.items.map { it.itemKey }.distinct().size)
        }
    }

    @Test
    fun `the file an import names advances with the files it works through`() {
        withHarness { harness ->
            val first = harness.writeText("alpha.txt", "first\n")
            val second = harness.writeText("bravo.txt", "second\n")

            val run = harness.import(listOf(first, second), harness.pipeline(RecordingUnits(units = 1)))

            assertEquals(JobState.COMPLETE, run.job.state)
            // The files are worked through in their stable order, so the last one named is the last one
            // read, and what it names is the file's own name rather than the path it was selected from.
            assertEquals("bravo.txt", run.job.currentItem)
            assertTrue(
                !run.job.currentItem!!.contains("/"),
                "the import must report a file's name, never the path it was selected from",
            )
        }
    }

    @Test
    fun `a path with another platform's separator still names only the file`() {
        withHarness { harness ->
            // A Windows-style path typed into the manual fallback: on macOS it is one segment, so a name
            // taken from the platform's own separator rules would cross as the whole path.
            val pasted = Path.of("C:\\Users\\someone\\private\\report.mobi")

            val run = harness.import(listOf(pasted), harness.pipeline(RecordingUnits(units = 1)))

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals("report.mobi", run.job.currentItem)
        }
    }

    @Test
    fun `an import names the file it is working on while it works on it`() {
        withHarness { harness ->
            val source = harness.writeText("report.txt", "Ordinary text\n")
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(source))
                harness.attach(context, harness.storedPipeline(context, HeldUnits(parked, release)))

                // The extractor is parked inside this file, so the durable row is read in the middle of
                // the file's own work rather than at the end of the attempt.
                runBlocking { withTimeout(PARK_TIMEOUT_MILLIS) { parked.await() } }
                assertEquals("report.txt", context.jobs.get(job.id)!!.currentItem)

                release.complete(Unit)
                assertEquals(JobState.COMPLETE, harness.awaitJob(context, job.id).state)
            }
        }
    }

    @Test
    fun `a directory import reads only its top level unless recursion is asked for`() {
        withHarness { harness ->
            harness.writeText("top.txt", "top\n")
            harness.writeText("nested/deep.txt", "deep\n")

            // Without the flag the nested file must not appear at all: importing a directory is now
            // top-level-only unless the caller says to descend into subdirectories.
            val topOnly = harness.import(
                listOf(harness.sourcesDir),
                harness.pipeline(RecordingUnits(units = 1)),
            )
            assertEquals(
                listOf("top.txt"),
                topOnly.items.map { Path.of(it.sourcePath).fileName.toString() },
            )

            // With the flag the whole tree is imported; the nested file is simply another item.
            val recursive = harness.import(
                listOf(harness.sourcesDir),
                harness.pipeline(RecordingUnits(units = 1)),
                recursive = true,
            )
            assertEquals(
                listOf("deep.txt", "top.txt"),
                recursive.items.map { Path.of(it.sourcePath).fileName.toString() },
            )
        }
    }

    @Test
    fun `an import whose payload names a missing collection fails the job instead of importing blindly`() {
        withHarness { harness ->
            val source = harness.writeText("orphan.txt", "text\n")

            val run = harness.import(
                listOf(source),
                harness.pipeline(RecordingUnits(units = 1)),
                collectionId = CollectionId("no-such-collection"),
            )

            assertEquals(JobState.FAILED, run.job.state)
            assertEquals("INVALID_REQUEST", run.job.errorCode)
            assertTrue(run.items.isEmpty())
            assertTrue(run.documents.isEmpty())
        }
    }

    @Test
    fun `a source that vanished before the attempt ran is reported per item`() {
        withHarness { harness ->
            val kept = harness.writeText("kept.txt", "text\n")
            val vanished = harness.sourcesDir.resolve("vanished.txt")
            Files.writeString(vanished, "gone soon\n")
            Files.delete(vanished)

            val run = harness.import(listOf(kept, vanished), harness.pipeline(RecordingUnits(units = 1)))

            assertEquals(JobState.COMPLETE, run.job.state)
            val byName = run.items.associateBy { Path.of(it.sourcePath).fileName.toString() }
            assertEquals(ImportItemOutcome.IMPORTED, byName.getValue("kept.txt").outcome)
            assertEquals(ImportItemOutcome.FAILED, byName.getValue("vanished.txt").outcome)
            assertEquals("SOURCE_MISSING", byName.getValue("vanished.txt").errorCode)
            assertEquals(setOf("kept.txt", "vanished.txt"), byName.keys)
        }
    }

    @Test
    fun `a restarted import finishes from the managed copy once the source file is gone`() {
        withHarness { harness ->
            val source = harness.writeText("moved.txt", "Ordinary text\n")
            val canonical = source.toRealPath()
            val parked = CompletableDeferred<Unit>()

            // The first attempt stores the bytes, commits its first unit, and dies while the next one is
            // still being worked on: the state a machine that loses power leaves behind.
            val jobId = harness.interruptDurably(listOf(canonical), ParkedUnits(parked), parked)

            val interrupted = AppContext.open(harness.dataDir).use { it.importItems.listForJob(jobId) }
            val stored = interrupted.single().documentId
            assertEquals(ImportItemOutcome.PENDING, interrupted.single().outcome)
            assertTrue(stored != null, "the managed document is recorded before extraction starts")

            // The user then moves the file away, so the managed copy is the only place these bytes exist.
            // The interrupted job already points at that document, which is what makes finishing it possible
            // instead of reporting a source that is simply missing.
            Files.delete(canonical)

            val resumed = RecordingUnits(units = 3)
            val run = harness.resumeDurably(jobId, resumed)

            assertEquals(JobState.COMPLETE, run.job.state)
            assertEquals(ImportItemOutcome.DUPLICATE, run.items.single().outcome)
            assertEquals(stored, run.items.single().documentId)
            assertNotEquals("SOURCE_MISSING", run.items.single().errorCode)
            assertEquals(listOf("unit-1", "unit-2"), resumed.produced)
            assertEquals(listOf("unit-0"), resumed.skipped)
            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.get(stored)!!
                assertEquals(DocumentStatus.COMPLETE, document.status)
                assertEquals(3, context.index().chunkCount(CollectionId("default"), stored))
                assertEquals(
                    setOf("unit-0", "unit-1", "unit-2"),
                    context.content
                        .loadCheckpoints(
                            stored,
                            ExtractionFingerprint.of(document.sha256, ExtractionSettings(ocrLanguages = "eng")),
                        )
                        .map { it.key }
                        .toSet(),
                )
            }
        }
    }

    @Test
    fun `an import with no durable unit store leaves the document extracting rather than complete`() {
        withHarness { harness ->
            val source = harness.writeText("pending.txt", "text\n")
            val extractor = RecordingUnits(units = 2)

            val run = harness.import(listOf(source), harness.pipeline(extractor))

            // This is the state production produces today: the bytes are stored and the item reads as
            // imported, while the document is deliberately not called complete. A script that sees exit 0
            // has to be able to tell that from a searchable document, which is what this pins. The extractor
            // is not run either, because a store that cannot keep its output would only spend OCR time.
            val item = run.items.single()
            assertEquals(ImportItemOutcome.IMPORTED, item.outcome)
            assertEquals(DocumentStatus.EXTRACTING, run.documents.getValue(item.documentId!!).status)
            assertTrue(extractor.produced.isEmpty(), "extraction ran with nowhere to store its units")
        }
    }

    @Test
    fun `a file that cannot be copied fails its own item without stopping the import`() {
        withHarness { harness ->
            assertTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "this test makes a file unreadable with a POSIX mode",
            )
            val readable = harness.writeText("readable.txt", "text\n")
            val unreadable = harness.writeText("unreadable.txt", "private\n")
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"))

            val run = harness.import(listOf(readable, unreadable), harness.pipeline(RecordingUnits(units = 1)))

            // A copy failure is that file's result, not the job's: the import reaches its end and the file
            // that could not be read is reported with a code and a message the user can act on.
            assertEquals(JobState.COMPLETE, run.job.state)
            val byName = run.items.associateBy { Path.of(it.sourcePath).fileName.toString() }
            assertEquals(ImportItemOutcome.IMPORTED, byName.getValue("readable.txt").outcome)
            val failed = byName.getValue("unreadable.txt")
            assertEquals(ImportItemOutcome.FAILED, failed.outcome)
            assertEquals("SOURCE_UNREADABLE", failed.errorCode)
            assertTrue(!failed.errorMessage.isNullOrBlank(), "a failed item carries a message for the user")
        }
    }

    @Test
    fun `a finished extraction indexes its chunks and only then completes the document`() {
        withHarness { harness ->
            val source = harness.writeText("minutes.txt", "Ordinary text\n")

            val run = harness.importDurably(listOf(source), RecordingUnits(units = 3))

            val item = run.items.single()
            assertEquals(ImportItemOutcome.IMPORTED, item.outcome)
            val documentId = item.documentId!!
            val collectionId = CollectionId("default")
            // Reading it back through a fresh process is the point: text, chunks, vectors and index entries
            // are durable state, not the attempt's memory.
            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.get(documentId)!!
                assertEquals(DocumentStatus.COMPLETE, document.status)
                val units = context.content.listUnits(documentId, afterOrdinal = -1, limit = 10)
                assertEquals(listOf(0, 1, 2), units.map { it.ordinal })
                assertEquals("unit 0", units.first().extractedText)
                assertTrue(context.content.chunkCount(documentId) >= 3, "every unit produced chunks")
                assertEquals(3, context.index().chunkCount(collectionId, documentId), "every chunk is searchable")
                val hits = context.index().searchKeyword(collectionId, "unit", limit = 10)
                assertEquals(3, hits.size)
                assertEquals(documentId.value, hits.first().documentId)
                assertEquals(
                    setOf("unit-0", "unit-1", "unit-2"),
                    context.content
                        .loadCheckpoints(
                            documentId,
                            ExtractionFingerprint.of(document.sha256, ExtractionSettings(ocrLanguages = "eng")),
                        )
                        .map { it.key }
                        .toSet(),
                )
                assertNotNull(
                    context.content.extractionMarker(documentId),
                    "a pass that reported it finished leaves a marker",
                )
            }
        }
    }

    @Test
    fun `an import sends nothing before its external scope is approved and waits for one`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val profile = harness.externalProfile()
            // No page is allowed without an approval: the attempt has a scope, and it is spent before it starts.
            val snapshot = harness.externalSnapshot(profile = profile, allowance = 0)
            val dispatcher = DispatchingUnits(text = "read by the model")

            val waiting = harness.importWithOcr(listOf(source), dispatcher, snapshot)

            // Nothing was sent, and the job says what it waits for: the refusal comes first because that is
            // the promise the allowance makes.
            assertEquals(0, dispatcher.sent, "a page left this machine before its scope was approved")
            assertEquals(0, harness.externalAccountOf(waiting.job.id, snapshot).distinctPages)
            assertEquals(JobState.COMPLETE, waiting.job.state)
            assertEquals(
                JobStore.AWAITING_APPROVAL_STAGE,
                waiting.job.stage,
                "an import that may not dispatch has to say what it waits for",
            )
            assertEquals(
                0,
                waiting.items.count { it.outcome == ImportItemOutcome.FAILED },
                "a job waiting for an approval is not a job whose file failed",
            )

            // The approval is the scope this job was admitted with, and it puts the same job back in the queue.
            harness.approveJobScope(waiting.job.id, snapshot, maxDistinctPages = 1)
            val resumed = harness.resumeWaitingImport(waiting.job.id, dispatcher)

            assertEquals(1, dispatcher.sent, "the approved page was never sent")
            val account = harness.externalAccountOf(resumed.job.id, snapshot)
            assertEquals(1, account.distinctPages)
            assertEquals(1, account.calls)
            // The file this attempt had already copied is a duplicate of its own earlier work when it is read
            // again, and it is read: what the wait cost the person is time, not the reading.
            assertEquals(DocumentStatus.COMPLETE, resumed.documents.values.single().status)
            assertEquals(0, resumed.items.count { it.outcome == ImportItemOutcome.FAILED })
        }
    }

    @Test
    fun `one allowance covers two files of one job and pauses before the page past it`() {
        withHarness { harness ->
            val first = harness.writeText("first.txt", "First\n")
            val second = harness.writeText("second.txt", "Second\n")
            val profile = harness.externalProfile()
            // One distinct page: the allowance belongs to the job, so the second file's page is the one
            // that would exceed it.
            val snapshot = harness.externalSnapshot(profile = profile, allowance = 1)
            val dispatcher = DispatchingUnits(text = "read by the model")

            val limited = harness.importWithOcr(listOf(first, second), dispatcher, snapshot)

            // The bound is the assertion: one page may leave, and the page that would be the second does not.
            assertEquals(1, dispatcher.sent, "the page past the allowance was sent")
            val sent = harness.externalAccountOf(limited.job.id, snapshot)
            assertEquals(1, sent.distinctPages)
            assertEquals(1, sent.calls)
            assertEquals(JobStore.AWAITING_APPROVAL_STAGE, limited.job.stage)
            assertEquals(
                1,
                limited.items.count { it.outcome == ImportItemOutcome.IMPORTED },
                "the file whose page was approved was imported",
            )

            harness.approveJobScope(limited.job.id, snapshot, maxDistinctPages = 2)
            val resumed = harness.resumeWaitingImport(limited.job.id, dispatcher)

            // The second file's page is a different page, counted once, and the call count follows it: the
            // two counters answer two different questions.
            assertEquals(2, dispatcher.sent)
            val account = harness.externalAccountOf(resumed.job.id, snapshot)
            assertEquals(2, account.distinctPages)
            assertEquals(2, account.calls)
            assertEquals(2, resumed.documents.size)
            assertTrue(resumed.documents.values.all { document -> document.status == DocumentStatus.COMPLETE })
            assertEquals(0, resumed.items.count { it.outcome == ImportItemOutcome.FAILED })
        }
    }

    @Test
    fun `an import admitted with check-and-improve reads with the collection's OCR selection`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            // What the route does at admission: the collection's selection is resolved into the attempt's
            // snapshot, and the extraction settings that travel in the payload are derived from it.
            val snapshot = OcrSettingsSnapshot(
                engine = OcrEngine.SURYA,
                mode = OcrImportMode.CHECK_AND_IMPROVE,
                language = "eng",
                extractorVersion = EXTRACTOR_SCHEMA_VERSION,
            )
            val settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot)
            assertEquals(OcrImportMode.CHECK_AND_IMPROVE, settings.ocrMode)
            assertEquals(OcrEngine.SURYA, settings.ocrAttempt?.engine)

            val extractor = ModeRecordingUnits()
            val run = harness.importDurably(listOf(source), extractor, settings = settings)

            assertEquals(ImportItemOutcome.IMPORTED, run.items.single().outcome)
            assertEquals(OcrImportMode.CHECK_AND_IMPROVE, extractor.mode, "the mode has to reach the extraction")
            assertEquals(OcrEngine.SURYA, extractor.engine, "the engine has to be the one that was selected")
        }
    }

    @Test
    fun `a check-and-improve import stages its pages as a candidate and leaves published content alone`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            // What admission does: the collection's check-and-improve selection becomes the attempt's settings.
            val settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(
                OcrSettingsSnapshot(
                    engine = OcrEngine.SURYA,
                    mode = OcrImportMode.CHECK_AND_IMPROVE,
                    language = "eng",
                    extractorVersion = EXTRACTOR_SCHEMA_VERSION,
                ),
            )
            var staged: CandidateRevisionSink? = null

            val run = harness.importStaging(listOf(source), RecordingUnits(units = 2), settings) { staged = it }

            assertEquals(ImportItemOutcome.IMPORTED, run.items.single().outcome)
            val document = run.documents.values.single()
            // A reading nobody has decided about is not a finished document: it owes a person an answer, and
            // a document waiting for one is not complete.
            assertEquals(DocumentStatus.NEEDS_REVIEW, document.status)

            val candidate = assertNotNull(assertNotNull(staged).candidateRevisionId, "the reading was staged")
            AppContext.open(harness.dataDir).use { context ->
                val pages = context.revisions.pages(candidate)
                assertEquals(listOf(0, 1), pages.map { it.ordinal }, "every page the attempt read is staged")
                assertTrue(pages.all { page -> page.approval == PageApproval.PENDING })

                // Nothing of the reading reached what the document publishes or what a search reads: no
                // content unit, no chunk, no index entry, and no revision the document would serve.
                assertTrue(
                    context.content.listUnits(document.id, -1, 10).isEmpty(),
                    "a staged reading must not commit content units",
                )
                assertEquals(0, context.content.chunkCount(document.id))
                assertEquals(0, context.index().chunkCount(CollectionId("default"), document.id))
                assertNull(context.revisions.activeRevisionId(document.id))
            }
        }
    }

    @Test
    fun `a check-and-improve import records a pending review for a page its reading disagrees with`() = runBlocking {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val reviewer = FakeOpenAiServer(
                listOf(FakeOpenAiResponse(body = reviewEnvelope(reviewAnswer("B_BETTER")))),
            )
            try {
                val snapshot = reviewingSnapshot(harness.reviewProfile(reviewer.url).revision.revisionId)

                val run = harness.importStaging(
                    sources = listOf(source),
                    extractor = CheckAndImprovePages(text = "name 123", reading = "name 128"),
                    settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                    ocr = snapshot,
                )

                val document = run.documents.values.single()
                assertEquals(
                    DocumentStatus.NEEDS_REVIEW,
                    document.status,
                    "the reading was staged and judged: ${document.errorCode} ${document.errorMessage}",
                )
                AppContext.open(harness.dataDir).use { context ->
                    val review = context.ocrReviews.pending(document.id).single()
                    // The page's two readings differ, so a person has to decide which one stands — pilot mode
                    // proposes, whatever the reviewer answered.
                    assertEquals(PublicationDisposition.PROPOSE, review.disposition)
                    assertEquals(ReviewerRecommendation.NEW_BETTER, review.recommendation)
                    assertEquals(
                        "page:1",
                        review.unitId,
                        "the review names the page its reading was made from, the identity the reading dispatched under",
                    )
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
    fun `a check-and-improve import publishes the pages it approved and leaves the rest pending`() = runBlocking {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val reviewer = FakeOpenAiServer(
                listOf(FakeOpenAiResponse(body = reviewEnvelope(reviewAnswer("B_BETTER")))),
            )
            try {
                val snapshot = reviewingSnapshot(harness.reviewProfile(reviewer.url).revision.revisionId)

                val run = harness.importStaging(
                    sources = listOf(source),
                    extractor = CheckAndImproveTwoPages(
                        matchingText = "approved page text",
                        differingText = "the page says one thing",
                        differingReading = "the engine read another",
                    ),
                    settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                    ocr = snapshot,
                )

                // A reading whose pages are not all decided is neither finished nor failed: what it publishes
                // is the part nobody has to decide about, and what it owes is a person's answer about the rest.
                val document = run.documents.values.single()
                assertEquals(DocumentStatus.NEEDS_REVIEW, document.status)
                assertEquals(ImportItemOutcome.IMPORTED, run.items.single().outcome)
                assertEquals(JobState.COMPLETE, run.job.state)

                AppContext.open(harness.dataDir).use { context ->
                    val candidate = assertNotNull(
                        context.revisions.activeRevisionId(document.id),
                        "an initial import publishes what it approved rather than waiting for every page",
                    )
                    assertEquals(
                        listOf(PageApproval.APPROVED, PageApproval.PENDING),
                        context.revisions.pages(candidate).map { page -> page.approval },
                        "the page whose reading is the text it carries is approved; the other owes a decision",
                    )

                    // The approved page is the document's text and a reader can find it...
                    assertEquals(
                        listOf(0),
                        context.content.listUnits(document.id, -1, 10).map { unit -> unit.ordinal },
                        "only the approved page becomes content",
                    )
                    val hits = searchable(context, "approved")
                    assertEquals(listOf("approved page text"), hits.map { hit -> hit.text })
                    assertEquals(candidate, hits.single().revisionId, "the published text names its revision")

                    // ...while the page nobody has decided about has no searchable text at all: not what the
                    // engine read, and not what the page itself says, until a person decides which of the two
                    // stands. Its image and its review are what a person works from instead.
                    assertTrue(
                        searchable(context, "engine").isEmpty(),
                        "the reading of a page awaiting a decision was published",
                    )
                    assertTrue(
                        searchable(context, "says").isEmpty(),
                        "a page awaiting a decision published one of its readings anyway",
                    )
                    val review = context.ocrReviews.pending(document.id).single()
                    assertEquals("page:2", review.unitId)
                    assertEquals(PublicationDisposition.PROPOSE, review.disposition)
                }
                // Two pages, one comparison: the pair that says the same thing is sent to nobody.
                assertEquals(1, reviewer.handledRequests)
            } finally {
                reviewer.close()
            }
        }
    }

    /** What a reader searching this collection finds, under the revision snapshot a reader is served. */
    private fun searchable(context: AppContext, query: String) = context.search.search(
        query,
        SearchMode.KEYWORD,
        SearchFilters(collectionId = CollectionId("default")),
    ).hits

    @Test
    fun `a check-and-improve import whose reading matches the page's own text records no review`() = runBlocking {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            val reviewer = FakeOpenAiServer(
                listOf(FakeOpenAiResponse(body = reviewEnvelope(reviewAnswer("B_BETTER")))),
            )
            try {
                val snapshot = reviewingSnapshot(harness.reviewProfile(reviewer.url).revision.revisionId)

                val run = harness.importStaging(
                    sources = listOf(source),
                    extractor = CheckAndImprovePages(text = "name 123", reading = "name 123"),
                    settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                    ocr = snapshot,
                )

                AppContext.open(harness.dataDir).use { context ->
                    assertTrue(
                        context.ocrReviews.pending(run.documents.values.single().id).isEmpty(),
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
    fun `a fill-missing import through the same pipeline still commits through the stored units`() {
        withHarness { harness ->
            val source = harness.writeText("plain.txt", "Ordinary text\n")
            var staged: CandidateRevisionSink? = null

            val run = harness.importStaging(
                sources = listOf(source),
                extractor = RecordingUnits(units = 2),
                settings = ExtractionSettings(ocrLanguages = "eng"),
            ) { staged = it }

            assertNull(staged, "a fill-missing attempt has no candidate to stage into")
            assertEquals(ImportItemOutcome.IMPORTED, run.items.single().outcome)
            val document = run.documents.values.single()
            assertEquals(DocumentStatus.COMPLETE, document.status)
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(
                    listOf(0, 1),
                    context.content.listUnits(document.id, -1, 10).map { it.ordinal },
                    "the reading is committed as the document's content units",
                )
                assertEquals(2, context.index().chunkCount(CollectionId("default"), document.id))
            }
        }
    }

    @Test
    fun `an attempt reads under the runtime identity the job was admitted with, not one probed later`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "Ordinary text\n")
            // What admission recorded: the runtime the reading engine reported when the job was created.
            val admitted = OcrSettingsSnapshot(
                engine = OcrEngine.SURYA,
                mode = OcrImportMode.CHECK_AND_IMPROVE,
                language = "eng",
                extractorVersion = EXTRACTOR_SCHEMA_VERSION,
                runtimeIdentity = "surya-ocr 0.22.1 backend llamacpp",
            )
            val settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(admitted)
            assertEquals(admitted.runtimeIdentity, settings.ocrAttempt?.runtimeIdentity)

            // The first attempt dies where a killed process does, after committing two of its three units,
            // and its reader would answer another runtime if it were ever asked.
            val interrupted = RecordingUnits(units = 3, failProducingUnit = 2).also {
                it.runtimeIdentityAnswer = "surya-ocr 0.23.0"
            }
            harness.importDurably(listOf(source), interrupted, settings = settings)
            assertEquals(listOf("unit-0", "unit-1"), interrupted.produced)
            assertEquals(0, interrupted.probes, "the attempt probed the reader for a runtime it was admitted with")

            // The attempt that follows is handed the payload's own settings. Another Surya version installed
            // in between is *not* this attempt's runtime: the fingerprint is the key the committed pages are
            // looked up by, and the admitted runtime's key is the one they were written under.
            val resumed = RecordingUnits(units = 3).also { it.runtimeIdentityAnswer = "surya-ocr 0.24.0" }
            val second = harness.importDurably(listOf(source), resumed, settings = settings)

            assertEquals(0, resumed.probes, "a probe replaced the runtime the job was admitted with")
            assertEquals(interrupted.fingerprints.single(), resumed.fingerprints.single())
            assertEquals(listOf("unit-0", "unit-1"), resumed.skipped, "the admitted runtime's work was not reused")
            assertEquals(listOf("unit-2"), resumed.produced)
            val document = second.documents.values.single()
            assertEquals(DocumentStatus.COMPLETE, document.status)

            // And the identity that was used is the admitted one: had the attempt discovered a new runtime,
            // its key would have been this one and the reuse above could not have happened.
            assertNotEquals(
                resumed.fingerprints.single(),
                ExtractionFingerprint.of(
                    document.sha256,
                    ExtractionSettings(ocrLanguages = "eng")
                        .forOcrSettings(admitted.copy(runtimeIdentity = "surya-ocr 0.23.0")),
                ),
            )
        }
    }

    @Test
    fun `a reading that still owes a person a decision is review-pending, not complete`() {
        withHarness { harness ->
            val clean = harness.writeText("clean.txt", "Ordinary text\n")

            val run = harness.importAwaitingDecision(listOf(clean), RecordingUnits(units = 2))

            // Every unit was read and the document finished its pass, so it is neither failed nor waiting for
            // a tool: what it owes is a person's decision about a page the reading proposed.
            assertEquals(DocumentStatus.NEEDS_REVIEW, run.documents.values.single().status)
            assertEquals(ImportItemOutcome.IMPORTED, run.items.single().outcome)
            assertEquals(JobState.COMPLETE, run.job.state)
        }
    }

    @Test
    fun `a pending decision outranks a unit that could not be read`() {
        withHarness { harness ->
            val mixed = harness.writeText("mixed.txt", "Ordinary text\n")

            val run = harness.importAwaitingDecision(
                listOf(mixed),
                FailedUnitsThenFinish(goodUnits = 1, failedUnits = 1),
            )

            // The question a reader has to answer first is whether anybody accepted the reading, so a
            // document with both a proposal and a failed unit is review-pending; the failure stays on record
            // per unit rather than being dropped.
            assertEquals(DocumentStatus.NEEDS_REVIEW, run.documents.values.single().status)
        }
    }

    @Test
    fun `an unchanged collection keeps the legacy reading identity`() {
        // A collection that still selects Tesseract and fill-missing adds nothing to the extent settings, which
        // is what keeps the extraction fingerprint of an unchanged archive byte-identical to what it was before
        // OCR engines existed: committed checkpoints stay reusable rather than being read again.
        val snapshot = OcrSettingsSnapshot(
            engine = OcrEngine.TESSERACT,
            mode = OcrImportMode.FILL_MISSING,
            language = "eng",
            extractorVersion = EXTRACTOR_SCHEMA_VERSION,
        )

        val settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot)

        assertNull(settings.ocrAttempt)
        assertEquals(OcrImportMode.FILL_MISSING, settings.ocrMode)
        assertEquals(
            ExtractionFingerprint.of("a".repeat(64), ExtractionSettings(ocrLanguages = "eng")),
            ExtractionFingerprint.of("a".repeat(64), settings),
        )
    }

    @Test
    fun `a document whose finished pass had failed units completes with warnings and stays searchable`() {
        withHarness { harness ->
            val source = harness.writeText("mixed.txt", "Ordinary text\n")

            val run = harness.importDurably(listOf(source), FailedUnitsThenFinish(goodUnits = 2, failedUnits = 1))

            val item = run.items.single()
            assertEquals(ImportItemOutcome.IMPORTED, item.outcome)
            val documentId = item.documentId!!
            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.get(documentId)!!
                // The extraction marker names the failed unit, and the pass still finished, so the document
                // is searchable with a warning rather than failed or silently complete.
                assertEquals(DocumentStatus.COMPLETE_WITH_WARNINGS, document.status)
                assertEquals(1, context.content.extractionMarker(documentId)?.failedUnits)
                assertEquals(2, context.index().chunkCount(CollectionId("default"), documentId))
            }
        }
    }

    @Test
    fun `a missing model fails the document at embedding with the install remedy`() {
        withHarness { harness ->
            val source = harness.writeText("missing.txt", "Ordinary text\n")

            val jobId = AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(source.toRealPath()))
                // The real embedder supplier against an empty models directory answers null, which is what
                // makes the failure a per-document remedy rather than a startup crash.
                ImportJobHandler.attachTo(
                    context,
                    harness.storedPipeline(context, RecordingUnits(units = 1)),
                    documentEmbedder = E5Embedder.productionDocumentEmbedder(
                        modelsDir = context.paths.modelsDir,
                        profileDirectory = context.paths.embeddingProfileDir,
                    ),
                )
                harness.awaitJob(context, job.id)
                job.id
            }

            AppContext.open(harness.dataDir).use { context ->
                val item = context.importItems.listForJob(jobId).single()
                assertEquals(ImportItemOutcome.FAILED, item.outcome)
                assertEquals(ModelManager.MODEL_NOT_INSTALLED_CODE, item.errorCode)
                assertTrue(
                    item.errorMessage.orEmpty().contains("embeddingModel"),
                    "the remedy must name the install action, was ${item.errorMessage}",
                )
                assertEquals(DocumentStatus.FAILED, context.documents.get(item.documentId!!)!!.status)
            }
        }
    }

    @Test
    fun `a document over the embedding ceiling is refused before any embedding`() {
        withHarness { harness ->
            val source = harness.writeText("huge.txt", "x\n")
            var embedCalls = 0
            val countingEmbedder = object : DocumentEmbedder {
                override fun embedDocuments(texts: List<String>): List<FloatArray> {
                    embedCalls++
                    return TestDocumentEmbedder().embedDocuments(texts)
                }
            }

            // 4 units -> 4 chunks, over the test ceiling of 3.
            val run = harness.importDurably(
                sources = listOf(source),
                extractor = RecordingUnits(units = 4),
                embedder = countingEmbedder,
                maxChunksPerDocument = 3,
            )

            val item = run.items.single()
            assertEquals(ImportItemOutcome.FAILED, item.outcome)
            assertEquals("INDEX_TOO_LARGE", item.errorCode)
            assertTrue(
                item.errorMessage.orEmpty().contains("chunks"),
                "the message names the measured property, was ${item.errorMessage}",
            )
            assertEquals(0, embedCalls, "an over-ceiling document is refused before any embedding work")
            val documentId = item.documentId!!
            assertEquals(DocumentStatus.FAILED, run.documents.getValue(documentId).status)
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(0, context.index().chunkCount(CollectionId("default"), documentId))
            }
        }
    }

    @Test
    fun `a document refused over the ceiling resumes from its persisted chunks once the ceiling is raised`() {
        withHarness { harness ->
            val source = harness.writeText("resume-after-ceiling.txt", "x\n")

            // Run 1: 4 units -> 4 chunks, refused at a ceiling of 3 before any embedding. The chunks are
            // persisted before the refusal: that is what the raised-ceiling re-run re-embeds.
            val run1Extractor = RecordingUnits(units = 4)
            val run1 = harness.importDurably(
                sources = listOf(source),
                extractor = run1Extractor,
                maxChunksPerDocument = 3,
            )
            val item1 = run1.items.single()
            val documentId = item1.documentId!!
            assertEquals(ImportItemOutcome.FAILED, item1.outcome)
            assertEquals("INDEX_TOO_LARGE", item1.errorCode)
            assertEquals(DocumentStatus.FAILED, run1.documents.getValue(documentId).status)
            assertEquals(4, run1Extractor.produced.size, "run 1 must extract all four units for the resume to have them")
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(0, context.index().chunkCount(CollectionId("default"), documentId))
            }

            // Run 2: the same file against the same durable state ("a new process over the same data
            // directory"), with the ceiling raised to 4. It must attach to the same failed document via its
            // stored managed copy, skip every already-committed unit, and finish embedding from the
            // persisted chunks -- no new extraction, no repeated OCR.
            var embedCallsRun2 = 0
            val countingEmbedder = object : DocumentEmbedder {
                override fun embedDocuments(texts: List<String>): List<FloatArray> {
                    embedCallsRun2++
                    return TestDocumentEmbedder().embedDocuments(texts)
                }
            }
            val run2Extractor = RecordingUnits(units = 4)
            val run2 = harness.importDurably(
                sources = listOf(source),
                extractor = run2Extractor,
                maxChunksPerDocument = 4,
                embedder = countingEmbedder,
            )

            val item2 = run2.items.single()
            assertEquals(documentId, item2.documentId, "the re-run must finish the same document")
            assertEquals(ImportItemOutcome.DUPLICATE, item2.outcome, "the bytes were already in the managed library")
            assertEquals(DocumentStatus.COMPLETE, run2.documents.getValue(documentId).status)
            assertTrue(run2Extractor.produced.isEmpty(), "the re-run must not re-extract committed units")
            assertEquals(4, run2Extractor.skipped.size, "every committed unit must be reused from its checkpoint")
            assertEquals(4, embedCallsRun2, "the re-run embeds each unit's persisted chunk exactly once (one call per unit)")
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(4, context.index().chunkCount(CollectionId("default"), documentId))
            }
        }
    }

    @Test
    fun `a document at the embedding ceiling is indexed normally`() {
        withHarness { harness ->
            val source = harness.writeText("just-under.txt", "x\n")

            // 3 units -> 3 chunks, exactly at the test ceiling of 3.
            val run = harness.importDurably(
                sources = listOf(source),
                extractor = RecordingUnits(units = 3),
                maxChunksPerDocument = 3,
            )

            val item = run.items.single()
            assertEquals(ImportItemOutcome.IMPORTED, item.outcome)
            val documentId = item.documentId!!
            assertEquals(DocumentStatus.COMPLETE, run.documents.getValue(documentId).status)
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(3, context.index().chunkCount(CollectionId("default"), documentId))
            }
        }
    }

    @Test
    fun `a document refused before its first unit fails and stores nothing`() {
        withHarness { harness ->
            val source = harness.writeText("secret.txt", "not readable\n")

            val run = harness.importDurably(listOf(source), RefusingUnits(unitsBeforeRefusing = 0))

            val item = run.items.single()
            assertEquals(ImportItemOutcome.FAILED, item.outcome)
            assertEquals(ENCRYPTED_DOCUMENT_CODE, item.errorCode)
            assertTrue(
                item.errorMessage!!.contains("decrypt"),
                "the message has to name the remedy, was ${item.errorMessage}",
            )
            val documentId = item.documentId!!
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(DocumentStatus.FAILED, context.documents.get(documentId)!!.status)
                assertTrue(context.content.listUnits(documentId, -1, 10).isEmpty())
                assertNull(context.content.extractionMarker(documentId), "a refusal is not a finished pass")
            }
        }
    }

    @Test
    fun `a document that stops after some units keeps them and still fails`() {
        withHarness { harness ->
            val source = harness.writeText("partial.txt", "half readable\n")

            val run = harness.importDurably(listOf(source), RefusingUnits(unitsBeforeRefusing = 2))

            val item = run.items.single()
            assertEquals(ImportItemOutcome.FAILED, item.outcome)
            assertEquals(ENCRYPTED_DOCUMENT_CODE, item.errorCode)
            val documentId = item.documentId!!
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(DocumentStatus.FAILED, context.documents.get(documentId)!!.status)
                // What was read before the refusal is evidence and stays: a page that could be read is still
                // readable, whatever the next page turned out to be.
                assertEquals(listOf(0, 1), context.content.listUnits(documentId, -1, 10).map { it.ordinal })
                assertNull(context.content.extractionMarker(documentId))
            }
        }
    }

    @Test
    fun `a page read by ocr says so, and says so only once it is committed`() {
        withHarness { harness ->
            val source = harness.writeText("scan.txt", "scanned page\n")
            val parked = CompletableDeferred<Unit>()

            // The attempt announces its total, commits its first page -- read by a tool -- and then parks.
            AppContext.open(harness.dataDir).use { context ->
                harness.enqueueForTest(context, listOf(source.toRealPath()))
                harness.attach(
                    context,
                    harness.storedPipeline(
                        context,
                        ParkedUnits(parked, units = 3, method = ExtractionMethod.OCR, announces = true),
                    ),
                )
                runBlocking { withTimeout(PARK_TIMEOUT_MILLIS) { parked.await() } }
            }

            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.listByCollection(CollectionId("default"), limit = 10).single()
                assertEquals(
                    DocumentStatus.OCR,
                    document.status,
                    "the tool-read page is durable before the document claims to be reading with OCR",
                )
                val progress = context.content.documentProgress(listOf(document.id)).getValue(document.id)
                assertEquals(1, progress.processedUnits)
                assertEquals(3, progress.totalUnits, "the announced total is the denominator the reader sees")
                assertEquals(1, progress.ocrUnits)
                assertEquals(UnitKind.LINE, progress.unitKind)
                assertEquals(
                    listOf(0),
                    context.content.listUnits(document.id, -1, 10).map { it.ordinal },
                    "the page committed before the park is the one the progress counts",
                )
            }
        }
    }

    @Test
    fun `a killed attempt keeps its committed units and the resume reads only the rest`() {
        withHarness { harness ->
            val source = harness.writeText("long.txt", "Ordinary text\n")
            val parked = CompletableDeferred<Unit>()

            // The attempt commits its first unit, then dies where a child tool would be working.
            val jobId = AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(source.toRealPath()))
                harness.attach(context, harness.storedPipeline(context, ParkedUnits(parked, units = 3)))
                runBlocking { withTimeout(PARK_TIMEOUT_MILLIS) { parked.await() } }
                job.id
            }

            val committed = AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.listByCollection(CollectionId("default"), limit = 10).single()
                context.content.listUnits(document.id, -1, 10).map { it.ordinal }
            }
            assertEquals(listOf(0), committed, "the unit committed before the kill is durable")

            // The next process reads the rest and nothing else.
            val resumed = RecordingUnits(units = 3)
            AppContext.open(harness.dataDir).use { context ->
                harness.attach(context, harness.storedPipeline(context, resumed))
                harness.awaitJob(context, jobId)
                val document = context.documents.listByCollection(CollectionId("default"), limit = 10).single()
                assertEquals(listOf("unit-1", "unit-2"), resumed.produced)
                assertEquals(listOf("unit-0"), resumed.skipped)
                assertEquals(listOf(0, 1, 2), context.content.listUnits(document.id, -1, 10).map { it.ordinal })
                assertTrue(context.content.chunkCount(document.id) >= 3, "the resumed pass chunks every unit")
                // The resume ends the same way a first pass does: complete, and once in the index.
                assertEquals(DocumentStatus.COMPLETE, document.status)
                assertEquals(3, context.index().chunkCount(CollectionId("default"), document.id))
            }
        }
    }

    @Test
    fun `a byte-identical import of a review-pending document is a duplicate rather than a re-ingest`() {
        withHarness { harness ->
            val source = harness.writeText("pending.txt", "Ordinary text\n")
            val first = harness.importAwaitingDecision(listOf(source), RecordingUnits(units = 2))

            assertEquals(DocumentStatus.NEEDS_REVIEW, first.documents.values.single().status)

            // The bytes are already stored, and the document owes a person a decision about the reading that
            // was committed. Reading it again is neither needed nor able to answer that question, so the item
            // is a duplicate and the document is left exactly as it was.
            val second = RecordingUnits(units = 2)
            val run = harness.importDurably(listOf(source), second)

            assertEquals(ImportItemOutcome.DUPLICATE, run.items.single().outcome)
            assertTrue(second.produced.isEmpty(), "a document that awaits a decision was read again")
            assertTrue(second.skipped.isEmpty(), "the extractor is not even invoked for a finished duplicate")
            assertEquals(DocumentStatus.NEEDS_REVIEW, run.documents.values.single().status)
        }
    }

    @Test
    fun `a second import of a stored document reuses its units and does not chunk it again`() {
        withHarness { harness ->
            val source = harness.writeText("again.txt", "Ordinary text\n")
            harness.importDurably(listOf(source), RecordingUnits(units = 2))
            val before = AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.listByCollection(CollectionId("default"), limit = 10).single()
                context.content.listUnits(document.id, -1, 10).flatMap { context.content.chunksOf(it.id).map { chunk -> chunk.id } }
            }
            assertTrue(before.isNotEmpty())

            val second = RecordingUnits(units = 2)
            val run = harness.importDurably(listOf(source), second)

            assertEquals(ImportItemOutcome.DUPLICATE, run.items.single().outcome)
            // The document is already complete and indexed, so the re-import is a duplicate that never
            // re-enters extraction at all — not a resume that re-reads committed units.
            assertTrue(second.produced.isEmpty(), "a completed document must not be read again")
            assertTrue(second.skipped.isEmpty(), "the extractor is not even invoked for a completed duplicate")
            val after = AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.listByCollection(CollectionId("default"), limit = 10).single()
                context.content.listUnits(document.id, -1, 10).flatMap { context.content.chunksOf(it.id).map { chunk -> chunk.id } }
            }
            assertEquals(before, after, "unchanged text and tokenizer must not rebuild the chunks")

            // Re-importing an already indexed document also leaves exactly one set of index entries.
            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.listByCollection(CollectionId("default"), limit = 10).single()
                assertEquals(DocumentStatus.COMPLETE, document.status)
                assertEquals(2, context.index().chunkCount(CollectionId("default"), document.id))
            }
        }
    }

    /**
     * The OCR selection a check-and-improve import is admitted with when [reviewerRevisionId] judges its
     * pages: every page is read by the local engine, and a reviewer is configured.
     */
    private fun reviewingSnapshot(reviewerRevisionId: String): OcrSettingsSnapshot = OcrSettingsSnapshot(
        engine = OcrEngine.TESSERACT,
        mode = OcrImportMode.CHECK_AND_IMPROVE,
        language = "eng",
        extractorVersion = EXTRACTOR_SCHEMA_VERSION,
        reviewProfileRevisionId = reviewerRevisionId,
    )

    private fun withHarness(block: (Harness) -> Unit) {
        val directory = Files.createTempDirectory("infoscry-import")
        try {
            Harness(directory).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

/** One import attempt's durable result. */
internal data class ImportRun(
    val job: Job,
    val items: List<ImportItem>,
    val documents: Map<DocumentId, Document>,
)

/**
 * One retry attempt's durable result.
 *
 * There are no import items: a retry addresses documents that already exist, so the document's own row is
 * the per-document record of what happened.
 */
internal data class RetryRun(val job: Job, val documents: Map<DocumentId, Document>)

/**
 * Seeds a collection with a fixed identifier.
 *
 * Migration 013 retires the automatic Default, and tests that name `default` in job payloads or
 * assertions still need the row to exist. The collection store generates its own id, so this writes the
 * row a person would have created and keeps [id] as the test already names it.
 */
internal fun AppContext.seedCollectionWithId(id: String, name: String) {
    database.transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                    "VALUES ('$id', '$name', 'eng', 'ACTIVE', '2026-09-27T07:00:00Z', '2026-09-27T07:00:00Z')",
            )
        }
    }
}

/**
 * A temporary data directory with a Default-identified collection, and the ability to run real import
 * attempts against it. Either import opens and closes the data directory, so a second call is a restart
 * in the sense that matters: a new process over the same durable state.
 */
internal class Harness(val directory: Path) : AutoCloseable {

    /** The files a test imports. Separate from [dataDir], so importing a directory cannot sweep the archive. */
    val sourcesDir: Path = Files.createDirectories(directory.resolve("sources"))

    val dataDir = Files.createDirectories(directory.resolve("data"))

    init {
        // A new archive has no automatic Default collection, and every import here names `default`
        // explicitly — its managed library path is `library/default/...` too. Seed the row a person
        // would have created, keeping that fixed id so the payloads and assertions can name it.
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    fun writeText(name: String, content: String): Path {
        val file = sourcesDir.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    fun writeBinary(name: String): Path {
        val file = sourcesDir.resolve(name)
        Files.createDirectories(file.parent)
        Files.write(file, ByteArray(256) { (it and 0xFF).toByte() })
        return file
    }

    /** The managed copy of [documentId], found the way a reader would: inside its document directory. */
    fun managedOriginal(documentId: DocumentId): Path {
        val documentDir = dataDir.resolve("library/default/${documentId.value}")
        return Files.list(documentDir).use { entries ->
            entries.filter { Files.isRegularFile(it) }.toList().single()
        }
    }

    fun pipeline(
        extractor: DocumentExtractor,
        sink: ExtractionSink = ExtractionSink.NONE,
    ): ImportPipeline = ImportPipeline(
        detector = MediaTypeDetector(),
        registry = ExtractorRegistry(listOf(extractor), TextualFallbackExtractor()),
        sink = sink,
    )

    /**
     * The pipeline production runs for a reading that becomes published content, with one substitution: the
     * extractor.
     *
     * The detector, the durable sink, and the store are the real ones, so a test that uses this exercises
     * what a real import actually commits — units, checkpoints, chunks, and the document's status — rather
     * than a file the test wrote itself. It wires no candidate, so a check-and-improve attempt through it
     * commits the way every attempt did before a staged reading existed; [stagingPipeline] is the one that
     * stages.
     */
    fun storedPipeline(context: AppContext, extractor: DocumentExtractor): ImportPipeline = ImportPipeline(
        detector = MediaTypeDetector(),
        registry = ExtractorRegistry(listOf(extractor), TextualFallbackExtractor()),
        sink = StoredUnitsSink(context.paths, context.documents, context.content),
    )

    /** One import through the durable pipeline, from a fresh process over the same data directory. */
    fun importDurably(
        sources: List<Path>,
        extractor: DocumentExtractor,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        collectionId: CollectionId = CollectionId("default"),
        embedder: DocumentEmbedder = TestDocumentEmbedder(),
        maxChunksPerDocument: Int = ImportJobHandler.MAX_CHUNKS_PER_DOCUMENT,
        recursive: Boolean = false,
    ): ImportRun = AppContext.open(dataDir).use { context ->
        val job = enqueue(context, sources, settings, collectionId, recursive)
        attach(context, storedPipeline(context, extractor), embedder = embedder, maxChunksPerDocument = maxChunksPerDocument)
        finish(context, job.id, collectionId)
    }

    /**
     * The durable pipeline production runs for a check-and-improve attempt: the same real detector, unit
     * store and revision store, plus the candidate a staged reading is held in.
     *
     * [onCandidate] is handed the sink the attempt opened, so a test reads the revision the reading became
     * rather than a double's claim about it. `storedPipeline` above is the same pipeline without the
     * candidate, which is what a test that only observes the settings a reading carries keeps using.
     */
    fun stagingPipeline(
        context: AppContext,
        extractor: DocumentExtractor,
        onCandidate: (CandidateRevisionSink) -> Unit = {},
    ): ImportPipeline {
        val committed = StoredUnitsSink(context.paths, context.documents, context.content)
        return ImportPipeline(
            detector = MediaTypeDetector(),
            registry = ExtractorRegistry(listOf(extractor), TextualFallbackExtractor()),
            sink = committed,
            candidateSinkFor = { documentId ->
                CandidateRevisionSink(
                    revisions = context.revisions,
                    documentId = documentId,
                    provenance = ImportPipeline.PROVENANCE_CHECK_AND_IMPROVE,
                ).also(onCandidate)
            },
        )
    }

    /** One import through [stagingPipeline], from a fresh process over the same data directory. */
    fun importStaging(
        sources: List<Path>,
        extractor: DocumentExtractor,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        collectionId: CollectionId = CollectionId("default"),
        ocr: OcrSettingsSnapshot? = null,
        onCandidate: (CandidateRevisionSink) -> Unit = {},
    ): ImportRun = AppContext.open(dataDir).use { context ->
        val job = enqueue(context, sources, settings, collectionId, ocr = ocr)
        attach(context, stagingPipeline(context, extractor, onCandidate))
        finish(context, job.id, collectionId)
    }

    fun import(
        sources: List<Path>,
        pipeline: ImportPipeline,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        collectionId: CollectionId = CollectionId("default"),
        embedder: DocumentEmbedder = TestDocumentEmbedder(),
        maxChunksPerDocument: Int = ImportJobHandler.MAX_CHUNKS_PER_DOCUMENT,
        recursive: Boolean = false,
    ): ImportRun = AppContext.open(dataDir).use { context ->
        val job = enqueue(context, sources, settings, collectionId, recursive)
        attach(context, pipeline, embedder = embedder, maxChunksPerDocument = maxChunksPerDocument)
        finish(context, job.id, collectionId)
    }

    /**
     * Attaches the import worker with a deterministic fake embedder, so documents are indexed without
     * the pinned model. A missing model belongs to its own test, which uses the real embedder supplier
     * against an empty models directory.
     */
    fun attach(
        context: AppContext,
        pipeline: ImportPipeline,
        embedder: DocumentEmbedder = TestDocumentEmbedder(),
        maxChunksPerDocument: Int = ImportJobHandler.MAX_CHUNKS_PER_DOCUMENT,
    ): JobRunner =
        ImportJobHandler.attachTo(
            context,
            pipeline,
            documentEmbedder = { embedder },
            maxChunksPerDocument = maxChunksPerDocument,
        )

    /**
     * Starts an import and lets it die in the middle of extraction, the way a killed process does.
     *
     * The attempt is parked inside [extractionParked] when this returns, and closing the context is the
     * kill: the runner hands the unfinished job back to the queue, which is the durable state a restart
     * has to pick up. Nothing else about the attempt is simulated.
     */
    fun interrupt(
        sources: List<Path>,
        pipeline: ImportPipeline,
        extractionParked: CompletableDeferred<Unit>,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        collectionId: CollectionId = CollectionId("default"),
    ): JobId = AppContext.open(dataDir).use { context ->
        val job = enqueue(context, sources, settings, collectionId)
        attach(context, pipeline)
        runBlocking { withTimeout(PARK_TIMEOUT_MILLIS) { extractionParked.await() } }
        job.id
    }

    /** Runs an already queued job to its end in a fresh process, as the next start would after a kill. */
    fun resume(
        jobId: JobId,
        pipeline: ImportPipeline,
        collectionId: CollectionId = CollectionId("default"),
    ): ImportRun = AppContext.open(dataDir).use { context ->
        attach(context, pipeline)
        finish(context, jobId, collectionId)
    }

    /**
     * One import whose committed reading still owes a person a decision.
     *
     * The sink is the durable one with exactly one difference: it answers that a page of the reading awaits
     * a decision, which is what a comparison stages for a page a model proposed and nobody accepted. The
     * decision itself belongs to the import's comparison path; everything else here — the units, the
     * checkpoints, the chunks, the index — is the real pipeline, so the document's own status is what the
     * test observes rather than a double's opinion.
     */
    fun importAwaitingDecision(sources: List<Path>, extractor: DocumentExtractor): ImportRun =
        AppContext.open(dataDir).use { context ->
            val pipeline = ImportPipeline(
                detector = MediaTypeDetector(),
                registry = ExtractorRegistry(listOf(extractor), TextualFallbackExtractor()),
                sink = PendingDecisionSink(StoredUnitsSink(context.paths, context.documents, context.content)),
            )
            val job = enqueue(context, sources, ExtractionSettings(ocrLanguages = "eng"), CollectionId("default"))
            attach(context, pipeline)
            finish(context, job.id, CollectionId("default"))
        }

    /**
     * The durable sink with one difference: it says a page of the reading still awaits a decision.
     *
     * That answer is the whole of what `NEEDS_REVIEW` rests on, and the sink is where it belongs: what
     * committed the reading is what knows whether any of it is a proposal. Everything else is delegated, so
     * the units, their checkpoints and their artifacts are the durable sink's real work.
     */
    class PendingDecisionSink(private val delegate: StoredUnitsSink) : ExtractionSink by delegate {

        override suspend fun awaitingDecision(documentId: DocumentId): Int = 1
    }

    /** [interrupt] with the real durable unit store, so the resume has checkpoints to reuse. */
    fun interruptDurably(
        sources: List<Path>,
        extractor: DocumentExtractor,
        extractionParked: CompletableDeferred<Unit>,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        collectionId: CollectionId = CollectionId("default"),
    ): JobId = AppContext.open(dataDir).use { context ->
        val job = enqueue(context, sources, settings, collectionId)
        attach(context, storedPipeline(context, extractor))
        runBlocking { withTimeout(PARK_TIMEOUT_MILLIS) { extractionParked.await() } }
        job.id
    }

    /** An external image-model profile, created through the real store so its revision is a row that exists. */
    fun externalProfile(): infoscry.ocr.OcrProfile = AppContext.open(dataDir).use { context ->
        context.ocrProfiles.create(
            name = "Vision transcriber",
            draft = infoscry.ocr.OcrProfileRevisionDraft(
                provider = infoscry.llm.LlmProvider.OPENAI_COMPATIBLE,
                model = "vision-model",
                contextWindow = 32_000,
                maxOutputTokens = 2_048,
                endpoint = "https://example.invalid/v1",
                inputPricePerMillion = 1.0,
                outputPricePerMillion = 2.0,
            ),
            enabled = true,
        )
    }

    /**
     * An image-model *reviewer* profile whose endpoint is [endpoint], created through the real store.
     *
     * A loopback endpoint is what a test's scripted reviewer answers on, and it is local: nothing has to be
     * approved for a review to be dispatched to it, which is exactly the state a loopback provider is in.
     */
    fun reviewProfile(endpoint: String): infoscry.ocr.OcrProfile = AppContext.open(dataDir).use { context ->
        context.ocrProfiles.create(
            name = "Vision reviewer",
            draft = infoscry.ocr.OcrProfileRevisionDraft(
                provider = infoscry.llm.LlmProvider.OPENAI_COMPATIBLE,
                model = "vision-reviewer",
                contextWindow = 32_000,
                maxOutputTokens = 1_024,
                endpoint = endpoint,
                inputPricePerMillion = 1.0,
                outputPricePerMillion = 1.0,
            ),
            enabled = true,
        )
    }

    /**
     * The OCR selection one import is admitted with: the image model reads the pages, every page is read, and
     * the *job* may send [allowance] distinct pages before a person has to approve more.
     */
    fun externalSnapshot(profile: infoscry.ocr.OcrProfile, allowance: Int): OcrSettingsSnapshot =
        OcrSettingsSnapshot(
            engine = OcrEngine.LLM,
            mode = OcrImportMode.CHECK_AND_IMPROVE,
            language = "eng",
            extractorVersion = EXTRACTOR_SCHEMA_VERSION,
            transcriptionProfileRevisionId = profile.revision.revisionId,
            externalPageLimit = allowance,
        )

    /** One import whose payload carries an OCR selection, run through the durable pipeline. */
    fun importWithOcr(
        sources: List<Path>,
        extractor: DocumentExtractor,
        snapshot: OcrSettingsSnapshot,
        collectionId: CollectionId = CollectionId("default"),
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        embedder: DocumentEmbedder = TestDocumentEmbedder(),
    ): ImportRun = AppContext.open(dataDir).use { context ->
        val job = enqueue(context, sources, settings, collectionId, recursive = false, ocr = snapshot)
        attach(context, storedPipeline(context, extractor), embedder = embedder)
        finish(context, job.id, collectionId)
    }

    /** Records the approval an import waits for, exactly as the job's own approval route does. */
    fun approveJobScope(jobId: JobId, snapshot: OcrSettingsSnapshot, maxDistinctPages: Int) {
        AppContext.open(dataDir).use { context ->
            context.ocrOperations.approveExternalScope(
                owner = OcrExternalOwner.job(jobId.value),
                snapshotHash = OcrOperationStore.snapshotHashOf(snapshot),
                authorizedDistinctPages = maxDistinctPages,
            )
        }
    }

    /** What one job-owned scope holds: the pages it sent, what they cost, and what it may send now. */
    fun externalAccountOf(jobId: JobId, snapshot: OcrSettingsSnapshot): OcrExternalAccount =
        AppContext.open(dataDir).use { context ->
            context.ocrOperations.allowanceFor(
                owner = OcrExternalOwner.job(jobId.value),
                configuredAllowance = snapshot.externalPageLimit,
                snapshotHash = OcrOperationStore.snapshotHashOf(snapshot),
            )
        }

    /** Resumes a job that waited for an approval, and runs the attempt it owes in a fresh process. */
    fun resumeWaitingImport(jobId: JobId, extractor: DocumentExtractor): ImportRun =
        AppContext.open(dataDir).use { context ->
            context.jobs.resumeAwaitingApproval(jobId)
            attach(context, storedPipeline(context, extractor))
            finish(context, jobId, CollectionId("default"))
        }

    /** [resume] with the real durable unit store. */
    fun resumeDurably(
        jobId: JobId,
        extractor: DocumentExtractor,
        collectionId: CollectionId = CollectionId("default"),
    ): ImportRun = AppContext.open(dataDir).use { context ->
        attach(context, storedPipeline(context, extractor))
        finish(context, jobId, collectionId)
    }

    /** Queues an import without attaching a worker, for tests that run their own pipeline. */
    internal fun enqueueForTest(context: AppContext, sources: List<Path>, recursive: Boolean = false): Job =
        enqueue(context, sources, ExtractionSettings(ocrLanguages = "eng"), CollectionId("default"), recursive)
    /**
     * Queues a retry for documents the archive already holds, without attaching a worker.
     *
     * A retry is a job kind of its own — it addresses document identifiers and never copies bytes — so a
     * test that drives one queues that kind rather than an import.
     */
    internal fun enqueueRetry(
        context: AppContext,
        documentIds: List<DocumentId>,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        collectionId: CollectionId = CollectionId("default"),
    ): Job = context.jobs.enqueue(
        type = JobType.RETRY,
        collectionId = collectionId.takeIf { context.collections.get(it) != null },
        payload = RetryJobPayload(
            collectionId = collectionId.value,
            documentIds = documentIds.map { it.value },
            settings = settings,
        ).encode(),
        total = documentIds.size,
    )

    /** One retry through the durable pipeline, from a fresh process over the same data directory. */
    fun retry(
        documentIds: List<DocumentId>,
        extractor: DocumentExtractor,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        embedder: DocumentEmbedder = TestDocumentEmbedder(),
        maxChunksPerDocument: Int = ImportJobHandler.MAX_CHUNKS_PER_DOCUMENT,
        collectionId: CollectionId = CollectionId("default"),
    ): RetryRun = AppContext.open(dataDir).use { context ->
        val job = enqueueRetry(context, documentIds, settings, collectionId)
        attach(
            context,
            storedPipeline(context, extractor),
            embedder = embedder,
            maxChunksPerDocument = maxChunksPerDocument,
        )
        RetryRun(
            job = awaitJob(context, job.id),
            documents = context.documents.listByCollection(collectionId, limit = 100).associateBy { it.id },
        )
    }

    /** Waits for [jobId] to reach a terminal state, which is what a restarted process has to do. */
    internal fun awaitJob(context: AppContext, jobId: JobId): Job = runBlocking { awaitTerminal(context, jobId) }

    private fun enqueue(
        context: AppContext,
        sources: List<Path>,
        settings: ExtractionSettings,
        collectionId: CollectionId,
        recursive: Boolean = false,
        ocr: OcrSettingsSnapshot? = null,
    ): Job {
        val payload = ImportJobPayload(
            collectionId = collectionId.value,
            sources = sources.map { it.toAbsolutePath().normalize().toString() },
            settings = settings,
            ocr = ocr,
            recursive = recursive,
        )
        return context.jobs.enqueue(
            type = JobType.IMPORT,
            // A collection the database does not hold would be refused by the foreign key, so the job is
            // enqueued without one and the payload is what names it — which is the case under test.
            collectionId = collectionId.takeIf { context.collections.get(it) != null },
            payload = payload.encode(),
            total = 0,
        )
    }

    private fun finish(context: AppContext, jobId: JobId, collectionId: CollectionId): ImportRun {
        val finished = runBlocking { awaitTerminal(context, jobId) }
        return ImportRun(
            job = finished,
            items = context.importItems.listForJob(jobId),
            documents = context.documents.listByCollection(collectionId, limit = 100).associateBy { it.id },
        )
    }

    override fun close() = Unit
}

/** An extractor that records the reading settings it was handed, so the mode and engine are observable. */
internal class ModeRecordingUnits : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    var mode: OcrImportMode? = null
        private set
    var engine: infoscry.ocr.OcrEngine? = null
        private set

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        mode = input.settings.ocrMode
        engine = input.settings.readingEngine()
        input.boundary.unit {
            emit(
                ExtractionEvent.UnitReady(
                    key = "unit-0",
                    ordinal = 0,
                    unit = ContentUnitDraft(
                        locator = SourceLocation.TextLines(start = 1, end = 1),
                        extractedText = "read under the collection's selection",
                        searchText = "read under the collection's selection",
                        method = ExtractionMethod.OCR,
                    ),
                ),
            )
        }
        input.boundary.unit {
            emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1))
        }
    }
}

/**
 * An extractor that stands in for an image-capable engine, sending one page through the attempt's own
 * dispatch authority.
 *
 * It asks for permission exactly where a real engine does — for this document's page, through the profile
 * revision the attempt was admitted with — counts the call before it sends, and refuses the page when the
 * permit is refused. The import path's job-owned allowance is therefore proven about the production seam
 * rather than about a double that decides for itself whether it may send.
 */
internal class DispatchingUnits(
    private val text: String,
    private val unitId: String = "unit-0",
) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    /** How many pages this engine actually sent, which is what one job's allowance bounds. */
    var sent: Int = 0
        private set

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val authority = input.dispatch
        if (authority != null && !input.isCommitted(unitId)) {
            val request = ExternalDispatchPermitRequest(
                profileRevisionId = authority.profileRevisionId,
                page = PageDispatchIdentity(unitId = unitId, ordinal = 0, documentId = input.documentId.value),
            )
            if (!authority.isPermitted(request)) {
                throw ImageLlmException(
                    ImageLlmException.EXTERNAL_DISPATCH_NOT_PERMITTED,
                    "this page is not covered by an external dispatch permit for this profile revision",
                )
            }
            authority.attemptAboutToBeSent(request)
            sent++
        }
        if (!input.isCommitted(unitId)) {
            input.boundary.unit {
                emit(
                    ExtractionEvent.UnitReady(
                        key = unitId,
                        ordinal = 0,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.TextLines(start = 1, end = 1),
                            extractedText = text,
                            searchText = text,
                            method = ExtractionMethod.OCR,
                        ),
                    ),
                )
            }
        }
        input.boundary.unit {
            emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1))
        }
    }
}

/**
 * An extractor that records which units it produced and which it skipped, so a resume is observable.
 *
 * [failProducingUnit] makes the attempt die where a child tool would: after the units before it were
 * committed, and before the document could be called extracted.
 */
internal class RecordingUnits(
    private val units: Int,
    private val failProducingUnit: Int? = null,
) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    val produced = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val fingerprints = mutableListOf<ExtractionFingerprint>()

    /**
     * What this reader answers when an attempt asks what its runtime is, and how often it was asked.
     *
     * A reader is asked only for an attempt whose settings record no runtime of their own, so a call count
     * (and the different identity it would answer) is what says that an admitted identity was used rather
     * than replaced by one discovered at the attempt.
     */
    var runtimeIdentityAnswer: String? = null
    var probes: Int = 0
        private set

    override suspend fun runtimeIdentity(kind: OcrEngine): String? {
        probes++
        return runtimeIdentityAnswer
    }

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        fingerprints += input.fingerprint
        repeat(units) { index ->
            val key = "unit-$index"
            if (failProducingUnit == index) {
                throw IllegalStateException("the extraction tool died on $key")
            }
            if (input.isCommitted(key)) {
                skipped += key
            } else {
                input.boundary.unit {
                    produced += key
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = index,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                                extractedText = "unit $index",
                                searchText = "unit $index",
                                method = ExtractionMethod.DIRECT_TEXT,
                            ),
                        ),
                    )
                }
            }
        }
        input.boundary.unit {
            emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = units))
        }
    }
}

/**
 * An extractor that parks itself in the middle of its work, so a test can kill the process there.
 *
 * It commits its first unit, reports that it has reached the park, and then waits for a release that never
 * arrives: from the durable state's point of view the attempt is alive and unfinished, which is the state a
 * power loss leaves behind.
 */
internal class ParkedUnits(
    private val parked: CompletableDeferred<Unit>,
    private val units: Int = 3,
    /** How the first unit was read; an OCR unit is what puts the document in its OCR phase. */
    private val method: ExtractionMethod = ExtractionMethod.DIRECT_TEXT,
    /** Whether the extractor announces its total before it starts, as a real one does when it can. */
    private val announces: Boolean = false,
) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        if (announces) {
            input.boundary.unit {
                emit(ExtractionEvent.Progress(unitKind = UnitKind.LINE, totalUnits = units))
            }
        }
        val first = "unit-0"
        if (!input.isCommitted(first)) {
            input.boundary.unit {
                emit(
                    ExtractionEvent.UnitReady(
                        key = first,
                        ordinal = 0,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.TextLines(start = 1, end = 1),
                            extractedText = "unit 0",
                            searchText = "unit 0",
                            method = method,
                        ),
                    ),
                )
            }
        }
        parked.complete(Unit)
        // Where the process dies: a real child tool would be working here for minutes.
        CompletableDeferred<Unit>().await()
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = units)) }
    }
}

/**
 * An extractor that holds its document between two units until a test releases it.
 *
 * The wait is deliberately *outside* `input.boundary.unit`: the shared mutation permit is free while the
 * attempt sits there, exactly as it is while a real extractor renders its next page, so a deletion can be
 * admitted while the import that owns the document is still running.
 */
internal class HeldUnits(
    private val parked: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
    private val units: Int = 3,
) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        repeat(units) { index ->
            val key = "unit-$index"
            if (!input.isCommitted(key)) {
                input.boundary.unit {
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = index,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                                extractedText = "unit $index",
                                searchText = "unit $index",
                                method = ExtractionMethod.DIRECT_TEXT,
                            ),
                        ),
                    )
                }
            }
            if (index == 0) {
                parked.complete(Unit)
                release.await()
            }
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = units)) }
    }
}

/**
 * An extractor that reports units and then refuses the rest of the document, the way a reader does when it
 * finds the container is protected half way through.
 *
 * The refusal is the real one from the extraction contract: a document-level failure with no `Finished`
 * afterwards, which is what tells the pipeline the document was not read to the end.
 */
/**
 * An extractor that reads part of the document, reports one unit it could not read, and still finishes
 * the pass — the shape that makes a document `COMPLETE_WITH_WARNINGS`: what it read is searchable, and
 * the failure is on record.
 */
internal class FailedUnitsThenFinish(
    private val goodUnits: Int,
    private val failedUnits: Int,
) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        repeat(goodUnits) { index ->
            val key = "unit-$index"
            if (!input.isCommitted(key)) {
                input.boundary.unit {
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = index,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                                extractedText = "unit $index",
                                searchText = "unit $index",
                                method = ExtractionMethod.DIRECT_TEXT,
                            ),
                        ),
                    )
                }
            }
        }
        repeat(failedUnits) { index ->
            val ordinal = goodUnits + index
            input.boundary.unit {
                emit(ExtractionEvent.UnitFailed(key = "unit-failed-$index", ordinal = ordinal, code = OCR_FAILED_CODE))
            }
        }
        input.boundary.unit {
            emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = goodUnits + failedUnits))
        }
    }
}

internal class RefusingUnits(private val unitsBeforeRefusing: Int) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        repeat(unitsBeforeRefusing) { index ->
            val key = "unit-$index"
            if (!input.isCommitted(key)) {
                input.boundary.unit {
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = index,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                                extractedText = "unit $index",
                                searchText = "unit $index",
                                method = ExtractionMethod.DIRECT_TEXT,
                            ),
                        ),
                    )
                }
            }
        }
        input.boundary.unit {
            emitDocumentRefusal(input, DOCUMENT_REFUSED_KEY, ENCRYPTED_DOCUMENT_CODE)
        }
    }
}

/**
 * An extractor that reads every page's image even though the page carries text, as check-and-improve does.
 *
 * It leaves the raster a real render would leave under the document's artifacts and hands *both* readings to
 * the sink: what the engine read from the pixels, and the text the page itself carries beside it. [text] and
 * [reading] are the pair a person has to decide between.
 */
internal class CheckAndImprovePages(
    private val text: String,
    private val reading: String,
) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        if (!input.isCommitted(PAGE_KEY)) {
            val image = renderedPage(input)
            input.boundary.unit {
                emit(
                    ExtractionEvent.UnitReady(
                        key = PAGE_KEY,
                        ordinal = PAGE_ORDINAL,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.PdfPage(PAGE_ORDINAL + 1),
                            extractedText = reading,
                            searchText = reading,
                            method = ExtractionMethod.OCR,
                            meanConfidence = 0.9,
                            // The image the engine read the page from, named the way a render names it, so a
                            // later phase can rebuild those exact pixels for a reviewer.
                            sourceImage = image.artifactProvenance(input.artifactRoot),
                            directText = text,
                        ),
                    ),
                )
            }
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1)) }
    }

    /** The page image a reader of this page would have handed to the engine, as its own artifact. */
    private fun renderedPage(input: ExtractionInput): PageImage {
        val file = input.artifactRoot.resolve(PAGE_IMAGE_NAME)
        Files.createDirectories(input.artifactRoot)
        writePng(file)
        return PageImage.ofFile(
            documentId = input.documentId,
            unitId = PAGE_KEY,
            ordinal = PAGE_ORDINAL,
            imageRoot = input.artifactRoot,
            imageReference = PAGE_IMAGE_NAME,
            artifactRoot = input.artifactRoot,
            renderDpi = null,
            rotationDegrees = 0,
        )
    }

    private companion object {
        const val PAGE_KEY = "page:1"
        const val PAGE_ORDINAL = 0
        const val PAGE_IMAGE_NAME = "page-000001.png"
    }
}

/**
 * Two pages of one file under check-and-improve, one of which says what the page carries and one of which does
 * not, so an import of it has a page nobody owes a decision about beside one somebody does.
 *
 * Both pages carry a text layer, which is what the mode compares against: the first page's engine reading is
 * the text the page itself holds, and the second page's reading differs from it.
 */
internal class CheckAndImproveTwoPages(
    /** The text the first page carries, which its reading says too. */
    private val matchingText: String,
    /** The text the second page carries. */
    private val differingText: String,
    /** What the engine read from the second page's pixels, which is not what the page carries. */
    private val differingReading: String,
) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        pages().forEach { page ->
            if (input.isCommitted(page.key)) return@forEach
            val image = renderedPage(input, page.key, page.ordinal)
            input.boundary.unit {
                emit(
                    ExtractionEvent.UnitReady(
                        key = page.key,
                        ordinal = page.ordinal,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.PdfPage(page.ordinal + 1),
                            extractedText = page.reading,
                            searchText = page.reading,
                            method = ExtractionMethod.OCR,
                            meanConfidence = 0.9,
                            sourceImage = image.artifactProvenance(input.artifactRoot),
                            directText = page.directText,
                        ),
                    ),
                )
            }
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = pages().size)) }
    }

    /** The two pages, and the pair each one is judged by. */
    private fun pages() = listOf(
        Page(key = "page:1", ordinal = 0, directText = matchingText, reading = matchingText),
        Page(key = "page:2", ordinal = 1, directText = differingText, reading = differingReading),
    )

    private data class Page(
        val key: String,
        val ordinal: Int,
        val directText: String,
        val reading: String,
    )

    /** The page image a reader of this page would have handed to the engine, as its own artifact. */
    private fun renderedPage(input: ExtractionInput, key: String, ordinal: Int): PageImage {
        val name = "page-00000${ordinal + 1}.png"
        val file = input.artifactRoot.resolve(name)
        Files.createDirectories(input.artifactRoot)
        writePng(file)
        return PageImage.ofFile(
            documentId = input.documentId,
            unitId = key,
            ordinal = ordinal,
            imageRoot = input.artifactRoot,
            imageReference = name,
            artifactRoot = input.artifactRoot,
            renderDpi = null,
            rotationDegrees = 0,
        )
    }
}

/**
 * Writes a PNG, so a page image rebuilt from a provenance has real pixels to verify its hash against.
 */
private fun writePng(path: Path) {
    val image = java.awt.image.BufferedImage(24, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, image.width, image.height)
    } finally {
        graphics.dispose()
    }
    check(javax.imageio.ImageIO.write(image, "png", path.toFile())) { "no PNG writer is available" }
}

/**
 * The one page's worth of data the scripted reviewer answers about, for the pair its reading is compared with.
 */
private const val PAGE_KEY_FOR_REVIEW = "page:1"

/**
 * The answer an image-model reviewer gives: which side it prefers, and where the two readings differ.
 *
 * The unit id and ordinal are the page identity the request names, which is what the client checks an answer
 * against, and the spans are the place the number differs in "name 123" and "name 128" — the pair the
 * check-and-improve import of [CheckAndImprovePages] compares.
 */
private fun reviewAnswer(recommendation: String): String = buildJsonObject {
    put("unitId", PAGE_KEY_FOR_REVIEW)
    put("ordinal", 0)
    put("recommendation", recommendation)
    put("confidence", 0.9)
    putJsonArray("reasons") {
        addJsonObject {
            put("explanation", "the number in the name differs")
            put("aStart", 5)
            put("aEnd", 8)
            put("bStart", 5)
            put("bEnd", 8)
        }
    }
}.toString()

/** One OpenAI-compatible envelope whose single answer is [content]. */
private fun reviewEnvelope(content: String): String = buildJsonObject {
    put("model", "vision-reviewer-2026-02-01")
    putJsonArray("choices") {
        addJsonObject {
            put("finish_reason", "stop")
            putJsonObject("message") {
                put("role", "assistant")
                put("content", content)
            }
        }
    }
}.toString()

/** Fails the test rather than hanging it when a job never reaches a terminal state. */
internal suspend fun awaitTerminal(context: AppContext, id: JobId): Job {
    val deadline = System.nanoTime() + TERMINAL_TIMEOUT_NANOS
    while (System.nanoTime() < deadline) {
        val job = context.jobs.get(id)
        if (job != null && job.state !in setOf(JobState.QUEUED, JobState.RUNNING)) return job
        delay(POLL_MILLIS)
    }
    throw AssertionError("job ${id.value} never reached a terminal state")
}

private const val TERMINAL_TIMEOUT_NANOS = 60_000_000_000L
private const val POLL_MILLIS = 20L

/** How long a test waits for an attempt to reach its park before calling the test a failure. */
private const val PARK_TIMEOUT_MILLIS = 30_000L
