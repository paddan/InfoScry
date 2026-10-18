package infoscry.ocr

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * The wire shape of a page's structure over one served window of its text. Shared by the source reader and the
 * page review, so both send the same JSON; it lives beside [PageStructure] because both consumers depend on it.
 */
@Serializable
data class StructureView(val blocks: List<StructureBlockView>)

@Serializable
data class StructureBlockView(
    val kind: BlockKind,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val level: Int? = null,
    val order: Int,
    val start: Int,
    val end: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rows: List<StructureRowView>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val latex: String? = null,
)

@Serializable
data class StructureRowView(val cells: List<StructureCellView>)

@Serializable
data class StructureCellView(
    val header: Boolean,
    val rowSpan: Int,
    val colSpan: Int,
    val start: Int,
    val end: Int,
)

/**
 * The blocks of [structure] that overlap the served window `[from, to)`, with every range clamped to that
 * window and shifted by `from`. A block's cells are all kept, so a table keeps its grid even where the window
 * cuts through it.
 */
internal fun windowStructure(structure: PageStructure, from: Int, to: Int): StructureView =
    StructureView(
        blocks = structure.blocks
            .filter { it.extracted.start < to && it.extracted.endExclusive > from }
            .map { block ->
                StructureBlockView(
                    kind = block.kind,
                    level = block.level,
                    order = block.order,
                    start = clampedStart(block.extracted, from, to),
                    end = clampedEnd(block.extracted, from, to),
                    rows = block.rows?.map { row ->
                        StructureRowView(
                            cells = row.cells.map { cell ->
                                StructureCellView(
                                    header = cell.header,
                                    rowSpan = cell.rowSpan,
                                    colSpan = cell.colSpan,
                                    start = clampedStart(cell.extracted, from, to),
                                    end = clampedEnd(cell.extracted, from, to),
                                )
                            },
                        )
                    },
                    latex = block.latex,
                )
            },
    )

private fun clampedStart(range: TextRange, from: Int, to: Int): Int =
    (maxOf(range.start, from).coerceAtMost(to)) - from

private fun clampedEnd(range: TextRange, from: Int, to: Int): Int {
    val start = maxOf(range.start, from).coerceAtMost(to)
    return maxOf(start, minOf(range.endExclusive, to)) - from
}
