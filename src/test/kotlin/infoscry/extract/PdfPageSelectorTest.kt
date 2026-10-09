package infoscry.extract

import infoscry.ocr.OcrQualityScorer
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject

/**
 * The page-level decision on whether a PDF page must be read by OCR or an image model.
 *
 * The documents are built in memory with PDFBox, so each case states the exact picture and text it means
 * rather than depending on a committed fixture whose meaning would have to be looked up. The text-layer
 * cases assert their own preconditions first: a clean paragraph must be clean by [OcrQualityScorer] and
 * the garbled text must be garbled by it and still pass the extractor's usable-text test. Without those
 * checks a "read" could come from the text being empty or unusable rather than from the image, and the
 * test would prove nothing about the image measurement.
 */
class PdfPageSelectorTest {

    private val selector = PdfPageSelector()

    @Test
    fun `a page with long digital text and no image is not read`() {
        PDDocument().use { document ->
            pageWith(document, text = CLEAN_ENGLISH)
            assertCleanText(CLEAN_ENGLISH)

            assertEquals(emptyList(), selector.pagesToRead(document))
        }
    }

    @Test
    fun `a page with clean text and a small logo is not read`() {
        PDDocument().use { document ->
            pageWith(document, text = CLEAN_ENGLISH, pictures = listOf(LOGO))
            assertCleanText(CLEAN_ENGLISH)

            assertEquals(emptyList(), selector.pagesToRead(document))
        }
    }

    @Test
    fun `a page with poor text and an image covering thirty percent is read`() {
        PDDocument().use { document ->
            pageWith(document, text = GARBLED, pictures = listOf(WIDE_PICTURE))
            assertPoorText(GARBLED)

            assertTrue(selector.shouldRead(document, 1))
        }
    }

    @Test
    fun `a page with one image covering the whole page and no text is read`() {
        PDDocument().use { document ->
            pageWith(document, pictures = listOf(FULL_PAGE))

            assertTrue(selector.shouldRead(document, 1))
        }
    }

    @Test
    fun `a page with no text and no image is read`() {
        PDDocument().use { document ->
            pageWith(document)

            assertTrue(selector.shouldRead(document, 1))
        }
    }

    @Test
    fun `a poor text layer with an image inside a form covering forty percent is read`() {
        PDDocument().use { document ->
            pageWith(document, text = GARBLED, pictures = listOf(FORM_PICTURE))
            assertPoorText(GARBLED)

            assertTrue(selector.shouldRead(document, 1))
        }
    }

    @Test
    fun `a mixed document reports exactly the pages that need reading`() {
        PDDocument().use { document ->
            // Page 1: clean text with a logo, which is neither poor text nor a large picture.
            pageWith(document, text = CLEAN_ENGLISH, pictures = listOf(LOGO))
            // Page 2: no text layer and no image, which has nothing to trust and must be read.
            pageWith(document)
            // Page 3: poor text with a picture covering thirty percent, which must be read.
            pageWith(document, text = GARBLED, pictures = listOf(WIDE_PICTURE))

            assertEquals(listOf(2, 3), selector.pagesToRead(document))
        }
    }

    @Test
    fun `a large image on a page with clean English text is not read`() {
        PDDocument().use { document ->
            pageWith(document, text = CLEAN_ENGLISH, pictures = listOf(WIDE_PICTURE))
            assertCleanText(CLEAN_ENGLISH)

            assertFalse(selector.shouldRead(document, 1))
        }
    }

    @Test
    fun `a large image on a page with clean Swedish text is not read`() {
        PDDocument().use { document ->
            pageWith(document, text = CLEAN_SWEDISH, pictures = listOf(WIDE_PICTURE))
            assertCleanText(CLEAN_SWEDISH)

            assertFalse(selector.shouldRead(document, 1))
        }
    }

    @Test
    fun `a large image on a page with garbled text is read`() {
        PDDocument().use { document ->
            pageWith(document, text = GARBLED, pictures = listOf(WIDE_PICTURE))
            assertPoorText(GARBLED)

            assertTrue(selector.shouldRead(document, 1))
        }
    }

