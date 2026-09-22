package infoscry.server

import infoscry.AppContext
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.search.DocumentRow
import infoscry.search.vectorFor
import infoscry.storage.CollectionStore
import infoscry.storage.Instants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The search and citation boundary over a real socket: what each search answers, and which status each
 * refusal carries.
 *
 * The search endpoints are where the archive's core promise shows up — a hit is a citation that opens a
 * unit, and neither may leak more than the citation needs. Two classes of refusal exist on purpose: the
 * caller's own mistake is a 4xx, and an environment the caller cannot fix is a 503 with the remedy. The
 * tests below seed a deterministic index through the server's own context and drive the wire, because
 * the contract is the status and the body, not a handler called directly.
 */
class SearchRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-search-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    // ---- The collection boundary ----

    @Test
    fun `a search without a collection is refused as a bad request`() = runBlocking {
        val response = harness.get("/api/search?q=nightfall")

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "collection is required")
    }

    @Test
    fun `a search against an unknown collection is refused as not found`() = runBlocking {
        val response = harness.get("/api/search?collection=nobody&q=nightfall")

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    // ---- The response shape: hits and empty results ----

    @Test
    fun `a keyword search returns hits without ever exposing document text`() = runBlocking {
        // Three units in one document: the response must name the matching chunk's citation and snippet,
        // and must not carry the other two units' text — a hit is a citation, not a document.
        val (documentId, _) = seedUnit(document = "evidence.txt", text = "the nightfall report is sealed")
        seedUnit(document = "evidence.txt", text = "the alibi was never tested")
        seedUnit(document = "evidence.txt", text = "the ledger shows a pattern of transfers")

        val response = harness.get("/api/search?collection=Default&q=nightfall&mode=keyword")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertContains(body, "nightfall")
        assertFalse(body.contains("the alibi was never tested"), "one hit carried another unit's text")
        assertFalse(body.contains("the ledger shows"), "one hit carried another unit's text")
        assertFalse(body.contains("\"extractedText\""), "the search response exposes extracted document text")
        assertContains(body, "\"locator\":")
        assertContains(body, "\"unitId\":")
        assertContains(body, documentId.value)
    }

    @Test
    fun `a keyword search with no matches is a stable empty result, not an error`() = runBlocking {
        val response = harness.get("/api/search?collection=Default&q=zzzzzz&mode=keyword")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        // The body is the same shape with an empty list: not null, not an error object.
        assertEquals("""{"hits":[],"staleFiltered":0}""", response.bodyAsText())
    }

    @Test
    fun `POST search reads the same contract as GET`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/search",
            body = """{"query":"nightfall","filters":{"collection":"Default","mode":"keyword"}}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "\"hits\":[]")
    }

    @Test
    fun `a malformed POST search body is refused as a bad request`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/search",
            body = """{"query":42}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "INVALID_REQUEST")
    }

    // ---- The two status classes ----

    @Test
    fun `hybrid search without a model is a service-unavailable carrying the install remedy`() = runBlocking {
        // The default mode is hybrid, which embeds the query; a temporary data directory has no model,
        // so the answer is the environment class: 503 with the remedy that fixes it. The keyword half
        // is a separate, caller-readable success, which the keyword tests above pin.
        val response = harness.get("/api/search?collection=Default&q=nightfall")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertContains(body, "MODEL_NOT_INSTALLED")
        assertContains(body, "embeddingModel")
    }

    @Test
    fun `an over-long query is a bad request carrying the shortener`() = runBlocking {
        // The body carries the query, because a 1,200-word query in the query string would hit a
        // URI-length cap before the server could apply its own bound.
        val longQuery = (0 until 1_200).map { "word" }.joinToString(" ")
        val response = harness.request(
            HttpMethod.Post,
            "/api/search",
            body = """{"query":"$longQuery","filters":{"collection":"Default","mode":"keyword"}}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "QUERY_TOO_LONG")
    }

    // ---- Content units, and why a deleted collection's unit is not served ----

    @Test
    fun `a content unit from a live collection is served and one a collection no longer has is not found`() =
        runBlocking {
            val (_, unitId) = seedUnit(document = "notes.txt", text = "notes about the harbour")
            val response = harness.get("/api/content-units/${unitId.value}")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "notes about the harbour")

            harness.request(
                HttpMethod.Delete,
                "/api/collections/${harness.collectionIdOf("Default")}",
                body = """{"confirmName":"Default"}""",
                credential = Credential.BEARER,
            )

            val afterDelete = harness.get("/api/content-units/${unitId.value}")
            assertEquals(HttpStatusCode.NotFound, afterDelete.status, afterDelete.bodyAsText())
        }

    @Test
    fun `an unknown content unit is not found`() = runBlocking {
        val response = harness.get("/api/content-units/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    // ---- A nominal reindex accept: 202 with a job ----

    @Test
    fun `reindex is accepted as a job`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/reindex",
            body = """{"collection":"Default"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertContains(body, "\"accepted\":true")
        assertContains(body, "\"jobId\":")
    }

    /** Seeds one unit in the Default collection and indexes it, returning the document and unit ids. */
    private suspend fun seedUnit(document: String, text: String): Pair<DocumentId, ContentUnitId> {
        val context = harness.context
        val collection = CollectionStore.DEFAULT_ID
        val now = Instants.now()
        val documentId = DocumentId.new()
        context.documents.insert(
            Document(
                id = documentId,
                collectionId = collection,
                sha256 = "sha-$document:$text",
                mediaType = "text/plain",
                originalFilename = document,
                sourcePath = "tmp/original/$document",
                sizeBytes = 1L,
                status = DocumentStatus.COMPLETE,
                title = document,
                author = null,
                language = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val committed = context.content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = ExtractionFingerprint.of(
                "sha-$document",
                ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "search-routes-test"),
            ),
            key = "unit-0",
            ordinal = 0,
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
            ),
            artifactRoot = context.paths.libraryDir,
        )
        val unitId = committed.unit.id
        val chunk = Chunk(
            id = ChunkId.new(),
            contentUnitId = unitId,
            ordinal = 0,
            text = text,
            startOffset = 0,
            endOffset = text.length,
            tokenCount = text.length,
            tokenStart = 0,
            tokenEnd = (text.length - 1).coerceAtLeast(0),
        )
        context.index().replaceDocument(
            listOf(
                DocumentRow(
                    collectionId = collection,
                    documentId = documentId,
                    unitId = unitId,
                    locator = SourceLocation.TextLines(1, 1),
                    locatorLabel = "line 1",
                    chunk = chunk,
                    vector = vectorFor(text),
                ),
            ),
        )
        return documentId to unitId
    }
}