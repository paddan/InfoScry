package infoscry.ocr

import infoscry.domain.DocumentId
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable

/**
 * Where a rescan operation has got to.
 *
 * The stages are the operation's durability, not a progress label: every one of them is a state a process
 * can be killed in and resume from, because each is reached only after the work behind it is committed.
 * Their shape is the one thing the ticket fixes, so a later reader can always answer "what is this document
 * doing right now" from the row alone.
 *
 * Three pairs are easy to confuse and are deliberately distinct:
 *
 * - [AWAITING_APPROVAL] is *before* any page of an unapproved scope is dispatched. It is a waiting state a
 *   process can restart into, not a failure: the job that reached it ends, and the approval resumes the
 *   operation through another attempt with the same snapshot and the same counters.
 * - [NEEDS_TOOL] is a **failure** whose remedy is an installation. It is separate from [FAILED] because the
 *   document is not broken — nothing about it can be read until a tool exists — and separate from a per-page
 *   failure, which does not stop the operation at all.
 * - [COMPLETE] is about the *reading*, not about the review backlog. An operation can be complete with pages
 *   still awaiting a person's decision; the pending-review count is what says so, and such a document is
 *   shown as needing review rather than as fully searchable.
 */
@Serializable
enum class OcrOperationStage {
    PREFLIGHT,
    AWAITING_APPROVAL,
    OCR,
    REVIEW,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    COMPLETE,
    FAILED,
    CANCELLED,
    NEEDS_TOOL,
    ;

    /** Whether this stage means nothing further will happen without an explicit new attempt. */
    val isTerminal: Boolean get() = this != PREFLIGHT && this != AWAITING_APPROVAL && this != OCR &&
        this != REVIEW && this != CHUNKING && this != EMBEDDING && this != INDEXING

    /**
     * Whether an operation in this stage owns the document's reading.
     *
     * A document may be under one reading at a time: two overlapping rescans would each stage a candidate
     * against the same baseline and one of them would publish over the other's decision. Every stage that
     * either is doing work or is waiting for somebody holds the document — including [AWAITING_APPROVAL],
     * which is a documented wait and not an end: the reading continues once the scope is approved, with the
     * same snapshot and the same counters, so admitting a second operation meanwhile would race that
     * continuation. Terminal stages release the document: a failed or cancelled operation's proposal is
     * dead, and a complete one has already published.
     */
    val holdsDocument: Boolean get() = !isTerminal
}

/** Which record an external page allowance belongs to: one document's operation, or one import job. */
enum class OcrExternalOwnerKind {
    OPERATION,
    JOB,
}

/**
 * The record a distinct page and a provider call are counted against.
 *
 * An import of twenty files has one allowance covering the pages of all of them, which is why the owner is
 * the job; a rescan has one covering the pages of its single document, which is why it is the operation.
 */
data class OcrExternalOwner(val kind: OcrExternalOwnerKind, val id: String) {

    init {
        require(id.isNotBlank()) { "an external page allowance belongs to something that is named" }
    }

    companion object {

        fun operation(operationId: String): OcrExternalOwner =
            OcrExternalOwner(OcrExternalOwnerKind.OPERATION, operationId)

        fun job(jobId: String): OcrExternalOwner = OcrExternalOwner(OcrExternalOwnerKind.JOB, jobId)
    }
}

/** Which stage of an attempt sent a page: the reading, or the reviewer judging two readings. */
@Serializable
enum class OcrDispatchStage {
    TRANSCRIPTION,
    REVIEW,
}

/**
 * What an owner has sent externally, and what it is allowed to send.
 *
 * The two counters are deliberately separate. [distinctPages] is the allowance's unit: one page that left
 * this Mac, however many times it was sent and by whichever stage. [calls] is what was paid for, retries
 * included, and a page transcribed and then reviewed counts once and twice respectively. [allowance] is the
 * maximum distinct pages this owner may send right now — the collection's configured allowance, raised by an
 * approved scope — and [approvedDistinctPages] is what a person approved, absent when nobody approved
 * anything.
 */
@Serializable
data class OcrExternalAccount(
    val distinctPages: Int,
    val calls: Int,
    val allowance: Int,
    val approvedDistinctPages: Int? = null,
) {

    init {
        require(distinctPages >= 0) { "a distinct page count is not negative, was $distinctPages" }
        require(calls >= 0) { "a call count is not negative, was $calls" }
        require(allowance >= 0) { "an external page allowance is not negative, was $allowance" }
        require(approvedDistinctPages == null || approvedDistinctPages >= 0) {
            "an approved page scope is not negative, was $approvedDistinctPages"
        }
    }

    /** How many more distinct pages may leave this machine before an approval is needed. */
    val remaining: Int get() = (allowance - distinctPages).coerceAtLeast(0)
}

/**
 * One durable rescan operation.
 *
 * It carries no page text, no path and no provider message: [errorCode] is a safe code and [errorMessage] is
 * a curated remedy. The counters are what a reader follows — pages read, pages the engine could not read, and
 * the external page/call pair — and the snapshot is the whole of what the attempt may do.
 */
