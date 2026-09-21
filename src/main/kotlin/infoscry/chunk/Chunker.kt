package infoscry.chunk

import infoscry.domain.ContentUnit
import infoscry.domain.SourceLocation

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
 * - **Every character of the unit is in some chunk.** A walk that stopped at the first passage that did not
 *   fit, or that dropped a tail too short to fill one, would lose text that a search can then never find.
 *   Overlap makes consecutive chunks share text; coverage makes their union the unit.
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

    fun chunk(
        unit: ContentUnit,
        maxSequenceTokens: Int = DEFAULT_MAX_SEQUENCE_TOKENS,
        overlapTokens: Int = DEFAULT_OVERLAP_TOKENS,
    ): ChunkPlan {
        require(maxSequenceTokens > 0) { "maxSequenceTokens must be positive, was $maxSequenceTokens" }
        require(overlapTokens >= 0) { "overlapTokens must not be negative, was $overlapTokens" }
        require(overlapTokens < maxSequenceTokens) {
            "an overlap of $overlapTokens tokens leaves no room inside a budget of $maxSequenceTokens"
        }

        val text = unit.searchText
        if (text.isBlank()) return ChunkPlan(drafts = emptyList(), headerDropped = false)

        val overhead = counter.encodePassage("").totalTokens
        val declaredHeader = repeatedHeaderOf(unit)
        val header = declaredHeader?.takeIf { candidate ->
            // A header is worth repeating only if a body token still fits beside it. Otherwise the unit is
            // chunked as plain text: the header's characters stay covered by whichever chunk holds them.
            counter.encodePassage(candidate).bodyTokens <= maxSequenceTokens - overhead - 1
        }
        val headerTokens = header?.let { counter.encodePassage(it).bodyTokens } ?: 0
        val bodyBudget = maxSequenceTokens - overhead - headerTokens
        val headerLength = header?.length ?: 0

        val drafts = mutableListOf<ChunkDraft>()
        var start = 0
        // Where the previous chunk ended. A chunk has to reach past it, or the walk would emit a chunk
        // that covers nothing new.
        var previousEnd = 0
        while (start < text.length) {
            val limit = furthestFittingEnd(
                text = text,
                start = start,
                header = header,
                maxSequenceTokens = maxSequenceTokens,
            )
            // Nothing fits — not even one character beside the prefix. The rest becomes a single over-budget
            // chunk rather than a silent truncation: refusing an oversized passage belongs to the embedder,
            // which can say so, not to a chunker that would have to drop text to keep quiet.
            val end = if (limit <= start) {
                text.length
            } else {
                preferredEnd(
                    text = text,
                    start = start,
                    limit = limit,
                    floor = maxOf(start, previousEnd),
                    header = header,
                    overhead = overhead,
                    headerTokens = headerTokens,
                    bodyBudget = bodyBudget,
                    maxSequenceTokens = maxSequenceTokens,
                )
            }

            val body = text.substring(start, end)
            val composed = if (header == null) body else header + body
            val passage = counter.encodePassage(composed)
            val bodyRange = passage.bodyRange ?: 0..(passage.totalTokens - 1).coerceAtLeast(0)
            drafts += ChunkDraft(
                ordinal = drafts.size,
                text = composed,
                startOffset = start,
                endOffset = end,
                tokenCount = passage.totalTokens,
                tokenStart = bodyRange.first,
                tokenEnd = bodyRange.last,
            )

            if (end >= text.length) break
            previousEnd = end
            start = nextStart(
                unitStart = start,
                unitEnd = end,
                // The passage's own spans address the composed text, so the repeated header is taken off and
                // the chunk's beginning is put back on: what the walk needs are offsets in the unit.
                bodySpans = passage.tokens.mapNotNull { it.span }.map { start + it.first - headerLength },
                overlapTokens = overlapTokens,
            )
        }
        return ChunkPlan(drafts = drafts, headerDropped = declaredHeader != null && header == null)
    }

    /**
     * The furthest character this chunk can reach and still fit the model's passage budget.
     *
     * The walk is a binary search rather than a character-by-character expansion: the encoded length grows
     * with the text, so the boundary is found in a logarithmic number of measurements instead of one per
     * character. The answer is verified before it is used, so a tokenizer whose length is not perfectly
     * monotone in its input can make the chunk conservative but never too long.
     */
    private fun furthestFittingEnd(
        text: String,
        start: Int,
        header: String?,
        maxSequenceTokens: Int,
    ): Int {
        var low = start + 1
        var high = text.length
        if (!fits(text, start, low, header, maxSequenceTokens)) return start
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
        const val CHUNKER_VERSION: String = "1"

        /** The model's passage budget: what a complete input may spend, prefix and special tokens included. */
        const val DEFAULT_MAX_SEQUENCE_TOKENS: Int = 512

        /** How much of the previous chunk's body the next one repeats, so a boundary does not lose meaning. */
        const val DEFAULT_OVERLAP_TOKENS: Int = 100
    }
}
