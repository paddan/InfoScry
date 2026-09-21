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
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.ExtractionSink
import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.TikaFallbackExtractor
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

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
            val sink = FileExtractionSink(harness.directory.resolve("units"))

            // The first attempt commits two of its three units and then dies where a child tool would.
            val interrupted = RecordingUnits(units = 3, failProducingUnit = 2)
            val first = harness.import(listOf(source), harness.pipeline(interrupted, sink))

            assertEquals(listOf("unit-0", "unit-1"), interrupted.produced)
            assertEquals(ImportItemOutcome.FAILED, first.items.single().outcome)
            assertEquals(setOf("unit-0", "unit-1"), sink.committedKeys())

            // The document is not finished, so importing the same file again resumes it rather than
            // declaring a duplicate and walking away from the unfinished extraction.
            val resumed = RecordingUnits(units = 3)
            val second = harness.import(listOf(source), harness.pipeline(resumed, sink))

            assertEquals(listOf("unit-2"), resumed.produced, "a committed unit was extracted again")
            assertEquals(listOf("unit-0", "unit-1"), resumed.skipped)
            assertEquals(setOf("unit-0", "unit-1", "unit-2"), sink.committedKeys())
            // The bytes were already stored, so this item is a duplicate; what it did was finish them.
            assertEquals(ImportItemOutcome.DUPLICATE, second.items.single().outcome)
            assertEquals(first.documents.keys.single(), second.items.single().documentId)
        }
    }

    @Test
    fun `changed extraction settings do not reuse another fingerprint's units`() {
        withHarness { harness ->
            val source = harness.writeText("report.txt", "text\n")
            val sink = FileExtractionSink(harness.directory.resolve("units"))

            val first = RecordingUnits(units = 1)
            harness.import(listOf(source), harness.pipeline(first, sink))

            val changed = RecordingUnits(units = 1)
            harness.import(
                listOf(source),
                harness.pipeline(changed, sink),
                settings = ExtractionSettings(ocrLanguages = "swe+eng"),
            )

            assertEquals(listOf("unit-0"), changed.produced, "another fingerprint's unit was reused")
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

    private val dataDir = Files.createDirectories(directory.resolve("data"))

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
        registry = ExtractorRegistry(listOf(extractor), TikaFallbackExtractor()),
        sink = sink,
    )

    fun import(
        sources: List<Path>,
        pipeline: ImportPipeline,
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
        collectionId: CollectionId = CollectionId("default"),
    ): ImportRun = AppContext.open(dataDir).use { context ->
        val payload = ImportJobPayload(
            collectionId = collectionId.value,
            sources = sources.map { it.toAbsolutePath().normalize().toString() },
            settings = settings,
        )
        val job = context.jobs.enqueue(
            type = JobType.IMPORT,
            // A collection the database does not hold would be refused by the foreign key, so the job is
            // enqueued without one and the payload is what names it — which is the case under test.
            collectionId = collectionId.takeIf { context.collections.get(it) != null },
            payload = payload.encode(),
            total = 0,
        )
        ImportJobHandler.attachTo(context, pipeline)
        val finished = runBlocking { awaitTerminal(context, job.id) }
        ImportRun(
            job = finished,
            items = context.importItems.listForJob(job.id),
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
 * A sink whose committed keys survive the attempt, so a resume has something to resume from.
 *
 * It writes a file rather than rows on purpose: the durable unit store belongs to its own task, and what
 * this test needs is only that the checkpoint protocol is real — keys committed under a fingerprint are
 * visible to the next attempt, and another fingerprint's keys are not.
 */
internal class FileExtractionSink(private val directory: Path) : ExtractionSink {

    override val storesUnits: Boolean = true

    private val delivered = mutableListOf<Triple<String, String, ExtractionEvent>>()

    override suspend fun committedKeys(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
    ): Set<String> = delivered
        .filter { (id, print, _) -> id == documentId.value && print == fingerprint.value }
        .mapNotNull { (_, _, event) -> keyOf(event) }
        .toSet()

    /** Every key committed by any attempt, for assertions about what a resume had to work with. */
    fun committedKeys(): Set<String> = delivered.mapNotNull { (_, _, event) -> keyOf(event) }.toSet()

    override suspend fun deliver(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        event: ExtractionEvent,
    ) {
        delivered += Triple(documentId.value, fingerprint.value, event)
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("committed.tsv"),
            delivered.joinToString("\n") { (id, print, deliveredEvent) ->
                listOf(id, print, keyOf(deliveredEvent) ?: "-").joinToString("\t")
            },
            StandardOpenOption.CREATE,
        )
    }

    private fun keyOf(event: ExtractionEvent): String? = when (event) {
        is ExtractionEvent.UnitReady -> event.key
        is ExtractionEvent.UnitFailed -> event.key
        is ExtractionEvent.Finished -> null
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

internal fun sha256Of(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)))
}
