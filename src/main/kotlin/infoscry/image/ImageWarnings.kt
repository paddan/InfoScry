package infoscry.image

import infoscry.ocr.SuryaOcr

/**
 * Which image warning a document records, and the sentence it is recorded with.
 *
 * An image stage can report several distinct codes for one document; the document keeps one error code and message
 * (as its other warnings do), so the most important is chosen: what the person can act on comes before what only
 * describes a limit. Codes never carry text from the document.
 */
object ImageWarnings {

    /** The code the ingest reports when the image stage itself fails; kept here so the ranking can name it. */
    const val INTERNAL_FAILURE_CODE: String = "IMAGE_EXTRACTION_FAILED"

    /** From the most to the least important. */
    private val PRIORITY: List<String> = listOf(
        SuryaLayoutUnavailableException.NEEDS_SURYA_LAYOUT,
        ImageStage.SOURCE_UNREADABLE,
        INTERNAL_FAILURE_CODE,
        ImageFindFailure.PARENT_UNREADABLE,
        ImageStage.LIMIT_REACHED,
        ImageFindFailure.TOO_MANY_IMAGES,
        infoscry.image.analysis.AnalysisRules.INVALID,
        infoscry.image.analysis.AnalysisRules.REFUSED,
        infoscry.image.analysis.AnalysisRules.TIMEOUT,
        ImageRejectedException.IMAGE_TOO_LARGE,
        ImageRejectedException.IMAGE_DECODE_FAILED,
        ImageStage.PARENT_TOO_LATE,
    )

    /** The code to record for [codes], or null when there is none. An unknown code ranks after the known ones. */
    fun mostImportant(codes: Collection<String>): String? =
        codes.minByOrNull { code -> PRIORITY.indexOf(code).let { if (it < 0) PRIORITY.size else it } }

    /** Whether [code] is one of the image warnings. */
    fun knows(code: String): Boolean = code in PRIORITY

    /** The sentence a document records for [code]. */
    fun messageFor(code: String): String = when (code) {
        SuryaLayoutUnavailableException.NEEDS_SURYA_LAYOUT ->
            "pictures on scanned pages could not be located because Surya is not available: ${SuryaOcr.installRemedy()}"
        ImageStage.SOURCE_UNREADABLE -> "the document could not be opened to look for its images"
        INTERNAL_FAILURE_CODE -> "the images of the document could not be extracted; its text was kept"
        ImageFindFailure.PARENT_UNREADABLE -> "the images of some pages or sections could not be read to the end"
        ImageStage.LIMIT_REACHED ->
            "the document holds more images than the limit of ${ImageFilter.MAX_PER_DOCUMENT}; the rest were not kept"
        ImageFindFailure.TOO_MANY_IMAGES ->
            "a page or section holds more images than ${ImageIdentity.MAX_PER_PARENT}; the rest were not read"
        ImageRejectedException.IMAGE_TOO_LARGE -> "an image was too large to keep"
        ImageRejectedException.IMAGE_DECODE_FAILED -> "an image could not be decoded"
        infoscry.image.analysis.AnalysisRules.INVALID ->
            "a picture could not be described because the image model's answer was not usable"
        infoscry.image.analysis.AnalysisRules.REFUSED ->
            "a picture could not be described because the image model refused it"
        infoscry.image.analysis.AnalysisRules.TIMEOUT ->
            "a picture could not be described before the image model timed out"
        ImageStage.PARENT_TOO_LATE -> "a page lies too late in the document for its images to be kept"
        else -> "the document's images were extracted with a warning"
    }
}
