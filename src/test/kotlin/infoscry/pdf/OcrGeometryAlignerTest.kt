package infoscry.pdf

import infoscry.domain.DocumentId
import infoscry.ocr.BlockDraft
import infoscry.ocr.BlockKind
import infoscry.ocr.CellDraft
import infoscry.ocr.OcrWordBox
import infoscry.ocr.PageImage
import infoscry.ocr.PageStructureDraft
import infoscry.ocr.StructureDerivation
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ticket 03: local canonical-text positioning. Expected rectangles below are literal numbers written from the
 * anchor boxes as drawn (or from the Tesseract and simulated Surya fixtures), never read back from the aligner.
 * Canonical text is compared by SHA-256 computed here, not by the production hash.
 */
class OcrGeometryAlignerTest {

    private val drawnImage = PageImage(
        documentId = DocumentId("document-1"),
        unitId = "unit-1",
        ordinal = 0,
        imageRoot = Path.of("images"),
        imageReference = "page-0.png",
        artifactRoot = Path.of("artifacts"),
        sha256 = ALIGN_IMAGE_SHA,
        width = 1000,
        height = 800,
        renderDpi = 200,
        rotationDegrees = 0,
    )

    private val probeImage = drawnImage.copy(width = 1700, height = 2200)

    private fun input(
        text: String,
        vararg anchors: OcrWordBox,
        image: PageImage = drawnImage,
    ) = GeometryAlignmentInput(
        canonicalText = text,
        image = image,
        geometry = ALIGN_GEOMETRY,
        transform = ALIGN_TRANSFORM,
        anchors = anchors.toList(),
        positioningVersion = "local-anchor-1",
    )

    private fun drawn(text: String, left: Int, top: Int, width: Int, height: Int) =
        OcrWordBox(text, left, top, width, height)

    private fun rect(left: Double, top: Double, width: Double, height: Double) =
        TextRectangle(left = left, top = top, width = width, height = height)

    private fun span(start: Int, endExclusive: Int, order: Int, rectangle: TextRectangle) =
        PositionedTextSpan(start, endExclusive, rectangle, SpanGranularity.WORD, order)

    private fun assertToken(
        tokens: List<TokenPlacement>,
        index: Int,
        text: String,
        kind: PlacementKind,
        anchors: List<Int> = emptyList(),
        rectangle: TextRectangle? = null,
    ) {
        val token = tokens[index]
        assertEquals(text, token.text, "token $index text")
        assertEquals(kind, token.kind, "token $index ('$text') kind")
        assertEquals(anchors, token.anchors, "token $index ('$text') anchor indices")
        if (rectangle == null) assertNull(token.rectangle, "token $index ('$text') has no rectangle")
        else assertEquals(rectangle, token.rectangle, "token $index ('$text') rectangle")
    }

    private fun sha(text: String) = oracleSha256(text)

    // Acceptance 1: Swedish normalization survives matching byte-for-byte, with a reversible offset map.

    @Test
    fun `swedish reading matches exactly and keeps its canonical hash`() {
        val text = "Åsa Lindqvist\nbetalade ÅÄÖ åäö"
        val layout = OcrGeometryAligner.align(
            input(
                text,
                drawn("Åsa", 10, 10, 40, 20),
                drawn("Lindqvist", 60, 10, 90, 20),
                drawn("betalade", 10, 40, 80, 20),
                drawn("ÅÄÖ", 100, 40, 60, 20),
                drawn("åäö", 170, 40, 50, 20),
            ),
        )
        assertTrue(layout.complete, "every visible token is placed: ${layout.reasonCode}")
        assertEquals(sha(text), layout.textSha256)
        assertEquals(
            listOf(
                span(0, 3, 0, rect(10.0, 10.0, 40.0, 20.0)),
                span(4, 13, 1, rect(60.0, 10.0, 90.0, 20.0)),
                span(14, 22, 2, rect(10.0, 40.0, 80.0, 20.0)),
                span(23, 26, 3, rect(100.0, 40.0, 60.0, 20.0)),
                span(27, 30, 4, rect(170.0, 40.0, 50.0, 20.0)),
            ),
            layout.spans,
        )
        assertEquals("ÅÄÖ", text.substring(layout.spans[3].start, layout.spans[3].endExclusive))
    }

    @Test
    fun `decomposed swedish letters match the precomposed drawn word and keep the decomposed reading`() {
        // "A" + U+030A is canonical Å written in decomposed form. Matching normalizes; the reading does not.
        val text = "Åsa Lindqvist"
        val layout = OcrGeometryAligner.align(
            input(text, drawn("Åsa", 10, 10, 40, 20), drawn("Lindqvist", 60, 10, 90, 20)),
        )
        assertTrue(layout.complete, "decomposed Å matches the precomposed box: ${layout.reasonCode}")
        assertEquals(sha(text), layout.textSha256, "the hash covers the decomposed reading as written")
        assertEquals(span(0, 4, 0, rect(10.0, 10.0, 40.0, 20.0)), layout.spans[0], "the span covers all four code units")
    }

