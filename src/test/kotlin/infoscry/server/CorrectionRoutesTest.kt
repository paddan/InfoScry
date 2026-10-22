package infoscry.server

import infoscry.document.RestoreFixtures
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.ReadingText
import infoscry.domain.SourceLocation
import infoscry.embedding.TestDocumentEmbedder
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `/api/collections/{id}/corrections`, the source reading and the revision history's corrections. */
class CorrectionRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-correction-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private suspend fun json(response: HttpResponse): JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun newCollection(): String {
        harness.createCollection("Notes", Credential.BEARER)
        return harness.collectionIdOf("Notes")
    }

    private suspend fun put(id: String, ruleId: String, find: String, replace: String, credential: Credential = Credential.CSRF) =
        harness.request(
            HttpMethod.Put,
            "/api/collections/$id/corrections/$ruleId",
            body = """{"find":"$find","replace":"$replace","exactCase":false}""",
            credential = credential,
        )

    private suspend fun list(id: String): JsonObject {
        val response = harness.request(HttpMethod.Get, "/api/collections/$id/corrections", credential = Credential.BEARER)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json(response)
    }

    @Test
    fun `a rule is stored once however often it is put`() = runBlocking {
        val id = newCollection()

        val first = put(id, "r1", "spinners", "Spinnars")
        val second = put(id, "r1", "spinners", "Spinnars")

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(first.bodyAsText(), second.bodyAsText())
        val rules = list(id).getValue("rules").jsonArray
        assertEquals(1, rules.size)
        assertEquals("spinners", rules.single().jsonObject.getValue("find").jsonPrimitive.content)
        assertEquals(0, rules.single().jsonObject.getValue("matches").jsonPrimitive.content.toInt())
        assertEquals("CORRECTIONS", list(id).getValue("job").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `an invalid rule answers 400 and a taken word 409 without naming the rule`() = runBlocking {
        val id = newCollection()
        put(id, "r1", "spinners", "Spinnars")

        val invalid = put(id, "r2", " ", "x")
        val taken = put(id, "r3", "Spinners", "Spinnarsson")

        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("INVALID_REQUEST", json(invalid).getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, taken.status)
        val error = json(taken).getValue("error").jsonObject
        assertEquals("CORRECTION_FIND_TAKEN", error.getValue("code").jsonPrimitive.content)
        assertFalse("Spinners" in error.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `deleting twice answers 204 both times`() = runBlocking {
        val id = newCollection()
        put(id, "r1", "spinners", "Spinnars")

        val first = harness.request(HttpMethod.Delete, "/api/collections/$id/corrections/r1", credential = Credential.CSRF)
        val second = harness.request(HttpMethod.Delete, "/api/collections/$id/corrections/r1", credential = Credential.CSRF)

        assertEquals(HttpStatusCode.NoContent, first.status)
        assertEquals(HttpStatusCode.NoContent, second.status)
        assertEquals(0, list(id).getValue("rules").jsonArray.size)
    }

    @Test
    fun `a write without the CSRF token is refused`() = runBlocking {
        val id = newCollection()

        val refused = put(id, "r1", "spinners", "Spinnars", credential = Credential.WRONG_CSRF)

        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals(0, list(id).getValue("rules").jsonArray.size)
    }

    @Test
    fun `a preview counts matches and stores nothing`() = runBlocking {
        val id = newCollection()
        RestoreFixtures.publishGenerations(harness.context, dataDir, CollectionId(id), marketReadings = listOf("hos spinners och spinners"))

        val response = harness.request(
            HttpMethod.Post,
            "/api/collections/$id/corrections/preview",
            body = """{"find":"spinners","replace":"Spinnars","exactCase":false}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val preview = json(response)
        assertEquals(2, preview.getValue("matches").jsonPrimitive.content.toInt())
        assertEquals(1, preview.getValue("documents").jsonPrimitive.content.toInt())
        val example = preview.getValue("examples").jsonArray.first().jsonObject
        assertTrue("Spinnars" in example.getValue("after").jsonPrimitive.content)
        assertTrue(example.getValue("sourceId").jsonPrimitive.content.isNotBlank())
        assertEquals(0, list(id).getValue("rules").jsonArray.size)
        assertTrue(list(id)["job"] == null || list(id)["job"] is JsonNull, "no job ran")
    }

    @Test
    fun `apply returns the collection's one correction job`() = runBlocking {
        val id = newCollection()

        val first = harness.request(HttpMethod.Post, "/api/collections/$id/corrections/apply", credential = Credential.CSRF)
        val second = harness.request(HttpMethod.Post, "/api/collections/$id/corrections/apply", credential = Credential.CSRF)

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(
            json(first).getValue("job").jsonObject.getValue("id"),
            json(second).getValue("job").jsonObject.getValue("id"),
        )
    }

    @Test
    fun `a corrected source says so and serves its reading`() = runBlocking {
        val id = newCollection()
        val context = harness.context
        val documentId = RestoreFixtures.importDocument(context, dataDir, CollectionId(id), "x")
        val corrected = ContentUnitId.new()
        val plain = ContentUnitId.new()
        val candidate = context.revisions.openCandidate(documentId, context.revisions.activeRevisionId(documentId), "TEST_REPLACEMENT")
        listOf(Triple(corrected, "hos Spinnars", ReadingText("hos spinners", "hos spinners")), Triple(plain, "annat", null))
            .forEachIndexed { ordinal, (unitId, text, reading) ->
                context.revisions.appendPage(
                    candidate,
                    RevisionPageDraft(
                        ordinal = ordinal,
                        unitId = unitId,
                        locator = SourceLocation.TextLines(ordinal + 1, ordinal + 1),
                        extractedText = text,
                        searchText = text,
                        approval = PageApproval.APPROVED,
                        reading = reading,
                        chunks = listOf(
                            RevisionChunkDraft(0, text, 0, text.length, 3, 0, 2, TestDocumentEmbedder().embedDocuments(listOf(text)).single()),
                        ),
                    ),
                )
            }
        context.revisionPublication.publish(documentId, context.revisions.activeRevisionId(documentId), candidate)

        val source = json(harness.request(HttpMethod.Get, "/api/collections/$id/sources/${corrected.value}", credential = Credential.BEARER))
        val reading = json(harness.request(HttpMethod.Get, "/api/collections/$id/sources/${corrected.value}/reading", credential = Credential.BEARER))
        val plainSource = json(harness.request(HttpMethod.Get, "/api/collections/$id/sources/${plain.value}", credential = Credential.BEARER))
        val plainReading = json(harness.request(HttpMethod.Get, "/api/collections/$id/sources/${plain.value}/reading", credential = Credential.BEARER))

        assertEquals(true, source.getValue("hasReading").jsonPrimitive.content.toBoolean())
        assertEquals("hos spinners", reading.getValue("reading").jsonPrimitive.content)
        assertEquals(false, plainSource["hasReading"]?.jsonPrimitive?.content?.toBoolean() ?: false)
        assertTrue(plainReading["reading"] == null || plainReading["reading"] is JsonNull)
        val pinned = harness.request(
            HttpMethod.Get, "/api/collections/$id/sources/${corrected.value}/reading?revision=$candidate", credential = Credential.BEARER,
        )
        assertEquals("hos spinners", json(pinned).getValue("reading").jsonPrimitive.content)
    }

    @Test
    fun `the revision history names what corrections changed`() = runBlocking {
        val id = newCollection()
        val fixture = RestoreFixtures.publishGenerations(harness.context, dataDir, CollectionId(id), marketReadings = listOf("hos spinners"))
        harness.context.revisions.recordCorrections(
            fixture.revisionIds.last(), "f".repeat(64), listOf(infoscry.domain.AppliedCorrection("spinners", "Spinnars", 1)),
        )

        val response = harness.request(
            HttpMethod.Get, "/api/collections/$id/documents/${fixture.documentId.value}/ocr/revisions", credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val revisions = json(response).getValue("revisions").jsonArray.map { it.jsonObject }
        val corrected = revisions.single { it.getValue("revisionId").jsonPrimitive.content == fixture.revisionIds.last() }
        val corrections = corrected.getValue("corrections").jsonArray.single().jsonObject
        assertEquals("Spinnars", corrections.getValue("replace").jsonPrimitive.content)
        assertEquals(1, corrections.getValue("matches").jsonPrimitive.content.toInt())
    }
}
