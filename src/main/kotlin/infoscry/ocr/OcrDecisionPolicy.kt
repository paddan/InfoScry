package infoscry.ocr

import infoscry.storage.OcrReviewStore
import infoscry.storage.OcrReviewStore.OcrValidationRecord
import kotlinx.serialization.Serializable

/**
 * A stretch of one reading, as character offsets: [startOffset] is its first character and [endOffset] the
 * one after its last.
 *
 * Offsets are into the reading they belong to and never into a page, because the two readings of one page
 * may have different lengths: an offset that did not say which reading it is into would be about nothing.
 * A reason and a finding both name the text they were made about this way.
 */
@Serializable
data class TextSpan(val startOffset: Int, val endOffset: Int) {

    init {
        require(startOffset >= 0) { "a span starts at or after the first character, was $startOffset" }
        require(endOffset > startOffset) { "span $startOffset..$endOffset is empty rather than a stretch" }
    }

    /** How many characters the stretch holds. */
    val length: Int get() = endOffset - startOffset

    /** Whether this stretch and [other] share at least one character. */
    fun overlaps(other: TextSpan): Boolean = startOffset < other.endOffset && other.startOffset < endOffset
}

/**
 * What one deterministic check is about.
 *
 * These checks see two texts and nothing else, so each code says what was *found* rather than what it means
 * for the page: `MISSING_REGION` is a stretch of the baseline the candidate does not hold, and whether that
 * is a better reading is a person's judgement, not this vocabulary's. The sensitive codes exist because an
 * invented name, number, date or negation is the failure a fluent reading hides best, so each is called out
 * by name rather than folded into "the texts differ".
 */
enum class DiagnosticCode {

    /** One side has no legible text at all. */
    EMPTY_OUTPUT,

    /** A reading stops in the middle of a token: a trailing hyphen or an opening bracket nothing closes. */
    TRUNCATED_OUTPUT,

    /** A non-blank line repeats the line before it, which is a reading running on rather than a page. */
    REPEATED_TEXT,

    /** Control characters, replacement marks, or a run of the same punctuation mark. */
    SUSPICIOUS_SEQUENCE,

    /** A stretch of the baseline the candidate has nothing in its place for. */
    MISSING_REGION,

    /** A stretch the candidate has that the baseline has nothing in its place for. */
    ADDED_REGION,

    /** The two readings differ in length by more than [LENGTH_CHANGE_RATIO] of the baseline's. */
    LENGTH_CHANGE,

    /** A region the two readings both have and that they do not say the same thing in. */
    ALIGNED_DIFFERENCE,

    /** A capitalised word differs between the two readings of a region. */
    NAME_CHANGED,

    /** A number differs between the two readings of a region. */
    NUMBER_CHANGED,

    /** A date differs between the two readings of a region. */
    DATE_CHANGED,

    /** A negation differs between the two readings of a region. */
    NEGATION_CHANGED,

    /** Both readings are empty. An identically empty pair is evidence of nothing, so it is not a no-op. */
    EMPTY_PAIR,

    /** There is no published reading to compare the candidate with. */
    NO_BASELINE,
    ;

    companion object {

        /** How much longer one reading has to be than the other before the length itself is reported. */
        const val LENGTH_CHANGE_RATIO: Double = 0.2

        /**
         * The codes that name a place in the two readings: the ones a reviewer's reason may be tied to.
         *
         * [LENGTH_CHANGE] is deliberately not among them. It is a statement about both readings at once, so
         * a reason that pointed at it would validate any claim anywhere on the page.
         */
        val REGION_CODES: Set<DiagnosticCode> = setOf(
            MISSING_REGION,
            ADDED_REGION,
            ALIGNED_DIFFERENCE,
            NAME_CHANGED,
            NUMBER_CHANGED,
            DATE_CHANGED,
            NEGATION_CHANGED,
        )

        /**
         * The codes that keep a difference away from automatic replacement, whatever a policy has been
         * measured to allow.
         *
         * The pilot never reaches this question, because it replaces nothing without a person. It is stated
         * here because it is a property of what the checks found rather than of one policy version: a page
         * that lost a region, invented a name or repeats itself is not a page a threshold should decide.
         */
        val BLOCKING_CODES: Set<DiagnosticCode> = setOf(
            EMPTY_OUTPUT,
            EMPTY_PAIR,
            NO_BASELINE,
            TRUNCATED_OUTPUT,
            REPEATED_TEXT,
            SUSPICIOUS_SEQUENCE,
            MISSING_REGION,
            ADDED_REGION,
            NAME_CHANGED,
            NUMBER_CHANGED,
            DATE_CHANGED,
            NEGATION_CHANGED,
        )
    }
}

