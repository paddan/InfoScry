package infoscry.chunk

import infoscry.domain.ContentUnit
import infoscry.domain.SourceLocation
import infoscry.ocr.BlockKind
import infoscry.ocr.PageStructure
import infoscry.ocr.TableRow

/**
 * One chunk before the store gives it an identifier.
 *
 * [text] is what the embedder and the index receive. [startOffset] and [endOffset] are the span of the
 * unit's own text this chunk covers, which is what a citation points at; when the unit repeats a header,
 * [text] begins with that header and the span still addresses only the unit's characters.
 *
 * [tokenStart] and [tokenEnd] are the first and last token indices of [text] inside the passage this chunk
 * encodes. Anything before [tokenStart] or after [tokenEnd] is what the encoder added — the passage prefix
 * and the special tokens — so a reader can see what the budget was spent on, and [tokenCount] is the whole
 * passage including them.
 */
data class ChunkDraft(
    val ordinal: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val tokenCount: Int,
    val tokenStart: Int,
    val tokenEnd: Int,
) {
    init {
        require(text.isNotEmpty()) { "a chunk carries text" }
        require(startOffset >= 0) { "ChunkDraft.startOffset must not be negative, was $startOffset" }
        require(endOffset > startOffset) {
            "ChunkDraft.endOffset ($endOffset) must follow startOffset ($startOffset)"
        }
        require(tokenCount >= 0) { "ChunkDraft.tokenCount must not be negative, was $tokenCount" }
        require(tokenStart in 0..<tokenCount) { "ChunkDraft.tokenStart ($tokenStart) is outside the passage" }
        require(tokenEnd in tokenStart..<tokenCount) {
            "ChunkDraft.tokenEnd ($tokenEnd) must follow tokenStart ($tokenStart) inside the passage"
        }
    }
}

/**
 * What chunking one unit produced.
 *
 * [headerDropped] says that the unit repeats a header which did not leave room for a single body token, so
 * the chunks were cut without it. That is reported rather than kept quiet: the header's own characters are
 * still covered by the chunk that contains them, but the repetition a reader of a later chunk would have
 * wanted is gone, and only the caller can decide whether to say so.
 */
data class ChunkPlan(val drafts: List<ChunkDraft>, val headerDropped: Boolean)

/**
 * Splits one content unit into the passages an embedder can take.
 *
 * Three rules shape every boundary, and each exists because of a failure it prevents:
 *
 * - **A chunk never crosses a unit.** A chunk's citation is the unit's locator, so a chunk holding two
 *   locators could not be cited at all.
 * - **The whole encoded passage fits the budget, not the body.** The measure is what the model receives:
 *   the prefix, the special tokens, a repeated header, and the body. Shrinking a body by an assumed prefix
 *   length overflows the model by exactly the length of the assumption.
 * - **Every searchable character of the unit is in some chunk.** A walk that stopped at the first passage
 *   that did not fit, or that dropped a tail too short to fill one, would lose text that a search can then
 *   never find. Overlap makes consecutive chunks share text; coverage makes their union the unit — minus
 *   blank pages and the isolated known `Â` artifact, which cannot carry evidence and are not embedded.
 *
 * A page with a structure (see [StructureLayout]) is chunked inside its segments and at its block and table-row
 * ends instead; a page without one is chunked as described here, unchanged.
 *
 * Where the text offers a line or paragraph break near the budget, the chunk ends there, because a passage
 * cut mid-sentence reads badly in a snippet and mid-word embeds worse. The break is only taken when it keeps
 * at least half the budget: a single newline early in a long unbroken run would otherwise chain into a walk
 * of tiny chunks.
 */
