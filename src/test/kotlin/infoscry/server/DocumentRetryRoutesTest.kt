package infoscry.server

import infoscry.document.RetryPrerequisites
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.extract.ExtractionSettings
import infoscry.extract.TesseractOcr
import infoscry.jobs.RetryJobPayload
import infoscry.storage.Instants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * The retry API over a real socket: what admission accepts, what it refuses and why, and what a second
 * click on the same document does.
 *
 * The route's contract has two halves that have to hold together. The request shape is exclusive — named
 * documents or every eligible one, never both, and never neither — which is a typed 400. The decisions
 * about individual documents are not errors: they come back with the accepted job ids and, for each
 * document that was not accepted, the sentence that says why. That is what lets one request queue nine
 * attempts and explain the tenth.
 */
class DocumentRetryRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-retry-routes")
        harness = ApiTestServer(dataDir, retryPrerequisites = availablePrerequisites())
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `an eligible document is accepted as a retry job and moves to queued`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = seedDocument(collectionId, "failed.txt", "failed once", DocumentStatus.FAILED)

        val admission = admitRetry(collectionId.value, listOf(document.value))

        assertEquals(collectionId.value, admission.collectionId)
        assertEquals(1, admission.acceptedJobIds.size)
        assertTrue(admission.rejected.isEmpty())

        // The document keeps its identity and gains the attempt's own state: the listing is where the user
        // sees that the retry is queued.
        assertEquals(DocumentStatus.QUEUED, harness.context.documents.get(document)!!.status)

        val job = jobsOfType(JobType.RETRY).single()
        assertEquals(admission.acceptedJobIds.single(), job.id.value)
        assertEquals(JobState.QUEUED, job.state)
        assertEquals(1, job.total)
        assertEquals(collectionId.value, job.collectionId?.value)

        val payload = RetryJobPayload.decode(harness.context.jobs.get(infoscry.domain.JobId(job.id.value))!!.payload)
        assertEquals(listOf(document.value), payload.documentIds)
        assertEquals(collectionId.value, payload.collectionId)
    }

    @Test
    fun `a request that does not say what to retry, or says it twice, is a typed refusal`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = seedDocument(collectionId, "failed.txt", "failed once", DocumentStatus.FAILED)

        val both = requestRetry(collectionId.value, """{"documentIds":["${document.value}"],"allEligible":true}""")
        val neither = requestRetry(collectionId.value, """{}""")
        val empty = requestRetry(collectionId.value, """{"documentIds":[]}""")
        val tooMany = requestRetry(
            collectionId.value,
            """{"documentIds":[${(1..201).joinToString(",") { "\"doc-$it\"" }}]}""",
        )
        val blank = requestRetry(collectionId.value, """{"documentIds":[""]}""")
        val uncredentialed = harness.request(
            HttpMethod.Post,
            retryPath(collectionId.value),
            body = """{"documentIds":["${document.value}"]}""",
            credential = Credential.NONE,
        )

        for ((label, response) in mapOf(
            "both" to both,
            "neither" to neither,
            "empty" to empty,
            "too many" to tooMany,
            "blank" to blank,
        )) {
            assertEquals(HttpStatusCode.BadRequest, response.status, "$label: ${response.bodyAsText()}")
            assertContains(response.bodyAsText(), "INVALID_REQUEST", message = "$label")
        }
        assertEquals(HttpStatusCode.Unauthorized, uncredentialed.status)

        // Nothing was admitted by any of the refused shapes, and the document is exactly as it was.
        assertTrue(jobsOfType(JobType.RETRY).isEmpty(), "a refused request may not queue an attempt")
        assertEquals(DocumentStatus.FAILED, harness.context.documents.get(document)!!.status)
    }

    @Test
    fun `an unknown id and another collection's id are refused without confirming either`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val other = CollectionId(createCollection("Elsewhere"))
        val foreign = seedDocument(other, "foreign.txt", "another collection", DocumentStatus.FAILED)

        val admission = admitRetry(collectionId.value, listOf("does-not-exist", foreign.value))

        assertTrue(admission.acceptedJobIds.isEmpty())
        assertEquals(
            listOf("does-not-exist", foreign.value),
            admission.rejected.map { it.documentId },
            "the refusal comes back per document rather than as one answer for the request",
        )
        assertEquals(
            1,
            admission.rejected.map { it.reason }.distinct().size,
            "an unknown id and another collection's id must not be distinguishable: ${admission.rejected}",
        )
        assertEquals(DocumentStatus.FAILED, harness.context.documents.get(foreign)!!.status)
        assertTrue(jobsOfType(JobType.RETRY).isEmpty())
    }

    @Test
    fun `a document that is not failed, cancelled or waiting for a tool is refused`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val completed = seedDocument(collectionId, "done.txt", "read bytes", DocumentStatus.COMPLETE)
        val warned =
            seedDocument(collectionId, "warned.txt", "warned bytes", DocumentStatus.COMPLETE_WITH_WARNINGS)
        val running = seedDocument(collectionId, "running.txt", "in progress bytes", DocumentStatus.EXTRACTING)

        val admission = admitRetry(collectionId.value, listOf(completed.value, warned.value, running.value))

        assertTrue(admission.acceptedJobIds.isEmpty())
        val byDocument = admission.rejected.associateBy { it.documentId }
        assertContains(byDocument.getValue(completed.value).reason, "nothing to retry")
        assertContains(byDocument.getValue(warned.value).reason, "nothing to retry")
        assertContains(byDocument.getValue(running.value).reason, "already queued or running")
        assertEquals(DocumentStatus.EXTRACTING, harness.context.documents.get(running)!!.status)
    }

    @Test
    fun `a document an import still holds is refused rather than raced`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = seedDocument(collectionId, "held.txt", "held by an import", DocumentStatus.FAILED)

        // A queued import whose item already names this document: a resume would read the same bytes, so the
        // retry has to lose to it rather than double-process the document.
        val job = harness.context.jobs.enqueue(type = JobType.IMPORT, collectionId = collectionId, total = 1)
        harness.context.importItems.queue(job.id, "item-1", "/tmp/held.txt")
        harness.context.importItems.attachDocument(job.id, "item-1", document)

        val admission = admitRetry(collectionId.value, listOf(document.value))

        assertTrue(admission.acceptedJobIds.isEmpty())
        assertContains(admission.rejected.single().reason, "already queued or running")
        assertEquals(DocumentStatus.FAILED, harness.context.documents.get(document)!!.status)
    }

    @Test
    fun `a document a deletion has targeted is refused`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = seedDocument(collectionId, "doomed.txt", "being deleted", DocumentStatus.FAILED)
        recordDocumentDeletionTarget(harness, collectionId, document)

        val admission = admitRetry(collectionId.value, listOf(document.value))

        assertTrue(admission.acceptedJobIds.isEmpty())
        assertContains(admission.rejected.single().reason, "being deleted")
        assertEquals(DocumentStatus.FAILED, harness.context.documents.get(document)!!.status)
    }

    @Test
    fun `a missing prerequisite is a refusal with its remedy, never a job`() = runBlocking {
        Files.createTempDirectory("infoscry-retry-tools").let { toolsDir ->
            try {
                ApiTestServer(
                    toolsDir,
                    retryPrerequisites = availablePrerequisites(ocrTool = false, ebookTool = false, model = false),
                ).use { server ->
                    server.createCollection("Nightfall", Credential.BEARER)
                    val collectionId = CollectionId(server.collectionIdOf("Nightfall"))
                    val needsOcr = seedDocument(
                        server,
                        collectionId,
                        "scan.pdf",
                        "a scanned page needs a tool",
                        DocumentStatus.NEEDS_TOOL,
                        errorCode = TesseractOcr.NEEDS_TESSERACT_CODE,
                    )
                    // Distinct bytes, because identical bytes in one collection are one document.
                    val converterOnly = seedDocument(
                        server,
                        collectionId,
                        "book.mobi",
                        "a kindle book needs a tool",
                        DocumentStatus.FAILED,
                    )
                    setMediaType(server, converterOnly, "application/x-mobipocket-ebook")
                    val plain = seedDocument(server, collectionId, "plain.txt", "plain text", DocumentStatus.FAILED)

                    val admission = runBlocking {
                        ApiJson.decodeFromString<RetryDocumentsResponse>(
                            server.request(
                                HttpMethod.Post,
                                retryPath(collectionId.value),
                                body = """{"documentIds":["${needsOcr.value}","${converterOnly.value}","${plain.value}"]}""",
                                credential = Credential.BEARER,
                            ).bodyAsText(),
                        )
                    }

                    assertTrue(admission.acceptedJobIds.isEmpty())
                    val byDocument = admission.rejected.associateBy { it.documentId }
                    assertContains(byDocument.getValue(needsOcr.value).reason, "Tesseract")
                    assertContains(byDocument.getValue(needsOcr.value).reason, "install")
                    assertContains(byDocument.getValue(converterOnly.value).reason, "converter")
                    assertContains(byDocument.getValue(plain.value).reason, "embedding model")
                    assertTrue(
                        jobsOfType(server, JobType.RETRY).isEmpty(),
                        "an unavailable prerequisite may not be papered over by a queued job",
                    )
                    assertEquals(DocumentStatus.FAILED, server.context.documents.get(plain)!!.status)
                }
            } finally {
                toolsDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `two clicks at once queue exactly one attempt`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val document = seedDocument(collectionId, "raced.txt", "raced", DocumentStatus.FAILED)
        val body = """{"documentIds":["${document.value}"]}"""

        val responses = listOf(
            async { harness.request(HttpMethod.Post, retryPath(collectionId.value), body, Credential.BEARER) },
            async { harness.request(HttpMethod.Post, retryPath(collectionId.value), body, Credential.BEARER) },
        ).awaitAll()

        val admissions = responses.map { ApiJson.decodeFromString<RetryDocumentsResponse>(it.bodyAsText()) }
        assertEquals(1, admissions.sumOf { it.acceptedJobIds.size }, "two clicks are one attempt, not two")
        assertEquals(1, admissions.sumOf { it.rejected.size }, "the second click is told why it did nothing")
        assertContains(admissions.single { it.rejected.isNotEmpty() }.rejected.single().reason, "already queued or running")
        assertEquals(1, jobsOfType(JobType.RETRY).size)
        assertEquals(DocumentStatus.QUEUED, harness.context.documents.get(document)!!.status)
    }

    @Test
    fun `allEligible retries every eligible document and leaves the finished ones alone`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        // Every seeded document carries its own bytes: identical bytes in one collection are one document.
        val failed = seedDocument(collectionId, "failed.txt", "failed bytes", DocumentStatus.FAILED)
        val cancelled = seedDocument(collectionId, "cancelled.txt", "cancelled bytes", DocumentStatus.CANCELLED)
        val needsTool = seedDocument(collectionId, "tool.pdf", "tool bytes", DocumentStatus.NEEDS_TOOL)
        val completed = seedDocument(collectionId, "done.txt", "read bytes", DocumentStatus.COMPLETE)
        val warned = seedDocument(collectionId, "warned.txt", "warned bytes", DocumentStatus.COMPLETE_WITH_WARNINGS)

        val response = harness.request(
            HttpMethod.Post,
            retryPath(collectionId.value),
            body = """{"allEligible":true}""",
            credential = Credential.BEARER,
        )
        val admission = ApiJson.decodeFromString<RetryDocumentsResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(1, admission.acceptedJobIds.size)
        assertTrue(admission.rejected.isEmpty())
        val job = jobsOfType(JobType.RETRY).single()
        // Every eligible document, across whatever pages the listing would have shown them on.
        assertEquals(3, job.total)
        val payload = RetryJobPayload.decode(harness.context.jobs.get(infoscry.domain.JobId(job.id.value))!!.payload)
        assertEquals(
            setOf(failed.value, cancelled.value, needsTool.value),
            payload.documentIds.toSet(),
        )
        assertEquals(
            setOf(DocumentStatus.QUEUED),
            setOf(failed, cancelled, needsTool)
                .map { harness.context.documents.get(it)!!.status }
                .toSet(),
        )
        assertEquals(DocumentStatus.COMPLETE, harness.context.documents.get(completed)!!.status)
        assertEquals(DocumentStatus.COMPLETE_WITH_WARNINGS, harness.context.documents.get(warned)!!.status)
    }

    @Test
    fun `allEligible with nothing eligible queues nothing`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        seedDocument(collectionId, "done.txt", "read bytes", DocumentStatus.COMPLETE)

        val response = harness.request(
            HttpMethod.Post,
            retryPath(collectionId.value),
            body = """{"allEligible":true}""",
            credential = Credential.BEARER,
        )
        val admission = ApiJson.decodeFromString<RetryDocumentsResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(admission.acceptedJobIds.isEmpty())
        assertTrue(admission.rejected.isEmpty())
        assertTrue(jobsOfType(JobType.RETRY).isEmpty())
    }

    @Test
    fun `allEligible reaches eligible documents beyond the first listing page`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        // Distinct bytes, because identical bytes in one collection are one document.
        val eligible = (1..120).map { index ->
            seedDocument(collectionId, "failed-$index.txt", "failed bytes $index", DocumentStatus.FAILED)
        }
        val completed = seedDocument(collectionId, "done.txt", "read bytes", DocumentStatus.COMPLETE)

        // The reader sees 50 of these at a time, so 120 eligible documents are 70 beyond the first page.
        val firstPage = ApiJson.decodeFromString<DocumentsResponse>(
            harness.get("/api/collections/${collectionId.value}/documents").bodyAsText(),
        )
        assertEquals(50, firstPage.documents.size)
        assertEquals(121, firstPage.total)

        val response = requestRetry(collectionId.value, """{"allEligible":true}""")
        val admission = ApiJson.decodeFromString<RetryDocumentsResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(1, admission.acceptedJobIds.size)
        assertTrue(admission.rejected.isEmpty())
        val job = jobsOfType(JobType.RETRY).single()
        assertEquals(120, job.total, "collection-wide selection is not the page a reader is looking at")
        val payload = RetryJobPayload.decode(harness.context.jobs.get(infoscry.domain.JobId(job.id.value))!!.payload)
        assertEquals(eligible.map { it.value }.toSet(), payload.documentIds.toSet())
        assertEquals(DocumentStatus.COMPLETE, harness.context.documents.get(completed)!!.status)
    }

    @Test
    fun `collection-wide selection applies the per-document safeguards to each admission`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val free = seedDocument(collectionId, "free.txt", "free bytes", DocumentStatus.FAILED)
        val held = seedDocument(collectionId, "held.txt", "held bytes", DocumentStatus.FAILED)
        val doomed = seedDocument(collectionId, "doomed.txt", "doomed bytes", DocumentStatus.CANCELLED)

        // Changing eligibility inside one collection-wide pass: an import that still names one document and a
        // deletion that has already targeted another, while the third is free to be read again.
        val pendingImport = harness.context.jobs.enqueue(type = JobType.IMPORT, collectionId = collectionId, total = 1)
        harness.context.importItems.queue(pendingImport.id, "item-1", "/tmp/held.txt")
        harness.context.importItems.attachDocument(pendingImport.id, "item-1", held)
        recordDocumentDeletionTarget(harness, collectionId, doomed)

        val response = requestRetry(collectionId.value, """{"allEligible":true}""")
        val admission = ApiJson.decodeFromString<RetryDocumentsResponse>(response.bodyAsText())

        assertEquals(1, admission.acceptedJobIds.size)
        val byDocument = admission.rejected.associateBy { it.documentId }
        assertEquals(setOf(held.value, doomed.value), byDocument.keys)
        assertContains(byDocument.getValue(held.value).reason, "already queued or running")
        assertContains(byDocument.getValue(doomed.value).reason, "being deleted")
        val job = jobsOfType(JobType.RETRY).single()
        assertEquals(1, job.total)
        assertEquals(
            listOf(free.value),
            RetryJobPayload.decode(harness.context.jobs.get(infoscry.domain.JobId(job.id.value))!!.payload).documentIds,
        )
        assertEquals(DocumentStatus.QUEUED, harness.context.documents.get(free)!!.status)
        assertEquals(DocumentStatus.FAILED, harness.context.documents.get(held)!!.status)
        assertEquals(DocumentStatus.CANCELLED, harness.context.documents.get(doomed)!!.status)
    }

    @Test
    fun `two Retry all calls at once queue one attempt for the same documents`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val documents = (1..5).map { index ->
            seedDocument(collectionId, "failed-$index.txt", "failed bytes $index", DocumentStatus.FAILED)
        }

        val responses = listOf(
            async { requestRetry(collectionId.value, """{"allEligible":true}""") },
            async { requestRetry(collectionId.value, """{"allEligible":true}""") },
        ).awaitAll()
        val admissions = responses.map { ApiJson.decodeFromString<RetryDocumentsResponse>(it.bodyAsText()) }

        // Both calls are answered, and the documents end up with exactly one attempt between them: the second
        // call finds the work already admitted rather than queueing a second pass over the same bytes.
        assertTrue(responses.all { it.status == HttpStatusCode.Accepted })
        assertEquals(1, admissions.sumOf { it.acceptedJobIds.size })
        assertEquals(1, jobsOfType(JobType.RETRY).size)
        assertEquals(5, jobsOfType(JobType.RETRY).single().total)
        assertTrue(documents.all { harness.context.documents.get(it)!!.status == DocumentStatus.QUEUED })
    }

    @Test
    fun `allEligible that can run nothing is answered, not counted as a queued attempt`() = runBlocking {
        Files.createTempDirectory("infoscry-retry-all-tools").let { toolsDir ->
            try {
                ApiTestServer(toolsDir, retryPrerequisites = availablePrerequisites(model = false)).use { server ->
                    server.createCollection("Nightfall", Credential.BEARER)
                    val collectionId = CollectionId(server.collectionIdOf("Nightfall"))
                    val failed = seedDocument(server, collectionId, "failed.txt", "failed bytes", DocumentStatus.FAILED)
                    val cancelled =
                        seedDocument(server, collectionId, "cancelled.txt", "cancelled bytes", DocumentStatus.CANCELLED)

                    val response = server.request(
                        HttpMethod.Post,
                        retryPath(collectionId.value),
                        body = """{"allEligible":true}""",
                        credential = Credential.BEARER,
                    )
                    val admission = ApiJson.decodeFromString<RetryDocumentsResponse>(response.bodyAsText())

                    assertEquals(HttpStatusCode.Accepted, response.status)
                    assertTrue(
                        admission.acceptedJobIds.isEmpty(),
                        "nothing may be reported as queued when no eligible document could run",
                    )
                    val byDocument = admission.rejected.associateBy { it.documentId }
                    assertEquals(setOf(failed.value, cancelled.value), byDocument.keys)
                    byDocument.values.forEach { assertContains(it.reason, "embedding model") }
                    assertTrue(jobsOfType(server, JobType.RETRY).isEmpty())
                    assertEquals(DocumentStatus.FAILED, server.context.documents.get(failed)!!.status)
                    assertEquals(DocumentStatus.CANCELLED, server.context.documents.get(cancelled)!!.status)
                }
            } finally {
                toolsDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `the details read offers retry exactly where it is plausible`() = runBlocking {
        val collectionId = CollectionId(createCollection("Nightfall"))
        val failed = seedDocument(collectionId, "failed.txt", "failed bytes", DocumentStatus.FAILED)
        val completed = seedDocument(collectionId, "done.txt", "read bytes", DocumentStatus.COMPLETE)

        val failedDetail = ApiJson.decodeFromString<DocumentDetail>(
            harness.get("/api/collections/${collectionId.value}/documents/${failed.value}").bodyAsText(),
        )
        val completedDetail = ApiJson.decodeFromString<DocumentDetail>(
            harness.get("/api/collections/${collectionId.value}/documents/${completed.value}").bodyAsText(),
        )

        assertTrue(failedDetail.retryEligible, "a failed document is what Retry is for")
        assertTrue(!completedDetail.retryEligible, "a successful document is not offered Retry")
    }

    @Test
    fun `an unknown collection is the same not-found answer as everywhere else`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            retryPath("no-such-collection"),
            body = """{"allEligible":true}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `settings that moved while the probe ran are reconciled at admission`() = runBlocking {
        val racedDir = Files.createTempDirectory("infoscry-retry-settings-race")
        try {
            lateinit var server: ApiTestServer
            var probes = 0
            // The window: the probe is asked what this machine offers before admission holds the shared
            // mutation permit, and an OCR-language change admitted in between would otherwise be enqueued
            // with the settings the reader no longer has. The change is made through the store, which is the
            // same write the settings route's own permit makes.
            server = ApiTestServer(
                racedDir,
                retryPrerequisites = { collection ->
                    probes++
                    if (probes == 1) server.context.collections.updateOcrLanguages(collection.id, "deu+eng")
                    RetryPrerequisites(
                        settings = ExtractionSettings(ocrLanguages = collection.ocrLanguages),
                        ocrToolAvailable = true,
                        ebookToolAvailable = true,
                        embeddingModelAvailable = true,
                    )
                },
            )
            server.use { live ->
                live.createCollection("Nightfall", Credential.BEARER)
                val collectionId = CollectionId(live.collectionIdOf("Nightfall"))
                val document = seedDocument(live, collectionId, "failed.txt", "failed bytes", DocumentStatus.FAILED)

                val response = live.request(
                    HttpMethod.Post,
                    retryPath(collectionId.value),
                    body = """{"documentIds":["${document.value}"]}""",
                    credential = Credential.BEARER,
                )
                val admission = ApiJson.decodeFromString<RetryDocumentsResponse>(response.bodyAsText())

                assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
                assertEquals(1, admission.acceptedJobIds.size)
                assertEquals(2, probes, "the changed settings are probed again under the permit")
                val job = live.context.jobs.get(infoscry.domain.JobId(admission.acceptedJobIds.single()))!!
                assertEquals(
                    "deu+eng",
                    RetryJobPayload.decode(job.payload).settings.ocrLanguages,
                    "the queued attempt names the settings that were admitted, not the ones the probe saw",
                )
            }
        } finally {
            racedDir.toFile().deleteRecursively()
        }
    }

    private suspend fun createCollection(name: String): String {
        harness.createCollection(name, Credential.BEARER)
        return harness.collectionIdOf(name)
    }

    /** A managed document in a chosen state, without running an import attempt for it. */
    private fun seedDocument(
        collectionId: CollectionId,
        name: String,
        content: String,
        status: DocumentStatus,
        errorCode: String? = null,
    ): DocumentId = seedDocument(harness, collectionId, name, content, status, errorCode)

    private fun seedDocument(
        server: ApiTestServer,
        collectionId: CollectionId,
        name: String,
        content: String,
        status: DocumentStatus,
        errorCode: String? = null,
    ): DocumentId {
        val directory = Files.createDirectories(server.dataDir.resolve("sources"))
        val source = directory.resolve(name)
        Files.writeString(source, content)
        val imported = server.context.library.importFile(collectionId, source)
        server.context.documents.updateStatus(imported.document.id, status, errorCode)
        return imported.document.id
    }

    private suspend fun admitRetry(collectionId: String, documentIds: List<String>): RetryDocumentsResponse {
        val body = """{"documentIds":[${documentIds.joinToString(",") { "\"$it\"" }}]}"""
        val response = requestRetry(collectionId, body)
        check(response.status == HttpStatusCode.Accepted) {
            "admitting a retry should be accepted, was ${response.status}: ${response.bodyAsText()}"
        }
        return ApiJson.decodeFromString(response.bodyAsText())
    }

    private suspend fun requestRetry(collectionId: String, body: String) =
        harness.request(HttpMethod.Post, retryPath(collectionId), body, Credential.BEARER)

    private suspend fun jobsOfType(type: JobType): List<JobApiView> = jobsOfType(harness, type)

    private suspend fun jobsOfType(server: ApiTestServer, type: JobType): List<JobApiView> =
        ApiJson.decodeFromString<JobsResponse>(server.get("/api/jobs").bodyAsText())
            .jobs
            .filter { it.type == type }

    private fun retryPath(collectionId: String): String = "/api/collections/$collectionId/documents/retry"

    private companion object {

        /** A machine that has everything a retry could need, unless a test says otherwise. */
        fun availablePrerequisites(
            ocrTool: Boolean = true,
            ebookTool: Boolean = true,
            model: Boolean = true,
        ): suspend (Collection) -> RetryPrerequisites = { collection ->
            RetryPrerequisites(
                settings = ExtractionSettings(ocrLanguages = collection.ocrLanguages),
                ocrToolAvailable = ocrTool,
                ebookToolAvailable = ebookTool,
                embeddingModelAvailable = model,
            )
        }
    }
}

/** Rewrites one document's media type, for the formats only the external converter can read. */
internal fun setMediaType(server: ApiTestServer, documentId: DocumentId, mediaType: String) {
    server.context.database.transaction { connection ->
        connection.prepareStatement("UPDATE documents SET media_type = ? WHERE id = ?").use { statement ->
            statement.setString(1, mediaType)
            statement.setString(2, documentId.value)
            statement.executeUpdate()
        }
    }
    assertEquals(mediaType, server.context.documents.get(documentId)!!.mediaType)
}

/**
 * Records a document deletion that has been admitted but has not run, on an already-open server.
 *
 * The target row is the durable guard a retry has to obey; the rows are written directly because the
 * deletion machine's own phases would remove the document, which is a different test.
 */
internal fun recordDocumentDeletionTarget(
    server: ApiTestServer,
    collectionId: CollectionId,
    documentId: DocumentId,
) {    val operationId = UUID.randomUUID().toString()
    val now = Instants.now()
    server.context.database.transaction { connection ->
        connection.prepareStatement(
            "INSERT INTO deletion_operations (id, collection_id, collection_name, trash_basename, " +
                "managed_originals_existed, phase, kind, created_at, updated_at) VALUES (?, ?, ?, ?, 1, " +
                "'PREPARED', 'DOCUMENT', ?, ?)",
        ).use { statement ->
            statement.setString(1, operationId)
            statement.setString(2, collectionId.value)
            statement.setString(3, "seeded")
            statement.setString(4, ".deleted-${UUID.randomUUID()}")
            statement.setString(5, now)
            statement.setString(6, now)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO document_deletion_targets (operation_id, document_id, managed_existed) VALUES (?, ?, 1)",
        ).use { statement ->
            statement.setString(1, operationId)
            statement.setString(2, documentId.value)
            statement.executeUpdate()
        }
    }
    assertNotNull(server.context.documents.get(documentId), "the guard is about a document that still exists")
    assertTrue(server.context.documents.isDeletionTarget(documentId))
}
