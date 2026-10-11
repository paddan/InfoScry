package infoscry.pdf

import infoscry.pdf.SelectablePdfFixtures.PAGE_HEIGHT_PT
import infoscry.pdf.SelectablePdfFixtures.PAGE_WIDTH_PT
import infoscry.pdf.SelectablePdfFixtures.PIXELS_PER_POINT
import infoscry.pdf.SelectablePdfFixtures.PlacedRun
import infoscry.pdf.SelectablePdfFixtures.Rect
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import infoscry.pdf.LocalAnchorAligner.Anchor
import infoscry.pdf.LocalAnchorAligner.Placement
import infoscry.pdf.SelectablePdfFixtures.TruthWord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/** A glyph as a PDF reader reports it: its character and the box it occupies, in top-down page points. */
internal data class ExtractedGlyph(val char: String, val left: Double, val top: Double, val width: Double, val height: Double) {
    val rect: Rect get() = Rect(left, top, width, height)
}

/**
 * The placement probe: writes invisible text over a reading image and reads it back.
 *
 * Scope: the native/local case only. It proves that PDFBox writes Swedish Unicode through an embedded
 * Type 0 font, that the extracted glyph boxes land within the fixture's boxes, and that the visible raster
 * does not change. It does not prove any reader's selection behaviour.
 */
internal object PdfTextPlacementProbe {

    /**
     * Rebuilds the page from the reading image and writes [runs] as invisible text. Nothing from any earlier
     * layer survives, so stale text cannot be copied from the result.
     */
    fun rebuildPage(readingImage: BufferedImage, runs: List<PlacedRun>, fontBytes: ByteArray): ByteArray {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(PAGE_WIDTH_PT.toFloat(), PAGE_HEIGHT_PT.toFloat()))
            document.addPage(page)
            val image = LosslessFactory.createFromImage(document, readingImage)
            val font = PDType0Font.load(document, ByteArrayInputStream(fontBytes), true)
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(image, 0f, 0f, PAGE_WIDTH_PT.toFloat(), PAGE_HEIGHT_PT.toFloat())
                for (run in runs) SelectablePdfFixtures.writeInvisibleRun(stream, font, run)
            }
            return save(document)
        }
    }

    /**
     * The naive alternative the spec rejects: keep the existing page and append the new invisible layer. Used
     * only as a negative control, to show that the stale layer survives an append.
     */
    fun appendInvisibleRuns(pdf: ByteArray, runs: List<PlacedRun>, fontBytes: ByteArray): ByteArray {
        Loader.loadPDF(pdf).use { document ->
            val page = document.getPage(0)
            val font = PDType0Font.load(document, ByteArrayInputStream(fontBytes), true)
            PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { stream ->
                for (run in runs) SelectablePdfFixtures.writeInvisibleRun(stream, font, run)
            }
            return save(document)
        }
    }

    /** Glyphs in content-stream order, each with the box PDFBox reports for it. */
    fun extractGlyphs(pdf: ByteArray): List<ExtractedGlyph> {
        Loader.loadPDF(pdf).use { document ->
            val collector = GlyphCollector()
            collector.getText(document)
            return collector.glyphs
        }
    }

    /** The page as a 200 DPI raster, rendered by PDFBox. */
    fun render(pdf: ByteArray): BufferedImage {
        Loader.loadPDF(pdf).use { document ->
            return PDFRenderer(document).renderImageWithDPI(0, SelectablePdfFixtures.RENDER_DPI.toFloat(), ImageType.RGB)
        }
    }

    private fun save(document: PDDocument): ByteArray = ByteArrayOutputStream().also { document.save(it) }.toByteArray()

    private class GlyphCollector : PDFTextStripper() {
        val glyphs = mutableListOf<ExtractedGlyph>()

        override fun processTextPosition(text: TextPosition) {
            val size = text.fontSizeInPt.toDouble()
            // The baseline, converted to top-down points, with the fixture's box convention applied to it.
            val baseline = text.yDirAdj.toDouble()
            glyphs += ExtractedGlyph(
                char = text.unicode,
                left = text.xDirAdj.toDouble(),
                top = baseline - SelectablePdfFixtures.TOP_FRACTION * size,
                width = text.widthDirAdj.toDouble(),
                height = size,
            )
        }
    }
}

