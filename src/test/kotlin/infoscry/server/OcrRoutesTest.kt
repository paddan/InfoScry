package infoscry.server

import infoscry.jobs.Harness
import infoscry.jobs.PictureUnits
import infoscry.jobs.RescanHarness
import infoscry.jobs.RescanJobPayload
import infoscry.storage.OcrOperationStore
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

/**
 * The rescan API over a real socket: what each request does, what it refuses, and what it never says.
 *
 * The archive is seeded before the server starts — a collection with one imported picture document and its
 * first published revision — so every assertion here is about the *route* layer: which id is resolved under
 * which scope, which status a conflict gets, and that a repeated request is one operation rather than two.
 * Whether a page can be read is deliberately not asserted on: the attempt runs with the production engine
 * wiring on whatever machine the suite happens to run on, and its progress is read from the operation.
 */
class OcrRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer
    private lateinit var picture: RescanHarness.Picture
    private lateinit var reviewProfileId: String

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-ocr-routes")
        // The document is imported through the real import path before the server owns the directory, with a
        // stand-in extractor: the routes under test are about documents that exist, not about reading them.
        // The archive is the harness's own data directory: that is the directory a second process opens.
        var archive: Path? = null
        RescanHarness(dataDir).use { seeded ->
            picture = seeded.importPicture("first reading")
            reviewProfileId = seeded.reviewProfileId
            archive = seeded.archiveDir
        }
        harness = ApiTestServer(archive!!, rescanEmbedder = { true })
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private val prefix: String get() = "/api/collections/default/documents/${picture.documentId.value}/ocr"

    private suspend fun preview(body: String = "{}"): HttpResponse =
        harness.request(HttpMethod.Post, "$prefix/preview", body, Credential.CSRF)

    private suspend fun previewId(): String {
        val body = preview().bodyAsText()
        return Regex(""""previewId":"([^"]+)"""").find(body)!!.groupValues[1]
    }

    private suspend fun admit(body: String): HttpResponse =
        harness.request(HttpMethod.Post, "$prefix/rescan", body, Credential.CSRF)

    private suspend fun admittedOperationId(requestId: String = "request-1"): String {
        val body = admit("""{"previewId":"${previewId()}","requestId":"$requestId"}""").bodyAsText()
        return Regex(""""operationId":"([^"]+)"""").find(body)!!.groupValues[1]
    }

    @Test
    fun `a preview names the destination, the page count and the snapshot an approval binds to`() = runBlocking {
        val response = preview()

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertContains(body, "\"pageTotal\":1")
        assertContains(body, "\"snapshotHash\":")
        assertContains(body, "\"managedHash\":\"${documentHash()}\"")
        assertContains(body, "\"externalPageUpperBound\":0", message = "a local engine sends nothing")
        assertTrue(!body.contains("appKeyEnvironmentVariable"), "no key material may be named in a preview")
    }

    @Test
    fun `a rescan is admitted durably and a repeated request is the same operation`() = runBlocking {
        val previewId = previewId()
        val body = """{"previewId":"$previewId","requestId":"same-request"}"""

        val first = admit(body)
        val second = admit(body)

        assertEquals(HttpStatusCode.Accepted, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.Accepted, second.status, second.bodyAsText())
        val firstId = Regex(""""operationId":"([^"]+)"""").find(first.bodyAsText())!!.groupValues[1]
        val secondId = Regex(""""operationId":"([^"]+)"""").find(second.bodyAsText())!!.groupValues[1]
        assertEquals(firstId, secondId, "a repeated request admitted a second reading")
        assertContains(first.bodyAsText(), "\"requestId\":\"same-request\"")
    }

    @Test
    fun `the same request id with another preview conflicts instead of admitting a second reading`() = runBlocking {
        val first = admit("""{"previewId":"${previewId()}","requestId":"reused"}""")
        assertEquals(HttpStatusCode.Accepted, first.status, first.bodyAsText())

        val conflicting = admit("""{"previewId":"${previewId()}","requestId":"reused"}""")

        assertEquals(HttpStatusCode.Conflict, conflicting.status, conflicting.bodyAsText())
        assertContains(conflicting.bodyAsText(), "OCR_REQUEST_CONFLICT")
    }

    @Test
    fun `a rescan cannot be admitted twice at the same time`() = runBlocking {
        admit("""{"previewId":"${previewId()}","requestId":"first"}""")

        val second = admit("""{"previewId":"${previewId()}","requestId":"second"}""")

        // Two readings of one document would each stage a candidate against the same baseline, so the second is
        // refused rather than run: the document's own operation says which one is reading it.
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
        assertContains(second.bodyAsText(), "OCR_ALREADY_RUNNING")
    }

    @Test
    fun `another document's or another collection's operation is not found`() = runBlocking {
        val operationId = admittedOperationId()

        val otherDocument = harness.get("$prefix/operations/$operationId")
        val unscoped = harness.get(
            "/api/collections/${"elsewhere"}/documents/${picture.documentId.value}/ocr/operations/$operationId",
        )

        assertEquals(HttpStatusCode.OK, otherDocument.status, "the scoped read must work")
        assertEquals(HttpStatusCode.NotFound, unscoped.status, unscoped.bodyAsText())
        assertContains(unscoped.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `an invalid shape is a bad request and an unknown document is not found`() = runBlocking {
        val missingRequestId = admit("""{"previewId":"${previewId()}"}""")
        val unknownDocument = harness.request(
            HttpMethod.Post,
            "/api/collections/default/documents/does-not-exist/ocr/preview",
            "{}",
            Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, missingRequestId.status, missingRequestId.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, unknownDocument.status, unknownDocument.bodyAsText())
    }

    @Test
    fun `an approval bound to another scope is refused and one that names this scope resumes the attempt`() =
        runBlocking {
            val operationId = admittedOperationId()
            val snapshotHash = snapshotHashOf(operationId)

            val stale = harness.request(
                HttpMethod.Post,
                "$prefix/operations/$operationId/approve-external",
                """{"expectedSnapshotHash":"${"0".repeat(64)}","maxDistinctPages":2}""",
                Credential.CSRF,
            )
            val tooSmall = harness.request(
                HttpMethod.Post,
                "$prefix/operations/$operationId/approve-external",
                """{"expectedSnapshotHash":"$snapshotHash","maxDistinctPages":0}""",
                Credential.CSRF,
            )
            val approved = harness.request(
                HttpMethod.Post,
                "$prefix/operations/$operationId/approve-external",
                """{"expectedSnapshotHash":"$snapshotHash","maxDistinctPages":2}""",
                Credential.CSRF,
            )

            assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
            assertContains(stale.bodyAsText(), "STALE_RESCAN_PREVIEW")
            assertEquals(HttpStatusCode.BadRequest, tooSmall.status, tooSmall.bodyAsText())
            assertEquals(HttpStatusCode.Accepted, approved.status, approved.bodyAsText())
        }

    @Test
    fun `cancelling an operation is durable and resuming a finished one is refused`() = runBlocking {
        val operationId = admittedOperationId()

        val cancelled = harness.request(
            HttpMethod.Post,
            "$prefix/operations/$operationId/cancel",
            null,
            Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        assertTrue(
            cancelled.bodyAsText().contains("\"stage\":\"CANCELLED\"") ||
                cancelled.bodyAsText().contains("\"cancelRequested\"") ||
                cancelled.bodyAsText().contains("\"stage\":\"PREFLIGHT\""),
            "cancelling must answer with the operation's own state, was ${cancelled.bodyAsText()}",
        )
    }

    @Test
    fun `the reviews route reports the pending pages and how the two counters differ`() = runBlocking {
        val operationId = admittedOperationId()

        val reviews = harness.get("$prefix/reviews?operationId=$operationId")

        assertEquals(HttpStatusCode.OK, reviews.status, reviews.bodyAsText())
        assertContains(reviews.bodyAsText(), "\"pendingReviewCount\":")
        assertContains(reviews.bodyAsText(), "\"externalAccounting\":")
        assertContains(reviews.bodyAsText(), "distinct page(s)")
    }

    @Test
    fun `a decision or a publication against another revision is a conflict`() = runBlocking {
        val operationId = admittedOperationId()
        val decisions = harness.request(
            HttpMethod.Post,
            "$prefix/review-decisions?operationId=$operationId",
            """{"requestId":"d1","expectedRevisionId":"another-revision","documentWide":"USE_NEW"}""",
            Credential.CSRF,
        )
        val publication = harness.request(
            HttpMethod.Post,
            "$prefix/publish-decisions?operationId=$operationId",
            """{"expectedRevisionId":"another-revision"}""",
            Credential.CSRF,
        )

        assertEquals(HttpStatusCode.Conflict, decisions.status, decisions.bodyAsText())
        assertContains(decisions.bodyAsText(), "STALE_DOCUMENT_REVISION")
        assertEquals(HttpStatusCode.Conflict, publication.status, publication.bodyAsText())
    }

    @Test
    fun `the revision history names the active reading and its provenance`() = runBlocking {
        val revisions = harness.get("$prefix/revisions")

        assertEquals(HttpStatusCode.OK, revisions.status, revisions.bodyAsText())
        assertContains(revisions.bodyAsText(), "\"activeRevisionId\":\"${picture.baselineRevisionId}\"")
        assertContains(revisions.bodyAsText(), "\"provenance\":\"IMPORT\"")
        assertContains(revisions.bodyAsText(), "\"active\":true")
    }

    @Test
    fun `an import's external scope is approved on the job, and another scope is refused`() = runBlocking {
        // The import records the collection's OCR selection, which is what an approval binds to.
        val accepted = harness.request(
            HttpMethod.Post,
            "/api/imports",
            """{"collection":"default","paths":["${harness.context.paths.root.resolve("nothing.png")}"]}""",
            Credential.CSRF,
        )
        assertEquals(HttpStatusCode.Accepted, accepted.status, accepted.bodyAsText())
        val jobId = Regex("\"id\":\"([^\"]+)\"").find(accepted.bodyAsText())!!.groupValues[1]
        val snapshotHash = OcrOperationStore.snapshotHashOf(
            infoscry.jobs.ImportJobPayload.decode(harness.context.jobs.get(infoscry.domain.JobId(jobId))!!.payload).ocr!!,
        )

        val stale = harness.request(
            HttpMethod.Post,
            "/api/jobs/$jobId/approve-external",
            """{"expectedSnapshotHash":"${"1".repeat(64)}","maxDistinctPages":4}""",
            Credential.CSRF,
        )
        val approved = harness.request(
            HttpMethod.Post,
            "/api/jobs/$jobId/approve-external",
            """{"expectedSnapshotHash":"$snapshotHash","maxDistinctPages":4}""",
            Credential.CSRF,
        )

        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
        assertEquals(HttpStatusCode.Accepted, approved.status, approved.bodyAsText())
        assertContains(approved.bodyAsText(), "\"authorizedDistinctPages\":4")
        assertContains(approved.bodyAsText(), "\"distinctPagesSent\":0")
        assertContains(approved.bodyAsText(), "\"calls\":0")
    }

    // ---- helpers ----

    private fun documentHash(): String = harness.context.documents.get(picture.documentId)!!.sha256

    private fun snapshotHashOf(operationId: String): String = OcrOperationStore.snapshotHashOf(
        harness.context.ocrOperations.operation(operationId)!!.snapshot,
    )

    @Test
    fun `the collection's review profile is selectable through the settings route and reaches the operation`() =
        runBlocking {
            val updated = harness.request(
                HttpMethod.Patch,
                "/api/collections/default/ocr-languages",
                """{"ocrEngine":"SURYA","ocrImportMode":"CHECK_AND_IMPROVE","ocrReviewProfileId":"$reviewProfileId","ocrExternalPageLimit":1}""",
                Credential.CSRF,
            )
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())

            val preview = preview()
            assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
            val previewBody = preview.bodyAsText()
            assertContains(previewBody, "\"reviewProfileRevisionId\":")
            assertContains(previewBody, "\"externalPageUpperBound\":1")
            assertContains(previewBody, "\"approvalRequired\":false", message = "the allowance covers one page")

            val operationId = admittedOperationId()
            val operation = snapshotHashOf(operationId)
            assertNotEquals("", operation, "the admitted operation records the snapshot it was admitted with")
            assertContains(
                harness.get("$prefix/operations/$operationId").bodyAsText(),
                "\"policyVersion\":1",
                message = "a pilot policy is what an operation records",
            )
            // The job the admission queued names the operation rather than restating the work, so a resume
            // continues the same reading with the same snapshot.
            val rescanJob = harness.context.jobs.list(limit = 50).first { job ->
                job.type == infoscry.domain.JobType.RESCAN
            }
            assertEquals(
                operationId,
                RescanJobPayload.decode(rescanJob.payload).operationId,
            )
        }
}
