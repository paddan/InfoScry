package infoscry.server

import infoscry.AppContext
import infoscry.ocr.ReadingMethodCatalog
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class ReadingMethodsResponse(val methods: List<ReadingMethodOption>, val default: String?)

@Serializable
data class ReadingMethodOption(
    val method: String,
    val label: String,
    val destination: String,
    val available: Boolean,
    val unavailableReason: String?,
    val external: Boolean,
)

fun Routing.configureReadingMethodRoutes(context: AppContext, catalog: ReadingMethodCatalog = context.readingMethodCatalog) {
    route("/api/collections/{id}/reading-methods") {
        get {
            call.handle {
                val id = call.collectionId()
                val collection = context.collectionService.requireActiveByNameOrId(id.value)
                // A rescan dialog asks with `?for=rescan` and is also offered Keep current text; an import never is.
                val methods = if (call.request.queryParameters["for"] == "rescan") catalog.rescanAvailability(id) else catalog.availability(id)
                val options = methods.map { method ->
                    ReadingMethodOption(
                        method = method.method.id,
                        label = method.label,
                        destination = method.destination,
                        available = method.available,
                        unavailableReason = method.unavailableReason,
                        external = method.external,
                    )
                }
                call.respondJson(
                    HttpStatusCode.OK,
                    ReadingMethodsResponse(
                        methods = options,
                        default = collection.ocrSettings().defaultMethod.id,
                    ),
                )
            }
        }
    }
}
