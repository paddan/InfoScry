package infoscry.ocr

import infoscry.extract.TextNormalizer
import infoscry.pdf.PdfModelRules
import kotlinx.serialization.Serializable

/** Bumped when the shape or the derivation rules of [PageStructure] change; part of a reading's identity. */
const val STRUCTURE_VERSION: Int = 1

/** A half-open UTF-16 range `[start, endExclusive)` into one of a page's two text forms. */
@Serializable
data class TextRange(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0) { "a text range starts at or after 0, was $start" }
        require(endExclusive >= start) { "a text range ends at or after its start, was $start..$endExclusive" }
    }
}

/** A rectangle in image pixels (top-left origin), as the engine reported it. */
@Serializable
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right > left && bottom > top) { "a pixel box has a positive size" }
    }
}

@Serializable
enum class BlockKind {
    HEADING, PARAGRAPH, LIST_ITEM, TABLE, FORMULA, CODE, CAPTION, FOOTNOTE,
    PAGE_HEADER, PAGE_FOOTER, FIGURE,
}

@Serializable
data class TableCell(
    val header: Boolean,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val extracted: TextRange,
    val search: TextRange,
) {
    init {
        require(rowSpan >= 1 && colSpan >= 1) { "a table cell spans at least one row and one column" }
    }
}

@Serializable
data class TableRow(val cells: List<TableCell>)

/**
 * One piece of a page's canonical reading. [extracted] and [search] locate the same text in the unit's
 * `extractedText` (the evidence) and `searchText` (the index form); [order] is the reading order.
 */
@Serializable
data class StructureBlock(
    val kind: BlockKind,
    val level: Int? = null,
    val order: Int,
    val bbox: PixelBox? = null,
    val extracted: TextRange,
    val search: TextRange,
    val rows: List<TableRow>? = null,
    val latex: String? = null,
) {
    init {
        require(level == null || (kind == BlockKind.HEADING && level in 1..6)) {
            "only a heading carries a level, and it is 1..6"
        }
        require(rows == null || kind == BlockKind.TABLE) { "only a table carries rows" }
        require(latex == null || kind == BlockKind.FORMULA) { "only a formula carries LaTeX" }
        require(order >= 0) { "a block's order is not negative" }
        rows?.let { requireCellsInside(it) }
    }

    /** Cells follow each other inside the block's own ranges, in both text forms. */
    private fun requireCellsInside(tableRows: List<TableRow>) {
        var extractedEnd = extracted.start
        var searchEnd = search.start
        for (cell in tableRows.flatMap { it.cells }) {
            require(cell.extracted.start >= extractedEnd && cell.extracted.endExclusive <= extracted.endExclusive) {
                "table cells stay inside their table and do not overlap"
            }
            require(cell.search.start >= searchEnd && cell.search.endExclusive <= search.endExclusive) {
                "table cells stay inside their table and do not overlap"
            }
            extractedEnd = cell.extracted.endExclusive
            searchEnd = cell.search.endExclusive
        }
    }
}

/**
 * The structure of one page: what its blocks are, in reading order, and where each one sits in the unit's
 * two texts. It is derived from the text, never the other way round (see [StructureDerivation]), so it can be
 * dropped and rebuilt without changing what is searched or cited.
 *
 * Construction checks everything the structure alone can prove. What only the text can prove (the text hash,
 * ranges within bounds, no split surrogate pair, nothing but whitespace between blocks) is [verifiedAgainst].
 */
@Serializable
data class PageStructure(
    val version: Int,
    val textSha256: String,
    val imageSha256: String,
    val parser: String,
    val blocks: List<StructureBlock>,
    /**
     * True when the parser could not keep the page's structure and the page was reduced to paragraphs (the
     * `STRUCTURE_DEGRADED` condition). Page review shows it; the text and citations are unaffected.
     */
    val degraded: Boolean = false,
) {
    init {
        require(version == STRUCTURE_VERSION) { "unsupported page structure version $version" }
        require(PdfModelRules.isSha256(textSha256)) { "textSha256 is a lowercase hex SHA-256" }
        require(PdfModelRules.isSha256(imageSha256)) { "imageSha256 is a lowercase hex SHA-256" }
        require(parser.isNotBlank()) { "a structure names the parser that made it" }
        require(blocks.map { it.order }.toSet().size == blocks.size) { "block order values are unique" }
        requireNoOverlap(blocks.map { it.extracted })
        requireNoOverlap(blocks.map { it.search })
    }

    /**
     * This structure, proven to describe [extractedText]. The search form is derived here the same way
     * [StructureDerivation] derives it, so a structure cannot be checked against a text it was not made for.
     *
     * @throws IllegalArgumentException when any part of the structure does not fit the text.
     */
    fun verifiedAgainst(extractedText: String): PageStructure {
        require(PdfModelRules.sha256Of(extractedText) == textSha256) {
            "the structure was made for a different text"
        }
        val searchText = TextNormalizer.normalize(extractedText).search
        for (block in blocks) {
            requireRangeFits(block.extracted, extractedText)
            requireRangeFits(block.search, searchText)
            requireSameText(block.extracted, block.search, extractedText, searchText)
            for (cell in block.rows.orEmpty().flatMap { it.cells }) {
                requireRangeFits(cell.extracted, extractedText)
                requireRangeFits(cell.search, searchText)
                requireSameText(cell.extracted, cell.search, extractedText, searchText)
            }
        }
        requireOnlyWhitespaceOutside(blocks.map { it.extracted }, extractedText)
        requireOnlyWhitespaceOutside(blocks.map { it.search }, searchText)
        return this
    }

    private fun requireSameText(extracted: TextRange, search: TextRange, extractedText: String, searchText: String) {
        val expected = TextNormalizer.normalize(extractedText.substring(extracted.start, extracted.endExclusive)).search
        require(searchText.substring(search.start, search.endExclusive) == expected) {
            "a search range holds the normalized text of its extracted range"
        }
    }

    private fun requireNoOverlap(ranges: List<TextRange>) {
        var previousEnd = 0
        for (range in ranges.sortedBy { it.start }) {
            require(range.start >= previousEnd) { "block ranges do not overlap" }
            previousEnd = range.endExclusive
        }
    }

    private fun requireRangeFits(range: TextRange, text: String) {
        require(range.endExclusive <= text.length) { "a range lies inside the text" }
        require(
            !PdfModelRules.splitsSurrogatePair(text, range.start) &&
                !PdfModelRules.splitsSurrogatePair(text, range.endExclusive),
        ) { "a range does not split a surrogate pair" }
    }

    private fun requireOnlyWhitespaceOutside(ranges: List<TextRange>, text: String) {
        var position = 0
        for (range in ranges.sortedBy { it.start }) {
            requireBlank(text, position, range.start)
            position = range.endExclusive
        }
        requireBlank(text, position, text.length)
    }

    private fun requireBlank(text: String, from: Int, to: Int) {
        for (index in from until to) {
            require(text[index].isWhitespace()) { "every non-whitespace character belongs to a block" }
        }
    }
}
