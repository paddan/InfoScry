package infoscry.server

import infoscry.domain.CollectionId
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.extract.EXTRACTOR_SCHEMA_VERSION
import infoscry.extract.ExtractionSettings
import infoscry.jobs.ImportJobPayload
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.storage.ImportItemOutcome
import infoscry.storage.JobStore
import infoscry.storage.OcrOperationStore
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The browser-facing job routes expose an allowlist, not persisted job and import-item rows. */
class JobRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-job-routes")
        harness = ApiTestServer(dataDir)
        // A new archive has no automatic Default; the accepted-import test needs one that exists.
        runBlocking { harness.context.collectionService.create("Default") }
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `job list detail and cancel omit worker payload and raw failure details`() = runBlocking {
        val privatePath = "/private/evidence/quarterly-report.pdf"
        val excerpt = "CONFIDENTIAL document excerpt"
        val job = harness.context.jobs.enqueue(
            JobType.IMPORT,
            payload = """{"paths":["$privatePath"]}""",
            total = 1,
        )
        harness.context.jobs.claim(job.id)
        harness.context.jobs.fail(
            job.id,
            code = "JOB_FAILED",
            message = "Could not parse $privatePath: $excerpt",
        )

        val list = harness.get("/api/jobs").bodyAsText()
        val detail = harness.get("/api/jobs/${job.id.value}").bodyAsText()
        val cancel = harness.request(HttpMethod.Post, "/api/jobs/${job.id.value}/cancel", credential = Credential.BEARER)
            .bodyAsText()

        listOf(list, detail, cancel).forEach { body ->
            assertFalse(body.contains(privatePath), "job routes must omit private paths: $body")
            assertFalse(body.contains(excerpt), "job routes must omit raw failure details: $body")
            assertFalse(body.contains("payload"), "worker payload must not cross the API boundary: $body")
            assertFalse(body.contains("errorMessage"), "raw failure details must not cross the API boundary: $body")
            val jobWire = findJob(body).jsonObject
            assertTrue(
                jobWire.keys.all { it in setOf("id", "type", "state", "createdAt", "updatedAt", "collectionId", "stage", "currentItem", "completed", "total", "errorCode", "cancelRequested") },
                "job wire fields must stay within the documented allowlist: ${jobWire.keys}",
            )
            assertEquals("JOB_FAILED", jobWire["errorCode"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `a running job names the file it is working on, and the name is not the path it came from`() = runBlocking {
        val privatePath = "/private/evidence/quarterly-report.pdf"
        val job = harness.context.jobs.enqueue(
            JobType.IMPORT,
            payload = """{"paths":["$privatePath"]}""",
            total = 2,
        )
        harness.context.jobs.claim(job.id)
        harness.context.jobs.progress(job.id, stage = "copy", completed = 1, currentItem = "quarterly-report.pdf")

        val body = harness.get("/api/jobs/${job.id.value}").bodyAsText()

        val jobWire = Json.parseToJsonElement(body).jsonObject["job"]!!.jsonObject
        assertEquals("quarterly-report.pdf", jobWire["currentItem"]?.toString()?.trim('"'))
        assertFalse(body.contains(privatePath), "the job route must omit the selected path: $body")
    }

    @Test
    fun `job item responses name the source file and the sentence the code means, with no source path`() = runBlocking {
        val privatePath = "/private/evidence/quarterly-report.pdf"
        val excerpt = "CONFIDENTIAL document excerpt"
        val job = harness.context.jobs.enqueue(JobType.IMPORT)
        val item = harness.context.importItems.queue(job.id, "item-key", privatePath)
        harness.context.importItems.record(
            job.id,
            item.itemKey,
            outcome = ImportItemOutcome.FAILED,
            errorCode = "UNSUPPORTED_MEDIA_TYPE",
            errorMessage = "Could not parse $privatePath: $excerpt",
        )

        val response = harness.get("/api/jobs/${job.id.value}/items")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        // The absolute path the file was selected from is the archive's own bookkeeping: the durable
        // import history is a management read, so only the file's name crosses.
        assertFalse(body.contains(privatePath), "item responses must not carry the selected path: $body")
        assertFalse(body.contains("sourcePath"), "the item wire type must not expose sourcePath: $body")
        assertTrue(body.contains("sourceName"), "the item wire type must expose sourceName: $body")
        assertTrue(body.contains("errorMessage"), "the item wire type must expose errorMessage: $body")
        // The stored message is a diagnostic for the machine that ran the import: it can carry a document's
        // own text, so it stays on this side of the boundary and the code's sentence is served instead.
        assertFalse(body.contains(excerpt), "raw stored failure text must not cross the API boundary: $body")
        assertFalse(body.contains("Could not parse"), "the stored message's own words must not cross: $body")
        val itemWire = Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertTrue(
            itemWire.keys.all { it in setOf("id", "jobId", "documentId", "sourceName", "outcome", "errorCode", "errorMessage", "createdAt", "updatedAt") },
            "item wire fields must stay within the documented allowlist: ${itemWire.keys}",
        )
        assertEquals("UNSUPPORTED_MEDIA_TYPE", itemWire["errorCode"]?.toString()?.trim('"'))
        // The name is the path's own last segment, so a folder import still names the file that failed.
        assertEquals("quarterly-report.pdf", itemWire["sourceName"]?.toString()?.trim('"'))
        assertEquals(
            "the pipeline has no extractor for this kind of file",
            itemWire["errorMessage"]?.toString()?.trim('"'),
        )
    }

    @Test
    fun `accepted import response uses the same safe job DTO`() = runBlocking {
        val privatePath = "/private/evidence/quarterly-report.pdf"
        val response = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"Default","paths":["$privatePath"]}""",
            credential = Credential.BEARER,
        )
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Accepted, response.status, body)
        assertFalse(body.contains(privatePath), "accepted job responses must not echo selected paths: $body")
        assertFalse(body.contains("payload"), "worker payload must not cross the API boundary: $body")
        assertFalse(body.contains("errorMessage"), "raw failure details must not cross the API boundary: $body")
        val jobWire = Json.parseToJsonElement(body).jsonObject["job"]!!.jsonObject
        assertTrue(
            jobWire.keys.all { it in setOf("id", "type", "state", "createdAt", "updatedAt", "collectionId", "stage", "currentItem", "completed", "total", "errorCode", "cancelRequested") },
            "accepted job fields must stay within the documented allowlist: ${jobWire.keys}",
        )
    }

    @Test
    fun `job list rejects malformed negative and overflowing paging values`() = runBlocking {
        listOf(
            "?limit=abc", "?limit=1.5", "?limit=0", "?limit=-1", "?limit=2147483648",
            "?offset=abc", "?offset=1.5", "?offset=-1", "?offset=2147483648",
        ).forEach { query ->
            val response = harness.get("/api/jobs$query")
            assertEquals(HttpStatusCode.BadRequest, response.status, "query $query: ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().contains("INVALID_REQUEST"), "query $query")
        }
        assertEquals(HttpStatusCode.OK, harness.get("/api/jobs?limit=1&offset=0").status)
    }

    @Test
    fun `a job paused for an external approval names the snapshot the approval route accepts`() = runBlocking {
        val collectionId = harness.context.collectionService.requireActiveByNameOrId("Default").id
        val snapshot = OcrSettingsSnapshot(
            engine = OcrEngine.TESSERACT,
            mode = OcrImportMode.FILL_MISSING,
            language = "eng",
            extractorVersion = EXTRACTOR_SCHEMA_VERSION,
            externalPageLimit = 1,
        )
        val waiting = pausedForApproval(
            collectionId,
            ImportJobPayload(
                collectionId = "default",
                sources = listOf("/private/evidence/scan.png"),
                settings = ExtractionSettings(ocrLanguages = "eng"),
                ocr = snapshot,
            ).encode(),
        )
        // A job paused without a recorded selection has no scope to approve, so it carries no block.
        val unscoped = pausedForApproval(
            collectionId,
            ImportJobPayload(
                collectionId = "default",
                sources = listOf("/private/evidence/other.png"),
                settings = ExtractionSettings(ocrLanguages = "eng"),
            ).encode(),
        )
        val queued = harness.context.jobs.enqueue(JobType.IMPORT, payload = """{"paths":["/x.png"]}""", total = 1)

        val detail = jobObject(harness.get("/api/jobs/${waiting.value}").bodyAsText())
        val approval = detail["externalApproval"]!!.jsonObject
        val snapshotHash = approval["snapshotHash"]!!.jsonPrimitive.content
        assertEquals(OcrOperationStore.snapshotHashOf(snapshot), snapshotHash)
        assertEquals(0, approval["distinctPagesSent"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, approval["allowance"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, approval["calls"]!!.jsonPrimitive.content.toInt())

        // The list answers the same block for the paused job, and never for a job that is not paused.
        val listed = Json.parseToJsonElement(harness.get("/api/jobs").bodyAsText()).jsonObject["jobs"]!!.jsonArray
            .associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(snapshotHash, listed.getValue(waiting.value).jsonObject["externalApproval"]!!.jsonObject["snapshotHash"]!!.jsonPrimitive.content)
        assertFalse(listed.getValue(unscoped.value).jsonObject.containsKey("externalApproval"))
        assertFalse(listed.getValue(queued.id.value).jsonObject.containsKey("externalApproval"))
        assertFalse(jobObject(harness.get("/api/jobs/${unscoped.value}").bodyAsText()).containsKey("externalApproval"))

        // The import history carries the same block for the paused import, and none for the unscoped one.
        val history = Json.parseToJsonElement(harness.get("/api/collections/Default/imports").bodyAsText())
            .jsonObject["imports"]!!.jsonArray
            .associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(
            snapshotHash,
            history.getValue(waiting.value).jsonObject["externalApproval"]!!.jsonObject["snapshotHash"]!!.jsonPrimitive.content,
        )
        assertFalse(history.getValue(unscoped.value).jsonObject.containsKey("externalApproval"))

        val approved = harness.request(
            HttpMethod.Post,
            "/api/jobs/${waiting.value}/approve-external",
            """{"expectedSnapshotHash":"$snapshotHash","maxDistinctPages":2}""",
            Credential.CSRF,
        )
        assertEquals(HttpStatusCode.Accepted, approved.status, approved.bodyAsText())

        // The approval resumes the job, so it no longer waits and the block is gone with the stage.
        val resumed = jobObject(harness.get("/api/jobs/${waiting.value}").bodyAsText())
        assertFalse(resumed.containsKey("externalApproval"), "a resumed job is not awaiting approval")
    }

    @Test
    fun `cancelling a job paused for an external approval ends it as cancelled and refuses a later approval`() = runBlocking {
        val collectionId = harness.context.collectionService.requireActiveByNameOrId("Default").id
        val snapshot = OcrSettingsSnapshot(
            engine = OcrEngine.TESSERACT,
            mode = OcrImportMode.FILL_MISSING,
            language = "eng",
            extractorVersion = EXTRACTOR_SCHEMA_VERSION,
            externalPageLimit = 1,
        )
        val waiting = pausedForApproval(
            collectionId,
            ImportJobPayload(
                collectionId = "default",
                sources = listOf("/private/evidence/scan.png"),
                settings = ExtractionSettings(ocrLanguages = "eng"),
                ocr = snapshot,
            ).encode(),
        )
        val snapshotHash = OcrOperationStore.snapshotHashOf(snapshot)

        val cancelled = harness.request(HttpMethod.Post, "/api/jobs/${waiting.value}/cancel", null, Credential.CSRF)
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        val job = jobObject(cancelled.bodyAsText())
        assertEquals("CANCELLED", job["state"]!!.jsonPrimitive.content)
        assertFalse(job.containsKey("externalApproval"))
        assertFalse(job["stage"]?.jsonPrimitive?.content == JobStore.AWAITING_APPROVAL_STAGE)

        // Idempotent: a second cancel answers the same ended job.
        val again = harness.request(HttpMethod.Post, "/api/jobs/${waiting.value}/cancel", null, Credential.CSRF)
        assertEquals("CANCELLED", jobObject(again.bodyAsText())["state"]!!.jsonPrimitive.content)

        val refused = harness.request(
            HttpMethod.Post,
            "/api/jobs/${waiting.value}/approve-external",
            """{"expectedSnapshotHash":"$snapshotHash","maxDistinctPages":2}""",
            Credential.CSRF,
        )
        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        assertEquals(JobState.CANCELLED, harness.context.jobs.get(waiting)!!.state)
    }

    /** Enqueues an import with [payload] and leaves it paused the way the import handler does. */
    private fun pausedForApproval(collectionId: CollectionId, payload: String): JobId {
        val job = harness.context.jobs.enqueue(JobType.IMPORT, collectionId = collectionId, payload = payload, total = 1)
        harness.context.jobs.claim(job.id)
        harness.context.jobs.progress(job.id, stage = JobStore.AWAITING_APPROVAL_STAGE)
        harness.context.jobs.complete(job.id)
        return job.id
    }

    private fun jobObject(body: String) = Json.parseToJsonElement(body).jsonObject["job"]!!.jsonObject

    private fun findJob(body: String) = Json.parseToJsonElement(body).jsonObject["job"]
        ?: Json.parseToJsonElement(body).jsonObject["jobs"]!!.jsonArray.first()
}
