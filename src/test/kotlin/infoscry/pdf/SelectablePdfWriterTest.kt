package infoscry.pdf

import infoscry.pdf.WriterFixtures.displayRect
import infoscry.pdf.WriterFixtures.geometry
import infoscry.pdf.WriterFixtures.glyphs
import infoscry.pdf.WriterFixtures.layoutFor
import infoscry.pdf.WriterFixtures.render
import infoscry.pdf.WriterFixtures.sha256
import infoscry.pdf.WriterFixtures.text
import infoscry.pdf.WriterFixtures.wordSpans
import infoscry.pdf.WriterFixtures.writePng
import infoscry.pdf.WriterFixtures.writeSourcePdf
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.jupiter.api.io.TempDir

class SelectablePdfWriterTest {

    @field:TempDir
    lateinit var dir: Path

    // ---- Shared page designs -------------------------------------------------------------------------------------

    private val a4Crop = geometry(612.0 to 792.0, CropRect(0.0, 0.0, 420.0, 595.0))
    private val fullLetter = geometry(612.0 to 792.0, CropRect(0.0, 0.0, 612.0, 792.0))

    private val mixedReading = "Åsa Lindqvist\nbetalade 120 kronor."
    private val mixedWords = listOf(
        "Åsa" to CropRect(72.0, 100.0, 40.0, 14.0),
        "Lindqvist" to CropRect(118.0, 100.0, 70.0, 14.0),
        "betalade" to CropRect(72.0, 122.0, 60.0, 14.0),
        "120" to CropRect(136.0, 122.0, 24.0, 14.0),
        "kronor." to CropRect(164.0, 122.0, 45.0, 14.0),
    )

    /** One page's writer-facing description: the reading image it was measured on and its layout. */
    private data class MeasuredPage(val image: Path, val width: Int, val height: Int)

    private fun measure(source: Path, pageIndex: Int, dpi: Int, name: String): MeasuredPage {
        val image = render(source, pageIndex, dpi)
        return MeasuredPage(writePng(image, dir.resolve("$name.png")), image.width, image.height)
    }

    // ---- 1. Mixed document: physical pages, order, dimensions, per-mode text ------------------------------------

    @Test
    fun `mixed document keeps every physical page in order with its dimensions and the text each mode allows`() {
        val source = dir.resolve("mixed-source.pdf")
        val nativeGeometry = fullLetter
        writeSourcePdf(
            source,
            listOf(
                SourcePage(
                    nativeGeometry,
                    SourceContent.NativeText(listOf(TextRun("Åsa betalade 120 kronor.", 72.0, 120.0, 14.0))),
                ),
                SourcePage(a4Crop, SourceContent.Scan(inks = mixedWords.map { it.second })),
                SourcePage(geometry(612.0 to 792.0, CropRect(0.0, 0.0, 612.0, 792.0), rotation = 90), SourceContent.Scan(inks = emptyList())),
                SourcePage(geometry(500.0 to 400.0, CropRect(0.0, 0.0, 500.0, 400.0)), SourceContent.Scan(inks = listOf(CropRect(50.0, 50.0, 200.0, 20.0)))),
            ),
        )
        val ocrImage = measure(source, 1, 200, "ocr-page")
        val blankImage = measure(source, 2, 200, "blank-page")
        val unavailableImage = measure(source, 3, 200, "unavailable-page")
        val unavailableGeometry = geometry(500.0 to 400.0, CropRect(0.0, 0.0, 500.0, 400.0))
        // An incomplete layout (alignment could not place the text) keeps its safe reason on the unavailable page.
        val incompleteLayout = PageTextLayout(
            version = 1,
            textSha256 = PdfModelRules.sha256Of("Ärendet"),
            imageSha256 = WriterFixtures.sha256(unavailableImage.image),
            width = unavailableImage.width,
            height = unavailableImage.height,
            geometry = unavailableGeometry,
            transform = WriterFixtures.readingTransform(unavailableGeometry, unavailableImage.width, unavailableImage.height),
            spans = emptyList(),
            complete = false,
            reasonCode = "NO_ALIGNMENT",
            positioningVersion = "fixture-positioning-1",
        )
        val originalSha = sha256(source)

        val request = PdfWriteRequest(
            original = source,
            revisionId = "rev-mixed",
            pages = listOf(
                PdfPageInput(0, null, null, null, nativeGeometry, PdfPageMode.NATIVE),
                PdfPageInput(
                    1,
                    mixedReading,
                    ocrImage.image,
                    layoutFor(a4Crop, mixedReading, wordSpans(mixedReading, mixedWords), ocrImage.image, ocrImage.width, ocrImage.height),
                    a4Crop,
                    PdfPageMode.OCR,
                ),
                PdfPageInput(2, null, blankImage.image, null, geometry(612.0 to 792.0, CropRect(0.0, 0.0, 612.0, 792.0), rotation = 90), PdfPageMode.BLANK),
                PdfPageInput(3, null, unavailableImage.image, incompleteLayout, unavailableGeometry, PdfPageMode.UNAVAILABLE),
            ),
            output = dir.resolve("mixed-out.pdf"),
        )

        val result = SelectablePdfWriter.write(request)

        assertEquals(originalSha, sha256(source), "the original file must never be touched")
        assertEquals(
            listOf(PdfPageCoverage.NATIVE, PdfPageCoverage.OCR, PdfPageCoverage.BLANK, PdfPageCoverage.UNAVAILABLE),
            result.manifest.pages.map { it.coverage },
        )
        assertEquals(listOf(null, null, null, "NO_ALIGNMENT"), result.manifest.pages.map { it.reasonCode })
        assertEquals(2, result.manifest.selectablePageCount, "one NATIVE and one OCR page are selectable")
        assertEquals(1, result.manifest.blankPageCount)
        assertEquals(1, result.manifest.unavailablePageResults.size)
        assertEquals(result.bytes, java.io.File(dir.resolve("mixed-out.pdf").toString()).length())
        assertEquals(sha256(dir.resolve("mixed-out.pdf")), result.sha256)

        val out = dir.resolve("mixed-out.pdf")
        assertEquals(geometriesOf(source), geometriesOf(out), "every physical page keeps its order and dimensions")
        assertEquals("Åsa betalade 120 kronor.", text(out, 0).trim().replace(Regex("\\s+"), " "))
        assertEquals(mixedReading.replace('\n', ' '), text(out, 1).trim().replace(Regex("\\s+"), " "))
        assertEquals("", text(out, 2).trim(), "a verified blank page has no selectable text")
        assertEquals("", text(out, 3).trim(), "an unavailable page has no selectable text")
        assertVisibleSame(out, 2, blankImage.image, 200)
        assertVisibleSame(out, 3, unavailableImage.image, 200)
        assertEquals(
            emptyList<String>(),
            Files.list(dir).use { files -> files.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList() },
            "a successful write leaves no scratch or temporary files beside the output",
        )
    }

