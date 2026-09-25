package infoscry.server

import infoscry.domain.JobType
import infoscry.storage.ImportItemOutcome
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

/** The browser-facing job routes expose an allowlist, not persisted job and import-item rows. */
class JobRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-job-routes")
        harness = ApiTestServer(dataDir)
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
                jobWire.keys.all { it in setOf("id", "type", "state", "createdAt", "updatedAt", "collectionId", "stage", "completed", "total", "errorCode", "cancelRequested") },
                "job wire fields must stay within the documented allowlist: ${jobWire.keys}",
            )
            assertEquals("JOB_FAILED", jobWire["errorCode"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `job item responses omit source paths and raw failure details`() = runBlocking {
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
        assertFalse(body.contains(privatePath), "item responses must omit the source path: $body")
        assertFalse(body.contains(excerpt), "item responses must omit raw failure details: $body")
        assertFalse(body.contains("sourcePath"), "the item wire type must not expose sourcePath: $body")
        assertFalse(body.contains("errorMessage"), "the item wire type must not expose errorMessage: $body")
        val itemWire = Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertTrue(
            itemWire.keys.all { it in setOf("id", "jobId", "documentId", "sourceName", "outcome", "errorCode", "createdAt", "updatedAt") },
            "item wire fields must stay within the documented allowlist: ${itemWire.keys}",
        )
        assertEquals("UNSUPPORTED_MEDIA_TYPE", itemWire["errorCode"]?.toString()?.trim('"'))
        assertEquals("quarterly-report.pdf", itemWire["sourceName"]?.toString()?.trim('"'))
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
            jobWire.keys.all { it in setOf("id", "type", "state", "createdAt", "updatedAt", "collectionId", "stage", "completed", "total", "errorCode", "cancelRequested") },
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

    private fun findJob(body: String) = Json.parseToJsonElement(body).jsonObject["job"]
        ?: Json.parseToJsonElement(body).jsonObject["jobs"]!!.jsonArray.first()
}
