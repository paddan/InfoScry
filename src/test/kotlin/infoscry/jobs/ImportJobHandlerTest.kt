package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.domain.SourceLocation
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
import infoscry.extract.MediaTypeDetector
import infoscry.extract.TextualFallbackExtractor
import infoscry.extract.emitDocumentRefusal
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.E5Embedder
import infoscry.embedding.ModelManager
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.OCR_FAILED_CODE
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
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
                assertEquals(3, context.index.chunkCount(CollectionId("default"), documentId))
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
                assertEquals(3, context.index.chunkCount(CollectionId("default"), stored))
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
                assertEquals(3, context.index.chunkCount(collectionId, documentId), "every chunk is searchable")
                val hits = context.index.searchKeyword(collectionId, "unit", limit = 10)
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
                assertEquals(2, context.index.chunkCount(CollectionId("default"), documentId))
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
            assertNotEquals(DocumentStatus.COMPLETE, run.documents.getValue(documentId).status)
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(0, context.index.chunkCount(CollectionId("default"), documentId))
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
                assertEquals(3, context.index.chunkCount(CollectionId("default"), documentId))
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
                assertEquals(3, context.index.chunkCount(CollectionId("default"), document.id))
            }
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
                assertEquals(2, context.index.chunkCount(CollectionId("default"), document.id))
            }
        }
    }

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
 * A temporary data directory with the default collection, and the ability to run real import attempts
 * against it. Either import opens and closes the data directory, so a second call is a restart in the
 * sense that matters: a new process over the same durable state.
 */
internal class Harness(val directory: Path) : AutoCloseable {

    /** The files a test imports. Separate from [dataDir], so importing a directory cannot sweep the archive. */
    val sourcesDir: Path = Files.createDirectories(directory.resolve("sources"))

    val dataDir = Files.createDirectories(directory.resolve("data"))

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
     * The pipeline production runs, with one substitution: the extractor.
     *
     * The detector, the durable sink, and the store are the real ones, so a test that uses this exercises
     * what a real import actually commits — units, checkpoints, chunks, and the document's status — rather
     * than a file the test wrote itself.
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
    ): ImportRun = AppContext.open(dataDir).use { context ->
        val job = enqueue(context, sources, settings, collectionId)
        attach(context, storedPipeline(context, extractor), embedder = embedder, maxChunksPerDocument = maxChunksPerDocument)
        finish(context, job.id, collectionId)
    }

    fun import(
        sources: List<Path>,
        pipeline: ImportPipeline,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        collectionId: CollectionId = CollectionId("default"),
        embedder: DocumentEmbedder = TestDocumentEmbedder(),
        maxChunksPerDocument: Int = ImportJobHandler.MAX_CHUNKS_PER_DOCUMENT,
    ): ImportRun = AppContext.open(dataDir).use { context ->
        val job = enqueue(context, sources, settings, collectionId)
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
    internal fun enqueueForTest(context: AppContext, sources: List<Path>): Job =
        enqueue(context, sources, ExtractionSettings(ocrLanguages = "eng"), CollectionId("default"))

    /** Waits for [jobId] to reach a terminal state, which is what a restarted process has to do. */
    internal fun awaitJob(context: AppContext, jobId: JobId): Job = runBlocking { awaitTerminal(context, jobId) }

    private fun enqueue(
        context: AppContext,
        sources: List<Path>,
        settings: ExtractionSettings,
        collectionId: CollectionId,
    ): Job {
        val payload = ImportJobPayload(
            collectionId = collectionId.value,
            sources = sources.map { it.toAbsolutePath().normalize().toString() },
            settings = settings,
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
) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
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
