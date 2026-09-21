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
import infoscry.logging.LogFields
import infoscry.storage.CollectionStore
import infoscry.storage.DocumentCriterion
import infoscry.storage.DocumentStore
import java.util.Locale
import kotlinx.serialization.json.Json
import org.apache.lucene.search.IndexSearcher
import org.slf4j.LoggerFactory

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
    private val index: () -> LuceneIndex,
    private val queryEmbedder: () -> QueryEmbedder?,
    private val maxScopeTerms: Int = DEFAULT_MAX_SCOPE_TERMS,
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
        if (documentIds != null && documentIds.size > maxScopeTerms) {
            throw SearchUnavailableException(
                FILTER_TOO_BROAD_CODE,
                "the filter matched ${documentIds.size} documents; narrow it " +
                    "(media type, processing status, or date range) and search again",
            )
        }

        val keywordHits = when (mode) {
            SearchMode.KEYWORD, SearchMode.HYBRID ->
                searchRefusingOverbroad {
                    index().searchKeyword(filters.collectionId, queryText, documentIds, TOP_PER_BRANCH)
                }

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

    /**
     * Wraps a Lucene branch so a pathological index cannot surface as a server error.
     *
     * The pre-parse guard bounds how many tokens a query may analyze into, but a short query with a
     * broad wildcard expands at *rewrite* time, inside the searcher, and a rewrite can still run past
     * Lucene's clause ceiling on an index large enough to feed it. That is the caller's query doing
     * it, not a corrupt index, so it is answered with the same shape every other caller mistake uses.
     */
    private inline fun <T> searchRefusingOverbroad(block: () -> T): T = try {
        block()
    } catch (tooMany: IndexSearcher.TooManyClauses) {
        throw SearchUnavailableException(
            QUERY_TOO_BROAD_CODE,
            "the query expands to more clauses than the index can hold; narrow the wildcards or " +
                "shorten it and search again",
        )
    }

    private fun semanticSearch(
        queryText: String,
        filters: SearchFilters,
        documentIds: Set<String>?,
    ): List<IndexHit> {
        val status = index().schemaStatus
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
        return searchRefusingOverbroad {
            index().searchVector(filters.collectionId, vector, documentIds, TOP_PER_BRANCH)
        }
    }

    private fun publish(fused: List<FusedSearchHit>, queryText: String): SearchOutcome {
        var stale = 0
        val hits = buildList {
            for (fusedHit in fused) {
                val resolved = resolveHit(fusedHit, queryText)
                if (resolved == null) {
                    stale++
                } else {
                    add(resolved)
                }
            }
        }
        return SearchOutcome(hits, stale)
    }

    /**
     * Validates one fused hit against the live database and decodes its stored locator into a citable
     * [SearchHit]. Returns null when the hit must not surface: its collection or document no longer
     * exists or is tombstoned, or its stored locator no longer decodes (an older schema's JSON after a
     * locator variant changed, or a corrupt row). An undecodable locator is dropped and counted with the
     * stale rows — a fabricated fallback citation would be worse than the miss, because this
     * application's core promise is exact page/section/cell citations.
     */
    internal fun resolveHit(fusedHit: FusedSearchHit, queryText: String): SearchHit? {
        val collection = collections.get(CollectionId(fusedHit.hit.collectionId))
        val document = documents.get(DocumentId(fusedHit.hit.documentId))
        val live = collection != null &&
            collection.lifecycle == CollectionLifecycle.ACTIVE &&
            document != null
        if (!live) return null

        val locator = decodeLocator(fusedHit.hit.locator) ?: run {
            LOGGER.atWarn()
                .addKeyValue(LogFields.COMPONENT, COMPONENT_VALUE)
                .addKeyValue(LogFields.DOCUMENT, fusedHit.hit.documentId)
                .addKeyValue(UNIT_ID_FIELD, fusedHit.hit.unitId)
                .log("a search hit's stored locator could not be decoded; dropping the hit")
            return null
        }
        return SearchHit(
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
            locator = locator,
            locatorLabel = fusedHit.hit.locatorLabel,
            matchedBy = fusedHit.matchedBy,
        )
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
        queryText.lowercase(Locale.ROOT)
            .replace("\"", " ")
            .split(Regex("[^a-z0-9äöüß]+"))
            .filter { it.isNotBlank() }
            .distinct()

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun decodeLocator(locator: String): SourceLocation? =
        try {
            LOCATOR_JSON.decodeFromString(SourceLocation.serializer(), locator)
        } catch (failure: Exception) {
            null
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
        const val FILTER_TOO_BROAD_CODE: String = "FILTER_TOO_BROAD"

        /** The code a query carries when Lucene's clause ceiling stops it during a rewrite. */
        const val QUERY_TOO_BROAD_CODE: String = "QUERY_TOO_BROAD"

        /**
         * How many candidate documents a document-level filter may select before a search is refused.
         *
         * The scope itself is a set query, so a broad criterion is not a clause-ceiling problem — the bound is
         * an early, cheap, actionable refusal instead of doing a 50 000-term scope and a ranking pass over its
         * results. Five times the plan's 10 000-document archive target, so it does not bite a real archive;
         * beyond it, "narrow the filter" is a better answer than a slow search.
         */
        const val DEFAULT_MAX_SCOPE_TERMS: Int = 50_000

        private val LOCATOR_JSON = Json { ignoreUnknownKeys = true }
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.search")

/** The structured top-level component name for search logs. */
private const val COMPONENT_VALUE: String = "search"

/** The structured field carrying the unit id of a hit dropped because its locator would not decode. */
private const val UNIT_ID_FIELD: String = "unit_id"