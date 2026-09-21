package infoscry.search

import infoscry.config.AppPaths
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.embedding.QueryEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.DocumentStore
import infoscry.storage.Instants
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Retrieval over a real SQLite database and a real Lucene index, with a deterministic embedders pair:
 * a single-vector fake embedder per test indexes the chunks and the same vector answers the query, so
 * the semantic branch finds exactly what a deterministic vector says is nearest.
 *
 * Each test opens exactly one [LuceneIndex] for its data directory (a second writer on the same
 * generation would corrupt it) and shares that instance between indexing and [SearchService].
 */
class SearchServiceTest {

    private lateinit var dataDir: Path
    private lateinit var paths: AppPaths
    private lateinit var database: Database
    private lateinit var collections: CollectionStore
    private lateinit var documents: DocumentStore
    private lateinit var content: ContentStore

    @BeforeTest
    fun openStack() {
        dataDir = Files.createTempDirectory("infoscry-search")
        paths = AppPaths.of(dataDir)
        database = Database(paths.databaseFile)
        SchemaMigrator(database).migrate()
        collections = CollectionStore(database)
        documents = DocumentStore(database)
        content = ContentStore(database)
    }

    @AfterTest
    fun closeStack() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `reciprocal rank fusion scores both branches and cuts to the top`() {
        val keyword = listOf(hit("c", "d", "keyword-only-1", 0), hit("c", "d", "both-1", 0))
        val semantic = listOf(hit("c", "d", "both-1", 0), hit("c", "d", "semantic-only-1", 0))

        val fused = reciprocalRankFusion(keyword, semantic, k = 60, top = 30)

        // both-1 at rank 2 in both branches: 1/62 + 1/62, above either single-branch hit on its own.
        assertEquals("both-1", fused.first().hit.unitId)
        assertEquals(setOf(SearchMode.KEYWORD, SearchMode.SEMANTIC), fused.first().matchedBy)
        assertEquals(setOf(SearchMode.KEYWORD), fused.single { it.hit.unitId == "keyword-only-1" }.matchedBy)
        assertEquals(setOf(SearchMode.SEMANTIC), fused.single { it.hit.unitId == "semantic-only-1" }.matchedBy)
        assertTrue(
            fused.first().score > fused.single { it.hit.unitId == "keyword-only-1" }.score,
            "a hit present in both branches must outrank a hit present in one",
        )
    }

    @Test
    fun `fusion is top-cut, idempotent and deterministic on ties`() {
        val keyword = (1..40).map { hit("c", "d", "kw-$it", 0) }
        val semantic = (1..40).map { hit("c", "d", "sm-$it", 0) }

        val first = reciprocalRankFusion(keyword, semantic, k = 4, top = 5)
        val repeated = reciprocalRankFusion(keyword, semantic, k = 4, top = 5)

        assertEquals(5, first.size)
        assertEquals(first.map { it.hit.unitId }, repeated.map { it.hit.unitId })

        // Two hits tied on score sort by identity, not by the order they were fed in.
        val tiedKeyword = listOf(hit("c", "d", "kw-b", 0)) // 1/(4+1)
        val tiedSemantic = listOf(hit("c", "d", "kw-a", 0)) // 1/(4+1)
        val ascending = reciprocalRankFusion(tiedKeyword, tiedSemantic, k = 4, top = 5)
        val descending = reciprocalRankFusion(tiedSemantic, tiedKeyword, k = 4, top = 5)
        assertEquals(
            ascending.map { it.hit.unitId },
            descending.map { it.hit.unitId },
            "a score tie must break by identity, not by input order",
        )
    }

