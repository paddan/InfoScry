package infoscry.image.analysis

import infoscry.account.UserId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import kotlinx.serialization.Serializable

/** What an image tag describes: a person, an object, an animal, a place, or text visible in the picture. */
@Serializable
enum class TagCategory {
    PERSON,
    OBJECT,
    ANIMAL,
    PLACE,
    TEXT,
}

/**
 * Where a tag's specific name came from.
 *
 * [TEXT] is a name the caption or the page around the picture states, [APPEARANCE] one the model recognised
 * from the pixels and which is therefore uncertain, and [MANUAL] one a person added or confirmed.
 */
@Serializable
enum class TagSource {
    TEXT,
    APPEARANCE,
    MANUAL,
}

/**
 * One thing an image shows: the generic [label], and the specific [name] when one is known.
 *
 * The values are already trimmed and bounded here: a tag is written to the database and rendered as search
 * text, so a label that would make two tags compare differently after trimming, or a name longer than a
 * caption, is refused where it is built rather than silently normalised later.
 */
@Serializable
data class ImageTag(
    val category: TagCategory,
    val label: String,
    val name: String? = null,
    val source: TagSource,
) {
    init {
        require(label.isNotBlank()) { "an image tag's label must not be blank" }
        require(label == label.trim()) { "an image tag's label must be trimmed" }
        require(label.length <= MAX_FIELD_LENGTH) {
            "an image tag's label is at most $MAX_FIELD_LENGTH characters, was ${label.length}"
        }
        name?.let {
            require(it.isNotBlank()) { "an image tag's name is either absent or not blank" }
            require(it == it.trim()) { "an image tag's name must be trimmed" }
            require(it.length <= MAX_FIELD_LENGTH) {
                "an image tag's name is at most $MAX_FIELD_LENGTH characters, was ${it.length}"
            }
        }
    }

    companion object {
        /** The longest label or name one tag may carry. */
        const val MAX_FIELD_LENGTH: Int = 120
    }
}

/** Whether an analysis completed or failed; a failed one is retried by the next run. */
enum class AnalysisOutcome {
    DONE,
    FAILED,
}

/**
 * Everything that decides what an image's analysis is: the image, the profile revision and prompt that read
 * it, the collection's language, and whether appearance-derived names were asked for.
 *
 * Reuse is keyed by the whole value: analyising the same image with the same inputs is not a model call.
 */
data class ImageAnalysisKey(
    val documentId: DocumentId,
    val unitId: ContentUnitId,
    val imageSha256: String,
    val profileRevisionId: String,
    val promptVersion: Int,
    val language: String,
    val identifyAppearance: Boolean,
) {
    init {
        require(imageSha256.length == 64) { "an image analysis names a SHA-256, was '${imageSha256.take(16)}'" }
        require(profileRevisionId.isNotBlank()) { "an image analysis names the profile revision that read it" }
        require(promptVersion > 0) { "an image analysis names a positive prompt version, was $promptVersion" }
        require(language.isNotBlank()) { "an image analysis names its language" }
    }
}

/**
 * One stored analysis of one image.
 *
 * [description] and [tags] belong to a [AnalysisOutcome.DONE] analysis; [errorCode] to a failed one. A failed
 * analysis is kept so the run that met it can report why, and is replaced by the next attempt's result.
 */
data class ImageAnalysis(
    val key: ImageAnalysisKey,
    val outcome: AnalysisOutcome,
    val description: String?,
    val tags: List<ImageTag>,
    val errorCode: String?,
    /** The caption the analysis was made with, kept so a tag edit can rebuild the unit's text. */
    val caption: String? = null,
    /** The page text the analysis was made with, for the same reason. */
    val pageContext: String? = null,
) {
    init {
        require(tags.size <= MAX_TAGS) { "an image analysis carries at most $MAX_TAGS tags, had ${tags.size}" }
        when (outcome) {
            AnalysisOutcome.DONE -> {
                require(description != null) { "a done image analysis carries its description" }
                require(errorCode == null) { "a done image analysis carries no error code" }
            }
            AnalysisOutcome.FAILED -> {
                require(errorCode != null) { "a failed image analysis carries its error code" }
                require(tags.isEmpty()) { "a failed image analysis carries no tags" }
            }
        }
    }

    companion object {
        /** The most tags one image analysis may carry. */
        const val MAX_TAGS: Int = 30
    }
}

/** What a person did to one image's tags. */
@Serializable
enum class TagEditAction {
    ADD,
    REMOVE,
    CONFIRM,
}

/**
 * One person's edit of one image's tags, applied on top of every analysis.
 *
 * [editId] makes the edit idempotent: a repeated request with the same id changes nothing. [createdBy] is the
 * person who made it, when the edit came from someone.
 */
data class ImageTagEdit(
    val editId: String,
    val documentId: DocumentId,
    val unitId: ContentUnitId,
    val action: TagEditAction,
    val tag: ImageTag,
    val createdBy: UserId?,
    val createdAt: String,
) {
    init {
        require(editId.isNotBlank()) { "a tag edit names itself" }
        require(createdAt.isNotBlank()) { "a tag edit records when it was made" }
    }
}
