package infoscry.image

import infoscry.domain.NormalizedBox
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * One image found in a document, before it is written.
 *
 * @param widthPx the declared width of the image, in pixels.
 * @param heightPx the declared height of the image, in pixels.
 * @param box the image's rectangle on its parent page as fractions `0..1`, or null when the format has no geometry.
 * @param isMask whether the image is a soft mask or a stencil mask, which is never shown on its own.
 */
data class ImageCandidate(
    val bytes: ImageBytes,
    val widthPx: Int,
    val heightPx: Int,
    val box: NormalizedBox?,
    val isMask: Boolean,
)

/** Why an image is skipped and left out of the document's images. */
enum class SkipReason {
    /** The shortest side, or the area on the page, is below the minimum. */
    TOO_SMALL,

    /** The same image appears on more pages than the repetition bound allows. Decided by the extraction stage. */
    REPEATED,

    /** A soft or stencil mask. */
    MASK,

    /** An image of one uniform colour. */
    UNIFORM,

    /** The document already holds the most images it may. Decided by the extraction stage. */
    LIMIT,
}

/**
 * The fixed rules that decide which images are kept, applied to each candidate before it is written.
 *
 * Repetition across pages and the per-document limit depend on the whole document, so the extraction stage
 * applies them; this object owns only the rules a single candidate can be judged by.
 */
object ImageFilter {

    /** The shortest side an image may have and still be kept, in pixels. */
    const val MIN_SIDE_PX: Int = 96

    /** The smallest share of the parent page an image may cover and still be kept, as a fraction. */
    const val MIN_PAGE_AREA: Double = 0.01

    /** The most pages one image may repeat on; an image on more pages is a logo, rule or ornament. */
    const val MAX_REPEAT_PAGES: Int = 3

    /** The most images one document may hold. */
    const val MAX_PER_DOCUMENT: Int = 500

    /** The share of a page one image must cover for the page to count as a scan, as a fraction. */
    const val SCANNED_PAGE_COVERAGE: Double = 0.90

    /** The grid of pixels sampled to decide whether an image is uniform. */
    private const val SAMPLE_GRID: Int = 64

    /** The largest difference, per channel, between two sampled pixels that still counts as the same colour. */
    private const val UNIFORM_TOLERANCE: Int = 2

    /**
     * The reason [candidate] is skipped, or null when it is kept.
     *
     * [decoded] is the candidate's pixels when the caller has decoded them; without it the uniform-colour rule
     * is not applied. The size rules use the declared size and box, so they need no decoding.
     */
    fun skipReason(candidate: ImageCandidate, decoded: BufferedImage?): SkipReason? = when {
        minOf(candidate.widthPx, candidate.heightPx) < MIN_SIDE_PX -> SkipReason.TOO_SMALL
        candidate.box != null && area(candidate.box) < MIN_PAGE_AREA -> SkipReason.TOO_SMALL
        candidate.isMask -> SkipReason.MASK
        decoded != null && isUniform(decoded) -> SkipReason.UNIFORM
        else -> null
    }

    /** Whether one image covers so much of its page that the page is a scan. */
    fun isScanned(box: NormalizedBox?): Boolean = box != null && area(box) >= SCANNED_PAGE_COVERAGE

    private fun area(box: NormalizedBox): Double = (box.right - box.left) * (box.bottom - box.top)

    /**
     * Whether every sampled pixel matches the first one within [UNIFORM_TOLERANCE] in every channel.
     *
     * A fixed grid is sampled rather than every pixel, so the cost is bounded for any size of image. A picture
     * that varies only between samples is not caught, which is acceptable for filtering blank pictures.
     */
    private fun isUniform(image: BufferedImage): Boolean {
        val reference = image.getRGB(0, 0)
        for (row in 0 until SAMPLE_GRID) {
            val y = sampleCoordinate(row, image.height)
            for (column in 0 until SAMPLE_GRID) {
                val x = sampleCoordinate(column, image.width)
                if (!sameColour(reference, image.getRGB(x, y))) return false
            }
        }
        return true
    }

    /** The pixel coordinate of grid position [index] along an axis of [length] pixels, spanning the whole axis. */
    private fun sampleCoordinate(index: Int, length: Int): Int =
        ((index.toLong() * (length - 1)) / (SAMPLE_GRID - 1)).toInt()

    private fun sameColour(first: Int, second: Int): Boolean =
        (0 until 4).all { channel ->
            val shift = channel * 8
            abs(((first ushr shift) and 0xFF) - ((second ushr shift) and 0xFF)) <= UNIFORM_TOLERANCE
        }
}
