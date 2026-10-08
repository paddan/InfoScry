package infoscry.server

import infoscry.AppContext
import infoscry.document.PendingReview
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
import infoscry.document.RevisionHistoryEntry
import infoscry.storage.RevisionRestoreRecord
import infoscry.storage.StaleRescanPreviewException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
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
    /**
     * Whether `GET .../reviews/{unitId}/image` can be asked for this page. It is a listing-time answer from the
     * page's record (the image exists inside its document and is the one the review was made against); the
     * route verifies the bytes and is the authority, so a client still handles a refusal.
     */
    val imageAvailable: Boolean = false,
)

/** One pending page's candidate text, bounded, as the review surface shows it next to the page image. */
@Serializable
data class PendingCandidateResponse(
    val operationId: String,
    val unitId: String,
    val ordinal: Int,
    /** The hash of the whole candidate text, which a decision on this page must quote. */
    val candidateHash: String,
    /** The revision a decision's `expectedRevisionId` names while this proposal is pending. */
    val baselineRevisionId: String? = null,
    val text: String,
    /** The candidate's full length in characters; larger than `text` when [truncated]. */
    val totalChars: Int,
    val truncated: Boolean,
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
    /** Pages already decided whose decisions are not published yet; Publish is offered while this is above zero. */
    val decidedUnpublishedCount: Int = 0,
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

/** What the rescan that produced a revision was configured with, when an operation recorded it. */
@Serializable
data class RevisionReadingView(
    val engine: String,
    val mode: String,
    val language: String,
    val toolVersion: String? = null,
    val modelVersion: String? = null,
    val transcriptionModel: String? = null,
    val reviewModel: String? = null,
)

/**
 * How the pages of one revision differ from its parent's, each page counted once.
 *
 * `automatic` and `manual` are decisions the archive recorded (a reviewer policy approval and a person's
 * approval or edit); `unknown` is a change with no recorded decision; `restored` pages differ because the
 * revision is an explicit restore; `notPublished` pages never became text.
 */
@Serializable
data class PageChangesView(
    val unchanged: Int,
    val added: Int,
    val automatic: Int,
    val manual: Int,
    val unknown: Int,
    val restored: Int,
    val notPublished: Int,
)

/**
 * One published revision of a document, with the provenance of how it came to be.
 *
 * Only what the archive recorded: a field that was not recorded is absent rather than guessed, and nothing
 * here names a file, an artifact or an endpoint.
 */
@Serializable
data class RevisionView(
    val revisionId: String,
    val parentRevisionId: String? = null,
    val state: String,
    val provenance: String,
    val createdAt: String,
    val active: Boolean,
    val pageCount: Int,
    /** When the revision became the document's text; absent for a reading that never went through a publication. */
    val publishedAt: String? = null,
    /** The historical revision this one is an explicit restore of. */
    val restoredFromRevisionId: String? = null,
    /**
     * The OCR engine, mode, language and versions the revision was read with; absent when no page was read by
     * OCR or the attempt recorded no settings.
     */
    val reading: RevisionReadingView? = null,
    val extractionMethods: List<String> = emptyList(),
    val pageChanges: PageChangesView,
    /** True when every published page was read without OCR, so the history says no page needed OCR. */
    val noOcrNeeded: Boolean = false,
)

/** A page of a document's published history, with the revision that is active now. */
@Serializable
data class RevisionsResponse(
    val revisions: List<RevisionView>,
    val total: Int,
    val activeRevisionId: String? = null,
)

/** Restoring a historical revision. The request id is what makes a repeated POST one operation. */
@Serializable
data class RestoreRevisionRequest(
    val requestId: String,
    /** The revision the caller was looking at; a document that publishes another one refuses the restore. */
    val expectedRevisionId: String,
    /** The historical revision whose page texts are to become the document's text again. */
    val restoreRevisionId: String,
)

/** One restore request, as it stands. */
@Serializable
data class RestoreOperationView(
    val restoreId: String,
    val documentId: String,
    val requestId: String,
    val expectedRevisionId: String,
    val restoredFromRevisionId: String,
    /** The revision the restore created: the document's text once the restore is `PUBLISHED`. */
    val newRevisionId: String,
    val phase: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: String,
    val updatedAt: String,
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

        // Withdraws an undecided replacement and releases the document; the published text is untouched.
        post("/operations/{operationId}/discard-pending") {
            call.handle {
                val operation = context.rescanService.discardPending(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = call.operationId(),
                )
                call.respondJson(HttpStatusCode.OK, operation)
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
                        reviews = pending.drop(offset).take(limit).map(PendingReview::toApiView),
                        total = pending.size,
                        pendingPages = operation.pendingReviewCount,
                        pendingReviewCount = operation.pendingReviewCount,
                        decidedUnpublishedCount = operation.decidedUnpublishedCount,
                        externalAccounting = externalAccountingOf(operation),
                    ),
                )
            }
        }

        // The text a person is deciding about. JSON only, bounded, and only for a page that is still pending.
        get("/reviews/{unitId}/candidate") {
            call.handle {
                val operationId = call.reviewOperationId()
                val reading = context.rescanService.pendingCandidate(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = operationId,
                    unitId = call.reviewUnitId(),
                )
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.respondJson(
                    HttpStatusCode.OK,
                    PendingCandidateResponse(
                        operationId = operationId,
                        unitId = reading.unitId,
                        ordinal = reading.ordinal,
                        candidateHash = reading.candidateHash,
                        baselineRevisionId = reading.baselineRevisionId,
                        text = reading.text,
                        totalChars = reading.totalChars,
                        truncated = reading.truncated,
                    ),
                )
            }
        }

        // The image that reading was made from: the managed artifact, resolved server-side from the page's own
        // record. The type is one of a fixed list, never a stored value, and nothing here names a file.
        get("/reviews/{unitId}/image") {
            call.handle {
                val image = context.rescanService.pendingImage(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    operationId = call.reviewOperationId(),
                    unitId = call.reviewUnitId(),
                )
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.response.headers.append("Content-Security-Policy", "default-src 'none'; sandbox")
                call.respondBytes(image.bytes, ContentType.parse(image.mediaType), HttpStatusCode.OK)
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
                val limit = call.boundedLimit(DEFAULT_REVISIONS)
                val offset = call.boundedOffset()
                // Scoped like every other id in this tree: a document of another collection is answered exactly
                // like one that does not exist, so the history confirms nothing the caller did not scope.
                val history = context.revisionHistory.history(call.collectionId(), call.documentId())
                call.respondJson(
                    HttpStatusCode.OK,
                    RevisionsResponse(
                        revisions = history.drop(offset).take(limit).map(RevisionHistoryEntry::toApiView),
                        total = history.size,
                        activeRevisionId = history.firstOrNull { it.active }?.revision?.id,
                    ),
                )
            }
        }

        // Explicit restoration: a NEW revision that copies a historical one's page texts and publishes through
        // the same recoverable protocol as a rescan. It never reads a page again. The restore is durable before
        // this answers and idempotent under its request id, like a rescan admission.
        post("/restore") {
            call.handle {
                val request = call.receiveJson<RestoreRevisionRequest>()
                if (request.requestId.isBlank() || request.expectedRevisionId.isBlank() || request.restoreRevisionId.isBlank()) {
                    throw BadRequestException(
                        "a restore needs a request id, the revision it was taken against and the revision to restore",
                    )
                }
                val restore = context.revisionRestore.restore(
                    collectionId = call.collectionId(),
                    documentId = call.documentId(),
                    requestId = request.requestId,
                    expectedRevisionId = request.expectedRevisionId,
                    restoreRevisionId = request.restoreRevisionId,
                )
                call.respondJson(HttpStatusCode.Accepted, restore.toApiView())
            }
        }

        get("/restores/{restoreId}") {
            call.handle {
                val restoreId = call.parameters["restoreId"]?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("a restore id is required in the path")
                val restore = context.revisionRestore.restoreOf(call.collectionId(), call.documentId(), restoreId)
                call.respondJson(HttpStatusCode.OK, restore.toApiView())
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
                if (job.state == infoscry.domain.JobState.CANCELLED) {
                    throw BadRequestException("this job was cancelled, so there is nothing left to approve")
                }
                val snapshot = job.recordedOcrSelection()
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
 * What a person needs to approve a job that is waiting for external pages, carried on the job views only while
 * the job waits: the snapshot the approval must name, what the scope has already sent, and what it may send.
 */
@Serializable
data class ExternalApprovalView(
    val snapshotHash: String,
    val distinctPagesSent: Int,
    val allowance: Int,
    val calls: Int,
)

/**
 * The OCR selection an import or retry job recorded when it was admitted, or null when it recorded none.
 *
 * The approval route and the job views both read the selection through here, so the snapshot a view offers for
 * approval is always the one the route will accept.
 */
internal fun infoscry.domain.Job.recordedOcrSelection(): OcrSettingsSnapshot? =
    if (type == infoscry.domain.JobType.RETRY) {
        infoscry.jobs.RetryJobPayload.decode(payload).ocr
    } else {
        infoscry.jobs.ImportJobPayload.decode(payload).ocr
    }

/**
 * The approval a job waiting for external pages needs, or null when the job is not waiting for one.
 *
 * Only an import or a retry can be approved through `POST /api/jobs/{id}/approve-external`, so only those are
 * offered. A payload that cannot be read is not offered either: a job list must not fail because one row is
 * unreadable, and the approval route refuses that job on its own terms.
 */
internal fun AppContext.externalApprovalOf(job: infoscry.domain.Job): ExternalApprovalView? {
    if (job.stage != infoscry.storage.JobStore.AWAITING_APPROVAL_STAGE) return null
    if (job.type != infoscry.domain.JobType.IMPORT && job.type != infoscry.domain.JobType.RETRY) return null
    val snapshot = runCatching { job.recordedOcrSelection() }.getOrNull() ?: return null
    val owner = OcrExternalOwner.job(job.id.value)
    val snapshotHash = infoscry.storage.OcrOperationStore.snapshotHashOf(snapshot)
    return ExternalApprovalView(
        snapshotHash = snapshotHash,
        distinctPagesSent = ocrOperations.distinctPageCount(owner),
        allowance = ocrOperations.allowanceFor(owner, snapshot.externalPageLimit, snapshotHash).allowance,
        calls = ocrOperations.callCount(owner),
    )
}

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

private fun RevisionHistoryEntry.toApiView(): RevisionView = RevisionView(
    revisionId = revision.id,
    parentRevisionId = revision.parentRevisionId,
    state = revision.state.name,
    provenance = revision.provenance,
    createdAt = revision.createdAt,
    active = active,
    pageCount = pageCount,
    publishedAt = publishedAt,
    restoredFromRevisionId = restoredFromRevisionId,
    reading = reading?.let { reading ->
        RevisionReadingView(
            engine = reading.engine,
            mode = reading.mode,
            language = reading.language,
            toolVersion = reading.toolVersion,
            modelVersion = reading.modelVersion,
            transcriptionModel = reading.transcriptionModel,
            reviewModel = reading.reviewModel,
        )
    },
    extractionMethods = extractionMethods,
    pageChanges = PageChangesView(
        unchanged = pageChanges.unchanged,
        added = pageChanges.added,
        automatic = pageChanges.automatic,
        manual = pageChanges.manual,
        unknown = pageChanges.unknown,
        restored = pageChanges.restored,
        notPublished = pageChanges.notPublished,
    ),
    noOcrNeeded = noOcrNeeded,
)

private fun RevisionRestoreRecord.toApiView(): RestoreOperationView = RestoreOperationView(
    restoreId = restoreId,
    documentId = documentId.value,
    requestId = requestId,
    expectedRevisionId = expectedRevisionId,
    restoredFromRevisionId = restoredFromRevisionId,
    newRevisionId = newRevisionId,
    phase = phase.name,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun PendingReview.toApiView(): PendingReviewView = review.toApiView(imageAvailable)

private fun PageReview.toApiView(imageAvailable: Boolean): PendingReviewView = PendingReviewView(
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
    imageAvailable = imageAvailable,
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

private fun ApplicationCall.reviewOperationId(): String =
    request.queryParameters["operationId"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("the review routes need the operation they are about")

private fun ApplicationCall.reviewUnitId(): String =
    parameters["unitId"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a page id is required in the path")

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
