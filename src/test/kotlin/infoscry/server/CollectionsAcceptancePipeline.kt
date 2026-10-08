/*
 * The substitutable half of the ticket-12 Collections browser acceptance.
 *
 * `CollectionsBrowserAcceptanceTest` drives the real built reader in Chromium against a real local
 * InfoScry server, a real SQLite archive, the real durable unit store, the real import job path and a
 * real HTTP client. Exactly two things are substituted, both through seams production already has:
 *
 * - the extraction pipeline, because a fake extractor can produce a mixed direct-text/OCR document, a
 *   document whose total is unknown while it runs, and a file that fails its first attempt on demand —
 *   none of which a real redistributable fixture can be made to do without Tesseract and a GPU;
 * - the document embedder, because a temporary archive has no pinned model. `TestDocumentEmbedder` is
 *   the same fake the job-handler tests index with.
 *
 * Everything the fixtures need is written into a temporary directory by [AcceptanceFixtures]; nothing
 * here reads a private document and nothing is sent to a provider. The PNG fixture carries only the
 * eight magic bytes Tika detects `image/png` from: the fake extractor never parses it, and a real
 * image's pixels would add nothing but bytes.
 */
package infoscry.server

import infoscry.AppContext
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DocumentExtractor
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.TextualFallbackExtractor
import infoscry.jobs.ImportPipeline
import infoscry.jobs.StoredUnitsSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/**
 * A one-way latch the acceptance test opens to let a held extraction continue.
 *
 * It stands in for the minutes a real OCR pass takes, and it is waited on *between* unit boundaries, so
 * a held import is a job whose extraction stage is not in flight: that is what lets a collection or
 * document deletion be admitted and drain while the import is still running, which is the boundary the
 * acceptance has to reach. Opening it twice is a no-op; a gate nobody opens times out rather than
 * hanging a suite.
 */
internal class AcceptanceGate {

    private val released = CompletableDeferred<Unit>()

    val isReleased: Boolean get() = released.isCompleted

    fun release() {
        released.complete(Unit)
    }

    suspend fun await(): Unit = withTimeout(GATE_TIMEOUT_MILLIS) { released.await() }

    private companion object {
        const val GATE_TIMEOUT_MILLIS = 180_000L
    }
}

/**
 * The pipeline the acceptance server runs imports with: the real detector and the real durable unit
 * store, and one extractor that says what the acceptance needs.
 */
internal fun acceptancePipeline(context: AppContext, extractor: CollectionsAcceptanceExtractor): ImportPipeline = ImportPipeline(
    detector = MediaTypeDetector(),
    registry = ExtractorRegistry(listOf(extractor), TextualFallbackExtractor()),
    sink = StoredUnitsSink(context.paths, context.documents, context.content),
)

/**
 * One unit per line, three pages read one way and another, a file that refuses its first attempt, and a
 * file that holds until [gate] is opened.
 *
 * The behaviour is keyed by the file's own name, which is the only thing an extractor is handed that the
 * fixtures control. Units are emitted inside `input.boundary.unit`, and the held one waits outside it, so
 * the shared mutation permit is free exactly while a real extractor would be between two pages.
 */
