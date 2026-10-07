package infoscry.server

import infoscry.AppContext
import infoscry.collection.DeletionRecoveryBlockedException
import infoscry.collection.OcrSettingsUpdate
import infoscry.diagnostics.ToolProbe
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.ExtensionFilter
import infoscry.jobs.ImportJobPayload
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.document.RescanRefusalException
import infoscry.document.RestoreRefusalException
import infoscry.document.RestoreRequestConflictException
import infoscry.document.StaleActiveRevisionException
import infoscry.document.StaleCandidateDecisionException
import infoscry.jobs.DocumentIngest
import infoscry.search.RevisionSnapshotUnavailableException
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.OcrOperationConflictException
import infoscry.storage.OcrRequestConflictException
import infoscry.storage.StaleRescanPreviewException
import infoscry.storage.CollectionConfirmationMismatchException
import infoscry.storage.DuplicateCollectionNameException
import infoscry.storage.DuplicateLlmProfileNameException
import infoscry.storage.DuplicateOcrProfileNameException
import infoscry.storage.LastLlmProfileException
import infoscry.storage.MaintenanceInProgressException
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import infoscry.extract.ExternalProcess
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
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
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

/**
 * One collection OCR-settings edit.
 *
 * The path keeps its `ocr-languages` name because it is the route clients already call; the body now also
 * carries the engine, the import mode, the two profile selections and the external page allowance. Every new
 * field is optional and absent means "keep what the collection has", so an old request that sends only
 * `ocrLanguages` changes only the languages. A profile id sent as an empty string clears that profile.
 */
