package infoscry.image

import infoscry.domain.NormalizedBox
import infoscry.extract.PdfExtractor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/** What an image header says: the media type when the format may keep its bytes, and the declared size. */
internal class Probed(val encodedMediaType: String?, val widthPx: Int, val heightPx: Int)

/** Reads image headers and turns encoded bytes of any format ImageIO knows into a candidate. */
internal object ImageProbe {

    private val ENCODED_BY_FORMAT = mapOf(
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
    )

    /** The header of [bytes], or null when no reader takes it. Only the header is read. */
    fun probe(bytes: ByteArray): Probed? = try {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width < 1 || height < 1) {
                    null
                } else {
                    Probed(ENCODED_BY_FORMAT[reader.formatName.lowercase()], width, height)
                }
            } finally {
                reader.dispose()
            }
        }
    } catch (unreadable: IOException) {
        null
    } catch (unreadable: RuntimeException) {
        // A reader given untrusted bytes can fail with any runtime exception.
        null
    }

    /**
     * A candidate for [bytes], or null when they are not an image ImageIO can read.
     *
     * JPEG, PNG and GIF keep their bytes; any other format is decoded, reduced to the rendering bound when it
     * is larger, and carried as pixels. The declared size is the header's, not the reduced one.
     */
    fun candidateOf(bytes: ByteArray, box: NormalizedBox?): ImageCandidate? {
        val probed = probe(bytes) ?: return null
        val encoded = probed.encodedMediaType
        val imageBytes = if (encoded != null) {
            ImageBytes.Encoded(bytes, encoded)
        } else {
            ImageBytes.Raster(decodeReduced(bytes, probed) ?: return null)
        }
        return ImageCandidate(imageBytes, probed.widthPx, probed.heightPx, box, isMask = false)
    }

    private fun decodeReduced(bytes: ByteArray, probed: Probed): BufferedImage? = try {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
            val reader = ImageIO.getImageReaders(stream).next()
            try {
                reader.input = stream
                val parameter = reader.defaultReadParam
                val step = subsampleStep(probed.widthPx, probed.heightPx)
                if (step > 1) parameter.setSourceSubsampling(step, step, 0, 0)
                reader.read(0, parameter)
            } finally {
                reader.dispose()
            }
        }
    } catch (unreadable: IOException) {
        null
    } catch (unreadable: RuntimeException) {
        null
    }

    /** The smallest whole subsampling step that keeps a [width] by [height] frame within the rendering bound. */
    fun subsampleStep(width: Int, height: Int): Int {
        var step = 1
        while (((width + step - 1L) / step) * ((height + step - 1L) / step) > PdfExtractor.MAX_RENDERED_PIXELS) step++
        return step
    }

    /**
     * The rectangle as fractions clamped to the page, or null when nothing of it lies on the page
     * (a degenerate or off-page rectangle cannot be a [NormalizedBox]).
     */
    fun boxOrNull(left: Double, top: Double, right: Double, bottom: Double): NormalizedBox? {
        if (listOf(left, top, right, bottom).any { !it.isFinite() }) return null
        val l = left.coerceIn(0.0, 1.0)
        val t = top.coerceIn(0.0, 1.0)
        val r = right.coerceIn(0.0, 1.0)
        val b = bottom.coerceIn(0.0, 1.0)
        return if (r > l && b > t) NormalizedBox(l, t, r, b) else null
    }
}
