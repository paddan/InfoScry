package infoscry.image

import infoscry.domain.NormalizedBox
import kotlin.math.max
import kotlin.math.min

/**
 * One line of a page's text and where it sits, in the same normalized page coordinates as an image's box: fractions
 * of the displayed page (crop box, top-left origin, page rotation applied).
 */
data class TextLine(val text: String, val box: NormalizedBox)

/**
 * What the text of a page says about a picture on it: a caption derived from the lines next to it, and a bounded
 * excerpt of the other lines nearby. Both are indexed with the image unit, so a search on either finds the picture.
 *
 * The functions are pure and depend only on the lines handed in, so every page-text source (a PDF's text layer, the
 * structure blocks of a scanned page) shares one rule.
 */
object PageTextContext {

    /** How far a caption may sit from its picture, as a share of the page height. */
    const val CAPTION_GAP_SHARE: Double = 0.03

    /** How much of the narrower of the picture and a line must share horizontal extent for the line to sit beside the picture. */
    const val MIN_OVERLAP: Double = 0.5

    /** The most characters of surrounding text a unit carries beyond its caption. */
    const val MAX_CONTEXT_CHARS: Int = 600

    /** The most lines a caption joins. */
    const val MAX_CAPTION_LINES: Int = 3

    /** A line longer than this is a paragraph of body text (an OCR block, say), never part of a caption; it is still context. */
    const val MAX_CAPTION_LINE_CHARS: Int = 400

    /** A line starting with a figure label (`Fig. 2`, `Figur 3.`, `Bild 1`, `Foto:`, `Photo 7`, `Tab 4`) is preferred. */
    private val LABEL = Regex(
        "^(?:figure|figur|fig|bild|foto|photo|tab)(?=\\s*[\\d.:;,)\\u002D\\u2013\\u2014])",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [text] starts like the label of a figure, a photograph or a table. */
    fun isLabel(text: String): Boolean = LABEL.containsMatchIn(text.trim())

    /**
     * The caption of the picture in [image]: the contiguous lines directly below it (otherwise directly above),
     * at most [MAX_CAPTION_LINES], within [CAPTION_GAP_SHARE] of the page height and sharing at least [MIN_OVERLAP]
     * of the narrower width. A block that starts with a figure label wins over a plain adjacent one; with no label
     * anywhere, the block below is used before the block above. Null when no line is adjacent.
     */
    fun captionFor(image: NormalizedBox, lines: List<TextLine>): String? {
        val beside = lines.filter {
            it.text.isNotBlank() && it.text.length <= MAX_CAPTION_LINE_CHARS && sharesWidth(image, it.box)
        }
        val below = chainBelow(image, beside)
        val above = chainAbove(image, beside)
        val chosen = when {
            below.isNotEmpty() && isLabel(below.first().text) -> below
            above.isNotEmpty() && isLabel(above.first().text) -> above
            below.isNotEmpty() -> below
            else -> above
        }
        return chosen.joinToString(" ") { it.text }.collapsed().takeIf { it.isNotEmpty() }
    }

    /**
     * The other text nearest the picture in [image]: [lines] ordered by distance from it (vertical first, then
     * horizontal), without any line the [exclude]d caption holds, joined with spaces until [MAX_CONTEXT_CHARS], and cut
     * at a word boundary. Null when nothing is left.
     */
    fun contextFor(image: NormalizedBox, lines: List<TextLine>, exclude: String?): String? {
        val excluded = exclude?.collapsed()?.takeIf { it.isNotEmpty() }
        val ordered = lines
            .map { it to it.text.collapsed() }
            .filter { (_, text) -> text.isNotEmpty() && (excluded == null || !excluded.contains(text)) }
            .sortedWith(
                compareBy<Pair<TextLine, String>>(
                    { (line, _) -> verticalGap(image, line.box) },
                    { (line, _) -> horizontalGap(image, line.box) },
                    { (line, _) -> line.box.top },
                    { (line, _) -> line.box.left },
                ),
            )
        val out = StringBuilder()
        for ((_, text) in ordered) {
            val separator = if (out.isEmpty()) 0 else 1
            if (out.length + separator + text.length <= MAX_CONTEXT_CHARS) {
                if (separator == 1) out.append(' ')
                out.append(text)
                continue
            }
            val room = MAX_CONTEXT_CHARS - out.length - separator
            val cut = wordCut(text, room)
            if (cut.isNotEmpty()) {
                if (separator == 1) out.append(' ')
                out.append(cut)
            }
            break
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /** The longest prefix of [text] within [room] characters that ends on a whole word (a hard cut only for one giant word). */
    private fun wordCut(text: String, room: Int): String {
        if (room <= 0) return ""
        if (text.length <= room) return text
        if (text[room].isWhitespace()) return text.substring(0, room).trimEnd()
        val space = text.lastIndexOf(' ', room - 1)
        return when {
            space > 0 -> text.substring(0, space).trimEnd()
            else -> ""
        }
    }

    /** The lines directly below the picture, nearest first and in reading order. */
    private fun chainBelow(image: NormalizedBox, lines: List<TextLine>): List<TextLine> {
        val candidates = lines.filter { centerY(it.box) >= image.bottom }.sortedBy { it.box.top }
        val chain = ArrayList<TextLine>()
        var edge = image.bottom
        for (line in candidates) {
            if (chain.size == MAX_CAPTION_LINES) break
            if (line.box.top - edge > CAPTION_GAP_SHARE) break
            chain += line
            edge = max(edge, line.box.bottom)
        }
        return chain
    }

    /** The lines directly above the picture in reading order; a block with a figure label starts at the label. */
    private fun chainAbove(image: NormalizedBox, lines: List<TextLine>): List<TextLine> {
        val candidates = lines.filter { centerY(it.box) <= image.top }.sortedByDescending { it.box.bottom }
        val chain = ArrayList<TextLine>()
        var edge = image.top
        for (line in candidates) {
            if (chain.size == MAX_CAPTION_LINES) break
            if (edge - line.box.bottom > CAPTION_GAP_SHARE) break
            chain += line
            edge = min(edge, line.box.top)
        }
        // chain runs from the line nearest the picture upwards; a label line ends the caption at its top.
        val label = chain.indexOfFirst { isLabel(it.text) }
        val block = if (label >= 0) chain.subList(0, label + 1) else chain
        return block.reversed()
    }

    private fun sharesWidth(image: NormalizedBox, line: NormalizedBox): Boolean {
        val overlap = min(image.right, line.right) - max(image.left, line.left)
        val narrower = min(image.right - image.left, line.right - line.left)
        return overlap > 0.0 && overlap >= MIN_OVERLAP * narrower
    }

    private fun centerY(box: NormalizedBox): Double = (box.top + box.bottom) / 2.0

    private fun verticalGap(image: NormalizedBox, line: NormalizedBox): Double = when {
        line.top >= image.bottom -> line.top - image.bottom
        image.top >= line.bottom -> image.top - line.bottom
        else -> 0.0
    }

    private fun horizontalGap(image: NormalizedBox, line: NormalizedBox): Double = when {
        line.left >= image.right -> line.left - image.right
        image.left >= line.right -> image.left - line.right
        else -> 0.0
    }

    private fun String.collapsed(): String = trim().replace(Regex("\\s+"), " ")
}