@Serializable
data class UpdateOcrLanguagesRequest(
    val ocrLanguages: String? = null,
    val ocrEngine: OcrEngine? = null,
    val ocrImportMode: OcrImportMode? = null,
    val ocrTranscriptionProfileId: String? = null,
    val ocrReviewProfileId: String? = null,
    val ocrExternalPageLimit: Int? = null,
) {

    fun toUpdate(): OcrSettingsUpdate = OcrSettingsUpdate(
        ocrLanguages = ocrLanguages,
        engine = ocrEngine,
        importMode = ocrImportMode,
        transcriptionProfileId = ocrTranscriptionProfileId,
        reviewProfileId = ocrReviewProfileId,
        externalPageLimit = ocrExternalPageLimit,
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
    val completed: Int = 0,
    val total: Int = 0,
    val errorCode: String? = null,
    val cancelRequested: Boolean = false,
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

/** What a caller asks for: the collection by name or id, and the files or directories it selected. */
@Serializable
data class ImportRequest(
    val collection: String,
    val paths: List<String>,
    // Defaulted rather than required so a caller that predates the flag keeps working, and it defaults to
    // false on purpose: a directory import no longer descends into subdirectories unless recursion is
    // asked for, so a request that says nothing gets exactly that non-recursive behavior.
    val recursive: Boolean = false,
    // Extension lists for a folder import; both default to empty, which means no filter.
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

/** What the picker is asked to choose: files (any number of them) or one folder. */
@Serializable
data class PickRequest(val directory: Boolean = false)

/** The absolute paths the native pick dialog returned, in the order the user chose them. */
@Serializable
data class PickResponse(val paths: List<String>)

/**
 * The answer to an import request.
 *
 * `accepted` is the honest word for it: the job is durable and a worker will run it, which is not the
 * same as the import being finished. A caller that needs the outcome asks for the job, or passes `--wait`.
 */
@Serializable
data class ImportAcceptedResponse(val accepted: Boolean, val job: JobApiView)

/** One file's result, so a caller can report which documents failed and why. */
@Serializable
data class ImportItemsResponse(val items: List<ImportItemApiView>)

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
    credentials: ApiCredentials,
    jobEventIdleDeadlineMillis: Long = DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS,
    picker: suspend (Boolean) -> List<String> = ::pickWithOsascript,
) {
    installRequestGuard(credentials)

    // One pick dialog at a time for the whole process: a second concurrent pick would open another
    // dialog on top of the first, which a user cannot even see, let alone answer.
    val pickMutex = Mutex()

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
            // Admission is durable before this answers and the destructive work continues in the
            // server's own process, so a caller that goes away, or never reads the answer, cannot lose
            // the deletion. 202 says exactly that: accepted, not done.
            delete {
                call.handle {
                    val id = call.collectionId()
                    val request = call.receiveJson<DeleteCollectionRequest>()
                    val operation = context.collectionService.requestDeletion(id, request.confirmName)
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
                        IgnorePatternsBody(context.collectionService.updateIgnorePatterns(id, request.patterns).patterns),
                    )
                }
            }

            patch("/ocr-languages") {
                call.handle {
                    val id = call.collectionId()
                    val request = call.receiveJson<UpdateOcrLanguagesRequest>()
                    call.respondJson(
                        HttpStatusCode.OK,
                        CollectionResponse(context.collectionService.updateOcrSettings(id, request.toUpdate())),
                    )
                }
            }
        }

        route("/api/imports") {
            post {
                call.handle {
                    // A request that names both an include and an exclude list is refused here, before the mutation
                    // gate and before any job or copy exists.
                    val request = call.receiveJson<ImportRequest>()
                    val extensions = ExtensionFilter.of(request.include, request.exclude)
                    context.mutations.withMutation {
                        // The same gate every other mutating command passes: while an unsafe deletion state is
                        // unresolved, admitting an import would add work to an archive an operator has to
                        // repair first.
                        context.collectionService.requireMutationsAllowed()
                        val collection = context.collectionService.requireActiveByNameOrId(request.collection)
                        // The job records the tool version it will run with, so its checkpoints are keyed by
                        // what actually produced them. That is why the probe happens once per job creation.
                        // The collection's OCR selection is snapshotted here, once, and travels in the
                        // payload: the engine, the import mode, the profile revisions and the external page
                        // allowance of the whole job. Editing a default afterwards changes future imports
                        // only, and an import of twenty files has one allowance covering all of them.
                        //
                        // The snapshot also records the runtime the reading engine reports *now*, before the
                        // job exists: the attempt's fingerprint is computed from it, so an engine upgraded
                        // between admission and a restart cannot make a resumed attempt read under a runtime
                        // nobody admitted.
                        val snapshot = context.ocr.withProbedRuntime(
                            context.ocr.snapshotFor(
                                settings = collection.ocrSettings(),
                                extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
                                renderDpi = infoscry.extract.PdfExtractor.DEFAULT_RENDER_DPI,
                            ),
                        )
                        val settings = ToolProbe.extractionSettings(collection.ocrLanguages)
                            .forOcrSettings(snapshot)
                        val payload = ImportJobPayload.of(
                            collectionId = collection.id,
                            sources = request.paths,
                            settings = settings,
                            recursive = request.recursive,
                            ocr = snapshot,
                            extensions = extensions,
                            // Snapshotted here, with the OCR settings: a later edit of the list changes future
                            // imports only.
                            ignore = context.collectionService.ignorePatterns(collection.id),
                        )
                        val job = context.jobs.enqueue(
                            type = JobType.IMPORT,
                            collectionId = collection.id,
                            payload = payload.encode(),
                            total = 0,
                        )
                        call.respondJson(
                            HttpStatusCode.Accepted,
                            ImportAcceptedResponse(accepted = true, job = job.toApiView()),
                        )
                    }
                }
            }

            // The native picker is opened by the server on the user's machine, and the browser gets back
            // the absolute paths it chose. A pick is a mutation like every other here -- the dialog changes
            // state outside the server -- so it stands behind the same request guard as any other POST.
            post("/pick") {
                call.handle {
                    val request = call.receiveJson<PickRequest>()
                    if (!pickMutex.tryLock()) {
                        call.respondJson(
                            HttpStatusCode.Conflict,
                            ApiErrorResponse(
                                ApiError(
                                    code = PICK_BUSY_CODE,
                                    message = "another pick dialog is already open; wait for it before asking again",
                                ),
                            ),
                        )
                    } else {
                        try {
                            call.respondJson(HttpStatusCode.OK, PickResponse(picker(request.directory)))
                        } catch (cancelled: PickerCancelledException) {
                            call.respondJson(
                                HttpStatusCode.BadRequest,
                                ApiErrorResponse(
                                    ApiError(
                                        code = PICK_CANCELLED_CODE,
                                        message = cancelled.message
                                            ?: "the pick dialog was closed without choosing anything",
                                    ),
                                ),
                            )
                        } catch (unavailable: PickerUnavailableException) {
                            call.respondJson(
                                HttpStatusCode.ServiceUnavailable,
                                ApiErrorResponse(
                                    ApiError(
                                        code = PICK_UNAVAILABLE_CODE,
                                        message = unavailable.message
                                            ?: "the pick dialog could not be opened on this machine",
                                    ),
                                ),
                            )
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            // A caller that went away is not a picker failure: the cancellation is passed on
                            // so the request dies the way it should instead of being answered as a 503.
                            throw cancelled
                        } catch (_: Exception) {
                            // Whatever else the picker threw is a failure to present the dialog, not a
                            // defect in a handler: the caller gets the same code and remedy it would for
                            // osascript missing, because both mean "no pick dialog right now".
                            call.respondJson(
                                HttpStatusCode.ServiceUnavailable,
                                ApiErrorResponse(
                                    ApiError(
                                        code = PICK_UNAVAILABLE_CODE,
                                        message = "the pick dialog could not be opened; keep InfoScry running on " +
                                            "a graphical macOS session with osascript available and try again",
                                    ),
                                ),
                            )
                        } finally {
                            pickMutex.unlock()
                        }
                    }
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
                    call.respondJson(HttpStatusCode.OK, JobsResponse(context.jobs.list(limit, offset).map(Job::toApiView)))
                }
            }

            get("/{id}") {
                call.handle {
                    val jobId = call.jobId()
                    val job = context.jobs.get(jobId)
                        ?: throw NoSuchElementException("no job with id ${jobId.value}")
                    call.respondJson(HttpStatusCode.OK, JobResponse(job.toApiView()))
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
                        ImportItemsResponse(context.importItems.listForJob(jobId).map(ImportItem::toApiView)),
                    )
                }
            }

            // The cancellation request is persisted before anything is signalled, so a caller that sees
            // this answer knows the request outlives this process.
            post("/{id}/cancel") {
                call.handle {
                    val jobId = call.jobId()
                    call.respondJson(HttpStatusCode.OK, JobResponse(context.cancelJob(jobId).toApiView()))
                }
            }
        }

        configureSearchRoutes(context, context.mutations)
        configureSourceRoutes(context)
        configureLlmProfileRoutes(context)
        configureOcrProfileRoutes(context)
        configureOcrLlmCandidateRoutes(context)
        configureOcrRescanRoutes(context)
        configureLlmCatalogRoutes(credentials)
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

