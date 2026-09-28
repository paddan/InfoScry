package infoscry.server

import infoscry.document.DocumentIndexRemover
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The document deletion API over a real socket: what admission answers with, what the read-only operation
 * status says afterwards, and what a request that must not be admitted leaves behind.
 *
 * The point of the route is that a deletion is not a request: it is validated against the active
 * collection before anything happens, it survives the caller that asked for it, and it can be followed to
 * its terminal phase even after the document's row is gone.
 */
class DocumentDeletionRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-document-deletion-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a confirmed deletion is accepted with its operation and reads back as a document deletion`() = runBlocking {
        val id = CollectionId(createCollection("Nightfall"))
        val target = importDocument(id, "target.txt", "the target material")
        val survivor = importDocument(id, "survivor.txt", "the survivor material")

        val admitted = admitDocumentDeletion(id.value, listOf(target.value))

        assertEquals(id.value, admitted.collectionId)
        assertEquals(listOf(target.value), admitted.documentIds)
        assertFalse(admitted.operationId.isBlank())

        val finished = harness.awaitDeletion(admitted.operationId)

        assertEquals("DOCUMENT", finished.kind)
        assertEquals(id.value, finished.collectionId)
        assertEquals("Nightfall", finished.collectionName)
        assertEquals(listOf(target.value), finished.documentIds)
        assertEquals("DONE", finished.phase)
        assertTrue(finished.terminal)
        assertNull(finished.errorCode)

        // The target's row is gone; the other document is untouched.
        assertEquals(
            HttpStatusCode.NotFound,
            harness.get("/api/collections/${id.value}/documents/${target.value}").status,
        )
        val listed = ApiJson.decodeFromString<DocumentsResponse>(
            harness.get("/api/collections/${id.value}/documents").bodyAsText(),
        )
        assertEquals(listOf(survivor.value), listed.documents.map { it.id.value })
    }

    @Test
    fun `the operation read hands out no managed or trash path`() = runBlocking {
        val id = CollectionId(createCollection("Nightfall"))
        val target = importDocument(id, "target.txt", "the target material")

        val admitted = admitDocumentDeletion(id.value, listOf(target.value))
        harness.awaitDeletion(admitted.operationId)

        val body = harness.get("/api/deletions/${admitted.operationId}").bodyAsText()

        assertFalse(body.contains(dataDir.toString()), "the data directory is not the client's business: $body")
        assertFalse(body.contains("trash"), "the parked directory stays inside the process: $body")
    }

    @Test
    fun `a document that must not be deleted rejects the whole request before any side effect`() = runBlocking {
        val id = CollectionId(createCollection("Nightfall"))
        val other = CollectionId(createCollection("Elsewhere"))
        val target = importDocument(id, "target.txt", "the target material")
        val foreign = importDocument(other, "foreign.txt", "another collection's material")

        val unconfirmed = harness.request(
            HttpMethod.Post,
            "/api/collections/${id.value}/documents/delete",
            body = """{"documentIds":["${target.value}"],"confirmed":false}""",
            credential = Credential.BEARER,
        )
        val uncredentialed = harness.request(
            HttpMethod.Post,
            "/api/collections/${id.value}/documents/delete",
            body = """{"documentIds":["${target.value}"],"confirmed":true}""",
            credential = Credential.NONE,
        )
        val empty = harness.request(
            HttpMethod.Post,
            "/api/collections/${id.value}/documents/delete",
            body = """{"documentIds":[],"confirmed":true}""",
            credential = Credential.BEARER,
        )
        val unknown = harness.request(
            HttpMethod.Post,
            "/api/collections/${id.value}/documents/delete",
            body = """{"documentIds":["does-not-exist"],"confirmed":true}""",
            credential = Credential.BEARER,
        )
        val crossCollection = harness.request(
            HttpMethod.Post,
            "/api/collections/${id.value}/documents/delete",
            body = """{"documentIds":["${target.value}","${foreign.value}"],"confirmed":true}""",
            credential = Credential.BEARER,
        )
        val tooMany = harness.request(
            HttpMethod.Post,
            "/api/collections/${id.value}/documents/delete",
            body = """{"documentIds":[${(1..201).joinToString(",") { "\"doc-$it\"" }}],"confirmed":true}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, unconfirmed.status, unconfirmed.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, uncredentialed.status, uncredentialed.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, empty.status, empty.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, unknown.status, unknown.bodyAsText())
        assertContains(unknown.bodyAsText(), "NOT_FOUND")
        assertEquals(HttpStatusCode.NotFound, crossCollection.status, crossCollection.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, tooMany.status, tooMany.bodyAsText())

        // Nothing was admitted, and neither the request's own target nor the foreign one was touched.
        assertTrue(
            ApiJson.decodeFromString<DeletionsResponse>(harness.get("/api/deletions").bodyAsText()).deletions.isEmpty(),
            "a refused request may not admit a deletion",
        )
        assertTrue(harness.context.documents.get(target) != null, "the target is untouched")
        assertTrue(harness.context.documents.get(foreign) != null, "another collection's document is untouched")
        assertTrue(
            Files.list(harness.context.paths.libraryDir).use { entries ->
                entries.noneMatch { it.fileName.toString().startsWith(".deleted-") }
            },
            "a refused deletion parks nothing",
        )
    }

    @Test
    fun `a repeated confirmation answers with the same unfinished operation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = DocumentIndexRemover {
            entered.complete(Unit)
            release.await()
        }
        Files.createTempDirectory("infoscry-document-deletion-repeat").let { gatedDir ->
            try {
                ApiTestServer(gatedDir, documentIndex = gated).use { server ->
                    server.createCollection("Nightfall", Credential.BEARER)
                    val id = CollectionId(server.collectionIdOf("Nightfall"))
                    val target = importDocument(server, id, "target.txt", "the target material")

                    val first = admitDocumentDeletion(server, id.value, listOf(target.value))
                    withTimeout(TIMEOUT_MILLIS) { entered.await() }
                    val second = admitDocumentDeletion(server, id.value, listOf(target.value))

                    assertEquals(
                        first.operationId,
                        second.operationId,
                        "a repeated confirmation is not a second deletion",
                    )
                    assertEquals(1, server.context.deletions.listUnfinished().size, "one operation, not two")

                    release.complete(Unit)
                    assertEquals("DONE", server.awaitDeletion(first.operationId).phase)
                }
            } finally {
                gatedDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a second deletion is refused while one holds exclusive maintenance`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = DocumentIndexRemover {
            entered.complete(Unit)
            release.await()
        }
        Files.createTempDirectory("infoscry-document-deletion-maintenance").let { gatedDir ->
            try {
                ApiTestServer(gatedDir, documentIndex = gated).use { server ->
                    server.createCollection("Nightfall", Credential.BEARER)
                    val id = CollectionId(server.collectionIdOf("Nightfall"))
                    val target = importDocument(server, id, "target.txt", "the target material")
                    val survivor = importDocument(server, id, "survivor.txt", "the survivor material")

                    val admitted = admitDocumentDeletion(server, id.value, listOf(target.value))
                    withTimeout(TIMEOUT_MILLIS) { entered.await() }

                    val refused = server.request(
                        HttpMethod.Post,
                        "/api/collections/${id.value}/documents/delete",
                        body = """{"documentIds":["${survivor.value}"],"confirmed":true}""",
                        credential = Credential.BEARER,
                    )

                    assertEquals(HttpStatusCode.Locked, refused.status, refused.bodyAsText())
                    assertContains(refused.bodyAsText(), "MAINTENANCE_IN_PROGRESS")

                    release.complete(Unit)
                    assertEquals("DONE", server.awaitDeletion(admitted.operationId).phase)
                }
            } finally {
                gatedDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a deletion admitted before its caller disconnects still finishes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = DocumentIndexRemover {
            entered.complete(Unit)
            release.await()
        }
        Files.createTempDirectory("infoscry-document-deletion-disconnect").let { gatedDir ->
            try {
                ApiTestServer(gatedDir, documentIndex = gated).use { server ->
                    server.createCollection("Nightfall", Credential.BEARER)
                    val id = CollectionId(server.collectionIdOf("Nightfall"))
                    val target = importDocument(server, id, "target.txt", "the target material")
                    // A client of its own: it goes away as soon as it has the answer, which is the
                    // disconnection this test is about.
                    val admitted = HttpClient(CIO).use { ephemeral ->
                        val response = ephemeral.request(server.url + "/api/collections/${id.value}/documents/delete") {
                            method = HttpMethod.Post
                            header(HttpHeaders.Authorization, "Bearer ${server.bearer}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"documentIds":["${target.value}"],"confirmed":true}""")
                        }
                        ApiJson.decodeFromString<DeleteDocumentsResponse>(response.bodyAsText())
                    }

                    withTimeout(TIMEOUT_MILLIS) { entered.await() }
                    assertNull(
                        server.context.documents.get(target),
                        "the admission is durable before the caller's answer, so the rows are already gone here",
                    )

                    release.complete(Unit)
                    assertEquals("DONE", server.awaitDeletion(admitted.operationId).phase)
                }
            } finally {
                gatedDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a saved source link reports unavailable content instead of another document`() = runBlocking {
        val id = CollectionId(createCollection("Nightfall"))
        val target = importDocument(id, "target.txt", "the target material")
        val unit = harness.context.content.listUnits(target, afterOrdinal = -1, limit = 1).single()

        assertEquals(
            HttpStatusCode.OK,
            harness.get("/api/collections/${id.value}/sources/${unit.id.value}").status,
        )

        val admitted = admitDocumentDeletion(id.value, listOf(target.value))
        harness.awaitDeletion(admitted.operationId)

        val after = harness.get("/api/collections/${id.value}/sources/${unit.id.value}")
        assertEquals(HttpStatusCode.NotFound, after.status, "a removed source must not resolve to another one")
        assertContains(after.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `a repeated id in one request is one target, not a second removal`() = runBlocking {
        val id = CollectionId(createCollection("Nightfall"))
        val target = importDocument(id, "target.txt", "the target material")
        val survivor = importDocument(id, "survivor.txt", "the survivor material")

        val admitted = admitDocumentDeletion(id.value, listOf(target.value, target.value, target.value))

        assertEquals(
            listOf(target.value),
            admitted.documentIds,
            "a duplicated id does not multiply the targets the operation records",
        )
        assertEquals(
            listOf(target.value),
            harness.context.deletions.get(admitted.operationId)?.targets?.map { it.documentId.value },
            "the durable operation keeps one target row, so the destructive phases run once",
        )

        val finished = harness.awaitDeletion(admitted.operationId)

        assertEquals(listOf(target.value), finished.documentIds)
        assertEquals("DONE", finished.phase)
        assertTrue(harness.context.deletions.listUnfinished().isEmpty(), "one operation, finished")
        assertNull(harness.context.documents.get(target))
        assertEquals(survivor, harness.context.documents.get(survivor)?.id)
    }

    private suspend fun createCollection(name: String): String {
        harness.createCollection(name, Credential.BEARER)
        return harness.collectionIdOf(name)
    }

    /** A managed document with one readable unit, so the source route has something to serve. */
    private fun importDocument(collectionId: CollectionId, name: String, content: String): DocumentId =
        importDocument(harness, collectionId, name, content)

    private fun importDocument(
        server: ApiTestServer,
        collectionId: CollectionId,
        name: String,
        content: String,
    ): DocumentId {
        val directory = Files.createDirectories(server.dataDir.resolve("sources"))
        val source = directory.resolve(name)
        Files.writeString(source, content)
        val imported = server.context.library.importFile(collectionId, source)
        runBlocking {
            server.context.content.commitExtractedUnit(
                documentId = imported.document.id,
                fingerprint = infoscry.extract.ExtractionFingerprint.of(
                    imported.document.sha256,
                    infoscry.extract.ExtractionSettings(ocrLanguages = "eng"),
                ),
                key = "unit-0",
                ordinal = 0,
                draft = infoscry.extract.ContentUnitDraft(
                    locator = infoscry.domain.SourceLocation.TextLines(1, 1),
                    extractedText = content,
                    searchText = content,
                    method = infoscry.domain.ExtractionMethod.DIRECT_TEXT,
                ),
                artifactRoot = server.context.paths.artifactsDir(collectionId, imported.document.id),
            )
        }
        return imported.document.id
    }

    private suspend fun admitDocumentDeletion(
        collectionId: String,
        documentIds: List<String>,
    ): DeleteDocumentsResponse = admitDocumentDeletion(harness, collectionId, documentIds)

    private suspend fun admitDocumentDeletion(
        server: ApiTestServer,
        collectionId: String,
        documentIds: List<String>,
    ): DeleteDocumentsResponse {
        val body = """{"documentIds":[${documentIds.joinToString(",") { "\"$it\"" }}],"confirmed":true}"""
        val response = server.request(
            HttpMethod.Post,
            "/api/collections/$collectionId/documents/delete",
            body = body,
            credential = Credential.BEARER,
        )
        check(response.status == HttpStatusCode.Accepted) {
            "admitting a document deletion should be accepted, was ${response.status}: ${response.bodyAsText()}"
        }
        return ApiJson.decodeFromString(response.bodyAsText())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