    @Test
    fun `match key maps each key character back to its canonical range`() {
        val key = MatchKey.of("Åsa.")
        assertEquals("Åsa", key.text, "the key drops the period and composes the ring")
        assertEquals(0, key.canonicalStartOf(0))
        assertEquals(2, key.canonicalEndOf(0), "Å is the two code units A and U+030A")
        assertEquals(2, key.canonicalStartOf(1))
        assertEquals(3, key.canonicalEndOf(1))
        assertEquals(3, key.canonicalStartOf(2))
        assertEquals(4, key.canonicalEndOf(2))
    }

    @Test
    fun `match key expands a ligature into letters that all map to the one ligature`() {
        val key = MatchKey.of("ﬁnns")
        assertEquals("finns", key.text, "U+FB01 normalizes to fi for matching")
        assertEquals(0, key.canonicalStartOf(0))
        assertEquals(1, key.canonicalEndOf(0))
        assertEquals(0, key.canonicalStartOf(1), "the second letter of the ligature maps to the same unit")
        assertEquals(1, key.canonicalEndOf(1))
        assertEquals(1, key.canonicalStartOf(2))
    }

    @Test
    fun `ring-less drawn letters do not match canonical swedish letters`() {
        // ÅÄÖ is read as AAG by the recogniser: three edits away, so no box is lent to it.
        val layout = OcrGeometryAligner.align(input("ÅÄÖ", drawn("AAG", 10, 10, 40, 20)))
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
        assertTrue(layout.spans.isEmpty())
        assertToken(resolve("ÅÄÖ", drawn("AAG", 10, 10, 40, 20)).tokens, 0, "ÅÄÖ", PlacementKind.UNPLACED)
    }

    // Acceptance 2: corrected spelling and numbers, ligatures, whitespace, hyphenation, split and merged words.

    @Test
    fun `corrected spelling and number take the drawn box of the word they correct`() {
        val text = "Summa Lindkvist 120 kronor"
        val anchors = arrayOf(
            drawn("Summa", 10, 10, 50, 20),
            drawn("Lindqvist", 70, 10, 90, 20),
            drawn("l20", 170, 10, 30, 20),
            drawn("kronor", 210, 10, 60, 20),
        )
        val resolution = resolve(text, *anchors)
        assertToken(resolution.tokens, 0, "Summa", PlacementKind.EXACT, listOf(0), rect(10.0, 10.0, 50.0, 20.0))
        assertToken(resolution.tokens, 1, "Lindkvist", PlacementKind.FUZZY, listOf(1), rect(70.0, 10.0, 90.0, 20.0))
        assertToken(resolution.tokens, 2, "120", PlacementKind.FUZZY, listOf(2), rect(170.0, 10.0, 30.0, 20.0))
        assertToken(resolution.tokens, 3, "kronor", PlacementKind.EXACT, listOf(3), rect(210.0, 10.0, 60.0, 20.0))
        val layout = OcrGeometryAligner.align(input(text, *anchors))
        assertTrue(layout.complete, "every corrected token is placed: ${layout.reasonCode}")
        assertEquals(4, layout.spans.size)
    }

    @Test
    fun `ligature and non-breaking space and tab keep canonical ranges and drawn boxes`() {
        // Canonical: "Det" NBSP "ﬁnns" TAB "kvar". The ligature is one canonical unit; the drawn word is "finns".
        val text = "Det ﬁnns\tkvar"
        val layout = OcrGeometryAligner.align(
            input(
                text,
                drawn("Det", 0, 0, 30, 20),
                drawn("finns", 40, 0, 50, 20),
                drawn("kvar", 100, 0, 40, 20),
            ),
        )
        assertTrue(layout.complete, "ligature and whitespace are placed: ${layout.reasonCode}")
        assertEquals(
            listOf(
                span(0, 3, 0, rect(0.0, 0.0, 30.0, 20.0)),
                span(4, 8, 1, rect(40.0, 0.0, 50.0, 20.0)),
                span(9, 13, 2, rect(100.0, 0.0, 40.0, 20.0)),
            ),
            layout.spans,
        )
        assertEquals("ﬁnns", text.substring(4, 8), "the canonical ligature is kept as written")
    }

    @Test
    fun `line-end hyphen on the drawn fragment matches the canonical stem and keeps its box`() {
        val layout = OcrGeometryAligner.align(
            input(
                "Den stor\nkostnad",
                drawn("Den", 0, 0, 30, 20),
                drawn("stor-", 40, 0, 40, 20),
                drawn("kostnad", 0, 25, 80, 20),
            ),
        )
        assertTrue(layout.complete, "the hyphen is not part of the match key: ${layout.reasonCode}")
        assertEquals(span(4, 8, 1, rect(40.0, 0.0, 40.0, 20.0)), layout.spans[1])
    }