/**
 * One thing a deterministic check found, as its code and the place it was found.
 *
 * A finding about one side alone leaves the other side's span absent; a finding about the comparison as a
 * whole — two different lengths, or two empty readings — carries neither. The spans are offsets into the
 * readings themselves rather than excerpts of them, so a reason can be shown beside the text it is about
 * without this record carrying page text of its own.
 */
data class DiagnosticFinding(
    val code: DiagnosticCode,
    val baselineSpan: TextSpan? = null,
    val candidateSpan: TextSpan? = null,
)

/** Whether a reason came from the deterministic checks or from the reviewer's own answer. */
@Serializable
enum class ReasonOrigin { DETERMINISTIC, REVIEWER }

/**
 * One reason behind a review, bounded and tied to the place it is about.
 *
 * A deterministic reason carries the code of the check that found it and no words of its own, so a stored
 * review cannot be a paragraph about a page. A reviewer's reason carries the reviewer's own bounded
 * explanation, which is untrusted text: it is shown to a person beside the readings and decides nothing.
 */
@Serializable
data class ReviewReason(
    val code: String,
    val origin: ReasonOrigin,
    val baselineSpan: TextSpan? = null,
    val candidateSpan: TextSpan? = null,
    val explanation: String? = null,
) {
    init {
        require(code.isNotBlank()) { "a reason says what it is about" }
        require(baselineSpan != null || candidateSpan != null || origin == ReasonOrigin.DETERMINISTIC) {
            "a reviewer's reason is about a place: one without a span points at nothing"
        }
    }
}

/**
 * What a reviewer recommends about the two readings of one comparison.
 *
 * It is the model's own opinion about two readings and never a decision: the disposition below is what may
 * happen to a difference, and in pilot mode the recommendation does not move it at all. It is this
 * application's own vocabulary rather than the request's: a review request names its two readings as sides
 * and says nothing about which is which, so [OcrComparisonService] maps the side the model chose onto
 * `EXISTING_BETTER` (the published reading) and `NEW_BETTER` (the candidate), which is where the order the
 * request used is known.
 */
@Serializable
enum class ReviewerRecommendation { EXISTING_BETTER, NEW_BETTER, UNCERTAIN }

/**
 * What a review says should happen to the candidate text.
 *
 * - [KEEP] leaves the page as it is; the candidate is not published.
 * - [PROPOSE] leaves the page as it is and asks a person: the difference is pending manual review.
 * - [APPROVE] publishes the candidate without a person, which needs an accepted validation record.
 */
enum class PublicationDisposition { KEEP, PROPOSE, APPROVE }

/**
 * Which reviewer judged one comparison, as an accepted validation record's scope is checked against it.
 *
 * A comparison is judged by one immutable reviewer revision under one prompt version, and that pair is what
 * decides whether an accepted record is about *this* work: the comparison supplies neither the acceptance
 * nor a record's identity, only which reviewer it asked.
 */
data class ReviewerScope(val reviewerRevisionId: String, val reviewPromptVersion: Int)

/**
 * What the deterministic checks found about one comparison, before any reviewer is asked.
 *
 * The checks run first and are reported whatever the reviewer answers: they are the part of a review a
 * person can re-derive, and they are what a reviewer's claimed spans are validated against. Nothing here is
 * a verdict — [needsDecision] and [allowsAutomaticReplacement] are questions the policy asks, and the
 * sensitive codes stay blocking even where a policy was measured.
 */
data class PageDiagnostics(val findings: List<DiagnosticFinding>) {

    /** The code of every finding, for the callers that only ask whether something was found. */
    val codes: Set<DiagnosticCode> get() = findings.mapTo(linkedSetOf()) { finding -> finding.code }

    /**
     * Whether a person has to decide about this page.
     *
     * True when any check found anything: two readings that differ at all left a trace here, and the two
     * cases with no trace — identical text, and text that differs only in trailing whitespace — are the ones
     * nothing has to be decided about.
     */
    val needsDecision: Boolean get() = findings.isNotEmpty()