private const val API_PREFIX = "/api/"

/** The status-and-code pair the pick route names when a second dialog is asked for while one is open. */
private const val PICK_BUSY_CODE = "PICK_BUSY"

/** The status-and-code pair the pick route names when nothing was chosen. */
private const val PICK_CANCELLED_CODE = "PICK_CANCELLED"

/** The status-and-code pair the pick route names when no dialog could be presented. */
private const val PICK_UNAVAILABLE_CODE = "PICK_UNAVAILABLE"

/** What osascript prints when the user closes the dialog instead of choosing; matched case-insensitively. */
private const val PICK_CANCEL_TEXT = "cancel"

/** The user paces the dialog, so the bound is generous: minutes, not seconds. */
private val PICK_TIMEOUT: Duration = Duration.ofMinutes(10)

/**
 * The osascript that asks for one folder and prints its POSIX path on stdout.
 *
 * Every `-e` is one list element on purpose: this is a command list, never a string, so a path the user
 * chooses can never become shell syntax. `choose folder` prints its POSIX path when told to, and exits
 * nonzero with "User canceled" on stderr when the dialog is closed without a choice.
 */
private val PICK_FOLDER_SCRIPT: List<String> = listOf(
    "osascript", "-e", "POSIX path of (choose folder)",
)

/**
 * The osascript that asks for files, one POSIX path per line, several allowed.
 *
 * `choose file with multiple selections allowed` returns a list, so the script walks it and appends each
 * path followed by a linefeed; a cancelled dialog exits nonzero with "User canceled" on stderr.
 */
