package infoscry.jobs

import infoscry.domain.Collection
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.Job
import infoscry.extract.ExtractionSettings
import infoscry.library.ManagedLibrary
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Reading documents InfoScry already holds again, from their immutable managed copies.
 *
 * This is deliberately a path of its own rather than a kind of import. An ordinary import copies bytes it
 * has just been handed and classifies them, so a retry routed through it would classify the archive's own
 * copy of a document as a duplicate of that same document and walk away from the unfinished work — which is
 * exactly the outcome the user asked to avoid. A retry therefore starts from document identifiers, reads
 * the managed bytes, and never copies or classifies anything.
 *
 * What it keeps, in the order it matters:
 *
 * - **Identity.** Every document keeps its row, its identifier, its committed units and its citations; only
 *   its status and its searchable content move.
 * - **Compatible work.** Units an earlier attempt committed successfully are reused when the fingerprint
 *   matches and their artifacts still verify. A fingerprint that no longer matches (changed OCR languages,
 *   another tool version, the same bytes) repeats the extraction, and the UI says so.
 * - **Revisited failures.** A unit an earlier attempt failed is read again here: skipping it is what a
 *   crash resume does, and doing that on an explicit Retry would mean Retry did nothing at all.
 * - **A safe failure for lost bytes.** A managed copy that is gone fails that document with a code, rather
 *   than crashing the job or pretending the document was read.
 * - **Honest cancellation.** A cancelled attempt ends its interrupted documents as `CANCELLED`; a shutdown
 *   that merely requeues the job leaves them where they are, because the next process finishes them.
 */