class Chunker(
    private val counter: TokenCounter,
    val version: String = CHUNKER_VERSION,
) {

    /** The tokenizer this chunker measures with; part of what stored chunks are keyed by. */
    val counterId: String get() = counter.id

    /**
     * Chunks [unit]. With a [structure] of the same page, the boundaries follow it (see [StructureLayout]);
     * without one the text is walked as plain text. The structure is a parameter, not read here, because
     * the callers hold it differently (a staged page's reference, a stored unit's reference) and the same
     * unit has to chunk identically on every path.
     */
    fun chunk(
        unit: ContentUnit,
        maxSequenceTokens: Int = DEFAULT_MAX_SEQUENCE_TOKENS,
        overlapTokens: Int = DEFAULT_OVERLAP_TOKENS,
        structure: PageStructure? = null,
    ): ChunkPlan {
        require(maxSequenceTokens > 0) { "maxSequenceTokens must be positive, was $maxSequenceTokens" }
        require(overlapTokens >= 0) { "overlapTokens must not be negative, was $overlapTokens" }
        require(overlapTokens < maxSequenceTokens) {
            "an overlap of $overlapTokens tokens leaves no room inside a budget of $maxSequenceTokens"
        }

        val text = unit.searchText
        if (!hasSearchableWord(text)) return ChunkPlan(drafts = emptyList(), headerDropped = false)

        val overhead = counter.encodePassage("").totalTokens
        val layout = structure?.let { StructureLayout.of(it, text) }
        val drafts = mutableListOf<ChunkDraft>()
        var headerDropped = false

        if (layout == null) {
            val declaredHeader = repeatedHeaderOf(unit)
            // A header is worth repeating only if a body token still fits beside it. Otherwise the unit is
            // chunked as plain text: the header's characters stay covered by whichever chunk holds them.
            val header = declaredHeader?.let { candidate -> usableHeader(candidate, overhead, maxSequenceTokens) }
            walk(text, 0, text.length, maxSequenceTokens, overlapTokens, overhead, drafts, layout = null) { header }
            headerDropped = declaredHeader != null && header == null
        } else {
            val tableHeaders = HashMap<Int, RepeatedHeader?>()
            for ((from, to) in layout.segments) {
                walk(text, from, to, maxSequenceTokens, overlapTokens, overhead, drafts, layout) { start ->
                    val table = layout.tableContaining(start)
                    val row = table?.headerRow?.takeIf { start >= it.last + 1 }
                    if (table == null || row == null) {
                        null
                    } else {
                        if (table.start !in tableHeaders) {
                            val candidate = text.substring(row.first, row.last + 1) + "\n"
                            tableHeaders[table.start] = usableHeader(candidate, overhead, maxSequenceTokens)
                        }
                        val header = tableHeaders[table.start]
                        if (header == null) headerDropped = true
                        header
                    }
                }
            }
        }
        // A passage with no word in it is dropped rather than embedded, and the ordinals close up so the
        // chunks that remain still count from zero. The unit keeps every character either way: the source
        // viewer reads units, and a passage that cannot carry a word cannot carry evidence.
        val searchable = drafts
            .filter { hasSearchableWord(it.text) }
            .mapIndexed { ordinal, draft -> draft.copy(ordinal = ordinal) }
        return ChunkPlan(drafts = searchable, headerDropped = headerDropped)
    }

    /** [candidate] with its cost, or null when no body token would fit beside it. */
    private fun usableHeader(candidate: String, overhead: Int, maxSequenceTokens: Int): RepeatedHeader? {
        val tokens = counter.encodePassage(candidate).bodyTokens
        return if (tokens <= maxSequenceTokens - overhead - 1) RepeatedHeader(candidate, tokens) else null
    }

    /**
     * Walks `[from, to)` of [text] and appends one draft per chunk.
     *
     * [headerAt] is the header the chunk starting at a given offset repeats. A spreadsheet repeats one
     * header for the whole unit; a table in a structured page repeats its own header row only in chunks that
     * start inside it, so the header can change between chunks.
     */
    private fun walk(
        text: String,
        from: Int,
        to: Int,
        maxSequenceTokens: Int,
        overlapTokens: Int,
        overhead: Int,
        drafts: MutableList<ChunkDraft>,
        layout: StructureLayout?,
        headerAt: (Int) -> RepeatedHeader?,
    ) {
        var start = from
        // Where the previous chunk ended. A chunk has to reach past it, or the walk would emit a chunk
        // that covers nothing new.
        var previousEnd = from
        while (start < to) {
            val repeated = headerAt(start)
            val header = repeated?.text
            val headerTokens = repeated?.tokens ?: 0
            val bodyBudget = maxSequenceTokens - overhead - headerTokens
            val headerLength = header?.length ?: 0
            val limit = furthestFittingEnd(
                text = text,
                start = start,
                to = to,
                header = header,
                maxSequenceTokens = maxSequenceTokens,
            )
            val floor = maxOf(start, previousEnd)
            // Nothing fits — not even one character beside the prefix. The rest becomes a single over-budget
            // chunk rather than a silent truncation: refusing an oversized passage belongs to the embedder,
            // which can say so, not to a chunker that would have to drop text to keep quiet.
            val end = when {
                limit <= start -> to
                layout != null && limit >= to -> to
                else -> {
                    val structural = layout?.let {
                        structuralEnd(
                            text, it, start, limit, floor, header, overhead, headerTokens, bodyBudget,
                            maxSequenceTokens,
                        )
                    }
                    structural ?: preferredEnd(
                        text = text,
                        start = start,
                        limit = limit,
                        floor = floor,
                        header = header,
                        overhead = overhead,
                        headerTokens = headerTokens,
                        bodyBudget = bodyBudget,
                        maxSequenceTokens = maxSequenceTokens,
                    )
                }
            }

            val body = text.substring(start, end)
            val composed = if (header == null) body else header + body
            val passage = counter.encodePassage(composed)
            // A counter that read no body token from non-empty text has broken its own contract, and the
            // offsets it would produce address a passage whose text is not in the chunk. That is worse than
            // failing: every citation built from it would point at the wrong characters. The message carries
            // the length rather than the text, because an exception message ends up in a log.
            val bodyRange = passage.bodyRange ?: error(
                "the counter '${counter.id}' read no body token from a ${composed.length}-character chunk, " +
                    "so the chunk has no offsets in the text it was built from",
            )
            drafts += ChunkDraft(
                ordinal = drafts.size,
                text = composed,
                startOffset = start,
                endOffset = end,
                tokenCount = passage.totalTokens,
                tokenStart = bodyRange.first,
                tokenEnd = bodyRange.last,
            )

            if (end >= to) break
            previousEnd = end
            val chunkStart = start
            start = nextStart(
                unitStart = start,
                unitEnd = end,
                // The passage's own spans address the composed text, so the repeated header is taken off and
                // the chunk's beginning is put back on: what the walk needs are offsets in the unit.
                bodySpans = passage.tokens.mapNotNull { it.span }.map { start + it.first - headerLength },
                overlapTokens = overlapTokens,
            )
            if (layout != null && start < end) {
                // The overlap's token start can fall inside a table row, which would put a partial row behind
                // the repeated header. Starting at the row (or block) start instead keeps rows whole, but only
                // when that still reaches text this chunk did not cover; otherwise (a row that alone exceeds
                // the budget) the token start stands.
                val snapped = layout.lastStartAtOrBefore(start)
                if (snapped != null && snapped > chunkStart && snapped < start) {
                    val snappedHeader = headerAt(snapped)?.text
                    if (furthestFittingEnd(text, snapped, to, snappedHeader, maxSequenceTokens) > end) start = snapped
                }
            }
        }
    }

    /**
     * The end of a chunk at a structural boundary, or null to fall back to the plain rules.
     *
     * A block end is preferred, then a table row's end. Both are taken only when the shortened chunk keeps
     * half the body budget, except that a row end is also taken when the row that straddles the limit would
     * fit a chunk of its own: it then moves whole into the next chunk. When that row alone exceeds the budget
     * there is no boundary to take inside it, and the plain split applies.
     */
    private fun structuralEnd(
        text: String,
        layout: StructureLayout,
        start: Int,
        limit: Int,
        floor: Int,
        header: String?,
        overhead: Int,
        headerTokens: Int,
        bodyBudget: Int,
        maxSequenceTokens: Int,
    ): Int? {
        fun worthTaking(end: Int): Boolean {
            val kept = counter.encodePassage(compose(header, text, start, end)).totalTokens
            return kept - headerTokens - overhead >= bodyBudget / 2 && kept <= maxSequenceTokens
        }
        layout.lastBlockEnd(floor, limit)?.let { if (worthTaking(it)) return it }
        val rowEnd = layout.lastRowEnd(floor, limit) ?: return null
        if (worthTaking(rowEnd)) return rowEnd
        val straddling = layout.rowAfter(rowEnd) ?: return null
        val alone = counter.encodePassage(text.substring(straddling.first, straddling.last + 1)).totalTokens
        return if (alone <= maxSequenceTokens - headerTokens) rowEnd else null
    }

    /**
     * The furthest character this chunk can reach and still fit the model's passage budget.
     *
     * Finding that boundary is two searches, not one. A plain binary search between `start + 1` and the
     * unit's own end would measure candidates half the *remaining unit* away on its very first probe — and
     * every later chunk of the same unit would do the same against its own, still-huge remainder. For a
     * unit with no interior structure (a heading-free document is one unit end to end) that cost is paid
     * once per chunk against a length that barely shrinks, which turns chunking one large unit into work
     * proportional to its length *squared*. Growing the candidate end geometrically from `start` instead
     * finds a bracket around the true boundary — one bound that fits, one that does not — using probes no
     * larger than the boundary itself, however large the rest of the unit is; only then does the binary
     * search narrow that small bracket. The answer is verified before it is used, so a tokenizer whose
     * length is not perfectly monotone in its input can make the chunk conservative but never too long.
     */
    private fun furthestFittingEnd(
        text: String,
        start: Int,
        to: Int,
        header: String?,
        maxSequenceTokens: Int,
    ): Int {
        if (start >= to) return start
        if (!fits(text, start, start + 1, header, maxSequenceTokens)) return start

        var low = start + 1
        var high = to
        // Starts at 2, not 1: the check above already proved `start + 1` fits, so the first candidate this
        // loop tests should be the next untested one.
        var span = 2
        while (start + span < high) {
            val candidate = start + span
            if (!fits(text, start, candidate, header, maxSequenceTokens)) {
                high = candidate
                break
            }
            low = candidate
            span *= 2
        }

        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (fits(text, start, middle, header, maxSequenceTokens)) low = middle else high = middle - 1
        }
        var end = low
        while (end > start + 1 && !fits(text, start, end, header, maxSequenceTokens)) end--
        return end
    }

    private fun fits(text: String, start: Int, end: Int, header: String?, maxSequenceTokens: Int): Boolean =
        counter.encodePassage(compose(header, text, start, end)).totalTokens <= maxSequenceTokens

    /**
     * The end of the chunk, moved back to a line break when there is one worth taking.
     *
     * A break is taken only when the shortened chunk still spends at least half the body budget on body: a
     * newline one word into a long unbroken run is not a boundary worth chaining tiny chunks from.
     */
    private fun preferredEnd(
        text: String,
        start: Int,
        limit: Int,
        floor: Int,
        header: String?,
        overhead: Int,
        headerTokens: Int,
        bodyBudget: Int,
        maxSequenceTokens: Int,
    ): Int {
        val lineBreak = text.lastIndexOf('\n', limit - 1)
        if (lineBreak <= floor) return limit
        val kept = counter.encodePassage(compose(header, text, start, lineBreak)).totalTokens
        val bodyTokens = kept - headerTokens - overhead
        val worthTaking = bodyTokens >= bodyBudget / 2 && kept <= maxSequenceTokens
        return if (worthTaking) lineBreak else limit
    }

    /**
     * Where the next chunk starts: the beginning of the token that lies [overlapTokens] body tokens before
     * the end of this one.
     *
     * The overlap is measured in the counter's own tokens rather than in characters, because that is what
     * the overlap is for — the next passage has to carry enough of the previous one for a sentence split
     * across the boundary to still be found. Two guards keep the walk honest: it never starts a chunk before
     * the previous one began plus one character, and a body with no more than the overlap's worth of tokens
     * is left to tile the text instead of overlapping it into a stalled walk.
     */
    private fun nextStart(
        unitStart: Int,
        unitEnd: Int,
        bodySpans: List<Int>,
        overlapTokens: Int,
    ): Int {
        if (overlapTokens == 0 || bodySpans.size <= overlapTokens) return unitEnd
        val overlapStart = bodySpans[bodySpans.size - overlapTokens]
        return maxOf(unitStart + 1, overlapStart)
    }

    /**
     * The header a unit repeats, when its format has one.
     *
     * A table-shaped unit — a spreadsheet range or a CSV — starts with its header row, because that is what
     * the extractors put first so a later row still says what its columns mean. Repeating it in every chunk is
     * what keeps a chunk readable on its own; a prose unit has no such line and repeats nothing.
     */
    private fun repeatedHeaderOf(unit: ContentUnit): String? {
        if (unit.locator !is SourceLocation.SpreadsheetRange) return null
        val lineBreak = unit.searchText.indexOf('\n')
        if (lineBreak <= 0) return null
        return unit.searchText.substring(0, lineBreak) + "\n"
    }

    private fun compose(header: String?, text: String, start: Int, end: Int): String =
        if (header == null) text.substring(start, end) else header + text.substring(start, end)

    companion object {
        /**
         * What produced a chunk's boundaries. It is stored with them, so a change to the arithmetic
         * re-chunks rather than mixing two rules in one index.
         */
        const val CHUNKER_VERSION: String = "4"

        /** The model's passage budget: what a complete input may spend, prefix and special tokens included. */
        const val DEFAULT_MAX_SEQUENCE_TOKENS: Int = 512

        /** How much of the previous chunk's body the next one repeats, so a boundary does not lose meaning. */
        const val DEFAULT_OVERLAP_TOKENS: Int = 100
    }
}