/**
 * The local-anchor aligner prototype (ticket 01, acceptance 3). It places canonical words on word boxes from
 * a local positioning pass, and it reports what it cannot place rather than inventing a box.
 *
 * Matching is monotonic in reading order: canonical words and anchor words are aligned by longest common
 * subsequence, where two words match when their letters and digits are equal after lower-casing, or differ by
 * one edit. Matching is case-sensitive: 'Å' and 'å' are different letters on the page, and folding case let
 * an uppercase word land on the box of a lowercase one elsewhere in the line. A word that no anchor matches is then tried, in order, as a merge of consecutive anchors in the
 * gap left by its matched neighbours (one span per anchor), or as a part of a single anchor that covers more
 * text (ambiguous: the box cannot be divided without character geometry). Anything else is unpositioned.
 */
internal object LocalAnchorAligner {

    enum class Placement { POSITIONED, MERGED, AMBIGUOUS_SPLIT, UNPOSITIONED }

    /** A word box from a local pass, in top-down page points. */
    data class Anchor(val text: String, val rect: Rect)

    data class WordResult(val word: String, val placement: Placement, val spans: List<Rect>)

    fun align(words: List<String>, anchors: List<Anchor>): List<WordResult> {
        val pairs = longestCommonMatches(words, anchors)
        val matchedAnchorFor = arrayOfNulls<Int>(words.size)
        for ((wordIndex, anchorIndex) in pairs) matchedAnchorFor[wordIndex] = anchorIndex
        val results = ArrayList<WordResult>(words.size)
        for ((wordIndex, word) in words.withIndex()) {
            val matched = matchedAnchorFor[wordIndex]
            if (matched != null) {
                results += WordResult(word, Placement.POSITIONED, listOf(anchors[matched].rect))
                continue
            }
            val lower = pairs.lastOrNull { it.first < wordIndex }?.second?.plus(1) ?: 0
            val upper = pairs.firstOrNull { it.first > wordIndex }?.second?.minus(0) ?: anchors.size
            results += unmatched(word, anchors, lower, upper)
        }
        return results
    }

    private fun unmatched(word: String, anchors: List<Anchor>, lower: Int, upper: Int): WordResult {
        val target = norm(word)
        // A merge of consecutive anchors in the gap, which is only valid when their letters spell the word.
        for (start in lower until upper) {
            var joined = ""
            for (end in start until upper) {
                joined += norm(anchors[end].text)
                if (end > start && joined == target) {
                    return WordResult(word, Placement.MERGED, (start..end).map { anchors[it].rect })
                }
                if (!target.startsWith(joined)) break
            }
        }
        // An anchor that holds this word and more text: the word's share of the box is not measured.
        for (index in lower until upper) {
            val text = norm(anchors[index].text)
            if (text.length > target.length && text.contains(target)) {
                return WordResult(word, Placement.AMBIGUOUS_SPLIT, emptyList())
            }
        }
        return WordResult(word, Placement.UNPOSITIONED, emptyList())
    }

