package infoscry.search

import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation

/**
 * The three retrieval modes.
 *
 * [HYBRID] is the default: both branches run and their rankings are fused by reciprocal rank fusion, so a
 * result that matched by keyword but not semantically (or the reverse) still ranks. [KEYWORD] and [SEMANTIC]
 * run a single branch and are the honest building blocks a user chooses between.
 */
enum class SearchMode {
    KEYWORD,
    SEMANTIC,
    HYBRID,
    ;

    companion object {
        val DEFAULT: SearchMode = HYBRID
    }
}

/**
 * What a search is restricted to.
 *
 * [collectionId] is applied inside Lucene (it is an exact index filter). The remaining criteria describe
 * the *document* the chunk belongs to — media type, filename or original path, title/author/language probe,
 * ISO import-date bounds, processing status, and whether the content was OCR-derived — and are applied by
 * pre-selecting matching documents from SQLite **before** Lucene retrieval, never as a post-filter over
 * results.
 *
 * Dates are ISO-8601 UTC strings (the persistence boundary's format), so they compare lexicographically.
 */
data class SearchFilters(
    val collectionId: CollectionId? = null,
    val mediaTypes: Set<String> = emptySet(),
    val filenameOrPathContains: String? = null,
    val titleAuthorOrLanguageContains: String? = null,
    val importedFrom: String? = null,
    val importedUntil: String? = null,
    val statuses: Set<DocumentStatus> = emptySet(),
    val ocrOnly: Boolean = false,
) {

    /** Whether any document-level criterion (beyond the collection) is set. */
    val hasDocumentCriteria: Boolean
        get() = mediaTypes.isNotEmpty() ||
            filenameOrPathContains != null ||
            titleAuthorOrLanguageContains != null ||
            importedFrom != null ||
            importedUntil != null ||
            statuses.isNotEmpty() ||
            ocrOnly
}

/**
 * One search result: the exact unit a question matched, its citation, what matched, and — for keyword
 * hits — a snippet with the matched terms marked.
 *
 * [locator] is the decoded [SourceLocation] of the unit. [matchedBy] carries the mode badge or badges;
 * raw BM25 and vector scores are never exposed, only this comparable evidence.
 */
data class SearchHit(
    val collectionId: CollectionId,
    val documentId: DocumentId,
    val unitId: ContentUnitId,
    val chunkOrdinal: Int,
    val text: String,
    val highlighted: String?,
    val locator: SourceLocation,
    val locatorLabel: String,
    val matchedBy: Set<SearchMode>,
)

/** The outcome of one search: the ranked hits plus how many index rows were dropped as stale (DB-deleted). */
data class SearchOutcome(
    val hits: List<SearchHit>,
    val staleFiltered: Int,
)

/**
 * A search that cannot serve the request: the model is not installed, the GPU is unavailable, the index
 * needs a rebuild, or the query itself is unembeddable. [code] and [remedy] are typed so a route can turn
 * them into an actionable response instead of a 500.
 */
class SearchUnavailableException(
    val code: String,
    val remedy: String,
) : IllegalStateException("$code: $remedy")

/**
 * The retrieval identity of one chunk: the pair of opaque ids and the ordinal that make a Lucene row
 * the same row across the two branches of a hybrid search.
 */
data class HitKey(
    val collectionId: String,
    val documentId: String,
    val unitId: String,
    val chunkOrdinal: Int,
)

/** One fused result: the representative [IndexHit] plus which branch or branches matched it. */
data class FusedSearchHit(
    val hit: IndexHit,
    val matchedBy: Set<SearchMode>,
    val score: Double,
)

/**
 * Reciprocal rank fusion, exactly: `1 / (k + rank)` per branch with ranks starting at one, zero for a
 * branch the hit was absent from.
 *
 * Pure by design — it takes already-ordered lists and returns a fused order, so its arithmetic is testable
 * without an index. Ties break deterministically by identity, and the result is cut to [top].
 */
fun reciprocalRankFusion(
    keyword: List<IndexHit>,
    semantic: List<IndexHit>,
    k: Int = 60,
    top: Int = 30,
): List<FusedSearchHit> {
    require(k > 0) { "k must be positive, was $k" }
    require(top > 0) { "top must be positive, was $top" }

    val ranked = LinkedHashMap<HitKey, MutableRankedHit>()
    keyword.forEachIndexed { index, hit ->
        ranked.getOrPut(hit.toKey()) { MutableRankedHit(hit) }.keywordRank = index + 1
    }
    semantic.forEachIndexed { index, hit ->
        ranked.getOrPut(hit.toKey()) { MutableRankedHit(hit) }.semanticRank = index + 1
    }

    return ranked.values
        .map { rankedHit ->
            val score = keywordContribution(rankedHit.keywordRank, k) +
                keywordContribution(rankedHit.semanticRank, k)
            FusedSearchHit(
                hit = rankedHit.hit,
                matchedBy = buildSet {
                    if (rankedHit.keywordRank != null) add(SearchMode.KEYWORD)
                    if (rankedHit.semanticRank != null) add(SearchMode.SEMANTIC)
                },
                score = score,
            )
        }
        .sortedWith(
            compareByDescending<FusedSearchHit> { it.score }
                .thenBy { it.hit.collectionId }
                .thenBy { it.hit.documentId }
                .thenBy { it.hit.unitId }
                .thenBy { it.hit.chunkOrdinal },
        )
        .take(top)
}

private fun IndexHit.toKey(): HitKey = HitKey(collectionId, documentId, unitId, chunkOrdinal)

private fun keywordContribution(rank: Int?, k: Int): Double = if (rank == null) 0.0 else 1.0 / (k + rank)

/** Mutable aggregation slot for one [HitKey] while fusion runs. */
private class MutableRankedHit(
    val hit: IndexHit,
) {
    var keywordRank: Int? = null
    var semanticRank: Int? = null
}