private val PICK_FILES_SCRIPT: List<String> = listOf(
    "osascript",
    "-e", "set output to \"\"",
    "-e", "set chosen to choose file with multiple selections allowed",
    "-e", "repeat with f in chosen",
    "-e", "set output to output & POSIX path of f & linefeed",
    "-e", "end repeat",
    "-e", "return output",
)

/**
 * The user closed the native pick dialog without choosing anything.
 *
 * This is an outcome of the dialog, not a failure of the server: the caller reports it as the user's
 * decision and moves on.
 */
class PickerCancelledException(message: String) : IllegalStateException(message)

/**
 * The native pick dialog could not be presented at all.
 *
 * No osascript, a headless session (no window server), or a picker that failed in a way that names no
 * user decision: whichever it is, [message] says what has to be true for a pick to work.
 */
class PickerUnavailableException(message: String) : IllegalStateException(message)

/**
 * Opens the native macOS pick dialog and returns the absolute paths it chose.
 *
 * The dialog runs on the server's machine, which is the machine the user is sitting at, and the browser
 * only ever sees the paths back. The exit status and stderr of osascript say which way the dialog ended:
 * a zero exit with lines of output means a choice was made, a nonzero exit naming a cancel (or no output
 * at all) means the user closed it, and anything else means the dialog could not be presented.
 */
