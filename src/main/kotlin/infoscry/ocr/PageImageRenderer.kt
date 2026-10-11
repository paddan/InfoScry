package infoscry.ocr

import infoscry.domain.DocumentId
import infoscry.extract.PdfExtractor
import infoscry.extract.PdfPageRenderer
import infoscry.extract.PngPageRenderer
import infoscry.pdf.ImageToPdf
import infoscry.pdf.PdfPageGeometry
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * Renders one PDF page into a page image, inside the bounds one attempt may spend on it.
 *
 * Rendering is the step an OCR rescan is bought for, and it is also the step that can allocate an
 * unbounded amount of memory: a page's declared size is a number the file chooses, so a page that asks for
 * a raster past [maxRenderedPixels] is refused instead of allocated. The resolution is therefore fitted to
 * the bound rather than trusted from the settings — the reading records the resolution it was *rendered*
 * at, and a page that cannot be rendered at any legible resolution fails as that page rather than as a
 * document.
 *
 * What one page image carries is decided here: the artifact's own hash and dimensions are read back from
 * the file the renderer wrote (a record about what was actually produced, not about what was intended), the
 * page's declared rotation travels with it, and so does the version of this rendering. The drawing itself
 * is the injected [PdfPageRenderer], so that what a test observes about which pages were rendered, at what
 * resolution and in what order is decided by this class rather than by a PDFBox version.
 *
 * The artifact is written into the directory the caller names. Which directory that is, is the caller's
 * decision and it is a decision about evidence: a page read for review has to stay readable afterwards,
 * while a page rendered to fill missing text is working material for one attempt.
 */
