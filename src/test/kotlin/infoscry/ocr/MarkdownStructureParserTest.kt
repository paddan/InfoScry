package infoscry.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MarkdownStructureParserTest {

    private fun parse(markdown: String) = MarkdownStructureParser.parse(markdown)

    @Test
    fun `headings, list items and a table are recognised in reading order`() {
        val draft = parse(
            """
            # Title

            ## Sub

            - one
            2. two

            | Name | Qty |
            | --- | --- |
            | A | 1 |
            """.trimIndent(),
        )

        assertEquals(MarkdownStructureParser.PARSER, draft.parser)
        assertFalse(draft.degraded)
        assertEquals(
            listOf(BlockKind.HEADING, BlockKind.HEADING, BlockKind.LIST_ITEM, BlockKind.LIST_ITEM, BlockKind.TABLE),
            draft.blocks.map { it.kind },
        )
        assertEquals(listOf(1, 2), draft.blocks.take(2).map { it.level })
        assertEquals(listOf("Title", "Sub", "one", "2. two"), draft.blocks.take(4).map { it.text })
        val rows = draft.blocks.last().rows!!
        assertEquals(listOf(listOf("Name", "Qty"), listOf("A", "1")), rows.map { row -> row.map { it.text } })
        assertEquals(listOf(true, false), rows.map { row -> row.first().header })
    }

    @Test
    fun `an ordered item keeps its number as page text`() {
        val draft = parse("12. Beslut\n1) a")

        assertEquals(listOf(BlockKind.LIST_ITEM, BlockKind.LIST_ITEM), draft.blocks.map { it.kind })
        assertEquals(listOf("12. Beslut", "1) a"), draft.blocks.map { it.text })
    }

    @Test
    fun `a bullet item drops its glyph`() {
        val draft = parse("- one\n* two\n+ three")

        assertEquals(listOf("one", "two", "three"), draft.blocks.map { it.text })
    }

    @Test
    fun `a page marker written on one line keeps its text and leaks no tag`() {
        val draft = parse("<!-- page-header -->Title<!-- /page-header -->\n\n<!-- page-footer -->3<!-- /page-footer -->")

        assertEquals(listOf(BlockKind.PAGE_HEADER, BlockKind.PAGE_FOOTER), draft.blocks.map { it.kind })
        assertEquals(listOf("Title", "3"), draft.blocks.map { it.text })
        assertFalse(draft.blocks.any { it.text.contains("<!--") })
    }

    @Test
    fun `an escaped pipe is a literal character inside a cell`() {
        val draft = parse("| a \\| b | c |\n| --- | --- |\n| 1 | 2 |")

        val table = draft.blocks.single()
        assertEquals(BlockKind.TABLE, table.kind)
        assertEquals(listOf(listOf("a | b", "c"), listOf("1", "2")), table.rows!!.map { row -> row.map { it.text } })
    }

    @Test
    fun `lines with pipes but no separator row stay a paragraph`() {
        val draft = parse("a | b\nc | d")

        assertEquals(listOf(BlockKind.PARAGRAPH), draft.blocks.map { it.kind })
        assertEquals("a | b\nc | d", draft.blocks.single().text)
        assertNull(draft.blocks.single().rows)
    }

    @Test
    fun `an unterminated fence keeps every line of its text as code`() {
        val draft = parse("```\ncode line\nmore")

        assertEquals(BlockKind.CODE, draft.blocks.single().kind)
        assertEquals("code line\nmore", draft.blocks.single().text)
    }

    @Test
    fun `page header and footer markers wrap their own blocks`() {
        val draft = parse(
            """
            <!-- page-header -->
            School report
            <!-- /page-header -->

            Body text

            <!-- page-footer -->
            3
            <!-- /page-footer -->
            """.trimIndent(),
        )

        assertEquals(listOf(BlockKind.PAGE_HEADER, BlockKind.PARAGRAPH, BlockKind.PAGE_FOOTER), draft.blocks.map { it.kind })
        assertEquals(listOf("School report", "Body text", "3"), draft.blocks.map { it.text })
    }

    @Test
    fun `display and single-line formulas carry their LaTeX`() {
        val draft = parse("\$\$\nx^2 + y^2\n\$\$\n\n\$a+b\$")

        assertEquals(listOf(BlockKind.FORMULA, BlockKind.FORMULA), draft.blocks.map { it.kind })
        assertEquals(listOf("x^2 + y^2", "a+b"), draft.blocks.map { it.latex })
        assertEquals(listOf("x^2 + y^2", "a+b"), draft.blocks.map { it.text })
    }

    @Test
    fun `emphasis and code markers are removed and page text is kept`() {
        val draft = parse("**Bold** and `code`, snake_case stays")

        assertEquals("Bold and code, snake_case stays", draft.blocks.single().text)
    }

    @Test
    fun `an empty reading has no blocks`() {
        assertEquals(emptyList(), parse("").blocks)
        assertEquals(emptyList(), parse("  \n\n ").blocks)
    }
}
