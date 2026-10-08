package infoscry.server

import infoscry.AppContext
import infoscry.llm.LlmProvider
import infoscry.ocr.ImageLlmException
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrProfile
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.TextOnlyLlmProfileException
import infoscry.ocr.endpointScope
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * How long the probe endpoint waits for the first body byte before it refuses the request.
 *
 * A caller that announces a body and then sends nothing would otherwise hold the handler open for as long as
 * it keeps the connection: the read waits for either a byte or the end of the body, and neither ever arrives.
 * The bound is short because the only thing it can delay is a body that was never coming on an endpoint that
 * takes no body at all; a real empty body ends the read immediately with EOF.
 */
private const val PROBE_FIRST_BYTE_TIMEOUT_MILLIS = 250L

/**
 * The body one OCR profile create or edit supplies: the profile's complete next state.
 *
 * A `PATCH` carries the same fields as the `POST` rather than a sparse set, because the edit produces a
 * complete immutable revision: a revision holding half a profile would be a reading nothing could
 * reproduce. `enabled` is required rather than defaulted so an edit can never re-enable a profile by
 * omission.
 */
@Serializable
data class OcrProfileRequest(
    val name: String,
    val provider: LlmProvider,
    val model: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val enabled: Boolean,
    val endpoint: String = "",
    val apiKeyEnvironmentVariable: String? = null,
    /**
     * `PATCH` only: the revision the editor was looking at. When present and no longer the profile's current
     * revision, the edit is refused with `STALE_OCR_PROFILE_REVISION` and nothing changes; absent keeps the
     * unguarded behaviour. A `POST` carrying one is refused.
     */
    val expectedRevisionId: String? = null,
)

/**
 * One OCR profile as the API shows it: the profile, the revision it currently reads through, and whether
 * the environment variable holding its key exists.
 *
 * The key's *name* is all a caller learns; [keyAvailable] is resolved at read time from the process
 * environment and no value ever reaches this view. [scope] states whether dispatching to this profile sends
 * page images off this machine, which is a decision a caller has to make explicitly.
 */
@Serializable
data class OcrProfileApiView(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val revisionId: String,
    val sequence: Int,
    val provider: LlmProvider,
    val endpoint: String,
    val scope: OcrEndpointScope,
    val model: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val apiKeyEnvironmentVariable: String?,
    val keyAvailable: Boolean,
    val imageCapabilityMeasured: Boolean?,
    val imageCapabilityCheckedAt: String?,
    /** The LLM profile this profile was copied from; absent for an OCR profile made directly. */
    val sourceLlmProfileId: String? = null,
)

@Serializable
data class OcrProfilesResponse(val profiles: List<OcrProfileApiView>)

@Serializable
data class OcrProfileResponse(val profile: OcrProfileApiView)

/** The body that offers one LLM profile for transcription or review. */
@Serializable
data class OcrFromLlmProfileRequest(val llmProfileId: String)

/**
 * One LLM profile as the collection's OCR selects see it: enough to label it, never a key value.
 *
 * [imageInput] is what the catalog states about the model: true, false, or absent when it does not say, which
 * the reader is shown as "image support unknown". [keyAvailable] is the presence of the named variable only.
 */
@Serializable
data class OcrLlmCandidateApiView(
    val id: String,
    val name: String,
    val provider: LlmProvider,
    val endpoint: String,
    val scope: OcrEndpointScope,
    val model: String,
    val apiKeyEnvironmentVariable: String?,
    val keyAvailable: Boolean,
    val imageInput: Boolean?,
)

@Serializable
data class OcrLlmCandidatesResponse(val profiles: List<OcrLlmCandidateApiView>)

/**
 * What one capability check measured, and the profile as it now stands.
 *
 * The check is a measurement rather than an action on a document, so the answer carries both: the profile
 * with its recorded measurement, and this check's own outcome with the model version the provider resolved
 * the profile's alias to — which is not stored, because a later check may resolve the same alias
 * differently and the record is what was *measured*, not what was quoted.
 */
@Serializable
data class OcrProfileProbeResponse(
    val profile: OcrProfileApiView,
    val supported: Boolean,
    val modelVersion: String? = null,
    val errorCode: String? = null,
)

