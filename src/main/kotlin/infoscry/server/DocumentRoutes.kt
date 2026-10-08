package infoscry.server

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.UnitKind
import infoscry.jobs.ImportJobHandler
import infoscry.ocr.OcrQualityScorer
import infoscry.storage.DocumentListing
import infoscry.storage.DocumentProgress
import infoscry.storage.DocumentSort
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * One row of the documents page. Deliberately not [Document]: the domain row carries the original
 * filesystem path ([Document.sourcePath]) and the content hash ([Document.sha256]), which are not
 * part of the product surface and must never leave the server; [errorMessage] is dropped too,
 * because it can carry document text. The code a caller can act on remains.
 */
@Serializable
data class DocumentListItem(
    val id: DocumentId,
    val collectionId: CollectionId,
    val mediaType: String,
    val originalFilename: String,
    val sizeBytes: Long,
    val status: DocumentStatus,
    val createdAt: String,
    val updatedAt: String,
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    val errorCode: String? = null,
    /**
     * How far the document's current attempt has got, for the compact progress a row shows. Absent while
     * the document has no attempt recorded at all — a document sitting in the queue has no counts to show,
     * and a row of zeroes would be a claim rather than an absence.
     */
    val progress: DocumentProgressView? = null,
    /**
     * The document's quality score, 0 to 100, over the text of its live units. Absent unless the document is
     * complete and has text that can be judged. A reading aid for the row, never a decision input.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val qualityScore: Double? = null,
)

/**
 * What a document's current attempt has committed, in the numbers a reader can act on.
 *
 * [totalUnits] is `null` when the extractor never announced a total, and that is not a defect to be papered
 * over: a processed count shown against an invented denominator is a percentage of nothing. [unitKind]
 * names what is being counted — pages, sections, sheets, slides, lines, images — so the UI can label the
 * count in the document's own terms instead of calling every unit a page.
 *
 * [directTextUnits] and [ocrUnits] are `null` when no committed unit carries a stored method, which is what
 * a document extracted before methods were recorded looks like. That is "unknown", not zero.
 *
 * No message text travels here: a code becomes a sentence from [ImportJobHandler.messageFor], and the
 * stored message beside a code may be an exception's own words about a page, a path, or a pointer into the
 * document.
 */
@Serializable
data class DocumentProgressView(
    val unitKind: UnitKind? = null,
    val totalUnits: Int? = null,
    val processedUnits: Int = 0,
    val failedUnits: Int = 0,
    val directTextUnits: Int? = null,
    val ocrUnits: Int? = null,
)

/**
 * A page of one collection's documents plus the total the same criteria match, so the caller can
 * paginate without asking for the count separately.
 */
@Serializable
data class DocumentsResponse(val documents: List<DocumentListItem>, val total: Int)

/**
 * One collection's whole-document summary: every current status counted across the entire collection,
 * and the total those counts came from.
 *
 * The counts are an aggregate read of the collection's own managed documents — never a page and never a
 * filtered listing — and [byStatus] names every status the domain knows, including the ones no document
 * has, so a caller can show an absent status as zero rather than as an unknown. Nothing else travels:
 * no paths, no filenames, no stored text.
 */
@Serializable
data class DocumentSummaryResponse(
    val total: Int,
    val byStatus: Map<DocumentStatus, Int>,
)

/**
 * One document's collection-scoped detail: the same safe row the listing carries, the sentence for its
 * error code, and the first content unit a reader can open.
 *
 * The sentence is not the stored one: that may hold an exception's own words, which can name a page, a
 * path or a pointer into the document's text. [ImportJobHandler.messageFor] turns the code into words
 * InfoScry wrote, and an unrecognised code gets a generic sentence rather than the stored text.
 */
@Serializable
data class DocumentDetail(
    val document: DocumentListItem,
    val errorMessage: String? = null,
    /** The lowest-ordinal content unit, or null when the document has no readable content yet. */
    val sourceId: String? = null,
    /** The same counts the row shows, read for one document rather than for a page of them. */
    val progress: DocumentProgressView? = null,
    /**
     * Whether Retry is offered for this document: its status has unfinished business (`FAILED`,
     * `CANCELLED`, `NEEDS_TOOL`), nothing is deleting it, and its managed bytes are still there.
     *
     * Admission checks the same things and can still refuse for a reason only it knows — a missing tool, a
     * model that is not installed, an import that still holds the document — so this is what makes the action
     * appear where it is plausible, not a promise that it will be accepted.
     */
    val retryEligible: Boolean = false,
    /**
     * InfoScry's own sentences for the codes of the units the current attempt could not read.
     *
     * A document can finish with warnings while its own status is complete, so these are what says which
     * parts of it are missing and why. The document's own sentence is not repeated here: it is already
     * [errorMessage].
     */
    val warnings: List<String> = emptyList(),
    /** The same document-level quality score the row carries, absent unless the document is complete. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val qualityScore: Double? = null,
)

/**
 * `GET /api/collections/{id}/documents?q=&status=&sort=&limit=&offset=` — one page of a collection's
 * documents, filtered and ordered on the server.
 *
 * A read like every other read route: loopback-only, no credential (the guard in `configureRoutes`
 * already answered the caller who was not on loopback). [limit] defaults to 50, is clamped to at most
 * [MAX_DOCUMENTS_PER_PAGE], and anything that is not a positive whole number is a typed 400, never a
 * crash and never a silent default. [offset] defaults to 0 and only negative or non-numeric values are
 * refused.
 *
 * `q` is a literal case-insensitive contains match on the original filename. It never searches the
 * external source path, and its `%`, `_` and backslash match themselves (see [DocumentListing]). `status`
 * may repeat and names any domain status; `sort` is `newest` (the default), `oldest`, `name-asc` or
 * `name-desc`. A status or sort the server does not know is a typed 400 rather than a silently different
 * answer. Filtering, ordering and the total all use the same criteria, so the total always describes the
 * rows the page was drawn from.
 *
 * An unknown or deleting collection is the same 404 the other collection routes answer, via the shared
 * `requireActive` boundary.
 *
 * `POST /api/collections/{id}/documents/retry` reads documents InfoScry already holds again, from their
 * managed copies, without classifying them as new files. It either names the documents or asks for every
 * eligible one, never both; one request is one durable attempt, and the answer says which documents it
 * accepted (as job ids) and which it refused, each with a sentence that says why.
 */
fun Routing.configureDocumentRoutes(context: AppContext) {
    route("/api/collections/{id}/documents") {
        get {
            call.handle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val limit = call.request.queryParameters["limit"]?.let { parameter ->
                    parameter.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw BadRequestException("limit must be a whole number greater than zero, was '$parameter'")
                } ?: DEFAULT_DOCUMENTS_PER_PAGE
                val offset = call.request.queryParameters["offset"]?.let { parameter ->
                    parameter.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw BadRequestException("offset must be a whole number, zero or greater, was '$parameter'")
                } ?: 0
                val listing = DocumentListing(
                    collectionId = collection.id,
                    // A blank term is no term: the reader cleared the search box rather than searching for "".
                    filenameContains = call.request.queryParameters["q"]?.takeIf(String::isNotBlank),
                    statuses = statusesOf(call.request.queryParameters.getAll("status").orEmpty()),
                    sort = sortOf(call.request.queryParameters["sort"]),
                )
                val documents = context.documents.listListing(listing, minOf(limit, MAX_DOCUMENTS_PER_PAGE), offset)
                // One batched read for the whole page: a progress cell per row must not be a query per row.
                val progress = context.content.documentProgress(documents.map { it.id })
                // Only complete documents are scored, so the text is read for those alone and in one batch too.
                val texts = context.content.extractedTextsOf(
                    documents.filter { it.status == DocumentStatus.COMPLETE }.map { it.id },
                )
                call.respondJson(
                    HttpStatusCode.OK,
                    DocumentsResponse(
                        documents = documents.map {
                            it.toListItem(progress[it.id]?.toView(), it.qualityScoreFrom { texts[it.id] })
                        },
                        total = context.documents.countListing(listing),
                    ),
                )
            }
        }

        // The collection-wide aggregate. A static segment resolves ahead of `{documentId}` (a constant
        // path segment outranks a path parameter in Ktor's routing quality), so `/summary` can never be
        // mistaken for a document id, and it is declared beside the document route it must coexist with.
        // The guards are the listing's own: loopback (in `configureRoutes`), no credential, and
        // `requireActiveByNameOrId` answering an unknown or deleting collection with the same 404.
        get("/summary") {
            call.handle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val byStatus = context.documents.statusCountsByCollection(collection.id)
                // The total is the same grouped snapshot the counts came from, so the two agree by
                // construction. Listing query parameters are not read here by design: a summary that
                // answered to `q` or `status` would describe a filtered subset of rows, not the collection.
                call.respondJson(
                    HttpStatusCode.OK,
                    DocumentSummaryResponse(total = byStatus.values.sum(), byStatus = byStatus),
                )
            }
        }

        get("/{documentId}") {
            call.handle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val rawDocumentId = call.parameters["documentId"]?.takeIf(String::isNotBlank)
                    ?: throw BadRequestException("a document needs an id")
                // Scoped: another collection's document is answered exactly like one that does not exist,
                // so a response can never confirm that an id belongs to a collection the caller did not name.
                val document = context.documents.get(DocumentId(rawDocumentId))
                    ?.takeIf { it.collectionId == collection.id }
                    ?: throw NoSuchElementException("no document with id $rawDocumentId exists in this collection")
                val progress = context.content.documentProgress(listOf(document.id))[document.id]
                val errorMessage = document.errorCode?.let { ImportJobHandler.messageFor(it) }
                val qualityScore = document.qualityScoreFrom {
                    context.content.extractedTextsOf(listOf(document.id))[document.id]
                }
                call.respondJson(
                    HttpStatusCode.OK,
                    DocumentDetail(
                        document = document.toListItem(progress?.toView(), qualityScore),
                        errorMessage = errorMessage,
                        qualityScore = qualityScore,
                        sourceId = context.content.listUnits(document.id, afterOrdinal = -1, limit = 1)
                            .firstOrNull()?.id?.value,
                        progress = progress?.toView(),
                        // Retry is offered for work that stopped without finishing the document, and never for
                        // one whose managed copy is gone: there would be nothing to read.
                        retryEligible = context.retryService.isEligible(document) &&
                            java.nio.file.Files.isRegularFile(context.library.managedPathOf(document)),
                        // The document's own sentence is not repeated: a refusal about the whole document is
                        // what `errorMessage` already says, and these are the units that failed beside it.
                        warnings = progress?.failedCodes.orEmpty()
                            .map { ImportJobHandler.messageFor(it) }
                            .filter { it != errorMessage }
                            .distinct(),
                    ),
                )
            }
        }

        // Explicit documents, explicitly confirmed. Admission is durable before this answers and the
        // destructive phases continue in the server's own process, so 202 says exactly that: accepted,
        // not done. A caller follows the operation on `GET /api/deletions/{operationId}`.
        post("/delete") {
            call.handle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val request = call.receiveJson<DeleteDocumentsRequest>()
                if (!request.confirmed) {
                    throw BadRequestException("deleting documents has to be explicitly confirmed")
                }
                if (request.documentIds.isEmpty()) {
                    throw BadRequestException("a document deletion needs at least one document id")
                }
                if (request.documentIds.size > MAX_DOCUMENTS_PER_DELETION) {
                    throw BadRequestException(
                        "a document deletion removes at most $MAX_DOCUMENTS_PER_DELETION documents, " +
                            "was ${request.documentIds.size}",
                    )
                }
                val documentIds = request.documentIds.map { raw ->
                    raw.takeIf(String::isNotBlank)?.let(::DocumentId)
                        ?: throw BadRequestException("a document id in the request was blank")
                }
                // The service validates every id against the active collection before it records
                // anything, so an unknown or cross-collection id rejects the whole request with no side
                // effect. That check runs under the exclusive maintenance permit, which is also what
                // drains the bounded mutation stages a running import is inside.
                val operation = context.documentService.requestDeletion(collection.id, documentIds)
                call.respondJson(
                    HttpStatusCode.Accepted,
                    DeleteDocumentsResponse(
                        operationId = operation.id,
                        collectionId = collection.id.value,
                        documentIds = operation.targets.map { it.documentId.value },
                        phase = operation.phase.name,
                    ),
                )
            }
        }

        // Reading existing documents again from their managed copies. Not an import: this names document
        // identifiers, never paths, so it neither copies bytes nor classifies any as a duplicate — and the
        // response says which documents were accepted (as job ids) and which were refused, with a sentence
        // for each refusal. The body is authoritative about that distinction, because a request that named
        // ten documents may legitimately queue nine attempts and refuse one.
        post("/retry") {
            call.handle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val request = call.receiveJson<RetryDocumentsRequest>()
                if (request.allEligible && request.documentIds.isNotEmpty()) {
                    throw BadRequestException(
                        "a retry either names the documents it reads again or asks for every eligible one, not both",
                    )
                }
                if (!request.allEligible && request.documentIds.isEmpty()) {
                    throw BadRequestException(
                        "a retry needs at least one document id, or allEligible: true for every eligible document",
                    )
                }
                if (request.documentIds.size > MAX_DOCUMENTS_PER_RETRY) {
                    throw BadRequestException(
                        "a retry reads at most $MAX_DOCUMENTS_PER_RETRY documents, was ${request.documentIds.size}",
                    )
                }
                val admission = if (request.allEligible) {
                    context.retryService.admitRetryOfEligible(collection.id, request.ocr)
                } else {
                    val documentIds = request.documentIds.map { raw ->
                        raw.takeIf(String::isNotBlank)?.let(::DocumentId)
                            ?: throw BadRequestException("a document id in the request was blank")
                    }
                    context.retryService.admitRetry(collection.id, documentIds, request.ocr)
                }
                call.respondJson(
                    HttpStatusCode.Accepted,
                    RetryDocumentsResponse(
                        collectionId = collection.id.value,
                        acceptedJobIds = admission.acceptedJobIds,
                        rejected = admission.rejected.map { RejectedRetryView(it.documentId, it.reason, it.code) },
                    ),
                )
            }
        }
    }
}