class RetryJobHandler internal constructor(
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val jobs: JobStore,
    private val library: ManagedLibrary,
    private val mutations: MutationCoordinator,
    /**
     * The attempt-level wiring an import and this retry share: the dispatch authority a page is sent through
     * and the review a staged page is judged by come from the same job-owned scope and the same snapshotted
     * reviewer an import admitted with, so Retry is not a second answer to what either may do.
     */
    private val attemptDispatch: AttemptDispatch,
    private val ingest: DocumentIngest,
) : JobHandler {

    override suspend fun handle(job: Job, stage: JobStage) {
        val payload = RetryJobPayload.decode(job.payload)
        val collectionId = payload.collection
        // The payload names the collection, and the attempt refuses to run if the database does not agree:
        // a retry into a collection being deleted must not quietly deposit documents somewhere else.
        val collection = collections.get(collectionId)
            ?: throw IllegalArgumentException("no collection with id ${collectionId.value} exists")
        if (collection.lifecycle != CollectionLifecycle.ACTIVE) throw CollectionNotActiveException(collectionId)

        // The rows are re-read now, so a document a document deletion removed between admission and this
        // attempt is simply not here any more; nothing in this handler creates a document row.
        val documentsToRetry = payload.documentIds
            .map(::DocumentId)
            .mapNotNull { id -> documents.get(id)?.takeIf { it.collectionId == collectionId } }
        // What an earlier run of this same job completed, kept in the job's counters across a pause or a
        // requeue and re-read here, before this attempt reports its own zero. The gate exists because a
        // status-only skip would silently swallow a fresh explicit retry of a document that stands COMPLETE
        // or COMPLETE_WITH_WARNINGS — the runtime-forced re-read and the revisit of a failed unit are exactly
        // such retries — while under this gate only a document a previous run of *this* job already finished
        // is skipped, which is what a resumed job must not repeat.
        val completedByPreviousRun = job.completed
        stage.reportProgress(completed = 0, total = documentsToRetry.size)

        var completed = 0
        try {
            for (document in documentsToRetry) {
                try {
                    // A document an earlier run of this job already finished is not read again: the wait for an
                    // external approval may have paused after an earlier document completed, and re-reading it would
                    // repeat committed work — or reopen a published check-and-improve candidate as review-pending.
                    val finishedByPreviousRun = completedByPreviousRun > 0 &&
                        document.status in ImportJobHandler.FINISHED_STATUSES
                    if (!finishedByPreviousRun) {
                        retryOne(job, collection, payload.settings, payload.ocr, document, stage)
                    }
                } catch (deleted: DocumentBeingDeletedException) {
                    // A deletion won this race. The document stays deleted: nothing here publishes it again,
                    // and the deletion's own phases own whatever is left of it.
                    LOGGER.atInfo()
                        .addKeyValue(DOCUMENT_FIELD, document.id.value)
                        .log("a retry stopped on a document that is being deleted")
                }
                completed++
                stage.reportProgress(completed, documentsToRetry.size)
            }
        } catch (waiting: AttemptAwaitingApproval) {
            // A page would have exceeded this job's external scope, and it was not sent. The waiting state is
            // durable and the attempt ends here rather than reading the next document: no page of any document
            // may be dispatched until a person approves the scope, and the documents this attempt already
            // finished are not revisited when the approved job runs again, so the wait costs no work.
            stage.run(STAGE_RECORD) { jobs.progress(job.id, stage = JobStore.AWAITING_APPROVAL_STAGE) }
            LOGGER.atInfo()
                .addKeyValue(JOB_ID_FIELD, waiting.jobId.value)
                .log("a retry waits for an external page scope to be approved before another page is sent")
            return
        } catch (cancelled: CancellationException) {
            // An interrupted attempt must not leave a document looking permanently active. A shutdown that
            // only requeues the job is not a cancellation — the next process finishes the same document — so
            // the durable cancellation request is what decides which of the two this is.
            if (jobs.get(job.id)?.cancelRequested == true) cancelInterrupted(payload)
            throw cancelled
        }
    }

    /** Reads one document again, retaining its identity and its compatible committed work. */
    private suspend fun retryOne(
        job: Job,
        collection: Collection,
        settings: ExtractionSettings,
        snapshot: OcrSettingsSnapshot?,
        document: Document,
        stage: JobStage,
    ) {
        val managedPath = library.managedPathOf(document)
        if (!Files.isRegularFile(managedPath)) {
            // The bytes this retry was admitted for are gone. That is a safe failure, not a crash and not a
            // silent no-op: the document says exactly what is missing, and the next import of the same file
            // is what puts bytes back.
            recordFailure(stage, document, MANAGED_COPY_MISSING, MANAGED_COPY_MISSING_MESSAGE)
            return
        }
        ingest.ingest(
            collection = collection,
            settings = settings,
            document = document,
            managedPath = managedPath,
            stage = stage,
            // The one thing an explicit retry does differently from a crash resume.
            revisitFailedUnits = true,
            onFailure = { code, message -> recordFailure(stage, document, code, message) },
            // The page allowance this document's pages leave under: the *job's*, because the retry's
            // documents share one scope exactly as an import's files do, and this attempt's, because nothing
            // may be dispatched before the scope was approved.
            dispatch = attemptDispatch.authorityFor(
                job = job,
                document = document,
                snapshot = snapshot,
                dispatchStage = OcrDispatchStage.TRANSCRIPTION,
            ),
            // How this document's staged pages are judged, or null when the attempt has no reviewer: the
            // page's own text is compared with the engine's reading while the draft is in hand, and the
            // review is recorded for a person — the same rules an import's staged pages are judged by.
            review = attemptDispatch.stagedPageReviewOf(
                job = job,
                document = document,
                collection = collection,
                snapshot = snapshot,
            ),
            // The selection this retry was admitted with, which the revision it publishes records as its reading.
            reading = snapshot,
        )
    }

    /** Records why one document could not be read again, as its own status rather than the job's. */
    private suspend fun recordFailure(stage: JobStage, document: Document, code: String, message: String) {
        stage.run(STAGE_RECORD) {
            documents.updateStatus(document.id, ImportJobHandler.statusForFailureCode(code), code, message)
        }
    }

    /**
     * Ends the documents an interrupted attempt left mid-flight as `CANCELLED`.
     *
     * Only documents in a pipeline status are touched: a document this attempt already finished keeps the
     * terminal state it earned, and one it never reached keeps the failure the user asked to retry.
     *
     * The write runs outside the attempt's own permits on purpose — a cancelled stage refuses to write —
     * and inside an uncancellable context, because this *is* the cleanup the cancellation asked for. It
     * waits for exclusive maintenance rather than being refused by it, for the same reason a job stage does:
     * maintenance lasts long, and this is one bounded write. A failure here is swallowed deliberately: the
     * row may already be gone with its collection, and a deletion owns the document from that point on.
     */
    private suspend fun cancelInterrupted(payload: RetryJobPayload) {
        withContext(NonCancellable) {
            payload.documentIds.map(::DocumentId).forEach { id ->
                val document = documents.get(id) ?: return@forEach
                if (document.status !in INTERRUPTIBLE_STATUSES) return@forEach
                runCatching {
                    mutations.awaitMutation {
                        documents.updateStatus(id, DocumentStatus.CANCELLED, CANCELLED_CODE, CANCELLED_MESSAGE)
                    }
                }
            }
        }
    }

    private companion object {

        const val STAGE_RECORD = "record"

        /** The job the waiting attempt belongs to, for the log line that says what it waits for. */
        const val JOB_ID_FIELD = "job_id"

        /** The managed copy this attempt was admitted for is not on disk any more. */
        const val MANAGED_COPY_MISSING = "MANAGED_COPY_MISSING"
        const val MANAGED_COPY_MISSING_MESSAGE =
            "the document's managed copy is missing, so it cannot be read again; add the file again to " +
                "restore it"

        const val CANCELLED_CODE = "RETRY_CANCELLED"
        const val CANCELLED_MESSAGE = "this attempt was cancelled before the document was read"

        /** The statuses that mean work is underway on a document and nobody else owns its outcome. */
        val INTERRUPTIBLE_STATUSES: Set<DocumentStatus> = setOf(
            DocumentStatus.QUEUED,
            DocumentStatus.COPYING,
            DocumentStatus.EXTRACTING,
            DocumentStatus.OCR,
            DocumentStatus.CHUNKING,
            DocumentStatus.EMBEDDING,
            DocumentStatus.INDEXING,
        )
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.retry")

private const val DOCUMENT_FIELD = "document_id"
