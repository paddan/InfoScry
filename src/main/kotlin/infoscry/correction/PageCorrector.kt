package infoscry.correction

import infoscry.domain.ReadingText
import infoscry.extract.TextNormalizer
import infoscry.ocr.BlockKind
import infoscry.ocr.PageStructure
import infoscry.ocr.PageStructureStore
import infoscry.ocr.StructureBlock
import infoscry.ocr.StructureReference
import infoscry.ocr.TextRange
import infoscry.pdf.LayoutReference
import infoscry.pdf.PageTextLayout
import infoscry.pdf.PageTextLayoutStore
import infoscry.pdf.PdfModelRules
import infoscry.pdf.PositionedTextSpan
import infoscry.pdf.SpanGranularity
import java.nio.file.Path

/**
 * One page with the collection's corrections applied: its texts, the reading they were made from when they differ
 * from it, and the structure and layout references that describe the corrected text.
 */
data class CorrectedPage(
    val extractedText: String,
    val searchText: String,
    val reading: ReadingText?,
    val structureReference: StructureReference?,
    val layoutReference: LayoutReference?,
    val matchesByRule: Map<String, Int>,
)

/**
 * Applies a correction list to one page: the text, and the two artifacts whose offsets point into it.
 *
 * The search text is always derived from the corrected text the way every reading derives it, so it never drifts
 * from the evidence. The structure and the layout are shifted by the replacement's [OffsetMap] and checked again
 * against the corrected text; a structure that no longer holds falls back to one paragraph per line marked
 * degraded, and a layout that no longer holds is dropped, which leaves that page unselectable in an export. The
 * text itself is never at risk: an artifact is an optional extra on top of it.
 */
class PageCorrector(private val matcher: CorrectionMatcher) {

    /**
     * [base] is the page's reading (or its text when it has none), and [structure] and [layout] are the references
     * that describe [base].
     */
    fun correct(
        base: ReadingText,
        ordinal: Int,
        structure: StructureReference?,
        layout: LayoutReference?,
        artifactRoot: Path,
    ): CorrectedPage {
        val result = matcher.apply(base.extractedText)
        if (!result.changed) {
            return CorrectedPage(base.extractedText, base.searchText, null, structure, layout, emptyMap())
        }
        val extracted = result.text
        val search = TextNormalizer.normalize(extracted).search
        return CorrectedPage(
            extractedText = extracted,
            searchText = search,
            // The references handed in describe [base]; the corrected page gets its own, so the reading keeps these.
            reading = base.copy(structure = structure, layout = layout),
            structureReference = structure?.let { correctStructure(it, base, result, ordinal, artifactRoot) },
            layoutReference = layout?.let { correctLayout(it, base, result, artifactRoot) },
            matchesByRule = result.matchesByRule(),
        )
    }

    // ---- structure ----

    private fun correctStructure(
        reference: StructureReference,
        base: ReadingText,
        result: CorrectionResult,
        ordinal: Int,
        artifactRoot: Path,
    ): StructureReference? {
        val original = PageStructureStore.read(artifactRoot, reference, base.extractedText) ?: return null
        val extractedMap = OffsetMap(result.edits)
        val searchMap = OffsetMap(matcher.apply(base.searchText).edits)
        val shifted = try {
            original.copy(
                textSha256 = PdfModelRules.sha256Of(result.text),
                blocks = original.blocks.map { block ->
                    block.copy(
                        extracted = extractedMap.range(block.extracted),
                        search = searchMap.range(block.search),
                        rows = block.rows?.map { row ->
                            row.copy(
                                cells = row.cells.map { cell ->
                                    cell.copy(extracted = extractedMap.range(cell.extracted), search = searchMap.range(cell.search))
                                },
                            )
                        },
                    )
                },
            ).verifiedAgainst(result.text)
        } catch (broken: IllegalArgumentException) {
            degradedParagraphs(result.text, original) ?: return null
        }
        return PageStructureStore.write(artifactRoot, ordinal, shifted)
    }