/**
 * What a caller asks for: either the documents to read again, or every eligible one in the collection.
 *
 * The two are mutually exclusive rather than merged, because "these documents" and "whatever is eligible"
 * are different requests: one may fail a document the user named, and the other picks the set itself.
 */
@Serializable
data class RetryDocumentsRequest(
    val documentIds: List<String> = emptyList(),
    val allEligible: Boolean = false,
    /**
     * The OCR method to read with instead of the collection's, or absent to keep the collection's settings.
     * It is validated and admitted like a rescan preview, and applies to every document of the request.
     */
    val ocr: infoscry.document.RetryOcrChoice? = null,
)

/** One document a retry refused, and InfoScry's own sentence for the reason. */
@Serializable
data class RejectedRetryView(val documentId: String, val reason: String, val code: String? = null)

/**
 * What an admitted retry answers with: the attempts that were queued, and the documents that were not.
 *
 * An empty [acceptedJobIds] says the request was answered without queueing anything — every document it
 * named was refused, and [rejected] says why for each.
 */
@Serializable
data class RetryDocumentsResponse(
    val collectionId: String,
    val acceptedJobIds: List<String>,
    val rejected: List<RejectedRetryView>,
)

/** What a caller asks for: the exact documents to remove, and the explicit confirmation of it. */
@Serializable
data class DeleteDocumentsRequest(
    val documentIds: List<String> = emptyList(),
    val confirmed: Boolean = false,
)

