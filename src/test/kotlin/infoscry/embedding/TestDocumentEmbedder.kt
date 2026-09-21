package infoscry.embedding

import kotlin.math.sqrt

/**
 * Deterministic vectors for the indexing tests, one per passage.
 *
 * The import stage needs to embed chunks, and a test data directory has no model and must not
 * download one. The vectors are unit-length and stable for a text, so an index built with them is
 * reproducible, which is what the idempotency tests assert.
 */
internal class TestDocumentEmbedder(private val dimension: Int = 768) : DocumentEmbedder {

    override fun embedDocuments(texts: List<String>): List<FloatArray> = texts.map(::vectorFor)

    private fun vectorFor(text: String): FloatArray {
        val seed = text.hashCode()
        val random = java.util.Random(seed.toLong())
        val vector = FloatArray(dimension) { index ->
            random.nextFloat() * if (index % 2 == 0) 1f else -1f
        }
        val norm = sqrt(vector.sumOf { value -> value.toDouble() * value }.toFloat())
        for (index in vector.indices) vector[index] = if (norm == 0f) 0f else vector[index] / norm
        return vector
    }
}