package infoscry.server

import infoscry.AppContext
import infoscry.collection.DeletionRecoveryBlockedException
import infoscry.collection.OcrSettingsUpdate
import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.ImportPreviewRequest as ServiceImportPreviewRequest
import infoscry.jobs.ImportStartRequest
import infoscry.ocr.ReadingMethod
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import io.ktor.http.ContentType
import infoscry.search.SearchUnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import java.nio.file.Path
import org.slf4j.LoggerFactory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The product name the session endpoint reports, so a client can recognise the API it found. */
const val PRODUCT_NAME = "InfoScry"

@Serializable
data class ApiError(val code: String, val message: String, val details: Map<String, String>? = null)

@Serializable
data class ApiErrorResponse(val error: ApiError)

@Serializable
data class CollectionsResponse(val collections: List<Collection>)

@Serializable
data class CollectionResponse(val collection: Collection)

@Serializable
data class CreateCollectionRequest(
    val name: String,
    val description: String? = null,
    val ocrLanguages: String? = null,
)

@Serializable
data class RenameCollectionRequest(val name: String)

/**
 * One collection OCR-settings edit.
 *
 * The path keeps its `ocr-languages` name for compatibility. The body carries the collection's OCR language
 * and one default reading method; either field may be absent to keep its current value.
 */
@Serializable
data class UpdateOcrLanguagesRequest(
    val language: String? = null,
    val defaultMethod: ReadingMethod? = null,
) {

    fun toUpdate(): OcrSettingsUpdate = OcrSettingsUpdate(
        language = language,
        defaultMethod = defaultMethod,
    )
}

/** A collection's ignore patterns, as read and as saved: one entry per line, in order. */
@Serializable
data class IgnorePatternsBody(val patterns: List<String>)

@Serializable
data class DeleteCollectionRequest(val confirmName: String)

/**
 * What an admitted collection deletion answers with: the operation to follow, the collection it
 * removes, and the phase it has already recorded.
 *
 * The deletion is not finished when this is sent — the phases after admission take minutes — so the
 * caller follows [operationId] on `GET /api/deletions/{operationId}` to know when it is.
 */
@Serializable
data class DeleteCollectionResponse(val operationId: String, val collectionId: String, val phase: String)

@Serializable
data class JobApiView(
    val id: JobId,
    val type: JobType,
    val state: JobState,
    val createdAt: String,
    val updatedAt: String,
    val collectionId: CollectionId? = null,
    val stage: String? = null,
    /** The file the job is working on now, as its own name; never the path it was selected from. */
    val currentItem: String? = null,
    val currentProgress: DocumentProgressView? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val errorCode: String? = null,
    val cancelRequested: Boolean = false,
    /** Who started the job. */
    val startedBy: infoscry.domain.UserRef? = null,
    /** Who asked to cancel it. */
    val cancelledBy: infoscry.domain.UserRef? = null,
)

@Serializable
data class JobsResponse(val jobs: List<JobApiView>)

@Serializable
data class JobResponse(val job: JobApiView)

/**
 * A browser import-item row: the file's own name, its outcome, and what the failure amounts to.
 *
 * Only the name is carried, never the persisted absolute source path: the durable import history is a
 * management read, and the path it was selected from is not part of it. A folder import still names the
 * file that failed, because the name is the last segment of that path. The message is not the one stored
 * beside the outcome: that one may be an exception's own words, and [ImportJobHandler.messageFor] turns
 * the outcome's code into a sentence InfoScry wrote. An unrecognised code gets the generic sentence,
 * never the stored text.
 */
