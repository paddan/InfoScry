package infoscry.server

import infoscry.AppContext
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LlmProfileRequest(
    val name: String,
    val provider: LlmProvider,
    val endpoint: String = "",
    val model: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val cacheReadPricePerMillion: Double,
    val enabled: Boolean,
    val apiKeyEnvironmentVariable: String? = null,
)

@Serializable
data class LlmProfileApiView(
    val id: String,
    val name: String,
    val provider: LlmProvider,
    val endpoint: String,
    val model: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val cacheReadPricePerMillion: Double,
    val enabled: Boolean,
    val apiKeyEnvironmentVariable: String?,
    val keyAvailable: Boolean,
    val toolCallingMeasured: Boolean?,
    val capabilityCheckedAt: String?,
)

@Serializable
data class LlmProfilesResponse(
    val profiles: List<LlmProfileApiView>,
    val defaults: Map<LlmPromptRole, String?>,
)

@Serializable
data class LlmProfileResponse(val profile: LlmProfileApiView)

@Serializable
data class LlmDefaultRequest(val profileId: String)

@Serializable
data class LlmDefaultResponse(val role: LlmPromptRole, val profileId: String, val profileName: String)

fun Routing.configureLlmProfileRoutes(context: AppContext) {
    route("/api/llm/profiles") {
        get {
            call.handle {
                val defaults = LlmPromptRole.entries.associateWith { role ->
                    context.llm.defaultProfileName(role)?.let(context.llm::findByName)?.id
                }
                call.respondJson(
                    HttpStatusCode.OK,
                    LlmProfilesResponse(context.llm.list().map { it.toApiView() }, defaults),
                )
            }
        }
        post {
            call.handle {
                val request = call.receiveJson<LlmProfileRequest>()
                val profile = request.toProfile(UUID.randomUUID().toString())
                val created = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    context.llm.create(profile)
                }
                call.respondJson(HttpStatusCode.Created, LlmProfileResponse(created.toApiView()))
            }
        }
        put("/{id}") {
            call.handle {
                val id = call.parameters["id"]?.takeIf(String::isNotBlank)
                    ?: throw BadRequestException("a profile id is required")
                val request = call.receiveJson<LlmProfileRequest>()
                val updated = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    context.llm.update(id, request.toProfile(id))
                }
                    ?: throw NoSuchElementException("no LLM profile with id $id")
                call.respondJson(HttpStatusCode.OK, LlmProfileResponse(updated.toApiView()))
            }
        }
        delete("/{id}") {
            call.handle {
                val id = call.parameters["id"]?.takeIf(String::isNotBlank)
                    ?: throw BadRequestException("a profile id is required")
                val deleted = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    context.llm.deleteById(id)
                }
                if (!deleted) throw NoSuchElementException("no LLM profile with id $id")
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
    }

    route("/api/llm/defaults/{role}") {
        get {
            call.handle {
                val role = call.promptRole()
                val name = context.llm.defaultProfileName(role)
                    ?: throw NoSuchElementException("no default profile is set for ${role.name}")
                val profile = context.llm.findByName(name)
                    ?: throw NoSuchElementException("the default LLM profile no longer exists")
                call.respondJson(HttpStatusCode.OK, LlmDefaultResponse(role, profile.id, profile.name))
            }
        }
        put {
            call.handle {
                val role = call.promptRole()
                val request = call.receiveJson<LlmDefaultRequest>()
                val profile = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    context.llm.setDefaultById(role, request.profileId)
                } ?: throw NoSuchElementException("no LLM profile with id ${request.profileId}")
                call.respondJson(HttpStatusCode.OK, LlmDefaultResponse(role, profile.id, profile.name))
            }
        }
    }
}

private fun LlmProfileRequest.toProfile(id: String): LlmProfile = try {
    LlmProfile(
        id = id,
        name = name,
        provider = provider,
        endpoint = endpoint,
        model = model,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        cacheReadPricePerMillion = cacheReadPricePerMillion,
        enabled = enabled,
        apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
    )
} catch (_: IllegalArgumentException) {
    // Validation messages can contain caller supplied fields, including a token mistakenly pasted into
    // the environment-variable slot. Never echo those values back through the API.
    throw BadRequestException("LLM profile fields are invalid")
}

private fun LlmProfile.toApiView(): LlmProfileApiView = LlmProfileApiView(
    id = id,
    name = name,
    provider = provider,
    endpoint = endpoint,
    model = model,
    contextWindow = contextWindow,
    maxOutputTokens = maxOutputTokens,
    inputPricePerMillion = inputPricePerMillion,
    outputPricePerMillion = outputPricePerMillion,
    cacheReadPricePerMillion = cacheReadPricePerMillion,
    enabled = enabled,
    apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
    keyAvailable = keyAvailable(System::getenv),
    toolCallingMeasured = toolCallingMeasured,
    capabilityCheckedAt = capabilityCheckedAt,
)

private fun io.ktor.server.application.ApplicationCall.promptRole(): LlmPromptRole =
    try {
        LlmPromptRole.valueOf(parameters["role"] ?: "")
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("role must be ASK or INVESTIGATE")
    }
