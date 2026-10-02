package infoscry.server

import infoscry.AppContext
import infoscry.document.ReviewChoice
import infoscry.document.ReviewDecision
import infoscry.domain.DocumentId
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrOperation
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageReview
import infoscry.ocr.ReviewReason
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.StaleRescanPreviewException
import infoscry.storage.RevisionState
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// ---- request and response bodies ----

/** What a rescan preview may override: the collection's defaults are used for everything absent. */
@Serializable
data class RescanPreviewRequest(
    val engine: OcrEngine? = null,
    val importMode: OcrImportMode? = null,
    val transcriptionProfileId: String? = null,
    val reviewProfileId: String? = null,
)

/** Admitting a previewed rescan. The request id is what makes a repeated POST one operation. */
@Serializable
data class AdmitRescanRequest(
    val previewId: String,
    val requestId: String,
)

/** Approving an external page scope for one operation. */
@Serializable
data class ApproveExternalRequest(
    /** The snapshot hash the approval is bound to; a stale one is refused rather than applied. */
    val expectedSnapshotHash: String,
    /** The maximum number of distinct pages this approval authorizes. */
    val maxDistinctPages: Int,
)

/** One explicit page decision, as the review UI sends it. */
@Serializable
data class ReviewDecisionRequest(
    val unitId: String,
    val ordinal: Int,
    /** The hash of the candidate text this decision was taken against. */
    val candidateHash: String,
    val choice: ReviewChoice,
    val text: String? = null,
)

/** One decision batch: explicit pages, or one choice for the document's whole pending set. */
@Serializable
data class ReviewDecisionsRequest(
    val requestId: String,
    val expectedRevisionId: String,
    val decisions: List<ReviewDecisionRequest> = emptyList(),
    val documentWide: ReviewChoice? = null,
)

/** Admitting the publication of an operation's decided pages. */
@Serializable
data class PublishDecisionsRequest(
    val expectedRevisionId: String,
    /** The decision batch this publication is about, for a caller that has to say which one it meant. */
    val batchId: String? = null,
)

/** One proposed page as the review surface reads it. */
@Serializable
data class PendingReviewView(
    val unitId: String,
    val ordinal: Int,
    val recommendation: String,
    val disposition: String,
    val baselineRevisionId: String? = null,
    val baselineTextHash: String? = null,
    val candidateHash: String,
    val outcomeCode: String? = null,
    val confidence: Double? = null,
    val reasons: List<ReviewReasonView> = emptyList(),
    val reviewerRevisionId: String,
    val reviewPromptVersion: Int,
    val policyVersion: Int,
    val searchable: Boolean,
)

/** One bounded reason behind a review, as the comparison recorded it. */
@Serializable
data class ReviewReasonView(
    val code: String,
    val origin: String,
    val baselineStart: Int? = null,
    val baselineEnd: Int? = null,
    val candidateStart: Int? = null,
    val candidateEnd: Int? = null,
    val explanation: String? = null,
)

/** One page of a document's pending proposals, with what the external counters mean. */
@Serializable
data class ReviewsResponse(
    val reviews: List<PendingReviewView>,
    val total: Int,
    val pendingPages: Int,
    val pendingReviewCount: Int,
    /** How the two external counters differ, in the words the UI shows beside them. */
    val externalAccounting: String,
)

/** One applied decision, so the caller can see what happened to each page. */
@Serializable
data class AppliedDecisionView(val ordinal: Int, val choice: String, val unitId: String)

/** What one decision batch did. */
@Serializable
data class ReviewDecisionsResponse(
    val operation: OcrOperation,
    val applied: List<AppliedDecisionView>,
)

/** What admitting a publication of decided pages answered. */
@Serializable
data class PublicationDecisionResponse(
    val operation: OcrOperation,
    val publicationId: String,
    val phase: String,
    val errorCode: String? = null,
)

/** One published revision of a document, with the provenance of how it came to be. */
@Serializable
data class RevisionView(
    val revisionId: String,
    val parentRevisionId: String? = null,
    val state: String,
    val provenance: String,
    val createdAt: String,
    val active: Boolean,
    val pageCount: Int,
)

/** A page of a document's published history, with the revision that is active now. */
@Serializable
data class RevisionsResponse(
    val revisions: List<RevisionView>,
    val total: Int,
    val activeRevisionId: String? = null,
)

