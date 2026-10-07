package infoscry.jobs

import infoscry.config.AppPaths
import infoscry.document.PageReviewer
import infoscry.domain.Collection
import infoscry.domain.Document
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.StagedPageReview
import infoscry.storage.OcrOperationStore
import org.slf4j.LoggerFactory

/**
 * The attempt-level wiring an import and a retry share: how one job-owned external page scope becomes the
 * dispatch authority a page is sent through, and how a snapshotted reviewer profile becomes the review a
 * staged page is judged by.
 *
 * Both attempts read the same pages by the same rules, so this is one place rather than two: an import's
 * twenty files and a retry's selected documents share one allowance per job, and the transcription and the
 * review of one page are counted against the same scope the same way.
 */
internal class AttemptDispatch(
    private val operations: OcrOperationStore,
    private val profileRevisionOf: (String) -> OcrProfileRevision?,
    private val paths: AppPaths,
    private val reviewerFor: (OcrSettingsSnapshot, OcrDispatchAuthority?) -> PageReviewer,
) {

    fun authorityFor(
        job: Job,
        document: Document,
        snapshot: OcrSettingsSnapshot?,
        dispatchStage: OcrDispatchStage,
    ): OcrDispatchAuthority? {
        val scope = snapshot ?: return null
        val revisionId = when (dispatchStage) {
            OcrDispatchStage.TRANSCRIPTION -> scope.transcriptionProfileRevisionId
            OcrDispatchStage.REVIEW -> scope.reviewProfileRevisionId
        } ?: return null
        val revision = profileRevisionOf(revisionId) ?: return null
        if (revision.scope != OcrEndpointScope.EXTERNAL) return null
        return OcrDispatchAuthority(
            operations = operations,
            owner = OcrExternalOwner.job(job.id.value),
            documentId = document.id,
            stage = dispatchStage,
            profileRevisionId = revisionId,
            configuredAllowance = scope.externalPageLimit,
            snapshotHash = OcrOperationStore.snapshotHashOf(scope),
            onExhausted = { account ->
                LOGGER.atInfo()
                    .addKeyValue(JOB_ID_FIELD, job.id.value)
                    .addKeyValue(DOCUMENT_FIELD, document.id.value)
                    .addKeyValue(DISTINCT_PAGES_FIELD, account.distinctPages)
                    .addKeyValue(ALLOWANCE_FIELD, account.allowance)
                    .log("an external page scope is spent, so this page was not sent")
                throw AttemptAwaitingApproval(job.id)
            },
        )
    }

    fun stagedPageReviewOf(
        job: Job,
        document: Document,
        collection: Collection,
        snapshot: OcrSettingsSnapshot?,
    ): StagedPageReview? {
        val reviewerRevisionId = snapshot?.reviewProfileRevisionId ?: return null
        val reviewDispatch = authorityFor(job, document, snapshot, OcrDispatchStage.REVIEW)
        return StagedPageReview(
            reviewer = reviewerFor(snapshot, reviewDispatch),
            reviewProfileRevisionId = reviewerRevisionId,
            reviewPromptVersion = snapshot.reviewPromptVersion,
            policyVersion = snapshot.policyVersion,
            documentArtifactRoot = paths.artifactsDir(collection.id, document.id),
            managedCopyRoot = paths.documentDir(collection.id, document.id),
        )
    }

    private companion object {
        const val JOB_ID_FIELD = "job_id"
        const val DOCUMENT_FIELD = "document_id"
        const val DISTINCT_PAGES_FIELD = "distinct_external_pages"
        const val ALLOWANCE_FIELD = "external_page_allowance"
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.attempt")

/**
 * The attempt stopped because a page would have exceeded its job's external scope without an approval.
 */
internal class AttemptAwaitingApproval(val jobId: JobId) : IllegalStateException(
    "job ${jobId.value} waits for an external page scope to be approved before another page is sent",
)
