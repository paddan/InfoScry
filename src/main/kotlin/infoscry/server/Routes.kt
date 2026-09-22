package infoscry.server

import infoscry.AppContext
import infoscry.collection.DeletionRecoveryBlockedException
import infoscry.diagnostics.ToolProbe
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobType
import infoscry.jobs.ImportJobPayload
import infoscry.storage.CollectionConfirmationMismatchException
import infoscry.storage.DuplicateCollectionNameException
import infoscry.storage.MaintenanceInProgressException
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import org.slf4j.LoggerFactory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The product name the session endpoint reports, so a client can recognise the API it found. */
const val PRODUCT_NAME = "InfoScry"

@Serializable
data class ApiError(val code: String, val message: String)

@Serializable
data class ApiErrorResponse(val error: ApiError)

@Serializable
data class SessionResponse(val product: String, val csrfToken: String)

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

@Serializable
data class DeleteCollectionRequest(val confirmName: String)

@Serializable
data class DeleteCollectionResponse(val collectionId: String, val phase: String)

@Serializable
data class JobsResponse(val jobs: List<Job>)

@Serializable
data class JobResponse(val job: Job)

/** What a caller asks for: the collection by name or id, and the files or directories it selected. */
@Serializable
data class ImportRequest(val collection: String, val paths: List<String>)

/**
 * The answer to an import request.
 *
 * `accepted` is the honest word for it: the job is durable and a worker will run it, which is not the
 * same as the import being finished. A caller that needs the outcome asks for the job, or passes `--wait`.
 */
@Serializable
data class ImportAcceptedResponse(val accepted: Boolean, val job: Job)

/** One file's result, so a caller can report which documents failed and why. */
@Serializable
data class ImportItemsResponse(val items: List<infoscry.storage.ImportItem>)

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
 * The local HTTP API and the compiled frontend.
 *
 * Handlers stay thin: they parse, call one service method, and render the result or the failure. Status
 * codes are the contract the CLI and the browser read — 423 for "maintenance is running, try later",
 * 409 for "that deletion cannot be finished or that name is taken", 404 for "no such collection",
 * 400 for a request that is malformed or not confirmed.
 */
fun Application.configureRoutes(context: AppContext, credentials: ApiCredentials) {
    installRequestGuard(credentials)

    routing {
        get("/api/session") {
            call.respondJson(HttpStatusCode.OK, SessionResponse(product = PRODUCT_NAME, csrfToken = credentials.csrfToken))
        }

        route("/api/collections") {
            get {
                call.handle {
                    call.respondJson(HttpStatusCode.OK, CollectionsResponse(context.collectionService.list()))
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
                        CollectionResponse(context.collectionService.rename(id, request.name)),
                    )
                }
            }
            delete {
                call.handle {
                    val id = call.collectionId()
                    val request = call.receiveJson<DeleteCollectionRequest>()
                    val operation = context.collectionService.deleteConfirmed(id, request.confirmName)
                    call.respondJson(
                        HttpStatusCode.OK,
                        DeleteCollectionResponse(collectionId = id.value, phase = operation.phase.name),
                    )
                }
            }
        }

        route("/api/imports") {
            post {
                call.handle {
                    context.mutations.withMutation {
                        val request = call.receiveJson<ImportRequest>()
                        val collection = context.collectionService.requireActiveByNameOrId(request.collection)
                        // The job records the tool version it will run with, so its checkpoints are keyed by
                        // what actually produced them. That is why the probe happens once per job creation.
                        val settings = ToolProbe.extractionSettings(collection.ocrLanguages)
                        val payload = ImportJobPayload.of(
                            collectionId = collection.id,
                            sources = request.paths,
                            settings = settings,
                        )
                        val job = context.jobs.enqueue(
                            type = JobType.IMPORT,
                            collectionId = collection.id,
                            payload = payload.encode(),
                            total = 0,
                        )
                        call.respondJson(
                            HttpStatusCode.Accepted,
                            ImportAcceptedResponse(accepted = true, job = job),
                        )
                    }
                }
            }
        }

        route("/api/jobs") {
            get {
                call.handle {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_JOB_PAGE
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                    call.respondJson(HttpStatusCode.OK, JobsResponse(context.jobs.list(limit, offset)))
                }
            }

            get("/{id}") {
                call.handle {
                    val jobId = call.jobId()
                    val job = context.jobs.get(jobId)
                        ?: throw NoSuchElementException("no job with id ${jobId.value}")
                    call.respondJson(HttpStatusCode.OK, JobResponse(job))
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
                    call.respondJson(
                        HttpStatusCode.OK,
                        ImportItemsResponse(context.importItems.listForJob(jobId)),
                    )
                }
            }

            // The cancellation request is persisted before anything is signalled, so a caller that sees
            // this answer knows the request outlives this process.
            post("/{id}/cancel") {
                call.handle {
                    val jobId = call.jobId()
                    call.respondJson(HttpStatusCode.OK, JobResponse(context.cancelJob(jobId)))
                }
            }
        }

        configureSearchRoutes(context, context.mutations)

        // The compiled SvelteKit application. Its client-side routes all fall back to this file.
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

fun ApplicationCall.jobId(): JobId {
    val raw = parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a job id is required in the path")
    return JobId(raw)
}

fun ApplicationCall.collectionId(): CollectionId {
    val raw = parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a collection id is required in the path")
    return CollectionId(raw)
}

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
        respondJson(
            searchFailureStatus(refused.code),
            ApiErrorResponse(ApiError(code = refused.code, message = refused.remedy)),
        )
    } catch (maintenance: MaintenanceInProgressException) {
        respondJson(
            HttpStatusCode.Locked,
            ApiErrorResponse(ApiError(code = "MAINTENANCE_IN_PROGRESS", message = maintenance.message.orEmpty())),
        )
    } catch (blocked: DeletionRecoveryBlockedException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "DELETION_RECOVERY_BLOCKED", message = blocked.message.orEmpty())),
        )
    } catch (duplicate: DuplicateCollectionNameException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "DUPLICATE_COLLECTION_NAME", message = duplicate.message.orEmpty())),
        )
    } catch (mismatch: CollectionConfirmationMismatchException) {
        respondJson(
            HttpStatusCode.BadRequest,
            ApiErrorResponse(ApiError(code = "CONFIRMATION_MISMATCH", message = mismatch.message.orEmpty())),
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