    /**
     * Whether every check the pilot keeps would accept this difference if a policy were allowed to decide.
     *
     * False for the findings a threshold has no business deciding: a lost region, an invented or altered
     * name, number, date or negation, a repetition, a suspicious sequence, or one side being empty.
     */
    val allowsAutomaticReplacement: Boolean get() =
        findings.none { finding -> finding.code in DiagnosticCode.BLOCKING_CODES }

    /**
     * Whether [baselineSpan]/[candidateSpan] is a place the checks established as differing.
     *
     * A reviewer's reason is only a reason about *these* two readings when it points at a place they differ
     * in, so a claim about a stretch the readings agree on is dropped rather than stored — and so is a claim
     * about a side the finding says nothing was found on: a region only the candidate reads is a place on the
     * candidate's side alone, and a reason that also names a baseline stretch there is a claim about text the
     * checks did not find, exactly as one that names no place on the candidate's side points at nothing.
     */
    fun validates(baselineSpan: TextSpan?, candidateSpan: TextSpan?): Boolean {
        if (baselineSpan == null && candidateSpan == null) return false
        return findings.any { finding ->
            finding.code in DiagnosticCode.REGION_CODES &&
                matches(finding.baselineSpan, baselineSpan) &&
                matches(finding.candidateSpan, candidateSpan)
        }
    }

    companion object {

        /** Runs every check over the two readings. A null baseline is a page with nothing published. */
        fun of(baseline: String?, candidate: String): PageDiagnostics {
            val findings = mutableListOf<DiagnosticFinding>()
            val baselineLegible = baseline != null && baseline.isNotBlank()
            if (baseline != null && baseline.isBlank()) {
                findings += DiagnosticFinding(DiagnosticCode.EMPTY_OUTPUT, baselineSpan = wholeOf(baseline))
            }
            if (candidate.isBlank()) {
                findings += DiagnosticFinding(DiagnosticCode.EMPTY_OUTPUT, candidateSpan = wholeOf(candidate))
            }
            if (baseline == null || baseline.isBlank()) {
                // A page with nothing published is a page nobody can call an improvement on: there is no
                // reading to measure the candidate against, so this is a difference a person has to decide and
                // never something a measured policy may replace on its own.
                findings += DiagnosticFinding(DiagnosticCode.NO_BASELINE)
            }
            if ((baseline == null || baseline.isBlank()) && candidate.isBlank()) {
                findings += DiagnosticFinding(DiagnosticCode.EMPTY_PAIR)
            }
            baseline?.let { text -> findings += qualityFindings(text, onBaseline = true) }
            findings += qualityFindings(candidate, onBaseline = false)
            if (baselineLegible && candidate.isNotBlank()) {
                lengthChange(baseline, candidate)?.let(findings::add)
                findings += alignedDifferences(baseline, candidate)
            }
            return PageDiagnostics(findings)
        }

        /**
         * Whether one side of a claimed span is the side of the finding it is checked against.
         *
         * The side is claimed exactly when the finding has a place on it: a finding about one side alone — a
         * region only the baseline or only the candidate holds — is a reason about that side alone, so a
         * claim that also names a stretch on the other side is about text the checks established nothing
         * about and is dropped rather than stored.
         */
        private fun matches(findingSpan: TextSpan?, claimed: TextSpan?): Boolean = when {
            findingSpan == null -> claimed == null
            else -> claimed != null && findingSpan.overlaps(claimed)
        }

        /** A whole reading as one span, or no span at all when there is no text to point into. */
        private fun wholeOf(text: String): TextSpan? =
            if (text.isEmpty()) null else TextSpan(0, text.length)

        private fun wholeSpan(text: String): TextSpan? = wholeOf(text)

        private fun qualityFindings(text: String, onBaseline: Boolean): List<DiagnosticFinding> {
            val findings = mutableListOf<DiagnosticFinding>()
            truncatedAt(text)?.let { start ->
                val end = text.trimEnd().length
                if (end > start) findings += finding(DiagnosticCode.TRUNCATED_OUTPUT, onBaseline, TextSpan(start, end))
            }
            repeatedLineSpans(text).forEach { span ->
                findings += finding(DiagnosticCode.REPEATED_TEXT, onBaseline, span)
            }
            suspiciousSpan(text)?.let { span ->
                findings += finding(DiagnosticCode.SUSPICIOUS_SEQUENCE, onBaseline, span)
            }
            return findings
        }

        private fun finding(code: DiagnosticCode, onBaseline: Boolean, span: TextSpan): DiagnosticFinding =
            if (onBaseline) {
                DiagnosticFinding(code, baselineSpan = span)
            } else {
                DiagnosticFinding(code, candidateSpan = span)
            }

        /**
         * Where a reading stops in the middle of a token, or null when it does not.
         *
         * A page is written in whole tokens, so a text that ends on a hyphen or leaves an opening bracket,
         * brace or parenthesis unclosed is the shape a reading cut off at the output limit has. It is a
         * signal rather than proof — a page may genuinely end with a dash — which is why it is reported and
         * never acts alone.
         */
        private fun truncatedAt(text: String): Int? {
            val end = text.trimEnd()
            if (end.isEmpty()) return null
            if (end.last() in TRAILING_DASHES) return end.length - 1
            var depth = 0
            var lastOpening = -1
            end.forEachIndexed { index, character ->
                when (character) {
                    '(', '[', '{' -> {
                        depth++
                        lastOpening = index
                    }
                    ')', ']', '}' -> if (depth > 0) depth--
                }
            }
            return if (depth > 0) lastOpening else null
        }

        /** The spans of every non-blank line that says exactly what the line before it said. */
        private fun repeatedLineSpans(text: String): List<TextSpan> {
            val spans = mutableListOf<TextSpan>()
            var start = 0
            var previous: String? = null
            for (index in 0..text.length) {
                if (index == text.length || text[index] == '\n') {
                    val line = text.substring(start, index)
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        if (trimmed == previous && index > start) spans += TextSpan(start, index)
                        previous = trimmed
                    }
                    start = index + 1
                }
            }
            return spans
        }

