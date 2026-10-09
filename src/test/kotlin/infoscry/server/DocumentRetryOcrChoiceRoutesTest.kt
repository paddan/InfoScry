package infoscry.server

import infoscry.document.RetryPrerequisites
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobId
import infoscry.domain.JobType
import infoscry.extract.ExtractionSettings
import infoscry.jobs.ImportJobPayload
import infoscry.jobs.RetryJobPayload
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrProfile
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.storage.Instants
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Retry with a chosen OCR method: the choice is validated and admitted exactly as a rescan preview is, frozen
 * into the retry payload, and an unknown external page total is refused before a job is queued.
 *
 * The documents are failed rows with a picture or PDF media type: admission never reads their pixels, so the
 * tests assert on what is queued and what is refused rather than on an engine being installed here.
 */
class DocumentRetryOcrChoiceRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-retry-ocr-choice")
        harness = ApiTestServer(
            dataDir,
            retryPrerequisites = DocumentRetryRoutesTest.availablePrerequisites(),
            rescanEmbedder = { true },
        )
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a retry chooses one method and freezes READ_ALL without editing the collection`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = failedPdf(collectionId, "scan.pdf", "scan bytes")
        val response = requestRetry(collectionId.value,
            """{"documentIds":["${document.value}"],"method":"tesseract","requestId":"new-method"}""")
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val payload = onlyRetryPayload()
        val snapshot = assertNotNull(payload.documentSnapshots[document.value])
        assertEquals(OcrEngine.TESSERACT, snapshot.engine)
        assertEquals(OcrImportMode.READ_ALL, snapshot.mode)
        assertEquals("eng", snapshot.language)
        assertEquals(DocumentStatus.QUEUED, harness.context.documents.get(document)!!.status)
        assertEquals(infoscry.ocr.ReadingMethod.Tesseract, harness.context.collections.get(collectionId)!!.ocrSettings().defaultMethod)
    }

    @Test
    fun `invalid and unavailable methods do not enqueue a retry`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = failedPdf(collectionId, "scan.pdf", "scan bytes")
        val invalid = requestRetry(collectionId.value,
            """{"documentIds":["${document.value}"],"method":"llm:"}""")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        val unavailable = requestRetry(collectionId.value,
            """{"documentIds":["${document.value}"],"method":"llm:missing"}""")
        assertEquals(HttpStatusCode.Conflict, unavailable.status)
        assertTrue(harness.context.jobs.list(100).none { it.type == JobType.RETRY })
    }

    @Test
    fun `omitting method preserves the latest rescan and import readings after the collection default changes`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val rescanned = failedPdf(collectionId, "rescanned.pdf", "rescan bytes")
        val imported = failedPdf(collectionId, "imported.pdf", "import bytes")
        val rescanSnapshot = infoscry.ocr.OcrSettingsSnapshot(language = "swe", engine = OcrEngine.TESSERACT,
            mode = OcrImportMode.READ_ALL, extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION)
        val importSnapshot = rescanSnapshot.copy(language = "deu")
        val operation = harness.context.ocrOperations.admit(
            collectionId = collectionId.value,
            documentId = rescanned,
            baseRevisionId = null,
            snapshot = rescanSnapshot,
            requestId = "failed-rescan",
            requestHash = "a".repeat(64),
            pageTotal = 1,
            jobId = null,
        )
        harness.context.ocrOperations.advance(operation.operationId, infoscry.ocr.OcrOperationStage.FAILED)

        val importPayload = ImportJobPayload(
            collectionId = collectionId.value,
            sources = listOf(dataDir.resolve("imported.pdf").toString()),
            settings = infoscry.extract.ExtractionSettings("deu"),
            ocr = importSnapshot,
        )
        val importJob = harness.context.jobs.enqueue(JobType.IMPORT, collectionId, importPayload.encode())
        harness.context.importItems.queue(importJob.id, "import-item", dataDir.resolve("imported.pdf").toString())
            .also { harness.context.importItems.attachDocument(importJob.id, it.itemKey, imported) }
        harness.context.jobs.claim(importJob.id)
        harness.context.jobs.fail(importJob.id, "TEST_FAILURE", "failed before OCR")

        harness.context.collections.updateOcrSettings(
            collectionId,
            infoscry.ocr.CollectionOcrSettings(language = "fra", defaultMethod = infoscry.ocr.ReadingMethod.Surya),
        )
        val response = requestRetry(collectionId.value,
            """{"documentIds":["${rescanned.value}","${imported.value}"],"requestId":"keep-frozen"}""")
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val latest = harness.context.jobs.list(100).first { it.type == JobType.RETRY }
        val snapshots = RetryJobPayload.decode(latest.payload).documentSnapshots
        assertEquals(rescanSnapshot, snapshots[rescanned.value])
        assertEquals(importSnapshot, snapshots[imported.value])
    }

    @Test
    fun `an external retry refuses an unknown page count with the rescan refusal`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = failedPdf(collectionId, "unreadable.pdf", "not a PDF")
        val profile = externalProfile(measured = true)

        val response = requestRetry(collectionId.value,
            """{"documentIds":["${document.value}"],"method":"llm:${profile.id}"}""")

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), infoscry.document.RescanRefusalException.MANAGED_COPY_MISSING)
        assertContains(response.bodyAsText(), "page count could not be read")
        assertNothingQueued(document)
    }

    // ---- helpers ----

    private suspend fun createCollection(name: String): String {
        harness.createCollection(name, Credential.BEARER)
        return harness.collectionIdOf(name)
    }

    private fun seed(collectionId: CollectionId, name: String, content: String): DocumentId {
        val directory = Files.createDirectories(harness.dataDir.resolve("sources"))
        val source = directory.resolve(name)
        Files.writeString(source, content)
        val imported = harness.context.library.importFile(collectionId, source)
        harness.context.documents.updateStatus(imported.document.id, DocumentStatus.FAILED, "TEST_FAILURE")
        return imported.document.id
    }

    private fun failedPdf(collectionId: CollectionId, name: String, content: String): DocumentId {
        val id = seed(collectionId, name, content)
        setMediaType(harness, id, "application/pdf")
        return id
    }

    /** A published text for the document, written the way an import's publication leaves the rows. */
    private fun publishText(documentId: DocumentId) {
        harness.context.database.transaction { connection ->
            val revisionId = "revision-test-${documentId.value}"
            connection.prepareStatement(
                "INSERT INTO document_revisions (id, document_id, parent_revision_id, state, provenance, created_at) " +
                    "VALUES (?, ?, NULL, 'PUBLISHED', 'IMPORT', ?)",
            ).use { statement ->
                statement.setString(1, revisionId)
                statement.setString(2, documentId.value)
                statement.setString(3, Instants.now())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO document_active_revisions (document_id, revision_id, updated_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, revisionId)
                statement.setString(3, Instants.now())
                statement.executeUpdate()
            }
        }
    }

    private fun externalProfile(measured: Boolean?): OcrProfile {
        val profile = harness.context.ocrProfiles.create(
            name = "Vision transcriber",
            draft = OcrProfileRevisionDraft(
                provider = infoscry.llm.LlmProvider.OPENAI_COMPATIBLE,
                model = "vision-model",
                contextWindow = 32_000,
                maxOutputTokens = 2_048,
                endpoint = "https://transcriber.example.invalid/v1",
                inputPricePerMillion = 1.0,
                outputPricePerMillion = 2.0,
                apiKeyEnvironmentVariable = "HOME",
            ),
            enabled = true,
        )
        if (measured != null) {
            harness.context.ocrProfiles.recordImageCapability(profile.revision.revisionId, measured, Instants.now())
        }
        return profile
    }

    private suspend fun requestRetry(collectionId: String, body: String) = harness.request(
        HttpMethod.Post,
        "/api/collections/$collectionId/documents/retry",
        body,
        Credential.BEARER,
    )

    private fun onlyRetryPayload(): RetryJobPayload {
        val job = harness.context.jobs.list(100).single { it.type == JobType.RETRY }
        return RetryJobPayload.decode(harness.context.jobs.get(JobId(job.id.value))!!.payload)
    }

    private fun assertNothingQueued(document: DocumentId) {
        assertTrue(
            harness.context.jobs.list(100).none { it.type == JobType.RETRY },
            "a refused choice may not queue an attempt",
        )
        assertEquals(DocumentStatus.FAILED, harness.context.documents.get(document)!!.status)
    }
}
