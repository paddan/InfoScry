package infoscry.jobs

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.Job
import infoscry.document.ImageRescanOutcome
import infoscry.document.ImageRescanStep
import infoscry.image.ImageExtractionMode
import infoscry.ocr.ImageAnalysisRequest
import infoscry.storage.DocumentStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** No document of the collection could be scanned for images; [code] is the first refusal's. */
class ImageRescanFailedException(val code: String, message: String) : IllegalStateException(message)

/** What a collection-wide image rescan was started with. */
@Serializable
data class ImageRescanJobPayload(
    val imageExtraction: String,
    /** How the run describes its pictures; absent describes none. */
    val imageAnalysis: ImageAnalysisRequest? = null,
) {

    val mode: ImageExtractionMode get() = ImageExtractionMode.parse(imageExtraction)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        fun of(mode: ImageExtractionMode, imageAnalysis: ImageAnalysisRequest? = null): ImageRescanJobPayload =
            ImageRescanJobPayload(mode.id, imageAnalysis)

        fun decode(payload: String?): ImageRescanJobPayload {
            require(!payload.isNullOrBlank()) { "an image rescan job has no payload to read" }
            return runCatching { json.decodeFromString(serializer(), payload) }
                .getOrElse { failure -> throw IllegalArgumentException("the image rescan job's payload could not be read", failure) }
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Finds the images of every document of a collection without reading any text again.
 *
 * The job walks the collection's documents and starts, for each one that can be read again from page images, the
 * same *Keep current text* rescan the per-document dialog starts. Each of those is an ordinary rescan operation with
 * its own progress; this job only admits them, so it ends quickly and never holds a worker while the images are found.
 *
 * Repeating is safe: each document's step is named by this job and that document, so a resumed or repeated job
 * answers with the rescan it already started. A document another reading is running on is left alone.
 */
class ImageRescanJobHandler(
    private val documents: DocumentStore,
    private val start: suspend (CollectionId, DocumentId, ImageExtractionMode, String, ImageAnalysisRequest?) -> ImageRescanStep,
) : JobHandler {

    override suspend fun handle(job: Job, stage: JobStage) {
        val collectionId = requireNotNull(job.collectionId) { "an image rescan job names its collection" }
        val payload = ImageRescanJobPayload.decode(job.payload)
        val mode = payload.mode
        val ids = documentIds(collectionId)
        stage.reportProgress(completed = 0, total = ids.size)
        val outcomes = mutableMapOf<ImageRescanOutcome, Int>()
        val refusals = linkedMapOf<String, Pair<String, Int>>()
        ids.forEachIndexed { index, documentId ->
            val step = start(collectionId, documentId, mode, requestIdOf(job, documentId), payload.imageAnalysis)
            outcomes.merge(step.outcome, 1, Int::plus)
            step.refusal?.let { refusal ->
                val (message, count) = refusals[refusal.code] ?: ((refusal.message ?: refusal.code) to 0)
                refusals[refusal.code] = message to count + 1
                LOGGER.atWarn()
                    .addKeyValue("job_id", job.id.value)
                    .addKeyValue("document_id", documentId.value)
                    .addKeyValue("error_code", refusal.code)
                    .log("an image rescan step was refused")
            }
            stage.reportProgress(completed = index + 1, total = ids.size)
        }
        val started = (outcomes[ImageRescanOutcome.STARTED] ?: 0) + (outcomes[ImageRescanOutcome.ALREADY_STARTED] ?: 0)
        val (firstCode, first) = refusals.entries.firstOrNull()?.let { it.key to it.value } ?: (null to null)
        if (started == 0 && firstCode != null && first != null) {
            throw ImageRescanFailedException(
                firstCode,
                "No document could be scanned for images. ${count(first.second)} refused: ${first.first}",
            )
        }
        stage.reportCurrentItem(summaryOf(outcomes))
    }

    private fun summaryOf(outcomes: Map<ImageRescanOutcome, Int>): String {
        val started = (outcomes[ImageRescanOutcome.STARTED] ?: 0) + (outcomes[ImageRescanOutcome.ALREADY_STARTED] ?: 0)
        val parts = buildList {
            add("${count(started)} started")
            outcomes[ImageRescanOutcome.BUSY]?.let { add("${count(it)} already being scanned") }
            outcomes[ImageRescanOutcome.REFUSED]?.let { add("${count(it)} refused") }
            val skipped = listOf(ImageRescanOutcome.UNSUPPORTED_FORMAT, ImageRescanOutcome.NOT_READ, ImageRescanOutcome.DELETING)
                .sumOf { outcomes[it] ?: 0 }
            if (skipped > 0) add("${count(skipped)} left out")
        }
        return parts.joinToString(", ")
    }

    private fun count(n: Int): String = if (n == 1) "1 document" else "$n documents"

    private fun documentIds(collectionId: CollectionId): List<DocumentId> {
        val count = documents.countByCollection(collectionId)
        if (count == 0) return emptyList()
        return documents.listByCollection(collectionId, limit = count).map { it.id }.reversed()
    }

    companion object {
        /** The rescan request one document's step makes: the same job and document always name the same request. */
        fun requestIdOf(job: Job, documentId: DocumentId): String = requestPrefixOf(job) + documentId.value

        /** What every request [job] makes starts with. */
        fun requestPrefixOf(job: Job): String = "image-rescan:${job.id.value}:"

        /** The image rescan job a rescan request was made by, or null for a request none made. */
        fun jobIdOfRequest(requestId: String): String? =
            requestId.takeIf { it.startsWith("image-rescan:") }?.removePrefix("image-rescan:")?.substringBefore(':')?.takeIf { it.isNotBlank() }
    }
}

private val LOGGER = org.slf4j.LoggerFactory.getLogger("infoscry.jobs")
