package infoscry.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuryaStructureParserTest {

    private fun block(
        html: String,
        label: String? = "Text",
        readingOrder: Int? = null,
        bbox: PixelBox = PixelBox(0, 0, 10, 10),
    ) = SuryaBlockInput(html, label, readingOrder, bbox)

    private fun parse(vararg blocks: SuryaBlockInput) = SuryaStructureParser.parse(blocks.toList())

    @Test
    fun `mapsEveryLabel`() {
        val cases = mapOf(
            "SectionHeader" to BlockKind.HEADING,
            "Text" to BlockKind.PARAGRAPH,
            "Bibliography" to BlockKind.PARAGRAPH,
            "ListGroup" to BlockKind.LIST_ITEM,
            "Table" to BlockKind.TABLE,
            "Form" to BlockKind.TABLE,
            "TableOfContents" to BlockKind.TABLE,
            "Equation" to BlockKind.FORMULA,
            "Code" to BlockKind.CODE,
            "Caption" to BlockKind.CAPTION,
            "Footnote" to BlockKind.FOOTNOTE,
            "PageHeader" to BlockKind.PAGE_HEADER,
            "PageFooter" to BlockKind.PAGE_FOOTER,
            "Picture" to BlockKind.FIGURE,
            "Figure" to BlockKind.FIGURE,
            "Diagram" to BlockKind.FIGURE,
            "ChemicalBlock" to BlockKind.FIGURE,
            "SomethingNew" to BlockKind.PARAGRAPH,
        )
        for ((label, kind) in cases) {
            val html = when (kind) {
                BlockKind.TABLE -> "<table><tr><td>x</td></tr></table>"
                BlockKind.LIST_ITEM -> "<ul><li>x</li></ul>"
                BlockKind.FORMULA -> "<math>x</math>"
                else -> "x"
            }
            val draft = parse(block(html, label)).blocks.single()
            assertEquals(kind, draft.kind, "label $label")
        }
    }

    @Test
    fun `unlabelledBlockIsParagraph`() {
        assertEquals(BlockKind.PARAGRAPH, parse(block("text", null)).blocks.single().kind)
    }

    @Test
    fun `headingLevelFromTag`() {
        assertEquals(3, parse(block("<h3>Title</h3>", "SectionHeader")).blocks.single().level)
        assertEquals(2, parse(block("Title", "SectionHeader")).blocks.single().level)
    }

    @Test
    fun `tableWithSpans`() {
        val draft = parse(
            block(
                """<table><thead><tr><th rowspan="2">Name</th><th colspan="3">Values</th></tr></thead>""" +
                    """<tbody><tr><td>1</td><td>2</td><td>3</td></tr></tbody></table>""",
                "Table",
            ),
        ).blocks.single()
        assertEquals(BlockKind.TABLE, draft.kind)
        val rows = draft.rows!!
        assertEquals(2, rows.size)
        assertEquals(CellDraft("Name", header = true, rowSpan = 2, colSpan = 1), rows[0][0])
        assertEquals(CellDraft("Values", header = true, rowSpan = 1, colSpan = 3), rows[0][1])
        assertEquals(listOf("1", "2", "3"), rows[1].map { it.text })
        assertTrue(rows[1].none { it.header })
    }

    @Test
    fun `spansAreClampedToRange`() {
        val rows = parse(
            block("<table><tr><td rowspan=\"0\">a</td><td colspan=\"5000\">b</td><td rowspan=\"x\">c</td></tr></table>", "Table"),
        ).blocks.single().rows!!
        assertEquals(listOf(1, 1, 1), rows.single().map { it.rowSpan })
        assertEquals(listOf(1, 1000, 1), rows.single().map { it.colSpan })
    }

    @Test
    fun `raggedTableKeepsEveryCell`() {
        val rows = parse(
            block(
                "<table><tr><td>a</td><td>b</td><td>c</td></tr><tr><td colspan=\"9\">wide</td></tr></table>",
                "Table",
            ),
        ).blocks.single().rows!!
        val cells = rows.flatMap { row -> row.map { it.text } }
        assertEquals(listOf("a", "b", "c", "wide"), cells)
        assertEquals(9, rows[1][0].colSpan)
    }

    @Test
    fun `brokenTableBecomesParagraph`() {
        val result = parse(block("<table><tr>lost text</tr></table>", "Table"))
        val draft = result.blocks.single()
        assertEquals(BlockKind.PARAGRAPH, draft.kind)
        assertNull(draft.rows)
        assertTrue(draft.text.contains("lost text"))
        assertTrue(result.degraded)
    }

    @Test
    fun `formWithoutTableIsParagraphNotDegraded`() {
        val result = parse(block("<p>Name: ____</p>", "Form"))
        val draft = result.blocks.single()
        assertEquals(BlockKind.PARAGRAPH, draft.kind)
        assertEquals("Name: ____", draft.text)
        assertFalse(result.degraded)
    }

    @Test
    fun `tableOfContentsWithoutTableIsParagraphNotDegraded`() {
        val result = parse(block("<ul><li>1 Introduction</li><li>2 Method</li></ul>", "TableOfContents"))
        val draft = result.blocks.single()
        assertEquals(BlockKind.PARAGRAPH, draft.kind)
        assertEquals("1 Introduction 2 Method", draft.text)
        assertFalse(result.degraded)
    }

    @Test
    fun `textAroundTableIsKeptInDocumentOrder`() {
        val result = parse(
            block(
                "<p>Table 1: Results</p><table><tr><td>a</td><td>b</td></tr></table><p>Source: survey</p>",
                "Table",
            ),
        )
        val blocks = result.blocks
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.TABLE, BlockKind.PARAGRAPH), blocks.map { it.kind })
        assertEquals("Table 1: Results", blocks[0].text)
        assertEquals(listOf(listOf("a", "b")), blocks[1].rows!!.map { row -> row.map { it.text } })
        assertEquals("Source: survey", blocks[2].text)
        assertFalse(result.degraded)
    }

    @Test
    fun `preKeepsNewlinesAndBreaks`() {
        val draft = parse(block("<pre>line one\nline two<br>line three</pre>", "Code")).blocks.single()
        assertEquals(BlockKind.CODE, draft.kind)
        assertEquals("line one\nline two\nline three", draft.text)
    }

    @Test
    fun `listGroupGivesOneItemPerLi`() {
        val blocks = parse(block("<ul><li>first</li><li>second</li></ul>", "ListGroup")).blocks
        assertEquals(listOf("first", "second"), blocks.map { it.text })
        assertTrue(blocks.all { it.kind == BlockKind.LIST_ITEM })
    }

    @Test
    fun `scriptAndStyleDropped`() {
        val draft = parse(
            block("<style>p { color: red }</style><script>alert(1)</script><p>Visible</p>"),
        ).blocks.single()
        assertEquals("Visible", draft.text)
    }

    @Test
    fun `unknownMarkupContributesItsText`() {
        assertEquals("bold and italic", parse(block("<span><b>bold</b> and <i>italic</i></span>")).blocks.single().text)
    }

    @Test
    fun `sortsByReadingOrderNullLast`() {
        val blocks = parse(
            block("unordered first", readingOrder = null),
            block("third", readingOrder = 3),
            block("unordered second", readingOrder = null),
            block("first", readingOrder = 1),
            block("tie a", readingOrder = 2),
            block("tie b", readingOrder = 2),
        ).blocks
        assertEquals(
            listOf("first", "tie a", "tie b", "third", "unordered first", "unordered second"),
            blocks.map { it.text },
        )
    }

    @Test
    fun `pictureWithoutTextDropped`() {
        val blocks = parse(
            block("", "Picture"),
            block("<img src=\"x.png\">", "Figure"),
            block("Fig 1: a chart", "Picture"),
        ).blocks
        assertEquals(1, blocks.size)
        assertEquals(BlockKind.FIGURE, blocks.single().kind)
        assertEquals("Fig 1: a chart", blocks.single().text)
    }

    @Test
    fun `formulaKeepsLatex`() {
        val draft = parse(block("<math>\\frac{a}{b} = c</math>", "Equation")).blocks.single()
        assertEquals(BlockKind.FORMULA, draft.kind)
        assertEquals("\\frac{a}{b} = c", draft.latex)
        assertEquals("\\frac{a}{b} = c", draft.text)
    }

    @Test
    fun `listPrefaceIsKeptAsParagraphBeforeItems`() {
        val blocks = parse(block("<p>Requirements:</p><ul><li>first</li><li>second</li></ul>", "ListGroup")).blocks
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.LIST_ITEM, BlockKind.LIST_ITEM), blocks.map { it.kind })
        assertEquals(listOf("Requirements:", "first", "second"), blocks.map { it.text })
    }

    @Test
    fun `equationNumberOutsideMathIsKeptAsFollowingParagraph`() {
        val blocks = parse(block("<math>\\frac{a}{b} = c</math> (3)", "Equation")).blocks
        assertEquals(listOf(BlockKind.FORMULA, BlockKind.PARAGRAPH), blocks.map { it.kind })
        assertEquals("\\frac{a}{b} = c", blocks[0].latex)
        assertEquals("(3)", blocks[1].text)
    }

    @Test
    fun `tableCaptionIsKeptAsCaptionBeforeTable`() {
        val blocks = parse(
            block("<table><caption>Table 2: Sales</caption><tr><td>a</td><td>b</td></tr></table>", "Table"),
        ).blocks
        assertEquals(listOf(BlockKind.CAPTION, BlockKind.TABLE), blocks.map { it.kind })
        assertEquals("Table 2: Sales", blocks[0].text)
        assertEquals(listOf(listOf("a", "b")), blocks[1].rows!!.map { row -> row.map { it.text } })
    }

    @Test
    fun `bboxIsPassedThrough`() {
        val box = PixelBox(1, 2, 30, 40)
        assertEquals(box, parse(block("text", bbox = box)).blocks.single().bbox)
    }

    @Test
    fun `parserNameAndNoDegradationForCleanPage`() {
        val result = parse(block("<h1>Title</h1>", "SectionHeader"), block("body", "Text"))
        assertEquals(SuryaStructureParser.PARSER, result.parser)
        assertEquals("surya-html/1", result.parser)
        assertFalse(result.degraded)
    }
}
