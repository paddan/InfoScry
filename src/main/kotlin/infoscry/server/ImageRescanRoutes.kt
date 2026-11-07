package infoscry.server

import infoscry.AppContext
import infoscry.image.ImageExtractionMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class ImageRescanRunWire(
    val jobId: String,
    val documents: Int,
    val examined: Int,
    val started: Int,
    val done: Int,
    val failed: Int,
    val cancelled: Int,
    val scanning: Int,
    val waiting: Int,
    val currentDocument: String? = null,
    val active: Boolean,
    val cancelRequested: Boolean,
    val errorCode: String? = null,
)

@Serializable
data class ImageRescanRunResponse(val run: ImageRescanRunWire?)

@Serializable
data class ImageRescanCensusWire(val eligible: Int, val skipped: Map<String, Int>, val job: JobApiView?, val run: ImageRescanRunWire? = null)

private fun infoscry.document.ImageRescanRun.toWire() = ImageRescanRunWire(
    jobId, documents, examined, started, done, failed, cancelled, scanning, waiting, currentDocument, active, cancelRequested, errorCode,
)

@Serializable
data class StartImageRescanRequest(val imageExtraction: String)

/** Finding the images of every document of a collection; mounted under `/api/collections/{id}`. */
fun Route.imageRescanRoutes(context: AppContext) {
    get("/image-rescan") {
        call.handle {
            val census = context.collectionImageRescanService.census(call.collectionId())
            call.respondJson(
                HttpStatusCode.OK,
                ImageRescanCensusWire(census.eligible, census.skipped, census.job?.toApiView(context), census.run?.toWire()),
            )
        }
    }

    post("/image-rescan") {
        call.handle {
            val request = call.receiveJson<StartImageRescanRequest>()
            val mode = try {
                ImageExtractionMode.parse(request.imageExtraction)
            } catch (_: IllegalArgumentException) {
                throw BadRequestException("unknown image extraction mode")
            }
            val job = context.collectionImageRescanService.start(call.collectionId(), mode)
            call.respondJson(HttpStatusCode.OK, JobResponse(job.toApiView(context)))
        }
    }

    get("/image-rescan/run") {
        call.handle {
            call.respondJson(HttpStatusCode.OK, ImageRescanRunResponse(context.collectionImageRescanService.run(call.collectionId())?.toWire()))
        }
    }

    post("/image-rescan/cancel") {
        call.handle {
            call.respondJson(HttpStatusCode.OK, ImageRescanRunResponse(context.collectionImageRescanService.cancel(call.collectionId())?.toWire()))
        }
    }
}
