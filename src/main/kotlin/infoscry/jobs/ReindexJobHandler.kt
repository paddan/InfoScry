package infoscry.jobs

import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.search.ReindexRequest
import infoscry.search.ReindexResult
import infoscry.search.ReindexService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one reindex job was asked to do.
 *
 * [collectionId] names one collection to rebuild; `null` rebuilds every live collection. The payload is
 * the job's durable record of its own request, so a job that a restart re-runs rebuilds what the caller
 * asked for and not what happened to be in memory.
 */
@Serializable
data class ReindexJobPayload(val collectionId: String? = null) {

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {

        private val JSON = Json { ignoreUnknownKeys = true }

        fun decode(payload: String?): ReindexJobPayload =
            requireNotNull(payload) { "a reindex job has no payload to read" }.let { raw ->
                JSON.decodeFromString(serializer(), raw)
            }
    }
}

/**
 * The reindex job's handler: one rebuild, driven by the service that owns the swap protocol.
 *
 * The rebuild holds exclusive maintenance for its whole duration, which is why this handler never asks
 * the mutation gate for admission: the gate's exclusive side is *it*, and waiting for admission would
 * mean waiting for itself. Progress and cancellation both go through the maintenance-owner reporter
 * instead, which rechecks the attempt between documents — a cancelled rebuild stops after the document
 * it just made durable, and the successor it had begun is discarded.
 */
class ReindexJobHandler(private val service: ReindexService) : JobHandler {

    override suspend fun handle(job: Job, stage: JobStage) {
        val payload = ReindexJobPayload.decode(job.payload)
        val request = ReindexRequest(collectionId = payload.collectionId?.let { CollectionId(it) })
        stage.reportWhileMaintaining(ReindexService.STAGE_REBUILD, completed = 0, total = 0)
        val result = service.reindex(
            request = request,
            onProgress = { progress ->
                stage.reportWhileMaintaining(progress.stage, progress.completed, progress.total)
            },
        )
        stage.reportProgress(completed = result.documents, total = result.documents)
    }
}
