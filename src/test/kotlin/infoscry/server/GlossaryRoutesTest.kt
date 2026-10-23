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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `/api/collections/{id}/glossary`. */
class GlossaryRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-glossary-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private suspend fun json(response: HttpResponse): JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun newCollection(name: String = "Notes"): String {
        harness.createCollection(name, Credential.BEARER)
        return harness.collectionIdOf(name)
    }

    private suspend fun put(id: String, groupId: String, vararg terms: String, credential: Credential = Credential.CSRF) =
        harness.request(
            HttpMethod.Put,
            "/api/collections/$id/glossary/$groupId",
            body = """{"terms":[${terms.joinToString(",") { """{"text":"$it","prefix":false}""" }}]}""",
            credential = credential,
        )

    private suspend fun list(id: String): JsonObject {
        val response = harness.request(HttpMethod.Get, "/api/collections/$id/glossary", credential = Credential.BEARER)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json(response)
    }

    private suspend fun error(response: HttpResponse): JsonObject = json(response).getValue("error").jsonObject

    @Test
    fun `a group is stored once however often it is put`() = runBlocking {
        val id = newCollection()
        assertEquals(0, list(id).getValue("groups").jsonArray.size)

        val first = put(id, "g1", "Stockholm", "Sthlm")
        val second = put(id, "g1", "Stockholm", "Sthlm")

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(first.bodyAsText(), second.bodyAsText())
        val group = list(id).getValue("groups").jsonArray.single().jsonObject
        assertEquals("g1", group.getValue("id").jsonPrimitive.content)
        assertEquals(listOf("Stockholm", "Sthlm"), group.getValue("terms").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content })
    }

    @Test
    fun `deleting twice answers 204 both times`() = runBlocking {
        val id = newCollection()
        put(id, "g1", "Stockholm", "Sthlm")

        val first = harness.request(HttpMethod.Delete, "/api/collections/$id/glossary/g1", credential = Credential.CSRF)
        val second = harness.request(HttpMethod.Delete, "/api/collections/$id/glossary/g1", credential = Credential.CSRF)

        assertEquals(HttpStatusCode.NoContent, first.status)
        assertEquals(HttpStatusCode.NoContent, second.status)
        assertEquals(0, list(id).getValue("groups").jsonArray.size)
    }

    @Test
    fun `an invalid term answers 400 with its index`() = runBlocking {
        val id = newCollection()

        val invalid = put(id, "g1", "Stockholm", "!!!")

        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        val error = error(invalid)
        assertEquals("INVALID_GLOSSARY_GROUP", error.getValue("code").jsonPrimitive.content)
        assertEquals("1", error.getValue("details").jsonObject.getValue("term").jsonPrimitive.content)
    }

    @Test
    fun `a taken term answers 409 naming the term and the group but not the words`() = runBlocking {
        val id = newCollection()
        put(id, "g1", "Stockholm", "Sthlm")

        val taken = put(id, "g2", "Huvudstaden", "STHLM")

        assertEquals(HttpStatusCode.Conflict, taken.status)
        val error = error(taken)
        assertEquals("GLOSSARY_TERM_TAKEN", error.getValue("code").jsonPrimitive.content)
        val details = error.getValue("details").jsonObject
        assertEquals("1", details.getValue("term").jsonPrimitive.content)
        assertEquals("g1", details.getValue("groupId").jsonPrimitive.content)
        assertFalse("STHLM" in error.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `a group id another collection owns answers 409`() = runBlocking {
        val first = newCollection("First")
        val second = newCollection("Second")
        put(first, "g1", "Stockholm", "Sthlm")

        val refused = put(second, "g1", "Göteborg", "Gbg")

        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("GLOSSARY_ID_TAKEN", error(refused).getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `a write without the CSRF token is refused`() = runBlocking {
        val id = newCollection()

        val refused = put(id, "g1", "Stockholm", "Sthlm", credential = Credential.WRONG_CSRF)

        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals(0, list(id).getValue("groups").jsonArray.size)
    }
}