/**
 * What an admitted document deletion answers with: the operation to follow, the collection it removes
 * from, the documents it targets, and the phase it has already recorded.
 */
@Serializable
data class DeleteDocumentsResponse(
    val operationId: String,
    val collectionId: String,
    val documentIds: List<String>,
    val phase: String,
)

private const val DEFAULT_DOCUMENTS_PER_PAGE = 50

/** The largest page a caller may ask for; a larger [limit] is clamped down to this. */
private const val MAX_DOCUMENTS_PER_PAGE = 200

/** How many documents one deletion request may name, so one confirmation is one bounded operation. */
private const val MAX_DOCUMENTS_PER_DELETION = 200

/** How many documents one retry request may name, so one admission is one bounded attempt. */
private const val MAX_DOCUMENTS_PER_RETRY = 200

/** The order a listing asked for, or a typed refusal naming the values that exist. */
private fun sortOf(parameter: String?): DocumentSort = when (parameter?.lowercase()) {
    null, "newest" -> DocumentSort.NEWEST
    "oldest" -> DocumentSort.OLDEST
    "name-asc" -> DocumentSort.NAME_ASC
    "name-desc" -> DocumentSort.NAME_DESC
    else -> throw BadRequestException(
        "sort must be one of newest, oldest, name-asc, name-desc, was '$parameter'",
    )
}

