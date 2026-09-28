package infoscry.server

import infoscry.AppContext
import infoscry.storage.DeletionOperation
import infoscry.storage.DeletionPhase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * One deletion operation as a client may read it.
 *
 * Deliberately not [DeletionOperation]: the record carries the trash basename, which names a directory
 * in the data library, and the raw failure message, which may quote absolute paths inside it. Neither
 * crosses. What is left is what a reader can act on — what is being deleted, how far the deletion has
 * got, whether it is finished, and why it stopped when it did.
 *
 * [documentIds] is empty for a collection deletion and is the shape document deletion extends: the kind
 * says what the ids in [collectionId] and [documentIds] mean.
 */
@Serializable
data class DeletionOperationApiView(
    val operationId: String,
    val kind: String,
    val collectionId: String,
    val collectionName: String,
    val documentIds: List<String> = emptyList(),
    val phase: String,
    val terminal: Boolean,
    val errorCode: String? = null,
)

@Serializable
data class DeletionOperationResponse(val operation: DeletionOperationApiView)

/** The unfinished deletions, oldest first: what a reopened Admin restores as still running. */
@Serializable
data class DeletionsResponse(val deletions: List<DeletionOperationApiView>)

/**
 * `GET /api/deletions` and `GET /api/deletions/{operationId}` — read-only deletion status.
 *
 * The status is read from SQLite, not from the memory of the request that asked for the deletion, so it
 * answers after the collection's own row is gone and after the process that admitted the deletion has
 * been replaced. That is what lets the initiating UI follow a deletion to `DONE` and a reopened Admin
 * restore the ones that are still unfinished. Nothing here is a managed or trash path.
 *
 * Reads like the other collection reads: loopback-only, no credential. An unknown operation is the same
 * typed 404 the rest of the API answers.
 */
fun Routing.configureDeletionRoutes(context: AppContext) {
    route("/api/deletions") {
        get {
            call.handle {
                call.respondJson(
                    HttpStatusCode.OK,
                    DeletionsResponse(context.collectionService.unfinishedDeletions().map { it.toApiView() }),
                )
            }
        }

        get("/{operationId}") {
            call.handle {
                val id = call.parameters["operationId"]?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("a deletion operation id is required in the path")
                val operation = context.collectionService.deletionOperation(id)
                    ?: throw NoSuchElementException("no deletion operation with id $id")
                call.respondJson(HttpStatusCode.OK, DeletionOperationResponse(operation.toApiView()))
            }
        }
    }
}

internal fun DeletionOperation.toApiView() = DeletionOperationApiView(
    operationId = id,
    kind = kind.name,
    collectionId = collectionId.value,
    collectionName = collectionName,
    documentIds = targets.map { it.documentId.value },
    phase = phase.name,
    terminal = phase == DeletionPhase.DONE,
    errorCode = errorCode,
)
