package infoscry.jobs

import infoscry.domain.CollectionId
import infoscry.extract.ExtractionSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a retry job was asked to do: read existing documents again from what the archive already stores.
 *
 * It names document identifiers rather than paths on purpose. The managed copy is the archive's own
 * immutable original, so a retry keeps working after the external source was moved or deleted — and a
 * payload that named a source path would make the attempt depend on a file InfoScry never owned.
 *
 * [settings] travels here for the same reason it travels in an import: the fingerprint that decides which
 * committed units are reusable is computed from the settings the attempt runs with, and a collection's OCR
 * languages changing between admission and execution must not silently redefine what is already read.
 */
@Serializable
data class RetryJobPayload(
    val collectionId: String,
    val documentIds: List<String>,
    val settings: ExtractionSettings,
    /**
     * The OCR selection this retry was admitted with, or null for a payload that recorded none.
     *
     * It mirrors the import payload for the same two reasons: the external page allowance of the whole job
     * and the profile revisions an approval binds to travel here, so a retry dispatches its pages through
     * the same snapshotted engines, comparisons and allowance rules its admission chose. Null means "a
     * legacy payload", which is the same behavior the collection had before these settings existed:
     * Tesseract, fill-missing, no external pages.
     */
    val ocr: infoscry.ocr.OcrSettingsSnapshot? = null,
    val admissionRejected: List<infoscry.document.RejectedRetry> = emptyList(),
    val documentSnapshots: Map<String, infoscry.ocr.OcrSettingsSnapshot> = emptyMap(),
) {

    init {
        require(collectionId.isNotBlank()) { "a retry payload needs a collection" }
        require(documentIds.isNotEmpty()) { "a retry payload needs at least one document" }
        require(documentIds.none { it.isBlank() }) { "a retry payload must not hold a blank document id" }
    }

    val collection: CollectionId get() = CollectionId(collectionId)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {

        /**
         * Reads a job's payload back.
         *
         * A payload that cannot be read is a programming error rather than a user error: the job was
         * written by this application, and failing loudly beats reading the wrong documents.
         */
        fun decode(payload: String?): RetryJobPayload {
            require(!payload.isNullOrBlank()) { "a retry job has no payload to read" }
            return runCatching { json.decodeFromString(serializer(), payload) }
                .getOrElse { failure ->
                    throw IllegalArgumentException("the retry job's payload could not be read", failure)
                }
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}
