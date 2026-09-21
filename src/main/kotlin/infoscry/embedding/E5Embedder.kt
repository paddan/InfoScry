package infoscry.embedding

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import infoscry.chunk.EncodedPassage
import infoscry.chunk.EncodedToken
import infoscry.chunk.TokenCounter
import infoscry.chunk.WhitespaceTokenCounter
import org.slf4j.LoggerFactory
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Path
import kotlin.math.sqrt

/** One text as the model receives it: token ids and the mask that says which of them are real. */
internal data class EncodedRow(val ids: LongArray, val mask: LongArray)

/**
 * Runs whole passages through the model.
 *
 * This is the seam a test substitutes so the batching, padding and out-of-memory behaviour can be exercised
 * without a GPU, while the real implementation — tensors, inference, pooling and normalisation — is proven
 * on hardware by the model integration run. Everything else in [E5Embedder] is tokenizer and arithmetic.
 */
internal fun interface EmbeddingRunner : AutoCloseable {
    fun embed(ids: Array<LongArray>, masks: Array<LongArray>): List<FloatArray>

    override fun close() {}
}

/**
 * The pinned embedding model: it measures text the way the model measures it and turns that text into
 * vectors.
 *
 * Two jobs live on one class because they share one tokenizer and the chunker must not be able to measure a
 * passage with a different tokenizer than the embedder consumes. Measuring needs only the tokenizer file;
 * embedding needs the accelerator, and the accelerator is created on first use, so a machine without a ready
 * GPU can still chunk and search by keyword.
 */
