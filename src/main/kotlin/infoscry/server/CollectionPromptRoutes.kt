package infoscry.server

import infoscry.AppContext
import infoscry.collection.CollectionPrompts
import infoscry.collection.RolePrompts
import infoscry.llm.CollectionPrompt
import infoscry.llm.LlmPromptRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** One prompt as the client sees it. The Default prompt is `builtIn` and never has a stored row. */
@Serializable
data class PromptWire(val id: String, val name: String, val body: String, val version: Int, val builtIn: Boolean)

@Serializable
data class RolePromptsWire(val defaultId: String, val prompts: List<PromptWire>)

@Serializable
data class CollectionPromptsWire(val coreRules: String, val ask: RolePromptsWire, val investigate: RolePromptsWire)

@Serializable
data class CreatePromptRequest(val role: LlmPromptRole, val name: String, val body: String)

@Serializable
data class UpdatePromptRequest(val name: String, val body: String)

@Serializable
data class SetPromptDefaultRequest(val promptId: String)

@Serializable
data class PromptDefaultWire(val role: LlmPromptRole, val defaultId: String)

/**
 * A collection's Ask and Investigate prompts. Routes stay thin: the service owns the rules, mutation
 * admission and the idempotent repeat answers. Mounted under `/api/collections/{id}`.
 */
fun Route.collectionPromptRoutes(context: AppContext) {
    get("/prompts") {
        call.handle {
            call.respondJson(HttpStatusCode.OK, context.collectionPrompts.list(call.collectionId()).toWire())
        }
    }

    // 201 when a new prompt was stored; 200 when the identical prompt already existed.
    post("/prompts") {
        call.handle {
            val id = call.collectionId()
            val request = call.receiveJson<CreatePromptRequest>()
            val write = context.collectionPrompts.create(id, request.role, request.name, request.body)
            call.respondJson(
                if (write.created) HttpStatusCode.Created else HttpStatusCode.OK,
                write.prompt.toWire(),
            )
        }
    }

    put("/prompts/{promptId}") {
        call.handle {
            val id = call.collectionId()
            val promptId = call.promptId()
            val request = call.receiveJson<UpdatePromptRequest>()
            call.respondJson(
                HttpStatusCode.OK,
                context.collectionPrompts.update(id, promptId, request.name, request.body).toWire(),
            )
        }
    }

    delete("/prompts/{promptId}") {
        call.handle {
            context.collectionPrompts.delete(call.collectionId(), call.promptId())
            call.respondText("", status = HttpStatusCode.NoContent)
        }
    }

    put("/prompt-defaults/{role}") {
        call.handle {
            val id = call.collectionId()
            val role = call.promptRole()
            val request = call.receiveJson<SetPromptDefaultRequest>()
            val defaultId = context.collectionPrompts.setDefault(id, role, request.promptId)
            call.respondJson(HttpStatusCode.OK, PromptDefaultWire(role, defaultId))
        }
    }
}

private fun ApplicationCall.promptId(): String =
    parameters["promptId"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a prompt id is required in the path")

private fun ApplicationCall.promptRole(): LlmPromptRole {
    val raw = parameters["role"].orEmpty()
    return LlmPromptRole.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw BadRequestException("the prompt role must be ask or investigate")
}

private fun CollectionPrompt.toWire() = PromptWire(id, name, body, version, builtIn)

private fun RolePrompts.toWire() = RolePromptsWire(defaultId, prompts.map { it.toWire() })

private fun CollectionPrompts.toWire() = CollectionPromptsWire(coreRules, ask.toWire(), investigate.toWire())