/** The statuses a listing is restricted to; an unknown name is refused instead of matching nothing. */
private fun statusesOf(parameters: List<String>): Set<DocumentStatus> = parameters.map { parameter ->
    DocumentStatus.entries.firstOrNull { it.name.equals(parameter, ignoreCase = true) }
        ?: throw BadRequestException(
            "status must be one of ${DocumentStatus.entries.joinToString()}, was '$parameter'",
        )
}.toSet()

/**
 * The document-level quality score, or null for any document that is not complete. The status gate comes first, so
 * the unit texts are read only for a complete document; [unitTexts] is not called otherwise.
 */
private inline fun Document.qualityScoreFrom(unitTexts: () -> List<String>?): Double? {
    if (status != DocumentStatus.COMPLETE) return null
    return OcrQualityScorer.documentScoreOfTexts(unitTexts().orEmpty())
}

private fun Document.toListItem(progress: DocumentProgressView? = null, qualityScore: Double? = null) = DocumentListItem(
    id = id,
    collectionId = collectionId,
    mediaType = mediaType,
    originalFilename = originalFilename,
    sizeBytes = sizeBytes,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    title = title,
    author = author,
    language = language,
    errorCode = errorCode,
    progress = progress,
    qualityScore = qualityScore,
)

/** The stored counts as the wire shows them: the same fields, none of the failed codes. */
private fun DocumentProgress.toView() = DocumentProgressView(
    unitKind = unitKind,
    totalUnits = totalUnits,
    processedUnits = processedUnits,
    failedUnits = failedUnits,
    directTextUnits = directTextUnits,
    ocrUnits = ocrUnits,
)