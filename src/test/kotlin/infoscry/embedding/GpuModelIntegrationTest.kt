package infoscry.embedding

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import infoscry.chunk.Chunker
import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.GPU_TAG
import infoscry.MODEL_TAG
import org.junit.jupiter.api.Tag
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The hardware gate: the pinned model, on this machine's accelerator, with the evidence that it executed
 * there.
 *
 * It runs only under `./gradlew gpuIntegrationTest`, and it fails — never skips — when the model files or the
 * accelerator are missing, because a gate that reports success without running is worse than no gate. The
 * model is installed by `./gradlew embeddingModel` and lives in the data directory, which
 * `-Dinfoscry.dataDir=` points at.
 *
 * What it proves, in the order the requirements list it: the session runs on CoreML with kernel evidence
 * rather than a registration; the vectors are 768 dimensions, finite and unit length; retrieval behaves on
 * Swedish and English text; a passage of exactly the model's budget is embedded and one token more is
 * refused instead of truncated; and the chunker measures the same text the embedder consumes.
 */
@Tag(GPU_TAG)
@Tag(MODEL_TAG)
class GpuModelIntegrationTest {

    private val manifest = ModelManifest.load()

    private val paths = AppPaths.from(
        Path.of(
            System.getProperty("infoscry.dataDir")
                ?: "${System.getProperty("user.home")}/.infoscry",
        ),
    )

    private val installation: ModelInstallation by lazy {
        val problems = ModelManager(manifest).verifyInstalled(paths.modelsDir)
        if (problems.isNotEmpty()) {
            throw AssertionError(
                "the pinned model is not installed in ${paths.modelsDir}: $problems. " +
                    "Install it with './gradlew embeddingModel'. ${ModelManager.installRemedy()}",
            )
        }
        ModelInstallation(manifest, paths.modelsDir.resolve(manifest.revision))
    }

    private val embedder: E5Embedder by lazy {
        E5Embedder.production(
            manifest = manifest,
            installation = installation,
            profileDirectory = evidenceDirectory(),
        )
    }

    @Test
    fun `the pinned model executes on coreml and the profile proves it`() {
        val gpu = GpuRuntime(manifest)
        val readiness = gpu.probe()
        assertTrue(readiness.ready, "the validated platform must be ready: ${readiness.reasons}")

        val started = System.nanoTime()
        val verified = gpu.createSession(
            modelPath = installation.onnxPath,
            profileDirectory = evidenceDirectory(),
            warmUp = { session -> runWarmUp(session) },
        )
        val sessionMillis = (System.nanoTime() - started) / 1_000_000
        verified.use { session ->
            // Steady-state latency, measured rather than asserted: it depends on the machine, and the plan
            // asks for the number, not for a bound.
            val runs = (1..5).map {
                val runStarted = System.nanoTime()
                runWarmUp(session.session)
                (System.nanoTime() - runStarted) / 1_000_000
            }.sorted()
            assertTrue(
                session.profile.coreMlKernelEvents > 0,
                "CoreML must execute part of the graph, but the profile says: ${session.profile.describe()}",
            )
            assertTrue(
                session.profile.coreMlShare > GpuRuntime.REQUIRED_CORE_ML_SHARE,
                "CoreML must carry the compute, not a token node: ${session.profile.describe()}",
            )
            writeEvidence(session.profile, readiness, sessionMillis, runs[runs.size / 2])
        }
    }

    @Test
    fun `the embedder returns 768-dimension unit vectors for swedish and english text`() {
        val vectors = embedder.embedDocuments(
            listOf(
                "Palme utredningen omfattar tusentals dokument och vittnesmål.",
                "The investigation covers thousands of documents and witness statements.",
            ),
        )

        assertEquals(2, vectors.size)
        vectors.forEach { vector ->
            assertEquals(768, vector.size)
            assertTrue(vector.all { it.isFinite() }, "every component is finite")
            assertEquals(1.0f, norm(vector), 1e-3f)
        }
    }

    @Test
    fun `a swedish query ranks its paraphrase above an unrelated passage`() {
        val documents = listOf(
            "Vittnet såg en man i mörk jacka vid biografen.",
            "Bilen var parkerad utanför stationen hela natten.",
            "Rapporten handlar om skogsbruk och virkespriser.",
        )
        val vectors = embedder.embedDocuments(documents)
        val query = embedder.embedQuery("Vem såg mannen i den mörka jackan?")

        val ranked = documents.indices.sortedByDescending { dot(query, vectors[it]) }
        assertEquals(0, ranked.first(), "the paraphrase scores highest: $ranked")
    }