        /**
         * The first character sequence no page has: a control character, a replacement mark, or a run of the
         * same punctuation mark.
         *
         * Each of these is what a damaged raster or a decoding failure looks like in committed text, and each
         * is reported rather than cleaned: a comparison may not edit the page's text, and a person looking at
         * the image is what decides what the mark was.
         */
        private fun suspiciousSpan(text: String): TextSpan? {
            var index = 0
            while (index < text.length) {
                val character = text[index]
                if (character.isUnreadableMark()) return TextSpan(index, index + 1)
                if (character.isRepeatedMark()) {
                    var end = index + 1
                    while (end < text.length && text[end] == character) end++
                    if (end - index >= SUSPICIOUS_MARK_RUN) return TextSpan(index, end)
                    index = end
                    continue
                }
                index++
            }
            return null
        }

        private fun Char.isUnreadableMark(): Boolean =
            (isISOControl() && this != '\n' && this != '\r' && this != '\t') || this == REPLACEMENT_CHARACTER

        private fun Char.isRepeatedMark(): Boolean = !isLetterOrDigit() && !isWhitespace() && !isISOControl()

        /** The two readings' lengths, when they differ by more than the ratio the checks report at. */
        private fun lengthChange(baseline: String, candidate: String): DiagnosticFinding? {
            val difference = kotlin.math.abs(candidate.length - baseline.length).toDouble() / baseline.length
            return if (difference > DiagnosticCode.LENGTH_CHANGE_RATIO) {
                DiagnosticFinding(DiagnosticCode.LENGTH_CHANGE)
            } else {
                null
            }
        }

        /**
         * The aligned differences between two readings, region by region.
         *
         * The readings are lined up line by line on the lines they have in common, and what is left over is
         * reported as a changed region, a region only the baseline holds, or a region only the candidate
         * holds. A changed pair of lines is then narrowed to the characters that actually differ, and looked
         * through for the changes a fluent reading hides: names, numbers, dates and negations. Aligning on
         * lines rather than on characters is deliberate — a page's line is the unit a person compares by eye
         * — and narrowing inside a changed line is what lets a reason be checked against the place the
         * readings differ in rather than against the whole line they happen to share.
         */
        private fun alignedDifferences(baseline: String, candidate: String): List<DiagnosticFinding> {
            val baselineLines = linesOf(baseline)
            val candidateLines = linesOf(candidate)
            val hunks = alignedHunks(baselineLines.map { line -> line.text }, candidateLines.map { line -> line.text })
            val findings = mutableListOf<DiagnosticFinding>()
            hunks.forEach { hunk ->
                val baselineRange = hunk.baseline
                val candidateRange = hunk.candidate
                when {
                    baselineRange == null -> candidateRange?.let { range ->
                        spanOver(candidateLines, range)?.let { span ->
                            findings += DiagnosticFinding(DiagnosticCode.ADDED_REGION, candidateSpan = span)
                        }
                    }
                    candidateRange == null -> spanOver(baselineLines, baselineRange)?.let { span ->
                        findings += DiagnosticFinding(DiagnosticCode.MISSING_REGION, baselineSpan = span)
                    }
                    else -> findings += replacedFindings(
                        baseline = baseline,
                        candidate = candidate,
                        baselineLines = baselineLines,
                        candidateLines = candidateLines,
                        baselineRange = baselineRange,
                        candidateRange = candidateRange,
                    )
                }
            }
            return findings
        }

