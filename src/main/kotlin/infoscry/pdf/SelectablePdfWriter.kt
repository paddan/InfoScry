package infoscry.pdf

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.util.Matrix

/**
 * A file-level failure with a safe reason code. It never carries source text, provider output or raw paths.
 * Thrown only when no valid file for the whole request can be produced; nothing is published when it is thrown.
 */
class SelectablePdfWriteException(val reasonCode: String, message: String) : IllegalStateException(message) {
    init {
        require(PdfModelRules.isSafeReasonCode(reasonCode)) { "a write failure carries a safe reason code" }
    }
}

/**
 * Writes the derived, selectable-text PDF copy of one published revision (ticket 05).
 *
 * Every physical page of the original appears once, in order, with its original geometry:
 * - NATIVE pages keep their original page dictionary, content and text untouched.
 * - OCR pages keep their page dictionary (so bookmarks and destinations still resolve), lose their old content,
 *   resources and annotations, and receive exactly one new layer: the exact reading image, then invisible text
 *   (rendering mode 3) with one run per span. Stale text cannot survive, because the old content is unreferenced.
 * - BLANK pages are drawn from their reading image; they carry no text. A BLANK claim is kept only when the picture
 *   is verified blank, otherwise the page is UNAVAILABLE with reason BLANK_NOT_VERIFIED.
 * - UNAVAILABLE pages are drawn from their reading image with no text. Without a reading image the file fails.
 *
 * A page whose text cannot be drawn with the pinned font, or whose layout does not fit its picture, is reported as
 * UNAVAILABLE with an explicit reason. Its picture stays visible and no glyph is substituted or omitted.
 *
 * The writer reads the original only and writes to a temporary file beside the requested output. The temporary file
 * is reopened and validated before it is moved to the output, so a failed or interrupted write leaves no file at
 * that path. Pages are processed one at a time, and the original and the output use file-backed PDFBox storage.
 */
object SelectablePdfWriter {

    /** The renderer identity recorded in every manifest. Bump it when the drawing contract changes. */
    const val RENDERER_VERSION: String = "selectable-pdf-writer-1"

    /** The fixed inset around an OCR span's box that the written glyph run must stay within (plan, 2026-10-10). */
    const val CONTENT_INSET_POINTS: Double = 1.5

    /** A picture is verified blank when at most this fraction of its pixels is ink (luminance below the threshold). */
    private const val BLANK_INK_LUMINANCE: Int = 200
    private const val BLANK_MAX_INK_FRACTION: Double = 0.00001

    /** A layout's transform must agree with the page's render mapping to this relative tolerance. */
    private const val TRANSFORM_RELATIVE_TOLERANCE: Double = 0.01
    private const val TRANSFORM_ABSOLUTE_TOLERANCE: Double = 1e-6
    private const val TRANSFORM_OFFSET_TOLERANCE_POINTS: Double = 1.0

    /** The geometry a page reports after writing must equal the request's to this tolerance. */
    private const val GEOMETRY_TOLERANCE: Double = 1e-3

    /** The width of the zero-width separator drawn between two spans that touch on one line (copy spacing only). */
    private const val SEPARATOR_WIDTH_POINTS: Double = 0.1

    fun write(request: PdfWriteRequest): PdfWriteResult {
        val output = request.output.toAbsolutePath().normalize()
        val directory = checkNotNull(output.parent) { "an output path has a parent directory" }
        Files.createDirectories(directory)
        val temporary = directory.resolve(".${output.fileName}.${UUID.randomUUID()}.tmp")
        var published = false
        try {
            val results = writeTemporary(request, temporary, directory)
            validateReopened(temporary, request, results, directory)
            val sha256 = sha256(temporary)
            val bytes = Files.size(temporary)
            publish(temporary, output)
            published = true
            return PdfWriteResult(
                manifest = SelectablePdfManifest(request.revisionId, RENDERER_VERSION, PdfFontResolver.sha256(), results),
                sha256 = sha256,
                bytes = bytes,
            )
        } catch (error: IOException) {
            // PDFBox and file messages can carry paths or content, so only a safe code leaves this boundary.
            throw SelectablePdfWriteException("WRITE_IO_FAILED", "the selectable PDF copy could not be written from the original")
        } finally {
            if (!published) Files.deleteIfExists(temporary)
        }
    }

    // ---- Building the temporary file ------------------------------------------------------------------------------

