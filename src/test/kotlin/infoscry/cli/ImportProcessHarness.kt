package infoscry.cli

import com.github.ajalt.clikt.core.main
import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.domain.SourceLocation
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.TestDocumentEmbedder
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
        // The pipeline factory is the same seam production uses; the embedder substitution is the same
        // seam the import path uses, so the child documents are indexed without the pinned model.
        RootCommand(
            pipeline = { context -> harnessPipeline(context) },
            importEmbedder = { _ -> { FakeDocumentEmbedder } },
        ).main(args)
    }

    /**
     * The pipeline a harness run extracts with: the real detector, the real durable unit store, and one
     * fake extractor.
     *
     * The unit store is real on purpose: extraction, checkpoints, chunks, embeddings and index
     * publication are one pipeline, and a harness that stops before the durable store would be a harness
     * that tests an earlier version of it. The only substitution is the extractor and the embedder, and
     * the gate below is what makes an import observable while it runs.
     */
    fun harnessPipeline(context: AppContext): ImportPipeline = ImportPipeline(
        detector = MediaTypeDetector(),
        registry = ExtractorRegistry(listOf(HarnessExtractor()), TextualFallbackExtractor()),
        sink = StoredUnitsSink(context.paths, context.documents, context.content),
    )

    /** The gate path the child waits for, if the test asked for one. */
    fun gatePath(): Path? = System.getenv("INFOSCRY_TEST_GATE")?.takeIf { it.isNotBlank() }?.let(Path::of)

}

/** The fake embedder the process harness indexes with; a temporary data directory has no model. */
internal object FakeDocumentEmbedder : DocumentEmbedder {
    override fun embedDocuments(texts: List<String>): List<FloatArray> =
        TestDocumentEmbedder().embedDocuments(texts)
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