    @Test
    fun `split word divides one drawn box between two canonical words by character proportion`() {
        // Canonical "och" and "så" are one drawn box "ochså" of width 70: 3 of 5 characters are "och".
        val resolution = resolve(
            "Hej och så Tack",
            drawn("Hej", 0, 0, 30, 20),
            drawn("ochså", 40, 0, 70, 20),
            drawn("Tack", 120, 0, 50, 20),
        )
        assertToken(resolution.tokens, 0, "Hej", PlacementKind.EXACT, listOf(0), rect(0.0, 0.0, 30.0, 20.0))
        assertToken(resolution.tokens, 1, "och", PlacementKind.SPLIT, listOf(1), rect(40.0, 0.0, 42.0, 20.0))
        assertToken(resolution.tokens, 2, "så", PlacementKind.SPLIT, listOf(1), rect(82.0, 0.0, 28.0, 20.0))
        assertToken(resolution.tokens, 3, "Tack", PlacementKind.EXACT, listOf(2), rect(120.0, 0.0, 50.0, 20.0))
        val layout = OcrGeometryAligner.align(
            input("Hej och så Tack", drawn("Hej", 0, 0, 30, 20), drawn("ochså", 40, 0, 70, 20), drawn("Tack", 120, 0, 50, 20)),
        )
        assertTrue(layout.complete)
        assertEquals(span(4, 7, 1, rect(40.0, 0.0, 42.0, 20.0)), layout.spans[1])
        assertEquals(span(8, 10, 2, rect(82.0, 0.0, 28.0, 20.0)), layout.spans[2])
    }

    @Test
    fun `merged word is one span over the union of two same-line drawn boxes`() {
        // Drawn "bet" and "alade" on one line, two units apart: one canonical word "betalade".
        val resolution = resolve(
            "Hej betalade Tack",
            drawn("Hej", 0, 0, 30, 20),
            drawn("bet", 40, 0, 30, 20),
            drawn("alade", 72, 0, 50, 20),
            drawn("Tack", 130, 0, 50, 20),
        )
        assertToken(resolution.tokens, 1, "betalade", PlacementKind.MERGED, listOf(1, 2), rect(40.0, 0.0, 82.0, 20.0))
        assertToken(resolution.tokens, 2, "Tack", PlacementKind.EXACT, listOf(3), rect(130.0, 0.0, 50.0, 20.0))
    }

    @Test
    fun `hyphenated word joined across a line break becomes two word spans, each on its own drawn box`() {
        // Canonical "storkostnad" is drawn as "stor-" on one line and "kostnad" on the next. No union rectangle
        // is built over the gap between the lines: the word splits at the boundary of the two anchors' text.
        val text = "Den storkostnad var"
        val resolution = resolve(
            text,
            drawn("Den", 0, 0, 30, 20),
            drawn("stor-", 40, 0, 40, 20),
            drawn("kostnad", 0, 25, 80, 20),
            drawn("var", 90, 25, 30, 20),
        )
        assertToken(resolution.tokens, 0, "Den", PlacementKind.EXACT, listOf(0), rect(0.0, 0.0, 30.0, 20.0))
        assertToken(resolution.tokens, 1, "storkostnad", PlacementKind.LINE_BREAK_SPLIT, listOf(1, 2))
        assertEquals(
            listOf(
                TokenPiece(start = 4, endExclusive = 8, anchor = 1, rectangle = rect(40.0, 0.0, 40.0, 20.0)),
                TokenPiece(start = 8, endExclusive = 15, anchor = 2, rectangle = rect(0.0, 25.0, 80.0, 20.0)),
            ),
            resolution.tokens[1].pieces,
        )
        assertToken(resolution.tokens, 2, "var", PlacementKind.EXACT, listOf(3), rect(90.0, 25.0, 30.0, 20.0))
        val layout = OcrGeometryAligner.align(
            input(text, drawn("Den", 0, 0, 30, 20), drawn("stor-", 40, 0, 40, 20), drawn("kostnad", 0, 25, 80, 20), drawn("var", 90, 25, 30, 20)),
        )
        assertTrue(layout.complete, "both halves of the split word are placed: ${layout.reasonCode}")
        assertEquals(sha(text), layout.textSha256)
        assertEquals(
            listOf(
                span(0, 3, 0, rect(0.0, 0.0, 30.0, 20.0)),
                span(4, 8, 1, rect(40.0, 0.0, 40.0, 20.0)),
                span(8, 15, 2, rect(0.0, 25.0, 80.0, 20.0)),
                span(16, 19, 3, rect(90.0, 25.0, 30.0, 20.0)),
            ),
            layout.spans,
        )
        assertEquals("stor", text.substring(4, 8))
        assertEquals("kostnad", text.substring(8, 15))
    }

