package infoscry.server

import infoscry.AppContext
import infoscry.glossary.Glossary
import infoscry.glossary.GlossaryGroup
import infoscry.glossary.GlossaryTermInput
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
data class GlossaryTermWire(val text: String, val prefix: Boolean = false)

/** One group of names with the same meaning, terms in the order typed. */
@Serializable
data class GlossaryGroupWire(
    val id: String,
    val terms: List<GlossaryTermWire>,
    /** The group's version, to send back in `If-Match` with an edit; absent where the group is only named. */
    val version: Long? = null,
    /** Who last changed the group. */
    val updatedBy: infoscry.domain.UserRef? = null,
    val updatedAt: String? = null,
)

@Serializable
data class GlossaryListWire(val groups: List<GlossaryGroupWire>)

@Serializable
data class PutGlossaryGroupRequest(val terms: List<GlossaryTermWire>)

/**
 * A collection's glossary. Routes stay thin: [infoscry.glossary.GlossaryService] owns the rules, mutation admission
 * and the idempotent repeat answers. Mounted under `/api/collections/{id}`.
 */
fun Route.glossaryRoutes(context: AppContext) {
    get("/glossary") {
        call.handle {
            call.respondJson(HttpStatusCode.OK, context.glossaryService.list(call.collectionId()).toWire())
        }
    }

    put("/glossary/{groupId}") {
        call.handle {
            val id = call.collectionId()
            val request = call.receiveJson<PutGlossaryGroupRequest>()
            val group = context.glossaryService.put(id, call.groupId(), request.terms.map { GlossaryTermInput(it.text, it.prefix) }, call.ifMatchVersion())
            call.respondJson(HttpStatusCode.OK, group.toWire())
        }
    }

    delete("/glossary/{groupId}") {
        call.handle {
            context.glossaryService.delete(call.collectionId(), call.groupId())
            call.respondText("", status = HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.groupId(): String =
    parameters["groupId"]?.takeIf { it.isNotBlank() } ?: throw BadRequestException("a glossary group id is required in the path")

fun GlossaryGroup.toWire() = GlossaryGroupWire(id, terms.map { GlossaryTermWire(it.text, it.prefix) }, version, updatedBy, updatedAt)

private fun Glossary.toWire() = GlossaryListWire(groups.map { it.toWire() })
