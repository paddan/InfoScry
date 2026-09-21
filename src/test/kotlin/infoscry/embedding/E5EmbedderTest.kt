package infoscry.embedding

import ai.onnxruntime.OrtException
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The embedder without a GPU or a model: a tokenizer fixture small enough to commit, and a runner that stands
 * in for ONNX Runtime so the batching, padding, budget and out-of-memory behaviour are exercised directly.
 *
 * The real tokenizer's boundary behaviour is proven with the pinned model in
 * [GpuModelIntegrationTest], because a fixture tokenizer can only prove the code path, never the model's
 * own arithmetic.
 */
class E5EmbedderTest {

    private val manifest = testManifest(maxSequenceTokens = 512)

    private fun embedder(
        runner: EmbeddingRunner = RecordingRunner(),
        maxSequenceTokens: Int = 512,
        batchTokenBudget: Int = E5Embedder.DEFAULT_BATCH_TOKEN_BUDGET,
    ): E5Embedder = E5Embedder(
        manifest = manifest.copy(maxSequenceTokens = maxSequenceTokens),
        tokenizerPath = testTokenizerPath(),
        runner = runner,
        batchTokenBudget = batchTokenBudget,
    )

    @Test
    fun `measures a passage with the passage prefix and keeps only the text's own tokens`() {
        val passage = embedder().encodePassage("hej världen")

        // The tokenizer splits "passage: " into two tokens and adds CLS and SEP, so four tokens are overhead.
        assertEquals(4, passage.overheadTokens, "the prefix and the special tokens are the overhead")
        assertEquals(listOf("hej", "världen"), passage.bodySpans().map { "hej världen".substring(it) })
        assertEquals(3, passage.bodyRange?.first, "the body starts right after the prefix tokens")
        assertEquals(6, passage.totalTokens)
    }

    @Test
    fun `an empty passage has overhead and no body`() {
        val passage = embedder().encodePassage("")

        assertEquals(4, passage.totalTokens)
        assertEquals(null, passage.bodyRange, "the prefix alone carries no body token")
    }

    @Test
    fun `the tokenizer id names the model, the revision and the prefix convention`() {
        assertEquals("intfloat/multilingual-e5-base@d1287505@e5-query-passage-1", embedder().id)
    }

    @Test
    fun `a passage that does not fit the budget is refused rather than truncated`() {
        val embedder = embedder(maxSequenceTokens = 5)
        val tooLong = "en två tre fyra fem sex sju åtta"

        // The measurement itself grows with the text: a truncating tokenizer would report the budget instead.
        val measured = embedder.encodePassage(tooLong).totalTokens
        assertTrue(measured > 5, "the counter measured $measured tokens, so it did not truncate the passage")

        val failure = assertFailsWith<EmbeddingException> { embedder.embedDocuments(listOf(tooLong)) }
        assertEquals(E5Embedder.EMBEDDING_INPUT_TOO_LONG_CODE, failure.code)
        assertTrue(
            failure.message!!.contains("12"),
            "the message names how long the passage really was: ${failure.message}",
        )
    }

    @Test
    fun `a query that does not fit is refused with its own code`() {
        val embedder = embedder(maxSequenceTokens = 5)

        val failure = assertFailsWith<EmbeddingException> { embedder.embedQuery("en två tre fyra fem sex") }
        assertEquals(E5Embedder.QUERY_OVERLONG_CODE, failure.code)
    }

    @Test
    fun `a blank query is refused`() {
        val failure = assertFailsWith<EmbeddingException> { embedder().embedQuery("   ") }
        assertEquals(E5Embedder.EMPTY_QUERY_CODE, failure.code)
    }

    @Test
    fun `the query and passage prefixes cost the same tokens for a short text`() {
        val embedder = embedder()

        // "query: " and "passage: " are different tokens in the fixture, so the counts can differ; what the
        // caller relies on is that neither prefix is free, so a short query is not refused for budget reasons.
        assertEquals(embedder.queryTokens("hej"), embedder.encodePassage("hej").totalTokens)
        assertTrue(embedder.queryTokens("hej") > 0)
    }

    @Test
    fun `a query prefix is excluded from the body its tokens span`() {
        val embedder = embedder()
        val text = "hej världen"
        val encoded = embedder.encodedQuery(text)

        assertTrue(encoded.overheadTokens > 0, "the prefix itself costs tokens")
        assertTrue(encoded.totalTokens > encoded.overheadTokens, "the text also produced tokens")
        encoded.tokens.filter { it.span != null }.forEach { token ->
            val span = token.span!!
            assertTrue(
                span.first >= 0 && span.last < text.length,
                "a query prefix token must not enter the body, but $span addresses '$text'",
            )
        }
    }

    @Test
    fun `the query and passage prefixes produce different added tokens`() {
        val embedder = embedder()
        val text = "hej världen"

        val queryIds = embedder.encodedQuery(text).tokens.map { it.id }
        val passageIds = embedder.encodePassage(text).tokens.map { it.id }

        assertTrue(queryIds != passageIds, "different prefixes are different token sequences")
    }