    @Test
    fun `a same-line pair with a gap beyond the merge range is neither merged nor split across lines`() {
        // "bet" and "alade" share a line but sit 130 units apart: not one word, and not a line-break join either.
        val resolution = resolve(
            "Hej betalade Tack",
            drawn("Hej", 0, 0, 30, 20),
            drawn("bet", 40, 0, 30, 20),
            drawn("alade", 200, 0, 50, 20),
            drawn("Tack", 260, 0, 50, 20),
        )
        assertToken(resolution.tokens, 1, "betalade", PlacementKind.UNPLACED)
        assertToken(resolution.tokens, 2, "Tack", PlacementKind.EXACT, listOf(3), rect(260.0, 0.0, 50.0, 20.0))
        val layout = OcrGeometryAligner.align(
            input(
                "Hej betalade Tack",
                drawn("Hej", 0, 0, 30, 20),
                drawn("bet", 40, 0, 30, 20),
                drawn("alade", 200, 0, 50, 20),
                drawn("Tack", 260, 0, 50, 20),
            ),
        )
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
    }

    // Acceptance 3: repeated anchors, absent lines, insertions and reordered columns are never accepted on score.

    @Test
    fun `repeated word with an extra drawn copy is not guessed onto either copy`() {
        // The reading names Anna twice; the image has an extra "Anna" after Berg that the reading does not name.
        val resolution = resolve(
            "Anna Berg Anna",
            drawn("Anna", 0, 0, 40, 20),
            drawn("Berg", 50, 0, 40, 20),
            drawn("Anna", 100, 0, 40, 20),
            drawn("Anna", 150, 0, 40, 20),
        )
        assertToken(resolution.tokens, 0, "Anna", PlacementKind.EXACT, listOf(0), rect(0.0, 0.0, 40.0, 20.0))
        assertToken(resolution.tokens, 1, "Berg", PlacementKind.EXACT, listOf(1), rect(50.0, 0.0, 40.0, 20.0))
        assertToken(resolution.tokens, 2, "Anna", PlacementKind.UNPLACED)
        val layout = OcrGeometryAligner.align(
            input(
                "Anna Berg Anna",
                drawn("Anna", 0, 0, 40, 20),
                drawn("Berg", 50, 0, 40, 20),
                drawn("Anna", 100, 0, 40, 20),
                drawn("Anna", 150, 0, 40, 20),
            ),
        )
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
    }

    @Test
    fun `absent line leaves its words unplaced and the page incomplete`() {
        val text = "Hej Anna\nDet här saknas helt\nTack Berg"
        val resolution = resolve(
            text,
            drawn("Hej", 0, 0, 30, 20),
            drawn("Anna", 40, 0, 40, 20),
            drawn("Tack", 0, 100, 40, 20),
            drawn("Berg", 50, 100, 40, 20),
        )
        assertToken(resolution.tokens, 0, "Hej", PlacementKind.EXACT, listOf(0), rect(0.0, 0.0, 30.0, 20.0))
        assertToken(resolution.tokens, 2, "Det", PlacementKind.UNPLACED)
        assertToken(resolution.tokens, 3, "här", PlacementKind.UNPLACED)
        assertToken(resolution.tokens, 4, "saknas", PlacementKind.UNPLACED)
        assertToken(resolution.tokens, 5, "helt", PlacementKind.UNPLACED)
        assertToken(resolution.tokens, 6, "Tack", PlacementKind.EXACT, listOf(2), rect(0.0, 100.0, 40.0, 20.0))
        val layout = OcrGeometryAligner.align(
            input(text, drawn("Hej", 0, 0, 30, 20), drawn("Anna", 40, 0, 40, 20), drawn("Tack", 0, 100, 40, 20), drawn("Berg", 50, 100, 40, 20)),
        )
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
        assertTrue(layout.spans.isEmpty())
    }

    @Test
    fun `insertion without a visual counterpart is incomplete and borrows no neighbour's box`() {
        val resolution = resolve(
            "Summa Totalt 1250",
            drawn("Summa", 0, 0, 40, 20),
            drawn("1250", 50, 0, 40, 20),
        )
        assertToken(resolution.tokens, 0, "Summa", PlacementKind.EXACT, listOf(0), rect(0.0, 0.0, 40.0, 20.0))
        assertToken(resolution.tokens, 1, "Totalt", PlacementKind.UNPLACED)
        assertToken(resolution.tokens, 2, "1250", PlacementKind.EXACT, listOf(1), rect(50.0, 0.0, 40.0, 20.0))
        val layout = OcrGeometryAligner.align(
            input("Summa Totalt 1250", drawn("Summa", 0, 0, 40, 20), drawn("1250", 50, 0, 40, 20)),
        )
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
    }

    @Test
    fun `reordered columns are ambiguous and no word is placed`() {
        // The reading names the right column first; the engine's order puts the left column first.
        val text = "Carl Dahl Anna Berg"
        val anchors = arrayOf(
            drawn("Anna", 0, 0, 40, 20),
            drawn("Berg", 0, 25, 40, 20),
            drawn("Carl", 200, 0, 40, 20),
            drawn("Dahl", 200, 25, 40, 20),
        )
        val resolution = resolve(text, *anchors)
        assertTrue(resolution.tokens.all { it.kind == PlacementKind.UNPLACED }, "no reordered word is placed")
        val layout = OcrGeometryAligner.align(input(text, *anchors))
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
        assertTrue(layout.spans.isEmpty())
    }

