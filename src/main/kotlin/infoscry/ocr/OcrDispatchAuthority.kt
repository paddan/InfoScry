package infoscry.ocr

import infoscry.domain.DocumentId
import infoscry.storage.OcrOperationStore
import infoscry.storage.OcrExternalApproval
import infoscry.ocr.OcrExternalOwner

/**
 * The dispatch authority of one attempt: the thing that answers "may this page leave this machine".
 *
 * It exists because an external dispatch is the only irreversible, costly act this feature performs, and
 * because the answer has to come from persisted state rather than from the attempt's own opinion. The
 * authority is therefore built per attempt, per stage, for exactly one owner, one profile revision and one
 * document, and it is the only producer of that answer:
 *
 * - **One owner.** A page is counted against the operation's document or against the import job the page
 *   belongs to — never against "the profile" or "the collection", which would let one job's approval pay
 *   for another job's pages.
 * - **One stage and revision.** A transcription-stage authority cannot permit a review call and vice versa,
 *   and a call through another revision is refused rather than counted: the approval names the scope it was
 *   granted for.
 * - **Counting and deciding as one write.** [isPermitted] counts the distinct page and answers from the same
 *   transaction ([OcrOperationStore.authorizePage]), so the allowance is a bound and not a race.
 * - **Calls are counted where they happen.** A retry is a second dispatch, and only the client knows it
 *   happened: [attemptAboutToBeSent] is told once per network attempt, retries included, while the page
 *   counter moves only once per page.
 *
 * A refusal is not a failure: it means this scope has no approval for another page, and the operation waits
 * in an explicit state until a person approves the scope (see [onExhausted]).
 */
class OcrDispatchAuthority(
    private val operations: OcrOperationStore,
    private val owner: OcrExternalOwner,
    private val documentId: DocumentId,
    private val stage: OcrDispatchStage,
    /** The immutable profile revision this stage dispatches through. */
    val profileRevisionId: String,
    /** The collection's or job's configured allowance, before any approval. */
    private val configuredAllowance: Int,
    /** The snapshot hash an approval must name to cover this attempt. */
    private val snapshotHash: String,
    /**
     * Called when the allowance is spent, *before* the page is refused.
     *
     * This is where a caller records the explicit waiting state. It runs only when an unapproved page would
     * have been the one to exceed the bound, so a refusal always means "approval needed", never "something
     * else went wrong".
     */
    private val onExhausted: (OcrExternalAccount) -> Unit = {},
) : ExternalDispatchPermitValidator {

    override fun isPermitted(request: ExternalDispatchPermitRequest): Boolean {
        // A page of another document, or a call through another revision, is not this authority's to
        // permit: silently counting it would let one operation pay for another's pages.
        if (request.profileRevisionId != profileRevisionId) return false
        if (request.page.documentId != documentId.value) return false
        val account = operations.allowanceFor(owner, configuredAllowance, snapshotHash)
        val permitted = operations.authorizePage(
            owner = owner,
            documentId = documentId,
            unitId = request.page.unitId,
            ordinal = request.page.ordinal,
            allowance = account.allowance,
        )
        if (!permitted) onExhausted(account)
        return permitted
    }

    /**
     * Counts one network attempt, retries included.
     *
     * Called immediately before the transport sends, so a request that was never sent is never counted and a
     * retry always is.
     */
    fun attemptAboutToBeSent(request: ExternalDispatchPermitRequest) {
        if (request.profileRevisionId != profileRevisionId) return
        if (request.page.documentId != documentId.value) return
        operations.recordCall(
            owner = owner,
            documentId = documentId,
            unitId = request.page.unitId,
            ordinal = request.page.ordinal,
            stage = stage,
        )
    }

    /** The approval that raised this attempt's allowance, or null when the configured allowance covers it. */
    fun coveringApproval(): OcrExternalApproval? =
        operations.latestApproval(owner)?.takeIf { it.snapshotHash == snapshotHash }
}