    @Test
    fun `documents are embedded in batches padded to each batch's longest passage`() {
        val runner = RecordingRunner()
        val embedder = embedder(runner = runner)

        val vectors = embedder.embedDocuments(listOf("hej", "hej världen och en två"))

        assertEquals(2, vectors.size)
        // Two passages of different lengths go in one batch, padded to the longer one.
        assertEquals(listOf(2), runner.batchSizes)
        assertEquals(
            embedder.encodePassage("hej världen och en två").totalTokens,
            runner.lengths.single(),
            "the batch is padded to its longest row's encoded length",
        )
    }

    @Test
    fun `padding is masked out so it cannot enter a pooled vector`() {
        val rows = listOf(
            EncodedRow(ids = longArrayOf(1, 5, 2), mask = longArrayOf(1, 1, 1)),
            EncodedRow(ids = longArrayOf(1, 7), mask = longArrayOf(1, 1)),
        )

        val (ids, masks) = E5Embedder.padRows(rows)

        assertEquals(listOf(1L, 5L, 2L), ids[0].toList())
        assertEquals(listOf(1L, 7L, 0L), ids[1].toList(), "the short row is padded to the batch's longest")
        assertEquals(listOf(1L, 1L, 0L), masks[1].toList(), "the padding carries no attention")
    }

    @Test
    fun `a batch is split when its padded total would exceed the token budget`() {
        val batches = E5Embedder.planBatches(listOf(100, 100, 100), 250)

        assertEquals(
            listOf(0..1, 2..2),
            batches,
            "two rows of 100 fill the 250-token budget; the third opens the next batch",
        )
    }

    @Test
    fun `one passage longer than the whole budget is still a single batch`() {
        assertEquals(listOf(0..0), E5Embedder.planBatches(listOf(900), 250))
    }

    @Test
    fun `a device that runs out of memory gets smaller batches instead of a failure`() {
        val runner = OutOfMemoryRunner(failAbove = 1)
        val embedder = embedder(runner = runner, batchTokenBudget = 512)

        val vectors = embedder.embedDocuments(listOf("hej", "hej världen", "och en två tre"))

        assertEquals(3, vectors.size)
        assertEquals(listOf(1, 1, 1), runner.results.map { it.size }, "every batch was halved to one passage")
    }

    @Test
    fun `a device that cannot hold even one passage reports the device rather than looping`() {
        val runner = OutOfMemoryRunner(failAbove = 0)

        val failure = assertFailsWith<EmbeddingException> {
            embedder(runner = runner, batchTokenBudget = 512).embedDocuments(listOf("hej", "hej världen"))
        }
        assertEquals(E5Embedder.OUT_OF_MEMORY_CODE, failure.code)
        assertTrue(failure.message!!.contains("single passage"), "the message is actionable: ${failure.message}")
    }

    @Test
    fun `a failure that is not an allocation failure is not retried`() {
        val runner = FailingRunner(IllegalStateException("the graph is invalid"))

        val failure = assertFailsWith<IllegalStateException> { embedder(runner = runner).embedDocuments(listOf("hej")) }
        assertEquals("the graph is invalid", failure.message)
        assertEquals(1, runner.calls, "a model error is not retried")
    }

    @Test
    fun `allocation failures are recognised by type and by what onnx runtime says`() {
        assertTrue(E5Embedder.isOutOfMemory(OutOfMemoryError("java heap space")))
        assertTrue(E5Embedder.isOutOfMemory(IllegalStateException("Failed to allocate memory for tensor")))
        assertTrue(E5Embedder.isOutOfMemory(IllegalStateException("CoreML OOM while running")))
        assertTrue(!E5Embedder.isOutOfMemory(IllegalStateException("shape mismatch")))
    }