    @Test
    fun `keyword and semantic branches carry their own badge`() {
        val collection = collections.create("Default-collection")
        val document = insertDocument(collection.id, "single-branch.txt", "text/plain")
        openService(embedder = { FixedQueryEmbedder() }).let { (service, index) ->
            try {
                runBlocking { indexChunk(index, collection.id, document.id, "nightfall across the sea") }

                val keywordOnly = service.search("nightfall", mode = SearchMode.KEYWORD)
                assertEquals(setOf(SearchMode.KEYWORD), keywordOnly.hits.single().matchedBy)

                val semanticOnly = service.search("nightfall", mode = SearchMode.SEMANTIC)
                assertEquals(setOf(SearchMode.SEMANTIC), semanticOnly.hits.single().matchedBy)
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `keyword search finds, highlights and safely escapes the matched text`() {
        val collection = collections.create("Default-collection")
        val document = insertDocument(collection.id, "report.txt", "text/plain")
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking { indexChunk(index, collection.id, document.id, "the <nightfall> report budget") }

                val outcome = service.search("nightfall", mode = SearchMode.KEYWORD)

                val hit = outcome.hits.single()
                assertContains(hit.highlighted.orEmpty(), "<mark>nightfall</mark>")
                assertTrue(hit.highlighted.orEmpty().contains("&lt;"), "document text must be HTML-escaped")
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `invalid search syntax is escaped rather than surfaced as an error`() {
        val collection = collections.create("Default-collection")
        val document = insertDocument(collection.id, "report.txt", "text/plain")
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking { indexChunk(index, collection.id, document.id, "nightfall operations") }

                val outcome = service.search("nightfall::::!!((", mode = SearchMode.KEYWORD)

                assertTrue(outcome.hits.any { it.documentId == document.id }, "the escaped query should still match")
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `semantic search with an unavailable model is an actionable code, not a crash`() {
        val collection = collections.create("Default-collection")
        insertDocument(collection.id, "report.txt", "text/plain")
        openService(absentEmbedder()).let { (service, index) ->
            try {
                val failure = assertFailsWith<SearchUnavailableException> {
                    service.search("nightfall", mode = SearchMode.SEMANTIC)
                }
                assertEquals("MODEL_NOT_INSTALLED", failure.code)
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `an index that does not match the pinned model refuses semantic search only`() {
        val collection = collections.create("Default-collection")
        val document = insertDocument(collection.id, "report.txt", "text/plain")
        LuceneIndex.open(paths.indexDir, IDENTITY).use { index ->
            runBlocking { indexChunk(index, collection.id, document.id, "nightfall report") }
        }
        val staleIndex = LuceneIndex.open(paths.indexDir, IDENTITY.copy(modelFingerprint = "another-model"))
        try {
            val service = SearchService(
                collections = collections,
                documents = documents,
                index = staleIndex,
                queryEmbedder = { FixedQueryEmbedder() },
            )

            val failure = assertFailsWith<SearchUnavailableException> {
                service.search("nightfall", mode = SearchMode.SEMANTIC)
            }
            assertEquals(SearchService.REBUILD_REQUIRED_CODE, failure.code)

            val keywordOnly = service.search("nightfall", mode = SearchMode.KEYWORD)
            assertEquals(1, keywordOnly.hits.size)
        } finally {
            staleIndex.close()
        }
    }

    @Test
    fun `document criteria restrict the search before retrieval`() {
        val collection = collections.create("Default-collection")
        val pdf = insertDocument(collection.id, "annual.pdf", "application/pdf", status = DocumentStatus.COMPLETE)
        val text = insertDocument(collection.id, "notes.txt", "text/plain", status = DocumentStatus.COMPLETE)
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking {
                    indexChunk(index, collection.id, pdf.id, "nightfall budget")
                    indexChunk(index, collection.id, text.id, "nightfall budget")
                }

                fun filtered(mediaTypes: Set<String>) = service.search(
                    "budget",
                    mode = SearchMode.KEYWORD,
                    filters = SearchFilters(collectionId = collection.id, mediaTypes = mediaTypes),
                )

                assertEquals(setOf(pdf.id), filtered(setOf("application/pdf")).hits.map { it.documentId }.toSet())
                assertEquals(setOf(text.id), filtered(setOf("text/plain")).hits.map { it.documentId }.toSet())
                assertEquals(0, filtered(setOf("image/png")).hits.size)
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `filename and language criteria restrict before retrieval`() {
        val collection = collections.create("Default-collection")
        val swedish = insertDocument(collection.id, "rapport2024.pdf", "application/pdf", language = "sv")
        val english = insertDocument(collection.id, "annual-report.pdf", "application/pdf", language = "en")
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking {
                    // Both chunks contain the token "budget", so the unfiltered query matches both and
                    // only the criterion distinguishes them.
                    indexChunk(index, collection.id, swedish.id, "budget rapporten är på svenska")
                    indexChunk(index, collection.id, english.id, "budget the annual report is in english")
                }

                fun search(filter: SearchFilters) = service.search(
                    "budget",
                    mode = SearchMode.KEYWORD,
                    filters = filter,
                )

                assertEquals(
                    setOf(swedish.id),
                    search(
                        SearchFilters(collectionId = collection.id, filenameOrPathContains = "rapport2024"),
                    ).hits.map { it.documentId }.toSet(),
                )
                assertEquals(
                    setOf(swedish.id),
                    search(
                        SearchFilters(collectionId = collection.id, titleAuthorOrLanguageContains = "sv"),
                    ).hits.map { it.documentId }.toSet(),
                )
                assertEquals(
                    setOf(english.id),
                    search(
                        SearchFilters(collectionId = collection.id, titleAuthorOrLanguageContains = "en"),
                    ).hits.map { it.documentId }.toSet(),
                )
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `status criteria restrict before retrieval`() {
        val collection = collections.create("Default-collection")
        val complete = insertDocument(collection.id, "read.pdf", "application/pdf", status = DocumentStatus.COMPLETE)
        val failed = insertDocument(collection.id, "failure.pdf", "application/pdf", status = DocumentStatus.FAILED)
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking {
                    indexChunk(index, collection.id, complete.id, "nightfall complete")
                    indexChunk(index, collection.id, failed.id, "nightfall failed")
                }

                val completeOnly = service.search(
                    "nightfall",
                    mode = SearchMode.KEYWORD,
                    filters = SearchFilters(collectionId = collection.id, statuses = setOf(DocumentStatus.COMPLETE)),
                )
                assertEquals(setOf(complete.id), completeOnly.hits.map { it.documentId }.toSet())

                val failedOnly = service.search(
                    "nightfall",
                    mode = SearchMode.KEYWORD,
                    filters = SearchFilters(collectionId = collection.id, statuses = setOf(DocumentStatus.FAILED)),
                )
                assertEquals(setOf(failed.id), failedOnly.hits.map { it.documentId }.toSet())
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `ocr-only keeps only documents with a recorded ocr unit`() {
        val collection = collections.create("Default-collection")
        val plain = insertDocument(collection.id, "plain.pdf", "application/pdf")
        val scanned = insertDocument(collection.id, "scanned.pdf", "application/pdf")
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking {
                    indexChunk(index, collection.id, plain.id, "typed text")
                    indexChunk(index, collection.id, scanned.id, "scanned text")
                }
                commitOcrUnit(scanned.id)

                val outcome = service.search(
                    "text",
                    mode = SearchMode.KEYWORD,
                    filters = SearchFilters(collectionId = collection.id, ocrOnly = true),
                )

                assertEquals(setOf(scanned.id), outcome.hits.map { it.documentId }.toSet())
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `hits whose document was deleted are dropped and counted as stale`() {
        val collection = collections.create("Delete-me")
        val document = insertDocument(collection.id, "gone.pdf", "application/pdf")
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking { indexChunk(index, collection.id, document.id, "nightfall before deletion") }
            } finally {
                index.close()
            }
        }

        assertTrue(documents.delete(document.id))

        openService(absentEmbedder()).let { (service, index) ->
            try {
                val outcome = service.search("nightfall", mode = SearchMode.KEYWORD)
                assertEquals(0, outcome.hits.size)
                assertEquals(1, outcome.staleFiltered)
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `hits in a tombstoned collection are never surfaced`() {
        val collection = collections.create("Tombstoned")
        val document = insertDocument(collection.id, "nights.pdf", "application/pdf")
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking { indexChunk(index, collection.id, document.id, "nightfall in the tombstoned collection") }
            } finally {
                index.close()
            }
        }

        markTombstoned(collection.id)

        openService(absentEmbedder()).let { (service, index) ->
            try {
                val outcome = service.search("nightfall", mode = SearchMode.KEYWORD)
                assertEquals(0, outcome.hits.size)
                assertEquals(1, outcome.staleFiltered)
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `fixture expectations hold under keyword search with the deterministic embedder`() {
        val expectations = SearchFixtureExpectations.load()
        val collection = collections.create("Fixtures")
        val documentIds = mutableMapOf<String, DocumentId>()
        openService(absentEmbedder()).let { (service, index) ->
            try {
                for (fixture in expectations.fixtures()) {
                    val document = insertDocument(collection.id, fixture.first, "text/plain")
                    documentIds[fixture.first] = document.id
                    runBlocking { indexChunk(index, collection.id, document.id, fixture.second) }
                }

                for (expectation in expectations.expectations) {
                    val outcome = service.search(
                        expectation.query,
                        mode = SearchMode.KEYWORD,
                        filters = SearchFilters(collectionId = collection.id),
                    )
                    val expectedDocument = documentIds[expectation.fixture]
                        ?: error("no fixture document for ${expectation.fixture}")
                    val hit = outcome.hits.firstOrNull { it.documentId == expectedDocument }
                    assertTrue(
                        hit != null && hit.text.contains(expectation.expectedTextContains),
                        "query '${expectation.query}' in ${expectation.fixture} must find " +
                            "'${expectation.expectedTextContains}' in the top ${SearchService.FUSION_TOP} results",
                    )
                }
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `blank query is an empty outcome`() {
        val collection = collections.create("Empty")
        val document = insertDocument(collection.id, "any.pdf", "application/pdf")
        openService(absentEmbedder()).let { (service, index) ->
            try {
                runBlocking { indexChunk(index, collection.id, document.id, "any words at all") }
                assertEquals(0, service.search("   ", mode = SearchMode.KEYWORD).hits.size)
            } finally {
                index.close()
            }
        }
    }

    @Test
    fun `a criterion beyond the scope term bound is refused actionably instead of throwing`() {
        val collection = collections.create("broad-filter-collection")
        val bound = 5
        val (service, index) = openService(absentEmbedder(), maxScopeTerms = bound)
        try {
            repeat(bound + 2) { count ->
                insertDocument(collection.id, "bulk-$count.pdf", "text/plain")
            }
            val failure = assertFailsWith<SearchUnavailableException> {
                service.search(
                    "nightfall",
                    mode = SearchMode.KEYWORD,
                    filters = SearchFilters(mediaTypes = setOf("text/plain")),
                )
            }
            assertEquals(SearchService.FILTER_TOO_BROAD_CODE, failure.code)
            assertContains(failure.remedy, "narrow")
        } finally {
            index.close()
        }
    }

    @Test
    fun `a criterion within the scope term bound still searches`() {
        val collection = collections.create("narrow-filter-collection")
        val bound = 5
        val (service, index) = openService(absentEmbedder(), maxScopeTerms = bound)
        try {
            repeat(bound - 1) { count ->
                insertDocument(collection.id, "few-$count.txt", "text/plain")
            }
            val outcome = service.search(
                "no-chunk-matches-this",
                mode = SearchMode.KEYWORD,
                filters = SearchFilters(mediaTypes = setOf("text/plain")),
            )
            assertEquals(0, outcome.hits.size)
            assertEquals(0, outcome.staleFiltered)
        } finally {
            index.close()
        }
    }

    @Test
    fun `fusion scores are strictly one based`() {
        val keyword = listOf(hit("c", "d", "rank-one-only", 0))
        val fused = reciprocalRankFusion(keyword, emptyList(), k = 60, top = 5)
        assertEquals(1.0 / 61.0, fused.single().score, "rank 1 must score exactly 1/(60+1)")
    }

    @Test
    fun `a hit whose stored locator does not decode is dropped, not cited as line one`() {
        val collection = collections.create("garbled-locator-collection")
        val document = insertDocument(collection.id, "garbled.txt", "text/plain")
        val (service, index) = openService(absentEmbedder())
        try {
            val garbage = fusedHit(collection.id.value, document.id.value, "unit-a", "not-json")
            assertNull(service.resolveHit(garbage, "nightfall"), "an undecodable locator must drop the hit")

            // A locator row as an earlier version persisted it: the discriminator is the explicit serial
            // name (`text_lines`), not the Kotlin class name. The literal pins that stored contract, and the
            // round-trip assertion below proves the current encoder still reads and writes the same form —
            // a round trip alone would keep passing if the serial name changed and orphaned older rows.
            val persisted = """{"type":"text_lines","start":2,"end":2}"""
            assertEquals(
                persisted,
                Json.encodeToString(SourceLocation.serializer(), SourceLocation.TextLines(2, 2)),
            )

            val valid = fusedHit(collection.id.value, document.id.value, "unit-b", persisted)
            val resolved = service.resolveHit(valid, "nightfall")
            assertEquals(SourceLocation.TextLines(2, 2), resolved?.locator)
        } finally {
            index.close()
        }
    }

    // ---- fixtures ----

    /** One LuceneIndex for the whole test, shared by indexing and the service. */
    private fun openService(
        embedder: () -> QueryEmbedder?,
        maxScopeTerms: Int = SearchService.DEFAULT_MAX_SCOPE_TERMS,
    ): Pair<SearchService, LuceneIndex> {
        val index = LuceneIndex.open(paths.indexDir, IDENTITY)
        val service = SearchService(
            collections = collections,
            documents = documents,
            index = index,
            queryEmbedder = embedder,
            maxScopeTerms = maxScopeTerms,
        )
        return service to index
    }

    private fun absentEmbedder(): () -> QueryEmbedder? = { null }

    private fun insertDocument(
        collectionId: CollectionId,
        filename: String,
        mediaType: String,
        status: DocumentStatus = DocumentStatus.COMPLETE,
        language: String? = null,
    ): Document {
        val now = Instants.now()
        val document = Document(
            id = DocumentId.new(),
            collectionId = collectionId,
            sha256 = "sha-${filename}",
            mediaType = mediaType,
            originalFilename = filename,
            sourcePath = "tmp/original/$filename",
            sizeBytes = 1L,
            status = status,
            title = filename,
            author = null,
            language = language,
            createdAt = now,
            updatedAt = now,
        )
        documents.insert(document)
        return document
    }

    private suspend fun indexChunk(
        index: LuceneIndex,
        collectionId: CollectionId,
        documentId: DocumentId,
        text: String,
    ) {
        index.replaceDocument(
            listOf(
                DocumentRow(
                    collectionId = collectionId,
                    documentId = documentId,
                    unitId = ContentUnitId.new(),
                    chunk = Chunk(
                        id = ChunkId.new(),
                        contentUnitId = ContentUnitId.new(),
                        ordinal = 0,
                        text = text,
                        startOffset = 0,
                        endOffset = text.length,
                        tokenCount = text.length,
                        tokenStart = 0,
                        tokenEnd = (text.length - 1).coerceAtLeast(0),
                    ),
                    locator = SourceLocation.TextLines(1, 1),
                    locatorLabel = "line 1",
                    vector = vectorFor(text),
                ),
            ),
        )
    }

    /** A scanned document with an OCR unit: its mean confidence is what the ocr-only filter sees. */
    private fun commitOcrUnit(documentId: DocumentId) {
        val document = documents.get(documentId) ?: error("document must exist")
        val settings = ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "search-test")
        val fingerprint = ExtractionFingerprint.of(document.sha256, settings)
        content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = fingerprint,
            key = "ocr-page-1",
            ordinal = 0,
            draft = ContentUnitDraft(
                locator = SourceLocation.PdfPage(1),
                extractedText = "scanned text",
                searchText = "scanned text",
                meanConfidence = 0.9,
            ),
            artifactRoot = paths.libraryDir,
        )
    }

    private fun markTombstoned(collectionId: CollectionId) {
        database.transaction { connection ->
            connection.prepareStatement("UPDATE collections SET lifecycle = ? WHERE id = ?").use { statement ->
                statement.setString(1, CollectionLifecycle.DELETING.name)
                statement.setString(2, collectionId.value)
                statement.executeUpdate()
            }
        }
    }

    private fun fusedHit(
        collectionId: String,
        documentId: String,
        unitId: String,
        locator: String,
    ): FusedSearchHit {
        val indexHit = hit(collectionId, documentId, unitId, 0).copy(locator = locator)
        return FusedSearchHit(indexHit, setOf(SearchMode.KEYWORD), 0.0)
    }

    private fun hit(collectionId: String, documentId: String, unitId: String, ordinal: Int) = IndexHit(
        collectionId = collectionId,
        documentId = documentId,
        unitId = unitId,
        chunkOrdinal = ordinal,
        text = "the nightfall report",
        locator = """{"type":"TextLines","start":1,"end":1}""",
        locatorLabel = "line 1",
        score = 1.0f,
    )

    private companion object {
        val IDENTITY = IndexIdentity(
            schemaVersion = LuceneSchema.SCHEMA_VERSION,
            model = "intfloat/multilingual-e5-base",
            modelRevision = "d128750597153bb5987e10b1c3493a34e5a4502a",
            modelFingerprint = "expected-model-fingerprint",
            dimension = 768,
        )
    }
}

/** A query embedder that answers with a fixed vector, matching whichever chunk was indexed with it. */
private class FixedQueryEmbedder : QueryEmbedder {
    override fun embedQuery(query: String): FloatArray = vectorFor(query)
}

/** The committed keyword expectations over the fixture corpus, loaded from `search-expectations.json`. */
private class SearchFixtureExpectations private constructor(
    val expectations: List<Expectation>,
) {

    /** The expected units record a fixture name; this exposes those names with their classpath text. */
    fun fixtures(): List<Pair<String, String>> = expectations.map { it.fixture }.distinct().map { name ->
        name to resourceText(name)
    }

    data class Expectation(
        val query: String,
        val fixture: String,
        val expectedTextContains: String,
    )

    companion object {
        fun load(): SearchFixtureExpectations {
            val root = Json.parseToJsonElement(resourceText("search-expectations.json")).jsonObject
            val expectations = root.getValue("expectations").jsonArray.map { element ->
                val objectEntry = element.jsonObject
                Expectation(
                    query = objectEntry.getValue("query").jsonPrimitive.content
                        .also { check(it.isNotBlank()) { "an expectation needs a query" } },
                    fixture = objectEntry.getValue("fixture").jsonPrimitive.content
                        .also { check(it.isNotBlank()) { "an expectation needs a fixture" } },
                    expectedTextContains = objectEntry.getValue("expectedTextContains").jsonPrimitive.content
                        .also { check(it.isNotBlank()) { "an expectation needs expectedTextContains" } },
                )
            }
            return SearchFixtureExpectations(expectations)
        }

        private fun resourceText(name: String): String =
            SearchFixtureExpectations::class.java.getResourceAsStream("/fixtures/$name")
                ?.readBytes()
                ?.toString(Charsets.UTF_8)
                ?: error("fixture resource /fixtures/$name not found on the classpath")
    }
}