@Serializable
data class OcrOperation(
    val operationId: String,
    val collectionId: String,
    val documentId: DocumentId,
    /** The attempt that owns the operation right now, absent between attempts. */
    val jobId: String? = null,
    val baseRevisionId: String? = null,
    val candidateRevisionId: String? = null,
    val snapshot: OcrSettingsSnapshot,
    val stage: OcrOperationStage,
    val pageTotal: Int? = null,
    val pagesCommitted: Int = 0,
    val pagesFailed: Int = 0,
    val external: OcrExternalAccount,
    /** How many pages of this document are waiting for a person's decision. */
    val pendingReviewCount: Int = 0,
    /**
     * How many pages a person has already decided that are not published yet.
     *
     * Derived from persisted state on every read — the candidate revision's approved pages that came from a
     * proposal, while that revision is still unpublished — so it is the same after a reload or a restart and
     * is zero once the decisions are published. It is what lets a review surface offer Publish when
     * [pendingReviewCount] is already zero.
     */
    val decidedUnpublishedCount: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val requestId: String,
    val createdAt: String,
    val updatedAt: String,
) {

    init {
        require(operationId.isNotBlank()) { "an operation names itself" }
        require(collectionId.isNotBlank()) { "an operation belongs to a collection" }
        require(requestId.isNotBlank()) { "an operation carries the request that admitted it" }
        require(pagesCommitted >= 0) { "a committed page count is not negative, was $pagesCommitted" }
        require(pagesFailed >= 0) { "a failed page count is not negative, was $pagesFailed" }
        require(pendingReviewCount >= 0) {
            "a pending review count is not negative, was $pendingReviewCount"
        }
        require(pageTotal == null || pageTotal > 0) { "a known page total is positive, was $pageTotal" }
    }

    /**
     * Whether this operation holds its document right now.
     *
     * The stage is most of the answer, and the review backlog is the rest: a COMPLETE operation whose pages
     * are still awaiting a person's decision has *not* finished with the document, because the decision
     * publishes another revision of it. Counting such an operation as done would let a rescan be admitted
     * while the previous reading still has pages nobody has decided about, and the two would race the same
     * document's revision history. This is the same predicate the schema's own partial unique index states.
     */
    val holdsDocument: Boolean
        get() = stage.holdsDocument || (stage == OcrOperationStage.COMPLETE && pendingReviewCount > 0)

}

/** Where one stage of an attempt would dispatch to, named for the person who has to decide about it. */
@Serializable
data class OcrNamedDestination(
    val role: String,
    val engine: OcrEngine,
    val scope: OcrEndpointScope,
    /** The endpoint the profile revision names, or an empty string for a provider's own default. */
    val endpoint: String,
    val model: String,
    val profileRevisionId: String? = null,
) {

    init {
        require(role.isNotBlank()) { "a named destination says which stage it is for" }
        require(model.isNotBlank()) { "a named destination names its model" }
    }
}

/**
 * What a rescan is estimated to cost, and on what basis.
 *
 * A missing price is *not* a zero price: an estimate that cannot be made reads as unavailable with the
 * reason, because "0.00" would be a claim about a paid endpoint that nothing supports.
 */
@Serializable
data class OcrCostEstimate(
    val amountUsd: Double,
    val basis: String,
) {

    init {
        require(amountUsd >= 0.0 && amountUsd.isFinite()) {
            "a cost estimate is a non-negative finite amount, was $amountUsd"
        }
        require(basis.isNotBlank()) { "a cost estimate says what it was computed from" }
    }
}

/**
 * What a rescan would do, shown before it is admitted.
 *
 * The preview is durable (see `ocr_rescan_previews`) because admission revalidates against it: [managedHash]
 * is the archive's own copy of the bytes the reading would be made from, [baseRevisionId] the reading it
 * would compare against, and [snapshot] the settings whose hash an external approval is bound to. A person
 * who approved "these 12 pages through this endpoint and model" must not have that approval apply to another
 * scope because something was edited in between.
 *
 * [externalPageUpperBound] is conservative: it is the number of pages that *could* be sent if the whole
 * document were read, and it is absent when the total is not known, which is the state in which an approval
 * is required before the first page beyond the allowance. [approvalRequired] is that requirement, computed
 * against the collection's configured allowance.
 */
@Serializable
data class RescanPreview(
    val previewId: String,
    val documentId: DocumentId,
    val baseRevisionId: String? = null,
    val managedHash: String,
    val snapshot: OcrSettingsSnapshot,
    val snapshotHash: String,
    val pageTotal: Int? = null,
    val externalPageUpperBound: Int? = null,
    val destinations: List<OcrNamedDestination> = emptyList(),
    val costEstimate: OcrCostEstimate? = null,
    val costUnavailableReason: String? = null,
    val approvalRequired: Boolean = false,
    val externalAllowance: Int = 0,
    val expiresAt: String,
) {

    init {
        require(previewId.isNotBlank()) { "a preview names itself" }
        require(managedHash.isNotBlank()) { "a preview names the bytes it would read" }
        require(snapshotHash.isNotBlank()) { "a preview names the snapshot an approval would bind to" }
        require(expiresAt.isNotBlank()) { "a preview carries the moment it stops being valid" }
        require(externalAllowance >= 0) {
            "a preview names a non-negative allowance, was $externalAllowance"
        }
    }
}

/** The lowercase hex SHA-256 of a canonical string: the one digest form the operation records use. */
internal fun operationHash(canonical: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