    @Test
    fun `a provider failure the oom patterns do not recognise is logged and not retried`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(E5Embedder::class.java) as Logger
        logger.addAppender(appender)
        try {
            val embedder = embedder(runner = RefusingOrtRunner)

            val failure = assertFailsWith<OrtException> { embedder.embedDocuments(listOf("hej")) }
            // ORT prefixes the message with the code, so the assertion looks for the provider's own text.
            assertTrue(failure.message!!.contains("shape mismatch"), failure.message)

            val event = appender.list.single()
            val fields = event.keyValuePairs.associate { it.key to it.value }
            assertEquals("ORT_FAIL", fields["provider_error_code"])
            // The logged field is the message ORT itself reports, code prefix included.
            val providerError = fields["provider_error"] as? String ?: ""
            assertTrue(providerError.contains("shape mismatch"), providerError)
            assertEquals("embedding", fields["component"])
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `pooling averages the unmasked tokens and normalises the vector`() {
        // Two positions of dimension 2, the second masked out, so the mean is the first position alone.
        val hidden = floatArrayOf(3f, 4f, 100f, 100f)

        val vectors = poolAndNormalise(
            hidden = hidden,
            rowCount = 1,
            rowLength = 2,
            dimension = 2,
            masks = arrayOf(longArrayOf(1, 0)),
        )

        assertEquals(1, vectors.size)
        assertEquals(0.6f, vectors[0][0], 1e-6f)
        assertEquals(0.8f, vectors[0][1], 1e-6f)
        assertEquals(1.0f, norm(vectors[0]), 1e-5f)
    }

    @Test
    fun `pooling keeps rows apart`() {
        val hidden = floatArrayOf(1f, 0f, 0f, 2f)

        val vectors = poolAndNormalise(
            hidden = hidden,
            rowCount = 2,
            rowLength = 1,
            dimension = 2,
            masks = arrayOf(longArrayOf(1), longArrayOf(1)),
        )

        assertEquals(listOf(1f, 0f), vectors[0].toList())
        assertEquals(listOf(0f, 1f), vectors[1].toList())
    }

    @Test
    fun `a row with nothing to pool is a defect rather than a zero vector`() {
        assertFailsWith<IllegalArgumentException> {
            poolAndNormalise(
                hidden = floatArrayOf(1f, 1f),
                rowCount = 1,
                rowLength = 2,
                dimension = 1,
                masks = arrayOf(longArrayOf(0, 0)),
            )
        }
    }

    @Test
    fun `the production embedder refuses to touch the accelerator until a vector is asked for`() {
        val gpu = GpuRuntime(manifest, providers = { setOf("CPU") }, platform = Platform("macos", "aarch64"))
        val embedder = E5Embedder.production(
            manifest = manifest,
            installation = ModelInstallation(manifest, testTokenizerPath().parent),
            gpu = gpu,
            profileDirectory = Files.createTempDirectory("profile"),
        )

        // Measuring is a tokenizer job, so it works on a machine with no accelerator at all.
        assertNotNull(embedder.encodePassage("hej världen").bodyRange)

        val failure = try {
            embedder.embedDocuments(listOf("hej"))
            null
        } catch (thrown: Throwable) {
            thrown
        }
        assertTrue(
            failure is GpuUnavailableException,
            "embedding still needs the accelerator, but it failed with: $failure",
        )
    }

    @Test
    fun `the production counter measures provisionally when the model is not installed`() {
        val modelsDir = Files.createTempDirectory("no-model-installed")
        val profileDirectory = Files.createTempDirectory("profile")

        // Asking for the counter must not touch the model: the archive starts, serves and searches without it.
        val counter = E5Embedder.productionCounter(modelsDir, profileDirectory)
        val chunker = Chunker(counter)

        // It measures, and it says which measurement it used, so the chunks it produces are re-chunked once
        // the model arrives rather than being mistaken for the model's own.
        assertEquals(WhitespaceTokenCounter().id, chunker.counterId)
        assertTrue(chunker.chunk(testUnit("hej världen och en två tre")).drafts.isNotEmpty())
        assertEquals(
            "whitespace-2-1",
            chunker.counterId,
            "the provisional counter names itself, which is what makes the re-chunking pass replace it",
        )
    }

    // The other branch — an installation that is present, so the model's own tokenizer measures — needs the
    // real 1.1 GB files and is therefore proven by GpuModelIntegrationTest, which runs with the installed
    // model. A stand-in of that size is not something a unit test can or should allocate.

    private fun norm(vector: FloatArray): Float {
        var sum = 0.0
        vector.forEach { sum += it.toDouble() * it }
        return kotlin.math.sqrt(sum).toFloat()
    }
}

/** A runner that records what it was asked for and returns one fixed vector per passage. */
private class RecordingRunner : EmbeddingRunner {
    val batchSizes = mutableListOf<Int>()
    val lengths = mutableListOf<Int>()

    override fun embed(ids: Array<LongArray>, masks: Array<LongArray>): List<FloatArray> {
        batchSizes += ids.size
        lengths += ids.first().size
        return ids.indices.map { FloatArray(2) { 0.5f } }
    }
}

/** A runner that fails on any batch larger than [failAbove], as a device with too little memory does. */
/** A runner that fails the way the provider does, with a typed error code that is not an allocation failure. */
private object RefusingOrtRunner : EmbeddingRunner {
    override fun embed(ids: Array<LongArray>, masks: Array<LongArray>): List<FloatArray> {
        throw OrtException(OrtException.OrtErrorCode.ORT_FAIL, "shape mismatch")
    }
}

private class OutOfMemoryRunner(private val failAbove: Int) : EmbeddingRunner {
    val results = mutableListOf<List<FloatArray>>()

    override fun embed(ids: Array<LongArray>, masks: Array<LongArray>): List<FloatArray> {
        if (ids.size > failAbove) throw IllegalStateException("Failed to allocate memory for the batch")
        val vectors = ids.indices.map { FloatArray(2) { 0.5f } }
        results += vectors
        return vectors
    }
}

/** A runner that fails with something that is not an allocation failure. */
private class FailingRunner(private val failure: RuntimeException) : EmbeddingRunner {
    var calls = 0

    override fun embed(ids: Array<LongArray>, masks: Array<LongArray>): List<FloatArray> {
        calls++
        throw failure
    }
}
