package infoscry.document

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.domain.JobType
import infoscry.image.ImageExtractionMode
import infoscry.jobs.ImageRescanJobPayload
import infoscry.collection.CollectionService
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator

/** Which documents a collection-wide image rescan would start a rescan for, and the job doing it, if any. */
data class ImageRescanCensus(
    /** Documents whose images would be found again. */
    val eligible: Int,
    /** Documents left out, by reason (`UNSUPPORTED_FORMAT`, `NOT_READ`, `DELETING`). */
    val skipped: Map<String, Int>,
    /** The collection's running image rescan job, else its latest one. */
    val job: Job?,
    /** How the documents [job] started are getting on; null when there was no run. */
    val run: ImageRescanRun? = null,
)

/**
 * The progress of one collection-wide image rescan, counted over the per-document rescans it started.
 *
 * [active] stays true while the job is still starting rescans or any of them is still running, so a reader can follow
 * the whole run the way an import is followed.
 */
data class ImageRescanRun(
    val jobId: String,
    /** Documents the run looked at. */
    val documents: Int,
    /** Documents it has looked at so far. */
    val examined: Int,
    /** Per-document rescans it started. */
    val started: Int,
    val done: Int,
    val failed: Int,
    val cancelled: Int,
    val scanning: Int,
    val waiting: Int,
    /** The document being scanned now, by its own file name. */
    val currentDocument: String?,
    val active: Boolean,
    val cancelRequested: Boolean,
    /** Why the run's job failed, when it did (no document could be started). */
    val errorCode: String? = null,
)

class ImageRescanModeRequiredException : ApiFaultException(
    ApiStatus.BAD_REQUEST,
    "IMAGE_MODE_REQUIRED",
    "Choose which images to extract.",
)

/**
 * Finds the images of every document of a collection without reading any text again.
 *
 * Starting it enqueues one [JobType.IMAGE_RESCAN] job; a collection never has two at once, so a second start — by the
 * same person or someone else — answers with the job already running.
 */
class CollectionImageRescanService(
    private val collections: CollectionService,
    private val documents: DocumentStore,
    private val jobs: JobStore,
    private val mutations: MutationCoordinator,
    private val rescans: RescanService,
    private val operations: infoscry.storage.OcrOperationStore,
    /** Cancels a job, signalling its worker when one is running it. */
    private val cancelJob: suspend (infoscry.domain.JobId) -> Job,
) {

    private val enqueueLock = Any()

    fun census(collectionId: CollectionId): ImageRescanCensus {
        collections.requireActive(collectionId)
        val count = documents.countByCollection(collectionId)
        val all = if (count == 0) emptyList() else documents.listByCollection(collectionId, limit = count)
        var eligible = 0
        val skipped = sortedMapOf<String, Int>()
        all.forEach { document ->
            when (val outcome = rescans.imageRescanEligibility(document)) {
                null -> eligible++
                else -> skipped.merge(outcome.name, 1, Int::plus)
            }
        }
        val job = jobs.activeJob(JobType.IMAGE_RESCAN, collectionId) ?: jobs.latestJob(JobType.IMAGE_RESCAN, collectionId)
        return ImageRescanCensus(eligible, skipped, job, job?.let { runOf(collectionId, it) })
    }

    /** How the collection's latest image rescan is getting on, or null when it never had one. */
    fun run(collectionId: CollectionId): ImageRescanRun? {
        collections.requireActive(collectionId)
        val job = jobs.activeJob(JobType.IMAGE_RESCAN, collectionId) ?: jobs.latestJob(JobType.IMAGE_RESCAN, collectionId)
        return job?.let { runOf(collectionId, it) }
    }

    /**
     * Stops the collection's image rescan: the job stops starting rescans, and every rescan it started that has not
     * ended is cancelled. Repeating it changes nothing more; rescans that finished keep their result.
     */
    suspend fun cancel(collectionId: CollectionId): ImageRescanRun? {
        collections.requireActive(collectionId)
        val job = jobs.activeJob(JobType.IMAGE_RESCAN, collectionId) ?: jobs.latestJob(JobType.IMAGE_RESCAN, collectionId)
            ?: return null
        if (job.state == infoscry.domain.JobState.QUEUED || job.state == infoscry.domain.JobState.RUNNING) cancelJob(job.id)
        childrenOf(collectionId, job).filter { !it.stage.isTerminal }.forEach { operation ->
            runCatching { rescans.cancel(collectionId, operation.documentId, operation.operationId) }
        }
        return runOf(collectionId, checkNotNull(jobs.get(job.id)))
    }

    private fun childrenOf(collectionId: CollectionId, job: Job): List<infoscry.ocr.OcrOperation> =
        operations.operationsWithRequestPrefix(collectionId.value, infoscry.jobs.ImageRescanJobHandler.requestPrefixOf(job))

    private fun runOf(collectionId: CollectionId, job: Job): ImageRescanRun {
        val children = childrenOf(collectionId, job)
        var done = 0
        var failed = 0
        var cancelled = 0
        var scanning = 0
        var waiting = 0
        var current: infoscry.ocr.OcrOperation? = null
        children.forEach { operation ->
            when (operation.stage) {
                infoscry.ocr.OcrOperationStage.COMPLETE -> done++
                infoscry.ocr.OcrOperationStage.CANCELLED -> cancelled++
                infoscry.ocr.OcrOperationStage.FAILED, infoscry.ocr.OcrOperationStage.NEEDS_TOOL -> failed++
                else -> {
                    val attempt = operation.jobId?.let { jobs.get(infoscry.domain.JobId(it)) }
                    if (attempt?.state == infoscry.domain.JobState.RUNNING) {
                        scanning++
                        if (current == null) current = operation
                    } else {
                        waiting++
                    }
                }
            }
        }
        val jobActive = job.state == infoscry.domain.JobState.QUEUED || job.state == infoscry.domain.JobState.RUNNING
        return ImageRescanRun(
            jobId = job.id.value,
            documents = job.total,
            examined = job.completed,
            started = children.size,
            done = done,
            failed = failed,
            cancelled = cancelled,
            scanning = scanning,
            waiting = waiting,
            currentDocument = current?.let { documents.get(it.documentId)?.originalFilename },
            active = jobActive || scanning + waiting > 0,
            cancelRequested = job.cancelRequested,
            errorCode = job.errorCode,
        )
    }

    /** The collection's running image rescan job, or a new one for [mode]. */
    suspend fun start(collectionId: CollectionId, mode: ImageExtractionMode): Job {
        if (mode == ImageExtractionMode.OFF) throw ImageRescanModeRequiredException()
        return mutations.withMutation {
            collections.requireMutationsAllowed()
            collections.requireActive(collectionId)
            synchronized(enqueueLock) {
                jobs.activeJob(JobType.IMAGE_RESCAN, collectionId)
                    ?: jobs.enqueue(
                        JobType.IMAGE_RESCAN,
                        collectionId,
                        payload = ImageRescanJobPayload.of(mode).encode(),
                        total = documents.countByCollection(collectionId),
                    )
            }
        }
    }
}