class E5Embedder internal constructor(
    val manifest: ModelManifest,
    private val tokenizerPath: Path,
    private val runner: EmbeddingRunner,
    private val batchTokenBudget: Int = DEFAULT_BATCH_TOKEN_BUDGET,
) : TokenCounter, AutoCloseable {

    init {
        require(batchTokenBudget > 0) { "batchTokenBudget must be positive, was $batchTokenBudget" }
    }

    /**
     * The tokenizer that measured a chunk, written into the chunking metadata.
     *
     * A chunk measured by the provisional counter and a chunk measured here are not comparable, so the
     * measurement is named rather than assumed and the chunk metadata records which one produced it.
     */
    override val id: String = "${manifest.model}@${manifest.revision.take(REVISION_PREFIX_LENGTH)}" +
        "@${manifest.prefixVersion}"

    private val tokenizer: HuggingFaceTokenizer by lazy {
        HuggingFaceTokenizer.builder()
            .optTokenizerPath(tokenizerPath)
            .optAddSpecialTokens(true)
            // Truncation is off on purpose: a tokenizer that shortened its input would report a passage that
            // fits while the model received a different one, which is the failure this budget exists to
            // prevent. An oversized passage is refused by `refuseOverlongPassage` instead.
            .optTruncation(false)
            .optPadding(false)
            .optWithOverflowingTokens(false)
            .build()
    }

    /** Encodes [text] as the passage the model would receive, with the passage prefix. */
    override fun encodePassage(text: String): EncodedPassage = measure(manifest.passagePrefix, text)

    /**
     * Embeds whole passages, each one already sized to fit the model's budget.
     *
     * A passage that does not fit is refused, not shortened: the caller is the chunker, whose whole job is to
     * keep the input inside the budget, so an overflow here means the chunker and the embedder disagree about
     * the measurement and continuing would store a vector built from different text than the citation names.
     */
    fun embedDocuments(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val rows = texts.map { text -> encodeRow(manifest.passagePrefix, text, EMBEDDING_INPUT_TOO_LONG_CODE) }
        return embedRows(rows)
    }

    /**
     * Embeds one search query.
     *
     * Queries are rejected rather than truncated, and the message says what to do about it, because a
     * truncated query embeds a different question than the one asked while looking like a normal result.
     */
    fun embedQuery(query: String): FloatArray {
        if (query.isBlank()) {
            throw EmbeddingException(EMPTY_QUERY_CODE, "an embedding query must not be blank")
        }
        val passages = embedRows(listOf(encodeRow(manifest.queryPrefix, query, QUERY_OVERLONG_CODE)))
        return passages.single()
    }

    /**
     * How many tokens a query of [query] would spend, so a caller can refuse it before embedding.
     *
     * The query prefix differs from the passage prefix, so this cannot be answered by [encodePassage].
     */
    fun queryTokens(query: String): Int = encodeRow(manifest.queryPrefix, query, QUERY_OVERLONG_CODE).ids.size

    val dimension: Int get() = manifest.dimension

    val maxSequenceTokens: Int get() = manifest.maxSequenceTokens

    override fun close() = runner.close()

    private fun embedRows(rows: List<EncodedRow>): List<FloatArray> {
        val batches = planBatches(rows.map { it.ids.size }, batchTokenBudget)
        return batches.flatMap { range -> runBatch(rows.subList(range.first, range.last + 1)) }
    }

    /**
     * Runs one batch, halving it when the device runs out of memory.
     *
     * The retry exists because the token budget is a policy, not a measurement of free device memory: a large
     * document next to another process's work can exhaust the GPU at a size that normally fits. Halving down
     * to a single passage finds the largest batch that fits; failing at one passage is a device problem the
     * caller cannot fix by retrying, so it is reported as such.
     */
    private fun runBatch(rows: List<EncodedRow>): List<FloatArray> {
        if (rows.isEmpty()) return emptyList()
        val (ids, masks) = padRows(rows)
        try {
            val vectors = runner.embed(ids, masks)
            check(vectors.size == rows.size) {
                "the embedding runner returned ${vectors.size} vectors for ${rows.size} passages"
            }
            return vectors
        } catch (failure: Throwable) {
            if (!isOutOfMemory(failure)) throw failure
            if (rows.size == 1) {
                throw EmbeddingException(
                    code = OUT_OF_MEMORY_CODE,
                    message = "the model could not embed even a single passage: ${failure.message}. " +
                        "Close other GPU-heavy work and try again, or use a machine with more memory.",
                    cause = failure,
                )
            }
            val half = rows.size / 2
            return runBatch(rows.subList(0, half)) + runBatch(rows.subList(half, rows.size))
        }
    }

    /** Measures [text] under [prefix], keeping only the tokens the text itself produced. */
    private fun measure(prefix: String, text: String): EncodedPassage {
        val encoding = tokenizer.encode(prefix + text)
        val spans = encoding.charTokenSpans
        val tokens = encoding.ids.indices.map { index ->
            val span = spans.getOrNull(index)
            EncodedToken(
                id = encoding.ids[index].toInt(),
                // A special token has no span, and so does a token of the prefix: neither belongs to the text
                // the caller asked about, and a span pointing into them would put a citation on the wrong
                // characters. What is left addresses `text` itself, from offset zero.
                span = bodySpan(span?.start ?: NO_SPAN, span?.end ?: NO_SPAN, prefix.length),
            )
        }
        return EncodedPassage(tokens)
    }

    private fun encodeRow(prefix: String, text: String, tooLongCode: String): EncodedRow {
        refuseOverlongPassage(prefix, text, tooLongCode)
        val encoding: Encoding = tokenizer.encode(prefix + text)
        return EncodedRow(ids = encoding.ids, mask = encoding.attentionMask)
    }

    private fun refuseOverlongPassage(prefix: String, text: String, code: String) {
        val tokens = tokenizer.encode(prefix + text).ids.size
        if (tokens > manifest.maxSequenceTokens) {
            throw EmbeddingException(
                code = code,
                message = "this text needs $tokens tokens, but ${manifest.model} accepts at most " +
                    "${manifest.maxSequenceTokens}; it was divided into passages that each fit",
            )
        }
    }

    /** The passage prefix this tokenizer measured with, so a caller can see what the budget paid for. */
    private fun bodySpan(start: Int, end: Int, prefixLength: Int): IntRange? {
        if (start == NO_SPAN || end <= prefixLength) return null
        val bodyStart = start - prefixLength
        val bodyEnd = end - prefixLength
        if (bodyEnd <= bodyStart) return null
        return bodyStart until bodyEnd
    }

    companion object {

        /** A passage the model cannot accept, which means the caller's measurement was wrong. */
        const val EMBEDDING_INPUT_TOO_LONG_CODE: String = "EMBEDDING_INPUT_TOO_LONG"

        /** A query so long that embedding it would mean answering a different question. */
        const val QUERY_OVERLONG_CODE: String = "QUERY_TOO_LONG"

        /** A query with no text to embed. */
        const val EMPTY_QUERY_CODE: String = "EMPTY_QUERY"

        /** The device could not hold even one passage. */
        const val OUT_OF_MEMORY_CODE: String = "EMBEDDING_OUT_OF_MEMORY"

        /** Tokens of already-encoded passage text one batch may carry, so device memory stays bounded. */
        const val DEFAULT_BATCH_TOKEN_BUDGET: Int = 8 * 1024

        private const val REVISION_PREFIX_LENGTH: Int = 8
        private const val NO_SPAN: Int = -1

        private val LOGGER = LoggerFactory.getLogger(E5Embedder::class.java)

        /** Named log fields, so nothing private has to be put into the message to say what happened. */
        private const val FIELD_COMPONENT = "component"
        private const val FIELD_TOKENIZER = "tokenizer"
        private const val COMPONENT = "embedding"

        /**
         * The embedding model of an installed, verified model, with its accelerator created on first use.
         *
         * Laziness is deliberate: the tokenizer is needed to chunk and the accelerator to embed, so requiring
         * the GPU up front would make a machine without one unable to ingest at all.
         */
        fun production(
            manifest: ModelManifest,
            installation: ModelInstallation,
            gpu: GpuRuntime = GpuRuntime(manifest),
            profileDirectory: Path,
            batchTokenBudget: Int = DEFAULT_BATCH_TOKEN_BUDGET,
        ): E5Embedder = E5Embedder(
            manifest = manifest,
            tokenizerPath = installation.tokenizerPath,
            runner = CoreMlRunner(
                gpu = gpu,
                installation = installation,
                profileDirectory = profileDirectory,
                dimension = manifest.dimension,
                maxSequenceTokens = manifest.maxSequenceTokens,
            ),
            batchTokenBudget = batchTokenBudget,
        )

        /**
         * The counter production chunking measures with.
         *
         * The model's own tokenizer measures a passage when the model is installed, so what the chunker fits
         * and what the embedder consumes are the same measurement. When it is not installed the provisional
         * counter measures instead, and that is not a silent degradation: it names itself in
         * `document_chunking`, so the re-chunking pass that runs once the model arrives replaces those chunks
         * without touching the extracted text or the OCR checkpoints. Extraction is the expensive half of
         * ingestion and none of it needs a vector, so an archive with no model yet can still be ingested —
         * and embedding itself never falls back, it reports the remedy and the document stops short of
         * complete.
         *
         * The choice is made on first use and by presence and size rather than by hashing: nothing about
         * starting the server, serving a request or reading an existing document should wait on 1.1 GB of
         * digests.
         */
        fun productionCounter(
            modelsDir: Path,
            profileDirectory: Path,
            fallback: TokenCounter = WhitespaceTokenCounter(),
        ): TokenCounter = LazyTokenCounter {
            val manifest = ModelManifest.load()
            if (!ModelManager(manifest).isInstalled(modelsDir)) {
                LOGGER.atWarn()
                    .addKeyValue(FIELD_COMPONENT, COMPONENT)
                    .addKeyValue(FIELD_TOKENIZER, fallback.id)
                    .log("the embedding model is not installed, so passages are measured provisionally")
                return@LazyTokenCounter fallback
            }
            production(
                manifest = manifest,
                installation = ModelInstallation(manifest, modelsDir.resolve(manifest.revision)),
                profileDirectory = profileDirectory,
            )
        }

        /** Splits row lengths into batches whose padded total stays inside [batchTokenBudget]. */
        internal fun planBatches(lengths: List<Int>, batchTokenBudget: Int): List<IntRange> {
            if (lengths.isEmpty()) return emptyList()
            val batches = mutableListOf<IntRange>()
            var start = 0
            while (start < lengths.size) {
                val longest = lengths.subList(start, lengths.size).max()
                var end = start
                while (end < lengths.size && longest * (end - start + 1) <= batchTokenBudget) end++
                // One passage longer than the whole budget is still one batch: refusing it belongs to the
                // caller's budget check, not to a planner that would drop it.
                if (end == start) end = start + 1
                batches += start until end
                start = end
            }
            return batches
        }

        /** Pads every row in a batch to the batch's longest, with pad tokens masked out. */
        internal fun padRows(rows: List<EncodedRow>): Pair<Array<LongArray>, Array<LongArray>> {
            val length = rows.maxOf { it.ids.size }
            val ids = Array(rows.size) { index -> pad(rows[index].ids, length) }
            val masks = Array(rows.size) { index -> pad(rows[index].mask, length) }
            return ids to masks
        }

        private fun pad(source: LongArray, length: Int): LongArray {
            if (source.size == length) return source
            val padded = LongArray(length)
            source.copyInto(padded)
            return padded
        }

        /**
         * Whether a failure means the device ran out of room.
         *
         * The check is on the type and the message because ONNX Runtime reports an allocation failure as an
         * `OrtException` from the provider, and the alternative — treating every exception as retryable —
         * would silently turn a genuine model error into a head-of-line stall.
         */
        internal fun isOutOfMemory(failure: Throwable): Boolean {
            if (failure is OutOfMemoryError) return true
            val message = failure.message?.lowercase() ?: return false
            return message.contains("out of memory") ||
                message.contains("oom") ||
                message.contains("failed to allocate") ||
                message.contains("memory allocation")
        }
    }
}