    @Test
    fun `an english query ranks its paraphrase above an unrelated passage`() {
        val documents = listOf(
            "The witness saw a man in a dark jacket outside the cinema.",
            "The car was parked outside the station all night.",
            "The report is about forestry and timber prices.",
        )
        val vectors = embedder.embedDocuments(documents)
        val query = embedder.embedQuery("Who saw the man in the dark jacket?")

        val ranked = documents.indices.sortedByDescending { dot(query, vectors[it]) }
        assertEquals(0, ranked.first(), "the paraphrase scores highest: $ranked")
    }

    @Test
    fun `a passage of exactly the budget is embedded and one token more is refused`() {
        val fitting = passageUpToBudget()
        assertTrue(
            fitting.tokens <= manifest.maxSequenceTokens &&
                fitting.tokens >= manifest.maxSequenceTokens - 8,
            "the fixture passage fills the budget as closely as whole words allow, was ${fitting.tokens}",
        )
        // One more word measures past the budget instead of being cut back to it: that is the property the
        // refusal rests on, and a truncating tokenizer would report the budget here and hide the overflow.
        assertTrue(
            fitting.overflowTokens > manifest.maxSequenceTokens,
            "the tokenizer measures past the budget rather than truncating, " +
                "but '${fitting.overflow}' measured ${fitting.overflowTokens}",
        )

        val vectors = embedder.embedDocuments(listOf(fitting.text))

        assertEquals(1, vectors.size)
        assertEquals(768, vectors.single().size)
        assertEquals(1.0f, norm(vectors.single()), 1e-3f)

        val failure = assertFailsWith<EmbeddingException> {
            embedder.embedDocuments(listOf(fitting.overflow))
        }
        assertEquals(E5Embedder.EMBEDDING_INPUT_TOO_LONG_CODE, failure.code)
    }

    @Test
    fun `a batch of full-length passages is embedded without truncation`() {
        val passage = passageUpToBudget().text

        val vectors = embedder.embedDocuments(listOf(passage, passage))

        assertEquals(2, vectors.size)
        vectors.forEach { assertEquals(768, it.size) }
    }

    @Test
    fun `the chunker measures with the real tokenizer and every character stays citable`() {
        val text = buildString {
            append("Kolumn A\tKolumn B\n")
            repeat(400) { index -> append("rad $index\tvärde $index\n") }
        }
        val unit = unit(text)
        val chunker = Chunker(embedder)

        assertEquals(embedder.id, chunker.counterId, "chunking records the tokenizer that measured it")

        val plan = chunker.chunk(unit)
        assertTrue(plan.drafts.size > 1, "a unit this long needs several passages")
        plan.drafts.forEach { draft ->
            assertTrue(
                embedder.encodePassage(draft.text).totalTokens <= manifest.maxSequenceTokens,
                "every passage fits the model's budget, including the repeated header",
            )
            // A spreadsheet chunk repeats its header, so its text starts with it while the offsets address
            // only the unit's own characters: the body is the tail.
            val body = text.substring(draft.startOffset, draft.endOffset)
            assertTrue(
                draft.text.endsWith(body),
                "a chunk's offsets address the search text it came from (chunk=${draft.ordinal})",
            )
        }
        assertEquals(0, plan.drafts.first().startOffset)
        assertEquals(text.length, plan.drafts.last().endOffset, "the walk covers the whole unit")
        plan.drafts.zipWithNext().forEach { (previous, next) ->
            assertTrue(
                next.startOffset <= previous.endOffset,
                "the next passage overlaps rather than leaving a gap",
            )
        }
    }

    @Test
    fun `a prose unit's chunk offsets address exactly the text it stored`() {
        val text = buildString {
            repeat(300) { index -> append("Mening nummer $index handlar om utredningen och dess dokument.\n") }
        }
        val unit = unit(text).copy(locator = SourceLocation.TextLines(1, 300))

        val plan = Chunker(embedder).chunk(unit)

        assertTrue(plan.drafts.size > 1, "a unit this long needs several passages")
        plan.drafts.forEach { draft ->
            assertEquals(
                draft.text,
                text.substring(draft.startOffset, draft.endOffset),
                "without a repeated header a chunk's text is exactly the span it cites",
            )
            assertTrue(embedder.encodePassage(draft.text).totalTokens <= manifest.maxSequenceTokens)
        }
        assertEquals(text.length, plan.drafts.last().endOffset)
    }

    @Test
    fun `the tokenizer reports a body for every non-empty passage`() {
        // The chunker refuses a counter that reads no body token from non-empty text; this pins that the
        // real tokenizer never does, for the shapes ingestion actually produces.
        listOf("kort", "ett par ord", "Kolumn A\tKolumn B\nrad 1\tvärde 1\n", "å" + "ä".repeat(200)).forEach { text ->
            val passage = embedder.encodePassage(text)
            assertTrue(passage.bodyRange != null, "'$text' produced no body token")
            assertTrue(passage.bodyTokens > 0, "'$text' produced no body token")
        }
    }

