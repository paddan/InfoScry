package infoscry.pdf

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.util.Matrix

/**
 * Private fixtures for the selectable-PDF writer tests (ticket 05).
 *
 * Coordinates of a page's content are in unrotated crop-box points with a top-left origin, relative to the crop
 * box. The writer under test never sees them: it receives reading images and layouts. The truth boxes these
 * fixtures state are computed from the page design here, with the fixture's own mapping from unrotated space to
 * the displayed page, so the writer's output is compared against something it did not compute.
 */
internal object WriterFixtures {

    /** Pixels per point of the scan raster that stands in for a scanned page. */
    const val SCAN_PX_PER_PT: Double = 4.0

    const val SWEDISH_TEXT: String = "Åsa betalade 120 kronor."

    fun geometry(
        media: Pair<Double, Double>,
        crop: CropRect,
        rotation: Int = 0,
        userUnit: Double = 1.0,
        mediaOrigin: Pair<Double, Double> = 0.0 to 0.0,
    ): PdfPageGeometry = PdfPageGeometry(
        mediaX = mediaOrigin.first,
        mediaY = mediaOrigin.second,
        mediaWidth = media.first,
        mediaHeight = media.second,
        cropX = crop.left,
        cropY = crop.top,
        cropWidth = crop.width,
        cropHeight = crop.height,
        rotation = rotation,
        userUnit = userUnit,
    )

    /** The displayed size of a page as a viewer shows it, in user units. */
    fun displaySize(geometry: PdfPageGeometry): Pair<Double, Double> =
        if (geometry.rotation == 90 || geometry.rotation == 270) {
            geometry.cropHeight to geometry.cropWidth
        } else {
            geometry.cropWidth to geometry.cropHeight
        }

    /**
     * The fixture's own mapping from a rectangle in unrotated crop coordinates (top-left, relative to the crop
     * box) to the displayed page, in points relative to the displayed top-left corner. This is the forward map a
     * viewer applies for `/Rotate`, written separately from the writer's inverse.
     */
    fun displayRect(geometry: PdfPageGeometry, rect: CropRect): CropRect {
        val cw = geometry.cropWidth
        val ch = geometry.cropHeight
        return when (geometry.rotation) {
            0 -> rect
            90 -> CropRect(ch - (rect.top + rect.height), rect.left, rect.height, rect.width)
            180 -> CropRect(cw - (rect.left + rect.width), ch - (rect.top + rect.height), rect.width, rect.height)
            270 -> CropRect(rect.top, cw - (rect.left + rect.width), rect.height, rect.width)
            else -> error("unsupported rotation ${geometry.rotation}")
        }
    }

    /**
     * The inverse of [displayRect]: a rectangle as a viewer shows it, in display points, back in unrotated crop
     * coordinates. A scan is drawn in unrotated space, so this places words that read horizontally on the display.
     */
    fun unrotatedRect(geometry: PdfPageGeometry, display: CropRect): CropRect {
        val cw = geometry.cropWidth
        val ch = geometry.cropHeight
        return when (geometry.rotation) {
            0 -> display
            90 -> CropRect(display.top, ch - (display.left + display.width), display.height, display.width)
            180 -> CropRect(cw - (display.left + display.width), ch - (display.top + display.height), display.width, display.height)
            270 -> CropRect(cw - (display.top + display.height), display.left, display.height, display.width)
            else -> error("unsupported rotation ${geometry.rotation}")
        }
    }

    /** The displayed rectangle scaled to image pixels for a raster of [widthPx] x [heightPx]. */
    fun pixelRect(geometry: PdfPageGeometry, rect: CropRect, widthPx: Int, heightPx: Int): CropRect {
        val (displayW, displayH) = displaySize(geometry)
        val display = displayRect(geometry, rect)
        val sx = widthPx / displayW
        val sy = heightPx / displayH
        return CropRect(display.left * sx, display.top * sy, display.width * sx, display.height * sy)
    }

