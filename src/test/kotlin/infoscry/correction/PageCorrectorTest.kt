package infoscry.correction

import infoscry.domain.CollectionId
import infoscry.domain.ReadingText
import infoscry.extract.TextNormalizer
import infoscry.ocr.BlockKind
import infoscry.ocr.PageStructure
import infoscry.ocr.PageStructureStore
import infoscry.ocr.STRUCTURE_VERSION
import infoscry.ocr.StructureBlock
import infoscry.ocr.TableCell
import infoscry.ocr.TableRow
import infoscry.ocr.TextRange
import infoscry.pdf.ImageToPdf
import infoscry.pdf.PageTextLayout
import infoscry.pdf.PageTextLayoutStore
import infoscry.pdf.PdfPageGeometry
import infoscry.pdf.PositionedTextSpan
import infoscry.pdf.SpanGranularity
import infoscry.pdf.TextRectangle
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageCorrectorTest {

    private val root: Path = Files.createTempDirectory("infoscry-page-corrector")

    @AfterTest
    fun removeRoot() {
        root.toFile().deleteRecursively()
    }

    private fun corrector(vararg rules: String): PageCorrector = PageCorrector(
        CorrectionMatcher(
            rules.mapIndexed { index, text ->
                val (find, replace) = text.split(" → ")
                CorrectionRule("r$index", CollectionId("c"), find, replace, false, "t", "t")
            },
        ),
    )

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun reading(text: String) = TextNormalizer.normalize(text).let { ReadingText(it.extracted, it.search) }

    private fun block(kind: BlockKind, order: Int, start: Int, end: Int, rows: List<TableRow>? = null, level: Int? = null) =
        StructureBlock(kind, level, order, null, TextRange(start, end), TextRange(start, end), rows)

    private fun structure(text: String, vararg blocks: StructureBlock) =
        PageStructureStore.write(root, 0, PageStructure(STRUCTURE_VERSION, sha(text), "a".repeat(64), "test/1", blocks.toList()))

    private fun span(start: Int, end: Int, order: Int, granularity: SpanGranularity = SpanGranularity.LINE) =
        PositionedTextSpan(start, end, TextRectangle(1.0, 1.0 + order * 10, 50.0, 8.0), granularity, order)

    private fun layout(text: String, vararg spans: PositionedTextSpan) = PageTextLayoutStore.write(
        root,
        PageTextLayout(
            version = 1,
            textSha256 = sha(text),
            imageSha256 = "b".repeat(64),
            width = 100,
            height = 100,
            geometry = PdfPageGeometry(0.0, 0.0, 100.0, 100.0, 0.0, 0.0, 100.0, 100.0, 0, 1.0),
            transform = ImageToPdf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            spans = spans.toList(),
            complete = true,
            reasonCode = null,
            positioningVersion = "test-1",
        ),
    )

    @Test
    fun `an unchanged page keeps its references and has no reading`() {
        val text = "Brev i dag"
        val structureRef = structure(text, block(BlockKind.PARAGRAPH, 0, 0, text.length))
        val layoutRef = layout(text, span(0, text.length, 0))

        val page = corrector("spinners → Spinnars").correct(reading(text), 0, structureRef, layoutRef, root)

        assertSame(structureRef, page.structureReference)
        assertSame(layoutRef, page.layoutReference)
        assertNull(page.reading)
        assertEquals(text, page.extractedText)
        assertEquals(emptyMap(), page.matchesByRule)
    }

    @Test
    fun `a corrected page keeps its reading`() {
        val page = corrector("spinners → Spinnars").correct(reading("Brev från spinners"), 0, null, null, root)

        assertEquals("Brev från Spinnars", page.extractedText)
        assertEquals("Brev från Spinnars", page.searchText)
        assertEquals(reading("Brev från spinners"), page.reading)
        assertEquals(mapOf("r0" to 1), page.matchesByRule)
    }

    @Test
    fun `structure ranges move with the text`() {
        val text = "Hos spinners\n\nBrev i dag"
        val ref = structure(text, block(BlockKind.HEADING, 0, 0, 12, level = 1), block(BlockKind.PARAGRAPH, 1, 14, 24))

        val page = corrector("spinners → Spinnarsson").correct(reading(text), 0, ref, null, root)

        val read = assertNotNull(PageStructureStore.read(root, assertNotNull(page.structureReference), page.extractedText))
        assertEquals(TextRange(0, 15), read.blocks[0].extracted)
        assertEquals(TextRange(17, 27), read.blocks[1].extracted)
        assertEquals(TextRange(17, 27), read.blocks[1].search)
        assertEquals(false, read.degraded)
    }

    @Test
    fun `table cell ranges move with the text`() {
        val text = "spinners malmo"
        val row = TableRow(
            listOf(
                TableCell(header = false, extracted = TextRange(0, 8), search = TextRange(0, 8)),
                TableCell(header = false, extracted = TextRange(9, 14), search = TextRange(9, 14)),
            ),
        )
        val ref = structure(text, block(BlockKind.TABLE, 0, 0, 14, rows = listOf(row)))

        val page = corrector("spinners → Spinnarsson").correct(reading(text), 0, ref, null, root)

        val read = assertNotNull(PageStructureStore.read(root, assertNotNull(page.structureReference), page.extractedText))
        val cells = read.blocks.single().rows!!.single().cells
        assertEquals(listOf(TextRange(0, 11), TextRange(12, 17)), cells.map { it.extracted })
    }

    @Test
    fun `a phrase across two blocks falls back to degraded paragraphs`() {
        val text = "Hos spinners\nParagraf här"
        val ref = structure(text, block(BlockKind.HEADING, 0, 0, 12, level = 1), block(BlockKind.PARAGRAPH, 1, 13, 25))

        val page = corrector("spinners Paragraf → X").correct(reading(text), 0, ref, null, root)

        assertEquals("Hos X här", page.extractedText)
        val read = assertNotNull(PageStructureStore.read(root, assertNotNull(page.structureReference), page.extractedText))
        assertTrue(read.degraded)
        assertTrue(read.blocks.all { it.kind == BlockKind.PARAGRAPH })
    }

    @Test
    fun `layout spans move with the text`() {
        val text = "Hos spinners"
        val ref = layout(text, span(0, 3, 0, SpanGranularity.WORD), span(4, 12, 1, SpanGranularity.WORD))

        val page = corrector("spinners → Spinnarsson").correct(reading(text), 0, null, ref, root)

        val read = PageTextLayoutStore.read(root, assertNotNull(page.layoutReference), page.extractedText)
        assertEquals(listOf(0 to 3, 4 to 15), read.spans.map { it.start to it.endExclusive })
    }

    @Test
    fun `a phrase across two spans goes into the first`() {
        val text = "Karl\nJohan"
        val ref = layout(text, span(0, 4, 0), span(5, 10, 1))

        val page = corrector("Karl Johan → Carl Johansson Berg").correct(reading(text), 0, null, ref, root)

        assertEquals("Carl Johansson Berg", page.extractedText)
        val read = PageTextLayoutStore.read(root, assertNotNull(page.layoutReference), page.extractedText)
        assertEquals(listOf(0 to 19), read.spans.map { it.start to it.endExclusive })
    }

    @Test
    fun `a word span that gains a space becomes a line span`() {
        val text = "spinners"
        val ref = layout(text, span(0, 8, 0, SpanGranularity.WORD))

        val page = corrector("spinners → Spinnars Berg").correct(reading(text), 0, null, ref, root)

        val read = PageTextLayoutStore.read(root, assertNotNull(page.layoutReference), page.extractedText)
        assertEquals(SpanGranularity.LINE, read.spans.single().granularity)
    }

    @Test
    fun `the search text is always the normalized corrected text`() {
        val page = corrector("spinners → Spinnars").correct(reading("hos spinners\r\nmed\u0007klocka"), 0, null, null, root)

        assertEquals(TextNormalizer.normalize(page.extractedText).search, page.searchText)
    }
}
