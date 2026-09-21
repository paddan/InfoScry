package infoscry.server

import infoscry.collection.CollectionIndexRemover
import infoscry.storage.CollectionStore
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The collection API over a real socket: what each request does, and which status a failure produces.
 *
 * The status codes are a contract the CLI and the browser read to decide what to do next — retry later
 * for 423, choose another name for 409, re-confirm for 400 — so they are asserted as carefully as the
 * successful responses are.
 */
class CollectionRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `the seeded Default collection is listed`() = runBlocking {
        val collections = listed()

        assertTrue(
            collections.any { it.id == CollectionStore.DEFAULT_ID && it.name == "Default" },
            "the migration seeds Default so a user has somewhere to import without organising first",
        )
    }

    @Test
    fun `creating a collection returns it and lists it`() = runBlocking {
        val response = harness.createCollection("Project Nightfall", Credential.BEARER)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "Project Nightfall")
        assertTrue(listed().any { it.name == "Project Nightfall" })
    }

    @Test
    fun `a duplicate name is a conflict`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)

        val response = harness.createCollection("acme", Credential.BEARER)

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertContains(response.bodyAsText(), "DUPLICATE_COLLECTION_NAME")
    }

    @Test
    fun `a blank name is refused as a bad request`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/collections",
            body = """{"name":"   "}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "INVALID_REQUEST")
    }

    @Test
    fun `a malformed body is refused as a bad request, not a server error`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/collections",
            body = """{"name":""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "INVALID_REQUEST")
    }

    @Test
    fun `renaming returns the updated collection`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)
        val id = harness.collectionIdOf("Acme")

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id",
            body = """{"name":"Acme acquisition"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "Acme acquisition")
        assertFalse(listed().any { it.name == "Acme" })
    }

    @Test
    fun `an unknown collection is not found`() = runBlocking {
        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/does-not-exist",
            body = """{"name":"Anything"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `deleting with the wrong confirmation is refused and changes nothing`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)
        val id = harness.collectionIdOf("Acme")

        val response = harness.request(
            HttpMethod.Delete,
            "/api/collections/$id",
            body = """{"confirmName":"Another collection"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "CONFIRMATION_MISMATCH")
        assertTrue(listed().any { it.name == "Acme" }, "a refused deletion must leave the collection alone")
    }

    @Test
    fun `deleting with the exact name removes the collection`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)
        val id = harness.collectionIdOf("Acme")

        val response = harness.request(
            HttpMethod.Delete,
            "/api/collections/$id",
            body = """{"confirmName":"Acme"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "DONE")
        assertFalse(listed().any { it.name == "Acme" })
        assertFalse(Files.exists(harness.context.paths.collectionDir(infoscry.domain.CollectionId(id))))
    }

    @Test
    fun `a mutating command during maintenance is refused with 423`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = CollectionIndexRemover {
            started.complete(Unit)
            release.await()
        }
        val otherDataDir = Files.createTempDirectory("infoscry-routes-maintenance")
        try {
            ApiTestServer(otherDataDir, gated).use { gatedServer ->
                gatedServer.createCollection("Nightfall", Credential.BEARER)
                val id = gatedServer.collectionIdOf("Nightfall")

                val deletion = async(Dispatchers.Default) {
                    gatedServer.request(
                        HttpMethod.Delete,
                        "/api/collections/$id",
                        body = """{"confirmName":"Nightfall"}""",
                        credential = Credential.BEARER,
                    )
                }
                withTimeout(TIMEOUT_MILLIS) { started.await() }

                val refused = gatedServer.createCollection("During maintenance", Credential.BEARER)

                assertEquals(HttpStatusCode.Locked, refused.status)
                assertContains(refused.bodyAsText(), "MAINTENANCE_IN_PROGRESS")

                release.complete(Unit)
                val finished = withTimeout(TIMEOUT_MILLIS) { deletion.await() }
                assertEquals(HttpStatusCode.OK, finished.status, finished.bodyAsText())
            }
        } finally {
            otherDataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the compiled web application is served and client-side routes fall back to it`() = runBlocking {
        val root = harness.get("/")

        assertEquals(HttpStatusCode.OK, root.status, "the built frontend has to be packaged and served")
        assertContains(
            root.bodyAsText(),
            "/_app/",
            message = "the root has to serve the real application bundle, not a placeholder",
        )

        // The frontend is rendered in the browser, so a deep link has to reach the shell too.
        assertEquals(HttpStatusCode.OK, harness.get("/collections/anything").status)

        // An unknown API route stays an API answer: HTML here would hide a client's typo.
        val unknownApi = harness.get("/api/not-a-route")
        assertEquals(HttpStatusCode.NotFound, unknownApi.status)
        assertContains(unknownApi.bodyAsText(), "NOT_FOUND")
    }

    private suspend fun listed(): List<infoscry.domain.Collection> =
        ApiJson.decodeFromString<CollectionsResponse>(harness.get("/api/collections").bodyAsText()).collections

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
