package infoscry.server

import infoscry.AppContext
import infoscry.domain.DocumentStatus
import infoscry.domain.JobType
import infoscry.domain.SourceLocation
import infoscry.jobs.ReindexJobPayload
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import infoscry.search.SearchService
import infoscry.search.LuceneIndex
import infoscry.search.SearchUnavailableException
import infoscry.storage.MutationCoordinator
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** One search hit as the API reports it: the citation, what matched, and the unit to open. */
@Serializable
data class SearchHitResponse(
    val collectionId: String,
    val documentId: String,
    val title: String,
    val unitId: String,
    val chunkOrdinal: Int,
    val text: String,
    val highlighted: String?,
    val locator: SourceLocation,
    val locatorLabel: String,
    val matchedBy: Set<String>,
)

/** What one search returned, plus how many index rows pointed at database rows that no longer exist. */
@Serializable
data class SearchResponse(val hits: List<SearchHitResponse>, val staleFiltered: Int)

/** What `POST /api/reindex` answers: the request was accepted, and a job carries it. */
@Serializable
data class ReindexAcceptedResponse(val accepted: Boolean, val jobId: String, val state: String)

/** A rebuild request. [collection] names one collection; leaving it out rebuilds every collection. */
@Serializable
data class ReindexApiRequest(val collection: String? = null)

/** The filters one search is restricted to. [collection] is mandatory: a search reads one collection. */
@Serializable
data class SearchRequestFilters(
    val collection: String,
    val mode: String? = null,
    val mediaTypes: List<String>? = null,
    val filenameOrPathContains: String? = null,
    val titleAuthorOrLanguageContains: String? = null,
    val importedFrom: String? = null,
    val importedUntil: String? = null,
    val statuses: List<String>? = null,
    val ocrOnly: Boolean = false,
)

/** What a POST /api/search body carries: the query and the same filters the GET parameters name. */
@Serializable
data class SearchRequestBody(val query: String, val filters: SearchRequestFilters)

/**
 * Search and the citations a hit opens.
 *
 * Handlers stay thin: parse, call one service, render. The status codes are the contract — 400 for a
 * request the caller can fix (a missing collection, an over-long query, an over-broad filter), 503 for
 * an environment the caller cannot fix (no model, no usable GPU, an index that needs a rebuild), 404
 * for a collection or unit this archive does not serve, 423 for maintenance.
 */
fun Route.configureSearchRoutes(context: AppContext, coordinator: MutationCoordinator) {
    route("/api/search") {
        get {
            call.handle {
                val request = call.searchRequest()
                call.respondJson(HttpStatusCode.OK, call.runSearch(context, request.filters, request.query))
            }
        }
        post {
            call.handle {
                val body = call.receiveJson<SearchRequestBody>()
                call.respondJson(HttpStatusCode.OK, call.runSearch(context, body.filters, body.query))
            }
        }
    }

    route("/api/reindex") {
        post {
            call.handle {
                val request = call.receiveJson<ReindexApiRequest>()
                // Enqueueing is a write, so it is admitted through the gate like any other mutation: a
                // rebuild already running is exclusive maintenance, and another one queueing behind it
                // would be a job whose whole purpose is to wait for itself.
                coordinator.withMutation {
                    val collectionId = request.collection?.let { reference ->
                        context.collectionService.requireActiveByNameOrId(reference).id
                    }
                    val job = context.jobs.enqueue(
                        type = JobType.REINDEX,
                        collectionId = collectionId,
                        payload = ReindexJobPayload(collectionId?.value).encode(),
                        total = 0,
                    )
                    call.respondJson(
                        HttpStatusCode.Accepted,
                        ReindexAcceptedResponse(accepted = true, jobId = job.id.value, state = job.state.name),
                    )
                }
            }
        }
    }
}

private suspend fun ApplicationCall.runSearch(
    context: AppContext,
    request: SearchRequestFilters,
    query: String,
): SearchResponse {
    // The collection is resolved here, at the boundary, because SearchService legitimately supports a
    // null collection for the rebuild's own assertions; a caller-facing search names one.
    val collection = context.collectionService.requireActiveByNameOrId(request.collection)
    val outcome = context.search.search(
        queryText = query,
        mode = parseSearchMode(request.mode),
        filters = SearchFilters(
            collectionId = collection.id,
            mediaTypes = request.mediaTypes?.toSet() ?: emptySet(),
            filenameOrPathContains = request.filenameOrPathContains,
            titleAuthorOrLanguageContains = request.titleAuthorOrLanguageContains,
            importedFrom = request.importedFrom,
            importedUntil = request.importedUntil,
            statuses = request.statuses?.map { DocumentStatus.valueOf(it) }?.toSet() ?: emptySet(),
            ocrOnly = request.ocrOnly,
        ),
    )
    return SearchResponse(
        hits = outcome.hits.map { hit ->
            val document = context.documents.get(hit.documentId)
            SearchHitResponse(
                collectionId = hit.collectionId.value,
                documentId = hit.documentId.value,
                title = document?.title?.takeIf(String::isNotBlank) ?: document?.originalFilename ?: hit.documentId.value,
                unitId = hit.unitId.value,
                chunkOrdinal = hit.chunkOrdinal,
                text = hit.text,
                highlighted = hit.highlighted,
                locator = hit.locator,
                locatorLabel = hit.locatorLabel,
                matchedBy = hit.matchedBy.map { it.name }.toSet(),
            )
        },
        staleFiltered = outcome.staleFiltered,
    )
}

/** The filters and query one search request names, from the query string or the POST body. */
private data class SearchRequest(val query: String, val filters: SearchRequestFilters)

private fun ApplicationCall.searchRequest(): SearchRequest {
    val parameters = request.queryParameters
    val collection = parameters["collection"]
        ?: throw BadRequestException("collection is required; search reads one collection at a time")
    val query = parameters["q"] ?: throw BadRequestException("q is required; search needs something to look for")
    return SearchRequest(
        query = query,
        filters = SearchRequestFilters(
            collection = collection,
            mode = parameters["mode"],
            mediaTypes = parameters.getAll("mediaType"),
            filenameOrPathContains = parameters["path"],
            titleAuthorOrLanguageContains = parameters["text"],
            importedFrom = parameters["from"],
            importedUntil = parameters["until"],
            statuses = parameters.getAll("status"),
            ocrOnly = parameters["ocrOnly"]?.toBooleanStrictOrNull() ?: false,
        ),
    )
}

internal fun parseSearchMode(mode: String?): SearchMode = mode?.let { value ->
    SearchMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw BadRequestException("mode must be one of ${SearchMode.entries.joinToString()}")
} ?: SearchMode.DEFAULT

/**
 * Maps a refused search to the status its cause deserves.
 *
 * A caller can fix its own query, so those answers are 400. The environment cannot be fixed from the
 * caller's side, so those are 503 with the remedy that fixes it. Either way the code and the remedy
 * travel, because a status alone cannot say whether to shorten the query or to install the model.
 */
internal fun searchFailureStatus(code: String): HttpStatusCode =
    if (code in CLIENT_INPUT_CODES) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable

/** The refusal codes that name a mistake the caller made, rather than an environment problem. */
private val CLIENT_INPUT_CODES: Set<String> = setOf(
    LuceneIndex.QUERY_TOO_LONG_CODE,
    SearchService.FILTER_TOO_BROAD_CODE,
    SearchService.QUERY_TOO_BROAD_CODE,
)
