package infoscry.embedding

/**
 * Embeds one search query into the same vector space as document passages.
 *
 * A query embeds under the `query: ` prefix while passages embed under `passage: ` (the E5 contract), so
 * the query path is deliberately a separate seam from [DocumentEmbedder]: the model that serves both is
 * [E5Embedder], and the seam exists so search can be tested with a deterministic vector without a GPU.
 *
 * Queries are refused rather than truncated — an overlong or blank query fails with an actionable error,
 * never a silently shortened embedding of the wrong question.
 */
fun interface QueryEmbedder {

    /** Embeds [query], returning one 768-dimensional unit vector, or refusing it with an actionable error. */
    fun embedQuery(query: String): FloatArray
}