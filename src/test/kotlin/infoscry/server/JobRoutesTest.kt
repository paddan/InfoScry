package infoscry.server

import infoscry.domain.JobType
import infoscry.storage.ImportItemOutcome
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
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
        // A new archive has no automatic Default; the accepted-import test needs one that exists.
        runBlocking { harness.context.collectionService.create("Default") }
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `job and import history expose durable progress for the current document only`() = runBlocking {
        val context = harness.context
        val collection = context.collectionService.requireActiveByNameOrId("Default")
        val document = infoscry.domain.Document(
            id = infoscry.domain.DocumentId.new(), collectionId = collection.id,
            sha256 = "a".repeat(64), mediaType = "application/pdf", originalFilename = "large.pdf",
            sourcePath = "/private/large.pdf", sizeBytes = 42, status = infoscry.domain.DocumentStatus.EXTRACTING,
            createdAt = infoscry.storage.Instants.now(), updatedAt = infoscry.storage.Instants.now(),
        )
        context.documents.insert(document)
        val job = context.jobs.enqueue(JobType.IMPORT, collection.id, total = 2)
        context.jobs.claim(job.id)
        context.jobs.progress(job.id, stage = "extract", currentItem = "large.pdf")
        context.importItems.queue(job.id, "large", "/private/large.pdf")
        context.importItems.attachDocument(job.id, "large", document.id)
        val fingerprint = infoscry.extract.ExtractionFingerprint.of(document.sha256, infoscry.extract.ExtractionSettings(ocrLanguages = "eng"))
        context.content.recordProgress(document.id, fingerprint, infoscry.domain.UnitKind.PAGE, 40)
        val sink = infoscry.ocr.CandidateRevisionSink(context.revisions, document.id, "test")
        val event = infoscry.extract.ExtractionEvent.UnitReady("page-1", 0, infoscry.extract.ContentUnitDraft(
            locator = infoscry.domain.SourceLocation.PdfPage(1), extractedText = "private text", searchText = "private text",
            method = infoscry.domain.ExtractionMethod.OCR,
        ))
        sink.deliver(document.id, fingerprint, event)
        suspend fun currentProgress(path: String, history: Boolean = false): kotlinx.serialization.json.JsonObject {
            val body = harness.get(path).bodyAsText()
            assertFalse(body.contains("private text"))
            assertFalse(body.contains("/private/"))
            val wire = Json.parseToJsonElement(body).jsonObject
            val entry = if (history) wire["imports"]!!.jsonArray.single().jsonObject else wire["job"]!!.jsonObject
            return entry["currentProgress"]!!.jsonObject
        }
        assertEquals("1", currentProgress("/api/jobs/${job.id.value}")["processedUnits"].toString())
        assertEquals("40", currentProgress("/api/collections/${collection.id.value}/imports", true)["totalUnits"].toString())
        kotlinx.coroutines.withTimeout(5_000) {
            harness.client.prepareGet(harness.url + "/api/jobs/${job.id.value}/events").execute { response ->
                val channel = response.bodyAsChannel()
                suspend fun nextProgress(): String {
                    channel.readUTF8Line() // id
                    val data = channel.readUTF8Line()!!.removePrefix("data: ")
                    channel.readUTF8Line() // blank line
                    return Json.parseToJsonElement(data).jsonObject["currentProgress"]!!.jsonObject["processedUnits"].toString()
                }
                assertEquals("1", nextProgress())
                // Only the committed page changes; the SSE must not depend on the job row changing.
                sink.deliver(document.id, fingerprint, event.copy(key = "page-2", ordinal = 1))
                assertEquals("2", nextProgress())
            }
        }
        assertEquals("2", currentProgress("/api/jobs/${job.id.value}")["processedUnits"].toString())
        context.jobs.complete(job.id)
        val terminal = Json.parseToJsonElement(harness.get("/api/jobs/${job.id.value}").bodyAsText()).jsonObject["job"]!!.jsonObject
        assertTrue(terminal["currentProgress"] == null || terminal["currentProgress"].toString() == "null")
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
    fun `job items are served a page at a time with the job's total, in queue order`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.IMPORT)
        (1..5).forEach { harness.context.importItems.queue(job.id, "key-$it", "/archive/file-$it.pdf") }

        fun names(query: String): Pair<List<String>, Int> {
            val body = runBlocking { harness.get("/api/jobs/${job.id.value}/items$query").bodyAsText() }
            val wire = Json.parseToJsonElement(body).jsonObject
            return wire["items"]!!.jsonArray.map { it.jsonObject["sourceName"].toString().trim('"') } to
                wire["total"].toString().toInt()
        }

        assertEquals(listOf("file-2.pdf", "file-3.pdf") to 5, names("?limit=2&offset=1"))
        assertEquals(listOf("file-5.pdf") to 5, names("?limit=2&offset=4"))
        assertEquals(emptyList<String>() to 5, names("?limit=2&offset=9"))
        // Without a limit the whole job is served, which is what the CLI's own report reads.
        assertEquals((1..5).map { "file-$it.pdf" } to 5, names(""))
        // Reading the same page again answers the same page.
        assertEquals(names("?limit=2&offset=1"), names("?limit=2&offset=1"))
        listOf("?limit=0", "?limit=-1", "?limit=abc", "?offset=-1", "?offset=1.5").forEach { query ->
            val response = harness.get("/api/jobs/${job.id.value}/items$query")
            assertEquals(HttpStatusCode.BadRequest, response.status, "query $query: ${response.bodyAsText()}")
        }
    }

    @Test
    fun `accepted import response uses the same safe job DTO`() = runBlocking {
        val privatePath = dataDir.resolve("private/evidence/quarterly-report.pdf").toString()
        Files.createDirectories(Path.of(privatePath).parent)
        Files.writeString(Path.of(privatePath), "quarterly report")
        val response = harness.previewAndStartImport("Default", listOf(privatePath))
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
    fun `job API exposes no external approval block or approval route`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.IMPORT, payload = "{}", total = 1)

        val detail = harness.get("/api/jobs/${job.id.value}").bodyAsText()
        val approval = harness.request(
            HttpMethod.Post,
            "/api/jobs/${job.id.value}/approve-external",
            "{}",
            Credential.CSRF,
        )

        assertFalse(detail.contains("externalApproval"), detail)
        assertFalse(Json.parseToJsonElement(harness.get("/api/jobs").bodyAsText()).toString().contains("externalApproval"))
        assertEquals(HttpStatusCode.NotFound, approval.status, approval.bodyAsText())
    }

    private fun findJob(body: String) = Json.parseToJsonElement(body).jsonObject["job"]
        ?: Json.parseToJsonElement(body).jsonObject["jobs"]!!.jsonArray.first()
}
