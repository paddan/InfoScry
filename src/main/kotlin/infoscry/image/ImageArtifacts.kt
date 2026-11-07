package infoscry.image

import infoscry.extract.ImageExtractor
import infoscry.extract.PdfExtractor
import infoscry.storage.ContentAddressedFile
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The pixels of one image, as they were read from its source. */
sealed interface ImageBytes {

    /** An image as its source encoded it. Only the media types a reader can show are accepted. */
    data class Encoded(val bytes: ByteArray, val mediaType: String) : ImageBytes {
        init {
            require(mediaType in ENCODED_EXTENSIONS) {
                "an encoded image must be image/jpeg, image/png or image/gif, was $mediaType"
            }
        }
    }

    /** Pixels already decoded in memory, such as a cropped page region. */
    data class Raster(val image: BufferedImage) : ImageBytes
}

/** An image written under an artifact root. Paths are relative to that root. */
data class WrittenImage(
    val sha256: String,
    val relativePath: String,
    val thumbnailRelativePath: String,
    val mediaType: String,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * An image the extractor could not write, carrying a stable [code] that a warning can report.
 *
 * The message is the code alone: it never carries image bytes or document text.
 */
class ImageRejectedException(val code: String) : RuntimeException(code) {
    companion object {
        /** The encoded image is larger than the byte bound. */
        const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"

        /** The bytes are not an image a reader can decode. */
        const val IMAGE_DECODE_FAILED = "IMAGE_DECODE_FAILED"
    }
}

/**
 * Writes extracted images under a document's artifact root, named by their SHA-256.
 *
 * An image is stored as its original bytes when its source encoded it in a format a reader can show and it
 * is within the bounds; otherwise it is stored as PNG. Its thumbnail is a JPEG whose longest side is at most
 * [THUMBNAIL_SIDE_PX]. Every file is written through a temporary sibling and moved into place, so an
 * existing file with the right hash is reused and a reader never sees a half-written file.
 *
 * @param maxImageBytes the largest encoded image accepted; larger ones are refused.
 * @param maxRenderedPixels the most pixels a decoded image may hold; a larger one is reduced, not refused.
 */
class ImageArtifacts(
    private val maxImageBytes: Long = ImageExtractor.MAX_IMAGE_BYTES,
    private val maxRenderedPixels: Long = PdfExtractor.MAX_RENDERED_PIXELS,
) {
    init {
        require(maxImageBytes >= 1) { "an image byte bound must allow at least one byte, was $maxImageBytes" }
        require(maxRenderedPixels >= 1) {
            "an image pixel bound must allow at least one pixel, was $maxRenderedPixels"
        }
    }

    /**
     * Writes [bytes] and its thumbnail under [documentArtifactRoot].
     *
     * @throws ImageRejectedException with [ImageRejectedException.IMAGE_TOO_LARGE] or
     *   [ImageRejectedException.IMAGE_DECODE_FAILED] when the image cannot be written.
     */
    fun write(documentArtifactRoot: Path, bytes: ImageBytes): WrittenImage {
        val prepared = when (bytes) {
            is ImageBytes.Encoded -> prepareEncoded(bytes)
            is ImageBytes.Raster -> prepareRaster(bytes.image)
        }
        val sha256 = ContentAddressedFile.sha256Of(prepared.data)
        val relativePath = "$IMAGE_DIRECTORY/$sha256.${prepared.extension}"
        ContentAddressedFile.writeOnce(documentArtifactRoot.resolve(relativePath), prepared.data, sha256)
        writeThumbnail(documentArtifactRoot, sha256, prepared.raster)
        return WrittenImage(
            sha256 = sha256,
            relativePath = relativePath,
            thumbnailRelativePath = thumbnailPath(sha256),
            mediaType = prepared.mediaType,
            widthPx = prepared.raster.width,
            heightPx = prepared.raster.height,
        )
    }

    /**
     * Writes the thumbnail of the image file [source] under [documentArtifactRoot], named by [sha256], and
     * returns its path relative to that root. An existing thumbnail is reused.
     *
     * The source is decoded through the same pixel bound as [write], so a large managed copy is reduced
     * rather than read whole.
     *
     * @throws ImageRejectedException with [ImageRejectedException.IMAGE_DECODE_FAILED] when [source] cannot be decoded.
     */
    fun thumbnailFor(documentArtifactRoot: Path, source: Path, sha256: String): String {
        val raster = decodeBounded(ImageIO.createImageInputStream(source.toFile())).raster
        writeThumbnail(documentArtifactRoot, sha256, raster)
        return thumbnailPath(sha256)
    }

    private fun prepareEncoded(encoded: ImageBytes.Encoded): Prepared {
        if (encoded.bytes.size.toLong() > maxImageBytes) {
            throw ImageRejectedException(ImageRejectedException.IMAGE_TOO_LARGE)
        }
        val decoded = decodeBounded(ImageIO.createImageInputStream(ByteArrayInputStream(encoded.bytes)))
        // The bytes decide the format, not the declared media type: a mislabelled file keeps its real format.
        val detectedMediaType = ENCODED_MEDIA_TYPES_BY_FORMAT[decoded.formatName.lowercase()]
        return if (decoded.reduced || detectedMediaType == null) {
            Prepared(pngBytes(decoded.raster), "png", "image/png", decoded.raster)
        } else {
            Prepared(
                encoded.bytes,
                ENCODED_EXTENSIONS.getValue(detectedMediaType),
                detectedMediaType,
                decoded.raster,
            )
        }
    }

    private fun prepareRaster(image: BufferedImage): Prepared {
        val raster = if (image.width.toLong() * image.height > maxRenderedPixels) reduce(image) else image
        return Prepared(pngBytes(raster), "png", "image/png", raster)
    }

    /**
     * Decodes the first frame of the image in [input], subsampled by the smallest whole step that keeps it within
     * [maxRenderedPixels]. The header is read first, so no raster is allocated for an image that is too large
     * to decode whole, and the source stream is closed.
     *
     * @throws ImageRejectedException with [ImageRejectedException.IMAGE_DECODE_FAILED] when no reader takes the input.
     */
    private fun decodeBounded(input: ImageInputStream?): Decoded {
        if (input == null) throw ImageRejectedException(ImageRejectedException.IMAGE_DECODE_FAILED)
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) throw ImageRejectedException(ImageRejectedException.IMAGE_DECODE_FAILED)
            val reader = readers.next()
            val step: Int
            val raster: BufferedImage
            val formatName: String
            try {
                reader.input = stream
                formatName = reader.formatName
                step = subsampleStep(reader.getWidth(0), reader.getHeight(0))
                val parameter = reader.defaultReadParam
                if (step > 1) parameter.setSourceSubsampling(step, step, 0, 0)
                raster = reader.read(0, parameter)
            } catch (unreadable: IOException) {
                throw ImageRejectedException(ImageRejectedException.IMAGE_DECODE_FAILED)
            } catch (unreadable: RuntimeException) {
                // A decoder given untrusted bytes can fail with any runtime exception; none of them may escape.
                throw ImageRejectedException(ImageRejectedException.IMAGE_DECODE_FAILED)
            } finally {
                reader.dispose()
            }
            if (raster.width.toLong() * raster.height > maxRenderedPixels) {
                raster.flush()
                throw ImageRejectedException(ImageRejectedException.IMAGE_DECODE_FAILED)
            }
            return Decoded(raster, reduced = step > 1, formatName = formatName)
        }
    }

    /** The smallest whole subsampling step whose result holds at most [maxRenderedPixels] pixels. */
    private fun subsampleStep(width: Int, height: Int): Int {
        var step = 1
        while (subsampledPixels(width, height, step) > maxRenderedPixels) step++
        return step
    }

    /** What a reader produces for a frame subsampled by [step]: `ceil(columns / step)` per axis, in `Long` arithmetic. */
    private fun subsampledPixels(width: Int, height: Int, step: Int): Long {
        val columns = (width.toLong() + step - 1) / step
        val rows = (height.toLong() + step - 1) / step
        return columns * rows
    }

    /** A copy of [image] scaled down, preserving its aspect ratio, to at most [maxRenderedPixels] pixels. */
    private fun reduce(image: BufferedImage): BufferedImage {
        val factor = sqrt(maxRenderedPixels.toDouble() / (image.width.toLong() * image.height).toDouble())
        var width = max(1, (image.width * factor).toInt())
        var height = max(1, (image.height * factor).toInt())
        while (width.toLong() * height > maxRenderedPixels) {
            width = max(1, width - 1)
            height = max(1, height - 1)
        }
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(image, 0, 0, width, height, null)
        graphics.dispose()
        return scaled
    }

    private fun pngBytes(image: BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output)) { "no PNG writer is available" }
        return output.toByteArray()
    }

    private fun writeThumbnail(documentArtifactRoot: Path, sha256: String, source: BufferedImage) {
        val target = documentArtifactRoot.resolve(thumbnailPath(sha256))
        if (Files.isRegularFile(target)) return
        writeAtomically(target, thumbnailBytes(source))
    }

    /** The thumbnail as a JPEG no larger than [THUMBNAIL_SIDE_PX] on its longest side, flattened onto white. */
    private fun thumbnailBytes(source: BufferedImage): ByteArray {
        val scale = min(1.0, THUMBNAIL_SIDE_PX.toDouble() / max(source.width, source.height))
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val thumbnail = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = thumbnail.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        check(ImageIO.write(thumbnail, "jpg", output)) { "no JPEG writer is available" }
        return output.toByteArray()
    }

    /** Writes [bytes] to [target] through a temporary sibling and an atomic move. */
    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(checkNotNull(target.parent))
        val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun thumbnailPath(sha256: String): String = "$THUMBNAIL_DIRECTORY/$sha256.jpg"

    /** The file a written image is stored as: its bytes, extension, media type and the raster they show. */
    private class Prepared(val data: ByteArray, val extension: String, val mediaType: String, val raster: BufferedImage)

    /** The first frame of an image: its pixels, whether they were reduced, and the format its reader detected. */
    private class Decoded(val raster: BufferedImage, val reduced: Boolean, val formatName: String)

    companion object {
        /** The longest side of a thumbnail, in pixels. */
        const val THUMBNAIL_SIDE_PX: Int = 320

        /** The directory, under an artifact root, that holds the images. */
        const val IMAGE_DIRECTORY: String = "images"

        /** The directory, under an artifact root, that holds the thumbnails. */
        const val THUMBNAIL_DIRECTORY: String = "images/thumbs"
    }
}

/** The encoded media types an image may keep its original bytes for, with the file extension each uses. */
private val ENCODED_EXTENSIONS: Map<String, String> = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/gif" to "gif",
)

/** The media type of each format a reader names, lowercased, that may keep its original bytes. */
private val ENCODED_MEDIA_TYPES_BY_FORMAT: Map<String, String> = mapOf(
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
)
