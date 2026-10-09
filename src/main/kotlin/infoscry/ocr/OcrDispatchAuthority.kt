package infoscry.ocr

import infoscry.domain.DocumentId
import infoscry.storage.OcrOperationStore

/** The confirmed external-page allowance is exhausted; the refused page was not dispatched. */
class ExternalPageAllowanceExceededException : IllegalStateException(
    "the run needed more pages than were confirmed; no additional page was sent",
)

/**
 * The dispatch authority of one attempt: the thing that answers "may this page leave this machine".
 *
 * It exists because an external dispatch is the only irreversible, costly act this feature performs, and
 * because the answer has to come from persisted state rather than from the attempt's own opinion. The
 * authority is therefore built per attempt, per stage, for exactly one owner, one profile revision and one
 * document, and it is the only producer of that answer:
 *
 * - **One owner.** A page is counted against the operation's document or against the import job the page
 *   belongs to — never against "the profile" or "the collection", which would let one job consume another
 *   job's confirmed page allowance.
 * - **One revision.** A call through another revision is refused rather than counted: the immutable snapshot
 *   names the scope it was confirmed for.
 * - **Counting and deciding as one write.** [isPermitted] counts the distinct page and answers from the same
 *   transaction ([OcrOperationStore.authorizePage]), so the allowance is a bound and not a race.
 * - **Calls are counted where they happen.** A retry is a second dispatch, and only the client knows it
 *   happened: [attemptAboutToBeSent] is told once per network attempt, retries included, while the page
 *   counter moves only once per page.
 *
 * A refusal ends the run with `MORE_PAGES_THAN_CONFIRMED`; the page is denied before dispatch.
 */
class OcrDispatchAuthority(
    private val operations: OcrOperationStore,
    private val owner: OcrExternalOwner,
    private val documentId: DocumentId,
    private val stage: OcrDispatchStage,
    /** The immutable profile revision this stage dispatches through. */
    val profileRevisionId: String,
    /** The immutable snapshot's confirmed external-page allowance. */
    private val configuredAllowance: Int,
) : ExternalDispatchPermitValidator {

    override fun isPermitted(request: ExternalDispatchPermitRequest): Boolean {
        // A page of another document, or a call through another revision, is not this authority's to
        // permit: silently counting it would let one operation pay for another's pages.
        if (request.profileRevisionId != profileRevisionId) return false
        if (request.page.documentId != documentId.value) return false
        val permitted = operations.authorizePage(
            owner = owner,
            documentId = documentId,
            unitId = request.page.unitId,
            ordinal = request.page.ordinal,
            allowance = configuredAllowance,
        )
        if (!permitted) throw ExternalPageAllowanceExceededException()
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
}