        /**
         * What one changed block of lines is: one finding per changed line pair, or one for the block.
         *
         * Lines are paired one to one while the two blocks hold the same number of lines, because an OCR
         * reading that changed a word on ten lines changed those ten lines rather than replacing the block.
         * A block of another shape is reported whole: which line of a longer block replaced which is a
         * question the alignment cannot answer, and answering it wrongly would point a reason at the wrong
         * text.
         */
        private fun replacedFindings(
            baseline: String,
            candidate: String,
            baselineLines: List<Line>,
            candidateLines: List<Line>,
            baselineRange: IntRange,
            candidateRange: IntRange,
        ): List<DiagnosticFinding> {
            val findings = mutableListOf<DiagnosticFinding>()
            if (baselineRange.count() != candidateRange.count()) {
                val baselineSpan = spanOver(baselineLines, baselineRange)
                val candidateSpan = spanOver(candidateLines, candidateRange)
                if (baselineSpan != null && candidateSpan != null) {
                    findings += DiagnosticFinding(DiagnosticCode.ALIGNED_DIFFERENCE, baselineSpan, candidateSpan)
                    findings += sensitiveChanges(
                        baseline = baseline.substring(baselineSpan.startOffset, baselineSpan.endOffset),
                        candidate = candidate.substring(candidateSpan.startOffset, candidateSpan.endOffset),
                        baselineSpan = baselineSpan,
                        candidateSpan = candidateSpan,
                    )
                }
                return findings
            }
            baselineRange.forEachIndexed { offset, baselineIndex ->
                val spans = changedSpansOf(baselineLines[baselineIndex], candidateLines[candidateRange.first + offset])
                    ?: return@forEachIndexed
                findings += DiagnosticFinding(DiagnosticCode.ALIGNED_DIFFERENCE, spans.first, spans.second)
                findings += sensitiveChanges(
                    baseline = baseline.substring(spans.first.startOffset, spans.first.endOffset),
                    candidate = candidate.substring(spans.second.startOffset, spans.second.endOffset),
                    baselineSpan = spans.first,
                    candidateSpan = spans.second,
                )
            }
            return findings
        }

        /**
         * The two stretches that differ between one pair of lines, or null when the lines say the same.
         *
         * The characters the two lines share at the front and at the back are trimmed away, so the spans name
         * what actually changed rather than the line it is on, and each stretched end is widened to the next
         * whitespace so a name, number, date or negation is reported as the whole token a reader would
         * recognise instead of the half of it the trimming happened to leave. A pair that differs only by one
         * line being a prefix of the other leaves an empty stretch on one side, and the whole line is reported
         * in that case: a span is a stretch of text, so an empty one is not a span.
         */
        private fun changedSpansOf(baseline: Line, candidate: Line): Pair<TextSpan, TextSpan>? {
            if (baseline.text == candidate.text) return null
            var prefix = 0
            while (prefix < baseline.raw.length && prefix < candidate.raw.length &&
                baseline.raw[prefix] == candidate.raw[prefix]
            ) {
                prefix++
            }
            var suffix = 0
            val limit = minOf(baseline.raw.length, candidate.raw.length) - prefix
            while (suffix < limit &&
                baseline.raw[baseline.raw.length - 1 - suffix] == candidate.raw[candidate.raw.length - 1 - suffix]
            ) {
                suffix++
            }
            val baselineSpan = stretchOf(baseline, prefix, suffix)
            val candidateSpan = stretchOf(candidate, prefix, suffix)
            if (baselineSpan != null && candidateSpan != null) return baselineSpan to candidateSpan
            val wholeBaseline = wholeLineOf(baseline)
            val wholeCandidate = wholeLineOf(candidate)
            return if (wholeBaseline != null && wholeCandidate != null) wholeBaseline to wholeCandidate else null
        }