    /** Builds one A4 page with [text] at the top, and [pictures] placed from the bottom-left corner. */
    private fun pageWith(
        document: PDDocument,
        text: List<String> = emptyList(),
        pictures: List<Picture> = emptyList(),
    ): PDPage {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val width = PDRectangle.A4.width
        val height = PDRectangle.A4.height
        PDPageContentStream(document, page).use { content ->
            if (text.isNotEmpty()) {
                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                content.newLineAtOffset(72f, 760f)
                text.forEach { line ->
                    content.showText(line)
                    content.newLineAtOffset(0f, -18f)
                }
                content.endText()
            }
            pictures.forEach { picture ->
                val drawnWidth = width * picture.widthShare.toFloat()
                val drawnHeight = height * picture.heightShare.toFloat()
                if (picture.inForm) {
                    content.drawForm(formWith(document, drawnWidth, drawnHeight))
                } else {
                    content.drawImage(imageOf(document), 0f, 0f, drawnWidth, drawnHeight)
                }
            }
        }
        return page
    }

    /**
     * A form whose content draws one image across its own unit square, scaled to [width] by [height].
     *
     * The image is placed by the form's content rather than by the page, so the measurement has to follow
     * the form to find it; drawing the form from the page with an identity matrix leaves the image's
     * placement entirely to the form.
     */
    private fun formWith(document: PDDocument, width: Float, height: Float): PDFormXObject {
        val form = PDFormXObject(document)
        form.bBox = PDRectangle(PDRectangle.A4.width, PDRectangle.A4.height)
        val resources = PDResources()
        form.resources = resources
        val name = resources.add(imageOf(document))
        val content = "q $width 0 0 $height 0 0 cm /${name.name} Do Q"
        form.contentStream.createOutputStream().use { output ->
            output.write(content.toByteArray(Charsets.US_ASCII))
        }
        return form
    }

    private fun imageOf(document: PDDocument): PDImageXObject =
        LosslessFactory.createFromImage(document, BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB))

    private fun assertCleanText(lines: List<String>) {
        val text = lines.joinToString("\n")
        assertFalse(PdfPageCandidate(1, text).needsOcr, "the clean paragraph must pass the usable-text test")
        val score = OcrQualityScorer.score(text)
        assertTrue(score != null && score >= PdfPageSelector.MIN_TEXT_LAYER_QUALITY, "clean text scored $score")
    }

    private fun assertPoorText(lines: List<String>) {
        val text = lines.joinToString("\n")
        assertFalse(
            PdfPageCandidate(1, text).needsOcr,
            "the garbled text must pass the usable-text test, so the image is what decides the page",
        )
        val score = OcrQualityScorer.score(text)
        assertTrue(score != null && score < PdfPageSelector.MIN_TEXT_LAYER_QUALITY, "garbled text scored $score")
    }

    /** A picture covering [widthShare] by [heightShare] of the page, drawn directly or inside a form. */
    private data class Picture(val widthShare: Double, val heightShare: Double, val inForm: Boolean = false)

    private companion object {
        /** A logo: a tenth of the page. */
        val LOGO = Picture(widthShare = 0.5, heightShare = 0.2)

        /** A picture covering thirty percent of the page. */
        val WIDE_PICTURE = Picture(widthShare = 0.5, heightShare = 0.6)

        /** A picture covering the whole page. */
        val FULL_PAGE = Picture(widthShare = 1.0, heightShare = 1.0)

        /** A picture covering forty percent of the page, drawn from inside a form. */
        val FORM_PICTURE = Picture(widthShare = 0.8, heightShare = 0.5, inForm = true)

        val CLEAN_ENGLISH: List<String> = listOf(
            "The committee reviewed every transaction in the quarterly report and confirmed that the",
            "supporting documents were complete. Each payment is listed in the appendix with its date,",
            "amount and the name of the officer who approved it before the money was released.",
        )

        val CLEAN_SWEDISH: List<String> = listOf(
            "Protokollet sammanfattar överföringarna i ärendet och redovisar samtliga transaktioner i",
            "bilagan. Handlingarna är diarieförda och tillgängliga för granskning av den som har rätt",
            "att ta del av dem enligt bestämmelserna om allmänna handlingar och offentlighet.",
        )

        /**
         * Digit-mixed token noise: enough letters and digits to pass the usable-text test, but no word a reader
         * could take as text, so [OcrQualityScorer] scores it near zero.
         */
        val GARBLED: List<String> = listOf("xq7zt9 bk3m2w pl8nd4 vr6cj1 wq5fk0 mz2hp8 lt4gx7 dn9sb3")
    }
}