    private fun unit(text: String): ContentUnit = ContentUnit(
        id = ContentUnitId.new(),
        documentId = DocumentId.new(),
        ordinal = 0,
        locator = SourceLocation.SpreadsheetRange(sheet = "Ark1", startCell = "A1", endCell = "B400"),
        extractedText = text,
        searchText = text,
    )

    /** A passage at the model's budget, and the next word that pushes it over. */
    private data class BudgetPassage(
        val text: String,
        val tokens: Int,
        val overflow: String,
        val overflowTokens: Int,
    )

    /**
     * The longest whole-word passage at or below the budget, plus the first candidate past it.
     *
     * Single words are appended until the next one would overflow, so the fitting passage is as close to the
     * budget as whole words allow — which is exactly what a chunker built from the same measurement produces.
     */
    private fun passageUpToBudget(): BudgetPassage {
        val budget = manifest.maxSequenceTokens
        var text = ""
        var count = 1
        while (true) {
            val candidate = if (text.isEmpty()) "ord$count" else "$text ord$count"
            val tokens = embedder.encodePassage(candidate).totalTokens
            if (tokens > budget) {
                return BudgetPassage(
                    text = text,
                    tokens = embedder.encodePassage(text).totalTokens,
                    overflow = candidate,
                    overflowTokens = tokens,
                )
            }
            text = candidate
            count++
        }
    }

    /** Built once: reading tokenizer.json inside a timed block would measure the fixture, not the model. */
    private val warmUpEncoding by lazy {
        HuggingFaceTokenizer.builder()
            .optTokenizerPath(installation.tokenizerPath)
            .optAddSpecialTokens(true)
            .optTruncation(false)
            .optPadding(false)
            .build()
            .encode("${manifest.passagePrefix}InfoScry embedding warm-up.")
    }

    private fun runWarmUp(session: OrtSession) {
        val encoded = warmUpEncoding
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, encoded.ids.size.toLong())
        val ids = OnnxTensor.createTensor(environment, LongBuffer.wrap(encoded.ids), shape)
        val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(encoded.attentionMask), shape)
        val names = session.inputNames.toList()
        val maskName = names.first { it.contains("mask") }
        val idsName = names.first { it != maskName }
        try {
            session.run(mapOf(idsName to ids, maskName to mask)).close()
        } finally {
            ids.close()
            mask.close()
        }
    }

    private fun evidenceDirectory(): Path = Path.of("build", "gpu-validation").also { Files.createDirectories(it) }

    private fun writeEvidence(
        profile: GpuProfile,
        readiness: GpuReadiness,
        sessionMillis: Long,
        steadyStateMillis: Long,
    ) {
        val evidence = buildString {
            appendLine("{")
            appendLine("  \"platform\": \"${Platform.current().describe()}\",")
            appendLine("  \"device\": \"${readiness.device}\",")
            appendLine("  \"provider\": \"${readiness.provider}\",")
            appendLine("  \"onnxruntime\": \"${readiness.runtimeVersion}\",")
            appendLine("  \"model\": \"${manifest.model}\",")
            appendLine("  \"revision\": \"${manifest.revision}\",")
            appendLine("  \"modelFingerprint\": \"${readiness.modelFingerprint}\",")
            appendLine("  \"sessionMillis\": $sessionMillis,")
            appendLine("  \"steadyStateMillis\": $steadyStateMillis,")
            appendLine("  \"largestActivationBytes\": ${profile.largestActivationBytes},")
            appendLine("  \"coreMlKernelEvents\": ${profile.coreMlKernelEvents},")
            appendLine("  \"coreMlKernelMicros\": ${profile.coreMlKernelMicros},")
            appendLine("  \"cpuKernelEvents\": ${profile.cpuKernelEvents},")
            appendLine("  \"cpuKernelMicros\": ${profile.cpuKernelMicros},")
            appendLine("  \"coreMlShare\": ${profile.coreMlShare}")
            appendLine("}")
        }
        Files.writeString(evidenceDirectory().resolve("coreml-profile-summary.json"), evidence)
        println("GPU-EVIDENCE ${evidence.replace("\n", " ")}")
    }

    private fun norm(vector: FloatArray): Float {
        var sum = 0.0
        vector.forEach { sum += it.toDouble() * it }
        return kotlin.math.sqrt(sum).toFloat()
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        var sum = 0f
        left.indices.forEach { sum += left[it] * right[it] }
        return sum
    }
}