        /**
         * The stretch a line's changed characters fall in, widened to the tokens whose ends it lands inside.
         *
         * Widening to a token boundary is what makes a finding carry a name, a number or a date rather than a
         * fragment of one: `4711` and `4712` are compared as themselves, and a date whose last digit changed is
         * still recognised as a date.
         */
        private fun stretchOf(line: Line, prefix: Int, suffix: Int): TextSpan? {
            var start = prefix
            var end = line.raw.length - suffix
            if (end <= start) return null
            while (start > 0 && !line.raw[start - 1].isWhitespace()) start--
            while (end < line.raw.length && !line.raw[end].isWhitespace()) end++
            return TextSpan(line.start + start, line.start + end)
        }

        private fun wholeLineOf(line: Line): TextSpan? =
            if (line.end > line.start) TextSpan(line.start, line.end) else null

        /** One line of a reading: its text as written, and the stretch of the reading it covers. */
        private data class Line(val raw: String, val start: Int, val end: Int) {
            /** The line as it is compared and reported: trailing whitespace is not a difference by itself. */
            val text: String get() = raw.trimEnd()
        }

        private fun linesOf(text: String): List<Line> {
            val lines = mutableListOf<Line>()
            var start = 0
            for (index in text.indices) {
                if (text[index] == '\n') {
                    lines += Line(text.substring(start, index), start, index)
                    start = index + 1
                }
            }
            if (start < text.length) lines += Line(text.substring(start), start, text.length)
            return lines
        }

        /** The stretch of a reading [lines] covers, or null when they cover no character at all. */
        private fun spanOver(lines: List<Line>, range: IntRange): TextSpan? {
            val start = lines[range.first].start
            val end = lines[range.last].end
            return if (end > start) TextSpan(start, end) else null
        }

        /** A block of lines no alignment matched: the baseline it covers and the candidate it covers. */
        private data class Hunk(val baseline: IntRange?, val candidate: IntRange?)

        /**
         * The blocks of lines the two readings do not agree on, in order.
         *
         * The alignment is the longest common subsequence of the two line lists, so a changed line becomes one
         * region instead of a deletion followed by an insertion. A page whose lines are beyond
         * [MAX_ALIGNMENT_CELLS] is reported as one region covering it: the comparison stays honest about a
         * page too large to align rather than spending unbounded time on one.
         */
        private fun alignedHunks(baseline: List<String>, candidate: List<String>): List<Hunk> {
            val rows = baseline.size
            val columns = candidate.size
            if (rows.toLong() * columns.toLong() > MAX_ALIGNMENT_CELLS) {
                return listOf(Hunk(baseline.indices.orNull(), candidate.indices.orNull()))
            }
            val common = Array(rows + 1) { IntArray(columns + 1) }
            for (row in rows - 1 downTo 0) {
                for (column in columns - 1 downTo 0) {
                    common[row][column] = if (baseline[row] == candidate[column]) {
                        common[row + 1][column + 1] + 1
                    } else {
                        maxOf(common[row + 1][column], common[row][column + 1])
                    }
                }
            }
            val hunks = mutableListOf<Hunk>()
            val block = mutableListOf<Pair<Boolean, Int>>()
            fun flush() {
                if (block.isEmpty()) return
                val baselineIndices = block.filter { event -> event.first }.map { event -> event.second }
                val candidateIndices = block.filterNot { event -> event.first }.map { event -> event.second }
                hunks += Hunk(baselineIndices.rangeOrNull(), candidateIndices.rangeOrNull())
                block.clear()
            }
            var row = 0
            var column = 0
            while (row < rows && column < columns) {
                when {
                    baseline[row] == candidate[column] -> {
                        flush()
                        row++
                        column++
                    }
                    common[row + 1][column] >= common[row][column + 1] -> {
                        block += true to row
                        row++
                    }
                    else -> {
                        block += false to column
                        column++
                    }
                }
            }
            while (row < rows) {
                block += true to row
                row++
            }
            while (column < columns) {
                block += false to column
                column++
            }
            flush()
            return hunks
        }

        private fun List<Int>.rangeOrNull(): IntRange? = if (isEmpty()) null else first()..last()

        private fun IntRange.orNull(): IntRange? = if (isEmpty()) null else this