    private fun writeTemporary(request: PdfWriteRequest, target: Path, scratchDirectory: Path): List<PdfPageResult> {
        val results = ArrayList<PdfPageResult>(request.pages.size)
        Loader.loadPDF(request.original.toFile(), fileBackedStorage(scratchDirectory)).use { document ->
            if (document.numberOfPages != request.pages.size) {
                throw SelectablePdfWriteException(
                    "PAGE_COUNT_MISMATCH",
                    "the original has ${document.numberOfPages} physical pages and the request lists ${request.pages.size}",
                )
            }
            val font = PDType0Font.load(document, ByteArrayInputStream(PdfFontResolver.bytes()), true)
            for (input in request.pages) {
                val page = document.getPage(input.ordinal)
                requireGeometry(page, input)
                results += when (input.mode) {
                    PdfPageMode.NATIVE -> PdfPageResult(input.ordinal, PdfPageCoverage.NATIVE, null)
                    PdfPageMode.OCR, PdfPageMode.BLANK, PdfPageMode.UNAVAILABLE -> rebuild(document, font, page, input)
                }
            }
            document.save(target.toFile())
        }
        return results
    }

    private fun fileBackedStorage(directory: Path) =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(directory.toFile()).streamCache

    /** Replaces one page's content with a single new layer and reports the coverage it ends with. */
    private fun rebuild(document: PDDocument, font: PDType0Font, page: PDPage, input: PdfPageInput): PdfPageResult {
        val plan = plan(input)
        val picture = plan.picture
        removeOldContent(page)
        val image = LosslessFactory.createFromImage(document, picture.image)
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.saveGraphicsState()
            stream.transform(imageMatrix(plan.placement, picture.image))
            stream.drawImage(image, 0f, 0f, 1f, 1f)
            stream.restoreGraphicsState()
            if (plan.coverage == PdfPageCoverage.OCR) writeInvisibleLayer(stream, font, input.canonicalText!!, plan)
        }
        return PdfPageResult(input.ordinal, plan.coverage, plan.reasonCode)
    }

    /** Drops the page's old content, resources and annotations so that nothing from an earlier layer is referenced. */
    private fun removeOldContent(page: PDPage) {
        page.cosObject.removeItem(COSName.CONTENTS)
        page.cosObject.removeItem(COSName.ANNOTS)
        page.resources = PDResources()
    }

    private data class PagePlan(
        val coverage: PdfPageCoverage,
        val reasonCode: String?,
        val picture: ReadingPicture,
        val placement: ImageToPdf,
        val spans: List<PositionedTextSpan>,
    )

    private class ReadingPicture(val image: BufferedImage)

    /** Decides what a page becomes. Whole-file failures are thrown here, before the page is touched. */
    private fun plan(input: PdfPageInput): PagePlan {
        val geometry = input.geometry
        return when (input.mode) {
            PdfPageMode.OCR -> planOcr(input, geometry)
            PdfPageMode.BLANK -> blankOrUnavailable(loadPicture(input, input.layout), geometry)
            PdfPageMode.UNAVAILABLE -> {
                val picture = loadPicture(input, input.layout)
                val reason = input.layout?.reasonCode ?: "LAYOUT_MISSING"
                PagePlan(PdfPageCoverage.UNAVAILABLE, reason, picture, fitTransform(geometry, picture.image), emptyList())
            }
            PdfPageMode.NATIVE -> error("a NATIVE page is copied and never planned")
        }
    }

    private fun planOcr(input: PdfPageInput, geometry: PdfPageGeometry): PagePlan {
        val layout = checkNotNull(input.layout) { "an OCR page carries its layout" }
        val picture = loadPicture(input, layout)
        if (layout.spans.isEmpty()) return blankOrUnavailable(picture, geometry)
        val reading = checkNotNull(input.canonicalText) { "an OCR page carries its canonical text" }
        val spans = layout.spans.sortedBy { it.order }
        if (spans.any { span -> PdfFontResolver.unsupportedCodePoints(reading.substring(span.start, span.endExclusive)).isNotEmpty() }) {
            return unavailable(picture, geometry, "UNSUPPORTED_GLYPH")
        }
        val fit = fitTransform(geometry, picture.image)
        if (!transformMatches(layout.transform, fit)) return unavailable(picture, geometry, "TRANSFORM_MISMATCH")
        return PagePlan(PdfPageCoverage.OCR, null, picture, layout.transform, spans)
    }

    private fun blankOrUnavailable(picture: ReadingPicture, geometry: PdfPageGeometry): PagePlan =
        if (isVerifiedBlank(picture.image)) {
            PagePlan(PdfPageCoverage.BLANK, null, picture, fitTransform(geometry, picture.image), emptyList())
        } else {
            unavailable(picture, geometry, "BLANK_NOT_VERIFIED")
        }

    private fun unavailable(picture: ReadingPicture, geometry: PdfPageGeometry, reason: String): PagePlan =
        PagePlan(PdfPageCoverage.UNAVAILABLE, reason, picture, fitTransform(geometry, picture.image), emptyList())

    /**
     * Reads the reading image. When a layout is present its image hash and size must match the bytes read, which
     * is the provenance check that makes the layout's geometry apply to this picture.
     */
    private fun loadPicture(input: PdfPageInput, layout: PageTextLayout?): ReadingPicture {
        val file = input.readingImage
            ?: throw SelectablePdfWriteException(
                when (input.mode) {
                    PdfPageMode.UNAVAILABLE -> "UNAVAILABLE_WITHOUT_IMAGE"
                    PdfPageMode.BLANK -> "BLANK_WITHOUT_IMAGE"
                    else -> "READING_IMAGE_MISSING"
                },
                "physical page ${input.ordinal + 1} has no reading image to keep its visible content",
            )
        val image = try {
            ImageIO.read(file.toFile())
        } catch (error: IOException) {
            null
        } ?: throw SelectablePdfWriteException("READING_IMAGE_UNREADABLE", "physical page ${input.ordinal + 1}'s reading image cannot be decoded")
        if (layout != null) {
            if (sha256(file) != layout.imageSha256) {
                throw SelectablePdfWriteException(
                    "READING_IMAGE_PROVENANCE",
                    "physical page ${input.ordinal + 1}'s reading image is not the image its layout was measured on",
                )
            }
            if (image.width != layout.width || image.height != layout.height) {
                throw SelectablePdfWriteException(
                    "READING_IMAGE_MISMATCH",
                    "physical page ${input.ordinal + 1}'s reading image is ${image.width}x${image.height}, its layout ${layout.width}x${layout.height}",
                )
            }
        }
        return ReadingPicture(image)
    }

    /** A picture is verified blank only by measurement: text being absent is not proof. */
    private fun isVerifiedBlank(image: BufferedImage): Boolean {
        val width = image.width
        val row = IntArray(width)
        var ink = 0L
        for (y in 0 until image.height) {
            image.getRGB(0, y, width, 1, row, 0, width)
            for (pixel in row) {
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                if ((299 * red + 587 * green + 114 * blue) / 1000 < BLANK_INK_LUMINANCE) ink++
            }
        }
        return ink <= BLANK_MAX_INK_FRACTION * width.toLong() * image.height
    }

    // ---- Geometry -------------------------------------------------------------------------------------------------

    /**
     * The mapping from image pixels (top-left origin) to unrotated user space for a page's render: the image covers
     * the crop box as the viewer displays it, and `/Rotate` is applied once by the viewer. This is the inverse of
     * the forward map a viewer applies, so an upright picture lands upright after the viewer rotates it.
     */
    private fun fitTransform(geometry: PdfPageGeometry, image: BufferedImage): ImageToPdf {
        val quarterTurn = geometry.rotation == 90 || geometry.rotation == 270
        val displayWidth = if (quarterTurn) geometry.cropHeight else geometry.cropWidth
        val displayHeight = if (quarterTurn) geometry.cropWidth else geometry.cropHeight
        val kx = displayWidth / image.width
        val ky = displayHeight / image.height
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

    private fun transformMatches(actual: ImageToPdf, expected: ImageToPdf): Boolean {
        fun close(a: Double, e: Double) = abs(a - e) <= maxOf(TRANSFORM_ABSOLUTE_TOLERANCE, TRANSFORM_RELATIVE_TOLERANCE * abs(e))
        return close(actual.a, expected.a) && close(actual.b, expected.b) && close(actual.c, expected.c) &&
            close(actual.d, expected.d) &&
            abs(actual.e - expected.e) <= TRANSFORM_OFFSET_TOLERANCE_POINTS &&
            abs(actual.f - expected.f) <= TRANSFORM_OFFSET_TOLERANCE_POINTS
    }

    /** The picture drawn across the page, expressed as the PDF matrix that maps the unit square onto it. */
    private fun imageMatrix(placement: ImageToPdf, image: BufferedImage): Matrix {
        val width = image.width.toDouble()
        val height = image.height.toDouble()
        val t = placement
        return Matrix(
            (t.a * width).toFloat(),
            (t.b * width).toFloat(),
            (-t.c * height).toFloat(),
            (-t.d * height).toFloat(),
            (t.c * height + t.e).toFloat(),
            (t.d * height + t.f).toFloat(),
        )
    }

    private fun requireGeometry(page: PDPage, input: PdfPageInput) {
        val actual = geometryOf(page)
        if (!sameGeometry(actual, input.geometry)) {
            throw SelectablePdfWriteException(
                "PAGE_GEOMETRY_MISMATCH",
                "physical page ${input.ordinal + 1} does not have the geometry its request names",
            )
        }
    }

    private fun sameGeometry(a: PdfPageGeometry, b: PdfPageGeometry): Boolean =
        a.rotation == b.rotation &&
            abs(a.mediaX - b.mediaX) <= GEOMETRY_TOLERANCE && abs(a.mediaY - b.mediaY) <= GEOMETRY_TOLERANCE &&
            abs(a.mediaWidth - b.mediaWidth) <= GEOMETRY_TOLERANCE && abs(a.mediaHeight - b.mediaHeight) <= GEOMETRY_TOLERANCE &&
            abs(a.cropX - b.cropX) <= GEOMETRY_TOLERANCE && abs(a.cropY - b.cropY) <= GEOMETRY_TOLERANCE &&
            abs(a.cropWidth - b.cropWidth) <= GEOMETRY_TOLERANCE && abs(a.cropHeight - b.cropHeight) <= GEOMETRY_TOLERANCE &&
            abs(a.userUnit - b.userUnit) <= GEOMETRY_TOLERANCE

    private fun geometryOf(page: PDPage): PdfPageGeometry {
        val media = page.mediaBox
        val crop = page.cropBox
        try {
            return PdfPageGeometry(
                mediaX = media.lowerLeftX.toDouble(),
                mediaY = media.lowerLeftY.toDouble(),
                mediaWidth = media.width.toDouble(),
                mediaHeight = media.height.toDouble(),
                cropX = crop.lowerLeftX.toDouble(),
                cropY = crop.lowerLeftY.toDouble(),
                cropWidth = crop.width.toDouble(),
                cropHeight = crop.height.toDouble(),
                rotation = ((page.rotation % 360) + 360) % 360,
                userUnit = page.userUnit.toDouble(),
            )
        } catch (error: IllegalArgumentException) {
            throw SelectablePdfWriteException("PAGE_GEOMETRY_UNREADABLE", "the original has a page geometry this writer cannot read")
        }
    }

    // ---- Writing the invisible layer ------------------------------------------------------------------------------

    /**
     * One run per span, in reading order, so content-stream order is the canonical reading. Each run is horizontally
     * scaled (Tz) so its advance equals its span's width, and sized so its glyph box equals the span's height.
     * Between two spans on one line that the canonical text separates by a space, a zero-width space run keeps copies
     * spaced, without moving any span.
     */
    private fun writeInvisibleLayer(stream: PDPageContentStream, font: PDType0Font, reading: String, plan: PagePlan) {
        val spans = plan.spans
        for ((index, span) in spans.withIndex()) {
            val text = reading.substring(span.start, span.endExclusive)
            val frame = frame(plan.placement, span.rectangle.left, span.rectangle.top, span.rectangle.height)
            writeRun(stream, font, text, frame, span.rectangle.width * frame.lengthPerPixelX)
            if (index + 1 < spans.size) {
                val next = spans[index + 1]
                val separator = reading.substring(span.endExclusive, next.start)
                if (separator.isNotEmpty() && separator.all { it.isWhitespace() } && separator.none { it == '\n' || it == '\r' } &&
                    sharesLine(span.rectangle, next.rectangle)
                ) {
                    val spaceFrame = frame(plan.placement, span.rectangle.left + span.rectangle.width, span.rectangle.top, span.rectangle.height)
                    writeRun(stream, font, " ", spaceFrame, SEPARATOR_WIDTH_POINTS)
                }
            }
        }
    }

    private fun sharesLine(a: TextRectangle, b: TextRectangle): Boolean {
        val overlap = minOf(a.top + a.height, b.top + b.height) - maxOf(a.top, b.top)
        return overlap > 0.5 * minOf(a.height, b.height)
    }

    /**
     * The text frame of one image rectangle in user space. [origin] is the glyph origin on the baseline, [along] the
     * unit advance direction, [up] the unit direction of the glyph's top, and [heightUser] the glyph box's height.
     */
    private class Frame(
        val origin: Point,
        val along: Point,
        val up: Point,
        val heightUser: Double,
        val lengthPerPixelX: Double,
    )

    private data class Point(val x: Double, val y: Double)

    private fun frame(placement: ImageToPdf, leftPx: Double, topPx: Double, heightPx: Double): Frame {
        val t = placement
        val lengthX = hypot(t.a, t.b)
        val lengthY = hypot(t.c, t.d)
        val along = Point(t.a / lengthX, t.b / lengthX)
        val up = Point(-t.c / lengthY, -t.d / lengthY)
        val bottomLeft = apply(t, leftPx, topPx + heightPx)
        val heightUser = heightPx * lengthY
        val descent = PdfFontResolver.descentEm * heightUser / (PdfFontResolver.ascentEm + PdfFontResolver.descentEm)
        val origin = Point(bottomLeft.x + up.x * descent, bottomLeft.y + up.y * descent)
        return Frame(origin, along, up, heightUser, lengthX)
    }

    private fun apply(t: ImageToPdf, x: Double, y: Double): Point = Point(t.a * x + t.c * y + t.e, t.b * x + t.d * y + t.f)

    /** Writes [text] invisibly so that its advance equals [widthUser] along the frame's direction. */
    private fun writeRun(stream: PDPageContentStream, font: PDType0Font, text: String, frame: Frame, widthUser: Double) {
        val fontSize = frame.heightUser / (PdfFontResolver.ascentEm + PdfFontResolver.descentEm)
        val advance = font.getStringWidth(text) / 1000.0 * fontSize
        check(advance > 0.0) { "a text run has a positive advance" }
        val horizontalScale = 100.0 * widthUser / advance
        stream.beginText()
        stream.setFont(font, fontSize.toFloat())
        stream.setHorizontalScaling(horizontalScale.toFloat())
        stream.setRenderingMode(RenderingMode.NEITHER)
        stream.setTextMatrix(
            Matrix(
                frame.along.x.toFloat(),
                frame.along.y.toFloat(),
                frame.up.x.toFloat(),
                frame.up.y.toFloat(),
                frame.origin.x.toFloat(),
                frame.origin.y.toFloat(),
            ),
        )
        stream.showText(text)
        stream.endText()
        stream.setHorizontalScaling(100f)
    }

    // ---- Validation and publication -------------------------------------------------------------------------------

    /**
     * Reopens the temporary file and checks what the manifest claims: every physical page, its geometry, and the
     * text each page carries. Any mismatch fails the write, so nothing unverified is published.
     */
    private fun validateReopened(file: Path, request: PdfWriteRequest, results: List<PdfPageResult>, scratchDirectory: Path) {
        Loader.loadPDF(file.toFile(), fileBackedStorage(scratchDirectory)).use { document ->
            if (document.numberOfPages != request.pages.size) {
                throw SelectablePdfWriteException("OUTPUT_PAGE_COUNT", "the written file does not have every physical page")
            }
            for (input in request.pages) {
                val page = document.getPage(input.ordinal)
                if (!sameGeometry(geometryOf(page), input.geometry)) {
                    throw SelectablePdfWriteException("OUTPUT_GEOMETRY", "the written file changed physical page ${input.ordinal + 1}")
                }
                val coverage = results[input.ordinal].coverage
                if (coverage == PdfPageCoverage.NATIVE) continue
                val expected = if (coverage == PdfPageCoverage.OCR) {
                    val reading = checkNotNull(input.canonicalText)
                    val layout = checkNotNull(input.layout)
                    layout.spans.sortedBy { it.order }.joinToString("") { reading.substring(it.start, it.endExclusive) }
                } else {
                    ""
                }
                val extracted = extractText(document, input.ordinal)
                if (nonWhitespace(extracted) != nonWhitespace(expected)) {
                    throw SelectablePdfWriteException("OUTPUT_TEXT", "the written file's text layer on physical page ${input.ordinal + 1} is not the expected layer")
                }
            }
        }
    }

    private fun extractText(document: PDDocument, pageIndex: Int): String {
        val stripper = PDFTextStripper()
        stripper.sortByPosition = false
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        return stripper.getText(document)
    }

    private fun nonWhitespace(text: String): String = text.filterNot { it.isWhitespace() }

    private fun publish(temporary: Path, output: Path) {
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: Path): String {
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
}
