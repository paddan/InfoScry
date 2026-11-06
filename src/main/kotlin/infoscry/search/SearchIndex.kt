package infoscry.search

import infoscry.domain.Chunk
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.glossary.Glossary
import infoscry.storage.Database
import infoscry.storage.Instants
import java.sql.Connection
import java.sql.PreparedStatement
import kotlinx.serialization.json.Json
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.core.LowerCaseFilterFactory
import org.apache.lucene.analysis.custom.CustomAnalyzer
import org.apache.lucene.analysis.icu.ICUFoldingFilterFactory
import org.apache.lucene.analysis.standard.StandardTokenizerFactory
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.ParseException
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.BytesRef

/**
 * What the stored vectors are: the model and dimension they were embedded with.
 *
 * Semantic search may compare a query vector only against vectors the same model produced, so the identity
 * is recorded beside the rows and compared with the model this build embeds with.
 */
data class IndexIdentity(
    val schemaVersion: Int,
    val model: String,
    val modelRevision: String,
    val modelFingerprint: String,
    val dimension: Int,
) {
    init {
        require(model.isNotBlank()) { "an index identity names its model" }
        require(modelRevision.isNotBlank()) { "an index identity names its model revision" }
        require(modelFingerprint.isNotBlank()) { "an index identity records its model fingerprint" }
        require(dimension > 0) { "an index identity has a positive vector dimension, was $dimension" }
    }

    /** Why vectors recorded under [stored] cannot be searched with this identity; empty when they can. */
    fun reasonsAgainst(stored: IndexIdentity?): List<String> {
        if (stored == null) return emptyList()
        return buildList {
            if (stored.schemaVersion != schemaVersion) {
                add("schema version is ${stored.schemaVersion}, expected $schemaVersion")
            }
            if (stored.modelFingerprint != modelFingerprint) add("the index was built by a different model fingerprint")
            if (stored.dimension != dimension) {
                add("the index vectors are ${stored.dimension}-dimensional, expected $dimension")
            }
        }
    }
}

/** Whether the stored vectors can be searched with the model this build embeds with. */
sealed interface SchemaStatus {
    val schemaVersion: Int

    data class Ready(override val schemaVersion: Int) : SchemaStatus

    data class RebuildRequired(
        override val schemaVersion: Int,
        val reasons: List<String>,
    ) : SchemaStatus {
        init {
            require(reasons.isNotEmpty()) { "a rebuild-required status says why" }
        }
    }

    fun assertReady(): Ready = this as? Ready ?: error("the index needs a rebuild: $this")

    fun assertRebuildRequired(): RebuildRequired =
        this as? RebuildRequired ?: error("the index is ready, not rebuild-required")

    fun reasons(): List<String> = (this as? RebuildRequired)?.reasons ?: emptyList()
}

/** One searchable chunk of a document's published reading, as it is written. */
data class DocumentRow(
    val collectionId: CollectionId,
    val documentId: DocumentId,
    val unitId: ContentUnitId,
    val locator: SourceLocation,
    val locatorLabel: String,
    val chunk: Chunk,
    val vector: FloatArray,
    val revisionId: String? = null,
)

/** One search result row. */
data class IndexHit(
    val collectionId: String,
    val documentId: String,
    val unitId: String,
    val chunkOrdinal: Int,
    val text: String,
    val locator: String,
    val locatorLabel: String,
    val score: Float,
    val revisionId: String? = null,
)

/** A parsed keyword query with the glossary alternatives it gained, the groups they came from, and whether some were left out. */
data class KeywordExpansion(val query: Query, val groups: List<infoscry.glossary.GlossaryGroup>, val truncated: Boolean)

/**
 * The searchable rows of every document's published reading, in `search_chunks`, with BM25 keyword search
 * (pg_search) and nearest-neighbour vector search (pgvector) over them.
 *
 * Rows are written through [Database], so a caller that is inside a transaction writes them as part of it:
 * a publication replaces a document's rows and moves the document's authority in one commit, and no reader
 * can see one without the other. There is nothing to stage, hide or clean up afterwards.
 *
 * User queries are parsed by Lucene's classic parser, with the analyzer whose tokens are exactly the index's,
 * and the parsed query is translated into pg_search's query builders ([PgSearchQuery]); user text never
 * reaches pg_search's own parser.
 */