    /**
     * The inverse of the forward map above: image pixels (top-left origin) to unrotated user space, the transform
     * a layout records. Scale is the raster's own pixel-per-point ratio, so it matches what a render produces.
     */
    fun readingTransform(geometry: PdfPageGeometry, widthPx: Int, heightPx: Int): ImageToPdf {
        val (displayW, displayH) = displaySize(geometry)
        val kx = displayW / widthPx
        val ky = displayH / heightPx
        val cx = geometry.cropX
        val cy = geometry.cropY
        val cw = geometry.cropWidth
        val ch = geometry.cropHeight
        return when (geometry.rotation) {
            0 -> ImageToPdf(kx, 0.0, 0.0, -ky, cx, cy + ch)
            90 -> ImageToPdf(0.0, kx, ky, 0.0, cx, cy)
            180 -> ImageToPdf(-kx, 0.0, 0.0, ky, cx + cw, cy)
            270 -> ImageToPdf(0.0, -kx, -ky, 0.0, cx + cw, cy + ch)
            else -> error("unsupported rotation ${geometry.rotation}")
        }
    }

    /** Writes a PDF whose pages are the given sources, in order. */
    fun writeSourcePdf(file: Path, pages: List<SourcePage>) {
        PDDocument().use { document ->
            val font = PDType0Font.load(document, ByteArrayInputStream(PdfFontResolver.bytes()), true)
            for (source in pages) {
                val g = source.geometry
                val page = PDPage(PDRectangle(g.mediaX.toFloat(), g.mediaY.toFloat(), g.mediaWidth.toFloat(), g.mediaHeight.toFloat()))
                page.cropBox = PDRectangle(g.cropX.toFloat(), g.cropY.toFloat(), g.cropWidth.toFloat(), g.cropHeight.toFloat())
                page.rotation = g.rotation
                if (g.userUnit != 1.0) page.cosObject.setFloat(COSName.USER_UNIT, g.userUnit.toFloat())
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    when (val content = source.content) {
                        is SourceContent.NativeText -> for (run in content.runs) {
                            writeRun(stream, font, run, g, RenderingMode.FILL)
                        }
                        is SourceContent.Scan -> {
                            val picture = LosslessFactory.createFromImage(document, scanRaster(g, content.inks))
                            stream.saveGraphicsState()
                            stream.transform(Matrix(g.cropWidth.toFloat(), 0f, 0f, g.cropHeight.toFloat(), g.cropX.toFloat(), g.cropY.toFloat()))
                            stream.drawImage(picture, 0f, 0f, 1f, 1f)
                            stream.restoreGraphicsState()
                            for (run in content.staleRuns) writeRun(stream, font, run, g, RenderingMode.NEITHER)
                        }
                    }
                }
            }
            document.save(file.toFile())
        }
    }

    private fun writeRun(stream: PDPageContentStream, font: PDType0Font, run: TextRun, g: PdfPageGeometry, mode: RenderingMode) {
        stream.beginText()
        stream.setFont(font, run.size.toFloat())
        stream.setRenderingMode(mode)
        stream.newLineAtOffset(
            (g.cropX + run.left).toFloat(),
            (g.cropY + g.cropHeight - run.baseline).toFloat(),
        )
        stream.showText(run.text)
        stream.endText()
    }

    /** A grey raster of the crop box with black ink where the page has words, at [SCAN_PX_PER_PT]. */
    fun scanRaster(g: PdfPageGeometry, inks: List<CropRect>): BufferedImage {
        val width = (g.cropWidth * SCAN_PX_PER_PT).roundToInt()
        val height = (g.cropHeight * SCAN_PX_PER_PT).roundToInt()
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.BLACK
            for (ink in inks) {
                graphics.fillRect(
                    (ink.left * SCAN_PX_PER_PT).roundToInt(),
                    (ink.top * SCAN_PX_PER_PT).roundToInt(),
                    (ink.width * SCAN_PX_PER_PT).roundToInt(),
                    (ink.height * SCAN_PX_PER_PT).roundToInt(),
                )
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** Renders one page with PDFBox, the same render contract the reading image uses. */
    fun render(file: Path, pageIndex: Int, dpi: Int): BufferedImage =
        Loader.loadPDF(file.toFile()).use { document ->
            PDFRenderer(document).renderImageWithDPI(pageIndex, dpi.toFloat(), ImageType.RGB)
        }

    /** Dark pixels inside [rect] (image pixels): proof that a rendered word sits where the fixture says it does. */
    fun inkPixels(image: BufferedImage, rect: CropRect): Int {
        val x0 = rect.left.toInt().coerceIn(0, image.width)
        val y0 = rect.top.toInt().coerceIn(0, image.height)
        val x1 = (rect.left + rect.width).toInt().coerceIn(0, image.width)
        val y1 = (rect.top + rect.height).toInt().coerceIn(0, image.height)
        var dark = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val luminance = image.getRGB(x, y) and 0xFF
            if (luminance < 128) dark++
        }
        return dark
    }

    fun writePng(image: BufferedImage, file: Path): Path {
        check(ImageIO.write(image, "png", file.toFile())) { "no PNG writer" }
        return file
    }

    /**
     * Builds a layout for one page: the canonical text, its spans as rectangles in unrotated crop coordinates, and
     * the reading image they were measured on. The layout's transform is the fixture's render mapping.
     */
    fun layoutFor(
        g: PdfPageGeometry,
        canonical: String,
        spans: List<PlainSpan>,
        image: Path,
        widthPx: Int,
        heightPx: Int,
    ): PageTextLayout {
        val positioned = spans.mapIndexed { order, span ->
            PositionedTextSpan(
                start = span.start,
                endExclusive = span.endExclusive,
                rectangle = pixelRect(g, span.rect, widthPx, heightPx).toTextRectangle(),
                granularity = span.granularity,
                order = order,
            )
        }
        return PageTextLayout(
            version = 1,
            textSha256 = PdfModelRules.sha256Of(canonical),
            imageSha256 = sha256(image),
            width = widthPx,
            height = heightPx,
            geometry = g,
            transform = readingTransform(g, widthPx, heightPx),
            spans = positioned,
            complete = true,
            reasonCode = null,
            positioningVersion = "fixture-positioning-1",
        )
    }

    /** Spans for the given words of [canonical], in order; each word must occur in the reading after the previous one. */
    fun wordSpans(canonical: String, words: List<Pair<String, CropRect>>): List<PlainSpan> {
        var cursor = 0
        return words.map { (word, rect) ->
            val start = canonical.indexOf(word, cursor)
            require(start >= 0) { "word '$word' is not in the reading after offset $cursor" }
            cursor = start + word.length
            PlainSpan(start, cursor, rect, SpanGranularity.WORD)
        }
    }

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Glyphs in content-stream order as PDFBox reports them, with their boxes in display points. */
    fun glyphs(file: Path, pageIndex: Int): List<WriterExtractedGlyph> =
        Loader.loadPDF(file.toFile()).use { document ->
            val collector = GlyphCollector()
            collector.sortByPosition = false
            collector.startPage = pageIndex + 1
            collector.endPage = pageIndex + 1
            collector.getText(document)
            collector.glyphs
        }

    /** The text PDFBox extracts from one page, in content-stream order. */
    fun text(file: Path, pageIndex: Int): String =
        Loader.loadPDF(file.toFile()).use { document ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = false
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(document)
        }

    private class GlyphCollector : PDFTextStripper() {
        val glyphs = mutableListOf<WriterExtractedGlyph>()

        override fun processTextPosition(text: TextPosition) {
            glyphs += WriterExtractedGlyph(
                char = text.unicode,
                left = text.xDirAdj.toDouble(),
                baseline = text.yDirAdj.toDouble(),
                width = text.widthDirAdj.toDouble(),
                height = text.heightDir.toDouble(),
            )
        }
    }
}