/**
 * Whether [text] carries any searchable content. Single letters, digits, formulas and non-Latin text can
 * all be evidence; only blank text and the isolated known doubled-encoding artifact `Â` are rejected.
 *
 * Extraction stays faithful to the page. Chunker version 3 changes the indexing policy, so reindexing
 * regenerates chunks saved under the earlier two-character heuristic.
 */
internal fun hasSearchableWord(text: String): Boolean {
    val compact = text.filterNot(Char::isWhitespace)
    return compact.isNotEmpty() && compact != "Â" &&
        text.codePoints().anyMatch { Character.isLetterOrDigit(it) }
}

/**
 * Whether [text] carries meaning a vector can stand for: a run of at least two letters or digits, or one
 * character of a script that writes a word in one (Han, kana, Hangul).
 *
 * A passage that fails this — OCR noise such as `? 1 . . . . . .` or `: P . ---` — stays a keyword result for
 * the exact character, but is never a semantic neighbour: its vector says nothing, so it sits close to every
 * query's.
 */
internal fun carriesMeaning(text: String): Boolean {
    var run = 0
    for (codePoint in text.codePoints()) {
        if (Character.isIdeographic(codePoint) || Character.UnicodeScript.of(codePoint) in WORD_SCRIPTS) {
            return true
        }
        // A combining mark belongs to the letter before it: it neither lengthens nor breaks the run.
        if (isCombiningMark(codePoint)) continue
        run = if (Character.isLetterOrDigit(codePoint)) run + 1 else 0
        if (run >= 2) return true
    }
    return false
}

