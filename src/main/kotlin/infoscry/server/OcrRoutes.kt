package infoscry.server

import infoscry.AppContext
import infoscry.domain.DocumentId
import infoscry.ocr.OcrOperation
import infoscry.ocr.ReadingMethod
import infoscry.ocr.OcrSettingsSnapshot
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

/** The one reading method the person confirmed for this rescan. */
@Serializable
data class RescanPreviewRequest(
    val method: String,
)

/** Admitting a previewed rescan. The request id is what makes a repeated POST one operation. */
@Serializable
data class AdmitRescanRequest(
    val previewId: String,
    val requestId: String,
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
                val request = call.receiveJson<RescanPreviewRequest>()
                val method = try {
                    ReadingMethod.parse(request.method)
                } catch (_: IllegalArgumentException) {
                    throw BadRequestException("unknown reading method")
                }
                val preview = context.rescanService.preview(
                    collectionId = collectionId,
                    documentId = documentId,
                    method = method,
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

}

/** Operations of one document, newest first. */
@Serializable
data class OperationsResponse(val operations: List<OcrOperation>)

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

/** A document id out of the path, resolved under the collection the path already named. */
private fun ApplicationCall.documentId(): DocumentId {
    val raw = parameters["documentId"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a document id is required in the path")
    return DocumentId(raw)
}

private fun ApplicationCall.operationId(): String =
    parameters["operationId"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("an operation id is required in the path")

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

private const val DEFAULT_REVISIONS = 50
private const val MAX_PAGE = 200