    @Test
    fun `fuzzy-only candidates are never accepted on edit distance alone`() {
        // "Anna" is one edit from both "Anne" and "Anns" and exactly equal to neither: a tie, not a placement.
        val resolution = resolve(
            "Hej Anna Tack",
            drawn("Hej", 0, 0, 30, 20),
            drawn("Anne", 40, 0, 40, 20),
            drawn("Anns", 90, 0, 40, 20),
            drawn("Tack", 140, 0, 40, 20),
        )
        assertToken(resolution.tokens, 1, "Anna", PlacementKind.UNPLACED)
        assertToken(resolution.tokens, 2, "Tack", PlacementKind.EXACT, listOf(3), rect(140.0, 0.0, 40.0, 20.0))
    }

    @Test
    fun `real tesseract boxes place only the words they read, and the page stays incomplete`() {
        val text = swedishCanonical()
        val anchors = tesseractAnchors()
        val resolution = resolve(text, *anchors.toTypedArray(), image = probeImage)
        val tokens = resolution.tokens
        assertEquals(31, tokens.size, "the canonical reading has 31 visible words")
        // Anchor indices follow the TSV's word-level row order (see the fixture file).
        assertToken(tokens, 0, "Åsa", PlacementKind.UNPLACED)
        assertToken(tokens, 1, "Lindqvist", PlacementKind.EXACT, listOf(1), rect(270.0, 306.0, 117.0, 36.0))
        assertToken(tokens, 2, "betalade", PlacementKind.EXACT, listOf(2), rect(205.0, 369.0, 108.0, 26.0))
        assertToken(tokens, 3, "120", PlacementKind.EXACT, listOf(3), rect(331.0, 369.0, 41.0, 25.0))
        assertToken(tokens, 4, "kronor", PlacementKind.EXACT, listOf(4), rect(389.0, 368.0, 87.0, 26.0))
        assertToken(tokens, 5, "till", PlacementKind.EXACT, listOf(5), rect(204.0, 431.0, 36.0, 25.0))
        assertToken(tokens, 6, "Åsa.", PlacementKind.UNPLACED)
        assertToken(tokens, 7, "Den", PlacementKind.EXACT, listOf(7), rect(203.0, 490.0, 50.0, 31.0))
        assertToken(tokens, 8, "storkostnad", PlacementKind.LINE_BREAK_SPLIT, listOf(8, 9))
        assertToken(tokens, 9, "var", PlacementKind.EXACT, listOf(10), rect(324.0, 563.0, 43.0, 14.0))
        assertToken(tokens, 10, "hög.", PlacementKind.EXACT, listOf(11), rect(385.0, 553.0, 50.0, 33.0))
        for (index in 11..15) assertToken(tokens, index, tokens[index].text, PlacementKind.UNPLACED)
        assertToken(tokens, 16, "Åsa", PlacementKind.UNPLACED)
        assertToken(tokens, 17, "Lindqvist", PlacementKind.EXACT, listOf(13), rect(976.0, 306.0, 117.0, 36.0))
        assertToken(tokens, 18, "Örebro", PlacementKind.EXACT, listOf(14), rect(910.0, 361.0, 87.0, 35.0))
        assertToken(tokens, 19, "2", PlacementKind.EXACT, listOf(15), rect(1018.0, 374.0, 15.0, 19.0))
        assertToken(tokens, 20, "maj", PlacementKind.EXACT, listOf(16), rect(1051.0, 372.0, 46.0, 30.0))
        assertToken(tokens, 21, "och", PlacementKind.SPLIT, listOf(17), rect(910.0, 431.0, 43.8, 25.0))
        assertToken(tokens, 22, "så", PlacementKind.SPLIT, listOf(17), rect(953.8, 431.0, 29.2, 25.0))
        assertToken(tokens, 23, "åäö", PlacementKind.EXACT, listOf(18), rect(1001.0, 434.0, 49.0, 21.0))
        assertToken(tokens, 24, "ÅÄÖ", PlacementKind.UNPLACED)
        assertToken(tokens, 25, "Summa", PlacementKind.EXACT, listOf(20), rect(911.0, 490.0, 90.0, 29.0))
        assertToken(tokens, 26, "1250", PlacementKind.EXACT, listOf(21), rect(1022.0, 495.0, 67.0, 22.0))
        assertToken(tokens, 27, "Total", PlacementKind.EXACT, listOf(22), rect(913.0, 553.0, 67.0, 27.0))
        assertToken(tokens, 28, "amount:", PlacementKind.EXACT, listOf(23), rect(993.0, 556.0, 104.0, 22.0))
        assertToken(tokens, 29, "1250", PlacementKind.EXACT, listOf(24), rect(1118.0, 556.0, 66.0, 22.0))
        assertToken(tokens, 30, "SEK", PlacementKind.EXACT, listOf(25), rect(1203.0, 551.0, 61.0, 29.0))
        println("ticket03 probe page comparisons=${resolution.comparisons} budget=${OcrGeometryAligner.MAX_COMPARISONS}")
        assertTrue(resolution.comparisons in 1L..(OcrGeometryAligner.MAX_COMPARISONS / 10), "probe page work stays far below the budget")

        val layout = OcrGeometryAligner.align(input(text, *anchors.toTypedArray(), image = probeImage))
        assertFalse(layout.complete, "the missing line, the misread Åsa and ÅÄÖ keep the page incomplete")
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
        assertTrue(layout.spans.isEmpty())
        assertEquals(sha(text), layout.textSha256, "the full canonical reading is retained")
    }