/**
 * The rescan and review surface, nested under one collection and one document.
 *
 * Every id in a path is resolved under its own scope: an operation, a preview or a revision of another
 * collection or another document is answered exactly like one that does not exist, so a response can never
 * confirm an id the caller did not scope its request to. Nothing here accepts a filesystem path or a key.
 *
 * The status codes are the contract a client reads: 400 for a request whose shape is wrong, 409 for a
 * conflict with the document's lifecycle or its current revision, 404 for anything not in this scope, and 202
 * for an admission that is durable before this call returns — a rescan, an approval that resumes one, and a
 * resumption are all queued work whose progress is read from the operation rather than from this connection.
 */
fun Routing.configureOcrRescanRoutes(context: AppContext) {
    route("/api/collections/{id}/documents/{documentId}/ocr") {

        // What a rescan would do, without reading a page or sending anything anywhere.
        post("/preview") {
            call.handle {
                val collectionId = call.collectionId()
                val documentId = call.documentId()
                val request = call.receiveJsonOrEmpty<RescanPreviewRequest>()
                val preview = context.rescanService.preview(
                    collectionId = collectionId,
                    documentId = documentId,
                    overrides = infoscry.document.RescanOverrides(
                        engine = request.engine,
                        importMode = request.importMode,
                        transcriptionProfileId = request.transcriptionProfileId,
                        reviewProfileId = request.reviewProfileId,
                    ),
                )
                call.respondJson(HttpStatusCode.OK, preview)
            }
        }

        // Admission: durable before this answers, and idempotent under its request id.
        post("/rescan") {
            call.handle {
                val collectionId = call.collectionId()
                val documentId = call.documentId()
                val request = call.receiveJson<AdmitRescanRequest>()
                if (request.previewId.isBlank() || request.requestId.isBlank()) {
                    throw BadRequestException("a rescan needs both the preview it was shown and a request id")
                }
                val operation = context.rescanService.admitRescan(
                    collectionId = collectionId,
                    documentId = documentId,
                    previewId = request.previewId,
                    requestId = request.requestId,
                )
                call.respondJson(HttpStatusCode.Accepted, operation)
            }
        }

        get("/operations/{operationId}") {
            call.handle {
                val operation = context.rescanService.operation(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = call.operationId(),
                )
                call.respondJson(HttpStatusCode.OK, operation)
            }
        }

        // Approving a page scope widens the allowance of *this* operation's snapshot and resumes it.
        post("/operations/{operationId}/approve-external") {
            call.handle {
                val request = call.receiveJson<ApproveExternalRequest>()
                if (request.expectedSnapshotHash.isBlank()) {
                    throw BadRequestException("an approval names the snapshot hash it is bound to")
                }
                if (request.maxDistinctPages < 1) {
                    throw BadRequestException("an approval authorizes at least one page")
                }
                val operation = context.rescanService.approveExternal(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = call.operationId(),
                    expectedSnapshotHash = request.expectedSnapshotHash,
                    maxDistinctPages = request.maxDistinctPages,
                )
                call.respondJson(HttpStatusCode.Accepted, operation)
            }
        }

        // A durable cancellation request: the operation ends when the attempt observing it does.
        post("/operations/{operationId}/cancel") {
            call.handle {
                val operation = context.rescanService.cancel(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = call.operationId(),
                )
                call.respondJson(HttpStatusCode.OK, operation)
            }
        }

        // Another attempt of the same operation: the same snapshot, the same counters, the same candidate.
        post("/operations/{operationId}/resume") {
            call.handle {
                val operation = context.rescanService.resume(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = call.operationId(),
                )
                call.respondJson(HttpStatusCode.Accepted, operation)
            }
        }

        get("/reviews") {
            call.handle {
                val collectionId = call.collectionId()
                val documentId = call.documentId()
                val operationId = call.request.queryParameters["operationId"]?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("the reviews route needs the operation it is about")
                val limit = call.boundedLimit(DEFAULT_REVIEWS)
                val offset = call.boundedOffset()
                val pending = context.rescanService.pendingReviews(collectionId, documentId, operationId)
                val operation = context.rescanService.operation(collectionId, documentId, operationId)
                call.respondJson(
                    HttpStatusCode.OK,
                    ReviewsResponse(
                        reviews = pending.drop(offset).take(limit).map(PageReview::toApiView),
                        total = pending.size,
                        pendingPages = operation.pendingReviewCount,
                        pendingReviewCount = operation.pendingReviewCount,
                        externalAccounting = externalAccountingOf(operation),
                    ),
                )
            }
        }

        post("/review-decisions") {
            call.handle {
                val request = call.receiveJson<ReviewDecisionsRequest>()
                if (request.requestId.isBlank() || request.expectedRevisionId.isBlank()) {
                    throw BadRequestException(
                        "a decision batch needs a request id and the revision it was taken against",
                    )
                }
                val operationId = call.request.queryParameters["operationId"]?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("a decision batch names the operation it is about")
                val result = context.rescanService.decideReviews(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = operationId,
                    requestId = request.requestId,
                    expectedRevisionId = request.expectedRevisionId,
                    decisions = request.decisions.map { decision ->
                        ReviewDecision(
                            unitId = decision.unitId,
                            ordinal = decision.ordinal,
                            candidateHash = decision.candidateHash,
                            choice = decision.choice,
                            text = decision.text,
                        )
                    },
                    documentWide = request.documentWide,
                )
                call.respondJson(
                    HttpStatusCode.OK,
                    ReviewDecisionsResponse(
                        operation = result.operation,
                        applied = result.applied.map { applied ->
                            AppliedDecisionView(applied.ordinal, applied.choice, applied.unitId)
                        },
                    ),
                )
            }
        }

        post("/publish-decisions") {
            call.handle {
                val request = call.receiveJson<PublishDecisionsRequest>()
                if (request.expectedRevisionId.isBlank()) {
                    throw BadRequestException("a publication names the revision it was taken against")
                }
                val operationId = call.request.queryParameters["operationId"]?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("a publication names the operation it is about")
                val result = context.rescanService.publishDecisions(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = operationId,
                    expectedRevisionId = request.expectedRevisionId,
                )
                call.respondJson(
                    HttpStatusCode.Accepted,
                    PublicationDecisionResponse(
                        operation = result.operation,
                        publicationId = result.publicationId,
                        phase = result.phase,
                        errorCode = result.errorCode,
                    ),
                )
            }
        }

        get("/operations") {
            call.handle {
                val operations = context.rescanService.operationsOf(call.collectionId(), call.documentId())
                call.respondJson(HttpStatusCode.OK, OperationsResponse(operations))
            }
        }

        get("/revisions") {
            call.handle {
                val collectionId = call.collectionId()
                val documentId = call.documentId()
                val limit = call.boundedLimit(DEFAULT_REVISIONS)
                val offset = call.boundedOffset()
                val active = context.revisions.activeRevisionId(documentId)
                val all = context.revisions.revisions(documentId)
                    .filter { revision -> revision.state != RevisionState.CANDIDATE }
                call.respondJson(
                    HttpStatusCode.OK,
                    RevisionsResponse(
                        revisions = all.drop(offset).take(limit).map { revision ->
                            RevisionView(
                                revisionId = revision.id,
                                parentRevisionId = revision.parentRevisionId,
                                state = revision.state.name,
                                provenance = revision.provenance,
                                createdAt = revision.createdAt,
                                active = revision.id == active,
                                pageCount = context.revisions.pages(revision.id).size,
                            )
                        },
                        total = all.size,
                        activeRevisionId = active,
                    ),
                )
            }
        }
    }

    // The import-level approval: one allowance covers the pages of every file of one import, so approving it
    // happens on the job rather than on one of its documents.
    route("/api/jobs/{id}") {
        post("/approve-external") {
            call.handle {
                val request = call.receiveJson<ApproveExternalRequest>()
                if (request.expectedSnapshotHash.isBlank()) {
                    throw BadRequestException("an approval names the snapshot hash it is bound to")
                }
                if (request.maxDistinctPages < 1) {
                    throw BadRequestException("an approval authorizes at least one page")
                }
                val jobId = call.jobId()
                val job = context.jobs.get(jobId)
                    ?: throw NoSuchElementException("no job with id ${jobId.value}")
                val snapshot = infoscry.jobs.ImportJobPayload.decode(job.payload).ocr
                    ?: throw BadRequestException(
                        "this job recorded no OCR selection, so it has no external page scope to approve",
                    )
                val snapshotHash = infoscry.storage.OcrOperationStore.snapshotHashOf(snapshot)
                if (snapshotHash != request.expectedSnapshotHash) {
                    throw StaleRescanPreviewException(
                        jobId.value,
                        "its OCR selection is not the one this approval names",
                    )
                }
                val allowance = context.ocrOperations.approveExternalScope(
                    owner = OcrExternalOwner.job(jobId.value),
                    snapshotHash = snapshotHash,
                    authorizedDistinctPages = request.maxDistinctPages,
                )
                // An approval of a scope the job was waiting on *resumes* it: the waiting state is durable,
                // and the approval is what ends it. A job still running or already queued is left exactly as
                // it is — the allowance it just approved is read on every dispatch, so the attempt under way
                // continues past the scope it stopped at, and a second attempt would be work nobody asked for.
                val resumed = context.jobs.resumeAwaitingApproval(jobId)
                call.respondJson(
                    HttpStatusCode.Accepted,
                    ImportExternalApprovalResponse(
                        jobId = jobId.value,
                        approvalId = allowance.approvalId,
                        authorizedDistinctPages = allowance.authorizedDistinctPages,
                        distinctPagesSent = context.ocrOperations.distinctPageCount(
                            OcrExternalOwner.job(jobId.value),
                        ),
                        calls = context.ocrOperations.callCount(OcrExternalOwner.job(jobId.value)),
                        state = resumed.state.name,
                        stage = resumed.stage,
                    ),
                )
            }
        }
    }
}

