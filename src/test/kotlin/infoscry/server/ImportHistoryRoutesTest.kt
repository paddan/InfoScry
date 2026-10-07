package infoscry.server

import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.JobType
import infoscry.storage.ImportItemOutcome
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * A collection's durable import history over a real socket: what a page contains, what survives a
 * restart, and what must never leave the server.
 *
 * The privacy assertions run against the raw response body rather than a decoded object, because a
 * decoded [ImportsResponse] could not express the leak being guarded against: the route building that
 * object already decided what is not part of the product surface.
 */
class ImportHistoryRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-import-history")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a collection with no imports has an empty history`() = runBlocking {
        val id = newCollection()

        val response = harness.get("/api/collections/${id.value}/imports")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val page = ApiJson.decodeFromString<ImportsResponse>(response.bodyAsText())
        assertEquals(emptyList(), page.imports)
        assertEquals(0, page.total)
    }

    @Test
    fun `imports are paginated newest first and the total does not depend on the page size`() = runBlocking {
        val id = newCollection()
        val enqueued = (1..205).map { harness.context.jobs.enqueue(JobType.IMPORT, collectionId = id).id.value }

        val first = page(id, "?limit=2")
        assertEquals(enqueued.takeLast(2).reversed(), first.imports.map { it.id.value })
        assertEquals(205, first.total)

        val second = page(id, "?limit=2&offset=2")
        assertEquals(enqueued.dropLast(2).takeLast(2).reversed(), second.imports.map { it.id.value })
        assertEquals(205, second.total, "the total counts the collection, not the page")

        val tail = page(id, "?limit=50&offset=200")
        assertEquals(5, tail.imports.size)
        assertEquals(205, tail.total)

        val clamped = page(id, "?limit=500")
        assertEquals(200, clamped.imports.size, "a limit above 200 is clamped, not honoured or refused")

        val default = page(id, "")
        assertEquals(50, default.imports.size, "a page without a limit is 50 imports")
    }

    @Test
    fun `the history is collection scoped and holds only import jobs`() = runBlocking {
        val first = newCollection("First")
        val other = newCollection("Other")
        val listed = harness.context.jobs.enqueue(JobType.IMPORT, collectionId = first).id.value
        harness.context.jobs.enqueue(JobType.IMPORT, collectionId = other)
        harness.context.jobs.enqueue(JobType.REINDEX, collectionId = first)

        val page = page(first, "")

        assertEquals(listOf(listed), page.imports.map { it.id.value })
        assertEquals(1, page.total, "another collection's import must not be counted here")
    }

    @Test
    fun `a failed job and a cancelled one keep their state and error code`() = runBlocking {
        val id = newCollection()
        val failed = harness.context.jobs.enqueue(JobType.IMPORT, collectionId = id).id
        harness.context.jobs.claim(failed)
        harness.context.jobs.fail(failed, code = "IMPORT_FAILED", message = "the file could not be read")
        val cancelled = harness.context.jobs.enqueue(JobType.IMPORT, collectionId = id).id
        harness.context.jobs.cancel(cancelled)

        val page = page(id, "")

        assertEquals(
            listOf("CANCELLED", "FAILED"),
            page.imports.map { it.state.name },
            "newest first: the cancelled job was enqueued last",
        )
        assertEquals("IMPORT_FAILED", page.imports.last().errorCode)
        assertNull(page.imports.first().errorCode)
    }

    @Test
    fun `a finished import shows no stage of the attempt that ended`() = runBlocking {
        val id = newCollection()
        val completed = harness.context.jobs.enqueue(JobType.IMPORT, collectionId = id).id
        harness.context.jobs.claim(completed)
        harness.context.jobs.progress(completed, stage = "queue", completed = 2, total = 2, currentItem = "a.pdf")
        harness.context.jobs.complete(completed)
        val failed = harness.context.jobs.enqueue(JobType.IMPORT, collectionId = id).id
        harness.context.jobs.claim(failed)
        harness.context.jobs.progress(failed, stage = "queue", completed = 0, total = 1)
        harness.context.jobs.fail(failed, code = "IMPORT_FAILED", message = "the file could not be read")

        val entries = page(id, "").imports.associateBy { it.id }

        assertEquals("COMPLETE", entries.getValue(completed).state.name)
        assertNull(entries.getValue(completed).stage)
        assertEquals("FAILED", entries.getValue(failed).state.name)
        assertNull(entries.getValue(failed).stage)
    }

    @Test
    fun `a pending file and a pre-copy failure stay import items and carry no document`() = runBlocking {
        val id = newCollection()
        val job = harness.context.jobs.enqueue(JobType.IMPORT, collectionId = id)
        harness.context.jobs.claim(job.id)
        harness.context.jobs.progress(job.id, stage = "COPYING", completed = 0, total = 2, currentItem = "a.pdf")
        val queued = harness.context.importItems.queue(job.id, "key-a", "/private/evidence/a.pdf")
        val refused = harness.context.importItems.queue(job.id, "key-b", "/private/evidence/b.bin")
        harness.context.importItems.record(
            job.id,
            refused.itemKey,
            outcome = ImportItemOutcome.FAILED,
            errorCode = "UNSUPPORTED_MEDIA_TYPE",
            errorMessage = "could not parse /private/evidence/b.bin: CONFIDENTIAL text",
        )

        val entry = page(id, "").imports.single()
        assertEquals("RUNNING", entry.state.name)
        assertEquals("COPYING", entry.stage)
        // The file being copied crosses by its own name: a history row can say what the wait is for,
        // while the path the file was selected from stays on this side.
        assertEquals("a.pdf", entry.currentItem)
        assertEquals(0, entry.filesCompleted)
        assertEquals(2, entry.filesTotal)
        assertEquals("/api/jobs/${job.id.value}/items", entry.itemsUrl)

        // The link the summary carries is the persisted per-file outcome read, and it names the files that
        // never became documents instead of inventing a document row for them.
        val itemsResponse = harness.get(entry.itemsUrl)
        assertEquals(HttpStatusCode.OK, itemsResponse.status, itemsResponse.bodyAsText())
        val items = ApiJson.decodeFromString<ImportItemsResponse>(itemsResponse.bodyAsText()).items
        assertEquals(
            listOf(ImportItemOutcome.PENDING, ImportItemOutcome.FAILED),
            items.map { it.outcome },
        )
        assertEquals(listOf(null, null), items.map { it.documentId })
        assertEquals("the pipeline has no extractor for this kind of file", items.last().errorMessage)
        assertNull(items.first().errorMessage)
        assertEquals(queued.itemKey, "key-a")
    }

    @Test
    fun `persisted history and per-file outcomes survive a restart`() = runBlocking {
        val id = newCollection()
        val job = harness.context.jobs.enqueue(JobType.IMPORT, collectionId = id)
        harness.context.jobs.claim(job.id)
        harness.context.jobs.progress(job.id, stage = "DONE", completed = 1, total = 3)
        harness.context.jobs.complete(job.id)
        val imported = harness.context.importItems.queue(job.id, "key-a", "/private/evidence/a.pdf")
        harness.context.importItems.record(job.id, imported.itemKey, ImportItemOutcome.IMPORTED)
        val duplicate = harness.context.importItems.queue(job.id, "key-b", "/private/evidence/a-copy.pdf")
        harness.context.importItems.record(job.id, duplicate.itemKey, ImportItemOutcome.DUPLICATE)
        val failed = harness.context.importItems.queue(job.id, "key-c", "/private/evidence/c.bin")
        harness.context.importItems.record(
            job.id,
            failed.itemKey,
            outcome = ImportItemOutcome.FAILED,
            errorCode = "COPY_FAILED",
            errorMessage = "the file could not be copied",
        )
        harness.close()

        ApiTestServer(dataDir).use { restarted ->
            val response = restarted.get("/api/collections/${id.value}/imports")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val entry = ApiJson.decodeFromString<ImportsResponse>(response.bodyAsText()).imports.single()
            assertEquals(job.id.value, entry.id.value)
            assertEquals("COMPLETE", entry.state.name)
            assertEquals(1, entry.filesCompleted)
            assertEquals(3, entry.filesTotal, "the attempt's file counters are the ones it persisted")

            val items = ApiJson.decodeFromString<ImportItemsResponse>(
                restarted.get(entry.itemsUrl).bodyAsText(),
            ).items
            assertEquals(
                listOf(ImportItemOutcome.IMPORTED, ImportItemOutcome.DUPLICATE, ImportItemOutcome.FAILED),
                items.map { it.outcome },
            )
            assertEquals("the file could not be copied into the library", items.last().errorMessage)
        }
    }

    @Test
    fun `the history omits the source path, the stored failure text and the worker payload`() = runBlocking {
        val id = newCollection()
        val privatePath = "/private/evidence/quarterly-report.pdf"
        val excerpt = "CONFIDENTIAL document excerpt"
        val job = harness.context.jobs.enqueue(
            JobType.IMPORT,
            collectionId = id,
            payload = """{"paths":["$privatePath"]}""",
        )
        val item = harness.context.importItems.queue(job.id, "key-a", privatePath)
        harness.context.importItems.record(
            job.id,
            item.itemKey,
            outcome = ImportItemOutcome.FAILED,
            errorCode = "SOURCE_UNREADABLE",
            errorMessage = "could not read $privatePath: $excerpt",
        )

        val body = harness.get("/api/collections/${id.value}/imports").bodyAsText()

        assertFalse(body.contains(privatePath), "the history must omit the external source path: $body")
        assertFalse(body.contains(excerpt), "the history must omit stored failure text: $body")
        assertFalse(body.contains("payload"), "worker payload must not cross the API boundary: $body")
        assertFalse(body.contains("errorMessage"), "raw failure details must not cross the API boundary: $body")
        val entry = Json.parseToJsonElement(body).jsonObject["imports"]!!.jsonArray.single().jsonObject
        assertTrue(
            entry.keys.all {
                it in setOf(
                    "id", "state", "stage", "currentItem", "filesCompleted", "filesTotal",
                    "errorCode", "createdAt", "updatedAt", "itemsUrl",
                )
            },
            "history wire fields must stay within the documented allowlist: ${entry.keys}",
        )
        listOf("filesCompleted", "filesTotal", "itemsUrl", "state", "createdAt").forEach { field ->
            assertContains(body, field, message = "the history row needs $field to render")
        }
    }

    @Test
    fun `an unknown or deleting collection is not found`() = runBlocking {
        val unknown = harness.get("/api/collections/does-not-exist/imports")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertContains(unknown.bodyAsText(), "NOT_FOUND")

        val deleting = newCollection("Going")
        harness.context.jobs.enqueue(JobType.IMPORT, collectionId = deleting)
        markDeleting(deleting)

        val response = harness.get("/api/collections/${deleting.value}/imports")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `an invalid limit or offset is a typed bad request`() = runBlocking {
        val id = newCollection()
        val base = "/api/collections/${id.value}/imports"

        listOf("?limit=abc", "?limit=1.5", "?limit=0", "?limit=-3", "?offset=abc", "?offset=-1")
            .forEach { query ->
                val response = harness.get(base + query)
                assertEquals(HttpStatusCode.BadRequest, response.status, "query $query")
                assertContains(
                    response.bodyAsText(),
                    "INVALID_REQUEST",
                    message = "query $query must be a typed bad request",
                )
            }

        assertEquals(HttpStatusCode.OK, harness.get("$base?limit=1&offset=0").status)
    }

    private suspend fun newCollection(name: String = "Nightfall"): CollectionId =
        harness.createCollection(name, Credential.BEARER).bodyAsText()
            .let { ApiJson.decodeFromString<CollectionResponse>(it).collection.id }

    private fun page(id: CollectionId, query: String): ImportsResponse = runBlocking {
        val response = harness.get("/api/collections/${id.value}/imports$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        ApiJson.decodeFromString<ImportsResponse>(response.bodyAsText())
    }

    private fun markDeleting(collectionId: CollectionId) {
        harness.context.database.transaction { connection ->
            connection.prepareStatement("UPDATE collections SET lifecycle = ? WHERE id = ?").use { statement ->
                statement.setString(1, CollectionLifecycle.DELETING.name)
                statement.setString(2, collectionId.value)
                statement.executeUpdate()
            }
        }
    }
}
