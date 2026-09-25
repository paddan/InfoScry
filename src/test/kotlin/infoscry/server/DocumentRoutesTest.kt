package infoscry.server

import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
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
import kotlinx.coroutines.runBlocking

/**
 * The paginated document listing over a real socket: what a page contains, how the total behaves, and
 * what must never leave the server.
 *
 * The privacy assertions run against the raw response body rather than a decoded object, because a
 * decoded [DocumentsResponse] could not express the leak being guarded against: the endpoint building
 * that object already decided what is not part of the product surface.
 */
class DocumentRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-documents-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `an empty collection lists zero documents`() = runBlocking {
        val id = newCollection()

        val response = harness.get("/api/collections/$id/documents")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val page = ApiJson.decodeFromString<DocumentsResponse>(response.bodyAsText())
        assertEquals(emptyList(), page.documents)
        assertEquals(0, page.total)
    }

    @Test
    fun `documents are paginated and the total does not depend on the page size`() = runBlocking {
        val id = CollectionId(newCollection())
        repeat(205) { index -> harness.context.documents.insert(document(index, id)) }

        val first = page(id, "?limit=2")
        assertEquals(listOf("doc-204", "doc-203"), first.documents.map { it.id.value })
        assertEquals(205, first.total)

        val second = page(id, "?limit=2&offset=2")
        assertEquals(listOf("doc-202", "doc-201"), second.documents.map { it.id.value })
        assertEquals(205, second.total, "the total counts the collection, not the page")

        val tail = page(id, "?limit=50&offset=200")
        assertEquals(5, tail.documents.size)
        assertEquals(205, tail.total)

        val clamped = page(id, "?limit=500")
        assertEquals(200, clamped.documents.size, "a limit above 200 is clamped, not honoured or refused")
        assertEquals(205, clamped.total)

        val small = page(id, "?limit=10")
        assertEquals(10, small.documents.size)
        assertEquals(205, small.total)
    }

    @Test
    fun `the response omits the source path, the hash and the error message`() = runBlocking {
        val id = CollectionId(newCollection())
        harness.context.documents.insert(
            document(0, id).copy(
                sha256 = "aeac0c37f2f81db1c2e13d68aa5c4f7b14c0f9e1",
                sourcePath = "/private/evidence/quarterly-figures.pdf",
                title = "Quarterly figures",
                author = "Finance",
                language = "en",
            ),
        )
        harness.context.documents.insert(
            document(1, id).copy(
                status = DocumentStatus.FAILED,
                errorCode = "PDF_PARSE_FAILED",
                errorMessage = "could not parse page 2 of the 2025 budget worksheet",
            ),
        )

        val response = harness.get("/api/collections/${id.value}/documents")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()

        assertFalse(body.contains("sourcePath"), "the domain field sourcePath must never be serialized")
        assertFalse(body.contains("original_path"), "the wire name of sourcePath must never appear")
        assertFalse(body.contains("sha256"), "the content hash must never be serialized")
        assertFalse(body.contains("errorMessage"), "errorMessage can carry document text and is omitted")
        assertFalse(body.contains("2025 budget worksheet"), "error message text must not reach the response")
        assertFalse(body.contains("/private/evidence"), "the filesystem path itself must not reach the response")

        listOf(
            "originalFilename",
            "mediaType",
            "sizeBytes",
            "status",
            "title",
            "author",
            "language",
            "errorCode",
            "createdAt",
            "updatedAt",
        ).forEach { field ->
            assertContains(body, field, message = "the list row needs $field to render")
        }
    }

    @Test
    fun `an unknown collection is not found`() = runBlocking {
        val response = harness.get("/api/collections/does-not-exist/documents")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `an invalid limit or offset is a typed bad request`() = runBlocking {
        val id = newCollection()
        val base = "/api/collections/$id/documents"

        listOf("?limit=abc", "?limit=1.5", "?limit=0", "?limit=-3", "?offset=abc", "?offset=-1")
            .forEach { query ->
                val response = harness.get(base + query)
                assertEquals(HttpStatusCode.BadRequest, response.status, "query $query")
                assertContains(response.bodyAsText(), "INVALID_REQUEST", message = "query $query must be a typed bad request")
            }

        assertEquals(HttpStatusCode.OK, harness.get("$base?limit=1&offset=0").status)
    }

    @Test
    fun `the listing needs no credential, like every other read route`() = runBlocking {
        val id = newCollection()

        val response = harness.request(
            HttpMethod.Get,
            "/api/collections/$id/documents",
            body = null,
            credential = Credential.NONE,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun newCollection(): String {
        harness.createCollection("Docs", Credential.BEARER)
        return harness.collectionIdOf("Docs")
    }

    private suspend fun page(id: CollectionId, query: String): DocumentsResponse =
        ApiJson.decodeFromString<DocumentsResponse>(
            harness.get("/api/collections/${id.value}/documents$query").bodyAsText(),
        )

    private fun document(index: Int, collectionId: CollectionId): Document = Document(
        id = DocumentId("doc-$index"),
        collectionId = collectionId,
        sha256 = "sha256-of-doc-$index",
        mediaType = "application/pdf",
        originalFilename = "report-$index.pdf",
        sourcePath = "/private/evidence/report-$index.pdf",
        sizeBytes = 1024L * (index + 1),
        status = DocumentStatus.COMPLETE,
        title = "Report $index",
        author = "Author $index",
        language = "en",
        createdAt = "2026-01-01T00:00:00.${"%03d".format(index)}Z",
        updatedAt = "2026-01-01T00:00:00.${"%03d".format(index)}Z",
    )
}