    /** One paragraph per non-blank line of [text], marked degraded, or `null` when even that does not verify. */
    private fun degradedParagraphs(text: String, original: PageStructure): PageStructure? = try {
        var lineStart = 0
        val blocks = mutableListOf<StructureBlock>()
        for (line in text.split('\n')) {
            val lead = line.length - line.trimStart().length
            val content = line.trim()
            if (content.isNotEmpty()) {
                val start = lineStart + lead
                val end = start + content.length
                val searchStart = TextNormalizer.normalize(text.substring(0, start)).search.length
                val searchEnd = searchStart + TextNormalizer.normalize(text.substring(start, end)).search.length
                blocks += StructureBlock(
                    kind = BlockKind.PARAGRAPH,
                    order = blocks.size,
                    extracted = TextRange(start, end),
                    search = TextRange(searchStart, searchEnd),
                )
            }
            lineStart += line.length + 1
        }
        original.copy(textSha256 = PdfModelRules.sha256Of(text), blocks = blocks, degraded = true).verifiedAgainst(text)
    } catch (broken: IllegalArgumentException) {
        null
    }

    private fun OffsetMap.range(range: TextRange): TextRange = TextRange(mapStart(range.start), mapEnd(range.endExclusive))

    // ---- layout ----

    private fun correctLayout(
        reference: LayoutReference,
        base: ReadingText,
        result: CorrectionResult,
        artifactRoot: Path,
    ): LayoutReference? = try {
        val original = PageTextLayoutStore.read(artifactRoot, reference, base.extractedText)
        val spans = shiftedSpans(original.spans, result.edits, result.text)
        val corrected = original.copy(textSha256 = PdfModelRules.sha256Of(result.text), spans = spans)
            .verifiedAgainst(result.text)
        PageTextLayoutStore.write(artifactRoot, corrected)
    } catch (broken: RuntimeException) {
        // A layout that cannot be read or no longer fits the text is dropped: the page stays readable and
        // citable, and only its selectable text in an export is lost.
        null
    }

    /**
     * The spans of [spans] over the corrected text. Each edit belongs to the first span it overlaps, which takes the
     * whole replacement; a later span the edit also covered keeps only what lies after it, and is dropped when
     * nothing does. A word span whose new text holds a space becomes a line span.
     */
    private fun shiftedSpans(spans: List<PositionedTextSpan>, edits: List<TextEdit>, text: String): List<PositionedTextSpan> {
        val ordered = spans.sortedBy { it.start }
        val placed = placedEdits(edits)
        val owner = placed.associateWith { edit -> ordered.firstOrNull { it.start < edit.end && it.endExclusive > edit.start } }
        val map = OffsetMap(edits)
        return ordered.mapNotNull { span ->
            val startEdit = placed.firstOrNull { it.start < span.start && span.start < it.end }
            val endEdit = placed.firstOrNull { it.start < span.endExclusive && span.endExclusive < it.end }
            val start = when {
                startEdit == null -> map.mapStart(span.start)
                owner[startEdit] === span -> startEdit.newStart
                else -> startEdit.newEnd
            }
            val end = when {
                endEdit == null -> map.mapEnd(span.endExclusive)
                owner[endEdit] === span -> endEdit.newEnd
                else -> endEdit.newStart
            }
            if (end <= start) return@mapNotNull null
            val covered = text.substring(start, end)
            val granularity = if (span.granularity == SpanGranularity.WORD && covered.any(Char::isWhitespace)) {
                SpanGranularity.LINE
            } else {
                span.granularity
            }
            span.copy(start = start, endExclusive = end, granularity = granularity)
        }
    }

    private class PlacedEdit(val start: Int, val end: Int, val newStart: Int, val newEnd: Int)

    private fun placedEdits(edits: List<TextEdit>): List<PlacedEdit> {
        var delta = 0
        return edits.sortedBy { it.start }.map { edit ->
            val newStart = edit.start + delta
            delta += edit.replacement.length - (edit.endExclusive - edit.start)
            PlacedEdit(edit.start, edit.endExclusive, newStart, newStart + edit.replacement.length)
        }
    }
}
