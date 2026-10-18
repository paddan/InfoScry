package infoscry.ocr

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** One block of a Surya page as the worker sends it: the model's HTML, its label and reading order, and its box. */
data class SuryaBlockInput(val html: String, val label: String?, val readingOrder: Int?, val bbox: PixelBox)

/**
 * Turns Surya's per-block HTML into a [PageStructureDraft] (`surya-html/1`).
 *
 * Parsing is an allowlist: only the elements that carry structure (headings, lists, tables, math) are read
 * for their shape, and every other element contributes its text. Script and style content is dropped. No
 * markup is stored; the structure keeps plain text, so nothing in the HTML can reach the page text.
 *
 * A `Table` block that does not parse as a table keeps its text as a paragraph, and the whole page is marked
 * degraded. The page then reads as paragraphs, which is the same reading a page without structure gets.
 */
object SuryaStructureParser {

    const val PARSER: String = "surya-html/1"

    private const val DEFAULT_HEADING_LEVEL = 2
    private const val MAX_SPAN = 1000

    fun parse(blocks: List<SuryaBlockInput>): PageStructureDraft {
        var degraded = false
        val drafts = ArrayList<BlockDraft>()
        for (input in sortedByReadingOrder(blocks)) {
            val parsed = parseBlock(input)
            degraded = degraded || parsed.degraded
            drafts += parsed.blocks.filter(::hasText)
        }
        return PageStructureDraft(PARSER, drafts, degraded)
    }

    /** Blocks by reading order, a missing order last. The sort is stable, so ties and missing orders keep their received order. */
    private fun sortedByReadingOrder(blocks: List<SuryaBlockInput>): List<SuryaBlockInput> =
        blocks.sortedWith(compareBy<SuryaBlockInput>({ it.readingOrder == null }, { it.readingOrder ?: 0 }))

    private class ParsedBlock(val blocks: List<BlockDraft>, val degraded: Boolean = false)

    private fun parseBlock(input: SuryaBlockInput): ParsedBlock {
        val body = Jsoup.parseBodyFragment(input.html).body()
        body.select("script, style").remove()
        val kind = kindOf(input.label)
        return when (kind) {
            BlockKind.HEADING -> {
                val level = body.select("h1, h2, h3, h4, h5, h6").firstOrNull()
                    ?.tagName()?.substring(1)?.toInt() ?: DEFAULT_HEADING_LEVEL
                ParsedBlock(listOf(BlockDraft(BlockKind.HEADING, body.text(), level = level, bbox = input.bbox)))
            }
            BlockKind.LIST_ITEM -> parseList(body, input)
            BlockKind.TABLE -> parseTable(body, input)
            BlockKind.FORMULA -> parseFormula(body, input)
            BlockKind.CODE -> ParsedBlock(listOf(BlockDraft(kind, body.wholeText().trim(), bbox = input.bbox)))
            else -> ParsedBlock(listOf(BlockDraft(kind, body.text(), bbox = input.bbox)))
        }
    }

    /**
     * One item per top-level `li`. A list without items keeps its text as one item. Text outside the items (a
     * preface such as "Requirements:") is kept as a paragraph before them, so it is never lost. Its position
     * relative to the items is not kept, since only the items are read for their shape.
     */
    private fun parseList(body: Element, input: SuryaBlockInput): ParsedBlock {
        val items = body.select("li").filter { it.parents().none { parent -> parent.tagName() == "li" } }
        if (items.isEmpty()) {
            return ParsedBlock(listOf(BlockDraft(BlockKind.LIST_ITEM, body.text(), bbox = input.bbox)))
        }
        val preface = body.clone().also { it.select("li").remove() }.text().trim()
        val prefaceBlocks = if (preface.isEmpty()) emptyList() else listOf(BlockDraft(BlockKind.PARAGRAPH, preface, bbox = input.bbox))
        return ParsedBlock(prefaceBlocks + items.map { BlockDraft(BlockKind.LIST_ITEM, it.text(), bbox = input.bbox) })
    }

