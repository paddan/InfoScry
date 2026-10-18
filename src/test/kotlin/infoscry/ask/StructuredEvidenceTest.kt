package infoscry.ask

import infoscry.ocr.BlockDraft
import infoscry.ocr.BlockKind
import infoscry.ocr.CellDraft
import infoscry.ocr.DerivedPage
import infoscry.ocr.PageStructure
import infoscry.ocr.PageStructureDraft
import infoscry.ocr.STRUCTURE_VERSION
import infoscry.ocr.StructureBlock
import infoscry.ocr.StructureDerivation
import infoscry.ocr.TableCell
import infoscry.ocr.TableRow
import infoscry.ocr.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredEvidenceTest {

    private fun derive(vararg blocks: BlockDraft): DerivedPage =
        StructureDerivation.derive(PageStructureDraft("test/1", blocks.toList()), "c".repeat(64))

    private fun DerivedPage.render(start: Int = 0, end: Int = searchText.length): String =
        StructuredEvidence.render(structure, searchText, start, end)

    private fun row(vararg cells: CellDraft) = cells.toList()

    @Test
    fun rendersTableWithHeader() {
        val page = derive(
            BlockDraft(
                BlockKind.TABLE, "",
                rows = listOf(
                    row(CellDraft("Name", true), CellDraft("Qty", true)),
                    row(CellDraft("apple", false), CellDraft("3", false)),
                ),
            ),
        )

        assertEquals("| Name | Qty |\n| --- | --- |\n| apple | 3 |", page.render())
    }

    @Test
    fun midTableSpanRepeatsHeader() {
        val page = derive(
            BlockDraft(
                BlockKind.TABLE, "",
                rows = listOf(
                    row(CellDraft("Name", true), CellDraft("Qty", true)),
                    row(CellDraft("apple", false), CellDraft("3", false)),
                    row(CellDraft("pear", false), CellDraft("5", false)),
                ),
            ),
        )
        val start = page.searchText.indexOf("pear")

        assertEquals("| Name | Qty |\n| --- | --- |\n| pear | 5 |", page.render(start, page.searchText.length))
    }

    @Test
    fun escapesPipe() {
        val page = derive(
            BlockDraft(
                BlockKind.TABLE, "",
                rows = listOf(
                    row(CellDraft("Expr", true)),
                    row(CellDraft("a|b", false)),
                ),
            ),
        )

        assertEquals("| Expr |\n| --- |\n| a\\|b |", page.render())
    }

    @Test
    fun omitsPageFooter() {
        val page = derive(
            BlockDraft(BlockKind.PARAGRAPH, "Body text"),
            BlockDraft(BlockKind.PAGE_FOOTER, "Page 3"),
        )

        assertEquals("Body text", page.render())
    }

    @Test
    fun rendersHeadingsListItemsFormulasAndCode() {
        val page = derive(
            BlockDraft(BlockKind.HEADING, "Results", level = 2),
            BlockDraft(BlockKind.LIST_ITEM, "first"),
            BlockDraft(BlockKind.LIST_ITEM, "second"),
            BlockDraft(BlockKind.FORMULA, "E = mc2", latex = "E=mc^2"),
            BlockDraft(BlockKind.CODE, "val x = 1"),
        )

        assertEquals(
            "## Results\n\n- first\n- second\n\n\$E=mc^2\$\n\n```\nval x = 1\n```",
            page.render(),
        )
    }

    @Test
    fun textOutsideBlocksIsKeptAndSetOffFromTheBlockBeforeIt() {
        val searchText = "Body\ntrailing words"
        val page = structureOf(listOf(paragraph(0, 4)))

        assertEquals("Body\n\ntrailing words", StructuredEvidence.render(page, searchText, 0, searchText.length))
    }

    @Test
    fun textAfterACodeFenceIsNotGluedOntoIt() {
        val searchText = "val x\nafter words"
        val page = structureOf(listOf(StructureBlock(BlockKind.CODE, order = 0, extracted = TextRange(0, 5), search = TextRange(0, 5))))

        assertEquals("```\nval x\n```\n\nafter words", StructuredEvidence.render(page, searchText, 0, searchText.length))
    }

    @Test
    fun textBeforeATableIsNotGluedOntoItsFirstRow() {
        val searchText = "after words\nName\tQty\napple\t3"
        val tableStart = searchText.indexOf("Name")
        val cells = listOf(
            listOf(cell("Name", true, tableStart), cell("Qty", true, tableStart + 5)),
            listOf(cell("apple", false, tableStart + 9), cell("3", false, tableStart + 15)),
        )
        val table = StructureBlock(
            BlockKind.TABLE, order = 1,
            extracted = TextRange(tableStart, searchText.length), search = TextRange(tableStart, searchText.length),
            rows = cells.map { TableRow(it) },
        )
        val page = structureOf(listOf(table))

        assertEquals(
            "after words\n\n| Name | Qty |\n| --- | --- |\n| apple | 3 |",
            StructuredEvidence.render(page, searchText, 0, searchText.length),
        )
    }

    private fun paragraph(start: Int, end: Int) =
        StructureBlock(BlockKind.PARAGRAPH, order = 0, extracted = TextRange(start, end), search = TextRange(start, end))

    private fun cell(text: String, header: Boolean, start: Int): TableCell =
        TableCell(header = header, extracted = TextRange(start, start + text.length), search = TextRange(start, start + text.length))

    /** A structure over hand-placed blocks; the span tests do not verify it against a text, so the hash is a placeholder. */
    private fun structureOf(blocks: List<StructureBlock>): PageStructure = PageStructure(
        version = STRUCTURE_VERSION,
        textSha256 = "a".repeat(64),
        imageSha256 = "b".repeat(64),
        parser = "test/1",
        blocks = blocks,
    )
}
