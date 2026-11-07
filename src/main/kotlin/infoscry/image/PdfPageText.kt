package infoscry.image

import infoscry.domain.NormalizedBox
import java.io.IOException
import java.io.Writer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/**
 * The lines of a PDF page's text layer with where each sits, in the coordinates an image's box uses: fractions of the
 * displayed page, measured from the crop box, with the page's rotation applied and the origin at the top left.
 *
 * Only horizontal text (in the displayed orientation) is read; text set at another angle is not a candidate caption
 * or context. The amount read is bounded, so an enormous text layer cannot make a picture's page expensive, and
 * nothing here logs or reports text.
 */
internal object PdfPageText {

    /** The most lines kept for one page. */
    const val MAX_LINES: Int = 2_000

    /** The most characters of one line kept. */
    const val MAX_LINE_CHARS: Int = 1_000

    /** The most characters kept for one page. */
    const val MAX_PAGE_CHARS: Int = 200_000

    /** The lines of page number [pageIndex] (zero-based) of [document]; empty when the page has no text layer or it cannot be read. */
    fun linesOf(document: PDDocument, pageIndex: Int): List<TextLine> {
        val page = document.getPage(pageIndex)
        val crop = page.cropBox
        val rotated = Math.floorMod(page.rotation, 360).let { it == 90 || it == 270 }
        val width = (if (rotated) crop.height else crop.width).toDouble()
        val height = (if (rotated) crop.width else crop.height).toDouble()
        if (width <= 0.0 || height <= 0.0) return emptyList()
        val collector = Collector(width, height, Math.floorMod(page.rotation, 360).toFloat())
        return try {
            collector.setStartPage(pageIndex + 1)
            collector.setEndPage(pageIndex + 1)
            collector.writeText(document, Writer.nullWriter())
            collector.lines()
        } catch (unreadable: IOException) {
            emptyList()
        } catch (unreadable: RuntimeException) {
            // A content stream is untrusted; no failure of reading its text may escape.
            emptyList()
        }
    }

    private class Collector(
        private val width: Double,
        private val height: Double,
        private val upright: Float,
    ) : PDFTextStripper() {

        private val finished = ArrayList<TextLine>()
        private var characters = 0

        /** The words of the line being built, as boxes in points from the top left of the displayed page. */
        private val words = ArrayList<Word>()

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (finished.size >= MAX_LINES || characters >= MAX_PAGE_CHARS) return
            val word = wordOf(text, textPositions) ?: return
            val last = words.lastOrNull()
            if (last != null && !continues(last, word)) flush()
            words += word
        }

        override fun writeLineSeparator() {
            flush()
        }

        override fun endDocument(document: PDDocument) {
            flush()
        }

        fun lines(): List<TextLine> {
            flush()
            return finished
        }

        private fun wordOf(text: String, positions: List<TextPosition>): Word? {
            if (text.isBlank() || positions.isEmpty()) return null
            // Text that is not upright in the displayed page (its direction is the page's own rotation when it reads
            // left to right as shown) is not a line of text a caption can be read from.
            if (positions.any { it.dir != upright }) return null
            var left = Double.MAX_VALUE
            var right = -Double.MAX_VALUE
            var top = Double.MAX_VALUE
            var bottom = -Double.MAX_VALUE
            for (position in positions) {
                val x = position.xDirAdj.toDouble()
                val baseline = position.yDirAdj.toDouble()
                val glyphHeight = position.heightDir.toDouble()
                left = min(left, x)
                right = max(right, x + position.widthDirAdj.toDouble())
                top = min(top, baseline - glyphHeight)
                bottom = max(bottom, baseline)
            }
            return Word(text.trim(), left, top, right, bottom)
        }

        /** Whether [next] is on the line [last] ends: about the same baseline, to its right and close enough to be one phrase. */
        private fun continues(last: Word, next: Word): Boolean {
            val tolerance = 0.5 * max(last.bottom - last.top, next.bottom - next.top)
            if (abs(next.bottom - last.bottom) > tolerance) return false
            val gap = next.left - last.right
            return gap >= -tolerance && gap <= 4.0 * max(last.bottom - last.top, next.bottom - next.top)
        }

        private fun flush() {
            if (words.isEmpty()) return
            val text = words.joinToString(" ") { it.text }.take(MAX_LINE_CHARS)
            val box = boxOf(
                words.minOf { it.left }, words.minOf { it.top }, words.maxOf { it.right }, words.maxOf { it.bottom },
            )
            words.clear()
            if (box != null && finished.size < MAX_LINES && characters < MAX_PAGE_CHARS) {
                characters += text.length
                finished += TextLine(text, box)
            }
        }

        private fun boxOf(left: Double, top: Double, right: Double, bottom: Double): NormalizedBox? {
            val l = (left / width).coerceIn(0.0, 1.0)
            val r = (right / width).coerceIn(0.0, 1.0)
            val t = (top / height).coerceIn(0.0, 1.0)
            val b = (bottom / height).coerceIn(0.0, 1.0)
            return if (r > l && b > t) NormalizedBox(l, t, r, b) else null
        }
    }

    private class Word(val text: String, val left: Double, val top: Double, val right: Double, val bottom: Double)
}
