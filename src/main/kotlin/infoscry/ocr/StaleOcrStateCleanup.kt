package infoscry.ocr

import infoscry.domain.DocumentStatus
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.OcrOperationStore

/** Counts the legacy OCR records made restartable by one startup pass. */
data class CleanupReport(
    val jobsCancelled: Int,
    val operationsFailed: Int,
    val candidatesWithdrawn: Int,
)

/**
 * Cleans states emitted by the retired approval and review flow before interrupted jobs are recovered.
 * Each store write is conditional, so a crash between steps can be followed by another complete pass.
 */
class StaleOcrStateCleanup(
    private val jobs: JobStore,
    private val operations: OcrOperationStore,
    private val revisions: DocumentRevisionStore,
    private val documents: DocumentStore,
    /** The startup composition root supplies false because no worker is attached until open completes. */
    private val hasLiveAttempt: (String) -> Boolean = { false },
) {
    fun run(): CleanupReport {
        var jobsCancelled = 0
        var operationsFailed = 0
        var candidatesWithdrawn = 0

        jobs.legacyAwaitingApprovalJobs().forEach { job ->
            if (jobs.cancelLegacyAwaitingApproval(job.id)) jobsCancelled++
        }

        operations.operationsNeedingStartupCleanup().forEach { operation ->
            if (operation.stage.holdsDocument && !hasLiveAttempt(operation.operationId)) {
                if (candidateIsAuthoritative(operation)) {
                    repairPublished(operation)
                } else {
                    operations.atomically {
                        if (operations.failInterruptedIfUnowned(
                                operation.operationId,
                                operation.stage,
                                operation.jobId,
                            )
                        ) {
                            operation.jobId?.let { jobs.failInterruptedRescanJob(infoscry.domain.JobId(it)) }
                            documents.get(operation.documentId)?.let { document ->
                                if (isLatest(operation)) {
                                    documents.updateStatus(
                                        document.id,
                                        DocumentStatus.FAILED,
                                        "INTERRUPTED",
                                        "the previous reading was interrupted; start it again",
                                    )
                                }
                            }
                            operationsFailed++
                        }
                    }
                }
            } else if (operation.stage == OcrOperationStage.FAILED && operation.errorCode == "INTERRUPTED") {
                // Older builds wrote the operation and document separately. Repair the crash window left by
                // that ordering, and roll forward if publication had already become authoritative.
                if (candidateIsAuthoritative(operation)) repairPublished(operation)
                else repairInterruptedDocument(operation)
            } else if (operation.stage == OcrOperationStage.COMPLETE) {
                if (candidateIsAuthoritative(operation)) repairPublished(operation)
                else if (operation.pendingReviewCount > 0) {
                    // A completed legacy review candidate is not authoritative; keep its published baseline.
                    documents.get(operation.documentId)
                        ?.takeIf { it.status == DocumentStatus.NEEDS_REVIEW && isLatest(operation) }
                        ?.let { document -> documents.updateStatus(document.id, DocumentStatus.COMPLETE) }
                    if (withdrawPendingCandidate(operation, revisions, operations)) candidatesWithdrawn++
                }
            }
        }

        return CleanupReport(jobsCancelled, operationsFailed, candidatesWithdrawn)
    }

    private fun candidateIsAuthoritative(operation: OcrOperation): Boolean =
        operation.candidateRevisionId?.let { candidate ->
            revisions.activeRevisionId(operation.documentId) == candidate
        } == true

    private fun isLatest(operation: OcrOperation): Boolean =
        operations.latestForDocuments(listOf(operation.documentId))[operation.documentId]?.operationId ==
            operation.operationId

    private fun repairPublished(operation: OcrOperation) {
        operations.atomically {
            if (operation.stage != OcrOperationStage.COMPLETE) {
                operations.finish(
                    operation.operationId,
                    OcrOperationStage.COMPLETE,
                    errorCode = null,
                    errorMessage = null,
                )
            }
            if (operation.jobId != null) {
                jobs.completePublishedRescanJob(infoscry.domain.JobId(operation.jobId))
            } else {
                jobs.finishRescanJobsForOperation(operation.operationId, published = true)
            }
            if (isLatest(operation)) {
                documents.get(operation.documentId)?.let { document ->
                    val status = if (operation.pagesFailed > 0) {
                        DocumentStatus.COMPLETE_WITH_WARNINGS
                    } else {
                        DocumentStatus.COMPLETE
                    }
                    if (document.status != status || document.errorCode != null) {
                        documents.updateStatus(document.id, status)
                    }
                }
            }
        }
    }

    private fun repairInterruptedDocument(operation: OcrOperation) {
        operations.atomically {
            jobs.finishRescanJobsForOperation(operation.operationId, published = false)
            if (isLatest(operation)) {
                documents.get(operation.documentId)
                    ?.takeIf { it.status in PIPELINE_STATUSES }
                    ?.let { document ->
                        documents.updateStatus(
                            document.id,
                            DocumentStatus.FAILED,
                            "INTERRUPTED",
                            "the previous reading was interrupted; start it again",
                        )
                    }
            }
        }
    }

    private companion object {
        val PIPELINE_STATUSES = setOf(
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

/**
 * Withdraws a complete operation's undecided candidate and clears its durable pending count.
 * The two writes are deliberately repeatable: if the process stops between them, startup completes the
 * second write without changing the published revision or the candidate's identity.
 */
fun withdrawPendingCandidate(
    operation: OcrOperation,
    revisions: DocumentRevisionStore,
    operations: OcrOperationStore,
): Boolean {
    if (operation.stage != OcrOperationStage.COMPLETE || operation.pendingReviewCount <= 0) return false
    val candidate = operation.candidateRevisionId
    val withdrawn = candidate?.let(revisions::withdrawCandidateIfCandidate) ?: false
    val cleared = operations.clearPendingReviewIfComplete(operation.operationId, candidate, operation.pendingReviewCount)
    return withdrawn || cleared
}
