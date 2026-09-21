package infoscry.search

import infoscry.domain.Chunk
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.core.LowerCaseFilterFactory
import org.apache.lucene.analysis.custom.CustomAnalyzer
import org.apache.lucene.analysis.icu.ICUFoldingFilterFactory
import org.apache.lucene.analysis.standard.StandardTokenizerFactory
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.queryparser.classic.ParseException
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.TopDocs
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef

/**
 * Which embeddings this index's vectors were built with, recorded in the commit user data so a model
 * change is detected instead of compared against.
 *
 * [modelFingerprint] is the embedding model's own fingerprint (manifest model, revision, export,
 * pooling, prefix version and file hashes). It is deliberate that the schema version, model, revision,
 * fingerprint and dimension are all recorded: an index built by another model must report "rebuild
 * required" rather than answer semantic queries with vectors that were built by different mathematics.
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

    /** The identity, as commit user data. Keys are namespaced in [LuceneSchema]. */
    fun toUserData(): Map<String, String> = mapOf(
        LuceneSchema.KEY_SCHEMA_VERSION to schemaVersion.toString(),
        LuceneSchema.KEY_MODEL to model,
        LuceneSchema.KEY_MODEL_REVISION to modelRevision,
        LuceneSchema.KEY_MODEL_FINGERPRINT to modelFingerprint,
        LuceneSchema.KEY_DIMENSION to dimension.toString(),
    )

    companion object {
        /**
         * Reads an identity from commit user data, reporting every way it is missing or different from
         * [expected] rather than failing on the first one.
         *
         * @return the reasons the stored index cannot answer this index's vectors, empty when it can.
         */
        fun reasonsFor(entries: Iterable<Map.Entry<String, String>>, expected: IndexIdentity): List<String> {
            val stored = entries.associate { it.key to it.value }
            val reasons = buildList {
                stored[LuceneSchema.KEY_SCHEMA_VERSION]?.let { value ->
                    if (value != expected.schemaVersion.toString()) {
                        add("schema version is $value, expected ${expected.schemaVersion}")
                    }
                } ?: add("the index records no schema version")
                stored[LuceneSchema.KEY_MODEL_FINGERPRINT]?.let { value ->
                    if (value != expected.modelFingerprint) {
                        add("the index was built by a different model fingerprint")
                    }
                } ?: add("the index records no model fingerprint")
                stored[LuceneSchema.KEY_DIMENSION]?.let { value ->
                    if (value != expected.dimension.toString()) {
                        add("the index vectors are ${value}-dimensional, expected ${expected.dimension}")
                    }
                } ?: add("the index records no vector dimension")
            }
            return reasons
        }
    }
}

/**
 * Whether this index can answer the queries a caller wants to ask.
 *
 * A stable-text (keyword) index can still answer queries across a model change — text fields are
 * independent of the embedding model — so the status is a property of the *vector half* of the index:
 * when the model fingerprint or dimension recorded at commit time stops matching what the application
 * now embeds with, semantic retrieval must be refused with a rebuild instruction instead of returning
 * garbage distances.
 */
sealed interface SchemaStatus {
    val schemaVersion: Int

    /** The index's vectors still match the model it was opened with. */
    data class Ready(override val schemaVersion: Int) : SchemaStatus

    /** The index's recorded vectors were built by a different model; semantic search must be refused. */
    data class RebuildRequired(
        override val schemaVersion: Int,
        val reasons: List<String>,
    ) : SchemaStatus {
        init {
            require(reasons.isNotEmpty()) { "a rebuild-required status says why" }
        }
    }

    /** The status, narrowed to [Ready]. The caller is the search service that gates semantic queries. */
    fun assertReady(): Ready = this as? Ready ?: error("the index needs a rebuild: $this")

    /** The status, narrowed to [RebuildRequired], for callers that need the reasons. */
    fun assertRebuildRequired(): RebuildRequired =
        this as? RebuildRequired ?: error("the index is ready, not rebuild-required")

    /** The reasons a rebuild is required, empty when the index is ready. */
    fun reasons(): List<String> = (this as? RebuildRequired)?.reasons ?: emptyList()
}