@Serializable
data class ImportItemApiView(
    val id: String,
    val jobId: JobId,
    val documentId: infoscry.domain.DocumentId?,
    val sourceName: String?,
    val outcome: ImportItemOutcome,
    val errorCode: String?,
    val errorMessage: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * What a caller asks for: the collection by name or id, and the files it uploaded to it. Imports come only
 * from the client's computer: a folder is uploaded file by file, so the server never reads its own folders.
 */
@Serializable
data class ImportRequest(
    val collection: String,
    /** Files uploaded to the collection, by upload id. */
    val uploads: List<String>,
    // Extension lists; both default to empty, which means no filter.
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val method: String,
    val previewHash: String,
    val requestId: String,
    /** CLI permits a later identical command to replace a failed or cancelled import. */
    val restartStopped: Boolean = false,
)

@Serializable
data class ImportPreviewRouteRequest(
    val collection: String,
    /** Files uploaded to the collection, by upload id. */
    val uploads: List<String>,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val method: String,
)

/**
 * The answer to an import request.
 *
 * `accepted` is the honest word for it: the job is durable and a worker will run it, which is not the
 * same as the import being finished. A caller that needs the outcome asks for the job, or passes `--wait`.
 */
@Serializable
data class ImportAcceptedResponse(val accepted: Boolean, val job: JobApiView)

/**
 * Files' results, so a caller can report which documents failed and why: one page of the job's files and
 * how many the job has in all.
 */
@Serializable
data class ImportItemsResponse(val items: List<ImportItemApiView>, val total: Int)

/**
 * The wire format, in one place.
 *
 * Unknown keys are ignored so a newer client does not break an older server, and defaults are encoded
 * so a client can rely on every documented field being present.
 */
val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * The same wire format, refusing a body that carries a field the contract never asked for.
 *
 * [ApiJson] ignores unknown keys so a newer client does not break an older server, and every request body
 * whose extra fields can only ever be additive wants that. A body that names an environment variable is the
 * exception: it has no slot a key *value* may occupy, so the only thing an unknown field can be there is a
 * secret pasted into the wrong place. Dropping it silently would let the caller believe the value was
 * accepted, so this variant refuses the body instead.
 */
val ApiJsonRejectingUnknownFields = Json(ApiJson) {
    ignoreUnknownKeys = false
}

/**
 * The local HTTP API and the compiled frontend.
 *
 * Handlers stay thin: they parse, call one service method, and render the result or the failure. Status
 * codes are the contract the CLI and the browser read — 423 for "maintenance is running, try later",
 * 409 for "that deletion cannot be finished or that name is taken", 404 for "no such collection",
 * 400 for a request that is malformed or not confirmed.
 */
fun Application.configureRoutes(
    context: AppContext,
    settings: ServerSettings,
    jobEventIdleDeadlineMillis: Long = DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS,
) {
    installRequestGuard(context.accounts, settings, CollectionAccess(context))

    routing {
        configureAccountRoutes(context.accounts, settings)
        configureMembershipRoutes(context)
        configureUploadRoutes(context)

        route("/api/collections") {
            get {
                call.handle {
                    // A caller sees the collections they are a member of; an administrator sees every one.
                    val visible = call.visibleCollections()
                    val listed = context.collectionService.list().filter { visible == null || it.id in visible }
                        .map { it.copy(callerRole = call.collectionRole(it.id)?.name) }
                    call.respondJson(HttpStatusCode.OK, CollectionsResponse(listed))
                }
            }
            post {
                call.handle {
                    val request = call.receiveJson<CreateCollectionRequest>()
                    val created = context.collectionService.create(
                        name = request.name,
                        description = request.description,
                        ocrLanguages = request.ocrLanguages?.takeIf { it.isNotBlank() }
                            ?: infoscry.storage.CollectionStore.DEFAULT_OCR_LANGUAGES,
                        owner = call.principal.user.id,
                    )
                    call.respondJson(HttpStatusCode.Created, CollectionResponse(created))
                }
            }
        }

        route("/api/collections/{id}") {
            patch {
                call.handle {
                    val id = call.collectionId()
                    val request = call.receiveJson<RenameCollectionRequest>()
                    call.respondJson(
                        HttpStatusCode.OK,
                        CollectionResponse(context.collectionService.rename(id, request.name, call.ifMatchVersion())),
                    )
                }
            }
            // Admission is durable before this answers and the destructive work continues in the
            // server's own process, so a caller that goes away, or never reads the answer, cannot lose
            // the deletion. 202 says exactly that: accepted, not done.
            delete {
                call.handle {
                    val id = call.collectionId()
                    val request = call.receiveJson<DeleteCollectionRequest>()
                    val operation = context.collectionService.requestDeletion(id, request.confirmName, call.principal.user.id)
                    call.respondJson(
                        HttpStatusCode.Accepted,
                        DeleteCollectionResponse(
                            operationId = operation.id,
                            collectionId = id.value,
                            phase = operation.phase.name,
                        ),
                    )
                }
            }

            // The collection's ignore patterns: read, and replaced whole. A save is a mutation like the OCR settings
            // save beside it (CSRF, the mutation gate, the collection's own lifecycle), and an invalid pattern is
            // refused here with a 400 rather than discovered by a later import.
            get("/ignore-patterns") {
                call.handle {
                    val id = call.collectionId()
                    call.respondJson(
                        HttpStatusCode.OK,
                        IgnorePatternsBody(context.collectionService.ignorePatterns(id).patterns),
                    )
                }
            }

            put("/ignore-patterns") {
                call.handle {
                    val id = call.collectionId()
                    val request = call.receiveJson<IgnorePatternsBody>()
                    call.respondJson(
                        HttpStatusCode.OK,
                        IgnorePatternsBody(context.collectionService.updateIgnorePatterns(id, request.patterns, call.ifMatchVersion()).patterns),
                    )
                }
            }

            collectionPromptRoutes(context)

            correctionRoutes(context)

            glossaryRoutes(context)

            patch("/ocr-languages") {
                call.handle {
                    val id = call.collectionId()
                    val request = call.receiveJson<UpdateOcrLanguagesRequest>()
                    call.respondJson(
                        HttpStatusCode.OK,
                        CollectionResponse(context.collectionService.updateOcrSettings(id, request.toUpdate(), call.ifMatchVersion())),
                    )
                }
            }
        }

        route("/api/imports") {
            post("/preview") {
                call.handle {
                    // A field outside the contract (a server path from an older client, say) is refused, not silently dropped.
                    val request = call.receiveJsonRejectingUnknownFields<ImportPreviewRouteRequest>()
                    // Only the collection's own uploads are read, and the preview checks that again; it needs an editor.
                    call.requireCollectionRole(request.collection, infoscry.account.CollectionRole.EDITOR)
                    val method = try {
                        ReadingMethod.parse(request.method)
                    } catch (_: IllegalArgumentException) {
                        throw BadRequestException("unknown reading method")
                    }
                    val collection = context.collectionService.requireActiveByNameOrId(request.collection)
                    val preview = context.importPreviewService.preview(
                        ServiceImportPreviewRequest(
                            collection = collection.id,
                            paths = context.uploadPaths(collection.id, request.uploads),
                            recursive = false,
                            include = request.include,
                            exclude = request.exclude,
                            method = method,
                        ),
                    )
                    // An uploaded file is named as the person knows it, not by where the server keeps it.
                    val uploadArea = context.uploadAreas.uploadArea(collection.id).toString()
                    call.respondJson(
                        HttpStatusCode.OK,
                        preview.copy(files = preview.files.map { file ->
                            if (file.path.startsWith(uploadArea)) file.copy(path = java.nio.file.Path.of(file.path).fileName.toString()) else file
                        }),
                    )
                }
            }
            post {
                call.handle {
                    val request = call.receiveJsonRejectingUnknownFields<ImportRequest>()
                    call.requireCollectionRole(request.collection, infoscry.account.CollectionRole.EDITOR)
                    val uploaded = context.uploadPaths(context.collectionService.requireActiveByNameOrId(request.collection).id, request.uploads)
                    val method = try {
                        ReadingMethod.parse(request.method)
                    } catch (_: IllegalArgumentException) {
                        throw BadRequestException("unknown reading method")
                    }
                    val job = context.importStartService.start(
                        ImportStartRequest(
                            collectionId = CollectionId(request.collection),
                            paths = uploaded,
                            recursive = false,
                            include = request.include,
                            exclude = request.exclude,
                            method = method,
                            previewHash = request.previewHash,
                            requestId = request.requestId,
                            restartStopped = request.restartStopped,
                        ),
                    )
                    call.respondJson(
                        HttpStatusCode.Accepted,
                        ImportAcceptedResponse(accepted = true, job = job.toApiView(context)),
                    )
                }
            }
        }

        route("/api/jobs") {
            get {
                call.handle {
                    val limit = call.request.queryParameters["limit"]?.let { parameter ->
                        parameter.toIntOrNull()?.takeIf { it > 0 }
                            ?: throw BadRequestException("limit must be a whole number greater than zero, was '$parameter'")
                    } ?: DEFAULT_JOB_PAGE
                    val offset = call.request.queryParameters["offset"]?.let { parameter ->
                        parameter.toIntOrNull()?.takeIf { it >= 0 }
                            ?: throw BadRequestException("offset must be a whole number, zero or greater, was '$parameter'")
                    } ?: 0
                    // `active=true` lists only queued and running jobs, oldest first, for the Admin Jobs tab.
                    val active = call.request.queryParameters["active"]?.let { parameter ->
                        parameter.toBooleanStrictOrNull()
                            ?: throw BadRequestException("active must be true or false, was '$parameter'")
                    } ?: false
                    val visible = call.visibleCollections()
                    val jobs = if (active) context.jobs.listActive(limit, offset, visible) else context.jobs.list(limit, offset, visible)
                    call.respondJson(HttpStatusCode.OK, JobsResponse(jobs.map { it.toApiView(context) }))
                }
            }

            get("/{id}") {
                call.handle {
                    val jobId = call.jobId()
                    val job = context.jobs.get(jobId)
                        ?: throw NoSuchElementException("no job with id ${jobId.value}")
                    call.respondJson(HttpStatusCode.OK, JobResponse(job.toApiView(context)))
                }
            }

            get("/{id}/items") {
                call.handle {
                    val jobId = call.jobId()
                    // An unknown job is a 404 rather than an empty list: "no results" and "no such job" are
                    // different answers, and a caller that polls has to be able to tell them apart.
                    if (context.jobs.get(jobId) == null) {
                        throw NoSuchElementException("no job with id ${jobId.value}")
                    }
                    // A page when a limit is given, as the browser always asks; without one the whole job,
                    // which is what the CLI's end-of-import report reads.
                    val limit = call.request.queryParameters["limit"]?.let { parameter ->
                        parameter.toIntOrNull()?.takeIf { it > 0 }
                            ?: throw BadRequestException("limit must be a whole number greater than zero, was '$parameter'")
                    }
                    val offset = call.request.queryParameters["offset"]?.let { parameter ->
                        parameter.toIntOrNull()?.takeIf { it >= 0 }
                            ?: throw BadRequestException("offset must be a whole number, zero or greater, was '$parameter'")
                    } ?: 0
                    val items = if (limit == null) {
                        context.importItems.listForJob(jobId).drop(offset)
                    } else {
                        context.importItems.pageForJob(jobId, minOf(limit, MAX_JOB_ITEMS_PER_PAGE), offset)
                    }
                    call.respondJson(
                        HttpStatusCode.OK,
                        ImportItemsResponse(items.map(ImportItem::toApiView), context.importItems.countForJob(jobId)),
                    )
                }
            }

            // The cancellation request is persisted before anything is signalled, so a caller that sees
            // this answer knows the request outlives this process.
            post("/{id}/cancel") {
                call.handle {
                    val jobId = call.jobId()
                    call.respondJson(HttpStatusCode.OK, JobResponse(context.cancelJob(jobId).toApiView(context)))
                }
            }
        }

        configureSearchRoutes(context, context.mutations)
        configureSourceRoutes(context)
        configureSelectablePdfRoutes(context)
        configurePageReviewRoutes(context)
        configureLlmProfileRoutes(context)
        configureOcrProfileRoutes(context)
        configureReadingMethodRoutes(context)
        configureOcrLlmCandidateRoutes(context)
        configureOcrRescanRoutes(context)
        configureLlmCatalogRoutes()
        configureAskRoutes(context)
        configureInvestigationRoutes(context)
        configureConversationRoutes(context)
        configureDocumentRoutes(context)
        configureImportHistoryRoutes(context)
        configureDeletionRoutes(context)
        configureJobEventRoutes(context, jobEventIdleDeadlineMillis)

        // The compiled SvelteKit application. Its client-side routes all fall back to this file.
        // Give generated assets their own specific route. The generic client-side fallback below
        // otherwise wins for nested asset paths and serves HTML where the browser expects JS/CSS.
        staticResources("/_app", "$STATIC_RESOURCES/_app", index = null)
        staticResources("/", STATIC_RESOURCES, index = "index.html")

        // The frontend routes in the browser, so reloading a deep link has to reach the shell instead of
        // a 404. API paths are excluded: an unknown API route is a mistake, and answering it with HTML
        // would hide that from the caller.
        get("{...}") {
            if (call.request.path().startsWith(API_PREFIX)) {
                call.respondJson(
                    HttpStatusCode.NotFound,
                    ApiErrorResponse(ApiError(code = "NOT_FOUND", message = "no such API route")),
                )
            } else {
                call.respondApplicationShell()
            }
        }
    }
}

private const val STATIC_RESOURCES = "static"

/** How many jobs one page of `GET /api/jobs` returns when the caller does not ask for a size. */
private const val DEFAULT_JOB_PAGE = 100
private const val MAX_JOB_ITEMS_PER_PAGE = 500

private const val API_PREFIX = "/api/"

/**
 * Serves the application shell for a client-side route.
 *
 * A distribution without the compiled frontend answers with a named error rather than an empty page, so
 * a packaging mistake is visible instead of looking like a broken browser.
 */
private suspend fun ApplicationCall.respondApplicationShell() {
    val shell = javaClass.classLoader.getResource("$STATIC_RESOURCES/index.html")?.readText()
    if (shell == null) {
        respondJson(
            HttpStatusCode.NotFound,
            ApiErrorResponse(
                ApiError(
                    code = "FRONTEND_MISSING",
                    message = "the compiled web application is not in this build; run ./gradlew frontendBuild",
                ),
            ),
        )
        return
    }
    respondText(shell, ContentType.Text.Html, HttpStatusCode.OK)
}

suspend inline fun <reified T> ApplicationCall.respondJson(status: HttpStatusCode, value: T) {
    respondText(ApiJson.encodeToString(value), ContentType.Application.Json, status)
}

/** Decodes the request body, naming a malformed body as such instead of failing the server. */
suspend inline fun <reified T> ApplicationCall.receiveJson(): T {
    val body = receiveText()
    return try {
        ApiJson.decodeFromString(body)
    } catch (failure: SerializationException) {
        throw BadRequestException("the request body is not valid ${T::class.simpleName} JSON: ${failure.message}")
    }
}

/**
 * Decodes the request body, refusing any key the contract does not have a field for.
 *
 * The failure deliberately says nothing about *what* was in the body: the unknown key's own name and the
 * decoder's positional detail can quote the surrounding input, and the one body this is used for is a place
 * a caller pastes a credential. "This body has a field this endpoint does not accept" is everything the
 * caller needs to fix it and nothing a log should keep.
 */
suspend inline fun <reified T> ApplicationCall.receiveJsonRejectingUnknownFields(): T {
    val body = receiveText()
    return try {
        ApiJsonRejectingUnknownFields.decodeFromString(body)
    } catch (_: SerializationException) {
        throw BadRequestException(
            "the request body has a field this endpoint does not accept, or is not valid " +
                "${T::class.simpleName} JSON",
        )
    }
}

fun ApplicationCall.jobId(): JobId {
    val raw = parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a job id is required in the path")
    return JobId(raw)
}

/**
 * The record version an edit was made against, from `If-Match` (`"3"`, `W/"3"` or `3`); `null` when the
 * caller sent none, in which case the edit applies to whatever is current.
 */
fun ApplicationCall.ifMatchVersion(): Long? {
    val raw = request.headers[io.ktor.http.HttpHeaders.IfMatch]?.trim() ?: return null
    if (raw == "*") return null
    return raw.removePrefix("W/").trim('"').toLongOrNull()
        ?: throw BadRequestException("If-Match must name a record version")
}

fun ApplicationCall.collectionId(): CollectionId {
    val raw = parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a collection id is required in the path")
    return CollectionId(raw)
}

internal fun Job.toApiView(context: AppContext) = JobApiView(
    id = id,
    type = type,
    state = state,
    createdAt = createdAt,
    updatedAt = updatedAt,
    collectionId = collectionId,
    stage = stage,
    currentItem = currentItem,
    currentProgress = currentImportProgress(context),
    completed = completed,
    total = total,
    errorCode = errorCode,
    cancelRequested = cancelRequested,
    startedBy = context.attribution.userRef(startedBy),
    cancelledBy = context.attribution.userRef(cancelledBy),
)

private fun ImportItem.toApiView() = ImportItemApiView(
    id = id,
    jobId = jobId,
    documentId = documentId,
    sourceName = sourcePath.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotEmpty),
    outcome = outcome,
    errorCode = errorCode,
    errorMessage = errorCode?.let { ImportJobHandler.messageFor(it) },
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** A request the caller can fix. */
class BadRequestException(message: String) : IllegalArgumentException(message)

/**
 * Renders the outcome of one handler: the value it produced, or the status that describes its failure.
 *
 * Mapping happens here, once, so no handler has to remember which exception means which status — and so
 * a failure that is not recognised becomes a plain 500 with a message that says nothing about InfoScry's
 * internals.
 */
suspend fun ApplicationCall.handle(block: suspend () -> Unit) {
    try {
        block()
    } catch (refused: SearchUnavailableException) {
        // A search can be refused for a reason the caller can fix (an over-long query, an over-broad
        // filter) or one the environment has to fix (no model, no usable GPU, an index that needs a
        // rebuild). The code and the remedy travel either way, and the status names which kind it is.
        // The lookup itself stays here rather than on the exception so infoscry.search need not depend
        // on an HTTP status type.
        respondJson(
            searchFailureStatus(refused.code),
            ApiErrorResponse(ApiError(code = refused.code, message = refused.remedy)),
        )
    } catch (blocked: DeletionRecoveryBlockedException) {
        // The exception's own message names managed and parked directories for the log; the client gets the
        // curated sentence, which carries the phase and the error code instead of any path. The logging is
        // this exception's own, so it keeps a dedicated clause rather than the shared ApiFaultException one.
        LOGGER.atWarn().addKeyValue(ERROR_DETAIL_FIELD, blocked.message.orEmpty())
            .log("a mutating command was refused while a deletion recovery is unresolved")
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "DELETION_RECOVERY_BLOCKED", message = blocked.clientMessage)),
        )
    } catch (fault: ApiFaultException) {
        // Every other domain refusal answers for itself: the status family, the stable code, and the
        // sentence a client may see. See infoscry.domain.ApiFaultException.
        respondJson(
            fault.apiStatus.toHttpStatusCode(),
            ApiErrorResponse(ApiError(code = fault.code, message = fault.clientMessage(), details = fault.details())),
        )
    } catch (missing: NoSuchElementException) {
        respondJson(
            HttpStatusCode.NotFound,
            ApiErrorResponse(ApiError(code = "NOT_FOUND", message = missing.message.orEmpty())),
        )
    } catch (invalid: BadRequestException) {
        respondJson(
            HttpStatusCode.BadRequest,
            ApiErrorResponse(ApiError(code = "INVALID_REQUEST", message = invalid.message.orEmpty())),
        )
    } catch (invalid: IllegalArgumentException) {
        respondJson(
            HttpStatusCode.BadRequest,
            ApiErrorResponse(ApiError(code = "INVALID_REQUEST", message = invalid.message.orEmpty())),
        )
    } catch (failure: Exception) {
        // The client gets a code it can act on. The detail goes to the log, as a named field and as the
        // logged cause, because an internal message can name absolute paths inside the data directory —
        // and it is never interpolated into the message, where named-field redaction cannot see it.
        LOGGER.atError()
            .addKeyValue(ERROR_DETAIL_FIELD, failure.message ?: failure::class.simpleName ?: "unknown failure")
            .setCause(failure)
            .log("a request failed with an unhandled error")
        respondJson(
            HttpStatusCode.InternalServerError,
            ApiErrorResponse(
                ApiError(
                    code = "INTERNAL_ERROR",
                    message = "the request could not be completed; see the InfoScry log for the detail",
                ),
            ),
        )
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.server")

/** The name of the structured field carrying an unhandled failure's own message. */
private const val ERROR_DETAIL_FIELD = "error_detail"

/** The one place an [ApiStatus] becomes the `HttpStatusCode` the client sees. */
internal fun ApiStatus.toHttpStatusCode(): HttpStatusCode = when (this) {
    ApiStatus.BAD_REQUEST -> HttpStatusCode.BadRequest
    ApiStatus.UNAUTHORIZED -> HttpStatusCode.Unauthorized
    ApiStatus.FORBIDDEN -> HttpStatusCode.Forbidden
    ApiStatus.NOT_FOUND -> HttpStatusCode.NotFound
    ApiStatus.TOO_MANY_REQUESTS -> HttpStatusCode.TooManyRequests
    ApiStatus.CONFLICT -> HttpStatusCode.Conflict
    ApiStatus.LOCKED -> HttpStatusCode.Locked
    ApiStatus.SERVICE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
}
