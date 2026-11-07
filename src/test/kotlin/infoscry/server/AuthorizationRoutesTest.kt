package infoscry.server

import infoscry.account.CollectionRole
import infoscry.account.User
import infoscry.account.UserRole
import infoscry.ask.CitationValidation
import infoscry.collection.LastOwnerException
import infoscry.domain.CollectionId
import infoscry.domain.JobType
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.llm.TokenUsage
import infoscry.llm.defaultTestPrompt
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Who may do what, route by route, against the table in the spec: a reader, an editor, an owner, an
 * outsider (signed in, not a member) and an administrator (the harness's own user).
 */
class AuthorizationRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer
    private lateinit var collectionId: String
    private val tokens = mutableMapOf<String, String>()
    private val users = mutableMapOf<String, User>()

    @BeforeTest
    fun start() {
        dataDir = Files.createTempDirectory("infoscry-authorization")
        harness = ApiTestServer(dataDir)
        val created = runBlocking { harness.createCollection("Archive") }
        check(created.status == HttpStatusCode.Created)
        collectionId = runBlocking { harness.collectionIdOf("Archive") }
        for (name in listOf("reader", "editor", "owner", "outsider")) {
            val user = TestAccounts.ensureUser(harness.context, name)
            users[name] = user
            tokens[name] = harness.context.accounts.createToken(user.id, "tests", null).value
        }
        val id = CollectionId(collectionId)
        harness.context.memberships.put(id, users.getValue("reader").id, CollectionRole.READER, harness.admin.id)
        harness.context.memberships.put(id, users.getValue("editor").id, CollectionRole.EDITOR, harness.admin.id)
        harness.context.memberships.put(id, users.getValue("owner").id, CollectionRole.OWNER, harness.admin.id)
    }

    @AfterTest
    fun stop() {
        harness.close()
        dataDir.toFile().deleteRecursively()
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

    private suspend fun status(who: String, method: HttpMethod, path: String, body: String? = null): HttpStatusCode =
        send(who, method, path, body).status

    @Test
    fun `a reader sees the image rescan census, an editor starts it and an outsider gets 404`() = runBlocking {
        val path = "/api/collections/$collectionId/image-rescan"
        val body = """{"imageExtraction":"embedded"}"""

        assertEquals(HttpStatusCode.OK, status("reader", HttpMethod.Get, path))
        assertEquals(HttpStatusCode.Forbidden, status("reader", HttpMethod.Post, path, body))
        assertEquals(HttpStatusCode.NotFound, status("outsider", HttpMethod.Get, path))
        assertEquals(HttpStatusCode.NotFound, status("outsider", HttpMethod.Post, path, body))
        val first = send("editor", HttpMethod.Post, path, body)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val again = send("owner", HttpMethod.Post, path, """{"imageExtraction":"all"}""")
        assertEquals(first.bodyAsText().substringAfter("\"id\":\"").substringBefore("\""),
            again.bodyAsText().substringAfter("\"id\":\"").substringBefore("\""))
        assertEquals(HttpStatusCode.BadRequest, status("editor", HttpMethod.Post, path, """{"imageExtraction":"off"}"""))
        assertEquals(HttpStatusCode.BadRequest, status("editor", HttpMethod.Post, path, """{"imageExtraction":"bogus"}"""))
        assertEquals(HttpStatusCode.OK, status("reader", HttpMethod.Get, "$path/run"))
        assertEquals(HttpStatusCode.Forbidden, status("reader", HttpMethod.Post, "$path/cancel"))
        assertEquals(HttpStatusCode.NotFound, status("outsider", HttpMethod.Get, "$path/run"))
        assertEquals(HttpStatusCode.OK, status("editor", HttpMethod.Post, "$path/cancel"))
    }

    @Test
    fun `the creator of a collection becomes its owner`() = runBlocking {
        val created = send("outsider", HttpMethod.Post, "/api/collections", """{"name":"Mine"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = ApiJson.decodeFromString<CollectionResponse>(created.bodyAsText()).collection.id

        assertEquals(CollectionRole.OWNER, harness.context.memberships.roleOf(id, users.getValue("outsider").id))
        assertEquals(HttpStatusCode.OK, status("outsider", HttpMethod.Patch, "/api/collections/${id.value}", """{"name":"Mine, renamed"}"""))
    }

    @Test
    fun `a collection list shows only the caller's collections`() = runBlocking {
        val readerList = send("reader", HttpMethod.Get, "/api/collections").bodyAsText()
        val outsiderList = send("outsider", HttpMethod.Get, "/api/collections").bodyAsText()

        assertContains(readerList, "Archive")
        assertFalse(outsiderList.contains("Archive"), outsiderList)
    }

    @Test
    fun `an outsider is told a collection does not exist, exactly as for one that does not`() = runBlocking {
        val hidden = send("outsider", HttpMethod.Get, "/api/collections/$collectionId/documents")
        val missing = send("outsider", HttpMethod.Get, "/api/collections/no-such-collection/documents")

        assertEquals(HttpStatusCode.NotFound, hidden.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(
            hidden.bodyAsText().replace(collectionId, "X"),
            missing.bodyAsText().replace("no-such-collection", "X"),
            "the answer may not reveal that the collection exists",
        )
        assertEquals(HttpStatusCode.NotFound, status("outsider", HttpMethod.Get, "/api/collections/Archive/glossary"), "by name too")
    }

    @Test
    fun `reading needs a reader, editing an editor, settings and members an owner`() = runBlocking {
        val glossary = "/api/collections/$collectionId/glossary/g1"
        val terms = """{"terms":[{"text":"Stockholm"},{"text":"Sthlm"}]}"""
        assertEquals(HttpStatusCode.OK, status("reader", HttpMethod.Get, "/api/collections/$collectionId/documents"))

        val refused = send("reader", HttpMethod.Put, glossary, terms)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertContains(refused.bodyAsText(), "COLLECTION_ROLE_REQUIRED")
        assertEquals(HttpStatusCode.OK, status("editor", HttpMethod.Put, glossary, terms))

        val rename = """{"name":"Archive 2"}"""
        assertEquals(HttpStatusCode.Forbidden, status("editor", HttpMethod.Patch, "/api/collections/$collectionId", rename))
        assertEquals(HttpStatusCode.Forbidden, status("editor", HttpMethod.Put, "/api/collections/$collectionId/ignore-patterns", """{"patterns":[]}"""))
        assertEquals(HttpStatusCode.OK, status("owner", HttpMethod.Patch, "/api/collections/$collectionId", rename))

        val outsiderId = users.getValue("outsider").id.value
        assertEquals(HttpStatusCode.Forbidden, status("editor", HttpMethod.Put, "/api/collections/$collectionId/members/$outsiderId", """{"role":"READER"}"""))
        assertEquals(HttpStatusCode.OK, status("owner", HttpMethod.Put, "/api/collections/$collectionId/members/$outsiderId", """{"role":"READER"}"""))
        assertEquals(HttpStatusCode.OK, status("outsider", HttpMethod.Get, "/api/collections/$collectionId/documents"))
    }

    @Test
    fun `administrators reach every collection and only they manage profiles`() = runBlocking {
        val profile = """{"name":"p","provider":"OPENAI_COMPATIBLE","model":"m","contextWindow":1000,"maxOutputTokens":10,""" +
            """"inputPricePerMillion":0,"outputPricePerMillion":0,"cacheReadPricePerMillion":0,"enabled":true}"""
        val refused = send("owner", HttpMethod.Post, "/api/llm/profiles", profile)

        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertContains(refused.bodyAsText(), "ADMINISTRATOR_REQUIRED")
        assertEquals(HttpStatusCode.OK, status("reader", HttpMethod.Get, "/api/llm/profiles"), "everyone may choose a profile")
        assertEquals(HttpStatusCode.Created, harness.request(HttpMethod.Post, "/api/llm/profiles", profile, Credential.BEARER).status)
        // The administrator is not a member of a collection made by someone else, and still reaches it.
        val created = send("outsider", HttpMethod.Post, "/api/collections", """{"name":"Private"}""")
        val id = ApiJson.decodeFromString<CollectionResponse>(created.bodyAsText()).collection.id.value
        assertEquals(HttpStatusCode.OK, harness.request(HttpMethod.Get, "/api/collections/$id/documents", credential = Credential.BEARER).status)
    }

    @Test
    fun `routes that name the collection in the body check it too`() = runBlocking {
        val ask = """{"collection":"$collectionId","question":"q","profile":"none"}"""
        assertEquals(HttpStatusCode.NotFound, status("outsider", HttpMethod.Post, "/api/ask", ask))
        assertEquals(HttpStatusCode.NotFound, status("outsider", HttpMethod.Get, "/api/search?collection=$collectionId&q=x&mode=keyword"))
        assertEquals(HttpStatusCode.OK, status("reader", HttpMethod.Get, "/api/search?collection=$collectionId&q=x&mode=keyword"))

        assertEquals(HttpStatusCode.Forbidden, status("owner", HttpMethod.Post, "/api/reindex", "{}"), "every collection is an administrator's")
        assertEquals(HttpStatusCode.Forbidden, status("editor", HttpMethod.Post, "/api/reindex", """{"collection":"$collectionId"}"""))
        assertEquals(HttpStatusCode.Accepted, status("owner", HttpMethod.Post, "/api/reindex", """{"collection":"$collectionId"}"""))

        // An editor imports what was uploaded; the server's own files cannot be named at all.
        val preview = """{"collection":"$collectionId","uploads":["00000000-0000-0000-0000-000000000000"],"method":"none"}"""
        assertEquals(HttpStatusCode.NotFound, status("editor", HttpMethod.Post, "/api/imports/preview", preview))
        val serverFile = """{"collection":"$collectionId","paths":["/etc/hosts"],"uploads":[],"method":"none"}"""
        assertEquals(HttpStatusCode.BadRequest, status("editor", HttpMethod.Post, "/api/imports/preview", serverFile), "a server path is not part of the request")
        assertEquals(HttpStatusCode.Forbidden, status("reader", HttpMethod.Post, "/api/imports/preview", preview))
    }

    @Test
    fun `jobs follow their collection`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.REINDEX, collectionId = CollectionId(collectionId))
        val global = harness.context.jobs.enqueue(JobType.REINDEX)

        assertEquals(HttpStatusCode.NotFound, status("outsider", HttpMethod.Get, "/api/jobs/${job.id.value}"))
        assertEquals(HttpStatusCode.OK, status("reader", HttpMethod.Get, "/api/jobs/${job.id.value}"))
        assertEquals(HttpStatusCode.NotFound, status("owner", HttpMethod.Get, "/api/jobs/${global.id.value}"), "a job of no collection is an administrator's")
        assertFalse(send("outsider", HttpMethod.Get, "/api/jobs").bodyAsText().contains(job.id.value))
        assertContains(send("reader", HttpMethod.Get, "/api/jobs").bodyAsText(), job.id.value)
        assertEquals(HttpStatusCode.Forbidden, status("reader", HttpMethod.Post, "/api/jobs/${job.id.value}/cancel"))
        assertEquals(HttpStatusCode.OK, status("editor", HttpMethod.Post, "/api/jobs/${job.id.value}/cancel"))
    }

    @Test
    fun `an Ask conversation is private to the member who asked`() = runBlocking {
        val profile = LlmProfile(
            id = UUID.randomUUID().toString(), name = "p", provider = LlmProvider.OPENAI_COMPATIBLE, model = "m",
            contextWindow = 10_000, maxOutputTokens = 64, inputPricePerMillion = 0.0, outputPricePerMillion = 0.0,
            cacheReadPricePerMillion = 0.0, enabled = true, endpoint = "https://provider.invalid/v1",
        ).also { harness.context.llm.create(it) }
        val askId = harness.context.conversations.persistAsk(
            collectionId = CollectionId(collectionId),
            ownerId = users.getValue("reader").id,
            profile = profile,
            prompt = defaultTestPrompt(),
            question = "Who signed it?",
            answer = "Mira.",
            evidence = emptyList(),
            initialUsage = TokenUsage(1, 1, 0),
            initialCitations = CitationValidation(valid = emptyList(), invalid = emptyList()),
        )

        assertContains(send("reader", HttpMethod.Get, "/api/collections/$collectionId/asks").bodyAsText(), askId)
        assertFalse(send("editor", HttpMethod.Get, "/api/collections/$collectionId/asks").bodyAsText().contains(askId))
        assertFalse(harness.get("/api/collections/$collectionId/asks").bodyAsText().contains(askId), "not even an administrator's list")
        assertEquals(HttpStatusCode.NotFound, status("owner", HttpMethod.Delete, "/api/collections/$collectionId/conversations/$askId"))
        assertEquals(HttpStatusCode.NoContent, status("reader", HttpMethod.Delete, "/api/collections/$collectionId/conversations/$askId"))
    }

    @Test
    fun `an investigation is private to the member who started it`() = runBlocking {
        val profile = LlmProfile(
            id = UUID.randomUUID().toString(), name = "q", provider = LlmProvider.OPENAI_COMPATIBLE, model = "m",
            contextWindow = 10_000, maxOutputTokens = 64, inputPricePerMillion = 0.0, outputPricePerMillion = 0.0,
            cacheReadPricePerMillion = 0.0, enabled = true, endpoint = "https://provider.invalid/v1",
        ).also { harness.context.llm.create(it) }
        val id = harness.context.investigationLedger.persistInvestigateConversation(
            collectionId = CollectionId(collectionId),
            ownerId = users.getValue("editor").id,
            profile = profile,
            prompt = defaultTestPrompt(1),
            retrievalSnapshot = "{}",
        )

        assertEquals(HttpStatusCode.OK, status("editor", HttpMethod.Get, "/api/collections/$collectionId/investigations/$id"))
        assertEquals(HttpStatusCode.NotFound, status("reader", HttpMethod.Get, "/api/collections/$collectionId/investigations/$id"))
        assertFalse(send("reader", HttpMethod.Get, "/api/collections/$collectionId/investigations").bodyAsText().contains(id))
        val continued = send(
            "reader", HttpMethod.Post, "/api/investigations/$id/continue",
            """{"collection":"$collectionId","question":"and?","profile":"q"}""",
        )
        assertEquals(HttpStatusCode.NotFound, continued.status, continued.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, status("reader", HttpMethod.Post, "/api/investigations/$id/cancel"))
    }

    @Test
    fun `membership changes are idempotent and a collection keeps an owner`() = runBlocking {
        val members = "/api/collections/$collectionId/members"
        val outsider = users.getValue("outsider").id.value
        val owner = users.getValue("owner").id.value

        repeat(2) { assertEquals(HttpStatusCode.OK, status("owner", HttpMethod.Put, "$members/$outsider", """{"role":"EDITOR"}""")) }
        repeat(2) { assertEquals(HttpStatusCode.NoContent, status("owner", HttpMethod.Delete, "$members/$outsider")) }
        // The administrator created the collection, so it has two owners; the last one cannot leave.
        assertEquals(HttpStatusCode.NoContent, harness.request(HttpMethod.Delete, "$members/${harness.admin.id.value}", credential = Credential.BEARER).status)
        val last = send("owner", HttpMethod.Put, "$members/$owner", """{"role":"READER"}""")
        assertEquals(HttpStatusCode.Conflict, last.status)
        assertContains(last.bodyAsText(), "LAST_OWNER")
        assertEquals(HttpStatusCode.Conflict, status("owner", HttpMethod.Delete, "$members/$owner"))

        val listed = ApiJson.decodeFromString<MembersResponse>(send("reader", HttpMethod.Get, members).bodyAsText())
        assertEquals(setOf("reader", "editor", "owner"), listed.members.map { it.username }.toSet())
        assertEquals(CollectionRole.READER, listed.callerRole)
    }

    @Test
    fun `two owners demoting each other at once leave one owner`() {
        val id = CollectionId(collectionId)
        harness.context.memberships.remove(id, harness.admin.id)
        harness.context.memberships.put(id, users.getValue("editor").id, CollectionRole.OWNER, harness.admin.id)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("owner", "editor").map { name ->
                pool.submit<Result<Unit>> {
                    barrier.await()
                    runCatching { harness.context.memberships.put(id, users.getValue(name).id, CollectionRole.READER, harness.admin.id); Unit }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is LastOwnerException)
            assertEquals(1, harness.context.memberships.members(id).count { it.role == CollectionRole.OWNER })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the user who deleted a collection can follow the deletion after its memberships are gone`() = runBlocking {
        val admitted = send("owner", HttpMethod.Delete, "/api/collections/$collectionId", """{"confirmName":"Archive"}""")
        assertEquals(HttpStatusCode.Accepted, admitted.status, admitted.bodyAsText())
        val operation = ApiJson.decodeFromString<DeleteCollectionResponse>(admitted.bodyAsText())
        harness.awaitDeletion(operation.operationId)

        assertEquals(HttpStatusCode.OK, status("owner", HttpMethod.Get, "/api/deletions/${operation.operationId}"))
        assertEquals(HttpStatusCode.NotFound, status("reader", HttpMethod.Get, "/api/deletions/${operation.operationId}"))
        assertTrue(harness.context.memberships.members(CollectionId(collectionId)).isEmpty(), "the memberships went with the collection")
    }

    @Test
    fun `everyone can find colleagues to add without seeing the administrator list`() = runBlocking {
        val directory = send("reader", HttpMethod.Get, "/api/users/directory")

        assertEquals(HttpStatusCode.OK, directory.status)
        assertContains(directory.bodyAsText(), "outsider")
        assertFalse(directory.bodyAsText().contains("lastSignInAt"))
        assertEquals(HttpStatusCode.Forbidden, status("reader", HttpMethod.Get, "/api/users"))
        assertEquals(UserRole.USER, users.getValue("reader").role)
    }
}
