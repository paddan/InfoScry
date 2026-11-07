package infoscry.image

import infoscry.domain.ContentUnitId
import infoscry.domain.SourceLocation
import infoscry.extract.ImageExtractor
import infoscry.storage.CaptionSource
import java.nio.file.Path

/** A unit a text extractor emitted, which images are found under. */
data class ParentUnit(val unitId: ContentUnitId, val ordinal: Int, val locator: SourceLocation)

/**
 * One image found under a parent unit, before it is filtered or written.
 *
 * [caption] and [captionSource] are both set or both null.
 */
data class FoundImage(
    val parent: ParentUnit,
    val kind: ImageSourceKind,
    val candidate: ImageCandidate,
    val caption: String?,
    val captionSource: CaptionSource?,
) {
    init {
        require((caption == null) == (captionSource == null)) {
            "an image caption and its source are set together"
        }
    }
}

/**
 * Everything one parent unit holds.
 *
 * @param failures codes of images that could not be read, one per image; the parent's other images are still in [images].
 * @param scanned whether the parent has an image covering the page, which is the scan itself and is not returned (PDF only).
 * @param vectorSkipped how many vector drawings (EMF, WMF, SVG, PICT) the parent holds; they are out of scope and are
 *   counted as skipped, never reported as failures.
 */
data class ParentImages(
    val images: List<FoundImage>,
    val failures: List<String>,
    val scanned: Boolean,
    val vectorSkipped: Int = 0,
) {
    companion object {
        /** A parent without images. */
        val NONE: ParentImages = ParentImages(emptyList(), emptyList(), scanned = false)
    }
}

/** Finds the images of one document format. */
interface ImageFinder {

    /** The media types this finder opens. */
    val mediaTypes: Set<String>

    /** Opens [managedPath] once for a whole stage run. */
    fun open(managedPath: Path): OpenedSource
}

/** One open document. Close it when the stage run ends. */
interface OpenedSource : AutoCloseable {

    /**
     * The images of [parent], in reading order. A parent whose locator this finder does not own, or that the
     * document does not hold, returns [ParentImages.NONE]. One image that cannot be read is reported in
     * [ParentImages.failures] and never stops the others.
     */
    fun imagesOf(parent: ParentUnit): ParentImages
}

/** The codes an image finder reports besides [ImageRejectedException.IMAGE_DECODE_FAILED]. */
object ImageFindFailure {

    /** The parent holds more images than [ImageIdentity.MAX_PER_PARENT]; the rest are not read. */
    const val TOO_MANY_IMAGES = "TOO_MANY_IMAGES"

    /** The parent's content could not be walked to the end; the images found before are returned. */
    const val PARENT_UNREADABLE = "IMAGE_PARENT_UNREADABLE"
}

/** The finders, by the media type of the document they open. */
class ImageFinders(finders: List<ImageFinder>) {

    private val byMediaType: Map<String, ImageFinder> =
        finders.flatMap { finder -> finder.mediaTypes.map { type -> type to finder } }.toMap()

    /** The finder for [mediaType], or null when the format has no images to find. */
    fun forMediaType(mediaType: String): ImageFinder? = byMediaType[mediaType]

    companion object {

        /** PDF, OOXML Office, EPUB and standalone pictures. Legacy Office formats and other e-book formats have none. */
        fun standard(): ImageFinders = ImageFinders(
            listOf(PdfImageFinder(), OfficeImageFinder(), EpubImageFinder(), StandaloneImageFinder()),
        )

        /** The media types of standalone pictures, as [ImageExtractor] reads them. */
        internal val PICTURE_MEDIA_TYPES: Set<String> = setOf(
            ImageExtractor.PNG_MEDIA_TYPE,
            ImageExtractor.JPEG_MEDIA_TYPE,
            ImageExtractor.TIFF_MEDIA_TYPE,
        )
    }
}
