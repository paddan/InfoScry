package infoscry.server

import infoscry.AppContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete

/**
 * One delete route serves both views: an Ask answer and an Investigate conversation are the same
 * persistent conversation here, so a single collection-scoped delete covers the reader's history
 * column rows and the operator's API callers alike.
 */
fun Routing.configureConversationRoutes(context: AppContext) {
    delete("/api/collections/{id}/conversations/{conversationId}") {
        call.handle {
            val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
            val conversationId = call.parameters["conversationId"]?.takeIf(String::isNotBlank)
                ?: throw BadRequestException("a conversation id is required in the path")
            val deleted = context.mutations.withMutation {
                context.collectionService.requireMutationsAllowed()
                context.llm.deleteConversation(collection.id, conversationId)
            }
            if (!deleted) {
                // Unknown ids and ids owned by another collection are the same answer: not-found, and
                // nothing deleted, so one collection's key can never touch another collection's rows.
                throw NoSuchElementException("no conversation with id $conversationId exists in this collection")
            }
            call.respondText("", status = HttpStatusCode.NoContent)
        }
    }
}