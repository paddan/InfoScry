package infoscry.embedding

/**
 * Turns passages into vectors.
 *
 * [E5Embedder] is the production implementation; the tests substitute a deterministic fake so the
 * indexing stage can be exercised without the pinned model or the accelerator, which is exactly the
 * sort of named fake boundary the mutation gate expects.
 */
fun interface DocumentEmbedder {

    /** Embeds each passage, in order, as one model-scheduled batch. */
    fun embedDocuments(texts: List<String>): List<FloatArray>
}