internal class CollectionsAcceptanceExtractor(
    private val gate: AcceptanceGate,
    /** What each file's attempts produced and reused, so a resume is observable rather than assumed. */
    private val run: AcceptanceExtractorRun = AcceptanceExtractorRun(),
) : DocumentExtractor {

    /** What a test reads after a scenario to see which work a later attempt repeated. */
    val recorded: AcceptanceExtractorRun get() = run

    private val attempts = ConcurrentHashMap<String, AtomicInteger>()

    override val supportedMediaTypes: Set<String> = setOf(TEXT_PLAIN, IMAGE_PNG)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val name = input.originalFilename
        when {
            name == AcceptanceFixtures.MIXED_PAGES_FIXTURE -> mixedPages(input)
            name.startsWith(AcceptanceFixtures.FAIL_ONCE_PREFIX) -> {
                if (refusesFirstAttempt(name)) {
                    // One committed unit, then the failure a tool that dies mid-document leaves: the
                    // attempt is unfinished and the unit it did commit is what a later attempt may reuse.
                    emitUnit(
                        input = input,
                        index = 0,
                        locator = SourceLocation.TextLines(start = 1, end = 1),
                        text = "Line 1 of $name.",
                        method = readMethod(name),
                    )
                    error("the acceptance extractor refuses this file's first attempt after one unit")
                }
                repeat(FAIL_ONCE_UNITS) { index ->
                    emitUnit(
                        input = input,
                        index = index,
                        locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                        text = "Line ${index + 1} of $name.",
                        method = readMethod(name),
                    )
                }
                input.boundary.unit {
                    emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = FAIL_ONCE_UNITS))
                }
            }

            name.startsWith(AcceptanceFixtures.HELD_PREFIX) -> heldLines(input)
            else -> lines(input, unitCount = 1)
        }
    }

    /** A scanned page beside a page with its own text layer: three pages, one of them directly read. */
    private suspend fun FlowCollector<ExtractionEvent>.mixedPages(input: ExtractionInput) {
        input.boundary.unit { emit(ExtractionEvent.Progress(UnitKind.PAGE, PAGE_COUNT)) }
        repeat(PAGE_COUNT) { index ->
            emitUnit(
                input = input,
                index = index,
                locator = SourceLocation.PdfPage(page = index + 1),
                text = "Page ${index + 1} of the scanned acceptance fixture.",
                // One page has its own text layer and the next two are scans: the mixed document the
                // honest counters have to describe without calling every page an OCR page.
                method = if (index == 0) ExtractionMethod.DIRECT_TEXT else ExtractionMethod.OCR,
            )
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = PAGE_COUNT)) }
    }

    /** Three lines with no announced total: while it runs, progress is a count without a denominator. */
    private suspend fun FlowCollector<ExtractionEvent>.heldLines(input: ExtractionInput) {
        repeat(HELD_LINE_COUNT) { index ->
            emitUnit(
                input = input,
                index = index,
                locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                text = "Held line ${index + 1} of ${input.originalFilename}.",
                method = ExtractionMethod.DIRECT_TEXT,
            )
            if (index == 0) gate.await()
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = HELD_LINE_COUNT)) }
    }

    /** [unitCount] lines, each its own unit; a total is announced only when the extractor knows one. */
    private suspend fun FlowCollector<ExtractionEvent>.lines(input: ExtractionInput, unitCount: Int) {
        repeat(unitCount) { index ->
            emitUnit(
                input = input,
                index = index,
                locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                text = "Line ${index + 1} of ${input.originalFilename}.",
                method = ExtractionMethod.DIRECT_TEXT,
            )
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = unitCount)) }
    }

    private suspend fun FlowCollector<ExtractionEvent>.emitUnit(
        input: ExtractionInput,
        index: Int,
        locator: SourceLocation,
        text: String,
        method: ExtractionMethod,
    ) {
        val key = "unit-$index"
        // A committed unit is one an earlier attempt already delivered under this fingerprint: reading it
        // again would be the repetition the checkpoint exists to prevent.
        if (input.isCommitted(key)) {
            run.reused(input.originalFilename, key)
            return
        }
        input.boundary.unit {
            run.produced(input.originalFilename, key)
            emit(
                ExtractionEvent.UnitReady(
                    key = key,
                    ordinal = index,
                    unit = ContentUnitDraft(
                        locator = locator,
                        extractedText = text,
                        searchText = text,
                        method = method,
                    ),
                ),
            )
        }
    }

    /** A picture is read by OCR, a text file directly: only a document that needed OCR has a method to name. */
    private fun readMethod(name: String): ExtractionMethod =
        if (name.endsWith(".png")) ExtractionMethod.OCR else ExtractionMethod.DIRECT_TEXT

    private fun refusesFirstAttempt(name: String): Boolean =
        attempts.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet() == 1

    private companion object {
        const val TEXT_PLAIN = "text/plain"
        const val IMAGE_PNG = "image/png"
        const val PAGE_COUNT = 3
        const val HELD_LINE_COUNT = 3
        const val FAIL_ONCE_UNITS = 2
    }
}

/**
 * Which units each file's attempts actually produced and which they skipped as already committed.
 *
 * A resume or a retry that reused committed work is only honest if it can be seen to have done so, so
 * the extractor records it per file name rather than leaving it to the pipeline's own counters.
 */
internal class AcceptanceExtractorRun {

    private val producedKeys = ConcurrentHashMap<String, MutableList<String>>()
    private val reusedKeys = ConcurrentHashMap<String, MutableList<String>>()

    fun produced(file: String, key: String) {
        producedKeys.computeIfAbsent(file) { Collections.synchronizedList(mutableListOf()) }.add(key)
    }

    fun reused(file: String, key: String) {
        reusedKeys.computeIfAbsent(file) { Collections.synchronizedList(mutableListOf()) }.add(key)
    }

    fun produced(file: String): List<String> = producedKeys[file]?.toList().orEmpty()

    fun reused(file: String): List<String> = reusedKeys[file]?.toList().orEmpty()
}

/** The fixture file names the extractor's behaviour and the browser's assertions both name. */
internal object AcceptanceFixtures {

    const val MIXED_PAGES_FIXTURE = "mixed-pages.png"
    const val FAIL_ONCE_PREFIX = "fail-once-"
    const val HELD_PREFIX = "held-"
    const val BULK_PREFIX = "note-"
    const val BULK_FILES = 55
    const val LEGACY_FILENAME = "legacy-summary.txt"
    const val LEGACY_TOTAL_UNITS = 7

