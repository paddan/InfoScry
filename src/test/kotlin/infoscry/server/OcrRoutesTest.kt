package infoscry.server

import infoscry.jobs.RescanHarness
import infoscry.domain.DocumentStatus
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
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

/** The rescan HTTP boundary: method-bound previews, idempotent admission and restartable replacement. */
class OcrRoutesTest {
    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer
    private lateinit var picture: RescanHarness.Picture

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-ocr-routes")
        var archive: Path? = null
        RescanHarness(dataDir).use { seeded ->
            picture = seeded.importPicture("first reading")
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

    private suspend fun preview(method: String = "tesseract"): HttpResponse =
        harness.request(HttpMethod.Post, "$prefix/preview", """{"method":"$method"}""", Credential.CSRF)

    private suspend fun previewId(method: String = "tesseract"): String {
        val response = preview(method)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Regex(""""previewId":"([^"]+)"""").find(response.bodyAsText())!!.groupValues[1]
    }

    private suspend fun admit(previewId: String, requestId: String): HttpResponse =
        harness.request(
            HttpMethod.Post,
            "$prefix/rescan",
            """{"previewId":"$previewId","requestId":"$requestId"}""",
            Credential.CSRF,
        )

    private suspend fun operationId(response: HttpResponse): String =
        Regex(""""operationId":"([^"]+)"""").find(response.bodyAsText())!!.groupValues[1]

    @Test
    fun `preview records the selected method and page count without an approval step`() = runBlocking {
        val response = preview()
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        assertContains(body, "\"pageTotal\":1")
        assertContains(body, "\"externalPageUpperBound\":1")
        assertContains(body, "\"engine\":\"TESSERACT\"")
        assertFalse(body.contains("approvalRequired"), "a confirmed reading has no mid-run approval phase")
        assertFalse(body.contains("appKeyEnvironmentVariable"), "a preview never names key material")
    }

    @Test
    fun `a rescan is admitted durably and repeating its request returns the same operation`() = runBlocking {
        val previewId = previewId()
        val first = admit(previewId, "same-request")
        val firstId = operationId(first)
        val replay = admit(previewId, "same-request")

        assertEquals(HttpStatusCode.Accepted, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.Accepted, replay.status, replay.bodyAsText())
        assertEquals(firstId, operationId(replay))
        assertContains(first.bodyAsText(), "\"requestId\":\"same-request\"")
    }

    @Test
    fun `a new request replaces a live operation and preserves its published revision`() = runBlocking {
        val originalRevision = harness.context.revisions.activeRevisionId(picture.documentId)
        val first = admit(previewId(), "first-request")
        assertEquals(HttpStatusCode.Accepted, first.status, first.bodyAsText())
        val firstId = operationId(first)

        val replacement = admit(previewId(), "second-request")
        assertEquals(HttpStatusCode.Accepted, replacement.status, replacement.bodyAsText())
        val secondId = operationId(replacement)

        assertNotEquals(firstId, secondId)
        assertEquals("CANCELLED", harness.context.ocrOperations.operation(firstId)?.stage?.name)
        assertEquals(secondId, harness.context.ocrOperations.activeOperation(picture.documentId)?.operationId)
        assertEquals(originalRevision, harness.context.revisions.activeRevisionId(picture.documentId))
    }

    @Test
    fun `cancelling a queued rescan twice leaves its published text and records cancelled status`() = runBlocking {
        val originalRevision = harness.context.revisions.activeRevisionId(picture.documentId)
        val admitted = admit(previewId(), "queued-cancel")
        assertEquals(HttpStatusCode.Accepted, admitted.status, admitted.bodyAsText())
        val operationId = operationId(admitted)
        val cancelPath = "$prefix/operations/$operationId/cancel"

        val first = harness.request(HttpMethod.Post, cancelPath, credential = Credential.CSRF)
        val replay = harness.request(HttpMethod.Post, cancelPath, credential = Credential.CSRF)

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertContains(replay.bodyAsText(), "\"stage\":\"CANCELLED\"")
        assertEquals(DocumentStatus.CANCELLED, harness.context.documents.get(picture.documentId)?.status)
        assertEquals(originalRevision, harness.context.revisions.activeRevisionId(picture.documentId))
    }

    @Test
    fun `reusing a request id for another preview is a conflict`() = runBlocking {
        assertEquals(HttpStatusCode.Accepted, admit(previewId(), "reused").status)

        val conflicting = admit(previewId(), "reused")

        assertEquals(HttpStatusCode.Conflict, conflicting.status, conflicting.bodyAsText())
        assertContains(conflicting.bodyAsText(), "OCR_REQUEST_CONFLICT")
    }

    @Test
    fun `an unavailable method is reported with its reason and invalid ids are bad requests`() = runBlocking {
        val unavailable = preview("llm:missing-profile")
        val invalid = preview("llm:")

        assertEquals(HttpStatusCode.Conflict, unavailable.status, unavailable.bodyAsText())
        assertContains(unavailable.bodyAsText(), "METHOD_UNAVAILABLE")
        assertEquals(HttpStatusCode.BadRequest, invalid.status, invalid.bodyAsText())
    }

    @Test
    fun `an unknown document remains not found`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/collections/default/documents/does-not-exist/ocr/preview",
            """{"method":"tesseract"}""",
            Credential.CSRF,
        )

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "NOT_FOUND")
    }
}