/**
 * OCR profiles, separately managed from the Ask/Investigate profiles.
 *
 * These routes are admin configuration: they create and edit profiles and never start work, so they follow
 * the same shape as the LLM routes — the shared mutation admission wraps every write, JSON validation
 * happens before any side effect, and a failure the caller can fix is a 400 or a 409 rather than a 500.
 */
fun Routing.configureOcrProfileRoutes(context: AppContext) {
    route("/api/ocr/profiles") {
        get {
            call.handle {
                call.respondJson(
                    HttpStatusCode.OK,
                    OcrProfilesResponse(context.ocr.list().map { profile -> profile.toApiView(context) }),
                )
            }
        }
        post {
            call.handle {
                val request = call.receiveJsonRejectingUnknownFields<OcrProfileRequest>()
                if (request.expectedRevisionId != null) {
                    throw BadRequestException("a new profile has no revision to expect")
                }
                val created = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    context.ocr.create(request.name, request.toDraft(), request.enabled)
                }
                call.respondJson(HttpStatusCode.Created, OcrProfileResponse(created.toApiView(context)))
            }
        }
        /**
         * Offers an existing LLM profile for transcription or review by copying it into an OCR profile.
         *
         * The copy is what a collection selects and an attempt pins, so editing the LLM profile later cannot
         * change an admitted attempt. Choosing the same LLM profile again reuses its copy (200) and adds a
         * revision only when the LLM profile changed; a model the catalog states is text-only is a 409.
         */
        post("/from-llm") {
            call.handle {
                val request = call.receiveJsonRejectingUnknownFields<OcrFromLlmProfileRequest>()
                val llm = context.llm.findById(request.llmProfileId)
                    ?: throw NoSuchElementException("no LLM profile with that id")
                val copy = try {
                    context.mutations.withMutation {
                        context.collectionService.requireMutationsAllowed()
                        context.ocr.copyOfLlmProfile(llm)
                    }
                } catch (textOnly: TextOnlyLlmProfileException) {
                    call.respondJson(
                        HttpStatusCode.Conflict,
                        ApiErrorResponse(ApiError(code = "LLM_PROFILE_TEXT_ONLY", message = textOnly.message.orEmpty())),
                    )
                    return@handle
                }
                call.respondJson(
                    if (copy.created) HttpStatusCode.Created else HttpStatusCode.OK,
                    OcrProfileResponse(copy.profile.toApiView(context)),
                )
            }
        }
        patch("/{profileId}") {
            call.handle {
                val profileId = call.ocrProfileId()
                val request = call.receiveJsonRejectingUnknownFields<OcrProfileRequest>()
                // Optional for compatibility (the CLI and older clients send none and keep last-writer-wins),
                // but a present value must name a revision: a blank one is a malformed guard, not "no guard".
                if (request.expectedRevisionId != null && request.expectedRevisionId.isBlank()) {
                    throw BadRequestException("an expected revision id must not be blank")
                }
                val updated = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    context.ocr.update(
                        profileId,
                        request.name,
                        request.toDraft(),
                        request.enabled,
                        request.expectedRevisionId,
                    )
                }
                call.respondJson(HttpStatusCode.OK, OcrProfileResponse(updated.toApiView(context)))
            }
        }
        delete("/{profileId}") {
            call.handle {
                val profileId = call.ocrProfileId()
                val disabled = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    context.ocr.disable(profileId)
                }
                // Disabling keeps every revision, so a collection, job or attempt that references one still
                // resolves: this removes the profile from selection, never from history.
                if (!disabled) throw NoSuchElementException("no OCR profile with id $profileId")
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
        /**
         * Checks that this profile can carry an image at all, with this build's synthetic image.
         *
         * The endpoint takes no parameters, and that is part of the contract rather than an omission: a probe
         * that accepted a document, a page or a path would be a way to send somebody's page through the
         * capability path. A body is therefore refused instead of ignored, so a caller that sent one learns
         * that nothing was read from it.
         *
         * A check that ran is answered with 200 and the measurement it recorded, whether it passed or not: a
         * model that cannot read images measured `false`, which is the caller's answer rather than a server
         * error. A check that could *not run* — no endpoint, no external dispatch permit, no credential — is
         * a 409, because nothing was sent and the profile is what has to change first.
         */
        post("/{profileId}/probe") {
            call.handle {
                val profileId = call.ocrProfileId()
                val profile = context.ocr.require(profileId)
                // Any body at all is refused, not just a body with something to read in it: the endpoint
                // takes no parameters, so whitespace is a caller sending one and learning nothing read it.
                // The check is on the bytes rather than on the decoded text, because "it decodes to nothing"
                // is not "nothing was sent": a body encoded as UTF-16 that holds only a byte order mark
                // decodes to an empty string while the request carried two bytes. It is answered by the
                // channel's first byte rather than by `receive<ByteArray>()`, which would buffer a body of any
                // size just to decide this; `readAvailable` returns -1 only at the end of the body, so nothing
                // past that first byte is read and a body of any size costs one byte of memory.
                //
                // The wait for that byte is bounded and this check fails closed: a caller that announces a body
                // and then sends nothing would otherwise hold the handler open for as long as it keeps the
                // connection, and a body whose emptiness cannot be established inside the bound is refused
                // rather than waited for. Only the two ways an ordinary bodyless request ends — EOF, which is
                // `Content-Length: 0` or a closed body, and nothing else — accepts the request.
                val firstByte = withTimeoutOrNull(PROBE_FIRST_BYTE_TIMEOUT_MILLIS) {
                    call.receiveChannel().readAvailable(ByteArray(1), 0, 1)
                }
                if (firstByte == null || firstByte >= 0) {
                    throw BadRequestException("a capability probe takes no request body")
                }
                val measurement = try {
                    context.ocr.probeImageCapability(profile.revision)
                } catch (refused: ImageLlmException) {
                    call.respondJson(
                        HttpStatusCode.Conflict,
                        ApiErrorResponse(ApiError(code = refused.code, message = refused.message.orEmpty())),
                    )
                    return@handle
                }
                call.respondJson(
                    HttpStatusCode.OK,
                    OcrProfileProbeResponse(
                        profile = context.ocr.require(profileId).toApiView(context),
                        supported = measurement.supported,
                        modelVersion = measurement.modelVersion,
                        errorCode = measurement.errorCode,
                    ),
                )
            }
        }
    }
}

