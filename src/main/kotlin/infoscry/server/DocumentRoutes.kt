package infoscry.server

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
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
)

/**
 * A page of one collection's documents plus the collection's total, so the caller can paginate
 * without asking for the count separately.
 */
@Serializable
data class DocumentsResponse(val documents: List<DocumentListItem>, val total: Int)

/**
 * `GET /api/collections/{id}/documents?limit=&offset=` — the listing the documents page and the
 * source viewers consume, newest first.
 *
 * A read like every other read route: loopback-only, no credential (the guard in `configureRoutes`
 * already answered the caller who was not on loopback). [limit] defaults to 50, is clamped to at
 * most [MAX_DOCUMENTS_PER_PAGE], and anything that is not a positive whole number is a typed 400,
 * never a crash and never a silent default. [offset] defaults to 0 and only negative or non-numeric
 * values are refused. An unknown or deleting collection is the same 404 the other collection routes
 * answer, via the shared `requireActive` boundary.
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
                call.respondJson(
                    HttpStatusCode.OK,
                    DocumentsResponse(
                        documents = context.documents
                            .listByCollection(collection.id, minOf(limit, MAX_DOCUMENTS_PER_PAGE), offset)
                            .map { it.toListItem() },
                        total = context.documents.countByCollection(collection.id),
                    ),
                )
            }
        }
    }
}

private const val DEFAULT_DOCUMENTS_PER_PAGE = 50

/** The largest page a caller may ask for; a larger [limit] is clamped down to this. */
private const val MAX_DOCUMENTS_PER_PAGE = 200

private fun Document.toListItem() = DocumentListItem(
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
)