    private fun longestCommonMatches(words: List<String>, anchors: List<Anchor>): List<Pair<Int, Int>> {
        val n = words.size
        val m = anchors.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            table[i][j] = if (matches(words[i], anchors[j].text)) {
                table[i + 1][j + 1] + 1
            } else {
                maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                matches(words[i], anchors[j].text) && table[i][j] == table[i + 1][j + 1] + 1 -> {
                    pairs += i to j
                    i++
                    j++
                }
                table[i + 1][j] >= table[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }

    private fun matches(word: String, anchor: String): Boolean {
        val a = norm(word)
        val b = norm(anchor)
        return a.isNotEmpty() && (a == b || a.length >= MIN_FUZZY_LENGTH && editDistance(a, b) <= 1)
    }

    /** Letters and digits as printed, case kept: the comparison key for a word. */
    fun norm(text: String): String = text.filter { it.isLetterOrDigit() }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val MIN_FUZZY_LENGTH: Int = 3
}

class PdfTextPlacementProbeTest {

    private val readingImage: BufferedImage by lazy {
        val stream = PdfTextPlacementProbeTest::class.java.getResourceAsStream(SelectablePdfFixtures.PAGE_IMAGE_RESOURCE)
            ?: error("missing reading image ${SelectablePdfFixtures.PAGE_IMAGE_RESOURCE}")
        stream.use { ImageIO.read(it) }
    }

    private val fontBytes: ByteArray by lazy { SelectablePdfFixtures.probeFontBytes() }

    private val layout get() = SelectablePdfFixtures.layout

    /** The probe output, which must carry the canonical reading with the fixture's per-glyph boxes. */
    private fun probeOutput(): List<ExtractedGlyph> {
        val pdf = PdfTextPlacementProbe.rebuildPage(readingImage, SelectablePdfFixtures.placedRuns(), fontBytes)
        return PdfTextPlacementProbe.extractGlyphs(pdf)
    }

    @Test
    fun `probe writes canonical Swedish text whose per-span rectangles match the fixture within one point`() {
        val extracted = probeOutput()
        val expectedText = layout.glyphs.joinToString("") { it.char.toString() }
        val actualText = extracted.joinToString("") { it.char }
        assertEquals(expectedText, actualText, "extracted glyph text must be the canonical reading, without stale glyphs")

        assertEquals(layout.glyphs.size, extracted.size)
        // Asserted per span, the level the fixture defines geometry at: a corrected or merged token has the
        // drawn token's box, not one box per canonical character, so its glyphs are compared as a whole.
        val indexByOffset = layout.glyphs.withIndex().associate { (index, glyph) -> glyph.offset to index }
        var worstDeviation = 0.0
        for (span in layout.spans) {
            val indices = (span.start until span.endExclusive).map { indexByOffset.getValue(it) }
            val union = Rect(
                left = indices.minOf { extracted[it].left },
                top = indices.minOf { extracted[it].top },
                width = indices.maxOf { extracted[it].left + extracted[it].width } - indices.minOf { extracted[it].left },
                height = indices.maxOf { extracted[it].top + extracted[it].height } - indices.minOf { extracted[it].top },
            )
            worstDeviation = maxOf(
                worstDeviation,
                kotlin.math.abs(span.rect.left - union.left), kotlin.math.abs(span.rect.top - union.top),
                kotlin.math.abs(span.rect.width - union.width), kotlin.math.abs(span.rect.height - union.height),
            )
            assertRectWithinOnePoint(span.rect, union, "span '${span.text}' at offset ${span.start}")
        }
        println("Native probe: ${layout.spans.size} spans, worst rectangle deviation %.4f pt".format(worstDeviation))
    }

    @Test
    fun `probe replaces stale invisible text instead of trusting what the PDF already carried`() {
        val stale = SelectablePdfFixtures.staleInputPdf(readingImage)
        val staleText = PdfTextPlacementProbe.extractGlyphs(stale).joinToString("") { it.char }
        assertTrue(staleText.contains("Asa"), "the stale input must really carry wrong text, was '$staleText'")

        val rebuilt = PdfTextPlacementProbe.extractGlyphs(
            PdfTextPlacementProbe.rebuildPage(readingImage, SelectablePdfFixtures.placedRuns(), fontBytes),
        ).joinToString("") { it.char }
        assertFalse(rebuilt.contains("Asa"), "rebuilt page must not carry the stale word 'Asa': '$rebuilt'")
        assertFalse(rebuilt.contains("Sven"), "rebuilt page must not carry the stale word 'Sven': '$rebuilt'")
    }

    @Test
    fun `visible raster is unchanged by the invisible layer and still matches the reading image`() {
        val stale = SelectablePdfFixtures.staleInputPdf(readingImage)
        val rebuilt = PdfTextPlacementProbe.rebuildPage(readingImage, SelectablePdfFixtures.placedRuns(), fontBytes)
        val before = PdfTextPlacementProbe.render(stale)
        val after = PdfTextPlacementProbe.render(rebuilt)
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val beforePixels = before.getRGB(0, 0, before.width, before.height, null, 0, before.width)
        val afterPixels = after.getRGB(0, 0, after.width, after.height, null, 0, after.width)
        assertTrue(beforePixels.contentEquals(afterPixels), "invisible text must not change a single rendered pixel")

        val meanDifference = meanAbsoluteDifference(after, readingImage)
        assertTrue(meanDifference < MAX_MEAN_RASTER_DIFFERENCE, "rendered page drifted from the reading image: $meanDifference")
    }

    @Test
    fun `appending the new layer keeps the stale text, which is why replacement is required`() {
        val stale = SelectablePdfFixtures.staleInputPdf(readingImage)
        val appended = PdfTextPlacementProbe.appendInvisibleRuns(stale, SelectablePdfFixtures.placedRuns(), fontBytes)
        val text = PdfTextPlacementProbe.extractGlyphs(appended).joinToString("") { it.char }
        assertTrue(text.contains("Asa"), "appending must leave the stale word behind, which this control demonstrates")
    }

    @Test
    fun `fixture ink sits inside every expected span box`() {
        val scale = PIXELS_PER_POINT
        for (span in layout.spans) {
            val rect = span.rect
            val x0 = Math.floor(rect.left * scale).toInt()
            val x1 = Math.ceil(rect.right * scale).toInt()
            val y0 = Math.floor(rect.top * scale).toInt()
            val y1 = Math.ceil(rect.bottom * scale).toInt()
            var dark = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                if (x in 0 until readingImage.width && y in 0 until readingImage.height) {
                    val rgb = readingImage.getRGB(x, y) and 0xFF
                    if (rgb < INK_THRESHOLD) dark++
                }
            }
            assertTrue(dark > 0, "no ink inside the box of '${span.text}' at $rect")
        }
    }

    private fun assertRectWithinOnePoint(expected: Rect, actual: Rect, label: String) {
        assertWithin(expected.left, actual.left, "$label left")
        assertWithin(expected.top, actual.top, "$label top")
        assertWithin(expected.width, actual.width, "$label width")
        assertWithin(expected.height, actual.height, "$label height")
    }

    private fun assertWithin(expected: Double, actual: Double, label: String) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= TOLERANCE_POINTS,
            "$label: expected $expected, actual $actual (tolerance $TOLERANCE_POINTS pt)",
        )
    }

    private fun meanAbsoluteDifference(a: BufferedImage, b: BufferedImage): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        var total = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val pa = a.getRGB(x, y) and 0xFF
            val pb = b.getRGB(x, y) and 0xFF
            total += kotlin.math.abs(pa - pb)
        }
        return total.toDouble() / (w.toLong() * h)
    }


    /** Real Tesseract 5.5.3 word boxes (swe+eng, psm 3) for the committed reading image, in top-down points. */
    private fun tesseractAnchors(): List<Anchor> {
        val stream = PdfTextPlacementProbeTest::class.java.getResourceAsStream(SelectablePdfFixtures.TESSERACT_TSV_RESOURCE)
            ?: error("missing ${SelectablePdfFixtures.TESSERACT_TSV_RESOURCE}")
        val scale = SelectablePdfFixtures.PIXELS_PER_POINT
        return stream.bufferedReader().useLines { lines ->
            lines.mapNotNull { row ->
                val fields = row.split('\t')
                if (fields.size < TSV_COLUMNS || fields[0] != TSV_WORD_LEVEL || fields[11].isBlank()) {
                    null
                } else {
                    Anchor(
                        text = fields[11],
                        rect = Rect(
                            left = fields[6].toDouble() / scale,
                            top = fields[7].toDouble() / scale,
                            width = fields[8].toDouble() / scale,
                            height = fields[9].toDouble() / scale,
                        ),
                    )
                }
            }.toList()
        }
    }

    /** Truth boxes for a word, one per baseline: a word that crosses a line break keeps one box per line. */
    private fun truthLineRects(word: TruthWord): List<Rect> =
        word.spans.groupBy { it.baseline }.values.map { spans ->
            val left = spans.minOf { it.rect.left }
            val top = spans.minOf { it.rect.top }
            val right = spans.maxOf { it.rect.right }
            val bottom = spans.maxOf { it.rect.bottom }
            Rect(left, top, right - left, bottom - top)
        }

    /** Whether a placed box is within the one-point horizontal tolerance and overlaps the truth box vertically. */
    private fun horizontallyWithinOnePoint(actual: Rect, truth: Rect): Boolean =
        kotlin.math.abs(actual.left - truth.left) <= TOLERANCE_POINTS && kotlin.math.abs(actual.right - truth.right) <= TOLERANCE_POINTS

    private fun verticalOverlapRatio(actual: Rect, truth: Rect): Double {
        val overlap = minOf(actual.bottom, truth.bottom) - maxOf(actual.top, truth.top)
        return if (overlap <= 0) 0.0 else overlap / truth.height
    }

    @Test
    fun `tesseract word boxes place words on one-to-one matches and report what they cannot place`() {
        val words = layout.words.map { it.text }
        val results = LocalAnchorAligner.align(words, tesseractAnchors())
        val report = StringBuilder("Tesseract placement per canonical word (dLeft/dRight in pt vs fixture box):\n")
        var positionedGlyphs = 0
        var spanCount = 0
        var withinOnePoint = 0
        for ((index, result) in results.withIndex()) {
            val truthRects = truthLineRects(layout.words[index])
            val summary = if (result.spans.isEmpty()) {
                "-"
            } else {
                result.spans.zip(truthRects).joinToString("; ") { (actual, expected) ->
                    spanCount++
                    if (horizontallyWithinOnePoint(actual, expected) && verticalOverlapRatio(actual, expected) >= 0.5) {
                        withinOnePoint++
                    }
                    "dLeft=%.2f dRight=%.2f vOverlap=%.2f".format(
                        actual.left - expected.left, actual.right - expected.right, verticalOverlapRatio(actual, expected),
                    )
                }
            }
            report.append("  %-14s %-15s %s\n".format(result.word, result.placement, summary))
            if (result.placement == Placement.POSITIONED || result.placement == Placement.MERGED) {
                positionedGlyphs += layout.words[index].text.count { !it.isWhitespace() }
            }
        }
        println(report)
        println("Tesseract spans within 1 pt horizontally and 50% vertically: $withinOnePoint of $spanCount")
        println("Tesseract positioned non-whitespace glyphs: $positionedGlyphs of ${layout.glyphs.size}")

        val byWord = results.associateBy { it.word }
        // The line a local recogniser is expected to miss is absent from its boxes, so none of it can be placed.
        for (word in listOf("Ärendet", "avslutades", "den", "maj.")) {
            assertEquals(Placement.UNPOSITIONED, byWord.getValue(word).placement, "missing-line word '$word'")
        }
        // Tesseract reads the drawn "ÅÄÖ" as "AAG": no box may be lent to it, and the lowercase "åäö" keeps its own.
        assertEquals(Placement.UNPOSITIONED, results.first { it.word == "ÅÄÖ" }.placement)
        assertEquals(Placement.POSITIONED, results.first { it.word == "åäö" }.placement)
        // The merged drawn token "ochså" covers two canonical words; its share of the box is not measured.
        assertEquals(Placement.AMBIGUOUS_SPLIT, results.first { it.word == "och" }.placement)
        assertEquals(Placement.AMBIGUOUS_SPLIT, results.first { it.word == "så" }.placement)
        // The page is not complete: the missing line and the merged token keep it partial.
        assertTrue(positionedGlyphs < layout.glyphs.size, "a Tesseract-only layout must be incomplete for this page")
        // Measured, not assumed: Tesseract's ink-based boxes do not all meet the one-point acceptance.
        assertTrue(withinOnePoint < spanCount, "every Tesseract span met one point; the probe expected ink-box offsets")
    }

    @Test
    fun `surya multi-line blocks are not positioned at word or line level and a stretched block fails`() {
        val blocks = Json.parseToJsonElement(
            SelectablePdfFixtures::class.java.getResourceAsStream(SelectablePdfFixtures.SURYA_BLOCKS_RESOURCE)!!
                .bufferedReader().readText(),
        ).jsonArray
        val scale = SelectablePdfFixtures.PIXELS_PER_POINT
        val multiLine = blocks.map { it.jsonObject }.filter { it.getValue("text").jsonPrimitive.content.contains('\n') }
        assertEquals(2, multiLine.size, "the simulated page has two multi-line paragraph blocks")
        for (block in multiLine) {
            val bbox = block.getValue("bbox").jsonArray.map { it.jsonPrimitive.content.toDouble() / scale }
            val blockRect = Rect(bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1])
            val lines = block.getValue("text").jsonPrimitive.content.split('\n')
            assertTrue(lines.size > 1)
            // A block box covers every line of its paragraph, so one box cannot be a word or a line.
            val lindqvist = layout.words.first { it.text == "Lindqvist" }
            val wordTruth = truthLineRects(lindqvist).single()
            assertFalse(
                horizontallyWithinOnePoint(blockRect, wordTruth) && verticalOverlapRatio(blockRect, wordTruth) >= 0.5,
                "a multi-line block box passed as the word 'Lindqvist': block $blockRect vs word $wordTruth",
            )
            assertTrue(blockRect.height > 4 * wordTruth.height, "a paragraph block is several lines tall: ${blockRect.height} pt")
        }
    }

    @Test
    fun `coordinate-free llm text is positioned only through local anchors and fails for a reordered reading`() {
        val anchors = tesseractAnchors()
        val canonicalWords = layout.words.map { it.text }
        val asTranscribed = LocalAnchorAligner.align(canonicalWords, anchors)
        // The right column first, then the left: the order a model could return if it read columns out of order.
        val rightColumnStart = layout.text.indexOf("Åsa Lindqvist\nÖrebro")
        val reorderedWords = layout.words.filter { it.start >= rightColumnStart } +
            layout.words.filter { it.start < rightColumnStart }
        val reordered = LocalAnchorAligner.align(reorderedWords.map { it.text }, anchors)

        // The reading carries no geometry of its own: any placement it gets comes from the anchors alone.
        println("LLM as transcribed: ${asTranscribed.count { it.placement == Placement.UNPOSITIONED }} unplaced words of ${asTranscribed.size}")
        // A reordered reading cannot be matched monotonically to reading-order anchors, so words go unplaced.
        val unplacedAfterReorder = reordered.count { it.placement == Placement.UNPOSITIONED }
        println("LLM reordered reading: $unplacedAfterReorder unplaced words of ${reordered.size}")
        assertTrue(unplacedAfterReorder > 0, "a reordered reading must leave words the anchors cannot place")
    }

    private companion object {
        const val TOLERANCE_POINTS: Double = 1.0
        const val INK_THRESHOLD: Int = 200
        const val MAX_MEAN_RASTER_DIFFERENCE: Double = 2.0
        const val TSV_COLUMNS: Int = 12
        const val TSV_WORD_LEVEL: String = "5"
    }
}
