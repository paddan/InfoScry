package infoscry.server

import infoscry.document.RetryPrerequisites
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobId
import infoscry.domain.JobType
import infoscry.extract.ExtractionSettings
import infoscry.jobs.RetryJobPayload
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrProfile
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.storage.Instants
import infoscry.storage.OcrOperationStore
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Retry with a chosen OCR method: the choice is validated and admitted exactly as a rescan preview is, frozen
 * into the retry payload, and a document that already publishes a text is directed to Scan again.
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
    fun `a failed document retried with another mode and language is queued with that choice frozen in its payload`() =
        runBlocking {
            val collectionId = CollectionId(createCollection("Nightfall"))
            val document = failedPdf(collectionId, "scan.pdf", "scan bytes")

            val response = requestRetry(
                collectionId.value,
                """{"documentIds":["${document.value}"],"ocr":{"engine":"TESSERACT","importMode":"CHECK_AND_IMPROVE","language":"swe"}}""",
            )

            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            val payload = onlyRetryPayload()
            val snapshot = assertNotNull(payload.ocr, "the chosen reading is part of what the retry was admitted with")
            assertEquals(OcrEngine.TESSERACT, snapshot.engine)
            assertEquals(OcrImportMode.CHECK_AND_IMPROVE, snapshot.mode)
            assertEquals("swe", snapshot.language)
            // The extraction settings the fingerprint is computed from carry the same reading, so a unit read
            // under another choice is never reused as if it had been read under this one.
            assertEquals("swe", payload.settings.ocrLanguages)
            assertEquals(OcrImportMode.CHECK_AND_IMPROVE, payload.settings.ocrMode)
            assertEquals(OcrEngine.TESSERACT, payload.settings.ocrAttempt?.engine)
            assertEquals(DocumentStatus.QUEUED, harness.context.documents.get(document)!!.status)
            // The collection is not edited by a one-off choice.
            assertEquals(OcrImportMode.FILL_MISSING, harness.context.collections.get(collectionId)!!.ocrImportMode)
            assertEquals("eng", harness.context.collections.get(collectionId)!!.ocrLanguages)
        }

    @Test
    fun `omitting the choice keeps today's behaviour and records no chosen reading`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = failedPdf(collectionId, "scan.pdf", "scan bytes")

        val response = requestRetry(collectionId.value, """{"documentIds":["${document.value}"]}""")

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val payload = onlyRetryPayload()
        assertNull(payload.ocr)
        assertEquals("eng", payload.settings.ocrLanguages)
        assertNull(payload.settings.ocrAttempt)
    }

    @Test
    fun `an engine this machine does not have is refused before anything is queued`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = failedPdf(collectionId, "scan.pdf", "scan bytes")

        val response = requestRetry(
            collectionId.value,
            """{"documentIds":["${document.value}"],"ocr":{"engine":"SURYA"}}""",
        )

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "RESCAN_ENGINE_UNAVAILABLE")
        assertNothingQueued(document)
    }

    @Test
    fun `an external profile that was never measured as image capable is refused before anything is queued`() =
        runBlocking {
            val collectionId = CollectionId(createCollection("Nightfall"))
            val document = failedPdf(collectionId, "scan.pdf", "scan bytes")
            val profile = externalProfile(measured = null)

            val response = requestRetry(
                collectionId.value,
                """{"documentIds":["${document.value}"],"ocr":{"engine":"LLM","transcriptionProfileId":"${profile.id}"}}""",
            )

            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "RESCAN_PROFILE_UNMEASURED")
            assertNothingQueued(document)
        }

    @Test
    fun `external pages of a chosen profile start with no allowance and the retry job's scope is approved like an import's`() =
        runBlocking {
            val collectionId = CollectionId(createCollection("Nightfall"))
            val document = failedPdf(collectionId, "scan.pdf", "scan bytes")
            val profile = externalProfile(measured = true)

            val response = requestRetry(
                collectionId.value,
                """{"documentIds":["${document.value}"],"ocr":{"engine":"LLM","transcriptionProfileId":"${profile.id}"}}""",
            )

            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            val payload = onlyRetryPayload()
            val snapshot = assertNotNull(payload.ocr)
            assertEquals(OcrEngine.LLM, snapshot.engine)
            assertEquals(profile.revision.revisionId, snapshot.transcriptionProfileRevisionId)
            assertEquals(0, snapshot.externalPageLimit, "no external page may leave without an approval")

            val job = harness.context.jobs.list(100).single { it.type == JobType.RETRY }
            val approved = harness.request(
                HttpMethod.Post,
                "/api/jobs/${job.id.value}/approve-external",
                """{"expectedSnapshotHash":"${OcrOperationStore.snapshotHashOf(snapshot)}","maxDistinctPages":3}""",
                Credential.CSRF,
            )
            assertEquals(HttpStatusCode.Accepted, approved.status, approved.bodyAsText())
            assertContains(approved.bodyAsText(), "\"authorizedDistinctPages\":3")
        }

    @Test
    fun `a document that already publishes a text is directed to Scan again with a code`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val published = failedPdf(collectionId, "published.pdf", "has text")
        val plain = failedPdf(collectionId, "plain.pdf", "no text")
        publishText(published)

        val response = requestRetry(
            collectionId.value,
            """{"documentIds":["${published.value}","${plain.value}"],"ocr":{"importMode":"CHECK_AND_IMPROVE"}}""",
        )

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertContains(body, "RETRY_USE_SCAN_AGAIN")
        assertContains(body, "Scan again")
        assertEquals(listOf(plain.value), onlyRetryPayload().documentIds, "only the document without a text is read")
        assertEquals(DocumentStatus.FAILED, harness.context.documents.get(published)!!.status)
    }

    @Test
    fun `a format that has no page images is refused a chosen method with its code`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val text = seed(collectionId, "notes.txt", "plain text")

        val response = requestRetry(
            collectionId.value,
            """{"documentIds":["${text.value}"],"ocr":{"engine":"TESSERACT"}}""",
        )

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "PAGE_IMAGES_UNSUPPORTED")
        assertNothingQueued(text)
    }

    @Test
    fun `the choice applies to a retry of every eligible document too`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val first = failedPdf(collectionId, "one.pdf", "one")
        val second = failedPdf(collectionId, "two.pdf", "two")

        val response = requestRetry(
            collectionId.value,
            """{"allEligible":true,"ocr":{"importMode":"CHECK_AND_IMPROVE"}}""",
        )

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val payload = onlyRetryPayload()
        assertEquals(setOf(first.value, second.value), payload.documentIds.toSet())
        assertEquals(OcrImportMode.CHECK_AND_IMPROVE, payload.ocr?.mode)
    }

    @Test
    fun `an empty choice is the same as none`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = failedPdf(collectionId, "scan.pdf", "scan bytes")

        val response = requestRetry(collectionId.value, """{"documentIds":["${document.value}"],"ocr":{}}""")

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertNull(onlyRetryPayload().ocr)
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