        /**
         * The changes a fluent reading hides, inside one changed region.
         *
         * Each comparison is a multiset comparison of the tokens of that kind, so a word that moved elsewhere
         * on the line is not reported as a change and a word that changed *is*. A token is a run of text
         * between whitespace with the punctuation around it trimmed, which is what a name, a number or a
         * negation is on a page.
         */
        private fun sensitiveChanges(
            baseline: String,
            candidate: String,
            baselineSpan: TextSpan,
            candidateSpan: TextSpan,
        ): List<DiagnosticFinding> {
            val before = tokensOf(baseline)
            val after = tokensOf(candidate)
            val findings = mutableListOf<DiagnosticFinding>()
            if (before.filter(::isName).sorted() != after.filter(::isName).sorted()) {
                findings += DiagnosticFinding(DiagnosticCode.NAME_CHANGED, baselineSpan, candidateSpan)
            }
            if (before.filter(::isNumber).sorted() != after.filter(::isNumber).sorted()) {
                findings += DiagnosticFinding(DiagnosticCode.NUMBER_CHANGED, baselineSpan, candidateSpan)
            }
            if (before.filter(::isDate).sorted() != after.filter(::isDate).sorted()) {
                findings += DiagnosticFinding(DiagnosticCode.DATE_CHANGED, baselineSpan, candidateSpan)
            }
            if (before.filter(::isNegation).sorted() != after.filter(::isNegation).sorted()) {
                findings += DiagnosticFinding(DiagnosticCode.NEGATION_CHANGED, baselineSpan, candidateSpan)
            }
            return findings
        }

        private fun tokensOf(text: String): List<String> = text.split(TOKEN_BOUNDARY)
            .map { token -> token.trim(*TOKEN_TRIM) }
            .filter { token -> token.isNotEmpty() }

        private fun isName(token: String): Boolean =
            token.length > 1 && token.first().isUpperCase() && token.any { character -> character.isLetter() }

        private fun isNumber(token: String): Boolean = token.any { character -> character.isDigit() } && !isDate(token)

        private fun isDate(token: String): Boolean =
            SEPARATED_DATE.matches(token) ||
                (token.length == YEAR_DIGITS && token.all { character -> character.isDigit() } &&
                    token.toInt() in YEARS)

        private fun isNegation(token: String): Boolean = token.lowercase() in NEGATIONS

        private val TRAILING_DASHES = setOf('-', '\u2013', '\u2014')
        private const val REPLACEMENT_CHARACTER = '\uFFFD'
        private const val SUSPICIOUS_MARK_RUN = 4

        /** Beyond this many line pairs the page is reported as one region rather than aligned. */
        private const val MAX_ALIGNMENT_CELLS = 1_000_000L

        private val TOKEN_BOUNDARY = Regex("\\s+")
        private val TOKEN_TRIM =
            charArrayOf('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']', '{', '}', '<', '>', '*')

        /** A date as documents write it: two separators, or a four-digit year on its own. */
        private val SEPARATED_DATE = Regex("\\d{1,4}[./-]\\d{1,4}[./-]\\d{1,4}")

        /** The four-digit years a document's dates are in, so a bare invoice number is not read as a year. */
        private val YEARS = 1500..2100
        private const val YEAR_DIGITS = 4

        private val NEGATIONS = setOf(
            "not", "no", "never", "neither", "nor", "without",
            "inte", "ingen", "inget", "aldrig", "utan", "ej",
        )
    }
}

/**
 * The disposition policy: what may happen to a difference the checks and the reviewer have established.
 *
 * Four things this deliberately is not:
 *
 * - **It is not the reviewer.** [ReviewerRecommendation] is an input it reads, and in pilot mode
 *   ([OCR_POLICY_VERSION]) it is read and then not acted on: every differing candidate is a pending proposal.
 *   A recommendation with no validated reason behind it has already been turned into `UNCERTAIN` by the
 *   comparison, so there is nothing here to be talked into either.
 * - **It is not the reviewer's confidence.** That number is recorded for the person reading a review and is
 *   not an argument here at all: a model that says it is certain about an invented name is exactly the case
 *   a threshold must not decide.
 * - **It cannot enable itself.** [PublicationDisposition.APPROVE] needs a policy version past the pilot's
 *   *and* an [OcrValidationRecord] covering this comparison, and this build's wiring resolves no record:
 *   a record exists only where the store read one back, and nothing in this build writes one. Nor does an
 *   approval outlive the row it was decided under: the policy holds the review store and resolves the record
 *   from it on every decision rather than holding one, so a policy that was built while an acceptance existed
 *   stops approving as soon as the row is gone.
 * - **It counts nothing.** How many words the two readings share is deliberately not an input: a reading that
 *   agrees with another word for word is not thereby right, and the checks' token comparisons exist to *flag*
 *   a changed name, number, date or negation rather than to score agreement.
 */
