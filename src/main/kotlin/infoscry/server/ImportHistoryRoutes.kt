package infoscry.server

import infoscry.AppContext
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * One import of a collection as the history lists it: the job's durable state and stage, how many of its
 * files it has finished, and where its per-file outcomes are read.
 *
 * Deliberately not [Job]: the domain row carries the worker payload ([Job.payload]), which names the
 * absolute paths the user selected, and a raw failure message ([Job.errorMessage]), which may quote the
 * file or the document. Neither crosses. The counters are named for what an import counts — files, one
 * per selected source, never OCR pages. The file being read now crosses by its own name, never by the
 * path it was selected from.
 */
@Serializable
data class ImportHistoryEntry(
    val id: JobId,
    val state: JobState,
    val stage: String? = null,
    val currentItem: String? = null,
    val filesCompleted: Int,
    val filesTotal: Int,
    val errorCode: String? = null,
    val createdAt: String,
    val updatedAt: String,
    /** The persisted per-file outcomes of this import, on the existing job-items route. */
    val itemsUrl: String,
    /** The approval this import waits for, present only while it is paused for external pages. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val externalApproval: ExternalApprovalView? = null,
)

/** A page of one collection's imports plus the total the same criteria match. */
@Serializable
data class ImportsResponse(val imports: List<ImportHistoryEntry>, val total: Int)

/**
 * `GET /api/collections/{id}/imports?limit=&offset=` — one page of a collection's durable import history,
 * newest first.
 *
 * The read is what lets Admin show current and previous imports after a reload, a navigation away, or a
 * server restart: everything here comes from SQLite, not from a browser's memory of an in-flight job.
 *
 * A read like every other read route: loopback-only, no credential (the guard in `configureRoutes`
 * already answered the caller who was not on loopback). [limit] defaults to 50, is clamped to at most
 * [MAX_IMPORTS_PER_PAGE], and anything that is not a positive whole number is a typed 400, never a crash
 * and never a silent default. [offset] defaults to 0 and only negative or non-numeric values are refused.
 * The total describes the whole history, not the page.
 *
 * An unknown or deleting collection is the same 404 the other collection routes answer, via the shared
 * `requireActiveByNameOrId` boundary, so a history read can never confirm an id the caller may not manage.
 */
fun Routing.configureImportHistoryRoutes(context: AppContext) {
    route("/api/collections/{id}/imports") {
        get {
            call.handle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val limit = call.request.queryParameters["limit"]?.let { parameter ->
                    parameter.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw BadRequestException("limit must be a whole number greater than zero, was '$parameter'")
                } ?: DEFAULT_IMPORTS_PER_PAGE
                val offset = call.request.queryParameters["offset"]?.let { parameter ->
                    parameter.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw BadRequestException("offset must be a whole number, zero or greater, was '$parameter'")
                } ?: 0
                call.respondJson(
                    HttpStatusCode.OK,
                    ImportsResponse(
                        imports = context.jobs
                            .listImports(collection.id, minOf(limit, MAX_IMPORTS_PER_PAGE), offset)
                            .map { it.toImportHistoryEntry(context) },
                        total = context.jobs.countImports(collection.id),
                    ),
                )
            }
        }
    }
}

private fun Job.toImportHistoryEntry(context: AppContext) = ImportHistoryEntry(
    id = id,
    state = state,
    stage = stage,
    currentItem = currentItem,
    filesCompleted = completed,
    filesTotal = total,
    errorCode = errorCode,
    createdAt = createdAt,
    updatedAt = updatedAt,
    itemsUrl = "$JOB_ITEMS_PATH_PREFIX/${id.value}/items",
    externalApproval = context.externalApprovalOf(this),
)

/** How many imports one page returns when the caller does not ask for a size. */
private const val DEFAULT_IMPORTS_PER_PAGE = 50

/** The largest page a caller may ask for; a larger [limit] is clamped down to this. */
private const val MAX_IMPORTS_PER_PAGE = 200

/** The existing per-file outcome route, so a history row carries a link rather than a second payload. */
private const val JOB_ITEMS_PATH_PREFIX = "/api/jobs"