/** The model could not build a vector for a reason the caller has to act on. */
class EmbeddingException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Runs the model through ONNX Runtime, pooling and normalising what comes back.
 *
 * The session is created on first use and verified then: creating it profiles a warm-up run, so the cost of
 * proving the accelerator is paid once per process and never on the request path.
 */
internal class CoreMlRunner(
    private val gpu: GpuRuntime,
    private val installation: ModelInstallation,
    private val profileDirectory: Path,
    private val dimension: Int,
    private val maxSequenceTokens: Int,
) : EmbeddingRunner {

    private var verified: VerifiedSession? = null

    override fun embed(ids: Array<LongArray>, masks: Array<LongArray>): List<FloatArray> {
        val session = session()
        val environment = OrtEnvironment.getEnvironment()
        val batch = ids.size
        val length = ids.first().size
        val flatIds = LongArray(batch * length)
        val flatMasks = LongArray(batch * length)
        ids.forEachIndexed { row, values -> values.copyInto(flatIds, row * length) }
        masks.forEachIndexed { row, values -> values.copyInto(flatMasks, row * length) }
        val shape = longArrayOf(batch.toLong(), length.toLong())
        val idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(flatIds), shape)
        val maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(flatMasks), shape)
        
        val inputNames = session.session.inputNames.toList()
        val maskName = inputNames.firstOrNull { it.contains("mask") }
            ?: error("the pinned model has no attention mask input, only $inputNames")
        val idsName = inputNames.first { it != maskName }
        val inputs = mapOf(idsName to idsTensor, maskName to maskTensor)
        try {
            session.session.run(inputs).use { result ->
                val hidden = result.get(result.iterator().next().key).orElseThrow {
                    GpuUnavailableException(GpuRuntime.GPU_UNAVAILABLE_CODE, "the model returned no output")
                }
                val tensor = hidden as OnnxTensor
                val outputShape = tensor.info.shape
                val hiddenLength = outputShape[outputShape.size - 2].toInt()
                val buffer: FloatBuffer = tensor.floatBuffer
                val flattened = FloatArray(buffer.remaining())
                buffer.get(flattened)
                return poolAndNormalise(
                    hidden = flattened,
                    rowCount = batch,
                    rowLength = hiddenLength,
                    dimension = dimension,
                    masks = masks,
                )
            }
        } finally {
            idsTensor.close()
            maskTensor.close()
        }
    }

    private fun session(): VerifiedSession = verified ?: synchronized(this) {
        verified ?: gpu.createSession(
            modelPath = installation.onnxPath,
            profileDirectory = profileDirectory,
            warmUp = { ortSession -> warmUp(ortSession) },
        ).also { verified = it }
    }

    /**
     * One inference with synthetic public text.
     *
     * It has to be real work: the provider compiles its program on the first inference, and a provider that
     * never executes a node is exactly what the profile afterwards is checked for.
     */
    private fun warmUp(ortSession: ai.onnxruntime.OrtSession) {
        val environment = OrtEnvironment.getEnvironment()
        val length = minOf(WARM_UP_TOKENS, maxSequenceTokens)
        val ids = LongArray(length) { index -> if (index == 0) START_TOKEN else index.toLong() }
        val mask = LongArray(length) { 1L }
        val tensors = ortSession.inputNames.associateWith { name ->
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(if (name.contains("mask")) mask else ids),
                longArrayOf(1, length.toLong()),
            )
        }
        try {
            ortSession.run(tensors).close()
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    override fun close() {
        verified?.close()
        verified = null
    }

    private companion object {
        const val WARM_UP_TOKENS: Int = 8

        /** The tokenizer's beginning-of-sequence id; a real id, so the graph runs for real. */
        const val START_TOKEN: Long = 0L
    }
}

