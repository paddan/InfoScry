package infoscry.ocr

import infoscry.extract.TextNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructureDerivationTest {

    private val imageSha = "b".repeat(64)

    private fun derive(vararg blocks: BlockDraft, degraded: Boolean = false) =
        StructureDerivation.derive(PageStructureDraft("test/1", blocks.toList(), degraded), imageSha)

    private fun assertInvariant(page: DerivedPage) {
        assertEquals(TextNormalizer.normalize(page.extractedText).search, page.searchText)
        for (block in page.structure.blocks) {
            val normalized = TextNormalizer.normalize(page.extractedText.substring(block.extracted.start, block.extracted.endExclusive))
            assertEquals(normalized.extracted, page.extractedText.substring(block.extracted.start, block.extracted.endExclusive))
            assertEquals(normalized.search, page.searchText.substring(block.search.start, block.search.endExclusive))
            for (cell in block.rows.orEmpty().flatMap { it.cells }) {
                val cellText = page.extractedText.substring(cell.extracted.start, cell.extracted.endExclusive)
                assertEquals(TextNormalizer.normalize(cellText).extracted, cellText)
                assertEquals(TextNormalizer.normalize(cellText).search, page.searchText.substring(cell.search.start, cell.search.endExclusive))
            }
        }
        page.structure.verifiedAgainst(page.extractedText)
    }

    @Test
    fun `blocks join with newline and table cells with tab and rows with newline`() {
        val page = derive(
            BlockDraft(BlockKind.HEADING, "Title", level = 1),
            BlockDraft(
                BlockKind.TABLE, "",
                rows = listOf(
                    listOf(CellDraft("A", true), CellDraft("B", true)),
                    listOf(CellDraft("1", false), CellDraft("2", false)),
                ),
            ),
            BlockDraft(BlockKind.PARAGRAPH, "End"),
        )
        assertEquals("Title\nA\tB\n1\t2\nEnd", page.extractedText)
        assertEquals(3, page.structure.blocks.size)
        assertEquals(2, page.structure.blocks[1].rows!!.size)
        assertFalse(page.degraded)
        assertInvariant(page)
    }

    @Test
    fun `ranges slice back to the normalized block and cell text`() {
        val page = derive(
            BlockDraft(BlockKind.PARAGRAPH, "one"),
            BlockDraft(BlockKind.TABLE, "", rows = listOf(listOf(CellDraft("x", false), CellDraft("", false), CellDraft("z", false)))),
        )
        val table = page.structure.blocks[1]
        val cells = table.rows!!.single().cells
        assertEquals("x", page.searchText.substring(cells[0].search.start, cells[0].search.endExclusive))
        assertEquals("", page.searchText.substring(cells[1].search.start, cells[1].search.endExclusive))
        assertInvariant(page)
    }

    @Test
    fun `CRLF in a cell and decomposed text keep the invariant`() {
        val decomposed = "été"
        val page = derive(
            BlockDraft(BlockKind.PARAGRAPH, "line1\r\nline2"),
            BlockDraft(BlockKind.PARAGRAPH, decomposed),
            BlockDraft(BlockKind.TABLE, "", rows = listOf(listOf(CellDraft("c1\r\nc2", false), CellDraft(decomposed, false)))),
        )
        assertTrue(page.searchText.contains("été"))
        assertFalse(page.searchText.contains('\r'))
        assertEquals(TextNormalizer.normalize(page.extractedText).search, page.searchText)
        assertInvariant(page)
        assertFalse(page.degraded)
    }

    @Test
    fun `a blank block is dropped and order stays dense`() {
        val page = derive(
            BlockDraft(BlockKind.PARAGRAPH, "a"),
            BlockDraft(BlockKind.FIGURE, "  \n "),
            BlockDraft(BlockKind.PARAGRAPH, "b"),
        )
        assertEquals("a\nb", page.extractedText)
        assertEquals(listOf(0, 1), page.structure.blocks.map { it.order })
    }

    @Test
    fun `a degraded draft gives a degraded page`() {
        val page = derive(BlockDraft(BlockKind.PARAGRAPH, "a"), degraded = true)
        assertTrue(page.degraded)
        assertTrue(page.structure.degraded, "the artifact must carry the flag page review reads")
        assertFalse(derive(BlockDraft(BlockKind.PARAGRAPH, "a")).structure.degraded)
    }

    @Test
    fun `an empty draft gives empty text and no blocks`() {
        val page = derive()
        assertEquals("", page.extractedText)
        assertEquals("", page.searchText)
        assertTrue(page.structure.blocks.isEmpty())
        assertFalse(page.degraded)
    }

    @Test
    fun `level and latex are kept only where they belong`() {
        val page = derive(
            BlockDraft(BlockKind.HEADING, "H", level = 9),
            BlockDraft(BlockKind.PARAGRAPH, "P", level = 2, latex = "x"),
            BlockDraft(BlockKind.FORMULA, "f", latex = "\\alpha"),
        )
        assertEquals(6, page.structure.blocks[0].level)
        assertEquals(null, page.structure.blocks[1].level)
        assertEquals(null, page.structure.blocks[1].latex)
        assertEquals("\\alpha", page.structure.blocks[2].latex)
    }

    @Test
    fun `a lone CR at the end of a block or last cell never throws`() {
        val block = derive(BlockDraft(BlockKind.PARAGRAPH, "a\r"), BlockDraft(BlockKind.PARAGRAPH, "b"))
        assertInvariant(block)
        val cell = derive(
            BlockDraft(BlockKind.TABLE, "", rows = listOf(listOf(CellDraft("x", false), CellDraft("y\r", false)), listOf(CellDraft("z", false)))),
            BlockDraft(BlockKind.PARAGRAPH, "b"),
        )
        assertInvariant(cell)
    }
}