class SearchIndex(
    private val database: Database,
    /** The identity of the model this build embeds with. */
    val identity: IndexIdentity,
) {

    private val analyzer: Analyzer = newAnalyzer()

    val schemaVersion: Int get() = identity.schemaVersion

    /** Whether the stored vectors were embedded by the model this build uses. */
    val schemaStatus: SchemaStatus
        get() {
            val reasons = identity.reasonsAgainst(storedIdentity())
            return if (reasons.isEmpty()) SchemaStatus.Ready(schemaVersion) else SchemaStatus.RebuildRequired(schemaVersion, reasons)
        }

    /**
     * Prepares the database for this identity: records it when no vector has been stored yet, and creates
     * the HNSW index for its dimension. Idempotent.
     */
    fun open(): SearchIndex {
        database.transaction { connection ->
            val stored = storedIdentity(connection)
            if (stored == null && countAll(connection) == 0) writeIdentity(connection, identity)
            val dimension = identity.dimension
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS search_chunks_hnsw_$dimension ON search_chunks USING hnsw " +
                        "((embedding::halfvec($dimension)) halfvec_ip_ops) WHERE embedding_dimension = $dimension",
                )
            }
        }
        return this
    }

    /** Records that every stored vector is now this build's: the last step of a full rebuild. */
    fun recordIdentity() {
        database.transaction { connection -> writeIdentity(connection, identity) }
    }

    // ---- Writes ----

    /** Replaces every row of one document with [rows], in the caller's transaction when there is one. */
    fun replaceDocument(rows: List<DocumentRow>) {
        require(rows.isNotEmpty()) { "a document must have at least one chunk to be indexed" }
        val documentId = rows.first().documentId
        require(rows.all { it.documentId == documentId }) { "replacing a document indexes one document, not several" }
        database.transaction { connection ->
            deleteDocument(connection, documentId)
            connection.prepareStatement(INSERT_ROW).use { statement ->
                rows.forEach { row ->
                    statement.setString(1, row.collectionId.value)
                    statement.setString(2, row.documentId.value)
                    statement.setString(3, row.revisionId)
                    statement.setString(4, row.unitId.value)
                    statement.setInt(5, row.chunk.ordinal)
                    statement.setString(6, row.chunk.text)
                    statement.setString(7, LOCATOR_JSON.encodeToString(SourceLocation.serializer(), row.locator))
                    statement.setString(8, row.locatorLabel)
                    statement.setString(9, vectorLiteral(row.vector))
                    statement.setInt(10, row.vector.size)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /** Names the revision a document's rows belong to, for an import that records its revision after writing them. */
    fun tagRevision(documentId: DocumentId, revisionId: String) {
        database.transaction { connection ->
            connection.prepareStatement("UPDATE search_chunks SET revision_id = ? WHERE document_id = ?").use { statement ->
                statement.setString(1, revisionId)
                statement.setString(2, documentId.value)
                statement.executeUpdate()
            }
        }
    }

    /** Removes every row of one document. Idempotent. */
    fun deleteDocument(documentId: DocumentId) {
        database.transaction { connection -> deleteDocument(connection, documentId) }
    }

    /** Removes every row of one collection. Idempotent; a deleted collection's rows go with it anyway. */
    fun deleteCollection(collectionId: CollectionId) {
        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM search_chunks WHERE collection_id = ?").use { statement ->
                statement.setString(1, collectionId.value)
                statement.executeUpdate()
            }
        }
    }

    /** Removes the rows of every document whose rows belong to another revision than it publishes now. */
    fun deleteRowsOfUnpublishedRevisions(): Int = database.transaction { connection ->
        connection.prepareStatement(
            "DELETE FROM search_chunks s WHERE s.revision_id IS NOT NULL AND s.revision_id IS DISTINCT FROM " +
                "(SELECT a.revision_id FROM document_active_revisions a WHERE a.document_id = s.document_id)",
        ).use { it.executeUpdate() }
    }

    // ---- Reads ----

    /** How many rows one document has. */
    fun rowCount(documentId: DocumentId): Int =
        count("SELECT count(*) FROM search_chunks WHERE document_id = ?", documentId.value)

    /** How many rows one collection has. */
    fun rowCount(collectionId: CollectionId): Int =
        count("SELECT count(*) FROM search_chunks WHERE collection_id = ?", collectionId.value)

    /** How many chunks [documentId] has in [collectionId]. */
    fun chunkCount(collectionId: CollectionId, documentId: DocumentId): Int =
        count("SELECT count(*) FROM search_chunks WHERE collection_id = ? AND document_id = ?", collectionId.value, documentId.value)

    /** The documents that have rows. */
    fun storedDocumentIds(): Set<String> = database.read { connection ->
        connection.prepareStatement("SELECT DISTINCT document_id FROM search_chunks").use { statement ->
            statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    /**
     * [queryText] parsed and expanded by the collections' glossaries, for [searchKeyword] to run.
     *
     * Scoped to [collectionId], that collection's glossary applies. Across collections the query keeps its own
     * meaning everywhere and each glossary applies only inside its collection:
     * `(Q AND NOT in(expanded collections)) OR (collection = A AND E_A) OR …`. Either way the whole query stays
     * within [MAX_QUERY_TOKENS] clauses; what does not fit is left out and [KeywordExpansion.truncated] says so.
     */
    fun expandKeyword(
        collectionId: CollectionId?,
        queryText: String,
        glossaries: Map<CollectionId, Glossary>,
    ): KeywordExpansion {
        requireQueryWithinClauseCeiling(queryText)
        val parsed = parseSafely(queryText)
        val base = GlossaryQueryRewriter.clauseCount(parsed)
        fun rewriter(room: Int) = GlossaryQueryRewriter(
            maxAddedClauses = minOf(GlossaryQueryRewriter.DEFAULT_MAX_ADDED_CLAUSES, room.coerceAtLeast(0)),
        )
        if (collectionId != null) {
            val glossary = glossaries[collectionId] ?: return KeywordExpansion(parsed, emptyList(), false)
            val result = rewriter(MAX_QUERY_TOKENS - base).rewrite(parsed, glossary)
            return KeywordExpansion(result.query, result.groups, result.truncated)
        }
        val expanded = mutableListOf<Pair<CollectionId, Query>>()
        val groups = mutableListOf<infoscry.glossary.GlossaryGroup>()
        var truncated = false
        // Each expanded collection repeats the query once inside its own branch, plus its collection term.
        var used = base + 1
        for ((id, glossary) in glossaries.entries.sortedBy { it.key.value }) {
            val result = rewriter(MAX_QUERY_TOKENS - used - base - 2).rewrite(parsed, glossary)
            truncated = truncated || result.truncated
            if (result.groups.isEmpty()) continue
            val cost = GlossaryQueryRewriter.clauseCount(result.query) + 2
            if (used + cost > MAX_QUERY_TOKENS) {
                truncated = true
                continue
            }
            used += cost
            expanded += id to result.query
            groups += result.groups
        }
        if (expanded.isEmpty()) return KeywordExpansion(parsed, emptyList(), truncated)
        val query = BooleanQuery.Builder().apply {
            add(
                BooleanQuery.Builder().apply {
                    add(parsed, BooleanClause.Occur.MUST)
                    add(
                        TermInSetQuery(PgSearchQuery.COLLECTION_FIELD, expanded.map { BytesRef(it.first.value) }),
                        BooleanClause.Occur.MUST_NOT,
                    )
                }.build(),
                BooleanClause.Occur.SHOULD,
            )
            expanded.forEach { (id, query) ->
                add(
                    BooleanQuery.Builder().apply {
                        add(query, BooleanClause.Occur.MUST)
                        add(TermQuery(Term(PgSearchQuery.COLLECTION_FIELD, id.value)), BooleanClause.Occur.FILTER)
                    }.build(),
                    BooleanClause.Occur.SHOULD,
                )
            }
        }.build()
        return KeywordExpansion(query, groups, truncated)
    }

    /**
     * Keyword search over the chunk text.
     *
     * A query the classic parser cannot parse is escaped into a safe terms query rather than surfacing as an
     * error, and a query that analyzes into more than [MAX_QUERY_TOKENS] tokens is refused. Scores are BM25
     * scores; equal scores are ordered by row, which is the order rows were written.
     */
    fun searchKeyword(
        collectionId: CollectionId?,
        queryText: String,
        documentIds: Set<String>? = null,
        limit: Int,
        expansion: KeywordExpansion? = null,
    ): List<IndexHit> {
        val parsed = expansion?.query ?: run {
            requireQueryWithinClauseCeiling(queryText)
            parseSafely(queryText)
        }
        val translated = PgSearchQuery.translate(parsed)
        val (scope, scopeParameters) = scopeClause(collectionId, documentIds)
        val sql = "SELECT $HIT_COLUMNS, pdb.score(row_id) AS score FROM search_chunks " +
            "WHERE row_id @@@ ${translated.sql}$scope ORDER BY score DESC, row_id LIMIT ?"
        return database.read { connection ->
            connection.prepareStatement(sql).use { statement ->
                val next = bind(statement, 1, translated.parameters + scopeParameters)
                statement.setInt(next, limit)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toHit(rows.getFloat("score"))) } }
            }
        }
    }

    /**
     * Whether [queryText] has a lexical foothold in the index: one of its analyzed tokens is a prefix of an
     * indexed term inside the given scope.
     *
     * A prefix rather than an exact term, so a word still being typed ("waterg") or an inflected form
     * ("resign" for "resignation") still counts. Tokens shorter than [MIN_ANCHOR_PREFIX] are skipped because
     * two letters match almost any vocabulary and would ground almost anything.
     */
    fun hasLexicalAnchor(
        queryText: String,
        collectionId: CollectionId?,
        documentIds: Set<String>? = null,
        extraTokens: List<String> = emptyList(),
    ): Boolean {
        val tokens = (analyzeTokens(queryText) + extraTokens).distinct().filter { it.length >= MIN_ANCHOR_PREFIX }
        if (tokens.isEmpty()) return false
        val (scope, scopeParameters) = scopeClause(collectionId, documentIds)
        return database.read { connection ->
            tokens.any { token ->
                connection.prepareStatement(
                    "SELECT 1 FROM search_chunks WHERE row_id @@@ paradedb.regex('text', ?)$scope LIMIT 1",
                ).use { statement ->
                    bind(statement, 1, listOf(PgSearchQuery.prefixPattern(token)) + scopeParameters)
                    statement.executeQuery().use { it.next() }
                }
            }
        }
    }

    /**
     * The nearest rows to [vector] by dot product, narrowed by [collectionId] and [documentIds] when given.
     *
     * A scope of at most [EXACT_SEARCH_LIMIT] rows, or at most four times [limit], is searched exactly; a
     * larger one goes through the HNSW
     * index with iterative scans, which keep exploring until [limit] rows inside the scope are found. The
     * score is Lucene's dot-product score, `(1 + dot) / 2`.
     */
    fun searchVector(
        collectionId: CollectionId?,
        vector: FloatArray,
        documentIds: Set<String>? = null,
        limit: Int,
    ): List<IndexHit> {
        require(vector.size == identity.dimension) {
            "a query vector must match the index's dimension (${identity.dimension}), was ${vector.size}"
        }
        val dimension = identity.dimension
        val (scope, scopeParameters) = scopeClause(collectionId, documentIds)
        // A scope that is small, or not much larger than what was asked for, costs no more to scan exactly.
        val exact = vectorCandidateCount(collectionId, documentIds) <= maxOf(EXACT_SEARCH_LIMIT, limit * 4)
        // The approximate path orders by the indexed expression so the HNSW index serves it; the exact path
        // orders by a different expression on purpose, so the planner scans the scope and orders it exactly.
        val distance = if (exact) "(embedding <#> ?::halfvec)" else "(embedding::halfvec($dimension) <#> ?::halfvec($dimension))"
        val sql = "SELECT $HIT_COLUMNS, $distance AS distance FROM search_chunks " +
            "WHERE embedding_dimension = $dimension$scope ORDER BY distance, row_id LIMIT ?"
        val literal = vectorLiteral(vector)
        return database.read { connection ->
            if (!exact) {
                connection.createStatement().use { statement ->
                    statement.execute("SET hnsw.iterative_scan = relaxed_order")
                    statement.execute("SET hnsw.ef_search = ${(limit * 2).coerceIn(DEFAULT_EF_SEARCH, MAX_EF_SEARCH)}")
                }
            }
            try {
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, literal)
                    val next = bind(statement, 2, scopeParameters)
                    statement.setInt(next, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(rows.toHit(((1.0 - rows.getDouble("distance")) / 2.0).toFloat()))
                        }
                    }
                }.sortedWith(compareByDescending<IndexHit> { it.score })
            } finally {
                if (!exact) connection.createStatement().use { it.execute("RESET hnsw.iterative_scan; RESET hnsw.ef_search") }
            }
        }
    }

    /** Number of rows in the scope [searchVector] searches. */
    fun vectorCandidateCount(collectionId: CollectionId?, documentIds: Set<String>? = null): Int {
        val (scope, scopeParameters) = scopeClause(collectionId, documentIds)
        return database.read { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM search_chunks WHERE embedding_dimension = ${identity.dimension}$scope",
            ).use { statement ->
                bind(statement, 1, scopeParameters)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
    }

    // ---- Plumbing ----

    private fun storedIdentity(): IndexIdentity? = database.read { storedIdentity(it) }

    private fun storedIdentity(connection: Connection): IndexIdentity? =
        connection.prepareStatement(
            "SELECT schema_version, model, model_revision, model_fingerprint, dimension FROM search_index_state",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    IndexIdentity(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getInt(5))
                }
            }
        }

    private fun writeIdentity(connection: Connection, identity: IndexIdentity) {
        connection.prepareStatement(
            "INSERT INTO search_index_state (singleton, schema_version, model, model_revision, model_fingerprint, " +
                "dimension, updated_at) VALUES (1, ?, ?, ?, ?, ?, ?) ON CONFLICT (singleton) DO UPDATE SET " +
                "schema_version = excluded.schema_version, model = excluded.model, model_revision = " +
                "excluded.model_revision, model_fingerprint = excluded.model_fingerprint, dimension = " +
                "excluded.dimension, updated_at = excluded.updated_at",
        ).use { statement ->
            statement.setInt(1, identity.schemaVersion)
            statement.setString(2, identity.model)
            statement.setString(3, identity.modelRevision)
            statement.setString(4, identity.modelFingerprint)
            statement.setInt(5, identity.dimension)
            statement.setString(6, Instants.now())
            statement.executeUpdate()
        }
    }

    private fun countAll(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM search_chunks").use { rows -> rows.next(); rows.getInt(1) }
        }

    private fun deleteDocument(connection: Connection, documentId: DocumentId) {
        connection.prepareStatement("DELETE FROM search_chunks WHERE document_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeUpdate()
        }
    }

    private fun count(sql: String, vararg parameters: String): Int = database.read { connection ->
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    /** The `AND …` filter of a collection and document scope, and its parameters. */
    private fun scopeClause(collectionId: CollectionId?, documentIds: Set<String>?): Pair<String, List<Any>> {
        val clause = StringBuilder()
        val parameters = mutableListOf<Any>()
        if (collectionId != null) {
            clause.append(" AND collection_id = ?")
            parameters += collectionId.value
        }
        if (!documentIds.isNullOrEmpty()) {
            clause.append(" AND document_id = ANY(?)")
            parameters.add(documentIds.toTypedArray())
        }
        return clause.toString() to parameters
    }

    private fun bind(statement: PreparedStatement, from: Int, parameters: List<Any>): Int {
        var index = from
        for (parameter in parameters) {
            when (parameter) {
                is String -> statement.setString(index, parameter)
                is Int -> statement.setInt(index, parameter)
                is Float -> statement.setFloat(index, parameter)
                is Array<*> -> statement.setArray(index, statement.connection.createArrayOf("text", parameter))
                else -> error("unsupported query parameter ${parameter::class.simpleName}")
            }
            index++
        }
        return index
    }

    private fun java.sql.ResultSet.toHit(score: Float): IndexHit = IndexHit(
        collectionId = getString("collection_id"),
        documentId = getString("document_id"),
        unitId = getString("content_unit_id"),
        chunkOrdinal = getInt("chunk_ordinal"),
        text = getString("text"),
        locator = getString("locator"),
        locatorLabel = getString("locator_label"),
        score = score,
        revisionId = getString("revision_id"),
    )

    /** The analyzed tokens of [queryText], normalized exactly as the index stores them. */
    private fun analyzeTokens(queryText: String): List<String> =
        analyzer.tokenStream(PgSearchQuery.TEXT_FIELD, queryText).use { stream ->
            val term = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            buildList { while (stream.incrementToken()) add(term.toString()) }.also { stream.end() }
        }

    /**
     * Refuses a query that analyzes into more tokens than [MAX_QUERY_TOKENS], so an over-long paste is an
     * actionable refusal rather than an enormous query.
     */
    private fun requireQueryWithinClauseCeiling(queryText: String) {
        if (analyzeTokens(queryText).size > MAX_QUERY_TOKENS) {
            throw SearchUnavailableException(
                QUERY_TOO_LONG_CODE,
                "the query has more than $MAX_QUERY_TOKENS words; shorten it and search again",
            )
        }
    }

    /**
     * [queryText] with the two reserved characters that ordinary prose uses escaped: every unescaped `:`,
     * because the only searchable field is the text field and `1962:700` or `SFS 2010:800` would otherwise
     * name a field `1962`; and every unescaped `?` that ends a word, because a question such as
     * `… av unga?` would otherwise search `unga` plus one character. A `?` inside a word (`u?ga`) stays a
     * single-character wildcard; phrases, `*`, `+/-` and `AND/OR/NOT` are untouched.
     */
    private fun asPlainText(queryText: String): String =
        PLAIN_TRAILING_QUESTION_MARKS.replace(queryText) { "\\?".repeat(it.value.length) }
            .let { escaped -> PLAIN_COLON.replace(escaped) { "\\:" } }

    /** Parses a user query, falling back to the parser's own escaping when the input is not valid syntax. */
    private fun parseSafely(queryText: String): Query = try {
        QueryParser(PgSearchQuery.TEXT_FIELD, analyzer).parse(asPlainText(queryText))
    } catch (failure: ParseException) {
        QueryParser(PgSearchQuery.TEXT_FIELD, analyzer).parse(QueryParser.escape(queryText))
    }

    companion object {
        /** The layout version of the searchable rows; a change means every row is rebuilt. */
        const val SCHEMA_VERSION: Int = 1

        /** How many analyzed tokens a user query may carry. */
        const val MAX_QUERY_TOKENS: Int = 1_000

        /** How short a query token may be and still count as a lexical foothold. */
        const val MIN_ANCHOR_PREFIX: Int = 3

        /** The code a refusal carries when a query analyzes into more tokens than the ceiling can hold. */
        const val QUERY_TOO_LONG_CODE: String = "QUERY_TOO_LONG"

        /** A scope of at most this many rows is searched exactly rather than through the HNSW index. */
        const val EXACT_SEARCH_LIMIT: Int = 1_000

        private const val DEFAULT_EF_SEARCH: Int = 40

        /** The largest search list pgvector accepts. */
        private const val MAX_EF_SEARCH: Int = 1_000

        private val PLAIN_COLON = Regex("""(?<!\\):""")
        private val PLAIN_TRAILING_QUESTION_MARKS = Regex("""(?<!\\)\?+(?![\p{L}\p{N}])""")

        private val LOCATOR_JSON = Json { ignoreUnknownKeys = true }

        private const val HIT_COLUMNS =
            "collection_id, document_id, content_unit_id, chunk_ordinal, text, locator, locator_label, revision_id"

        private const val INSERT_ROW =
            "INSERT INTO search_chunks (collection_id, document_id, revision_id, content_unit_id, chunk_ordinal, " +
                "text, locator, locator_label, embedding, embedding_dimension) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::halfvec, ?)"

        /**
         * The analyzer whose tokens are the index's: standard tokenizer, ICU folding, lower case. The BM25
         * index's `pdb.unicode_words('ascii_folding=true')` tokenizer yields the same tokens, so a term this
         * analyzer produces is a term the index holds.
         */
        internal fun newAnalyzer(): Analyzer =
            CustomAnalyzer.builder()
                .withTokenizer(StandardTokenizerFactory::class.java)
                .addTokenFilter(ICUFoldingFilterFactory::class.java)
                .addTokenFilter(LowerCaseFilterFactory::class.java)
                .build()

        /** The text form pgvector reads a vector in. */
        internal fun vectorLiteral(vector: FloatArray): String =
            vector.joinToString(prefix = "[", postfix = "]", separator = ",")
    }
}