/**
 * Averages the model's token states under the attention mask and normalises the result.
 *
 * Mean pooling under the mask is the convention the model was trained with, so the mask is not optional: a
 * plain mean would divide the sum by the padding and return a vector that is not the sentence's.
 */
internal fun poolAndNormalise(
    hidden: FloatArray,
    rowCount: Int,
    rowLength: Int,
    dimension: Int,
    masks: Array<LongArray>,
): List<FloatArray> {
    require(hidden.size >= rowCount * rowLength * dimension) {
        "the model returned ${hidden.size} values for $rowCount rows of $rowLength x $dimension"
    }
    return (0 until rowCount).map { row ->
        val vector = FloatArray(dimension)
        var counted = 0
        for (position in 0 until rowLength) {
            if (masks.getOrNull(row)?.getOrNull(position) != 1L) continue
            counted++
            val base = (row * rowLength + position) * dimension
            for (axis in 0 until dimension) vector[axis] += hidden[base + axis]
        }
        require(counted > 0) { "row $row has no unmasked token to pool" }
        for (axis in 0 until dimension) vector[axis] /= counted.toFloat()
        l2Normalise(vector)
        vector
    }
}

/** Scales a vector to unit length, which is what makes a dot product a cosine similarity. */
internal fun l2Normalise(vector: FloatArray) {
    var sum = 0.0
    vector.forEach { sum += it.toDouble() * it }
    val norm = sqrt(sum).toFloat()
    require(norm > 0f) { "a pooled embedding has zero length and cannot be normalised" }
    for (index in vector.indices) vector[index] /= norm
}

/**
 * A counter that is built the first time it is asked for a measurement.
 *
 * Production chunking needs the model's tokenizer, and the model is 1.1 GB that a person installs
 * deliberately. Building it eagerly would put that requirement on process startup, where a missing
 * installation would stop the archive from serving anything at all — including the keyword search and source
 * viewing that do not need embeddings. Building it here instead makes the missing model a per-document
 * failure whose message names the remedy, which is where the person who has to act on it is looking.
 */
internal class LazyTokenCounter(private val build: () -> TokenCounter) : TokenCounter {

    private val delegate: TokenCounter by lazy(build)

    override val id: String get() = delegate.id

    override fun encodePassage(text: String): EncodedPassage = delegate.encodePassage(text)
}