    // Acceptance 4: Surya multi-line blocks are never words or lines and never a spread of the page text.

    @Test
    fun `multi-line block anchors are never placed as words or lines`() {
        val blocks = surynBlocks()
        val text = swedishCanonical()
        val resolution = resolve(text, *blocks.toTypedArray(), image = probeImage)
        assertTrue(resolution.tokens.all { it.kind == PlacementKind.UNPLACED }, "no word takes a block")
        val layout = OcrGeometryAligner.align(input(text, *blocks.toTypedArray(), image = probeImage))
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
        assertTrue(layout.spans.isEmpty(), "no block rectangle reaches the layout")
    }

    @Test
    fun `a block beside a real word box places only the single word and never the block`() {
        val blocks = surynBlocks()
        val total = drawn("Total", 913, 553, 67, 27)
        val text = swedishCanonical()
        val resolution = resolve(text, *(blocks + total).toTypedArray(), image = probeImage)
        // Anchor indices 0..2 are the three blocks; index 3 is the single word box.
        val totalToken = resolution.tokens.first { it.text == "Total" }
        assertToken(
            resolution.tokens,
            resolution.tokens.indexOf(totalToken),
            "Total",
            PlacementKind.EXACT,
            listOf(3),
            rect(913.0, 553.0, 67.0, 27.0),
        )
        assertTrue(
            resolution.tokens.all { token -> token.anchors.none { it in 0..2 } },
            "no word is placed on a block box",
        )
        val layout = OcrGeometryAligner.align(input(text, *(blocks + total).toTypedArray(), image = probeImage))
        assertFalse(layout.complete)
        assertTrue(layout.spans.all { it.granularity == SpanGranularity.WORD }, "no LINE span is ever produced")
    }

    // Acceptance 5: bounded work and memory, with a safe incomplete result on exhaustion.

    @Test
    fun `canonical text at the character limit is aligned and one more character returns a limit result`() {
        val atLimit = "ord ".repeat(OcrGeometryAligner.MAX_CANONICAL_UTF16 / 4)
        assertEquals(OcrGeometryAligner.MAX_CANONICAL_UTF16, atLimit.length)
        val atLimitLayout = OcrGeometryAligner.align(input(atLimit))
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, atLimitLayout.reasonCode, "at the limit it is aligned, not refused")
        assertEquals(sha(atLimit), atLimitLayout.textSha256)