    /**
     * A table becomes TABLE rows, and text around it becomes paragraphs before and after it, in document order,
     * so nothing is lost. Without a readable table the block is a paragraph with its text; only a `Table`
     * label counts that as degraded, since a `Form` or table of contents is often plain text by design.
     */
    private fun parseTable(body: Element, input: SuryaBlockInput): ParsedBlock {
        val table = body.selectFirst("table")
        val rows = table?.let(::rowsOf).orEmpty()
        if (table == null || rows.none { it.isNotEmpty() }) {
            return ParsedBlock(
                listOf(BlockDraft(BlockKind.PARAGRAPH, body.text(), bbox = input.bbox)),
                degraded = input.label == "Table",
            )
        }
        val outside = OutsideText()
        collectOutside(body, table, outside)
        val before = outside.before.map { BlockDraft(BlockKind.PARAGRAPH, it, bbox = input.bbox) }
        val after = outside.after.map { BlockDraft(BlockKind.PARAGRAPH, it, bbox = input.bbox) }
        // A caption belongs to its table, so it comes right before the table rather than with the surrounding text.
        val caption = table.select("caption").firstOrNull { it.closestTable() === table }?.text()?.trim().orEmpty()
        val captionBlocks = if (caption.isEmpty()) emptyList() else listOf(BlockDraft(BlockKind.CAPTION, caption, bbox = input.bbox))
        return ParsedBlock(before + captionBlocks + BlockDraft(BlockKind.TABLE, "", bbox = input.bbox, rows = rows) + after)
    }

    /** The text of a block outside its table, split into what comes before the table and what comes after it. */
    private class OutsideText {
        val before = ArrayList<String>()
        val after = ArrayList<String>()
        var passedTable = false

        fun add(text: String) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return
            (if (passedTable) after else before).add(trimmed)
        }
    }

    /** Walks the block in document order: text nodes and elements that do not hold the table are taken whole. */
    private fun collectOutside(parent: Node, table: Element, outside: OutsideText) {
        for (child in parent.childNodes()) {
            when {
                child === table -> outside.passedTable = true
                child is TextNode -> outside.add(child.text())
                child is Element && child.select("table").contains(table) -> collectOutside(child, table, outside)
                child is Element -> outside.add(child.text())
            }
        }
    }

    /** The cells of the table's own rows (a nested table's rows belong to the nested table). */
    private fun rowsOf(table: Element): List<List<CellDraft>> =
        table.select("tr").filter { it.closestTable() === table }.map { row ->
            row.children().filter { it.tagName() == "th" || it.tagName() == "td" }.map { cell ->
                CellDraft(
                    text = cell.text(),
                    header = cell.tagName() == "th",
                    rowSpan = span(cell.attr("rowspan")),
                    colSpan = span(cell.attr("colspan")),
                )
            }
        }

    private fun Element.closestTable(): Element? {
        var current: Element? = parent()
        while (current != null) {
            if (current.tagName() == "table") return current
            current = current.parent()
        }
        return null
    }

    private fun span(value: String): Int = value.trim().toIntOrNull()?.coerceIn(1, MAX_SPAN) ?: 1

    /**
     * The LaTeX is the `math` content, and the block's text is that source, so it stays searchable as written.
     * Text outside the `math` (an equation number such as "(3)") is kept as a paragraph after the formula.
     */
    private fun parseFormula(body: Element, input: SuryaBlockInput): ParsedBlock {
        val math = body.select("math").map { it.text() }.filter { it.isNotBlank() }
        val latex = if (math.isEmpty()) body.text() else math.joinToString("\n")
        val formula = BlockDraft(BlockKind.FORMULA, latex, bbox = input.bbox, latex = latex)
        if (math.isEmpty()) return ParsedBlock(listOf(formula))
        val rest = body.clone().also { it.select("math").remove() }.text().trim()
        val restBlocks = if (rest.isEmpty()) emptyList() else listOf(BlockDraft(BlockKind.PARAGRAPH, rest, bbox = input.bbox))
        return ParsedBlock(listOf(formula) + restBlocks)
    }

    private fun hasText(block: BlockDraft): Boolean =
        block.rows?.any { row -> row.any { it.text.isNotBlank() } } ?: block.text.isNotBlank()

    /** The label mapping from the structure spec. An unknown or missing label is a paragraph. */
    private fun kindOf(label: String?): BlockKind = when (label) {
        "SectionHeader" -> BlockKind.HEADING
        "Text", "Bibliography" -> BlockKind.PARAGRAPH
        "ListGroup" -> BlockKind.LIST_ITEM
        "Table", "Form", "TableOfContents" -> BlockKind.TABLE
        "Equation" -> BlockKind.FORMULA
        "Code" -> BlockKind.CODE
        "Caption" -> BlockKind.CAPTION
        "Footnote" -> BlockKind.FOOTNOTE
        "PageHeader" -> BlockKind.PAGE_HEADER
        "PageFooter" -> BlockKind.PAGE_FOOTER
        "Picture", "Figure", "Diagram", "ChemicalBlock" -> BlockKind.FIGURE
        else -> BlockKind.PARAGRAPH
    }
}
