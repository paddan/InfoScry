package infoscry.server

import infoscry.AppContext
import infoscry.document.RestoreDocument
import infoscry.document.RestoreFixtures
import infoscry.document.SwitchableEmbedder
import infoscry.domain.CollectionId
import infoscry.jobs.seedCollectionWithId
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The restore and history API over a real socket: what each request does, what it refuses, and what it
 * never says.
 *
 * The archive is seeded with three published readings before the server starts, so every assertion is about
 * the route layer — the status each conflict gets, that a repeated request is one operation, that an id of
 * another document or collection is answered exactly like one that does not exist, and that the history
 * names only what was recorded and never a filesystem path.
 */
class OcrRestoreRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer
    private lateinit var fixture: RestoreDocument
    private val embedder = SwitchableEmbedder()

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-restore-routes")
        AppContext.open(dataDir).use { context ->
            context.seedCollectionWithId("default", "Default")
            fixture = RestoreFixtures.publishGenerations(context, dataDir, CollectionId("default"))
        }
        harness = ApiTestServer(dataDir, restoreEmbedder = embedder.provider)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private val prefix: String get() = "/api/collections/default/documents/${fixture.documentId.value}/ocr"

    private fun body(
        requestId: String = "request-1",
        expected: String = fixture.revisionIds.last(),
        restoring: String = fixture.revisionIds.first(),
    ) = """{"requestId":"$requestId","expectedRevisionId":"$expected","restoreRevisionId":"$restoring"}"""

    private suspend fun restore(
        json: String = body(),
        path: String = "$prefix/restore",
        credential: Credential = Credential.CSRF,
    ): HttpResponse = harness.request(HttpMethod.Post, path, json, credential)

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun pageChanges(revision: JsonObject): Map<String, Int> =
        revision.getValue("pageChanges").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content.toInt() }

    private fun counts(
        unchanged: Int = 0,
        added: Int = 0,
        automatic: Int = 0,
        manual: Int = 0,
        unknown: Int = 0,
        restored: Int = 0,
        notPublished: Int = 0,
    ) = mapOf(
        "unchanged" to unchanged,
        "added" to added,
        "automatic" to automatic,
        "manual" to manual,
        "unknown" to unknown,
        "restored" to restored,
        "notPublished" to notPublished,
    )

    @Test
    fun `a restore is admitted durably and the history, search and sources then agree on the restored reading`() =
        runBlocking {
            val response = restore()

            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            val operation = json(response.bodyAsText())
            val newRevision = operation.getValue("newRevisionId").jsonPrimitive.content
            assertEquals("PUBLISHED", operation.getValue("phase").jsonPrimitive.content)
            assertEquals(fixture.revisionIds.first(), operation.getValue("restoredFromRevisionId").jsonPrimitive.content)
            assertEquals(fixture.revisionIds.last(), operation.getValue("expectedRevisionId").jsonPrimitive.content)

            // The operation can be read back under its own scope.
            val read = harness.get("$prefix/restores/${operation.getValue("restoreId").jsonPrimitive.content}")
            assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
            assertEquals(newRevision, json(read.bodyAsText()).getValue("newRevisionId").jsonPrimitive.content)

            // Search returns only the restored reading.
            val search = harness.get("/api/search?collection=Default&q=market&mode=keyword").bodyAsText()
            assertContains(search, "market first")
            assertFalse(search.contains("market second") || search.contains("market third"), search)

            // Old evidence opens its own revision; the live source and the new revision say the restored text.
            val third = harness.get(
                "/api/collections/default/sources/${fixture.unitIds[1].value}?revision=${fixture.revisionIds.last()}",
            ).bodyAsText()
            assertContains(third, "market third")
            val live = harness.get("/api/collections/default/sources/${fixture.unitIds[1].value}").bodyAsText()
            assertContains(live, "market first")
            val restored = harness.get(
                "/api/collections/default/sources/${fixture.unitIds[1].value}?revision=$newRevision",
            ).bodyAsText()
            assertContains(restored, "market first")
            assertContains(restored, "\"revisionId\":\"$newRevision\"")
        }

    @Test
    fun `the history says what was recorded about each revision and exposes no filesystem path`() = runBlocking {
        val restored = json(restore().bodyAsText())
        val newRevision = restored.getValue("newRevisionId").jsonPrimitive.content

        val response = harness.get("$prefix/revisions")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val history = json(response.bodyAsText())
        assertEquals(newRevision, history.getValue("activeRevisionId").jsonPrimitive.content)
        val revisions = history.getValue("revisions").jsonArray.map { it.jsonObject }
        assertEquals(4, revisions.size)
        assertEquals(
            fixture.revisionIds + newRevision,
            revisions.map { it.getValue("revisionId").jsonPrimitive.content },
            "the history lists every revision, oldest first",
        )
        assertEquals(listOf(false, false, false, true), revisions.map { it.getValue("active").jsonPrimitive.content.toBoolean() })

        val latest = revisions.last()
        assertEquals("RESTORE", latest.getValue("provenance").jsonPrimitive.content)
        assertEquals(fixture.revisionIds.first(), latest.getValue("restoredFromRevisionId").jsonPrimitive.content)
        assertEquals(fixture.revisionIds.last(), latest.getValue("parentRevisionId").jsonPrimitive.content)
        assertTrue(latest.getValue("createdAt").jsonPrimitive.content.isNotBlank())
        assertTrue(latest.getValue("publishedAt").jsonPrimitive.content.isNotBlank(), "a publication records when")
        assertEquals(2, latest.getValue("pageCount").jsonPrimitive.content.toInt())
        // Only recorded facts. The first reading has no parent and no restore, and says every page is new; the
        // restore says the page it brought back differs from the reading it replaced and that it is neither an
        // automatic nor a manual decision; a rescan-less replacement whose pages no review or decision recorded
        // is "unknown" rather than guessed.
        val first = revisions.first()
        assertFalse(first.containsKey("parentRevisionId"))
        assertFalse(first.containsKey("restoredFromRevisionId"))
        assertEquals(counts(added = 2), pageChanges(first))
        assertEquals(counts(unchanged = 1, unknown = 1), pageChanges(revisions[1]))
        assertEquals(counts(unchanged = 1, restored = 1), pageChanges(latest))
        // Nothing about an engine is claimed for a revision no operation produced.
        assertFalse(revisions.any { it.containsKey("reading") })

        val text = response.bodyAsText()
        assertFalse(text.contains(dataDir.toString()), "a filesystem path leaked into the history")
        assertFalse(text.contains("artifact", ignoreCase = true), text)
        assertFalse(text.contains("/Users") || text.contains("source_image"), text)
    }

    @Test
    fun `a repeated request is one operation and the same id with another body conflicts`() = runBlocking {
        val first = restore()
        val second = restore()
        val conflicting = restore(body(restoring = fixture.revisionIds[1]))

        assertEquals(HttpStatusCode.Accepted, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.Accepted, second.status, second.bodyAsText())
        assertEquals(
            json(first.bodyAsText()).getValue("restoreId"),
            json(second.bodyAsText()).getValue("restoreId"),
            "a repeated request restored twice",
        )
        assertEquals(HttpStatusCode.Conflict, conflicting.status, conflicting.bodyAsText())
        assertContains(conflicting.bodyAsText(), "RESTORE_REQUEST_CONFLICT")
        val history = json(harness.get("$prefix/revisions").bodyAsText())
        assertEquals(4, history.getValue("revisions").jsonArray.size)
    }

    @Test
    fun `an invalid shape is a bad request`() = runBlocking {
        val blank = restore(body(requestId = " "))
        val noRevision = restore("""{"requestId":"r","expectedRevisionId":"x"}""")
        val blankExpected = restore(body(expected = ""))
        val notJson = restore("not json")

        listOf(blank, noRevision, blankExpected, notJson).forEach { response ->
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }
        assertEquals(fixture.revisionIds.last(), harness.context.revisions.activeRevisionId(fixture.documentId))
    }

    @Test
    fun `a stale tab or the already active revision is a conflict and nothing changes`() = runBlocking {
        val stale = restore(body(expected = fixture.revisionIds[1]))
        val active = restore(body(restoring = fixture.revisionIds.last()))

        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
        assertContains(stale.bodyAsText(), "STALE_DOCUMENT_REVISION")
        assertEquals(HttpStatusCode.Conflict, active.status, active.bodyAsText())
        assertContains(active.bodyAsText(), "RESTORE_ALREADY_ACTIVE")
        assertEquals(fixture.revisionIds.last(), harness.context.revisions.activeRevisionId(fixture.documentId))
        assertEquals(3, harness.context.revisions.revisions(fixture.documentId).size)
    }

    @Test
    fun `another collection's revision or document is answered like one that does not exist`() = runBlocking {
        val other = harness.context.collections.create("Elsewhere")
        val foreign = RestoreFixtures.publishGenerations(harness.context, dataDir, other.id)

        val foreignRevision = restore(body(restoring = foreign.revisionIds.first()))
        val unknownRevision = restore(body(restoring = "revision-does-not-exist"))
        val foreignDocument = restore(
            body(),
            path = "/api/collections/${other.id.value}/documents/${fixture.documentId.value}/ocr/restore",
        )
        val unknownDocument = restore(body(), path = "/api/collections/default/documents/nope/ocr/restore")

        listOf(foreignRevision, unknownRevision, foreignDocument, unknownDocument).forEach { response ->
            assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "NOT_FOUND")
        }
        assertFalse(
            foreignRevision.bodyAsText().replace(foreign.revisionIds.first(), "") !=
                unknownRevision.bodyAsText().replace("revision-does-not-exist", ""),
            "the two answers differ in something other than the id the caller named",
        )
        assertEquals(fixture.revisionIds.last(), harness.context.revisions.activeRevisionId(fixture.documentId))
    }

    @Test
    fun `a restore needs credentials`() = runBlocking {
        val none = restore(credential = Credential.NONE)
        val wrong = restore(credential = Credential.WRONG_CSRF)

        assertEquals(HttpStatusCode.Unauthorized, none.status, none.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, wrong.status, wrong.bodyAsText())
        assertEquals(3, harness.context.revisions.revisions(fixture.documentId).size)
    }

    @Test
    fun `a restore during a document deletion is a conflict and a deleted document is not found`() = runBlocking {
        harness.context.documentService.beginDeletion(CollectionId("default"), listOf(fixture.documentId))

        val during = restore()

        assertEquals(HttpStatusCode.Conflict, during.status, during.bodyAsText())
        assertContains(during.bodyAsText(), "DOCUMENT_BEING_DELETED")
        assertEquals(3, harness.context.revisions.revisions(fixture.documentId).size)
    }

    @Test
    fun `an embedding failure is reported with a curated code and leaves the current revision serving`() = runBlocking {
        embedder.failure = IllegalStateException("provider said: secret document text")

        val failed = restore()

        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status, failed.bodyAsText())
        assertContains(failed.bodyAsText(), "RESTORE_EMBEDDING_FAILED")
        assertFalse(failed.bodyAsText().contains("secret document text"), failed.bodyAsText())
        assertEquals(fixture.revisionIds.last(), harness.context.revisions.activeRevisionId(fixture.documentId))
        val search = harness.get("/api/search?collection=Default&q=market&mode=keyword").bodyAsText()
        assertContains(search, "market third")
        assertFalse(search.contains("market first"), search)
    }

    @Test
    fun `a machine without an embedder refuses a restore and keeps search and sources working`() = runBlocking {
        embedder.available = false

        val refused = restore()

        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "RESTORE_EMBEDDING_UNAVAILABLE")
        val live = harness.get("/api/collections/default/sources/${fixture.unitIds[1].value}")
        assertEquals(HttpStatusCode.OK, live.status, live.bodyAsText())
        assertContains(live.bodyAsText(), "market third")
        assertEquals(0, harness.context.revisions.revisions(fixture.documentId).count { it.provenance == "RESTORE" })
    }

    @Test
    fun `reading the history is scoped to its collection`() = runBlocking {
        val other = harness.context.collections.create("Elsewhere")

        val scoped = harness.get("/api/collections/${other.id.value}/documents/${fixture.documentId.value}/ocr/revisions")

        assertEquals(HttpStatusCode.NotFound, scoped.status, scoped.bodyAsText())
    }

    @Test
    fun `a revision a rescan produced names the engine and settings it was read with`() = runBlocking {
        val operation = harness.context.ocrOperations.admit(
            collectionId = "default",
            documentId = fixture.documentId,
            baseRevisionId = fixture.revisionIds[0],
            snapshot = infoscry.ocr.OcrSettingsSnapshot(
                engine = infoscry.ocr.OcrEngine.TESSERACT,
                mode = infoscry.ocr.OcrImportMode.FILL_MISSING,
                language = "eng",
                extractorVersion = "test",
                toolVersion = "tesseract 5.5",
            ),
            requestId = "rescan-1",
            requestHash = "a".repeat(64),
            pageTotal = 2,
            jobId = null,
        )
        harness.context.ocrOperations.recordCandidateRevision(operation.operationId, fixture.revisionIds[1])

        val revisions = json(harness.get("$prefix/revisions").bodyAsText())
            .getValue("revisions").jsonArray.map { it.jsonObject }

        val reading = revisions[1].getValue("reading").jsonObject
        assertEquals("TESSERACT", reading.getValue("engine").jsonPrimitive.content)
        assertEquals("FILL_MISSING", reading.getValue("mode").jsonPrimitive.content)
        assertEquals("eng", reading.getValue("language").jsonPrimitive.content)
        assertEquals("tesseract 5.5", reading.getValue("toolVersion").jsonPrimitive.content)
        assertFalse(revisions[0].containsKey("reading"))
        assertFalse(revisions[2].containsKey("reading"))
    }
}