internal suspend fun pickWithOsascript(directory: Boolean): List<String> {
    val outcome = try {
        ExternalProcess.run(
            if (directory) PICK_FOLDER_SCRIPT else PICK_FILES_SCRIPT,
            PICK_TIMEOUT,
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // The requester went away while the dialog was open. ExternalProcess has already stopped it, so the
        // cancellation is passed on rather than dressed up as a failure to present a dialog.
        throw cancelled
    } catch (failure: Exception) {
        throw PickerUnavailableException(
            "the macOS file picker could not be started" +
                (failure.message?.let { ": $it" } ?: "") +
                "; run InfoScry from a graphical macOS session with osascript available and try again",
        )
    }
    return pickOutcomeOf(outcome.exitCode, outcome.stdout, outcome.stderr)
}

/**
 * What one osascript run means: the chosen paths, the user closing the dialog, or no dialog at all.
 *
 * Only osascript's own cancel wording means the user closed the dialog. A nonzero exit with any other
 * message -- a headless session refusing user interaction, most of all -- means no dialog was ever shown,
 * which the caller treats differently: it can offer a typed path where a cancel simply ends the attempt.
 */
internal fun pickOutcomeOf(exitCode: Int, stdout: String, stderr: String): List<String> {
    val paths = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { Path.of(it).toAbsolutePath().normalize().toString() }
        .toList()
    return when {
        exitCode == 0 && paths.isNotEmpty() -> paths
        stderr.contains(PICK_CANCEL_TEXT, ignoreCase = true) ->
            throw PickerCancelledException("the pick dialog was closed without choosing anything")
        else -> throw PickerUnavailableException(
            "the macOS picker failed (osascript exit $exitCode); " +
                "run InfoScry from a graphical macOS session with osascript available and try again",
        )
    }
}

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

fun ApplicationCall.collectionId(): CollectionId {
    val raw = parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a collection id is required in the path")
    return CollectionId(raw)
}

private fun Job.toApiView() = JobApiView(
    id = id,
    type = type,
    state = state,
    createdAt = createdAt,
    updatedAt = updatedAt,
    collectionId = collectionId,
    stage = stage,
    currentItem = currentItem,
    completed = completed,
    total = total,
    errorCode = errorCode,
    cancelRequested = cancelRequested,
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
        // The exception's own message names managed and parked directories for the log; the client gets the
        // curated sentence, which carries the phase and the error code instead of any path.
        LOGGER.atWarn().addKeyValue(ERROR_DETAIL_FIELD, blocked.message.orEmpty())
            .log("a mutating command was refused while a deletion recovery is unresolved")
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "DELETION_RECOVERY_BLOCKED", message = blocked.clientMessage)),
        )
    } catch (duplicate: DuplicateCollectionNameException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "DUPLICATE_COLLECTION_NAME", message = duplicate.message.orEmpty())),
        )
    } catch (duplicate: DuplicateLlmProfileNameException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "DUPLICATE_LLM_PROFILE_NAME", message = "an LLM profile with that name already exists")),
        )
    } catch (stale: infoscry.storage.StaleOcrProfileRevisionException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(
                ApiError(
                    code = "STALE_OCR_PROFILE_REVISION",
                    message = "this profile was edited after the revision you were editing; reload it and edit again",
                ),
            ),
        )
    } catch (duplicate: DuplicateOcrProfileNameException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "DUPLICATE_OCR_PROFILE_NAME", message = "an OCR profile with that name already exists")),
        )
    } catch (last: LastLlmProfileException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "LAST_LLM_PROFILE", message = last.message.orEmpty())),
        )
    } catch (mismatch: CollectionConfirmationMismatchException) {
        respondJson(
            HttpStatusCode.BadRequest,
            ApiErrorResponse(ApiError(code = "CONFIRMATION_MISMATCH", message = mismatch.message.orEmpty())),
        )
    } catch (sealed: RevisionSnapshotUnavailableException) {
        // An authoritative publication could not be made coherent in this process, so no search answer would
        // be right. 503 says "the archive is repairing itself, retry" rather than dressing a mixture of two
        // readings up as a result.
        respondJson(
            HttpStatusCode.ServiceUnavailable,
            ApiErrorResponse(
                ApiError(
                    code = "REVISION_SNAPSHOT_UNAVAILABLE",
                    message = "the archive is finishing a publication; retry once it has completed",
                ),
            ),
        )
    } catch (missing: NoSuchElementException) {
        respondJson(
            HttpStatusCode.NotFound,
            ApiErrorResponse(ApiError(code = "NOT_FOUND", message = missing.message.orEmpty())),
        )
    } catch (refused: RescanRefusalException) {
        // A rescan that cannot be admitted as asked: another format, a managed copy that is not the recorded
        // one, an engine this build does not have, an unmeasured external profile, no embedder. The code names
        // what to do about it, and 409 says the request was understood but the archive's state says no.
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = refused.code, message = refused.message.orEmpty())),
        )
    } catch (refused: RestoreRefusalException) {
        // A restore that cannot be done as asked. 409 says the request was understood and the archive's state
        // says no; 503 says this machine cannot embed right now and the request is worth repeating later. The
        // current revision is still the document's text in both cases, and the words are curated.
        respondJson(
            if (refused.unavailable) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = refused.code, message = refused.message.orEmpty())),
        )
    } catch (conflict: RestoreRequestConflictException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "RESTORE_REQUEST_CONFLICT", message = conflict.message.orEmpty())),
        )
    } catch (stale: StaleRescanPreviewException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(
                ApiError(
                    code = "STALE_RESCAN_PREVIEW",
                    message = "the preview is no longer the document's state (${stale.message.orEmpty()}); " +
                        "preview again",
                ),
            ),
        )
    } catch (conflict: OcrRequestConflictException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "OCR_REQUEST_CONFLICT", message = conflict.message.orEmpty())),
        )
    } catch (busy: OcrOperationConflictException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "OCR_ALREADY_RUNNING", message = busy.message.orEmpty())),
        )
    } catch (attempting: infoscry.storage.OcrAttemptInProgressException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "OCR_ATTEMPT_IN_PROGRESS", message = attempting.message.orEmpty())),
        )
    } catch (stale: StaleCandidateDecisionException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "STALE_REVIEW_DECISION", message = stale.message.orEmpty())),
        )
    } catch (stale: StaleActiveRevisionException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = "STALE_DOCUMENT_REVISION", message = stale.message.orEmpty())),
        )
    } catch (deleted: DocumentBeingDeletedException) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(
                ApiError(
                    code = "DOCUMENT_BEING_DELETED",
                    message = "this document is being deleted, so no rescan, review decision or restore may be admitted for it",
                ),
            ),
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
