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

/** Where imports read from: the import roots an administrator adds, the environment's, and a collection's uploads. */
class ImportSourcesRoutesTest {

    private lateinit var directory: Path
    private lateinit var root: Path
    private lateinit var outside: Path
    private lateinit var harness: ApiTestServer
    private lateinit var collectionId: String
    private lateinit var rootId: String
    private val tokens = mutableMapOf<String, String>()

    @BeforeTest
    fun start() {
        directory = Files.createTempDirectory("infoscry-import-sources")
        root = Files.createDirectories(directory.resolve("root")).toRealPath()
        outside = Files.createDirectories(directory.resolve("outside")).toRealPath()
        ImportRoots.configured = { emptyList() }
        harness = ApiTestServer(directory.resolve("data"))
        rootId = runBlocking {
            val added = harness.request(HttpMethod.Post, "/api/import-roots", """{"path":"$root"}""", Credential.BEARER)
            check(added.status == HttpStatusCode.Created) { added.bodyAsText() }
            Json.parseToJsonElement(added.bodyAsText()).jsonObject["root"]!!.jsonObject["id"]!!.jsonPrimitive.content
        }
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
        val top = send("editor", HttpMethod.Get, "/api/import-roots/browse?root=$rootId&path=").bodyAsText()
        assertContains(top, "\"name\":\"letters\",")
        assertContains(top, "\"name\":\"notes.txt\"")
        assertFalse(top.contains("escape"), top)
        assertContains(send("editor", HttpMethod.Get, "/api/import-roots/browse?root=$rootId&path=letters").bodyAsText(), "a.txt")
        assertEquals(HttpStatusCode.BadRequest, send("editor", HttpMethod.Get, "/api/import-roots/browse?root=$rootId&path=..").status)
        assertEquals(HttpStatusCode.Forbidden, send("reader", HttpMethod.Get, "/api/import-roots").status, "only people who may import browse")
    }

    @Test
    fun `an administrator adds and removes import folders, and the change counts at once`() = runBlocking {
        val inside = Files.writeString(root.resolve("inside.txt"), "Inside.\n")
        val previewInside = """{"collection":"$collectionId","paths":["$inside"],"method":"none"}"""
        val listed = json(send("editor", HttpMethod.Get, "/api/import-roots").bodyAsText())["roots"].toString()
        assertContains(listed, "\"source\":\"ADMIN\"")
        assertContains(listed, "\"available\":true")
        assertContains(listed, "\"addedBy\":\"${harness.admin.displayName}\"")

        // Adding the same folder again, also through a link to it, answers with the root that is there.
        val link = Files.createSymbolicLink(directory.resolve("link-to-root"), root)
        for (path in listOf(root, link)) {
            val again = harness.request(HttpMethod.Post, "/api/import-roots", """{"path":"$path"}""", Credential.BEARER)
            assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
            assertEquals(rootId, json(again.bodyAsText())["root"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        }

        for (path in listOf("relative/folder", directory.resolve("missing").toString(), inside.toString(), "/", directory.resolve("data").toString())) {
            val refused = harness.request(HttpMethod.Post, "/api/import-roots", """{"path":"$path"}""", Credential.BEARER)
            assertEquals(HttpStatusCode.BadRequest, refused.status, "$path: ${refused.bodyAsText()}")
        }
        assertEquals(HttpStatusCode.Forbidden, send("editor", HttpMethod.Post, "/api/import-roots", """{"path":"$outside"}""").status)
        assertEquals(HttpStatusCode.Forbidden, send("editor", HttpMethod.Delete, "/api/import-roots/$rootId").status)

        assertEquals(HttpStatusCode.OK, preview("editor", previewInside).status)
        assertEquals(HttpStatusCode.NoContent, harness.request(HttpMethod.Delete, "/api/import-roots/$rootId", null, Credential.BEARER).status)
        assertEquals(HttpStatusCode.NoContent, harness.request(HttpMethod.Delete, "/api/import-roots/$rootId", null, Credential.BEARER).status, "removing twice is fine")
        assertEquals(HttpStatusCode.Forbidden, preview("editor", previewInside).status, "a removed folder is no longer a source")
        assertContains(send("editor", HttpMethod.Get, "/api/import-roots").bodyAsText(), "\"roots\":[]")
    }

    @Test
    fun `a folder that has gone is shown as unavailable and nothing is read from it`() = runBlocking {
        val gone = Files.createDirectories(directory.resolve("gone")).toRealPath()
        val added = json(harness.request(HttpMethod.Post, "/api/import-roots", """{"path":"$gone"}""", Credential.BEARER).bodyAsText())
        val goneId = added["root"]!!.jsonObject["id"]!!.jsonPrimitive.content
        Files.delete(gone)

        val listed = Json.parseToJsonElement(send("editor", HttpMethod.Get, "/api/import-roots").bodyAsText()).jsonObject["roots"]!!
        val entry = listed.toString()
        assertContains(entry, "\"id\":\"$goneId\",\"name\":\"gone\"")
        assertContains(entry, "\"available\":false")
        assertEquals(HttpStatusCode.NotFound, send("editor", HttpMethod.Get, "/api/import-roots/browse?root=$goneId&path=").status)
        assertEquals(HttpStatusCode.NoContent, harness.request(HttpMethod.Delete, "/api/import-roots/$goneId", null, Credential.BEARER).status)
    }

    @Test
    fun `a folder from INFOSCRY_IMPORT_ROOTS is listed but is removed where it was set`() = runBlocking {
        val fromEnvironment = Files.createDirectories(directory.resolve("environment")).toRealPath()
        ImportRoots.configured = { listOf(fromEnvironment) }
        ApiTestServer(directory.resolve("data-environment")).use { server ->
            val listed = server.request(HttpMethod.Get, "/api/import-roots", null, Credential.BEARER).bodyAsText()
            assertContains(listed, "\"id\":\"environment-0\"")
            assertContains(listed, "\"source\":\"ENVIRONMENT\"")
            val refused = server.request(HttpMethod.Delete, "/api/import-roots/environment-0", null, Credential.BEARER)
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertContains(refused.bodyAsText(), "IMPORT_ROOT_FROM_ENVIRONMENT")
            val again = server.request(HttpMethod.Post, "/api/import-roots", """{"path":"$fromEnvironment"}""", Credential.BEARER)
            assertEquals(HttpStatusCode.OK, again.status, "a folder already listed is not stored twice")
        }
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
