package infoscry.ocr

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.document.RestoreFixtures
import infoscry.jobs.seedCollectionWithId
import infoscry.jobs.RescanJobPayload
import infoscry.storage.PageApproval
import infoscry.storage.JobStore
import infoscry.storage.RevisionState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class StaleOcrStateCleanupTest {
    private lateinit var dataDir: Path
    private val collection = CollectionId("default")

    @BeforeTest
    fun createDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-stale-ocr-cleanup")
        AppContext.open(dataDir).use { it.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    private fun open(): AppContext = AppContext.open(dataDir)

    @Test
    fun `a paused import job becomes cancelled`() {
        open().use { context ->
            val job = context.jobs.enqueue(JobType.IMPORT, collection)
            context.jobs.claim(job.id)
            context.jobs.progress(job.id, stage = "awaiting-approval")
            context.jobs.complete(job.id)
            markLegacyApprovalWait(context, job.id.value)

            val report = StaleOcrStateCleanup(context.jobs, context.ocrOperations, context.revisions, context.documents).run()

            assertEquals(JobState.CANCELLED, context.jobs.get(job.id)?.state)
            assertEquals(1, report.jobsCancelled)
        }
    }

    @Test
    fun `an operation left in a working stage fails and releases its document`() {
        open().use { context ->
            val document = runBlocking {
                context.library.importFile(collection, writeText("working.txt", "original text")).document
            }
            val operation = admitOperation(context, document.id)
            context.ocrOperations.advance(operation.operationId, OcrOperationStage.OCR)

            val report = StaleOcrStateCleanup(context.jobs, context.ocrOperations, context.revisions, context.documents).run()

            val failed = assertNotNull(context.ocrOperations.operation(operation.operationId))
            assertEquals(OcrOperationStage.FAILED, failed.stage)
            assertEquals("INTERRUPTED", failed.errorCode)
            assertNull(context.ocrOperations.activeOperation(document.id))
            assertEquals(infoscry.domain.DocumentStatus.FAILED, context.documents.get(document.id)?.status)
            assertEquals(1, report.operationsFailed)
        }
    }

    @Test
    fun `a complete operation with pending pages withdraws its candidate`() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val candidate = RestoreFixtures.stage(
                context,
                fixture.documentId,
                fixture.revisionIds.last(),
                listOf("proposal"),
                fixture.unitIds,
                approval = PageApproval.PENDING,
            )
            val operation = admitOperation(context, fixture.documentId)
            context.ocrOperations.recordCandidateRevision(operation.operationId, candidate)
            context.ocrOperations.recordProgress(operation.operationId, pendingReview = 1)
            context.ocrOperations.finish(operation.operationId, OcrOperationStage.COMPLETE)
            context.documents.updateStatus(fixture.documentId, infoscry.domain.DocumentStatus.NEEDS_REVIEW)

            val report = StaleOcrStateCleanup(context.jobs, context.ocrOperations, context.revisions, context.documents).run()

            assertEquals(RevisionState.WITHDRAWN, context.revisions.revision(candidate)?.state)
            assertEquals(0, context.ocrOperations.operation(operation.operationId)?.pendingReviewCount)
            assertEquals(infoscry.domain.DocumentStatus.COMPLETE, context.documents.get(fixture.documentId)?.status)
            assertEquals(1, report.candidatesWithdrawn)
        }
    }

    @Test
    fun `a completed pending count without a candidate is still released`() {
        open().use { context ->
            val document = runBlocking {
                context.library.importFile(collection, writeText("missing-candidate.txt", "original text")).document
            }
            val operation = admitOperation(context, document.id)
            context.ocrOperations.recordProgress(operation.operationId, pendingReview = 1)
            context.ocrOperations.finish(operation.operationId, OcrOperationStage.COMPLETE)
            context.documents.updateStatus(document.id, infoscry.domain.DocumentStatus.NEEDS_REVIEW)

            val report = StaleOcrStateCleanup(context.jobs, context.ocrOperations, context.revisions, context.documents).run()

            assertEquals(0, context.ocrOperations.operation(operation.operationId)?.pendingReviewCount)
            assertEquals(false, context.ocrOperations.operation(operation.operationId)?.holdsDocument)
            assertEquals(infoscry.domain.DocumentStatus.COMPLETE, context.documents.get(document.id)?.status)
            assertEquals(1, report.candidatesWithdrawn)
        }
    }

    @Test
    fun `running cleanup twice changes nothing the second time`() {
        open().use { context ->
            val job = context.jobs.enqueue(JobType.IMPORT, collection)
            context.jobs.claim(job.id)
            context.jobs.progress(job.id, stage = "awaiting-approval")
            context.jobs.complete(job.id)
            markLegacyApprovalWait(context, job.id.value)

            StaleOcrStateCleanup(context.jobs, context.ocrOperations, context.revisions, context.documents).run()
            val second = StaleOcrStateCleanup(context.jobs, context.ocrOperations, context.revisions, context.documents).run()

            assertEquals(CleanupReport(0, 0, 0), second)
        }
    }

    @Test
    fun `a live running job is untouched`() {
        open().use { context ->
            val document = runBlocking {
                context.library.importFile(collection, writeText("live.txt", "original text")).document
            }
            val job = context.jobs.enqueue(JobType.RESCAN, collection)
            val operation = admitOperation(context, document.id)
            context.jobs.claim(job.id)
            context.ocrOperations.advance(operation.operationId, OcrOperationStage.OCR)

            val report = StaleOcrStateCleanup(
                context.jobs,
                context.ocrOperations,
                context.revisions,
                context.documents,
                hasLiveAttempt = { true },
            ).run()

            assertEquals(OcrOperationStage.OCR, context.ocrOperations.operation(operation.operationId)?.stage)
            assertEquals(JobState.RUNNING, context.jobs.get(job.id)?.state)
            assertEquals(CleanupReport(0, 0, 0), report)
        }
    }

    @Test
    fun `a running job left by a killed process does not keep its operation alive at startup`() {
        open().use { context ->
            val document = runBlocking {
                context.library.importFile(collection, writeText("interrupted.txt", "original text")).document
            }
            val job = context.jobs.enqueue(JobType.RESCAN, collection)
            val operation = admitOperation(context, document.id, jobId = job.id.value)
            context.jobs.claim(job.id)
            context.ocrOperations.advance(operation.operationId, OcrOperationStage.OCR)
            val activeRevision = context.revisions.activeRevisionId(document.id)

            val report = StaleOcrStateCleanup(
                context.jobs,
                context.ocrOperations,
                context.revisions,
                context.documents,
            ).run()

            assertEquals(OcrOperationStage.FAILED, context.ocrOperations.operation(operation.operationId)?.stage)
            assertEquals("INTERRUPTED", context.ocrOperations.operation(operation.operationId)?.errorCode)
            assertEquals(JobState.FAILED, context.jobs.get(job.id)?.state)
            assertEquals("INTERRUPTED", context.jobs.get(job.id)?.errorCode)
            assertEquals(activeRevision, context.revisions.activeRevisionId(document.id))
            assertEquals(1, report.operationsFailed)
        }
    }

    @Test
    fun `startup rolls forward an operation whose candidate was published before the process died`() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val job = context.jobs.enqueue(JobType.RESCAN, collection)
            val operation = admitOperation(context, fixture.documentId, jobId = job.id.value)
            val candidate = RestoreFixtures.stage(
                context,
                fixture.documentId,
                operation.baseRevisionId,
                listOf(RestoreFixtures.STABLE_PAGE, "replacement published before crash"),
                fixture.unitIds,
            )
            context.ocrOperations.recordCandidateRevision(operation.operationId, candidate)
            runBlocking {
                context.revisionPublication.publish(fixture.documentId, operation.baseRevisionId, candidate)
            }
            val published = assertNotNull(context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(candidate, published)
            context.ocrOperations.advance(operation.operationId, OcrOperationStage.INDEXING)
            context.documents.updateStatus(fixture.documentId, infoscry.domain.DocumentStatus.INDEXING)
            context.jobs.claim(job.id)

            StaleOcrStateCleanup(context.jobs, context.ocrOperations, context.revisions, context.documents).run()

            assertEquals(OcrOperationStage.COMPLETE, context.ocrOperations.operation(operation.operationId)?.stage)
            assertEquals(JobState.COMPLETE, context.jobs.get(job.id)?.state)
            assertEquals(infoscry.domain.DocumentStatus.COMPLETE, context.documents.get(fixture.documentId)?.status)
            assertEquals(published, context.revisions.activeRevisionId(fixture.documentId))
        }
    }

    @Test
    fun `startup repairs a terminal interrupted operation after a split legacy write`() {
        open().use { context ->
            val document = runBlocking {
                context.library.importFile(collection, writeText("split-write.txt", "original text")).document
            }
            val job = context.jobs.enqueue(
                JobType.RESCAN,
                collection,
                RescanJobPayload("default", document.id.value, "placeholder").encode(),
            )
            val operation = admitOperation(context, document.id, jobId = job.id.value)
            context.database.transaction { connection ->
                connection.prepareStatement("UPDATE jobs SET payload = ? WHERE id = ?").use { statement ->
                    statement.setString(1, RescanJobPayload("default", document.id.value, operation.operationId).encode())
                    statement.setString(2, job.id.value)
                    statement.executeUpdate()
                }
            }
            context.jobs.claim(job.id)
            context.ocrOperations.advance(operation.operationId, OcrOperationStage.OCR)
            context.ocrOperations.finish(operation.operationId, OcrOperationStage.FAILED, "INTERRUPTED", "interrupted")
            context.documents.updateStatus(document.id, infoscry.domain.DocumentStatus.OCR)

            StaleOcrStateCleanup(context.jobs, context.ocrOperations, context.revisions, context.documents).run()

            assertEquals(OcrOperationStage.FAILED, context.ocrOperations.operation(operation.operationId)?.stage)
            assertEquals(infoscry.domain.DocumentStatus.FAILED, context.documents.get(document.id)?.status)
            assertEquals("INTERRUPTED", context.documents.get(document.id)?.errorCode)
            assertEquals(JobState.FAILED, context.jobs.get(job.id)?.state)
        }
    }

    private fun writeText(name: String, text: String): Path = dataDir.resolve(name).also { Files.writeString(it, text) }

    /** `complete` clears the stage in current code; restore the exact persisted legacy row for cleanup coverage. */
    private fun markLegacyApprovalWait(context: AppContext, jobId: String) {
        context.database.transaction { connection ->
            connection.prepareStatement("UPDATE jobs SET stage = ? WHERE id = ?").use { statement ->
                statement.setString(1, "awaiting-approval")
                statement.setString(2, jobId)
                statement.executeUpdate()
            }
        }
    }

    private fun admitOperation(
        context: AppContext,
        documentId: infoscry.domain.DocumentId,
        jobId: String? = null,
    ): OcrOperation {
        val document = context.documents.get(documentId)!!
        return context.ocrOperations.admit(
            collectionId = collection.value,
            documentId = documentId,
            baseRevisionId = context.revisions.activeRevisionId(documentId),
            snapshot = OcrSettingsSnapshot(
                engine = OcrEngine.TESSERACT,
                mode = OcrImportMode.FILL_MISSING,
                language = "eng",
                extractorVersion = "test",
            ),
            requestId = "request-${documentId.value}",
            requestHash = "a".repeat(64),
            pageTotal = 1,
            jobId = jobId,
        ).let { admitted ->
            admitted
        }
    }
}
