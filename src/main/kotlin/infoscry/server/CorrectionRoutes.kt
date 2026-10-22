package infoscry.server

import infoscry.AppContext
import infoscry.correction.CorrectionListView
import infoscry.correction.CorrectionPreview
import infoscry.correction.CorrectionRule
import infoscry.correction.CorrectionRuleInput
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** One correction rule as the client sees it, with how many places it changed in published text. */
@Serializable
data class CorrectionRuleWire(val id: String, val find: String, val replace: String, val exactCase: Boolean, val matches: Int = 0)

/** A collection's rules and its correction job (running, or the last one; `null` when none ever ran). */
@Serializable
data class CorrectionListWire(val rules: List<CorrectionRuleWire>, val job: JobApiView?)

@Serializable
data class PutCorrectionRequest(val find: String, val replace: String, val exactCase: Boolean = false)

/** A draft rule to preview (optionally replacing [replacingId]), or [removingId] to preview a removal. */
@Serializable
data class PreviewCorrectionRequest(
    val find: String? = null,
    val replace: String? = null,
    val exactCase: Boolean = false,
    val replacingId: String? = null,
    val removingId: String? = null,
)

@Serializable
data class CorrectionExampleWire(val documentId: String, val sourceId: String, val before: String, val after: String)

@Serializable
data class CorrectionPreviewWire(val matches: Int, val documents: Int, val examples: List<CorrectionExampleWire>)

/**
 * A collection's standing corrections. Routes stay thin: [infoscry.correction.CorrectionService] owns the rules,
 * mutation admission, the job and the idempotent repeat answers. Mounted under `/api/collections/{id}`.
 */
fun Route.correctionRoutes(context: AppContext) {
    get("/corrections") {
        call.handle {
            call.respondJson(HttpStatusCode.OK, context.correctionService.list(call.collectionId()).toWire(context))
        }
    }

    put("/corrections/{ruleId}") {
        call.handle {
            val id = call.collectionId()
            val request = call.receiveJson<PutCorrectionRequest>()
            val rule = context.correctionService.put(id, call.ruleId(), CorrectionRuleInput(request.find, request.replace, request.exactCase))
            call.respondJson(HttpStatusCode.OK, rule.toWire())
        }
    }

    delete("/corrections/{ruleId}") {
        call.handle {
            context.correctionService.delete(call.collectionId(), call.ruleId())
            call.respondText("", status = HttpStatusCode.NoContent)
        }
    }

    post("/corrections/preview") {
        call.handle {
            val id = call.collectionId()
            val request = call.receiveJson<PreviewCorrectionRequest>()
            val preview = if (request.removingId != null) {
                context.correctionService.previewRemoval(id, request.removingId)
            } else {
                val find = request.find ?: throw BadRequestException("a preview needs the text to find")
                val replace = request.replace ?: throw BadRequestException("a preview needs the text to write instead")
                context.correctionService.preview(id, CorrectionRuleInput(find, replace, request.exactCase), request.replacingId)
            }
            call.respondJson(HttpStatusCode.OK, preview.toWire())
        }
    }

    post("/corrections/apply") {
        call.handle {
            call.respondJson(HttpStatusCode.OK, JobResponse(context.correctionService.apply(call.collectionId()).toApiView(context)))
        }
    }
}

private fun ApplicationCall.ruleId(): String =
    parameters["ruleId"]?.takeIf { it.isNotBlank() } ?: throw BadRequestException("a correction id is required in the path")

private fun CorrectionRule.toWire(matches: Int = 0) = CorrectionRuleWire(id, find, replace, exactCase, matches)

private fun CorrectionListView.toWire(context: AppContext) =
    CorrectionListWire(rules.map { it.rule.toWire(it.matches) }, job?.toApiView(context))

private fun CorrectionPreview.toWire() = CorrectionPreviewWire(
    matches = matches,
    documents = documents,
    examples = examples.map { CorrectionExampleWire(it.documentId.value, it.unitId.value, it.before, it.after) },
)