class PageImageRenderer(
    private val draw: PdfPageRenderer = PngPageRenderer,
    private val maxRenderedPixels: Long = PdfExtractor.MAX_RENDERED_PIXELS,
) {

    init {
        require(maxRenderedPixels >= 1) {
            "a rendered-page bound must allow at least one pixel, was $maxRenderedPixels"
        }
    }

    /**
     * Renders [page] of [document] into [directory] and returns the page image it produced.
     *
     * @return the page image, or `null` when no legible resolution fits [maxRenderedPixels].
     * @throws java.io.IOException when the render produced nothing this process can record as an image.
     */
    fun render(
        document: PDDocument,
        page: Int,
        requestedDpi: Int?,
        directory: Path,
        artifactRoot: Path,
        documentId: DocumentId,
        unitId: String,
    ): PageImage? {
        require(page >= 1) { "a PDF page is numbered from one, was $page" }
        val media = document.getPage(page - 1)
        val dpi = resolutionFor(media, requestedDpi) ?: return null
        Files.createDirectories(directory)
        val written = draw.render(PDFRenderer(document), page, dpi, directory)
        require(written.startsWith(directory)) {
            "a renderer wrote $written outside the attempt directory ${directory}"
        }
        // Captured here and nowhere else, while [media] — the live PDF page object — is still open: once this
        // render returns, nothing downstream has the original page to measure again. A page whose geometry or
        // transform cannot be read or built this way (a header this build cannot parse, degenerate media/crop
        // boxes) simply gets none; see [PageImage.pdfGeometry].
        val (geometry, transform) = geometryAndTransform(media, written)

        return PageImage.ofFile(
            documentId = documentId,
            unitId = unitId,
            // The page's position is its stable identity, and the artifact name follows it.
            ordinal = page - 1,
            imageRoot = directory,
            imageReference = directory.relativize(written).toString(),
            artifactRoot = artifactRoot,
            renderDpi = dpi,
            rotationDegrees = rotationOf(media),
            pdfGeometry = geometry,
            pdfTransform = transform,
        )
    }

    /**
     * This page's [PdfPageGeometry] and the mapping from [renderedImage]'s own pixels into unrotated PDF user
     * space, or `null, null` when either cannot be built.
     *
     * The mapping is deliberately built from the *rendered raster's own measured dimensions*, never from the
     * DPI it was asked to render at: a renderer that could not honor the exact DPI, or rounding between
     * points and pixels, would otherwise make the transform disagree with the picture it is supposed to
     * describe. [SelectablePdfWriter] later re-derives this same geometry independently from the original and
     * validates a layout's transform against it with tolerance, which is why the formulas here mirror its
     * `geometryOf`/`fitTransform` exactly: the two are meant to agree, not merely to be each internally
     * consistent.
     */
    private fun geometryAndTransform(page: PDPage, renderedImage: Path): Pair<PdfPageGeometry?, ImageToPdf?> {
        val size = measuredSize(renderedImage) ?: return null to null
        val geometry = try {
            val media = page.mediaBox
            val crop = page.cropBox
            PdfPageGeometry(
                mediaX = media.lowerLeftX.toDouble(),
                mediaY = media.lowerLeftY.toDouble(),
                mediaWidth = media.width.toDouble(),
                mediaHeight = media.height.toDouble(),
                cropX = crop.lowerLeftX.toDouble(),
                cropY = crop.lowerLeftY.toDouble(),
                cropWidth = crop.width.toDouble(),
                cropHeight = crop.height.toDouble(),
                rotation = rotationOf(page),
                userUnit = page.userUnit.toDouble(),
            )
        } catch (invalid: IllegalArgumentException) {
            null
        } ?: return null to null
        val transform = try {
            fitTransform(geometry, size.first, size.second)
        } catch (invalid: IllegalArgumentException) {
            null
        } ?: return null to null
        return geometry to transform
    }

    /**
     * The mapping from top-left image pixels of a raster [imageWidth] by [imageHeight] into unrotated PDF
     * user space for a page rendered to cover [geometry]'s crop box: the same formula
     * `SelectablePdfWriter.fitTransform` uses to place a reading image back onto the page.
     */
    private fun fitTransform(geometry: PdfPageGeometry, imageWidth: Int, imageHeight: Int): ImageToPdf {
        val quarterTurn = geometry.rotation == 90 || geometry.rotation == 270
        val displayWidth = if (quarterTurn) geometry.cropHeight else geometry.cropWidth
        val displayHeight = if (quarterTurn) geometry.cropWidth else geometry.cropHeight
        val kx = displayWidth / imageWidth
        val ky = displayHeight / imageHeight
        val cx = geometry.cropX
        val cy = geometry.cropY
        val cw = geometry.cropWidth
        val ch = geometry.cropHeight
        return when (geometry.rotation) {
            0 -> ImageToPdf(kx, 0.0, 0.0, -ky, cx, cy + ch)
            90 -> ImageToPdf(0.0, kx, ky, 0.0, cx, cy)
            180 -> ImageToPdf(-kx, 0.0, 0.0, ky, cx + cw, cy)
            else -> ImageToPdf(0.0, -kx, -ky, 0.0, cx + cw, cy + ch)
        }
    }

    /** The pixel dimensions [path]'s own header declares, or `null` when this build cannot read them. */
    private fun measuredSize(path: Path): Pair<Int, Int>? = try {
        ImageIO.createImageInputStream(path.toFile())?.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) {
                null
            } else {
                val reader = readers.next()
                try {
                    reader.input = stream
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }
    } catch (unreadable: java.io.IOException) {
        null
    }

    /**
     * The resolution a page is rendered at, or `null` when no legible resolution fits the bound.
     *
     * The requested resolution is the first candidate and the legibility floor the last, so a page is
     * rendered as well as it can be within the bound instead of at a fixed compromise.
     */
    fun resolutionFor(page: PDPage, requestedDpi: Int?): Int? {
        val wanted = (requestedDpi ?: PdfExtractor.DEFAULT_RENDER_DPI)
            .coerceIn(PdfExtractor.MIN_RENDER_DPI, PdfExtractor.MAX_RENDER_DPI)
        val box = page.mediaBox
        for (dpi in wanted downTo PdfExtractor.MIN_RENDER_DPI) {
            if (pixels(box.width, box.height, dpi) <= maxRenderedPixels) return dpi
        }
        return null
    }

    private fun pixels(width: Float, height: Float, dpi: Int): Long {
        val columns = ceil(width.toDouble() * dpi / POINTS_PER_INCH)
        val rows = ceil(height.toDouble() * dpi / POINTS_PER_INCH)
        return (columns * rows).toLong()
    }

    /** The rotation a page declares, as a quarter-turn count. PDFBox applies it when it renders. */
    private fun rotationOf(page: PDPage): Int = ((page.rotation % FULL_TURN) + FULL_TURN) % FULL_TURN

    companion object {

        /** Where a page image read for review is kept, under the attempt's artifact directory. */
        const val PAGES_DIRECTORY: String = "pages"

        /** A quarter turn in degrees, and the cycle a page's declared rotation wraps around. */
        private const val FULL_TURN: Int = 360

        /** Points per inch: a PDF's own unit, and what a rendered resolution is relative to. */
        private const val POINTS_PER_INCH: Double = 72.0
    }
}