/**
 * One Lucene document's worth of data for one chunk, as the indexing stage of an import produces it.
 *
 * [chunk] is the chunk itself (its text is what gets analyzed), [locator] and [locatorLabel] are the
 * citation data of the unit the chunk belongs to, and [vector] is the embedding the chunk was passed
 * through. [unitId] names the content unit the chunk belongs to, so a hit can open its exact source.
 */
data class DocumentRow(
    val collectionId: CollectionId,
    val documentId: DocumentId,
    val unitId: ContentUnitId,
    val locator: SourceLocation,
    val locatorLabel: String,
    val chunk: Chunk,
    val vector: FloatArray,
)

/** A search hit: the stored data a result pane needs, already filtered by the query. */
data class IndexHit(
    val collectionId: String,
    val documentId: String,
    val unitId: String,
    val chunkOrdinal: Int,
    val text: String,
    val locator: String,
    val locatorLabel: String,
    val score: Float,
)

/**
 * The search index over one data directory.
 *
 * One Lucene document per searchable chunk, so a hit can be exact. The index lives in generations: a
 * new generation is built to completion and then published by atomically replacing the `current`
 * marker, and a restart resolves the marker rather than guessing. This task creates the first
 * generation and its marker; Task 18 owns the rebuild protocol that publishes a successor generation.
 *
 * Every mutation here is called from code that already holds a mutation permit — the import stage
 * rechecks its collection under the permit before publication, and the deletion machine owns exclusive
 * maintenance while `deleteCollection` runs. Within this class, a private mutex serialises the single
 * Lucene writer between concurrent jobs, because the writer is not itself thread-safe.
 */
