package infoscry.cli

import com.github.ajalt.clikt.core.main
import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DocumentExtractor
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.TextualFallbackExtractor
import infoscry.jobs.ImportPipeline
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The real `infoscry` command line with a test pipeline, started as a child process.
 *
 * The CLI's contract with a script — its exit code, the text it writes, and what it leaves behind on disk
 * — only exists for a real process. So this harness runs the actual Clikt command, the actual process
 * lock, the actual job runner and the actual SQLite state, and substitutes exactly one thing: the
 * extraction pipeline, because the formats and the durable unit store belong to their own tasks.
 *
 * What the substituted extractor can do is deliberately crude and test-shaped: it produces one unit per
 * text file, and it can be made to *wait* until a gate file appears, which is how a test observes the
 * states a fast import never shows — a command that is still alive mid-import, and a server that keeps
 * working after the command that asked for the import has returned.
 *
 * Configuration comes from the environment because a child JVM has no other channel:
 *
 * ```
 * INFOSCRY_TEST_GATE   path that must exist before the extractor finishes its unit
 * INFOSCRY_TEST_UNITS  how many units the extractor produces (default 1)
 * ```
 */
object ImportProcessHarness {

    @JvmStatic
    fun main(args: Array<String>) {
        // The pipeline factory is the same seam production uses; only its contents differ.
        RootCommand(pipeline = { context -> harnessPipeline(context) }).main(args)
    }

    /** The pipeline a harness run extracts with: the real detector, one fake extractor, a file sink. */
    fun harnessPipeline(context: AppContext): ImportPipeline = ImportPipeline(
        detector = MediaTypeDetector(),
        registry = ExtractorRegistry(listOf(HarnessExtractor()), TextualFallbackExtractor()),
        // A sink that survives the attempt, because extraction is a no-op without one and the gate below
        // is what makes an import observable while it runs.
        sink = FileUnitsSink(context.paths.tempDir.resolve("harness-units")),
    )

    /** The gate path the child waits for, if the test asked for one. */
    fun gatePath(): Path? = System.getenv("INFOSCRY_TEST_GATE")?.takeIf { it.isNotBlank() }?.let(Path::of)

}

/**
 * One unit per text file, and a gate that can hold the attempt open.
 *
 * The wait happens *inside* the unit boundary on purpose: it stands in for the minutes a real OCR pass
 * takes, and it must occupy the same place in the permit discipline as the work it replaces.
 */
internal class HarnessExtractor : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("text/plain")

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val units = System.getenv("INFOSCRY_TEST_UNITS")?.toIntOrNull() ?: 1
        repeat(units) { index ->
            val key = "document-$index"
            if (!input.isCommitted(key)) {
                input.boundary.unit {
                    ImportProcessHarness.gatePath()?.let { gate ->
                        val deadline = System.nanoTime() + GATE_TIMEOUT_NANOS
                        while (!Files.exists(gate) && System.nanoTime() < deadline) delay(GATE_POLL_MILLIS)
                    }
                    val text = Files.readString(input.managedPath)
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = index,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.TextLines(start = 1, end = 1),
                                extractedText = text,
                                searchText = text,
                            ),
                        ),
                    )
                }
            }
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = units)) }
    }

    private companion object {
        const val GATE_POLL_MILLIS = 20L
        const val GATE_TIMEOUT_NANOS = 120_000_000_000L
    }
}

/** A sink that records committed unit keys under the data directory, so a child process's work is visible. */
internal class FileUnitsSink(private val directory: Path) : infoscry.extract.ExtractionSink {

    override val storesUnits: Boolean = true

    override suspend fun committedKeys(
        documentId: infoscry.domain.DocumentId,
        fingerprint: infoscry.extract.ExtractionFingerprint,
    ): Set<String> = readEntries()
        .filter { (id, print, _) -> id == documentId.value && print == fingerprint.value }
        .map { (_, _, key) -> key }
        .toSet()

    override suspend fun deliver(
        documentId: infoscry.domain.DocumentId,
        fingerprint: infoscry.extract.ExtractionFingerprint,
        event: ExtractionEvent,
    ) {
        val key = when (event) {
            is ExtractionEvent.UnitReady -> event.key
            is ExtractionEvent.UnitFailed -> event.key
            is ExtractionEvent.Finished -> return
        }
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("units.txt"),
            "${documentId.value}\t${fingerprint.value}\t$key\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    /** Every committed entry as `(document, fingerprint, key)`. */
    private fun readEntries(): List<Triple<String, String, String>> {
        val file = directory.resolve("units.txt")
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split('\t')
                Triple(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
            }
    }
}