private val WORD_SCRIPTS = setOf(
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
)

private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

/** A header repeated in front of a chunk, with the body tokens it costs. */
private class RepeatedHeader(val text: String, val tokens: Int)

/**
 * Where a page's structure lets chunking cut, in offsets of the unit's search text.
 *
 * - [segments] are the maximal runs of blocks that are not page headers or footers; no chunk crosses one or
 *   covers an excluded block, because a running header repeated on every page is not evidence of the page.
 * - Block ends and table-row ends are the preferred cuts. A chunk never ends inside a table row unless the
 *   row alone exceeds the budget.
 * - A table's header row is its first row with a header cell, else its first row; a chunk that starts inside
 *   the table repeats it.
 */
private class StructureLayout(
    val segments: List<Pair<Int, Int>>,
    private val blockEnds: IntArray,
    blockStarts: List<Int>,
    private val rowRanges: List<IntRange>,
    private val tables: List<TableLayout>,
) {
    private val rowEnds = rowRanges.map { it.last + 1 }.toIntArray()
    private val starts = (blockStarts + rowRanges.map { it.first }).distinct().sorted().toIntArray()

    class TableLayout(val start: Int, val end: Int, val headerRow: IntRange?)

    /** The table [offset] lies strictly inside, so a chunk starting there needs the table's header. */
    fun tableContaining(offset: Int): TableLayout? =
        tables.firstOrNull { it.headerRow != null && offset > it.start && offset < it.end }

    /** The nearest block or table-row start at or before [offset]. */
    fun lastStartAtOrBefore(offset: Int): Int? {
        var index = starts.binarySearch(offset)
        if (index < 0) index = -index - 2
        return if (index >= 0) starts[index] else null
    }

    fun lastBlockEnd(floor: Int, limit: Int): Int? = lastIn(blockEnds, floor, limit)

    fun lastRowEnd(floor: Int, limit: Int): Int? = lastIn(rowEnds, floor, limit)

    /** The first row that begins at or after [end], if there is one. */
    fun rowAfter(end: Int): IntRange? = rowRanges.firstOrNull { it.first >= end }

    private fun lastIn(sorted: IntArray, floor: Int, limit: Int): Int? {
        var index = sorted.binarySearch(limit)
        if (index < 0) index = -index - 2
        return if (index >= 0 && sorted[index] > floor) sorted[index] else null
    }

    companion object {
        /**
         * The layout of [structure], or null when it does not address [text]. An unusable structure falls
         * back to plain chunking, which puts page headers and footers back into chunks.
         */
        fun of(structure: PageStructure, text: String): StructureLayout? {
            val blocks = structure.blocks.sortedBy { it.search.start }
            if (blocks.isEmpty() || blocks.any { it.search.endExclusive > text.length }) return null
            val excluded = setOf(BlockKind.PAGE_HEADER, BlockKind.PAGE_FOOTER)
            val segments = mutableListOf<Pair<Int, Int>>()
            var segmentStart = -1
            var segmentEnd = -1
            for (block in blocks) {
                if (block.kind in excluded) {
                    if (segmentStart >= 0) segments += segmentStart to segmentEnd
                    segmentStart = -1
                } else {
                    if (segmentStart < 0) segmentStart = block.search.start
                    segmentEnd = block.search.endExclusive
                }
            }
            if (segmentStart >= 0) segments += segmentStart to segmentEnd

            val kept = blocks.filter { it.kind !in excluded }
            val rows = mutableListOf<IntRange>()
            val tables = mutableListOf<TableLayout>()
            for (block in kept) {
                val tableRows = block.rows ?: continue
                val populated = tableRows.filter { it.cells.isNotEmpty() }
                fun rangeOf(row: TableRow): IntRange =
                    row.cells.minOf { it.search.start } until row.cells.maxOf { it.search.endExclusive }
                rows += populated.map(::rangeOf)
                val headerRow = populated.firstOrNull { row -> row.cells.any { it.header } } ?: populated.firstOrNull()
                tables += TableLayout(block.search.start, block.search.endExclusive, headerRow?.let(::rangeOf))
            }
            return StructureLayout(
                segments = segments,
                blockEnds = kept.map { it.search.endExclusive }.distinct().sorted().toIntArray(),
                blockStarts = kept.map { it.search.start },
                rowRanges = rows.sortedBy { it.first },
                tables = tables,
            )
        }
    }
}
