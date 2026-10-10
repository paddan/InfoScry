package infoscry.server

import infoscry.AppContext
import infoscry.document.PageReviewService
import infoscry.document.PageReviewUnavailableException
import infoscry.domain.DocumentId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val PageReviewJson = Json(ApiJson) { explicitNulls = true }

/** Collection/document IDs are resolved locally; neither route accepts paths or dispatches OCR. */
fun Routing.configurePageReviewRoutes(context: AppContext) {
    route("/api/collections/{id}/documents/{documentId}/page-review") {
        get {
            call.pageReviewHandle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val documentId = call.pageReviewDocumentId()
                val ordinal = call.pageReviewOrdinal()
                val revision = call.pageReviewRevision()
                val offset = call.optionalPageReviewInt("offset", 0)
                val limit = call.optionalPageReviewInt("limit", PageReviewService.DEFAULT_TEXT_LIMIT)
                val response = withContext(Dispatchers.IO) {
                    context.pageReviewService.review(collection.id, documentId, ordinal, revision, offset, limit)
                }
                call.respondText(PageReviewJson.encodeToString(response), ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        get("/image") {
            call.pageReviewHandle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val documentId = call.pageReviewDocumentId()
                val ordinal = call.pageReviewOrdinal()
                val revision = call.pageReviewRevision()
                val kind = call.request.queryParameters["kind"] ?: throw BadRequestException("image kind is required")
                val image = withContext(Dispatchers.IO) {
                    context.pageReviewService.image(collection.id, documentId, ordinal, revision, kind)
                }
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.respondOutputStream(ContentType.parse(image.mediaType)) {
                    Files.newInputStream(image.path, LinkOption.NOFOLLOW_LINKS).use { it.copyTo(this) }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.pageReviewHandle(block: suspend () -> Unit) = handle {
    try { block() } catch (unavailable: PageReviewUnavailableException) {
        respondJson(if (unavailable.conflict) HttpStatusCode.Conflict else HttpStatusCode.NotFound,
            ApiErrorResponse(ApiError(unavailable.code, unavailable.message.orEmpty())))
    }
}

private fun ApplicationCall.pageReviewDocumentId(): DocumentId =
    DocumentId(parameters["documentId"]?.takeIf(String::isNotBlank) ?: throw BadRequestException("a document id is required"))

private fun ApplicationCall.pageReviewRevision(): String? = request.queryParameters["revision"]?.let {
    it.takeIf(String::isNotBlank) ?: throw BadRequestException("revision must not be empty")
}

private fun ApplicationCall.pageReviewOrdinal(): Int = request.queryParameters["ordinal"]?.toIntOrNull()
    ?.takeIf { it >= 0 } ?: throw BadRequestException("ordinal must be a whole number, zero or greater")

private fun ApplicationCall.optionalPageReviewInt(name: String, default: Int): Int =
    request.queryParameters[name]?.let { it.toIntOrNull() ?: throw BadRequestException("$name must be a whole number") } ?: default