/** A rectangle with a top-left origin, in points or pixels depending on where it is used. */
internal data class CropRect(val left: Double, val top: Double, val width: Double, val height: Double) {
    fun toTextRectangle(): TextRectangle = TextRectangle(left, top, width, height)
}

/** One run of visible or invisible text in unrotated crop coordinates: [left] and [baseline] from the crop's top-left. */
internal data class TextRun(val text: String, val left: Double, val baseline: Double, val size: Double)

internal sealed interface SourceContent {
    /** Native text drawn with the fixture font, with no picture. */
    data class NativeText(val runs: List<TextRun>) : SourceContent

    /** A scanned page: a black-on-white picture of [inks], plus optional invisible stale text drawn over it. */
    data class Scan(val inks: List<CropRect>, val staleRuns: List<TextRun> = emptyList()) : SourceContent
}

internal data class SourcePage(val geometry: PdfPageGeometry, val content: SourceContent)

/** A span to place: UTF-16 offsets into the reading, and its rectangle in unrotated crop coordinates. */
internal data class PlainSpan(
    val start: Int,
    val endExclusive: Int,
    val rect: CropRect,
    val granularity: SpanGranularity,
)

/** A glyph as extracted, with its left edge, baseline and box in display points. */
internal data class WriterExtractedGlyph(val char: String, val left: Double, val baseline: Double, val width: Double, val height: Double) {
    val right: Double get() = left + width
    val top: Double get() = baseline - height
}
