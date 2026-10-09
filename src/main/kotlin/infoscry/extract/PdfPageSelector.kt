package infoscry.extract

import infoscry.ocr.OcrQualityScorer
import java.io.IOException
import kotlin.math.abs
import org.apache.pdfbox.contentstream.PDFStreamEngine
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.state.Concatenate
import org.apache.pdfbox.contentstream.operator.state.Restore
import org.apache.pdfbox.contentstream.operator.state.Save
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import org.apache.pdfbox.contentstream.operator.state.SetMatrix
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject

/**
 * Decides which pages of a PDF must be read by OCR or an image model, before anything is rendered.
 *
 * A page is read when its own text cannot be trusted and there is something on it that the text does not
 * describe. Two independent signals say the text cannot be trusted:
 *
 * - the text layer is missing, unreadable, or fails [PdfPageCandidate.needsOcr]; this is the same test the
 *   extractor applies, so the selector and the extractor agree on what "no usable text" means;
 * - the text layer is poor by [OcrQualityScorer] and a single image covers at least
 *   [MIN_IMAGE_COVERAGE] of the page. A large picture is the case where the page's content is the picture,
 *   and a text layer that is garbled cannot be taken as the page.
 *
 * A clean text layer is never sent on the strength of an image alone. A searchable scan carries a good
 * hidden OCR layer, and a photograph can carry real text; both are read correctly from their text, so
 * rendering them would spend OCR on pages that already say what they contain. That is why the text is
 * judged first, and the image is measured only for a page whose text is poor.
 *
 * Measuring is conservative. If a page's images cannot be measured, the page is read, because a failure to
 * measure must not turn into a page that was silently skipped. The only exception is a clean text layer,
 * where the decision does not depend on the image and so no measurement is attempted.
 */
class PdfPageSelector(private val pageText: PdfPageTextReader = PdfBoxPageTextReader) {

    /** 1-based pages of [document] that must be read. */
    fun pagesToRead(document: PDDocument): List<Int> =
        (1..document.numberOfPages).filter { page -> shouldRead(document, page) }

    /** Decision for one page (1-based). */
    fun shouldRead(document: PDDocument, page: Int): Boolean {
        require(page in 1..document.numberOfPages) {
            "page $page is outside 1..${document.numberOfPages}"
        }
        val text = try {
            pageText.read(document, page)
        } catch (failure: IOException) {
            // A text layer that cannot be read is no text layer at all, so the page is read.
            return true
        }
        if (PdfPageCandidate(page, text).needsOcr) return true
        // A null score means there is no text to judge, which counts as poor.
        val score = OcrQualityScorer.score(text)
        if (score != null && score >= MIN_TEXT_LAYER_QUALITY) return false
        return try {
            largestImageCoverage(document, page) >= MIN_IMAGE_COVERAGE
        } catch (failure: IOException) {
            // Failing to measure must not skip the page silently; reading it is the safe outcome.
            true
        }
    }

    /**
     * The share of the page covered by its largest single image.
     *
     * Only images placed with `Do` are measured. Inline images are deliberately not: they are small by
     * construction, and measuring them would need the inline-image parser for no change to the decision.
     */
    private fun largestImageCoverage(document: PDDocument, page: Int): Double {
        val pdPage = document.getPage(page - 1)
        val area = pageArea(pdPage)
        val measurer = ImageAreaMeasurer()
        measurer.processPage(pdPage)
        return measurer.largestImageArea / area
    }

    /**
     * The crop box's area, the box a reader shows and the one coverage is a share of.
     *
     * The media box is the fallback for a crop box that is empty. A page with no area at all cannot be
     * measured, and that is reported as a measurement failure so the caller reads it.
     */
    private fun pageArea(page: PDPage): Double {
        val box = page.cropBox.takeIf { it.width > 0f && it.height > 0f } ?: page.mediaBox
        val area = box.width.toDouble() * box.height.toDouble()
        if (area <= 0.0) throw IOException("the page has no area to measure images against")
        return area
    }

    /**
     * Walks a page's content and records the largest image placed on it, in user-space units.
     *
     * The stream engine is the only place that knows the current transformation matrix at the moment an
     * image is drawn, which is what decides where the image lands. The graphics-state operators are
     * registered so that the matrix is tracked through `q`, `Q`, `cm` and the other state changes, and
     * `Do` is intercepted rather than delegated because the default handler would render the image, which
     * a measurement must never do.
     */
    private class ImageAreaMeasurer : PDFStreamEngine() {

        /** The largest single image's placed area, in square user-space units. */
        var largestImageArea: Double = 0.0
            private set

        init {
            addOperator(Concatenate(this))
            addOperator(SetGraphicsStateParameters(this))
            addOperator(Save(this))
            addOperator(Restore(this))
            addOperator(SetMatrix(this))
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (operator.name != DO_OPERATOR) {
                super.processOperator(operator, operands)
                return
            }
            val name = operands.firstOrNull() as? COSName ?: return
            when (val xObject = resources?.getXObject(name)) {
                // An image's unit square is mapped by the matrix, so the determinant of the matrix is the
                // area the image covers. The absolute value makes a mirrored image measure the same.
                is PDImageXObject -> largestImageArea = maxOf(largestImageArea, placedArea())
                // A form is measured through its own content: images inside it are placed by the form's
                // matrix on top of the page's, and showForm carries that matrix down.
                is PDFormXObject -> showForm(xObject)
                else -> super.processOperator(operator, operands)
            }
        }

        private fun placedArea(): Double {
            val matrix = graphicsState.currentTransformationMatrix
            val a = matrix.scaleX.toDouble()
            val b = matrix.shearY.toDouble()
            val c = matrix.shearX.toDouble()
            val d = matrix.scaleY.toDouble()
            return abs(a * d - b * c)
        }

        private companion object {
            const val DO_OPERATOR = "Do"
        }
    }

    companion object {

        /**
         * The share of a page one image has to cover before the page's text is distrusted.
         *
         * A quarter of the page is large enough that the picture is the page's content, and small enough
         * that a logo or a stamp does not count. The number is a fixed rule, not a measurement of anything
         * in particular, and it is kept here so the tests and the decision share one source.
         */
        const val MIN_IMAGE_COVERAGE: Double = 0.25

        /**
         * The text-layer quality below which a large image is read rather than trusted.
         *
         * A page whose text scores at or above this is a readable page, whatever picture it also carries,
         * and is never sent to OCR on the strength of the picture.
         */
        const val MIN_TEXT_LAYER_QUALITY: Double = 75.0
    }
}
