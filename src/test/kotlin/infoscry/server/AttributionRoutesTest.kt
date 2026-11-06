package infoscry.server

import infoscry.account.CollectionRole
import infoscry.account.User
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.cli.FakeDocumentEmbedder
import infoscry.cli.ImportProcessHarness
import infoscry.domain.CollectionId
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
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
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Who did what, and two people working on the same thing at once. */
class AttributionRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer
    private lateinit var collectionId: String
    private val tokens = mutableMapOf<String, String>()
    private val users = mutableMapOf<String, User>()

    @BeforeTest
    fun start() {
        dataDir = Files.createTempDirectory("infoscry-attribution")
        ImportProcessHarness.gate = null
        harness = ApiTestServer(dataDir)
        ImportJobHandler.attachTo(
            harness.context,
            ImportProcessHarness.harnessPipeline(harness.context),
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = { FakeDocumentEmbedder },
        )
        for ((name, display) in listOf("anna" to "Anna Berg", "erik" to "Erik Lund")) {
            val user = TestAccounts.ensureUser(harness.context, name)
            harness.context.accounts.updateUser(user.id, display, null, null, actor = harness.admin.id)
            users[name] = harness.context.accounts.user(user.id)
            tokens[name] = harness.context.accounts.createToken(user.id, "tests", null).value
        }
        val created = runBlocking { send("anna", HttpMethod.Post, "/api/collections", """{"name":"Archive"}""") }
        collectionId = Json.parseToJsonElement(runBlocking { created.bodyAsText() }).jsonObject["collection"]!!.jsonObject["id"]!!.jsonPrimitive.content
        harness.context.memberships.put(CollectionId(collectionId), users.getValue("erik").id, CollectionRole.OWNER, users.getValue("anna").id)
    }

    @AfterTest
    fun stop() {
        runCatching { ImportProcessHarness.gate?.let { Files.writeString(it, "go") } }
        ImportProcessHarness.gate = null
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private suspend fun send(who: String, method: HttpMethod, path: String, body: String? = null, ifMatch: Long? = null): HttpResponse =
        harness.client.request(harness.url + path) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer ${tokens.getValue(who)}")
            ifMatch?.let { header(HttpHeaders.IfMatch, "\"$it\"") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private suspend fun awaitJob(jobId: String, who: String = "anna"): String = withTimeout(30_000) {
        while (true) {
            val body = send(who, HttpMethod.Get, "/api/jobs/$jobId").bodyAsText()
            val state = json(body)["job"]!!.jsonObject["state"]!!.jsonPrimitive.content
            if (state !in setOf("QUEUED", "RUNNING")) return@withTimeout body
            delay(50)
        }
        error("unreachable")
    }

    private suspend fun importAs(who: String, file: Path): HttpResponse {
        val preview = send(who, HttpMethod.Post, "/api/imports/preview", """{"collection":"$collectionId","paths":["$file"],"method":"none"}""")
        check(preview.status == HttpStatusCode.OK) { preview.bodyAsText() }
        val hash = json(preview.bodyAsText())["previewHash"]!!.jsonPrimitive.content
        return send(
            who, HttpMethod.Post, "/api/imports",
            """{"collection":"$collectionId","paths":["$file"],"method":"none","previewHash":"$hash","requestId":"${UUID.randomUUID()}"}""",
        )
    }

    /** Server paths are an administrator's until import roots exist, so the importers here are administrators. */
    private fun makeAdministrators() {
        for (name in listOf("anna", "erik")) {
            harness.context.accounts.updateUser(users.getValue(name).id, null, infoscry.account.UserRole.ADMIN, null, actor = harness.admin.id)
        }
    }

    @Test
    fun `a collection says who created it and who last changed it, and counts its versions`() = runBlocking {
        val before = json(send("anna", HttpMethod.Get, "/api/collections").bodyAsText())["collections"]!!.jsonArray.single().jsonObject
        assertEquals("Anna Berg", before["updatedBy"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)

        val renamed = send("erik", HttpMethod.Patch, "/api/collections/$collectionId", """{"name":"Archive 2"}""", ifMatch = before["version"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        val after = json(renamed.bodyAsText())["collection"]!!.jsonObject
        assertEquals("Erik Lund", after["updatedBy"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        assertEquals(before["version"]!!.jsonPrimitive.long + 1, after["version"]!!.jsonPrimitive.long)

        val stale = send("anna", HttpMethod.Patch, "/api/collections/$collectionId", """{"name":"Archive 3"}""", ifMatch = before["version"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertContains(stale.bodyAsText(), "EDIT_CONFLICT")
        assertContains(stale.bodyAsText(), "Erik Lund changed this in the meantime")
    }

    @Test
    fun `two people editing one glossary group, the stale edit is refused and a repeat of the current content is not`() = runBlocking {
        val path = "/api/collections/$collectionId/glossary/g1"
        val created = send("anna", HttpMethod.Put, path, """{"terms":[{"text":"Stockholm"},{"text":"Sthlm"}]}""")
        val version = json(created.bodyAsText())["version"]!!.jsonPrimitive.long

        val erik = send("erik", HttpMethod.Put, path, """{"terms":[{"text":"Stockholm"},{"text":"Sthlm"},{"text":"Holmia"}]}""", ifMatch = version)
        assertEquals(HttpStatusCode.OK, erik.status, erik.bodyAsText())
        assertEquals("Erik Lund", json(erik.bodyAsText())["updatedBy"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)

        val anna = send("anna", HttpMethod.Put, path, """{"terms":[{"text":"Stockholm"},{"text":"STHLM"}]}""", ifMatch = version)
        assertEquals(HttpStatusCode.Conflict, anna.status)
        assertContains(anna.bodyAsText(), "Erik Lund")
        val repeat = send("anna", HttpMethod.Put, path, """{"terms":[{"text":"Stockholm"},{"text":"Sthlm"},{"text":"Holmia"}]}""", ifMatch = version)
        assertEquals(HttpStatusCode.OK, repeat.status, "the same content as now is no edit, whatever version it names")
        val withoutVersion = send("anna", HttpMethod.Put, path, """{"terms":[{"text":"Stockholm"},{"text":"Sthlm"}]}""")
        assertEquals(HttpStatusCode.OK, withoutVersion.status, "the CLI sends no version and edits what is current")
    }

    @Test
    fun `a stale edit of a correction or a prompt is refused, naming who changed it`() = runBlocking {
        val rule = "/api/collections/$collectionId/corrections/r1"
        val v1 = json(send("anna", HttpMethod.Put, rule, """{"find":"spinners","replace":"Spinnars"}""").bodyAsText())["version"]!!.jsonPrimitive.long
        assertEquals(HttpStatusCode.OK, send("erik", HttpMethod.Put, rule, """{"find":"spinners","replace":"Spinnare"}""", ifMatch = v1).status)
        val stale = send("anna", HttpMethod.Put, rule, """{"find":"spinners","replace":"Spinner"}""", ifMatch = v1)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertContains(stale.bodyAsText(), "Erik Lund")

        val prompts = "/api/collections/$collectionId/prompts"
        val created = send("anna", HttpMethod.Post, prompts, """{"role":"ASK","name":"Short","body":"Answer briefly."}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val prompt = json(created.bodyAsText())
        val id = prompt["id"]!!.jsonPrimitive.content
        val version = prompt["version"]!!.jsonPrimitive.long
        assertEquals(HttpStatusCode.OK, send("erik", HttpMethod.Put, "$prompts/$id", """{"name":"Short","body":"Answer in one line."}""", ifMatch = version).status)
        val stalePrompt = send("anna", HttpMethod.Put, "$prompts/$id", """{"name":"Short","body":"Answer in two lines."}""", ifMatch = version)
        assertEquals(HttpStatusCode.Conflict, stalePrompt.status, stalePrompt.bodyAsText())
        val listed = send("anna", HttpMethod.Get, prompts).bodyAsText()
        assertContains(listed, "Erik Lund")
    }

    @Test
    fun `a job says who started and who cancelled it`() = runBlocking {
        val accepted = send("anna", HttpMethod.Post, "/api/reindex", """{"collection":"$collectionId"}""")
        assertEquals(HttpStatusCode.Accepted, accepted.status, accepted.bodyAsText())
        val queued = harness.context.jobs.enqueue(JobType.REINDEX, collectionId = CollectionId(collectionId))
        // Enqueued outside a request: nobody in person.
        assertEquals(null, harness.context.jobs.get(queued.id)?.startedBy)

        val jobId = json(accepted.bodyAsText())["jobId"]!!.jsonPrimitive.content
        assertEquals(users.getValue("anna").id, harness.context.jobs.get(JobId(jobId))?.startedBy)
        send("erik", HttpMethod.Post, "/api/jobs/${queued.id.value}/cancel")
        val job = json(send("anna", HttpMethod.Get, "/api/jobs/${queued.id.value}").bodyAsText())["job"]!!.jsonObject
        assertEquals("Erik Lund", job["cancelledBy"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        assertTrue(json(send("anna", HttpMethod.Get, "/api/jobs/$jobId").bodyAsText())["job"]!!.jsonObject["startedBy"].toString().contains("Anna Berg"))
    }

    @Test
    fun `an import's documents and readings are attributed to whoever started it, and the first page decision decides`() = runBlocking {
        makeAdministrators()
        val file = dataDir.resolve("minutes.txt").also { Files.writeString(it, "Protokoll fran motet.\n") }
        val accepted = importAs("erik", file)
        assertEquals(HttpStatusCode.Accepted, accepted.status, accepted.bodyAsText())
        val jobId = json(accepted.bodyAsText())["job"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertContains(awaitJob(jobId), "COMPLETE")
        val documentId = harness.context.documents.listByCollection(CollectionId(collectionId), 10, 0).single().id.value

        val detail = send("anna", HttpMethod.Get, "/api/collections/$collectionId/documents/$documentId").bodyAsText()
        assertEquals("Erik Lund", json(detail)["importedBy"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        val ocr = "/api/collections/$collectionId/documents/$documentId/ocr"
        val history = json(send("anna", HttpMethod.Get, "$ocr/revisions").bodyAsText())
        val revision = history["revisions"]!!.jsonArray.first().jsonObject
        assertEquals("Erik Lund", revision["createdBy"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        val revisionId = revision["revisionId"]!!.jsonPrimitive.content

        val approve = send("anna", HttpMethod.Post, "$ocr/decisions", """{"revisionId":"$revisionId","decision":"APPROVE"}""")
        assertEquals(HttpStatusCode.OK, approve.status, approve.bodyAsText())
        assertEquals(true, json(approve.bodyAsText())["decidedHere"]!!.jsonPrimitive.content.toBoolean())
        val reject = send(
            "erik", HttpMethod.Post, "$ocr/decisions",
            """{"revisionId":"$revisionId","decision":"REJECT","requestId":"r-1","restoreRevisionId":"$revisionId"}""",
        )
        assertEquals(HttpStatusCode.OK, reject.status, reject.bodyAsText())
        val answered = json(reject.bodyAsText())
        assertEquals(false, answered["decidedHere"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("APPROVE", answered["decision"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
        assertEquals("Anna Berg", answered["decision"]!!.jsonObject["decidedBy"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        assertFalse(answered.containsKey("restore"), "a second decision starts no restore")
        assertContains(send("erik", HttpMethod.Get, "$ocr/revisions").bodyAsText(), "\"decision\":\"APPROVE\"")
    }

    @Test
    fun `two people importing the same files get the same job`() = runBlocking {
        makeAdministrators()
        val gate = dataDir.resolve("gate")
        ImportProcessHarness.gate = gate
        val file = dataDir.resolve("report.txt").also { Files.writeString(it, "En rapport.\n") }

        val first = importAs("anna", file)
        val second = importAs("erik", file)

        assertEquals(HttpStatusCode.Accepted, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.Accepted, second.status, second.bodyAsText())
        val firstId = json(first.bodyAsText())["job"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(firstId, json(second.bodyAsText())["job"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, harness.context.jobs.list(10).count { it.type == JobType.IMPORT })
        Files.writeString(gate, "go")
        assertContains(awaitJob(firstId), JobState.COMPLETE.name)
    }
}