/** The LLM profiles a collection may be offered for OCR, each with what the catalog states about image input. */
fun Routing.configureOcrLlmCandidateRoutes(context: AppContext) {
    get("/api/ocr/llm-profiles") {
        call.handle {
            call.respondJson(
                HttpStatusCode.OK,
                OcrLlmCandidatesResponse(
                    context.llm.list().filter { it.enabled }.map { llm ->
                        OcrLlmCandidateApiView(
                            id = llm.id,
                            name = llm.name,
                            provider = llm.provider,
                            endpoint = llm.endpoint,
                            scope = endpointScope(llm.endpoint),
                            model = llm.model,
                            apiKeyEnvironmentVariable = llm.apiKeyEnvironmentVariable,
                            keyAvailable = llm.keyAvailable(System::getenv),
                            imageInput = context.ocr.imageInputOf(llm),
                        )
                    },
                ),
            )
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.ocrProfileId(): String =
    parameters["profileId"]?.takeIf(String::isNotBlank)
        ?: throw BadRequestException("an OCR profile id is required in the path")

private fun OcrProfileRequest.toDraft(): OcrProfileRevisionDraft = try {
    OcrProfileRevisionDraft(
        provider = provider,
        model = model,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        endpoint = endpoint,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
    )
} catch (_: IllegalArgumentException) {
    // A validation message names the offending field and can quote its value, and one of these fields is the
    // slot for an environment variable *name* — a place a caller will paste a token. Never echo it back.
    throw BadRequestException("OCR profile fields are invalid")
}

private fun OcrProfile.toApiView(context: AppContext): OcrProfileApiView = OcrProfileApiView(
    id = id,
    name = name,
    enabled = enabled,
    revisionId = revision.revisionId,
    sequence = revision.sequence,
    provider = revision.provider,
    endpoint = revision.endpoint,
    scope = revision.scope,
    model = revision.model,
    contextWindow = revision.contextWindow,
    maxOutputTokens = revision.maxOutputTokens,
    inputPricePerMillion = revision.inputPricePerMillion,
    outputPricePerMillion = revision.outputPricePerMillion,
    apiKeyEnvironmentVariable = revision.apiKeyEnvironmentVariable,
    keyAvailable = context.ocr.keyAvailable(revision),
    imageCapabilityMeasured = revision.imageCapabilityMeasured,
    imageCapabilityCheckedAt = revision.imageCapabilityCheckedAt,
    sourceLlmProfileId = sourceLlmProfileId,
)
