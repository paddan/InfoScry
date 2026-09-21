package infoscry.search

import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.embedding.EmbeddingException
import infoscry.embedding.GpuRuntime
import infoscry.embedding.GpuUnavailableException
import infoscry.embedding.ModelManager
import infoscry.embedding.QueryEmbedder
import infoscry.storage.CollectionStore
import infoscry.storage.DocumentCriterion
import infoscry.storage.DocumentStore
import kotlinx.serialization.json.Json

/**
 * Retrieval over one index: keyword, semantic, or hybrid, restricted before Lucene by the document
 * criteria and validated against the live database after fusion.
 *
 * The ordering principle that matters: criteria are applied by pre-selecting document ids from SQLite
 * and narrowing the Lucene query to them, never by filtering results after ranking. Then the fused hits
 * are re-checked against the database, because an index can hold rows whose document or collection the
 * database no longer wants searchable (a deleted document, a tombstoned collection) — those rows are
 * dropped at this boundary, never surfaced.
 *
 * The query embedder is the search-side twin of the import stage's document embedder: resolved lazily
 * and only for a semantic or hybrid request, so keyword search and source reads never wait on the model
 * or the accelerator. A missing model, an unavailable GPU, or an index that does not match the pinned
 * model all fail closed with an actionable code and remedy — never a CPU fallback and never a 500.
 */
