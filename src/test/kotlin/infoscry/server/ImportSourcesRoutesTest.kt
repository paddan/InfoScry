package infoscry.server

import infoscry.account.CollectionRole
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.cli.FakeDocumentEmbedder
import infoscry.cli.ImportProcessHarness
import infoscry.domain.CollectionId
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.ImportRoots
import infoscry.storage.TestDatabases
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

/** Where imports read from: the configured import roots and the files uploaded to a collection. */
class ImportSourcesRoutesTest {

    private lateinit var directory: Path
    private lateinit var root: Path
    private lateinit var outside: Path
    private lateinit var harness: ApiTestServer
    private lateinit var collectionId: String
    private val tokens = mutableMapOf<String, String>()

    @BeforeTest
    fun start() {
        directory = Files.createTempDirectory("infoscry-import-sources")
        root = Files.createDirectories(directory.resolve("root")).toRealPath()
        outside = Files.createDirectories(directory.resolve("outside")).toRealPath()
        ImportRoots.configured = { listOf(root) }
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
        ImportRoots.configured = { listOf(TestDatabases.TEMP_ROOT) }
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
    fun `an editor imports from inside a root and nowhere else, also not through a link`() = runBlocking {
        val inside = Files.writeString(root.resolve("inside.txt"), "Inside.\n")
        val elsewhere = Files.writeString(outside.resolve("secret.txt"), "Secret.\n")
        val link = Files.createSymbolicLink(root.resolve("link.txt"), elsewhere)
        val linkedFolder = Files.createSymbolicLink(root.resolve("linked"), outside)

        assertEquals(HttpStatusCode.OK, preview("editor", """{"collection":"$collectionId","paths":["$inside"],"method":"none"}""").status)
        for (path in listOf(elsewhere, link, linkedFolder, root.resolve("../outside/secret.txt"))) {
            val refused = preview("editor", """{"collection":"$collectionId","paths":["$path"],"method":"none"}""")
            assertEquals(HttpStatusCode.Forbidden, refused.status, "$path: ${refused.bodyAsText()}")
            assertContains(refused.bodyAsText(), "IMPORT_PATH_NOT_ALLOWED")
        }
        val refusedForAdmin = harness.request(HttpMethod.Post, "/api/imports/preview", """{"collection":"$collectionId","paths":["$elsewhere"],"method":"none"}""", Credential.BEARER)
        assertEquals(HttpStatusCode.Forbidden, refusedForAdmin.status, "an administrator is held to the roots too")
        assertEquals(HttpStatusCode.Forbidden, preview("reader", """{"collection":"$collectionId","paths":["$inside"],"method":"none"}""").status)
    }

    @Test
    fun `browsing lists a root's folders and files and leaves links out of it out`() = runBlocking {
        Files.createDirectories(root.resolve("letters"))
        Files.writeString(root.resolve("letters/a.txt"), "A.\n")
        Files.writeString(root.resolve("notes.txt"), "Notes.\n")
        Files.createSymbolicLink(root.resolve("escape"), outside)

        val roots = send("editor", HttpMethod.Get, "/api/import-roots").bodyAsText()
        assertContains(roots, "\"name\":\"root\"")
        val top = send("editor", HttpMethod.Get, "/api/import-roots/browse?root=0&path=").bodyAsText()
        assertContains(top, "\"name\":\"letters\",")
        assertContains(top, "\"name\":\"notes.txt\"")
        assertFalse(top.contains("escape"), top)
        assertContains(send("editor", HttpMethod.Get, "/api/import-roots/browse?root=0&path=letters").bodyAsText(), "a.txt")
        assertEquals(HttpStatusCode.BadRequest, send("editor", HttpMethod.Get, "/api/import-roots/browse?root=0&path=..").status)
        assertEquals(HttpStatusCode.Forbidden, send("reader", HttpMethod.Get, "/api/import-roots").status, "only people who may import browse")
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
    fun `uploads are refused for readers, outsiders, unnamed and over-size files`() = runBlocking {
        assertEquals(HttpStatusCode.Forbidden, upload("reader", "a.txt", "a".toByteArray()).status)
        assertEquals(HttpStatusCode.NotFound, upload("outsider", "a.txt", "a".toByteArray()).status)
        assertEquals(HttpStatusCode.BadRequest, upload("editor", "..", "a".toByteArray()).status)
        UploadLimit.bytes = 10
        val large = upload("editor", "large.txt", ByteArray(64) { 'x'.code.toByte() })
        assertEquals(HttpStatusCode.PayloadTooLarge, large.status)
        val area = harness.context.importRoots.uploadArea(CollectionId(collectionId))
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
        val area = Files.createDirectories(harness.context.importRoots.uploadArea(CollectionId(collectionId)).resolve(UUID.randomUUID().toString()))
        Files.writeString(area.resolve("old.txt"), "Old.\n")
        Files.setLastModifiedTime(area, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8))))

        assertEquals(1, harness.context.importRoots.sweepUploads())
        assertFalse(Files.exists(area))
    }
}
