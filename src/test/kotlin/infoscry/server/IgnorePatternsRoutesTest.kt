package infoscry.server

import infoscry.jobs.ImportJobPayload
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
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `GET`/`PUT /api/collections/{id}/ignore-patterns`: the credentials, the validation and the snapshot at admission. */
class IgnorePatternsRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-ignore-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private fun patternsOf(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject.getValue("patterns").jsonArray.map { it.jsonPrimitive.content }

    private suspend fun read(id: String): List<String> {
        val response = harness.request(HttpMethod.Get, "/api/collections/$id/ignore-patterns", credential = Credential.BEARER)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return patternsOf(response.bodyAsText())
    }

    private suspend fun write(id: String, json: String, credential: Credential = Credential.CSRF) =
        harness.request(HttpMethod.Put, "/api/collections/$id/ignore-patterns", body = json, credential = credential)

    @Test
    fun `a new collection reads back the defaults`() = runBlocking {
        harness.createCollection("Notes", Credential.BEARER)
        val id = harness.collectionIdOf("Notes")

        assertEquals(
            listOf(".DS_Store", "._*", "Thumbs.db", "desktop.ini", "~$*", "*.tmp", ".git/", "node_modules/"),
            read(id),
        )
    }

    @Test
    fun `a save replaces the list for that collection only`() = runBlocking {
        harness.createCollection("Notes", Credential.BEARER)
        harness.createCollection("Other", Credential.BEARER)
        val notes = harness.collectionIdOf("Notes")
        val other = harness.collectionIdOf("Other")

        val saved = write(notes, """{"patterns":["  *.bak ","","# keep","!keep.bak"]}""")

        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        assertEquals(listOf("*.bak", "# keep", "!keep.bak"), patternsOf(saved.bodyAsText()))
        assertEquals(listOf("*.bak", "# keep", "!keep.bak"), read(notes))
        assertEquals(8, read(other).size, "the other collection kept its own list")
    }

    @Test
    fun `an invalid pattern is refused on save and leaves the list unchanged`() = runBlocking {
        harness.createCollection("Notes", Credential.BEARER)
        val id = harness.collectionIdOf("Notes")
        val before = read(id)

        val refused = write(id, """{"patterns":["*.bak","!"]}""")

        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "INVALID_REQUEST")
        assertEquals(before, read(id))
    }

    @Test
    fun `a save needs the CSRF credential`() = runBlocking {
        harness.createCollection("Notes", Credential.BEARER)
        val id = harness.collectionIdOf("Notes")
        val before = read(id)

        listOf(Credential.NONE, Credential.WRONG_CSRF, Credential.WRONG_BEARER).forEach { credential ->
            val response = write(id, """{"patterns":["x"]}""", credential)
            assertNotEquals(HttpStatusCode.OK, response.status, "saved with $credential")
        }
        assertEquals(before, read(id))
    }

    @Test
    fun `an unknown collection is not found for both verbs`() = runBlocking {
        val get = harness.request(HttpMethod.Get, "/api/collections/missing/ignore-patterns", credential = Credential.BEARER)
        val put = write("missing", """{"patterns":["x"]}""")

        assertEquals(HttpStatusCode.NotFound, get.status, get.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, put.status, put.bodyAsText())
    }

    @Test
    fun `an import snapshots the list it was admitted with and a later edit does not change it`() = runBlocking {
        harness.createCollection("Notes", Credential.BEARER)
        val id = harness.collectionIdOf("Notes")
        val source = dataDir.resolve("a.txt")
        Files.writeString(source, "text")
        write(id, """{"patterns":["*.bak"]}""")

        val accepted = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"Notes","paths":["$source"]}""",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.Accepted, accepted.status, accepted.bodyAsText())
        write(id, """{"patterns":["*.other"]}""")

        val payload = ImportJobPayload.decode(harness.context.jobs.list(100).single().payload)
        assertEquals(listOf("*.bak"), payload.ignore.patterns)
        assertEquals(listOf("*.other"), read(id))
    }

    @Test
    fun `the CLI's loopback client reads and replaces the list through the running server`() = runBlocking {
        harness.createCollection("Notes", Credential.BEARER)
        infoscry.cli.LoopbackApi(infoscry.config.RuntimeInfo.read(harness.context.paths.runtimeFile)!!).use { api ->
            assertEquals(8, api.ignorePatterns("notes").size, "a collection is found by name, case-insensitively")

            assertEquals(listOf("*.bak"), api.setIgnorePatterns("Notes", listOf(" *.bak ", "")))

            assertEquals(listOf("*.bak"), api.ignorePatterns("Notes"))
            val refused = runCatching { api.setIgnorePatterns("Notes", listOf("!")) }.exceptionOrNull()
            assertEquals(true, refused is infoscry.cli.RemoteApiFailure, "an invalid pattern is the server's refusal: $refused")
            assertEquals(listOf("*.bak"), api.ignorePatterns("Notes"))
        }
    }
}