class LuceneIndex private constructor(
    private val paths: Path,
    generationInfo: GenerationInfo,
) : AutoCloseable {

    private val handle: GenerationHandle = generationInfo.open()

    /**
     * The generation name this index resolves to, e.g. `lucene-<uuid>`. Task 18 reads the same marker
     * to know which generation is the durable current one.
     */
    val name: String get() = handle.name

    /** The schema version this index was built with, for callers that must know before searching. */
    val schemaVersion: Int get() = handle.identity.schemaVersion

    /** Whether the recorded vectors still match the identity this index was opened with. */
    val schemaStatus: SchemaStatus
        get() = computeStatus()

    /** Serialises all writer operations; the Lucene writer is single-threaded by design. */
    private val writeMutex = Mutex()

    /**
     * Replaces every chunk of [documentId] in one writer operation.
     *
     * The replacement is the whole document: chunks that were indexed from a previous reading of the
     * same bytes are removed in the same operation that writes the new ones, and nothing is durable
     * until the commit that follows, so a crash anywhere before it leaves the previous reading intact.
     * That, plus the caller's own permit discipline, is what makes an interrupted import replayable
     * without ever leaving two copies of the same document in the index.
     */
    suspend fun replaceDocument(rows: List<DocumentRow>): Unit = writeMutex.withLock {
        require(rows.isNotEmpty()) { "a document must have at least one chunk to be indexed" }
        require(rows.all { it.documentId == rows.first().documentId }) {
            "replacing a document indexes one document, not several"
        }
        val term = Term(LuceneSchema.FIELD_DOCUMENT_ID, rows.first().documentId.value)
        val documents = rows.map(::toLuceneDocument)
        handle.writer.updateDocuments(term, documents)
        publish()
    }

    /** Removes every chunk of one document. Idempotent: an already-removed document is a no-op. */
    suspend fun deleteDocument(documentId: DocumentId): Unit = writeMutex.withLock {
        handle.writer.deleteDocuments(Term(LuceneSchema.FIELD_DOCUMENT_ID, documentId.value))
        publish()
    }

    /** Removes every chunk of one collection. Idempotent, and the deletion machine's index phase. */
    suspend fun deleteCollection(collectionId: CollectionId): Unit = writeMutex.withLock {
        handle.writer.deleteDocuments(Term(LuceneSchema.FIELD_COLLECTION_ID, collectionId.value))
        publish()
    }

    /**
     * Commits the writer. Every mutating method already commits; this exists for the maintenance
     * owner that must make sure a half-built state is durable before it moves on.
     */
    suspend fun commit(): Unit = writeMutex.withLock {
        handle.writer.commit()
        refresh()
    }

    /**
     * How many rows one document has in this generation, for the rebuild that decides which copied
     * documents already agree with the database.
     */
    fun rowCount(documentId: DocumentId): Int = readSearcher { searcher ->
        searcher.count(TermQuery(Term(LuceneSchema.FIELD_DOCUMENT_ID, documentId.value)))
    }

    /** How many rows one collection has, for a rebuild's validation that every row is accounted for. */
    fun rowCount(collectionId: CollectionId): Int = readSearcher { searcher ->
        searcher.count(TermQuery(Term(LuceneSchema.FIELD_COLLECTION_ID, collectionId.value)))
    }

    /**
     * The document identities this generation still holds rows for, read from the index itself.
     *
     * A rebuild cannot ask the database what the index holds — the whole point of a stale-row check is
     * that the two have diverged — so the identities come from the stored field through this
     * generation's own searcher. The terms dictionary yields every distinct document identity across
     * all of the generation's segments, and a term whose every row is deleted does not count, which
     * is what keeps a document the database no longer has from being mistaken for a live one.
     */
    fun storedDocumentIds(): Set<String> = readSearcher { searcher ->
        val identities = buildSet {
            for (leaf in searcher.indexReader.leaves()) {
                val terms = leaf.reader().terms(LuceneSchema.FIELD_DOCUMENT_ID) ?: continue
                val iterator = terms.iterator()
                while (iterator.next() != null) {
                    add(iterator.term().utf8ToString())
                }
            }
        }
        identities.filterTo(HashSet(identities.size)) { id ->
            searcher.count(TermQuery(Term(LuceneSchema.FIELD_DOCUMENT_ID, id))) > 0
        }
    }

    /**
     * Waits, bounded, for every search that is still reading this generation to finish.
     *
     * A rebuild publishes a successor and then retires the generation it replaced: readers that
     * acquired a searcher before the swap must be allowed to finish on it, which is the lease the
     * rebuild waits to drain. `false` means the wait ran out with readers still inside, and the caller
     * must leave the generation alone rather than closing it under them.
     */
    suspend fun drainLeases(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (openLeases.get() > 0) {
            if (System.nanoTime() >= deadline) return false
            delay(LEASE_POLL_MILLIS)
        }
        return true
    }

    /**
     * Keyword search over the analyzed text field.
     *
     * [collectionId] and [documentIds] narrow the match exactly in the query itself. A query the classic
     * parser cannot parse is escaped into a safe terms query rather than surfacing as an error — invalid
     * syntax is user input, not a corrupt index. A query that analyzes into more tokens than
     * [MAX_QUERY_TOKENS] is refused before it is parsed, because Lucene's clause ceiling is enforced when a
     * query is rewritten during a search and would otherwise surface as an exception rather than a refusal.
     */
    fun searchKeyword(
        collectionId: CollectionId?,
        queryText: String,
        documentIds: Set<String>? = null,
        limit: Int,
    ): List<IndexHit> {
        requireQueryWithinClauseCeiling(queryText)
        return readSearcher { searcher ->
            val parsed = parseSafely(queryText)
            val query = filteredBy(parsed, collectionId, documentIds)
            toHits(searcher, searcher.search(query, limit))
        }
    }

    /**
     * Vector search over the index's embedding field.
     *
     * Returns the nearest vectors to [vector], narrowed by [collectionId] and [documentIds] when given.
     * The caller is the search service, which decides whether the index's [schemaStatus] still matches the
     * model it embeds with before calling this.
     */
    fun searchVector(
        collectionId: CollectionId?,
        vector: FloatArray,
        documentIds: Set<String>? = null,
        limit: Int,
    ): List<IndexHit> = readSearcher { searcher ->
        require(vector.size == handle.identity.dimension) {
            "a query vector must match the index's dimension (${handle.identity.dimension}), was ${vector.size}"
        }
        val filter = scopeFilter(collectionId, documentIds)
        val query = if (filter != null) {
            KnnFloatVectorQuery(LuceneSchema.FIELD_VECTOR, vector, limit, filter)
        } else {
            KnnFloatVectorQuery(LuceneSchema.FIELD_VECTOR, vector, limit)
        }
        toHits(searcher, searcher.search(query, limit))
    }

    /** How many chunks [documentId] currently has in the index, for the idempotency guarantees. */
    fun chunkCount(collectionId: CollectionId, documentId: DocumentId): Int = readSearcher { searcher ->
        val query = BooleanQuery.Builder().apply {
            add(TermQuery(Term(LuceneSchema.FIELD_COLLECTION_ID, collectionId.value)), BooleanClause.Occur.FILTER)
            add(TermQuery(Term(LuceneSchema.FIELD_DOCUMENT_ID, documentId.value)), BooleanClause.Occur.FILTER)
        }.build()
        searcher.count(query)
    }

    override fun close() {
        handle.close()
    }

    // ---- Plumbing that keeps the write and read halves of the index honest ----

    private fun publish() {
        handle.writer.setLiveCommitData(handle.identity.toUserData().entries.toList())
        handle.writer.commit()
        refresh()
    }

    private fun refresh() {
        handle.manager.maybeRefresh()
    }

    private fun computeStatus(): SchemaStatus {
        // The latest durable commit's user data is the identity the vectors were built with. A fresh
        // generation commits its identity before its marker is published, so there is always at least
        // one commit to read.
        val latest = DirectoryReader.listCommits(handle.directory)
            .maxByOrNull { it.generation }
            ?.getUserData()
            ?: emptyMap()
        val reasons = IndexIdentity.reasonsFor(latest.entries, handle.identity)
        return if (reasons.isEmpty()) {
            SchemaStatus.Ready(schemaVersion)
        } else {
            SchemaStatus.RebuildRequired(schemaVersion, reasons)
        }
    }

    private fun filteredBy(
        query: Query,
        collectionId: CollectionId?,
        documentIds: Set<String>?,
    ): Query {
        val scope = scopeFilter(collectionId, documentIds)
        if (scope == null) return query
        return BooleanQuery.Builder().apply {
            add(query, BooleanClause.Occur.MUST)
            add(scope, BooleanClause.Occur.FILTER)
        }.build()
    }

    /**
     * One filter query naming the collection and document ids a hit must belong to.
     *
     * The document ids go in as a single [TermInSetQuery] rather than one `TermQuery` per id: a set query is
     * a `MultiTermQuery` and is not counted against Lucene's boolean clause ceiling, so a criterion that
     * matches every document in a large archive costs one clause instead of one per document.
     */
    private fun scopeFilter(collectionId: CollectionId?, documentIds: Set<String>?): Query? {
        if (collectionId == null && documentIds.isNullOrEmpty()) return null
        return BooleanQuery.Builder().apply {
            if (collectionId != null) {
                add(TermQuery(Term(LuceneSchema.FIELD_COLLECTION_ID, collectionId.value)), BooleanClause.Occur.FILTER)
            }
            if (!documentIds.isNullOrEmpty()) {
                add(
                    TermInSetQuery(LuceneSchema.FIELD_DOCUMENT_ID, documentIds.map { BytesRef(it) }),
                    BooleanClause.Occur.FILTER,
                )
            }
        }.build()
    }

    /**
     * Refuses a query that analyzes into more tokens than the clause ceiling can hold.
     *
     * The classic parser emits roughly one boolean clause per analyzed token, and Lucene enforces its ceiling
     * when the query is rewritten during the search — so counting the tokens first turns "the search box threw
     * an exception" into an actionable refusal.
     */
    private fun requireQueryWithinClauseCeiling(queryText: String) {
        if (countAnalyzedTokens(queryText) > MAX_QUERY_TOKENS) {
            throw SearchUnavailableException(
                QUERY_TOO_LONG_CODE,
                "the query has more than $MAX_QUERY_TOKENS words; shorten it and search again",
            )
        }
    }

    /** The tokens [queryText] analyzes into, counted with this index's own analyzer, stopping at the bound. */
    private fun countAnalyzedTokens(queryText: String): Int {
        val stream = handle.analyzer.tokenStream(LuceneSchema.FIELD_TEXT, queryText)
        return stream.use {
            it.reset()
            var count = 0
            while (count <= MAX_QUERY_TOKENS && it.incrementToken()) count++
            count
        }
    }

    /**
     * Parses a user query, falling back to the parser's own escaping when the input is not valid syntax.
     *
     * `QueryParser.escape` quotes every reserved character, so the escaped text is always parseable and the
     * result matches the user's words literally rather than failing on their phrasing.
     */
    private fun parseSafely(queryText: String): Query = try {
        QueryParser(LuceneSchema.FIELD_TEXT, handle.analyzer).parse(queryText)
    } catch (failure: ParseException) {
        QueryParser(LuceneSchema.FIELD_TEXT, handle.analyzer).parse(QueryParser.escape(queryText))
    }

    private fun toHits(searcher: IndexSearcher, top: TopDocs): List<IndexHit> =
        top.scoreDocs.map { hit -> toHit(searcher, hit) }

    private fun toHit(searcher: IndexSearcher, hit: ScoreDoc): IndexHit {
        val stored = searcher.storedFields().document(hit.doc)
        return IndexHit(
            collectionId = stored.get(LuceneSchema.FIELD_COLLECTION_ID).orEmpty(),
            documentId = stored.get(LuceneSchema.FIELD_DOCUMENT_ID).orEmpty(),
            unitId = stored.get(LuceneSchema.FIELD_CONTENT_UNIT_ID).orEmpty(),
            chunkOrdinal = stored.getField(LuceneSchema.FIELD_ORDINAL)?.numericValue()?.toInt() ?: -1,
            text = stored.get(LuceneSchema.FIELD_SNIPPET).orEmpty(),
            locator = stored.get(LuceneSchema.FIELD_LOCATOR).orEmpty(),
            locatorLabel = stored.get(LuceneSchema.FIELD_LOCATOR_LABEL).orEmpty(),
            score = hit.score,
        )
    }

    /** How many searches are reading this generation right now, so retirement can wait for them. */
    private val openLeases = AtomicInteger(0)

    private fun <T> readSearcher(block: (IndexSearcher) -> T): T {
        val searcher = handle.manager.acquire()
        openLeases.incrementAndGet()
        try {
            return block(searcher)
        } finally {
            openLeases.decrementAndGet()
            handle.manager.release(searcher)
        }
    }

    private fun toLuceneDocument(row: DocumentRow): Document = Document().apply {
        add(StringField(LuceneSchema.FIELD_DOCUMENT_ID, row.documentId.value, Field.Store.YES))
        add(StringField(LuceneSchema.FIELD_COLLECTION_ID, row.collectionId.value, Field.Store.YES))
        add(StringField(LuceneSchema.FIELD_CONTENT_UNIT_ID, row.unitId.value, Field.Store.YES))
        add(IntPoint(LuceneSchema.FIELD_ORDINAL, row.chunk.ordinal))
        add(StoredField(LuceneSchema.FIELD_ORDINAL, row.chunk.ordinal))
        // Stored so a snippet can be read back without broaching the database, and analyzed with the
        // ICU analyzer so multilingual text tokenizes like a reader would split it.
        add(TextField(LuceneSchema.FIELD_TEXT, row.chunk.text, Field.Store.YES))
        add(StoredField(LuceneSchema.FIELD_SNIPPET, row.chunk.text))
        add(StoredField(LuceneSchema.FIELD_LOCATOR, LOCATOR_JSON.encodeToString(SourceLocation.serializer(), row.locator)))
        add(StoredField(LuceneSchema.FIELD_LOCATOR_LABEL, row.locatorLabel))
        add(KnnFloatVectorField(LuceneSchema.FIELD_VECTOR, row.vector, VectorSimilarityFunction.DOT_PRODUCT))
    }

    companion object {

        /**
         * How many analyzed tokens a user query may carry.
         *
         * Lucene keeps its own boolean clause ceiling at its default (1 024), enforced when a query is
         * rewritten during a search, and the classic parser emits roughly one clause per analyzed token — so a
         * longer query would surface as `IndexSearcher.TooManyClauses` rather than a refusal. The bound sits
         * below the ceiling deliberately: a token does not have to map to exactly one clause (a phrase, a
         * boost, a field group or a wildcard need not), and the scope and wrapper queries this application
         * adds around the parsed query have to fit in the same ceiling.
         *
         * Raise the ceiling here and the query path pays for it: `IndexSearcher.setMaxClauseCount` is
         * process-wide and governs user parsing exactly as much as machine-built filters, which is why the
         * broad-scope case is solved by a set query instead of a higher ceiling.
         */
        const val MAX_QUERY_TOKENS: Int = 1_000

        /** The code a refusal carries when a query analyzes into more tokens than the ceiling can hold. */
        const val QUERY_TOO_LONG_CODE: String = "QUERY_TOO_LONG"

        private const val MARKER_FILE: String = "current"
        private const val GENERATION_PREFIX: String = "lucene-"

        /** How often retirement checks whether the readers it is waiting for have finished. */
        private const val LEASE_POLL_MILLIS: Long = 20L

        private val LOCATOR_JSON = Json { ignoreUnknownKeys = true }

        /**
         * Opens (or, on a fresh data directory, creates) the current index generation.
         *
         * An existing generation is resolved through the `current` marker and never guessed: creating a
         * directory from nowhere when a marker is missing would hide the very corruption recovery exists
         * to report. A fresh directory gets a complete empty generation — committed with the identity
         * it was built with — and only then an atomic marker, so the marker never names a generation
         * that is not fully readable.
         */
        fun open(indexDir: Path, identity: IndexIdentity): LuceneIndex {
            Files.createDirectories(indexDir)
            val generation = resolve(indexDir, identity)
            return LuceneIndex(indexDir, generation)
        }

        /**
         * Opens a *named* generation without consulting the marker.
         *
         * The rebuild builds a successor into `lucene-next-<uuid>` while the current generation keeps
         * serving, so it opens that directory directly: the marker still names the generation it will
         * name until the rebuild publishes, and a directory that exists without being named is exactly
         * what this is for. The caller commits and closes the writer it gets before any marker is
         * touched.
         */
        internal fun openGeneration(indexDir: Path, name: String, identity: IndexIdentity): LuceneIndex {
            check(name.startsWith(GENERATION_PREFIX)) { "a generation is named $GENERATION_PREFIX<uuid>, was $name" }
            return LuceneIndex(indexDir, GenerationInfo(name, indexDir, identity))
        }

        /** The generation the marker names, or `null` when no marker has been published yet. */
        internal fun currentGenerationName(indexDir: Path): String? {
            val marker = indexDir.resolve(MARKER_FILE)
            if (!Files.exists(marker)) return null
            val name = Files.readString(marker).trim()
            check(name.isNotBlank()) {
                "the search index marker ($marker) is empty; refusing to guess which generation is active"
            }
            return name
        }

        /** Publishes [name] as the active generation with the same atomic rename every open resolves. */
        internal fun publishMarker(indexDir: Path, name: String) {
            writeMarkerAtomically(indexDir.resolve(MARKER_FILE), name)
        }

        /**
         * Removes the generations the marker no longer names.
         *
         * Startup runs this once the marker has been resolved, so the set it removes is exactly the
         * unreferenced one: a half-built successor whose writer died, and a retired generation left
         * open by a process that could not drain its readers. The current target is never a candidate,
         * whatever its name looks like — a successor keeps the `next` spelling after it is published,
         * and deleting the named generation would be deleting the index the caller just chose.
         */
        internal fun sweepUnreferenced(indexDir: Path, keep: String): List<String> {
            val removed = mutableListOf<String>()
            Files.list(indexDir).use { entries ->
                entries
                    .filter { entry ->
                        val name = entry.fileName.toString()
                        (name.startsWith(GENERATION_PREFIX) || name == "$MARKER_FILE.tmp") && name != keep
                    }
                    .sorted()
                    .forEach { entry ->
                        val name = entry.fileName.toString()
                        if (Files.isDirectory(entry)) {
                            deleteRecursively(entry)
                        } else {
                            Files.deleteIfExists(entry)
                        }
                        removed += name
                    }
            }
            return removed
        }

        private fun deleteRecursively(directory: Path) {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }

        private fun resolve(indexDir: Path, identity: IndexIdentity): GenerationInfo {
            val marker = indexDir.resolve(MARKER_FILE)
            if (Files.exists(marker)) {
                val fromMarker = Files.readString(marker).trim()
                check(fromMarker.isNotBlank()) {
                    "the search index marker ($marker) is empty; refusing to guess which generation is active"
                }
                val target = indexDir.resolve(fromMarker)
                check(Files.isDirectory(target)) {
                    "the search index marker names $fromMarker but $target does not exist"
                }
                return GenerationInfo(fromMarker, indexDir, identity)
            }

            val generations = Files.list(indexDir).use { entries ->
                entries
                    .filter { Files.isDirectory(it) && it.fileName.toString().startsWith(GENERATION_PREFIX) }
                    .toList()
            }
            check(generations.isEmpty()) {
                "no search index marker exists but generations were found (${generations.joinToString {
                    it.fileName.toString()
                }}); refusing to guess which is active"
            }

            // A complete empty generation, then the marker — in that order, so the marker can never
            // point at a half-written directory.
            val name = GENERATION_PREFIX + UUID.randomUUID()
            val directory = indexDir.resolve(name)
            FSDirectory.open(directory).use { dir ->
                IndexWriter(dir, IndexWriterConfig(newAnalyzer())).use { writer ->
                    writer.setLiveCommitData(identity.toUserData().entries.toList())
                    writer.commit()
                }
            }
            writeMarkerAtomically(marker, name)
            return GenerationInfo(name, indexDir, identity)
        }

        /**
         * Creates a fresh analyzer for one writer.
         *
         * Lucene 10 removed the single-class `ICUAnalyzer`, so the stream the plan calls "ICU" is built
         * explicitly here and the IndexWriter that is given it owns it (closing the writer closes the
         * analyzer), which is why each writer gets its own instance rather than a shared one.
         */
        internal fun newAnalyzer(): Analyzer =
            CustomAnalyzer.builder()
                .withTokenizer(StandardTokenizerFactory::class.java)
                .addTokenFilter(ICUFoldingFilterFactory::class.java)
                .addTokenFilter(LowerCaseFilterFactory::class.java)
                .build()

        private fun writeMarkerAtomically(marker: Path, name: String) {
            val temporary = marker.resolveSibling("$MARKER_FILE.tmp")
            Files.writeString(temporary, name)
            try {
                Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (failure: java.nio.file.AtomicMoveNotSupportedException) {
                // The same directory holds both ends, so this is the fallback rather than the rule.
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/** What one generation is: its name, its directory, its identity, and the handle to its writer. */
private class GenerationInfo internal constructor(
    val name: String,
    indexDir: Path,
    val identity: IndexIdentity,
) {

    val directoryPath: Path = indexDir.resolve(name)

    fun open(): GenerationHandle {
        val directory = FSDirectory.open(directoryPath)
        val analyzer = LuceneIndex.newAnalyzer()
        try {
            // The identity is written with the first commit, so a generation that is opened and
            // committed without ever being searched still records what its vectors were built with.
            val writer = IndexWriter(directory, IndexWriterConfig(analyzer)).apply {
                setLiveCommitData(identity.toUserData().entries.toList())
            }
            return try {
                val manager = SearcherManager(writer, SearcherFactory())
                GenerationHandle(name, directory, writer, manager, analyzer, identity)
            } catch (failure: Throwable) {
                writer.close()
                analyzer.close()
                throw failure
            }
        } catch (failure: Throwable) {
            directory.close()
            analyzer.close()
            throw failure
        }
    }
}

/**
 * The open half of a generation: the single writer, the searcher manager that reads it, and the
 * analyzer both sides share. The searcher manager's acquire/release pair is the "lease" the rebuild
 * protocol waits to drain before retiring a generation.
 */
private class GenerationHandle internal constructor(
    val name: String,
    val directory: Directory,
    val writer: IndexWriter,
    val manager: SearcherManager,
    val analyzer: Analyzer,
    val identity: IndexIdentity,
) : AutoCloseable {

    override fun close() {
        runCatching { manager.close() }
        runCatching { writer.close() }
        // The IndexWriter owns the analyzer and closes it with itself, so this handle does not close the
        // analyzer a second time.
        runCatching { directory.close() }
    }
}