    /**
     * Everything the acceptance imports, in a temporary directory.
     *
     * All of it is generated from text and eight magic bytes, so nothing redistributed here is anybody's
     * document and nothing needs a private archive.
     */
    fun write(sourcesDir: Path): Path {
        Files.createDirectories(sourcesDir)
        repeat(BULK_FILES) { index ->
            writeText(sourcesDir.resolve("bulk").resolve("$BULK_PREFIX%02d.txt".format(index + 1)), "Bulk fixture line ${index + 1}.")
        }
        val duplicateBytes = "The same redistributable bytes, imported twice.\n"
        writeText(sourcesDir.resolve("duplicates").resolve("dup-a.txt"), duplicateBytes)
        writeText(sourcesDir.resolve("duplicates").resolve("dup-b.txt"), duplicateBytes)
        writeText(sourcesDir.resolve("duplicates").resolve("dup-c.txt"), duplicateBytes)
        Files.write(sourcesDir.resolve(MIXED_PAGES_FIXTURE), PNG_MAGIC + ByteArray(16))
        writeText(sourcesDir.resolve("single").resolve("one.txt"), "Line 1 of one.txt.\n")
        writeText(sourcesDir.resolve("single").resolve("two.txt"), "Line 1 of two.txt.\n")
        writeText(sourcesDir.resolve("single").resolve("three.txt"), "Line 1 of three.txt.\n")
        writeText(sourcesDir.resolve("single").resolve("four.txt"), "Line 1 of four.txt.\n")
        writeText(sourcesDir.resolve("archive").resolve("keep-a.txt"), "The first document of the other collection.\n")
        writeText(sourcesDir.resolve("archive").resolve("keep-b.txt"), "The second document of the other collection.\n")
        writeText(sourcesDir.resolve("held").resolve("held-01.txt"), "Held fixture line one.\n")
        writeText(sourcesDir.resolve("held").resolve("held-02.txt"), "Held fixture line two.\n")
        writeText(sourcesDir.resolve("held").resolve("held-03.txt"), "Held fixture line three.\n")
        writeText(sourcesDir.resolve(FAIL_ONCE_PREFIX + "a.txt"), "A first attempt that fails, then a second that reads.\n")
        writeText(sourcesDir.resolve(FAIL_ONCE_PREFIX + "b.txt"), "Another first attempt that fails.\n")
        // Ticket 05: two readable files and one binary blob no extractor claims.
        writeText(sourcesDir.resolve("unsupported").resolve("real-a.txt"), "The first readable file beside an unsupported one.\n")
        writeText(sourcesDir.resolve("unsupported").resolve("real-b.txt"), "The second readable file beside an unsupported one.\n")
        Files.write(sourcesDir.resolve("unsupported").resolve("blob.bin"), ByteArray(256) { it.toByte() })
        // Ticket 06: four readable files told apart only by their extension (case included).
        writeText(sourcesDir.resolve("types").resolve("alpha.txt"), "Alpha text file.\n")
        writeText(sourcesDir.resolve("types").resolve("beta.TXT"), "Beta text file with a capital extension.\n")
        writeText(sourcesDir.resolve("types").resolve("gamma.log"), "Gamma log file.\n")
        writeText(sourcesDir.resolve("types").resolve("delta.dat"), "Delta data file.\n")
        // Ticket 07: system and temporary files beside the real documents. Each is readable text, so only
        // an ignore pattern keeps it out.
        writeText(sourcesDir.resolve("ignored").resolve(".DS_Store"), "Finder bookkeeping that is plain text here.\n")
        writeText(sourcesDir.resolve("ignored").resolve("draft.tmp"), "A temporary draft.\n")
        writeText(sourcesDir.resolve("ignored").resolve("scratch-1.txt"), "A scratch file the reader adds a pattern for.\n")
        writeText(sourcesDir.resolve("ignored").resolve("real-a.txt"), "The first real document of the ignored folder.\n")
        writeText(sourcesDir.resolve("ignored").resolve("real-b.txt"), "The second real document of the ignored folder.\n")
        // Ticket 08: a document whose first attempt fails and is retried with a chosen method.
        // A picture, because a chosen OCR method needs page images: a text file is refused as unsupported.
        Files.write(sourcesDir.resolve(FAIL_ONCE_PREFIX + "scan.png"), PNG_MAGIC + ByteArray(32) { 7 })
        return sourcesDir
    }

    /** The absolute path the picker returns for a folder choice. */
    fun folder(sourcesDir: Path, name: String): List<String> = listOf(sourcesDir.resolve(name).toString())

    /** The absolute paths the picker returns for a file choice. */
    fun files(sourcesDir: Path, vararg names: String): List<String> = names.map { sourcesDir.resolve(it).toString() }

    private fun writeText(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    /** `\x89PNG\r\n\x1a\n`: the signature Tika's own magic rule detects `image/png` from. */
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
}

/**
 * What the server's picker answers, per scenario.
 *
 * The native picker needs a graphical session and a person, so the acceptance server is handed this
 * instead: the browser clicks `Choose files…` or `Choose folder…`, the route asks this, and the paths it
 * returns are the fixtures the scenario queued. The real dialog stays a manual gate.
 */
internal class AcceptancePicker(private val sourcesDir: Path) {

    @Volatile
    var folder: List<String> = emptyList()

    @Volatile
    var files: List<String> = emptyList()

    suspend fun choose(directory: Boolean): List<String> = if (directory) folder else files
}
