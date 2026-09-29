package infoscry.search

import infoscry.chunk.hasSearchableWord
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
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.Normalizer2
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
 * dropped at this boundary, never surfaced. Blank and known artifact-only rows are dropped here too:
 * a generation indexed before the chunking rule can still hold one.
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
     * @param requireLexicalAnchor whether hybrid retrieval may publish semantic-only hits for a query
     *   with no lexical foothold in the archive. The interactive Search box passes true, because the
     *   pinned embedding model cannot answer "is this relevant": its similarity scores are deliberately
     *   not calibrated (the E5 model card documents that they distribute between 0.7 and 1.0 and that only
     *   their relative order means anything), so nearest neighbours exist for gibberish too. A query that
     *   shares no token with the archive's own words is answered with nothing rather than with the
     *   passages closest to nonsense. Ask and Investigate leave it false, because their questions are
     *   meant to retrieve by meaning even when they share no wording with the sources.
     */
    fun search(
        queryText: String,
        mode: SearchMode = SearchMode.DEFAULT,
        filters: SearchFilters = SearchFilters(),
        requireLexicalAnchor: Boolean = false,
    ): SearchOutcome {
        val normalizedFilters = filters.normalized()
        if (queryText.isBlank()) return SearchOutcome(emptyList(), 0)

        // Criteria restrict the Lucene query itself. An empty candidate set means an empty result — the
        // index is not touched at all.
        val documentIds: Set<String>? = if (normalizedFilters.hasDocumentCriteria) {
            documents.findIds(normalizedFilters.toCriterion()).map { it.value }.toSet()
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

        val keyword = when (mode) {
            SearchMode.KEYWORD, SearchMode.HYBRID -> validatedCandidates(SearchMode.KEYWORD, queryText) { limit ->
                searchRefusingOverbroad {
                    index().searchKeyword(normalizedFilters.collectionId, queryText, documentIds, limit)
                }
            }
            SearchMode.SEMANTIC -> ValidatedCandidates.empty()
        }

        val semantic = when (mode) {
            SearchMode.SEMANTIC -> semanticSearch(queryText, normalizedFilters, documentIds)
            SearchMode.HYBRID -> if (semanticsApply(
                    queryText,
                    keyword.rawCandidates,
                    normalizedFilters,
                    documentIds,
                    requireLexicalAnchor,
                )
            ) semanticSearch(queryText, normalizedFilters, documentIds) else ValidatedCandidates.empty()
            SearchMode.KEYWORD -> ValidatedCandidates.empty()
        }
        val fused = when (mode) {
            SearchMode.HYBRID -> reciprocalRankFusion(keyword.hits, semantic.hits, top = FUSION_TOP)
            SearchMode.KEYWORD -> keyword.hits.take(TOP_PER_BRANCH)
                .map { FusedSearchHit(it, setOf(SearchMode.KEYWORD), 0.0) }
            SearchMode.SEMANTIC -> semantic.hits.take(TOP_PER_BRANCH)
                .map { FusedSearchHit(it, setOf(SearchMode.SEMANTIC), 0.0) }
        }

        val resolved = keyword.resolved + semantic.resolved.filterKeys { it !in keyword.resolved }
        return publish(fused, queryText, resolved, (keyword.staleKeys + semantic.staleKeys).size)
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
    ): ValidatedCandidates {
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
        val candidateCount = index().vectorCandidateCount(filters.collectionId, documentIds)
        return validatedCandidates(SearchMode.SEMANTIC, queryText, candidateCount) { limit ->
            searchRefusingOverbroad {
                index().searchVector(filters.collectionId, vector, documentIds, limit)
            }
        }
    }

    /**
     * Whether the semantic half of a hybrid search may run.
     *
     * A query the keyword half already matched is grounded by definition. Otherwise an interactive search
     * asks [LuceneIndex.hasLexicalAnchor] before spending an embedding on a query the archive has never
     * seen a word of — the answer that keeps the semantic branch from inventing matches for nonsense.
     */
    private fun semanticsApply(
        queryText: String,
        keywordHits: List<IndexHit>,
        filters: SearchFilters,
        documentIds: Set<String>?,
        requireLexicalAnchor: Boolean,
    ): Boolean {
        if (!requireLexicalAnchor || keywordHits.isNotEmpty()) return true
        return index().hasLexicalAnchor(queryText, filters.collectionId, documentIds)
    }

    /**
     * Validate before branch rank fusion, so bad rows cannot consume one of the visible result slots.
     * Lucene returns the current top N rather than pages. Grow N geometrically and inspect only newly
     * exposed candidates until the original 50-result branch is full or Lucene is exhausted. Thus the
     * ordinary path validates at most 50 rows, while a legacy artifact-heavy index can refill correctly.
     * Vector searches also compare returned hits with the scoped vector count because Lucene's approximate
     * KNN may return fewer than k before it has exhausted the matching vectors.
     */
    private fun validatedCandidates(
        mode: SearchMode,
        queryText: String,
        expectedCandidateCount: Int? = null,
        retrieve: (Int) -> List<IndexHit>,
    ): ValidatedCandidates {
        val checked = mutableMapOf<HitKey, CandidateValidation>()
        var requested = TOP_PER_BRANCH
        var candidates = retrieve(requested)
        while (true) {
            var rankedValid = 0
            for (hit in candidates) {
                val key = hit.searchKey()
                val validation = checked.getOrPut(key) {
                    // A blank or known artifact passage is not a result and is not stale.
                    if (!hasSearchableWord(hit.text)) CandidateValidation()
                    else {
                        val published = resolveHit(FusedSearchHit(hit, setOf(mode), 0.0), queryText)
                        CandidateValidation(published, stale = published == null)
                    }
                }
                if (validation.resolved != null) rankedValid++
                if (rankedValid >= TOP_PER_BRANCH) break
            }
            val exhausted = if (expectedCandidateCount == null) {
                candidates.size < requested
            } else {
                candidates.size >= expectedCandidateCount
            }
            if (rankedValid >= TOP_PER_BRANCH || exhausted || requested == Int.MAX_VALUE) break
            requested = (requested.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            candidates = retrieve(requested)
        }
        val valid = candidates.asSequence().mapNotNull { hit ->
            checked[hit.searchKey()]?.takeIf { it.resolved != null }?.let { hit }
        }.take(TOP_PER_BRANCH).toList()
        val resolved = checked.mapNotNull { (key, value) -> value.resolved?.let { key to it } }.toMap()
        val stale = checked.filterValues { it.stale }.keys
        return ValidatedCandidates(valid, resolved, stale, candidates)
    }

    private fun publish(
        fused: List<FusedSearchHit>,
        queryText: String,
        resolved: Map<HitKey, SearchHit>,
        staleCount: Int,
    ): SearchOutcome = SearchOutcome(
        hits = fused.mapNotNull { fusedHit ->
            val key = fusedHit.hit.searchKey()
            val hit = resolved[key] ?: return@mapNotNull null
            hit.copy(
                matchedBy = fusedHit.matchedBy,
                highlighted = if (SearchMode.KEYWORD in fusedHit.matchedBy) {
                    hit.highlighted ?: highlight(hit.text, queryText)
                } else null,
            )
        },
        staleFiltered = staleCount,
    )

    private data class ValidatedCandidates(
        val hits: List<IndexHit>,
        val resolved: Map<HitKey, SearchHit>,
        val staleKeys: Set<HitKey>,
        val rawCandidates: List<IndexHit>,
    ) {
        companion object {
            fun empty() = ValidatedCandidates(emptyList(), emptyMap(), emptySet(), emptyList())
        }
    }

    private data class CandidateValidation(
        val resolved: SearchHit? = null,
        val stale: Boolean = false,
    )

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

    /** Marks matched source tokens in one pass, escaping every untrusted segment independently. */
    private fun highlight(text: String, queryText: String): String? {
        val terms = queryTerms(queryText)
        if (terms.isEmpty()) return null
        val spans = mutableListOf<IntRange>()
        var tokenStart = -1
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val end = offset + Character.charCount(codePoint)
            if (isWordCodePoint(codePoint)) {
                if (tokenStart < 0) tokenStart = offset
            } else if (tokenStart >= 0) {
                if (foldForHighlight(text.substring(tokenStart, offset)).let { token -> terms.any(token::contains) }) {
                    spans += tokenStart until offset
                }
                tokenStart = -1
            }
            offset = end
        }
        if (tokenStart >= 0 && foldForHighlight(text.substring(tokenStart)).let { token -> terms.any(token::contains) }) {
            spans += tokenStart until text.length
        }
        if (spans.isEmpty()) return null

        return buildString {
            var cursor = 0
            for (span in spans) {
                val start = span.first
                val endExclusive = span.last + 1
                append(escapeHtml(text.substring(cursor, start)))
                append("<mark>")
                append(escapeHtml(text.substring(start, endExclusive)))
                append("</mark>")
                cursor = endExclusive
            }
            append(escapeHtml(text.substring(cursor)))
        }
    }

    private fun queryTerms(queryText: String): List<String> = buildList {
        var start = -1
        var offset = 0
        while (offset < queryText.length) {
            val codePoint = queryText.codePointAt(offset)
            val end = offset + Character.charCount(codePoint)
            if (isWordCodePoint(codePoint)) {
                if (start < 0) start = offset
            } else if (start >= 0) {
                add(foldForHighlight(queryText.substring(start, offset)))
                start = -1
            }
            offset = end
        }
        if (start >= 0) add(foldForHighlight(queryText.substring(start)))
    }.filter(String::isNotBlank).distinct()

    /** ICU folding approximates Lucene's ICUFoldingFilter, including accents such as å/ä/ö. */
    private fun foldForHighlight(value: String): String = UCharacter.foldCase(
        Normalizer2.getNFKDInstance().normalize(value),
        true,
    ).filterNot { character ->
        when (Character.getType(character)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }

    private fun isWordCodePoint(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) ||
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }

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

private fun IndexHit.searchKey(): HitKey = HitKey(collectionId, documentId, unitId, chunkOrdinal)

private val LOGGER = LoggerFactory.getLogger("infoscry.search")

/** The structured top-level component name for search logs. */
private const val COMPONENT_VALUE: String = "search"

/** The structured field carrying the unit id of a hit dropped because its locator would not decode. */
private const val UNIT_ID_FIELD: String = "unit_id"
