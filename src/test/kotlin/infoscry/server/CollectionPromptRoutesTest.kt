package infoscry.server

import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `/api/collections/{id}/prompts` and `/prompt-defaults/{role}`: reads, writes, repeats, CSRF and the wire shape. */
class CollectionPromptRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-prompt-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private fun promptsPath(id: String, suffix: String = "") = "/api/collections/$id/prompts$suffix"

    private suspend fun json(response: HttpResponse): JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private fun String.field(name: String): String = Json.parseToJsonElement(this).jsonObject.getValue(name).jsonPrimitive.content

    private suspend fun listJson(id: String): JsonObject {
        val response = harness.request(HttpMethod.Get, promptsPath(id), credential = Credential.BEARER)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json(response)
    }

    private fun namesOf(list: JsonObject, role: String): List<String> =
        list.getValue(role).jsonObject.getValue("prompts").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }

    private suspend fun create(id: String, role: String, name: String, body: String): HttpResponse = harness.request(
        HttpMethod.Post,
        promptsPath(id),
        body = """{"role":"$role","name":"$name","body":"$body"}""",
        credential = Credential.CSRF,
    )

    private suspend fun newCollection(): String {
        harness.createCollection("Notes", Credential.BEARER)
        return harness.collectionIdOf("Notes")
    }

    @Test
    fun `GET returns coreRules and Default first for both roles`() = runBlocking {
        val id = newCollection()

        val list = listJson(id)

        assertTrue(list.getValue("coreRules").jsonPrimitive.content.isNotBlank(), "the core rules are returned")
        for (role in listOf("ask", "investigate")) {
            val section = list.getValue(role).jsonObject
            assertEquals("default", section.getValue("defaultId").jsonPrimitive.content)
            val first = section.getValue("prompts").jsonArray.first().jsonObject
            assertEquals("default", first.getValue("id").jsonPrimitive.content)
            assertEquals("Default", first.getValue("name").jsonPrimitive.content)
            assertEquals(1, first.getValue("version").jsonPrimitive.content.toInt())
            assertEquals(true, first.getValue("builtIn").jsonPrimitive.content.toBoolean())
            assertTrue(first.getValue("body").jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `stored prompts follow Default in name order`() = runBlocking {
        val id = newCollection()
        create(id, "ASK", "Zeta", "Zeta body.")
        create(id, "ASK", "alpha", "Alpha body.")

        assertEquals(listOf("Default", "alpha", "Zeta"), namesOf(listJson(id), "ask"))
        assertEquals(listOf("Default"), namesOf(listJson(id), "investigate"), "a prompt belongs to one role")
    }

    @Test
    fun `POST creates 201, and a repeat of the same prompt answers 200 with the same id`() = runBlocking {
        val id = newCollection()

        val created = create(id, "ASK", "Short", "Answer in one line.")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val promptId = created.bodyAsText().field("id")
        assertNotEquals("default", promptId)

        val repeat = create(id, "ASK", "Short", "Answer in one line.")
        assertEquals(HttpStatusCode.OK, repeat.status, repeat.bodyAsText())
        assertEquals(promptId, repeat.bodyAsText().field("id"))
    }

    @Test
    fun `POST with a taken name and another body is 409 PROMPT_NAME_TAKEN`() = runBlocking {
        val id = newCollection()
        create(id, "ASK", "Short", "Answer in one line.")

        val taken = create(id, "ASK", "short", "A different body.")

        assertEquals(HttpStatusCode.Conflict, taken.status, taken.bodyAsText())
        assertContains(taken.bodyAsText(), "PROMPT_NAME_TAKEN")
    }

    @Test
    fun `POST with the reserved name Default or a blank body is 400 INVALID_REQUEST`() = runBlocking {
        val id = newCollection()

        val reserved = create(id, "ASK", "default", "Body.")
        val blank = create(id, "ASK", "Blank", "   ")

        assertEquals(HttpStatusCode.BadRequest, reserved.status, reserved.bodyAsText())
        assertContains(reserved.bodyAsText(), "INVALID_REQUEST")
        assertEquals(HttpStatusCode.BadRequest, blank.status, blank.bodyAsText())
        assertContains(blank.bodyAsText(), "INVALID_REQUEST")
    }

    @Test
    fun `PUT updates and bumps version only when the body changes`() = runBlocking {
        val id = newCollection()
        val promptId = create(id, "ASK", "Short", "Answer in one line.").bodyAsText().field("id")
        val path = promptsPath(id, "/$promptId")

        val renamed = harness.request(HttpMethod.Put, path, body = """{"name":"Brief","body":"Answer in one line."}""", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        assertEquals("1", renamed.bodyAsText().field("version"), "a rename alone keeps the version")
        assertEquals("Brief", renamed.bodyAsText().field("name"))

        val changed = harness.request(HttpMethod.Put, path, body = """{"name":"Brief","body":"Answer in two lines."}""", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
        assertEquals("2", changed.bodyAsText().field("version"), "a changed body bumps the version")
    }

    @Test
    fun `PUT on Default is 400`() = runBlocking {
        val id = newCollection()

        val refused = harness.request(HttpMethod.Put, promptsPath(id, "/default"), body = """{"name":"Other","body":"Body."}""", credential = Credential.CSRF)

        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "INVALID_REQUEST")
    }

    @Test
    fun `DELETE answers 204 and a second DELETE of the same id also answers 204`() = runBlocking {
        val id = newCollection()
        val promptId = create(id, "INVESTIGATE", "Gone", "Body.").bodyAsText().field("id")

        val first = harness.request(HttpMethod.Delete, promptsPath(id, "/$promptId"), credential = Credential.CSRF)
        val second = harness.request(HttpMethod.Delete, promptsPath(id, "/$promptId"), credential = Credential.CSRF)

        assertEquals(HttpStatusCode.NoContent, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, second.status, second.bodyAsText())
        assertEquals(listOf("Default"), namesOf(listJson(id), "investigate"))
    }

    @Test
    fun `PUT prompt-defaults sets the default and resets it with default`() = runBlocking {
        val id = newCollection()
        val promptId = create(id, "ASK", "Short", "Answer in one line.").bodyAsText().field("id")

        val set = harness.request(HttpMethod.Put, "/api/collections/$id/prompt-defaults/ask", body = """{"promptId":"$promptId"}""", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        assertEquals(promptId, set.bodyAsText().field("defaultId"))
        assertEquals(promptId, listJson(id).getValue("ask").jsonObject.getValue("defaultId").jsonPrimitive.content)

        val reset = harness.request(HttpMethod.Put, "/api/collections/$id/prompt-defaults/ask", body = """{"promptId":"default"}""", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
        assertEquals("default", reset.bodyAsText().field("defaultId"))
        assertEquals("default", listJson(id).getValue("ask").jsonObject.getValue("defaultId").jsonPrimitive.content)
    }

    @Test
    fun `writes without the CSRF credential are refused`() = runBlocking {
        val id = newCollection()
        val promptId = create(id, "ASK", "Short", "Answer in one line.").bodyAsText().field("id")
        val before = listJson(id)

        listOf(Credential.NONE, Credential.WRONG_CSRF, Credential.WRONG_BEARER).forEach { credential ->
            val post = harness.request(HttpMethod.Post, promptsPath(id), body = """{"role":"ASK","name":"X","body":"x"}""", credential = credential)
            val put = harness.request(HttpMethod.Put, promptsPath(id, "/$promptId"), body = """{"name":"X","body":"x"}""", credential = credential)
            val delete = harness.request(HttpMethod.Delete, promptsPath(id, "/$promptId"), credential = credential)
            val default = harness.request(HttpMethod.Put, "/api/collections/$id/prompt-defaults/ask", body = """{"promptId":"$promptId"}""", credential = credential)
            listOf(post, put, delete, default).forEach { response ->
                assertNotEquals(HttpStatusCode.OK, response.status, "a write was accepted with $credential")
                assertNotEquals(HttpStatusCode.Created, response.status, "a write was accepted with $credential")
                assertNotEquals(HttpStatusCode.NoContent, response.status, "a write was accepted with $credential")
            }
        }
        assertEquals(before, listJson(id), "refused writes change nothing")
    }
}
