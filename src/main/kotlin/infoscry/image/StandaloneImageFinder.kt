package infoscry.image

import infoscry.domain.SourceLocation
import infoscry.extract.ImageExtractor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * An imported picture is one image: the whole managed copy, under the picture's own unit.
 *
 * The picture has no geometry and no caption. A file too large to hold is reported as
 * [ImageRejectedException.IMAGE_TOO_LARGE] without being read.
 */
class StandaloneImageFinder : ImageFinder {

    override val mediaTypes: Set<String> = ImageFinders.PICTURE_MEDIA_TYPES

    override fun open(managedPath: Path): OpenedSource = object : OpenedSource {
        override fun imagesOf(parent: ParentUnit): ParentImages {
            if (parent.locator !is SourceLocation.Image) return ParentImages.NONE
            val size = try {
                Files.size(managedPath)
            } catch (unreadable: IOException) {
                return failed(ImageRejectedException.IMAGE_DECODE_FAILED)
            }
            if (size > ImageExtractor.MAX_IMAGE_BYTES) return failed(ImageRejectedException.IMAGE_TOO_LARGE)
            val bytes = try {
                Files.readAllBytes(managedPath)
            } catch (unreadable: IOException) {
                return failed(ImageRejectedException.IMAGE_DECODE_FAILED)
            }
            val candidate = ImageProbe.candidateOf(bytes, box = null)
                ?: return failed(ImageRejectedException.IMAGE_DECODE_FAILED)
            return ParentImages(
                images = listOf(FoundImage(parent, ImageSourceKind.STANDALONE, candidate, null, null)),
                failures = emptyList(),
                scanned = false,
            )
        }

        override fun close() = Unit

        private fun failed(code: String) = ParentImages(emptyList(), listOf(code), scanned = false)
    }
}