class OcrDecisionPolicy(
    val policyVersion: Int = OCR_POLICY_VERSION,
    /**
     * The archive an acceptance is resolved from, at every decision; null resolves none.
     *
     * A policy holds the store rather than a record, or a way of producing one, because a record read at
     * construction time can be a record whose row is gone: the store is asked again on every decision, so a
     * combination whose acceptance was deleted — or was never accepted — approves nothing, however long the
     * policy has been alive. There is no acceptance to state here, only one to resolve, and resolving it *is*
     * the archive read: [OcrValidationRecord]'s constructor is private to the store and [OcrReviewStore] is a
     * final class nothing can substitute for, so the store's own [OcrReviewStore.acceptedValidation] is the
     * only implementation of this seam and the most it can return is a row that actually exists.
     *
     * Production wiring is `OcrDecisionPolicy(policyVersion, reviews)`. The default resolves nothing, which is
     * what the pilot constant rests on.
     */
    private val reviews: OcrReviewStore? = null,
) {

    init {
        require(policyVersion > 0) { "a policy names a positive version, was $policyVersion" }
    }

    /**
     * Whether this policy could ever replace text without a person.
     *
     * False for the pilot, whose version is the one that requires manual approval, and false for a policy no
     * archive stands behind: the version is a number, and being wired to an archive is the capability. This is
     * a statement about the capability and never an approval: it says only that a store is wired and the
     * version gate is open, and whether one comparison is approved is [disposition]'s question, which asks the
     * archive again every time. Ticket 11 is where the version and the acceptance change together, on measured
     * and accepted evidence.
     */
    val automaticReplacementEnabled: Boolean
        get() = reviews != null && policyVersion > OCR_POLICY_VERSION

    /**
     * What may happen to one comparison.
     *
     * [scope] says which reviewer judged it, which is all that is asked about the comparison here: whether
     * the record this policy resolves for that reviewer and prompt was accepted is decided below, and the
     * page, the readings and the candidate hash travel in the review rather than into a decision.
     */
    fun disposition(
        diagnostics: PageDiagnostics,
        recommendation: ReviewerRecommendation,
        scope: ReviewerScope,
    ): PublicationDisposition {
        if (recommendation == ReviewerRecommendation.NEW_BETTER &&
            diagnostics.allowsAutomaticReplacement &&
            acceptanceCovers(scope)
        ) {
            return PublicationDisposition.APPROVE
        }
        return if (diagnostics.needsDecision) {
            PublicationDisposition.PROPOSE
        } else {
            PublicationDisposition.KEEP
        }
    }

    /**
     * Whether an accepted record resolved *now* is an acceptance of exactly this comparison.
     *
     * The resolution happens here, at the moment of the decision, rather than in the wiring: a record holds a
     * claim only while its row does, so what the archive answers now is what decides now. The pilot is
     * authoritative on top of it: with nothing resolved, under a policy newer than the one the record was
     * accepted for, or with a reviewer or prompt the record was not measured for, nothing is approved and
     * every difference stays a proposal a person decides.
     */
    private fun acceptanceCovers(scope: ReviewerScope): Boolean {
        // Defence in depth: the store's read is already keyed by this scope and this version, so a record for
        // another reviewer, prompt or policy cannot come back from it. The record's own fields are still
        // checked here, because the guarantee is the policy's rather than the query's.
        val accepted = reviews?.acceptedValidation(scope, policyVersion) ?: return false
        return accepted.reviewProfileRevisionId == scope.reviewerRevisionId &&
            accepted.reviewPromptVersion == scope.reviewPromptVersion &&
            acceptanceCoversPolicyVersion(accepted)
    }

    /**
     * Whether the resolved record stands behind this policy's own version — never the pilot's.
     *
     * The record's version has to be this policy's version: a record accepted for another policy is not
     * evidence about what this one may do, and the pilot version replaces nothing with a person whatever
     * record is resolved.
     */
    private fun acceptanceCoversPolicyVersion(accepted: OcrValidationRecord): Boolean =
        policyVersion > OCR_POLICY_VERSION && accepted.policyVersion == policyVersion
}
