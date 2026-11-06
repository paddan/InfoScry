package infoscry.server

import infoscry.account.CollectionRole
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.cli.FakeDocumentEmbedder
import infoscry.cli.ImportProcessHarness
import infoscry.domain.CollectionId
import infoscry.jobs.ImportJobHandler
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where imports read from: only the files uploaded to a collection from the client's computer. */
class ImportSourcesRoutesTest {

    private lateinit var directory: Path
    private lateinit var harness: ApiTestServer
    private lateinit var collectionId: String
    private val tokens = mutableMapOf<String, String>()

    @BeforeTest
    fun start() {
        directory = Files.createTempDirectory("infoscry-import-sources")
        harness = ApiTestServer(directory.resolve("data"))
        ImportJobHandler.attachTo(
            harness.context,
            ImportProcessHarness.harnessPipeline(harness.context),
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = { FakeDocumentEmbedder },
        )
        collectionId = runBlocking {
            harness.createCollection("Archive")
            harness.collectionIdOf("Archive")
        }
        for ((name, role) in listOf("editor" to CollectionRole.EDITOR, "reader" to CollectionRole.READER, "outsider" to null)) {
            val user = TestAccounts.ensureUser(harness.context, name)
            role?.let { harness.context.memberships.put(CollectionId(collectionId), user.id, it, harness.admin.id) }
            tokens[name] = harness.context.accounts.createToken(user.id, "tests", null).value
        }
    }

    @AfterTest
    fun stop() {
        harness.close()
        UploadLimit.bytes = uploadLimit()
        directory.toFile().deleteRecursively()
    }

    private suspend fun send(who: String, method: HttpMethod, path: String, body: String? = null): HttpResponse =
        harness.client.request(harness.url + path) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer ${tokens.getValue(who)}")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun upload(who: String, name: String, bytes: ByteArray): HttpResponse =
        harness.client.request(harness.url + "/api/collections/$collectionId/uploads") {
            method = HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer ${tokens.getValue(who)}")
            header(UPLOAD_FILENAME_HEADER, java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20"))
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }

    private suspend fun preview(who: String, body: String): HttpResponse = send(who, HttpMethod.Post, "/api/imports/preview", body)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `an import names only uploads, never a file on the server`() = runBlocking {
        val onServer = Files.writeString(directory.resolve("on-server.txt"), "On the server.\n")
        val withPath = preview("editor", """{"collection":"$collectionId","paths":["$onServer"],"uploads":[],"method":"none"}""")
        assertEquals(HttpStatusCode.BadRequest, withPath.status, withPath.bodyAsText())
        val unknownUpload = preview("editor", """{"collection":"$collectionId","uploads":["${UUID.randomUUID()}"],"method":"none"}""")
        assertEquals(HttpStatusCode.NotFound, unknownUpload.status, unknownUpload.bodyAsText())
        val notAnId = preview("editor", """{"collection":"$collectionId","uploads":["../../on-server.txt"],"method":"none"}""")
        assertEquals(HttpStatusCode.BadRequest, notAnId.status, notAnId.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, send("editor", HttpMethod.Get, "/api/import-roots").status, "there are no server folders to browse")
    }

    @Test
    fun `an uploaded file is previewed and imported, and the same bytes again answer with the document`() = runBlocking {
        val bytes = "Protokoll fran motet.\n".toByteArray()
        val uploaded = upload("editor", "protokoll 1.txt", bytes)
        assertEquals(HttpStatusCode.Created, uploaded.status, uploaded.bodyAsText())
        val uploadId = json(uploaded.bodyAsText())["uploadId"]!!.jsonPrimitive.content

        val shown = preview("editor", """{"collection":"$collectionId","uploads":["$uploadId"],"method":"none"}""")
        assertEquals(HttpStatusCode.OK, shown.status, shown.bodyAsText())
        val hash = json(shown.bodyAsText())["previewHash"]!!.jsonPrimitive.content
        val started = send(
            "editor", HttpMethod.Post, "/api/imports",
            """{"collection":"$collectionId","uploads":["$uploadId"],"method":"none","previewHash":"$hash","requestId":"${UUID.randomUUID()}"}""",
        )
        assertEquals(HttpStatusCode.Accepted, started.status, started.bodyAsText())
        val jobId = json(started.bodyAsText())["job"]!!.jsonObject["id"]!!.jsonPrimitive.content
        withTimeout(30_000) {
            while (json(send("editor", HttpMethod.Get, "/api/jobs/$jobId").bodyAsText())["job"]!!.jsonObject["state"]!!.jsonPrimitive.content != "COMPLETE") delay(50)
        }
        val document = harness.context.documents.listByCollection(CollectionId(collectionId), 10, 0).single()
        assertEquals("protokoll 1.txt", document.originalFilename)

        val again = upload("editor", "copy.txt", bytes)
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(document.id.value, json(again.bodyAsText())["existingDocumentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the same bytes uploaded twice before importing are one upload`() = runBlocking {
        val first = upload("editor", "brev.txt", "Brev.\n".toByteArray())
        val second = upload("editor", "kopia.txt", "Brev.\n".toByteArray())
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        val id = json(first.bodyAsText())["uploadId"]!!.jsonPrimitive.content
        assertEquals(id, json(second.bodyAsText())["uploadId"]!!.jsonPrimitive.content)
        assertEquals("brev.txt", json(second.bodyAsText())["name"]!!.jsonPrimitive.content)
        val area = harness.context.uploadAreas.uploadArea(CollectionId(collectionId))
        assertEquals(1L, Files.list(area).use { it.count() }, "nothing of the second upload is kept")
    }

    @Test
    fun `uploads are refused for readers, outsiders, unnamed and over-size files`() = runBlocking {
        assertEquals(HttpStatusCode.Forbidden, upload("reader", "a.txt", "a".toByteArray()).status)
        assertEquals(HttpStatusCode.NotFound, upload("outsider", "a.txt", "a".toByteArray()).status)
        assertEquals(HttpStatusCode.BadRequest, upload("editor", "..", "a".toByteArray()).status)
        UploadLimit.bytes = 10
        val large = upload("editor", "large.txt", ByteArray(64) { 'x'.code.toByte() })
        assertEquals(HttpStatusCode.PayloadTooLarge, large.status)
        val area = harness.context.uploadAreas.uploadArea(CollectionId(collectionId))
        assertEquals(0L, if (Files.isDirectory(area)) Files.list(area).use { it.count() } else 0L, "nothing of a refused upload is kept")
    }

    @Test
    fun `one collection's upload cannot be imported into another`() = runBlocking {
        val uploadId = json(upload("editor", "a.txt", "Private.\n".toByteArray()).bodyAsText())["uploadId"]!!.jsonPrimitive.content
        harness.createCollection("Other")
        val other = harness.collectionIdOf("Other")
        val refused = harness.request(HttpMethod.Post, "/api/imports/preview", """{"collection":"$other","uploads":["$uploadId"],"method":"none"}""", Credential.BEARER)
        assertEquals(HttpStatusCode.NotFound, refused.status, refused.bodyAsText())
    }

    @Test
    fun `old upload areas are swept`() {
        val area = Files.createDirectories(harness.context.uploadAreas.uploadArea(CollectionId(collectionId)).resolve(UUID.randomUUID().toString()))
        Files.writeString(area.resolve("old.txt"), "Old.\n")
        Files.setLastModifiedTime(area, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8))))

        assertEquals(1, harness.context.uploadAreas.sweepUploads())
        assertFalse(Files.exists(area))
    }
}