/** Operations of one document, newest first. */
@Serializable
data class OperationsResponse(val operations: List<OcrOperation>)

/** What one approved import scope answers with. */
@Serializable
data class ImportExternalApprovalResponse(
    val jobId: String,
    val approvalId: String,
    val authorizedDistinctPages: Int,
    val distinctPagesSent: Int,
    val calls: Int,
    /** The state the approved job answers with: `QUEUED` when this approval resumed it. */
    val state: String? = null,
    val stage: String? = null,
)

/**
 * How the two external counters differ, in the words the API and the UI show beside them.
 *
 * The distinction is the one a person has to be able to explain: a page counted once toward the allowance may
 * have cost two calls, because a page that was transcribed and then reviewed was sent twice.
 */
internal fun externalAccountingOf(operation: OcrOperation): String {
    val account = operation.external
    return "${account.distinctPages} distinct page(s) sent to an external provider, counted once each toward " +
        "the allowance of ${account.allowance}; ${account.calls} provider call(s), because a page transcribed " +
        "and then reviewed is one page and two calls"
}

private fun PageReview.toApiView(): PendingReviewView = PendingReviewView(
    unitId = unitId,
    ordinal = ordinal,
    recommendation = recommendation.name,
    disposition = disposition.name,
    baselineRevisionId = baselineRevisionId,
    baselineTextHash = baselineTextHash,
    candidateHash = candidateHash,
    outcomeCode = outcomeCode,
    confidence = confidence,
    reasons = reasons.map(ReviewReason::toApiView),
    reviewerRevisionId = reviewerRevisionId,
    reviewPromptVersion = reviewPromptVersion,
    policyVersion = policyVersion,
    searchable = searchable,
)