class SearchService(
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val index: LuceneIndex,
    private val queryEmbedder: () -> QueryEmbedder?,
) {

    /**
     * Runs one search.
     *
     * @param queryText the user's query; blank is an empty result, and syntax the classic parser cannot
     *   read is escaped inside [LuceneIndex] rather than surfacing as an error.
     * @param mode the retrieval mode, hybrid by default.
     * @param filters what the result is restricted to, before retrieval.
     */
    fun search(
        queryText: String,
        mode: SearchMode = SearchMode.DEFAULT,
        filters: SearchFilters = SearchFilters(),
    ): SearchOutcome {
        if (queryText.isBlank()) return SearchOutcome(emptyList(), 0)

        // Criteria restrict the Lucene query itself. An empty candidate set means an empty result — the
        // index is not touched at all.
        val documentIds: Set<String>? = if (filters.hasDocumentCriteria) {
            documents.findIds(filters.toCriterion()).map { it.value }.toSet()
        } else {
            null
        }
        if (documentIds != null && documentIds.isEmpty()) return SearchOutcome(emptyList(), 0)

        val keywordHits = when (mode) {
            SearchMode.KEYWORD, SearchMode.HYBRID ->
                index.searchKeyword(filters.collectionId, queryText, documentIds, TOP_PER_BRANCH)

            SearchMode.SEMANTIC -> emptyList()
        }

        val semanticHits = when (mode) {
            SearchMode.SEMANTIC, SearchMode.HYBRID -> semanticSearch(queryText, filters, documentIds)
            SearchMode.KEYWORD -> emptyList()
        }

        val fused = when (mode) {
            SearchMode.HYBRID -> reciprocalRankFusion(keywordHits, semanticHits, top = FUSION_TOP)
            SearchMode.KEYWORD -> keywordHits.map { FusedSearchHit(it, setOf(SearchMode.KEYWORD), 0.0) }
            SearchMode.SEMANTIC -> semanticHits.map { FusedSearchHit(it, setOf(SearchMode.SEMANTIC), 0.0) }
        }

        return publish(fused, queryText)
    }

    private fun semanticSearch(
        queryText: String,
        filters: SearchFilters,
        documentIds: Set<String>?,
    ): List<IndexHit> {
        val status = index.schemaStatus
        if (status !is SchemaStatus.Ready) {
            throw SearchUnavailableException(
                REBUILD_REQUIRED_CODE,
                "run `infoscry reindex` to rebuild the index for the pinned model",
            )
        }
        val embedder = queryEmbedder()
            ?: throw SearchUnavailableException(
                ModelManager.MODEL_NOT_INSTALLED_CODE,
                ModelManager.installRemedy(),
            )
        val vector = try {
            embedder.embedQuery(queryText)
        } catch (failure: GpuUnavailableException) {
            throw SearchUnavailableException(GpuRuntime.GPU_UNAVAILABLE_CODE, GpuRuntime.remedy())
        } catch (failure: EmbeddingException) {
            throw SearchUnavailableException(failure.code, failure.message ?: "the query could not be embedded")
        }
        return index.searchVector(filters.collectionId, vector, documentIds, TOP_PER_BRANCH)
    }

    private fun publish(fused: List<FusedSearchHit>, queryText: String): SearchOutcome {
        var stale = 0
        val hits = buildList {
            for (fusedHit in fused) {
                val collection = collections.get(CollectionId(fusedHit.hit.collectionId))
                val document = documents.get(DocumentId(fusedHit.hit.documentId))
                val live = collection != null &&
                    collection.lifecycle == CollectionLifecycle.ACTIVE &&
                    document != null
                if (!live) {
                    stale++
                    continue
                }
                add(
                    SearchHit(
                        collectionId = CollectionId(fusedHit.hit.collectionId),
                        documentId = DocumentId(fusedHit.hit.documentId),
                        unitId = ContentUnitId(fusedHit.hit.unitId),
                        chunkOrdinal = fusedHit.hit.chunkOrdinal,
                        text = fusedHit.hit.text,
                        highlighted = if (SearchMode.KEYWORD in fusedHit.matchedBy) {
                            highlight(fusedHit.hit.text, queryText)
                        } else {
                            null
                        },
                        locator = decodeLocator(fusedHit.hit.locator),
                        locatorLabel = fusedHit.hit.locatorLabel,
                        matchedBy = fusedHit.matchedBy,
                    ),
                )
            }
        }
        return SearchOutcome(hits, stale)
    }

    /**
     * Marks the query's terms in [text] with `<mark>`, HTML-escaping the rest so a search pane can
     * render the fragment as safe markup. Terms come from the query's words; a term absent from the text
     * leaves the text unmarked rather than emitting a partial tag.
     */
    private fun highlight(text: String, queryText: String): String? {
        val terms = queryTerms(queryText)
        if (terms.isEmpty()) return null
        val escaped = escapeHtml(text)
        var result = escaped
        var marked = false
        for (term in terms) {
            val target = "<mark>${escapeHtml(term)}</mark>"
            val replaced = result.replace(Regex(Regex.escape(term), RegexOption.IGNORE_CASE), target)
            if (replaced != result) {
                result = replaced
                marked = true
            }
        }
        return result.takeIf { marked }
    }

    private fun queryTerms(queryText: String): List<String> =
        queryText.lowercase()
            .replace("\"", " ")
            .split(Regex("[^a-z0-9äöüß]+"))
            .filter { it.isNotBlank() }
            .distinct()

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun decodeLocator(locator: String): SourceLocation =
        try {
            LOCATOR_JSON.decodeFromString(SourceLocation.serializer(), locator)
        } catch (failure: Exception) {
            SourceLocation.TextLines(1, 1)
        }

    private fun SearchFilters.toCriterion(): DocumentCriterion = DocumentCriterion(
        collectionId = collectionId,
        mediaTypes = mediaTypes,
        filenameOrPathContains = filenameOrPathContains,
        titleAuthorOrLanguageContains = titleAuthorOrLanguageContains,
        importedFrom = importedFrom,
        importedUntil = importedUntil,
        statuses = statuses,
        ocrOnly = ocrOnly,
    )

    companion object {
        const val TOP_PER_BRANCH: Int = 50
        const val FUSION_TOP: Int = 30
        const val REBUILD_REQUIRED_CODE: String = "INDEX_REBUILD_REQUIRED"

        private val LOCATOR_JSON = Json { ignoreUnknownKeys = true }
    }
}