    // ---- 2. OCR rebuild: stale invisible text is replaced, and none of it survives in the file -----------------

    @Test
    fun `an OCR rebuild replaces stale invisible text and no stale glyph survives anywhere in the file`() {
        val source = dir.resolve("stale-source.pdf")
        val staleWords = listOf("Åsa" to CropRect(72.0, 100.0, 40.0, 14.0), "Lindqvist" to CropRect(118.0, 100.0, 70.0, 14.0))
        writeSourcePdf(
            source,
            listOf(
                SourcePage(
                    a4Crop,
                    SourceContent.Scan(
                        inks = staleWords.map { it.second },
                        staleRuns = listOf(
                            TextRun("Asa", 72.0, 114.0, 14.0),
                            TextRun("Sven", 72.0, 300.0, 14.0),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(text(source, 0).contains("Asa"), "the stale input must really carry the wrong text")
        val reading = "Åsa Lindqvist"
        val image = measure(source, 0, 200, "stale-page")
        val out = dir.resolve("stale-out.pdf")

        SelectablePdfWriter.write(
            PdfWriteRequest(
                original = source,
                revisionId = "rev-stale",
                pages = listOf(
                    PdfPageInput(
                        0,
                        reading,
                        image.image,
                        layoutFor(a4Crop, reading, wordSpans(reading, staleWords), image.image, image.width, image.height),
                        a4Crop,
                        PdfPageMode.OCR,
                    ),
                ),
                output = out,
            ),
        )

        val extracted = text(out, 0)
        assertTrue(extracted.contains("Åsa"), "the canonical reading is copyable: '$extracted'")
        assertFalse(extracted.contains("Asa"), "the stale word must not survive the rebuild: '$extracted'")
        assertFalse(extracted.contains("Sven"), "the stale word must not survive the rebuild: '$extracted'")
        // The stale runs' own operands, copied from the source's content, must occur in no stream the output holds,
        // reachable or not. Control: the scanner finds them in the source, so their absence in the output means something.
        val staleOperands = staleOperands(source)
        assertEquals(2, staleOperands.size, "the source carries the two stale runs")
        for (operand in staleOperands) {
            assertTrue(allStreamText(source).any { it.contains(operand, ignoreCase = true) }, "control: the scanner sees $operand in the source")
            assertFalse(
                allStreamText(out).any { it.contains(operand, ignoreCase = true) },
                "a stale encoded run survives in a stream of the rebuilt file",
            )
        }
    }

    // ---- 3. Rotation, crop origin, user unit and reduced DPI ---------------------------------------------------

    @Test
    fun `span boxes and the rendered page hold for every rotation, crop origin, user unit and reduced resolution`() {
        val cases = listOf(
            "plain" to geometry(612.0 to 792.0, CropRect(0.0, 0.0, 300.0, 200.0)),
            "offset crop and media origin" to geometry(
                612.0 to 792.0,
                CropRect(40.0, 60.0, 300.0, 200.0),
                mediaOrigin = 10.0 to 20.0,
            ),
            "user unit 2" to geometry(612.0 to 792.0, CropRect(30.0, 30.0, 300.0, 200.0), userUnit = 2.0),
        )
        // Words as a viewer shows them: horizontal on the displayed page, which is what OCR reads from the reading image.
        val displayWords = listOf(
            "Åsa" to CropRect(20.0, 30.0, 40.0, 14.0),
            "Lindqvist" to CropRect(66.0, 30.0, 70.0, 14.0),
            "betalade" to CropRect(20.0, 52.0, 60.0, 14.0),
            "120" to CropRect(84.0, 52.0, 24.0, 14.0),
            "kronor." to CropRect(112.0, 52.0, 45.0, 14.0),
        )
        val reading = "Åsa Lindqvist\nbetalade 120 kronor."
        val report = StringBuilder()
        for ((name, base) in cases) for (rotation in listOf(0, 90, 180, 270)) for (dpi in listOf(200, 72)) {
            val label = "$name, rotation $rotation, $dpi dpi"
            val g = base.copy(rotation = rotation)
            // The scan is drawn in unrotated space; /Rotate turns it so the words read horizontally on the display.
            val scanWords = displayWords.map { (word, display) -> word to WriterFixtures.unrotatedRect(g, display) }
            val source = dir.resolve("geom-${name.hashCode()}-$rotation-$dpi.pdf").also {
                writeSourcePdf(it, listOf(SourcePage(g, SourceContent.Scan(inks = scanWords.map { w -> w.second }))))
            }
            val image = measure(source, 0, dpi, "geom-$rotation-$dpi-${name.hashCode()}")
            val spans = wordSpans(reading, scanWords)
            // The fixture's forward map must agree with the render: each displayed word is ink on the reading image.
            for ((word, display) in displayWords) {
                val pixels = WriterFixtures.pixelRect(g, WriterFixtures.unrotatedRect(g, display), image.width, image.height)
                assertTrue(WriterFixtures.inkPixels(javax.imageio.ImageIO.read(image.image.toFile()), pixels) > 0, "$label: no ink for '$word' at $pixels")
            }
            val out = dir.resolve("geom-out-${name.hashCode()}-$rotation-$dpi.pdf")
            SelectablePdfWriter.write(
                PdfWriteRequest(
                    original = source,
                    revisionId = "rev-geom",
                    pages = listOf(
                        PdfPageInput(
                            0,
                            reading,
                            image.image,
                            layoutFor(g, reading, spans, image.image, image.width, image.height),
                            g,
                            PdfPageMode.OCR,
                        ),
                    ),
                    output = out,
                ),
            )

            assertEquals(listOf(g), geometriesOf(out), "$label: the page keeps its geometry")
            val deviation = assertSpanBoxesInside(glyphs(out, 0), spans, g, label)
            report.append("  %-42s horizontal %.4f pt, vertical %.4f pt\n".format(label, deviation.horizontal, deviation.vertical))
            assertVisibleSame(out, 0, image.image, dpi, label)
        }
        println("Rotation/crop/user-unit/DPI matrix, deviation from span boxes (inset rule: 1.5 pt):\n$report")
    }

    // ---- 4. Native copy keeps the original objects and glyph boxes exactly ------------------------------------

    @Test
    fun `native pages are copied untouched with glyph boxes within a ten-thousandth of a point`() {
        for (rotation in listOf(0, 90)) {
            val g = geometry(612.0 to 792.0, CropRect(0.0, 0.0, 612.0, 792.0), rotation = rotation)
            val source = dir.resolve("native-$rotation.pdf")
            writeSourcePdf(
                source,
                listOf(SourcePage(g, SourceContent.NativeText(listOf(TextRun("Åsa betalade 120 kronor.", 72.0, 120.0, 14.0))))),
            )
            val out = dir.resolve("native-out-$rotation.pdf")
            val result = SelectablePdfWriter.write(
                PdfWriteRequest(source, "rev-native", listOf(PdfPageInput(0, null, null, null, g, PdfPageMode.NATIVE)), out),
            )

            assertEquals(PdfPageCoverage.NATIVE, result.manifest.pages.single().coverage)
            assertEquals(contentBytes(source), contentBytes(out), "rotation $rotation: the native content stream is copied byte for byte")
            val before = glyphs(source, 0)
            val after = glyphs(out, 0)
            assertEquals(before.size, after.size)
            var worst = 0.0
            for ((a, b) in before.zip(after)) {
                assertEquals(a.char, b.char)
                worst = maxOf(worst, abs(a.left - b.left), abs(a.baseline - b.baseline), abs(a.width - b.width), abs(a.height - b.height))
            }
            println("Native copy, rotation $rotation: worst glyph deviation %.6f pt".format(worst))
            assertTrue(worst <= 0.0001, "rotation $rotation: native glyph boxes moved by $worst pt")
        }
    }

    // ---- 5. Swedish Unicode, hyphenation, split and merged tokens, canonical copy order ------------------------

    @Test
    fun `Swedish letters, hyphenation, split and merged tokens are copied in the canonical reading order`() {
        val reading = listOf(
            "Åsa Lindqvist",
            "och så åäö ÅÄÖ",
            "Den stor",
            "kostnad var hög.",
        ).joinToString("\n")
        val inks = listOf(
            CropRect(72.0, 100.0, 40.0, 14.0), // Åsa
            CropRect(118.0, 100.0, 35.0, 14.0), // Lind, drawn apart from the next box
            CropRect(153.0, 100.0, 35.0, 14.0), // qvist: one canonical word, two drawn boxes
            CropRect(72.0, 122.0, 50.0, 14.0), // ochså: one drawn token read as two canonical words
            CropRect(126.0, 122.0, 30.0, 14.0), // åäö
            CropRect(160.0, 122.0, 35.0, 14.0), // ÅÄÖ
            CropRect(72.0, 144.0, 28.0, 14.0), // Den
            CropRect(104.0, 144.0, 40.0, 14.0), // stor-, hyphenated at the line end
            CropRect(72.0, 166.0, 60.0, 14.0), // kostnad
            CropRect(136.0, 166.0, 28.0, 14.0), // var
            CropRect(168.0, 166.0, 30.0, 14.0), // hög.
        )
        val spans = listOf(
            PlainSpan(reading.indexOf("Åsa"), reading.indexOf("Åsa") + 3, CropRect(72.0, 100.0, 40.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("Lindqvist"), reading.indexOf("Lindqvist") + 9, CropRect(118.0, 100.0, 70.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("och"), reading.indexOf("och") + 3, CropRect(72.0, 122.0, 28.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("så"), reading.indexOf("så") + 2, CropRect(100.0, 122.0, 22.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("åäö"), reading.indexOf("åäö") + 3, CropRect(126.0, 122.0, 30.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("ÅÄÖ"), reading.indexOf("ÅÄÖ") + 3, CropRect(160.0, 122.0, 35.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("Den"), reading.indexOf("Den") + 3, CropRect(72.0, 144.0, 28.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("stor"), reading.indexOf("stor") + 4, CropRect(104.0, 144.0, 40.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("kostnad"), reading.indexOf("kostnad") + 7, CropRect(72.0, 166.0, 60.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("var"), reading.indexOf("var") + 3, CropRect(136.0, 166.0, 28.0, 14.0), SpanGranularity.WORD),
            PlainSpan(reading.indexOf("hög."), reading.indexOf("hög.") + 4, CropRect(168.0, 166.0, 30.0, 14.0), SpanGranularity.WORD),
        )
        val source = dir.resolve("swedish-source.pdf")
        writeSourcePdf(source, listOf(SourcePage(a4Crop, SourceContent.Scan(inks = inks))))
        val image = measure(source, 0, 200, "swedish-page")
        val out = dir.resolve("swedish-out.pdf")

        SelectablePdfWriter.write(
            PdfWriteRequest(
                source,
                "rev-swedish",
                listOf(
                    PdfPageInput(
                        0,
                        reading,
                        image.image,
                        layoutFor(a4Crop, reading, spans, image.image, image.width, image.height),
                        a4Crop,
                        PdfPageMode.OCR,
                    ),
                ),
                out,
            ),
        )

        val extracted = text(out, 0).trim().split(Regex("\\s+"))
        assertEquals(reading.split(Regex("\\s+")), extracted, "copy order and tokens follow the canonical reading")
        val deviation = assertSpanBoxesInside(glyphs(out, 0), spans, a4Crop, "swedish page")
        println("Swedish page deviation: horizontal %.4f pt, vertical %.4f pt".format(deviation.horizontal, deviation.vertical))
        assertVisibleSame(out, 0, image.image, 200, "swedish page")
    }

    // ---- 6. Unsupported glyphs give an explicit page reason, never a substitution -----------------------------

    @Test
    fun `a character the font cannot draw makes its page unavailable with that reason and keeps the picture`() {
        val reading = "Hej 中."
        val words = listOf("Hej" to CropRect(72.0, 100.0, 30.0, 14.0), "中." to CropRect(110.0, 100.0, 20.0, 14.0))
        val source = dir.resolve("glyph-source.pdf")
        writeSourcePdf(source, listOf(SourcePage(a4Crop, SourceContent.Scan(inks = words.map { it.second }))))
        val image = measure(source, 0, 200, "glyph-page")
        val out = dir.resolve("glyph-out.pdf")

        val result = SelectablePdfWriter.write(
            PdfWriteRequest(
                source,
                "rev-glyph",
                listOf(
                    PdfPageInput(
                        0,
                        reading,
                        image.image,
                        layoutFor(a4Crop, reading, wordSpans(reading, words), image.image, image.width, image.height),
                        a4Crop,
                        PdfPageMode.OCR,
                    ),
                ),
                out,
            ),
        )

        assertEquals(PdfPageResult(0, PdfPageCoverage.UNAVAILABLE, "UNSUPPORTED_GLYPH"), result.manifest.pages.single())
        assertEquals("", text(out, 0).trim(), "no substituted or partial text layer is written for the page")
        assertVisibleSame(out, 0, image.image, 200)
    }

    // ---- 7. Blank pages need verified blankness ---------------------------------------------------------------

    @Test
    fun `a page is blank only when its picture is verified blank, and an unverified blank claim keeps its ink`() {
        val source = dir.resolve("blank-source.pdf")
        writeSourcePdf(
            source,
            listOf(
                SourcePage(a4Crop, SourceContent.Scan(inks = emptyList())),
                SourcePage(a4Crop, SourceContent.Scan(inks = listOf(CropRect(72.0, 100.0, 40.0, 14.0)))),
            ),
        )
        val blank = measure(source, 0, 200, "verified-blank")
        val ink = measure(source, 1, 200, "claimed-blank")
        val out = dir.resolve("blank-out.pdf")

        val result = SelectablePdfWriter.write(
            PdfWriteRequest(
                source,
                "rev-blank",
                listOf(
                    PdfPageInput(0, null, blank.image, null, a4Crop, PdfPageMode.BLANK),
                    PdfPageInput(1, null, ink.image, null, a4Crop, PdfPageMode.BLANK),
                ),
                out,
            ),
        )

        assertEquals(PdfPageResult(0, PdfPageCoverage.BLANK, null), result.manifest.pages[0])
        assertEquals(PdfPageResult(1, PdfPageCoverage.UNAVAILABLE, "BLANK_NOT_VERIFIED"), result.manifest.pages[1])
        assertVisibleSame(out, 1, ink.image, 200, "the claimed-blank page with ink")
    }

    // ---- 8. Unavailable without a picture fails the whole file --------------------------------------------------

    @Test
    fun `an unavailable page that cannot keep its picture fails the whole file and writes nothing`() {
        val source = dir.resolve("unavailable-source.pdf")
        writeSourcePdf(
            source,
            listOf(
                SourcePage(a4Crop, SourceContent.Scan(inks = listOf(CropRect(72.0, 100.0, 40.0, 14.0)))),
                SourcePage(a4Crop, SourceContent.Scan(inks = listOf(CropRect(72.0, 100.0, 40.0, 14.0)))),
            ),
        )
        val first = measure(source, 0, 200, "first-page")
        val out = dir.resolve("never-written.pdf")
        val originalSha = sha256(source)

        val error = assertFailsWith<SelectablePdfWriteException> {
            SelectablePdfWriter.write(
                PdfWriteRequest(
                    source,
                    "rev-fail",
                    listOf(
                        PdfPageInput(0, null, first.image, null, a4Crop, PdfPageMode.UNAVAILABLE),
                        PdfPageInput(1, null, null, null, a4Crop, PdfPageMode.UNAVAILABLE),
                    ),
                    out,
                ),
            )
        }

        assertEquals("UNAVAILABLE_WITHOUT_IMAGE", error.reasonCode)
        assertFalse(Files.exists(out), "a failed write leaves no output at the requested path")
        assertEquals(emptyList<Path>(), Files.list(dir).use { it.filter { p -> p.fileName.toString().endsWith(".tmp") }.toList() })
        assertEquals(originalSha, sha256(source))
    }

    // ---- 9. Provenance and geometry are checked, not assumed ----------------------------------------------------

    @Test
    fun `a page whose geometry or reading image disagrees with its source fails the whole file`() {
        val source = dir.resolve("provenance-source.pdf")
        writeSourcePdf(source, listOf(SourcePage(a4Crop, SourceContent.Scan(inks = listOf(CropRect(72.0, 100.0, 40.0, 14.0))))))
        val image = measure(source, 0, 200, "provenance-page")
        val wrongGeometry = geometry(612.0 to 792.0, CropRect(0.0, 0.0, 300.0, 595.0))

        val mismatch = assertFailsWith<SelectablePdfWriteException> {
            SelectablePdfWriter.write(
                PdfWriteRequest(
                    source,
                    "rev-geom",
                    listOf(PdfPageInput(0, null, image.image, null, wrongGeometry, PdfPageMode.UNAVAILABLE)),
                    dir.resolve("geom-mismatch.pdf"),
                ),
            )
        }
        assertEquals("PAGE_GEOMETRY_MISMATCH", mismatch.reasonCode)

        val reading = "Åsa"
        val layout = layoutFor(a4Crop, reading, wordSpans(reading, listOf("Åsa" to CropRect(72.0, 100.0, 40.0, 14.0))), image.image, image.width, image.height)
        val wrongImage = dir.resolve("other.png").also { writePng(BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY), it) }
        val provenance = assertFailsWith<SelectablePdfWriteException> {
            SelectablePdfWriter.write(
                PdfWriteRequest(
                    source,
                    "rev-provenance",
                    listOf(PdfPageInput(0, reading, wrongImage, layout, a4Crop, PdfPageMode.OCR)),
                    dir.resolve("provenance-mismatch.pdf"),
                ),
            )
        }
        assertEquals("READING_IMAGE_PROVENANCE", provenance.reasonCode)
    }

    // ---- 10. Font: pinned, Unicode-mapped, Swedish-complete ----------------------------------------------------

    @Test
    fun `the pinned font is the verified Noto Sans file and writes Swedish letters with Unicode mappings`() {
        assertEquals(PdfFontResolver.SHA256, sha256Of(PdfFontResolver.bytes()))
        assertEquals(825628, PdfFontResolver.bytes().size)
        for (letter in "ÅÄÖåäö") {
            assertTrue(PdfFontResolver.unsupportedCodePoints(letter.toString()).isEmpty(), "$letter must be drawable")
        }

        val source = dir.resolve("font-source.pdf")
        writeSourcePdf(source, listOf(SourcePage(a4Crop, SourceContent.Scan(inks = listOf(CropRect(72.0, 100.0, 40.0, 14.0))))))
        val image = measure(source, 0, 200, "font-page")
        val out = dir.resolve("font-out.pdf")
        SelectablePdfWriter.write(
            PdfWriteRequest(
                source,
                "rev-font",
                listOf(PdfPageInput(0, "Åsa", image.image, layoutFor(a4Crop, "Åsa", wordSpans("Åsa", listOf("Åsa" to CropRect(72.0, 100.0, 40.0, 14.0))), image.image, image.width, image.height), a4Crop, PdfPageMode.OCR)),
                out,
            ),
        )
        Loader.loadPDF(out.toFile()).use { document ->
            val page = document.getPage(0)
            val names = page.resources.fontNames.toList()
            assertEquals(1, names.size, "the page uses one embedded font")
            val font = page.resources.getFont(names.single()) as PDType0Font
            assertNotNull(font.cosObject.getDictionaryObject(COSName.TO_UNICODE), "the embedded font carries a ToUnicode map")
            assertEquals("Å", font.toUnicode(codeOf(font, "Å")), "Å maps back to Unicode U+00C5")
        }
    }

    // ---- 11. Multi-page fixture: size, time and memory are measured --------------------------------------------

    @Test
    fun `a thirty-page OCR document is written from file-backed storage with measured size and time`() {
        val pageCount = 30
        val source = dir.resolve("multi-source.pdf")
        val lines = (0 until 20).map { row -> CropRect(40.0, 20.0 + row * 24.0, 300.0, 12.0) }
        writeSourcePdf(source, (0 until pageCount).map { SourcePage(a4Crop, SourceContent.Scan(inks = lines)) })
        val sourceBytes = Files.size(source)
        val readings = (0 until pageCount).map { page -> lines.indices.joinToString(" ") { row -> "tok$page-$row" } }
        val requests = (0 until pageCount).map { page ->
            val image = measure(source, page, 150, "multi-$page")
            val words = lines.mapIndexed { row, rect -> "tok$page-$row" to rect }
            val reading = readings[page]
            PdfPageInput(
                page,
                reading,
                image.image,
                layoutFor(a4Crop, reading, wordSpans(reading, words), image.image, image.width, image.height),
                a4Crop,
                PdfPageMode.OCR,
            )
        }

        val runtime = Runtime.getRuntime()
        System.gc()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        val out = dir.resolve("multi-out.pdf")
        val result = SelectablePdfWriter.write(PdfWriteRequest(source, "rev-multi", requests, out))
        val seconds = (System.nanoTime() - started) / 1e9
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()

        assertEquals(pageCount, result.manifest.pages.size)
        assertEquals(pageCount, result.manifest.selectablePageCount)
        assertEquals(result.bytes, Files.size(out))
        assertEquals(geometriesOf(source), geometriesOf(out), "every page keeps its dimensions")
        for (page in listOf(0, 14, pageCount - 1)) {
            val extracted = text(out, page).replace(Regex("\\s+"), " ").trim()
            assertEquals(readings[page], extracted, "page ${page + 1} carries its own reading in order")
        }
        println(
            "Thirty-page fixture: source %d bytes, output %d bytes, %.1f s, heap delta %.1f MB (both JVM-wide, approximate)".format(
                sourceBytes, result.bytes, seconds, (heapAfter - heapBefore) / 1048576.0,
            ),
        )
    }

    // ---- 12. Idempotence: the same request twice gives the same file ------------------------------------------

    @Test
    fun `writing the same request twice produces the same bytes and manifest`() {
        val source = dir.resolve("idempotent-source.pdf")
        writeSourcePdf(source, listOf(SourcePage(a4Crop, SourceContent.Scan(inks = mixedWords.map { it.second }))))
        val image = measure(source, 0, 200, "idempotent-page")
        val request = PdfWriteRequest(
            source,
            "rev-idem",
            listOf(
                PdfPageInput(
                    0,
                    mixedReading,
                    image.image,
                    layoutFor(a4Crop, mixedReading, wordSpans(mixedReading, mixedWords), image.image, image.width, image.height),
                    a4Crop,
                    PdfPageMode.OCR,
                ),
            ),
            dir.resolve("idem-out.pdf"),
        )

        val first = SelectablePdfWriter.write(request)
        val second = SelectablePdfWriter.write(request)

        assertEquals(first.sha256, second.sha256)
        assertEquals(first.manifest, second.manifest)
        assertEquals(first.bytes, second.bytes)
    }

    // ---- 13. Annotations on rebuilt pages: visible appearance is flattened once, live links are dropped ----------

    @Test
    fun `OCR pages flatten a visible annotation into the picture once and drop live link annotations`() {
        val base = dir.resolve("annotated-base.pdf")
        writeSourcePdf(base, listOf(SourcePage(a4Crop, SourceContent.Scan(inks = listOf(CropRect(72.0, 100.0, 40.0, 14.0))))))
        val source = dir.resolve("annotated-source.pdf")
        Loader.loadPDF(base.toFile()).use { document ->
            val page = document.getPage(0)
            val appearance = PDAppearanceStream(document)
            appearance.bBox = PDRectangle(120f, 60f)
            appearance.resources = PDResources()
            PDPageContentStream(document, appearance).use { stream ->
                stream.setNonStrokingColor(1f, 0f, 0f)
                stream.addRect(0f, 0f, 120f, 60f)
                stream.fill()
            }
            val square = PDAnnotationSquare()
            square.rectangle = PDRectangle(200f, 300f, 120f, 60f)
            square.appearance = PDAppearanceDictionary().also { it.setNormalAppearance(appearance) }
            val link = PDAnnotationLink()
            link.rectangle = PDRectangle(50f, 50f, 80f, 20f)
            page.setAnnotations(listOf(square, link))
            document.save(source.toFile())
        }
        val image = measure(source, 0, 200, "annotated-page")
        assertTrue(redPixels(javax.imageio.ImageIO.read(image.image.toFile())) > 1000, "the reading image shows the visible annotation")
        val out = dir.resolve("annotated-out.pdf")

        SelectablePdfWriter.write(
            PdfWriteRequest(
                source,
                "rev-annotated",
                listOf(
                    PdfPageInput(
                        0,
                        "Åsa",
                        image.image,
                        layoutFor(a4Crop, "Åsa", wordSpans("Åsa", listOf("Åsa" to CropRect(72.0, 100.0, 40.0, 14.0))), image.image, image.width, image.height),
                        a4Crop,
                        PdfPageMode.OCR,
                    ),
                ),
                out,
            ),
        )

        Loader.loadPDF(out.toFile()).use { document ->
            assertTrue(document.getPage(0).annotations.isEmpty(), "annotations are flattened: the rebuilt page keeps no live annotation")
        }
        // Drawn once: the picture already holds the appearance; with no live annotation left, nothing draws it again.
        // Edge pixels of the resampled picture differ by a fraction of a percent, so the area is compared with tolerance.
        val referenceRed = redPixels(javax.imageio.ImageIO.read(image.image.toFile()))
        val renderedRed = redPixels(render(out, 0, 200))
        assertTrue(abs(renderedRed - referenceRed) <= 0.02 * referenceRed, "the annotation area is $renderedRed px, the picture's $referenceRed px")
        assertVisibleSame(out, 0, image.image, 200, "the annotated page")
    }

    // ---- 14. Bookmarks keep pointing at their physical pages ------------------------------------------------------

    @Test
    fun `outline destinations still resolve to the same physical pages after writing`() {
        val base = dir.resolve("outline-base.pdf")
        writeSourcePdf(
            base,
            listOf(
                SourcePage(a4Crop, SourceContent.NativeText(listOf(TextRun("Första sidan", 72.0, 120.0, 14.0)))),
                SourcePage(a4Crop, SourceContent.Scan(inks = listOf(CropRect(72.0, 100.0, 40.0, 14.0)))),
                SourcePage(a4Crop, SourceContent.Scan(inks = listOf(CropRect(72.0, 100.0, 40.0, 14.0)))),
            ),
        )
        val source = dir.resolve("outline-source.pdf")
        Loader.loadPDF(base.toFile()).use { document ->
            val outline = PDDocumentOutline()
            PDOutlineItem().also { it.title = "Two"; it.setDestination(document.getPage(1)); outline.addLast(it) }
            PDOutlineItem().also { it.title = "Three"; it.setDestination(document.getPage(2)); outline.addLast(it) }
            document.documentCatalog.documentOutline = outline
            document.save(source.toFile())
        }
        val image1 = measure(source, 1, 200, "outline-page-2")
        val image2 = measure(source, 2, 200, "outline-page-3")
        val out = dir.resolve("outline-out.pdf")
        val reading = "Åsa"
        val words = listOf("Åsa" to CropRect(72.0, 100.0, 40.0, 14.0))

        SelectablePdfWriter.write(
            PdfWriteRequest(
                source,
                "rev-outline",
                listOf(
                    PdfPageInput(0, null, null, null, a4Crop, PdfPageMode.NATIVE),
                    PdfPageInput(1, reading, image1.image, layoutFor(a4Crop, reading, wordSpans(reading, words), image1.image, image1.width, image1.height), a4Crop, PdfPageMode.OCR),
                    PdfPageInput(2, null, image2.image, null, a4Crop, PdfPageMode.UNAVAILABLE),
                ),
                out,
            ),
        )

        Loader.loadPDF(out.toFile()).use { document ->
            val items = document.documentCatalog.documentOutline.children().toList()
            assertEquals(listOf("Two", "Three"), items.map { it.title })
            assertEquals(listOf(1, 2), items.map { (it.destination as PDPageDestination).retrievePageNumber() })
        }
    }

    private fun redPixels(image: BufferedImage): Int {
        var red = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r > 200 && g < 80 && b < 80) red++
        }
        return red
    }

    // ---- Helpers ----------------------------------------------------------------------------------------------

    private fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun geometriesOf(file: Path): List<PdfPageGeometry> =
        Loader.loadPDF(file.toFile()).use { document ->
            document.pages.map { page -> geometryOf(page) }
        }

    private fun geometryOf(page: PDPage): PdfPageGeometry {
        val media = page.mediaBox
        val crop = page.cropBox
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
    }

    /** The decoded bytes of page 0's content stream. */
    private fun contentBytes(file: Path): String =
        Loader.loadPDF(file.toFile()).use { document -> document.getPage(0).contents.readBytes().toString(Charsets.ISO_8859_1) }

    /** The text-showing operators of page 1 of [file], exactly as its content stream holds them (`(bytes) Tj`). */
    private fun staleOperands(file: Path): List<String> =
        Loader.loadPDF(file.toFile()).use { document ->
            val contents = document.getPage(0).contents.readBytes().toString(Charsets.ISO_8859_1)
            Regex("""\((?:\\.|[^\\)])*\)\s*Tj""", RegexOption.DOT_MATCHES_ALL).findAll(contents).map { it.value }.toList()
        }

    /** Every stream the file holds, reachable or not, decoded as text. */
    private fun allStreamText(file: Path): List<String> =
        Loader.loadPDF(file.toFile()).use { document ->
            document.document.xrefTable.keys.mapNotNull { key ->
                val obj = document.document.getObjectFromPool(key)?.`object`
                (obj as? COSStream)?.createInputStream()?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
            }
        }

    private fun codeOf(font: PDType0Font, text: String): Int {
        val bytes = font.encode(text)
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    /**
     * Each span's glyphs, matched in order and skipping the spaces the writer draws between spans, must lie within
     * the span's box plus [SelectablePdfWriter.CONTENT_INSET_POINTS]. Returns the worst edge deviation in points.
     */
    private data class Deviation(val horizontal: Double, val vertical: Double)

    private fun assertSpanBoxesInside(glyphs: List<WriterExtractedGlyph>, spans: List<PlainSpan>, g: PdfPageGeometry, label: String): Deviation {
        val visible = glyphs.filter { !it.char.isBlank() }
        var index = 0
        var horizontal = 0.0
        var vertical = 0.0
        val inset = SelectablePdfWriter.CONTENT_INSET_POINTS
        for (span in spans) {
            val count = span.endExclusive - span.start
            assertTrue(visible.size >= index + count, "$label: ${visible.size} visible glyphs, expected at least ${index + count}")
            val boxes = visible.subList(index, index + count)
            index += count
            val expected = displayRect(g, span.rect)
            val left = boxes.minOf { it.left }
            val right = boxes.maxOf { it.right }
            val top = boxes.minOf { it.top }
            val bottom = boxes.maxOf { it.baseline }
            horizontal = maxOf(horizontal, abs(left - expected.left), abs(right - (expected.left + expected.width)))
            vertical = maxOf(vertical, abs(top - expected.top), abs(bottom - (expected.top + expected.height)))
            assertTrue(
                left >= expected.left - inset && right <= expected.left + expected.width + inset,
                "$label: glyphs horizontally at $left..$right, span box ${expected.left}..${expected.left + expected.width}",
            )
            assertTrue(
                top >= expected.top - inset && bottom <= expected.top + expected.height + inset,
                "$label: glyphs vertically at $top..$bottom, span box ${expected.top}..${expected.top + expected.height}",
            )
        }
        assertEquals(visible.size, index, "$label: every visible glyph belongs to one span")
        return Deviation(horizontal, vertical)
    }

    /** The rendered page must match the reading image it was built from, at the same scale: no visible text or shift. */
    private fun assertVisibleSame(file: Path, pageIndex: Int, image: Path, dpi: Int, label: String = "page") {
        val rendered = render(file, pageIndex, dpi)
        val reference = javax.imageio.ImageIO.read(image.toFile())
        assertEquals(reference.width, rendered.width, "$label: rendered width at $dpi dpi")
        assertEquals(reference.height, rendered.height, "$label: rendered height at $dpi dpi")
        val difference = meanAbsoluteDifference(rendered, reference)
        // Mean alone dilutes a shifted sparse scan across the white page, so count the pixels that visibly differ too.
        // Resampled edges of vector content (the annotation) differ by about 0.05%; a one-point shift of the picture
        // changes 0.14% to 0.3% of pixels, so 0.1% separates the two.
        val changed = changedPixelFraction(rendered, reference)
        println(
            "Render vs reading image, $label @${dpi}dpi: mean absolute difference %.4f / 255, pixels changed by more than 32: %.6f".format(difference, changed),
        )
        assertTrue(difference < 2.0, "$label: the render drifted from the reading image by $difference")
        assertTrue(changed <= 0.001, "$label: $changed of the pixels differ from the reading image")
    }

    private fun meanAbsoluteDifference(a: BufferedImage, b: BufferedImage): Double {
        var total = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val pa = a.getRGB(x, y) and 0xFF
            val pb = b.getRGB(x, y) and 0xFF
            total += abs(pa - pb)
        }
        return total.toDouble() / (a.width.toLong() * a.height)
    }

    private fun changedPixelFraction(a: BufferedImage, b: BufferedImage): Double {
        var changed = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val pa = a.getRGB(x, y)
            val pb = b.getRGB(x, y)
            val differs = (0 until 3).any { channel -> abs(((pa shr (8 * channel)) and 0xFF) - ((pb shr (8 * channel)) and 0xFF)) > 32 }
            if (differs) changed++
        }
        return changed.toDouble() / (a.width.toLong() * a.height)
    }
}