private fun ReviewReason.toApiView(): ReviewReasonView = ReviewReasonView(
    code = code,
    origin = origin.name,
    baselineStart = baselineSpan?.startOffset,
    baselineEnd = baselineSpan?.endOffset,
    candidateStart = candidateSpan?.startOffset,
    candidateEnd = candidateSpan?.endOffset,
    explanation = explanation,
)

/** A document id out of the path, resolved under the collection the path already named. */
private fun ApplicationCall.documentId(): DocumentId {
    val raw = parameters["documentId"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a document id is required in the path")
    return DocumentId(raw)
}

private fun ApplicationCall.operationId(): String =
    parameters["operationId"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("an operation id is required in the path")

/**
 * A body that is optional: a preview may be asked for with no overrides at all.
 *
 * An empty body is read as the request's own defaults rather than refused, because "preview this document
 * with the collection's settings" is the common case and a client should not have to send `{}` to mean it.
 */
private suspend inline fun <reified T> ApplicationCall.receiveJsonOrEmpty(): T {
    val body = receiveText()
    return if (body.isBlank()) ApiJson.decodeFromString("{}") else ApiJson.decodeFromString(body)
}

private fun ApplicationCall.boundedLimit(default: Int): Int {
    val raw = request.queryParameters["limit"] ?: return default
    val parsed = raw.toIntOrNull()?.takeIf { it > 0 }
        ?: throw BadRequestException("limit must be a whole number greater than zero, was '$raw'")
    return minOf(parsed, MAX_PAGE)
}

private fun ApplicationCall.boundedOffset(): Int {
    val raw = request.queryParameters["offset"] ?: return 0
    return raw.toIntOrNull()?.takeIf { it >= 0 }
        ?: throw BadRequestException("offset must be a whole number, zero or greater, was '$raw'")
}

private const val DEFAULT_REVIEWS = 50
private const val DEFAULT_REVISIONS = 50
private const val MAX_PAGE = 200
