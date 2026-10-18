package infoscry.ocr

import infoscry.extract.TextNormalizer
import infoscry.pdf.PdfModelRules

/** The reason code a page carries when its structure could not be kept and was reduced to paragraphs. */
const val STRUCTURE_DEGRADED: String = "STRUCTURE_DEGRADED"

/** A table cell as an engine's parser read it: text, but no ranges yet. */
data class CellDraft(val text: String, val header: Boolean, val rowSpan: Int = 1, val colSpan: Int = 1)

/** A block as an engine's parser read it: text, but no ranges yet. */
data class BlockDraft(
    val kind: BlockKind,
    val text: String,
    val level: Int? = null,
    val bbox: PixelBox? = null,
    val rows: List<List<CellDraft>>? = null,
    val latex: String? = null,
)

/** What a parser hands over, in reading order. [degraded] means the parser itself had to fall back. */
data class PageStructureDraft(val parser: String, val blocks: List<BlockDraft>, val degraded: Boolean = false)

/** A page's two texts and the structure that locates its blocks in both. */
data class DerivedPage(
    val extractedText: String,
    val searchText: String,
    val structure: PageStructure,
    val degraded: Boolean,
)

/**
 * Turns a parser's draft into the page's texts and its [PageStructure], in one direction only: blocks
 * first, then `extractedText`, then `searchText`, then the ranges.
 *
 * Every block and cell is normalized on its own, and the pieces are joined by `\n` (blocks and table rows)
 * and `\t` (cells). The normalizer works per character and a separator is never composed with its
 * neighbours, so a piece's offsets in the joined text are exactly its offsets in the normalized whole. That
 * is checked rather than assumed: if the joined result differs from normalizing the whole, the page is kept
 * as one paragraph per line and marked degraded, because a structure whose ranges point at the wrong
 * characters would make a citation highlight the wrong words.
 */
object StructureDerivation {

    fun derive(draft: PageStructureDraft, imageSha256: String): DerivedPage {
        val prepared = draft.blocks.mapNotNull(::prepare)
        build(prepared, draft.parser, imageSha256)?.let { return it.withDegraded(draft.degraded) }
        return degradedFallback(prepared, draft.parser, imageSha256)
    }

    /**
     * Paragraph-only reading that cannot fail: one paragraph per line, else the whole text as one paragraph,
     * else an empty page, which holds the invariant trivially.
     */
    private fun degradedFallback(prepared: List<Prepared>, parser: String, imageSha256: String): DerivedPage {
        val joined = prepared.joinToString("\n") { it.extracted }
        val lines = joined.split('\n').filter { it.isNotBlank() }
            .mapNotNull { prepare(BlockDraft(BlockKind.PARAGRAPH, it)) }
        val whole = listOfNotNull(prepare(BlockDraft(BlockKind.PARAGRAPH, joined)))
        val page = build(lines, parser, imageSha256)
            ?: build(whole, parser, imageSha256)
            ?: checkNotNull(build(emptyList(), parser, imageSha256)) { "an empty page satisfies the invariant" }
        return page.withDegraded(true)
    }

    /** The page with its flag set on both the page and the structure artifact, so they cannot disagree. */
    private fun DerivedPage.withDegraded(value: Boolean): DerivedPage =
        copy(structure = structure.copy(degraded = value), degraded = value)

    /** Line ends are unified before a piece is joined, so no piece ends in a lone CR that the whole would merge. */
    private fun unifyLineEnds(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private class Prepared(val draft: BlockDraft, val extracted: String, val search: String, val cells: List<List<PreparedCell>>?)

    private class PreparedCell(val draft: CellDraft, val extracted: String, val search: String)

    /** The block normalized, or null when nothing readable is left (a blank block is dropped). */
    private fun prepare(draft: BlockDraft): Prepared? {
        val rows = draft.rows?.takeIf { draft.kind == BlockKind.TABLE }
        if (rows != null) {
            val cells = rows.map { row ->
                row.map {
                    val text = TextNormalizer.normalize(unifyLineEnds(it.text))
                    PreparedCell(it, text.extracted, text.search)
                }
            }
            if (cells.all { row -> row.all { it.search.isBlank() } }) return null
            val extracted = cells.joinToString("\n") { row -> row.joinToString("\t") { it.extracted } }
            return Prepared(draft, extracted, TextNormalizer.normalize(extracted).search, cells)
        }
        val text = TextNormalizer.normalize(unifyLineEnds(draft.text))
        if (text.search.isBlank()) return null
        return Prepared(draft, text.extracted, text.search, null)
    }

    private fun build(blocks: List<Prepared>, parser: String, imageSha256: String): DerivedPage? {
        val extractedText = StringBuilder()
        val searchText = StringBuilder()
        val structureBlocks = ArrayList<StructureBlock>()
        for ((order, block) in blocks.withIndex()) {
            if (order > 0) {
                extractedText.append('\n')
                searchText.append('\n')
            }
            val extractedStart = extractedText.length
            val searchStart = searchText.length
            val rows = block.cells?.let { appendTable(it, extractedText, searchText) }
            if (rows == null) {
                extractedText.append(block.extracted)
                searchText.append(block.search)
            }
            val draft = block.draft
            structureBlocks += StructureBlock(
                kind = draft.kind,
                level = draft.level?.takeIf { draft.kind == BlockKind.HEADING }?.coerceIn(1, 6),
                order = order,
                bbox = draft.bbox,
                extracted = TextRange(extractedStart, extractedText.length),
                search = TextRange(searchStart, searchText.length),
                rows = rows,
                latex = draft.latex?.takeIf { draft.kind == BlockKind.FORMULA },
            )
        }
        val extracted = extractedText.toString()
        val search = searchText.toString()
        if (TextNormalizer.normalize(extracted).search != search) return null
        val structure = PageStructure(
            version = STRUCTURE_VERSION,
            textSha256 = PdfModelRules.sha256Of(extracted),
            imageSha256 = imageSha256,
            parser = parser,
            blocks = structureBlocks,
        )
        return DerivedPage(extracted, search, structure, degraded = false)
    }

    private fun appendTable(
        cells: List<List<PreparedCell>>,
        extractedText: StringBuilder,
        searchText: StringBuilder,
    ): List<TableRow> =
        cells.mapIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                extractedText.append('\n')
                searchText.append('\n')
            }
            TableRow(
                row.mapIndexed { cellIndex, cell ->
                    if (cellIndex > 0) {
                        extractedText.append('\t')
                        searchText.append('\t')
                    }
                    val extractedStart = extractedText.length
                    val searchStart = searchText.length
                    extractedText.append(cell.extracted)
                    searchText.append(cell.search)
                    TableCell(
                        header = cell.draft.header,
                        rowSpan = cell.draft.rowSpan,
                        colSpan = cell.draft.colSpan,
                        extracted = TextRange(extractedStart, extractedText.length),
                        search = TextRange(searchStart, searchText.length),
                    )
                },
            )
        }
}
