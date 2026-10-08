package infoscry.jobs

import infoscry.domain.CollectionId
import infoscry.extract.ExtractionSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an import job was asked to do, captured when it was enqueued.
 *
 * The job carries its own request rather than re-reading the current state, because an attempt may run
 * much later than the command that queued it — after a restart, after a pause — and must do what was
 * asked rather than what the settings happen to say by then. That is why [settings] travels here: an
 * extraction resumed tomorrow must be comparable with the checkpoints committed today, and a collection's
 * OCR languages changing in between must not silently redefine which units are already done.
 */
@Serializable
data class ImportJobPayload(
    val collectionId: String,
    val sources: List<String>,
    val settings: ExtractionSettings,
    /**
     * The OCR selection this import was admitted with, or null for an import that recorded none.
     *
     * It is here for the two things an import's OCR needs that the extraction settings cannot carry: the
     * external page allowance of the whole job — twenty files share one allowance — and the profile
     * revisions an approval binds to. Null means "a legacy payload", which is the same behavior the
     * collection had before these settings existed: Tesseract, fill-missing, no external pages.
     */
    val ocr: infoscry.ocr.OcrSettingsSnapshot? = null,
    // Defaulted rather than required, and it has to default to false: this payload's own JSON omits a
    // defaulted field, so a new non-recursive job is written without it and has to read back as false.
    // A payload written before this flag existed therefore also reads as false, and a directory it named
    // is read top-level-only on resume -- the same deliberate change the flag makes everywhere else.
    val recursive: Boolean = false,
    /** Which extensions this import keeps. Defaulted so a payload written before the filter reads as no filter. */
    val extensions: ExtensionFilter = ExtensionFilter.NONE,
    /**
     * The collection's ignore patterns as they were when this import was admitted. A snapshot, not a reference:
     * a later edit of the collection's list must not change an import that is queued, running or resumed. A payload
     * written before this field reads as no patterns.
     */
    val ignore: IgnorePatterns = IgnorePatterns.NONE,
) {

    init {
        require(collectionId.isNotBlank()) { "an import payload needs a collection" }
        require(sources.isNotEmpty()) { "an import payload needs at least one source" }
        // Blank or relative paths would make the job's meaning depend on the working directory of
        // whichever process runs it, which is exactly what a durable request must not do.
        require(sources.none { it.isBlank() }) { "an import payload must not hold a blank source path" }
        require(sources.all { java.nio.file.Path.of(it).isAbsolute }) {
            "an import payload must hold absolute source paths"
        }
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {

        /**
         * The payload for one import request: [sources] are canonicalised once, here, so every later
         * stage works with the same paths the enqueueing command validated.
         */
        fun of(
            collectionId: CollectionId,
            sources: List<String>,
            settings: ExtractionSettings,
            recursive: Boolean = false,
            ocr: infoscry.ocr.OcrSettingsSnapshot? = null,
            extensions: ExtensionFilter = ExtensionFilter.NONE,
            ignore: IgnorePatterns = IgnorePatterns.NONE,
        ): ImportJobPayload = ImportJobPayload(
            collectionId = collectionId.value,
            sources = sources.map { java.nio.file.Path.of(it).toAbsolutePath().normalize().toString() },
            settings = settings,
            ocr = ocr,
            recursive = recursive,
            extensions = extensions,
            ignore = ignore,
        )

        /**
         * Reads a job's payload back.
         *
         * A payload that cannot be read is a programming error rather than a user error: the job was
         * written by this application. Failing loudly beats importing something else than what was asked.
         */
        fun decode(payload: String?): ImportJobPayload {
            require(!payload.isNullOrBlank()) { "an import job has no payload to read" }
            return runCatching { json.decodeFromString(serializer(), payload) }
                .getOrElse { failure ->
                    throw IllegalArgumentException("the import job's payload could not be read", failure)
                }
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}