        val over = atLimit + "x"
        val overLayout = OcrGeometryAligner.align(input(over))
        assertFalse(overLayout.complete)
        assertEquals(OcrGeometryAligner.REASON_LIMIT_EXCEEDED, overLayout.reasonCode)
        assertTrue(overLayout.spans.isEmpty())
        assertEquals(sha(over), overLayout.textSha256, "the full canonical reading is never truncated")
    }

    @Test
    fun `more anchors than the limit returns a limit result with the reading intact`() {
        val text = "Åsa"
        val anchors = (0 until OcrGeometryAligner.MAX_ANCHORS + 1).map { index ->
            drawn("Åsa", index % 900, (index / 900) * 10, 10, 10)
        }
        val layout = OcrGeometryAligner.align(input(text, *anchors.toTypedArray()))
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_LIMIT_EXCEEDED, layout.reasonCode)
        assertEquals(sha(text), layout.textSha256)
    }

    @Test
    fun `work beyond the comparison budget returns a limit result while a smaller page is aligned`() {
        val largeText = (1..1500).joinToString(" ") { "alpha$it" }
        val largeAnchors = (1..1500).map { drawn("omega$it", (it % 100) * 9, (it / 100) * 9, 8, 8) }
        val large = OcrGeometryAligner.align(input(largeText, *largeAnchors.toTypedArray()))
        assertEquals(OcrGeometryAligner.REASON_LIMIT_EXCEEDED, large.reasonCode, "1500 x 1500 exceeds the budget")
        assertEquals(sha(largeText), large.textSha256)
        assertTrue(large.spans.isEmpty())

        val smallText = (1..300).joinToString(" ") { "alpha$it" }
        val smallAnchors = (1..300).map { drawn("omega$it", (it % 100) * 9, (it / 100) * 9, 8, 8) }
        val small = OcrGeometryAligner.align(input(smallText, *smallAnchors.toTypedArray()))
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, small.reasonCode, "300 x 300 stays within the budget")
    }

    @Test
    fun `a drawn box reaching past the image edge is unusable and never throws`() {
        // Recognizers can round a box one pixel past the raster. Such a box is not placed, and the reading is kept.
        val text = "Summa Totalt"
        val layout = OcrGeometryAligner.align(
            input(text, drawn("Summa", 990, 0, 20, 20)),
        )
        assertFalse(layout.complete)
        assertEquals(OcrGeometryAligner.REASON_AMBIGUOUS, layout.reasonCode)
        assertEquals(sha(text), layout.textSha256)
    }

    @Test
    fun `alignment reads no file, so a missing image root cannot change the result`() {
        val missing = drawnImage.copy(imageRoot = Path.of("/this/path/does/not/exist/infoscry-ticket-03"))
        val layout = OcrGeometryAligner.align(input("Åsa", drawn("Åsa", 10, 10, 40, 20), image = missing))
        assertTrue(layout.complete)
    }

    // Acceptance 6: identical immutable input gives identical mapping and hashes.

    @Test
    fun `repeated alignment of the same input gives the same mapping and the same hashes`() {
        val text = swedishCanonical()
        val anchors = tesseractAnchors().toTypedArray()
        val first = OcrGeometryAligner.align(input(text, *anchors, image = probeImage))
        val second = OcrGeometryAligner.align(input(text, *anchors, image = probeImage))
        val firstJson = Json.encodeToString(PageTextLayout.serializer(), first)
        val secondJson = Json.encodeToString(PageTextLayout.serializer(), second)
        assertEquals(firstJson, secondJson)
        assertEquals(sha(firstJson), sha(secondJson))
        assertEquals(resolve(text, *anchors, image = probeImage).tokens, resolve(text, *anchors, image = probeImage).tokens)
    }

    // Structure: blank readings verify, and only WORD spans ever leave the aligner.

    @Test
    fun `blank reading is a verified complete blank with no spans`() {
        val text = " \n\t "
        val layout = OcrGeometryAligner.align(input(text, drawn("Åsa", 10, 10, 40, 20)))
        assertTrue(layout.complete)
        assertTrue(layout.verifiedBlank)
        assertEquals(sha(text), layout.textSha256)
    }

    // Structure order: the canonical text is the structure's derived text, so spans follow cell order.

    @Test
    fun `table page spans follow cell order row by row and the layout is complete`() {
        val derived = StructureDerivation.derive(
            PageStructureDraft(
                parser = "test",
                blocks = listOf(
                    BlockDraft(BlockKind.PARAGRAPH, "Redovisning"),
                    BlockDraft(
                        BlockKind.TABLE,
                        "",
                        rows = listOf(
                            listOf(CellDraft("Namn", header = true), CellDraft("Belopp", header = true)),
                            listOf(CellDraft("Åsa", header = false), CellDraft("120", header = false)),
                            listOf(CellDraft("Bo", header = false), CellDraft("95", header = false)),
                        ),
                    ),
                ),
            ),
            ALIGN_IMAGE_SHA,
        )
        val text = derived.extractedText
        assertEquals("Redovisning\nNamn\tBelopp\nÅsa\t120\nBo\t95", text)

        // Word boxes as a local positioning pass reads them: visual order, which here is row by row.
        val layout = OcrGeometryAligner.align(
            input(
                text,
                drawn("Redovisning", 10, 10, 200, 20),
                drawn("Namn", 10, 40, 60, 20),
                drawn("Belopp", 120, 40, 80, 20),
                drawn("Åsa", 10, 70, 50, 20),
                drawn("120", 120, 70, 40, 20),
                drawn("Bo", 10, 100, 40, 20),
                drawn("95", 120, 100, 30, 20),
            ),
        )
        assertTrue(layout.complete, "every table word is placed: ${layout.reasonCode}")
        assertEquals(sha(text), layout.textSha256)

        val cellWords = listOf("Redovisning", "Namn", "Belopp", "Åsa", "120", "Bo", "95")
        assertEquals(cellWords, layout.spans.map { text.substring(it.start, it.endExclusive) })
        assertEquals((0 until cellWords.size).toList(), layout.spans.map { it.order }, "order follows the reading")
        assertEquals(layout.spans.map { it.start }.sorted(), layout.spans.map { it.start }, "starts run in reading order")

        // Every table cell's words are the spans whose start lies inside that cell's extracted range.
        val cells = derived.structure.blocks.single { it.kind == BlockKind.TABLE }.rows!!.flatMap { it.cells }
        val cellOfSpan = layout.spans.map { span -> cells.indexOfFirst { span.start in it.extracted.start until it.extracted.endExclusive } }
        assertEquals(-1, cellOfSpan.first(), "the heading paragraph is not a cell")
        assertEquals(listOf(0, 1, 2, 3, 4, 5), cellOfSpan.drop(1), "each table word lies in its cell, in cell order")
    }

    @Test
    fun `surya table separators do not change token placement or completeness`() {
        // A Surya table block is flattened with tabs between cells and newlines between rows; the aligner
        // tokenizes on any whitespace, so a space-separated reading of the same words must place identically.
        val tabbed = "Namn\tBelopp\nÅsa\t120"
        val spaced = "Namn Belopp Åsa 120"
        val anchors = arrayOf(
            drawn("Namn", 10, 40, 60, 20),
            drawn("Belopp", 120, 40, 80, 20),
            drawn("Åsa", 10, 70, 50, 20),
            drawn("120", 120, 70, 40, 20),
        )
        val fromTabs = OcrGeometryAligner.align(input(tabbed, *anchors))
        val fromSpaces = OcrGeometryAligner.align(input(spaced, *anchors))
        assertTrue(fromTabs.complete, "tab and newline separated table is complete: ${fromTabs.reasonCode}")
        assertTrue(fromSpaces.complete, "space separated words are complete: ${fromSpaces.reasonCode}")
        assertEquals(fromSpaces.spans.map { it.rectangle }, fromTabs.spans.map { it.rectangle })
        assertEquals(listOf("Namn", "Belopp", "Åsa", "120"), fromTabs.spans.map { tabbed.substring(it.start, it.endExclusive) })
    }

    @Test
    fun `a page without structure keeps the layout it had before structure order`() {
        // Pinned literal output for a plain reading (no structure input exists on the aligner): the same
        // rectangles, orders and offsets as before tables had cell order.
        val text = "Åsa betalade\nLindqvist 95"
        val layout = OcrGeometryAligner.align(
            input(
                text,
                drawn("Åsa", 10, 10, 40, 20),
                drawn("betalade", 60, 10, 80, 20),
                drawn("Lindqvist", 10, 40, 90, 20),
                drawn("95", 110, 40, 30, 20),
            ),
        )
        assertTrue(layout.complete)
        assertNull(layout.reasonCode)
        assertEquals(
            listOf(
                span(0, 3, 0, rect(10.0, 10.0, 40.0, 20.0)),
                span(4, 12, 1, rect(60.0, 10.0, 80.0, 20.0)),
                span(13, 22, 2, rect(10.0, 40.0, 90.0, 20.0)),
                span(23, 25, 3, rect(110.0, 40.0, 30.0, 20.0)),
            ),
            layout.spans,
        )
    }

    // Fixtures and helpers.

    private fun resolve(text: String, vararg anchors: OcrWordBox, image: PageImage = drawnImage): AlignmentResolution =
        OcrGeometryAligner.resolve(input(text, *anchors, image = image))

    private fun swedishCanonical(): String = readResource(ALIGN_CANONICAL_RESOURCE)

    /** Level-5 word rows of the Tesseract 5.5.3 TSV (swe+eng, psm 3), in the engine's own row order. */
    private fun tesseractAnchors(): List<OcrWordBox> =
        readResource(ALIGN_TSV_RESOURCE).lineSequence()
            .map { it.split('\t') }
            .filter { it.size >= 12 && it[0] == "5" && it[11].isNotBlank() }
            .map { drawn(it[11], it[6].toInt(), it[7].toInt(), it[8].toInt(), it[9].toInt()) }
            .toList()

    /** The simulated Surya page: three multi-line paragraph blocks, each a whole block of text. */
    private fun surynBlocks(): List<OcrWordBox> {
        val array: JsonArray = Json.parseToJsonElement(readResource(ALIGN_SURYA_RESOURCE)).jsonArray
        return array.map { element ->
            val block = element.jsonObject
            val bbox = block.getValue("bbox").jsonArray.map { it.jsonPrimitive.content.toInt() }
            OcrWordBox(
                text = block.getValue("text").jsonPrimitive.content,
                left = bbox[0],
                top = bbox[1],
                width = bbox[2] - bbox[0],
                height = bbox[3] - bbox[1],
            )
        }
    }

    private fun readResource(path: String): String =
        OcrGeometryAlignerTest::class.java.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("missing fixture $path")

    private companion object {
        const val ALIGN_CANONICAL_RESOURCE = "/pdf/alignment/swedish-page-canonical.txt"
        const val ALIGN_TSV_RESOURCE = "/pdf/alignment/swedish-page-tesseract-swe-eng-psm3.tsv"
        const val ALIGN_SURYA_RESOURCE = "/pdf/alignment/swedish-page-surya-blocks-simulated.json"
        val ALIGN_IMAGE_SHA: String = oracleSha256("ticket 03 drawn page")
        val ALIGN_GEOMETRY = PdfPageGeometry(0.0, 0.0, 500.0, 400.0, 0.0, 0.0, 500.0, 400.0, 0, 1.0)
        val ALIGN_TRANSFORM = ImageToPdf(a = 0.5, b = 0.0, c = 0.0, d = -0.5, e = 0.0, f = 400.0)
    }
}
