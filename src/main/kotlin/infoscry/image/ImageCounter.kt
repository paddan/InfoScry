package infoscry.image

import infoscry.domain.ContentUnitId
import infoscry.domain.SourceLocation
import infoscry.extract.PdfExtractor
import java.io.IOException
import java.nio.file.Path

/**
 * How many pictures a source file would yield, as far as that can be known before extraction.
 *
 * [known] is the number that can be stated (the pictures embedded in a PDF, one for a standalone picture);
 * [upperBound] is the largest number a run may send, which is larger than [known] when scanned pages can add
 * regions whose count no preview can know. [atLeast] says whether the dialog has to present [known] as a
 * lower bound rather than the count.
 */
data class ImageCount(val known: Int, val upperBound: Int, val atLeast: Boolean = false)

/**
 * Counts a source file's pictures without extracting anything.
 *
 * It is deliberately narrow: a standalone picture is one picture, and a PDF's embedded pictures are read from
 * its page resources. Office files and e-books are not counted, because their pictures are attached to units
 * only the extractor can name (a section, a slide, a sheet, a spine item); a run that cannot state how many
 * pictures it would send to an external profile is refused rather than given a guessed allowance.
 */
class ImageCounter(private val finders: ImageFinders = ImageFinders.standard()) {

    fun count(path: Path, mediaType: String, pages: Int?, withRegions: Boolean): ImageCount? {
        if (mediaType in ImageFinders.PICTURE_MEDIA_TYPES) return ImageCount(known = 1, upperBound = 1)
        if (mediaType != PdfExtractor.PDF_MEDIA_TYPE || pages == null || pages <= 0) return null
        val finder = finders.forMediaType(mediaType) ?: return null
        return try {
            finder.open(path).use { source ->
                var embedded = 0
                var scannedPages = 0
                for (page in 0 until pages) {
                    val parent = ParentUnit(
                        unitId = ContentUnitId("image-count-$page"),
                        ordinal = page,
                        locator = SourceLocation.PdfPage(page + 1),
                    )
                    val found = source.imagesOf(parent, withPageText = false)
                    embedded += found.images.count { it.kind == ImageSourceKind.EMBEDDED }
                    if (found.scanned) scannedPages++
                }
                ImageCount(
                    known = embedded,
                    // A scanned page can carry at least the scan itself and a figure or two; the confirmed
                    // allowance is one figure per scanned page, which is the count a region pass normally finds.
                    upperBound = if (withRegions) embedded + scannedPages else embedded,
                    atLeast = withRegions && scannedPages > 0,
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
