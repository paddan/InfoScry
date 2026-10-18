package infoscry.ask

import infoscry.ocr.BlockKind
import infoscry.ocr.PageStructure
import infoscry.ocr.StructureBlock
import infoscry.ocr.TableRow

/**
 * Renders a span of a structured page as Markdown, for the model to read as evidence.
 *
 * The span addresses the unit's search text, the same offset space chunks and citations use, so the
 * rendering is a view of what was searched and never a second text: nothing here changes an evidence ID,
 * a citation or a locator. The output is only what the model is sent; the caller still escapes it for the
 * evidence frame.
 *
 * A block is rendered from its own structure: headings get `#` by level, list items `- `, formulas
 * `$latex$`, code a fence, and a table a pipe table with its header row. A span that starts inside a table
 * repeats the header row, so a chunk that begins mid-table still says what its columns are. Page headers and
 * footers are left out, because a running line repeated on each page is not evidence of that page. Text that
 * belongs to no block is copied verbatim.
 */
object StructuredEvidence {

    /** The Markdown for `[start, end)` of [searchText], where [structure] describes [searchText]. */
    fun render(structure: PageStructure, searchText: String, start: Int, end: Int): String {
        require(start in 0..end && end <= searchText.length) { "the span lies inside the text" }
        val blocks = structure.blocks
            .sortedBy { it.search.start }
            .filter { it.search.start < end && it.search.endExclusive > start }
        if (blocks.isEmpty()) return searchText.substring(start, end)

        val out = StringBuilder()
        var cursor = start
        var previous: BlockKind? = null
        for (block in blocks) {
            val from = maxOf(block.search.start, start)
            val to = minOf(block.search.endExclusive, end)
            // Whitespace between blocks is the page's separator, which Markdown spells out itself; any other
            // text outside a block is a block of its own.
            val gap = searchText.substring(cursor, from)
            if (gap.isNotBlank()) {
                appendGap(out, previous, gap)
                previous = null
            }
            cursor = to
            if (block.kind == BlockKind.PAGE_HEADER || block.kind == BlockKind.PAGE_FOOTER) continue
            val rendered = renderBlock(block, searchText, start, end) ?: continue
            separate(out, previous, block.kind)
            out.append(rendered)
            previous = block.kind
        }
        val tail = searchText.substring(cursor, end)
        if (tail.isNotBlank()) appendGap(out, previous, tail)
        return out.toString()
    }

    /**
     * Text outside every block, set off by a blank line on both sides so it never glues onto a fence or a table
     * row. Its own line breaks are dropped at its ends, since the blank line replaces them.
     */
    private fun appendGap(out: StringBuilder, previous: BlockKind?, gap: String) {
        separate(out, previous, kind = null)
        out.append(gap.trim())
    }

    /** Consecutive list items share a line; every other block, and any gap, starts after a blank line. */
    private fun separate(out: StringBuilder, previous: BlockKind?, kind: BlockKind?) {
        if (out.isEmpty()) return
        if (previous == BlockKind.LIST_ITEM && kind == BlockKind.LIST_ITEM) {
            if (!out.endsWith("\n")) out.append('\n')
        } else {
            while (!out.endsWith("\n\n")) out.append('\n')
        }
    }

    /** The block's Markdown for the part of it inside the span, or null when that part is blank. */
    private fun renderBlock(block: StructureBlock, text: String, start: Int, end: Int): String? {
        val startsInside = block.search.start >= start
        val endsInside = block.search.endExclusive <= end
        val covered = text.substring(maxOf(block.search.start, start), minOf(block.search.endExclusive, end))
        if (block.kind == BlockKind.TABLE) return renderTable(block, text, start, end)
        if (covered.isBlank()) return null
        return when (block.kind) {
            BlockKind.HEADING -> if (startsInside) "#".repeat(block.level ?: 1) + " " + covered else covered
            BlockKind.LIST_ITEM -> if (startsInside) "- $covered" else covered
            BlockKind.FORMULA ->
                if (block.latex != null && startsInside && endsInside) "\$${block.latex}\$" else covered
            BlockKind.CODE -> fenced(covered)
            else -> covered
        }
    }

    /**
     * A pipe table. The header row is the first row with a header cell, else the first row, the same row the
     * chunker repeats. It is written whenever the table is in the span, so a span that starts mid-table
     * carries it too; body rows are the rows the span reaches, with cells clipped to the span.
     */
    private fun renderTable(block: StructureBlock, text: String, start: Int, end: Int): String? {
        val rows = block.rows.orEmpty().filter { it.cells.isNotEmpty() }
        if (rows.none { rowOverlaps(it, start, end) }) return null
        val headerIndex = rows.indexOfFirst { row -> row.cells.any { it.header } }.takeIf { it >= 0 } ?: 0
        val reached = rows.indices.filter { index ->
            index != headerIndex && rowOverlaps(rows[index], start, end)
        }

        val columns = rows.maxOf { it.cells.size }
        val lines = ArrayList<String>()
        lines += pipeLine(rows[headerIndex].cells.map { cellText(it.search.start, it.search.endExclusive, text) }, columns)
        lines += pipeLine(List(columns) { "---" }, columns)
        for (index in reached) {
            val cells = rows[index].cells.map { cell ->
                val from = maxOf(cell.search.start, start)
                val to = minOf(cell.search.endExclusive, end)
                if (from < to) cellText(from, to, text) else ""
            }
            lines += pipeLine(cells, columns)
        }
        return lines.joinToString("\n")
    }

    private fun rowOverlaps(row: TableRow, start: Int, end: Int): Boolean =
        row.cells.any { it.search.start < end && it.search.endExclusive > start }

    private fun cellText(from: Int, to: Int, text: String): String =
        text.substring(from, to).replace('\n', ' ').replace("|", "\\|").trim()

    /** A row padded to [columns] cells, so every line of the table has the same width. */
    private fun pipeLine(cells: List<String>, columns: Int): String =
        (cells + List(columns - cells.size) { "" }).joinToString(" | ", prefix = "| ", postfix = " |")

    /** A fence longer than any backtick run in [code], so the code cannot close its own fence. */
    private fun fenced(code: String): String {
        val longestRun = Regex("`+").findAll(code).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        return "$fence\n$code\n$fence"
    }
}
