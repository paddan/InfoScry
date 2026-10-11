package infoscry.pdf

import java.text.Normalizer

/**
 * Local, bounded placement of one page's canonical reading onto spatial word boxes from a local positioning
 * pass (ticket 03). It maps canonical tokens to anchor rectangles; it never replaces canonical text, never calls
 * a provider, never reads or writes a file, and never uses an anchor's own text as the reading.
 *
 * Acceptance is structural, not a score:
 * - Islands: a canonical token whose match key occurs once in the reading and once among the anchors is placed
 *   on that anchor. Islands must appear in the same order in both sequences, or the page is ambiguous.
 * - Between two islands, a segment with equal counts is placed pair by pair only when each pair is an exact key
 *   match, or a one-edit match that is the sole candidate on both sides.
 * - Otherwise a token is placed only when it has one candidate in the segment and that anchor has one candidate
 *   token, and the accepted pairs keep their order.
 * - A canonical word is one anchor split by character proportion, or two adjacent same-line anchors merged into
 *   one union. Two adjacent anchors on different lines that together spell one canonical word (a line-end hyphen
 *   join) are never unioned: the word becomes two WORD spans split at the boundary of the two anchors' text, each
 *   keeping its own anchor's rectangle.
 *
 * Anything unresolved makes the layout incomplete with a safe reason and no spans. [align] returns only a layout
 * that has passed [PageTextLayout.verifiedAgainst].
 */
object OcrGeometryAligner {
    const val LAYOUT_VERSION: Int = 1
    const val REASON_AMBIGUOUS: String = "PDF_TEXT_ALIGNMENT_AMBIGUOUS"
    const val REASON_LIMIT_EXCEEDED: String = "PDF_TEXT_ALIGNMENT_LIMIT_EXCEEDED"

    /** Canonical UTF-16 units accepted for one page. Beyond this the page is refused, never truncated. */
    const val MAX_CANONICAL_UTF16: Int = 20_000

    /** Word boxes accepted from one page's positioning pass. */
    const val MAX_ANCHORS: Int = 4_000

    /** Pair comparisons spent on matching, summed over the page. Each comparison is O(key length). */
    const val MAX_COMPARISONS: Long = 2_000_000L

    private const val MIN_FUZZY_KEY_LENGTH: Int = 3
    private const val MERGE_MIN_GAP_FACTOR: Double = -0.25
    private const val MERGE_MAX_GAP_FACTOR: Double = 0.5
    private const val LINE_OVERLAP_FACTOR: Double = 0.5
    private const val NARROW_CHARACTERS: String = "ijlftrI.,:;'!|"
    private const val WIDE_CHARACTERS: String = "mwMW@%"

    fun align(input: GeometryAlignmentInput): PageTextLayout {
        val text = input.canonicalText
        val resolution = resolve(input)
        val complete = resolution.reasonCode == null
        val spans = if (complete) spansOf(resolution.tokens) else emptyList()
        return PageTextLayout(
            version = LAYOUT_VERSION,
            textSha256 = PdfModelRules.sha256Of(text),
            imageSha256 = input.image.sha256.lowercase(),
            width = checkNotNull(input.image.width),
            height = checkNotNull(input.image.height),
            geometry = input.geometry,
            transform = input.transform,
            spans = spans,
            complete = complete,
            reasonCode = resolution.reasonCode,
            positioningVersion = input.positioningVersion,
        ).verifiedAgainst(text)
    }

    internal fun resolve(input: GeometryAlignmentInput): AlignmentResolution {
        if (input.canonicalText.length > MAX_CANONICAL_UTF16 || input.anchors.size > MAX_ANCHORS) {
            return AlignmentResolution(emptyList(), REASON_LIMIT_EXCEEDED, comparisons = 0L)
        }
        val tokens = tokenize(input.canonicalText)
        val anchors = usableAnchors(input)
        val aligner = SegmentAligner(tokens, anchors)
        return try {
            aligner.run()
            val placements = aligner.placements()
            val reason = if (placements.all { it.kind != PlacementKind.UNPLACED }) null else REASON_AMBIGUOUS
            AlignmentResolution(placements, reason, aligner.comparisonsSpent())
        } catch (exhausted: BudgetExhausted) {
            AlignmentResolution(emptyList(), REASON_LIMIT_EXCEEDED, MAX_COMPARISONS)
        }
    }

    private fun spansOf(tokens: List<TokenPlacement>): List<PositionedTextSpan> {
        val spans = ArrayList<PositionedTextSpan>(tokens.size)
        for (token in tokens) {
            if (token.pieces.isNotEmpty()) {
                for (piece in token.pieces) {
                    spans += PositionedTextSpan(piece.start, piece.endExclusive, piece.rectangle, SpanGranularity.WORD, spans.size)
                }
                continue
            }
            spans += PositionedTextSpan(
                start = token.start,
                endExclusive = token.endExclusive,
                rectangle = checkNotNull(token.rectangle),
                granularity = SpanGranularity.WORD,
                order = spans.size,
            )
        }
        return spans
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var index = 0
        var start = -1
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (PdfModelRules.isBlankCodePoint(codePoint)) {
                if (start >= 0) tokens += makeToken(text, start, index)
                start = -1
            } else if (start < 0) {
                start = index
            }
            index += Character.charCount(codePoint)
        }
        if (start >= 0) tokens += makeToken(text, start, text.length)
        return tokens
    }

    private fun makeToken(text: String, start: Int, endExclusive: Int): Token {
        val source = text.substring(start, endExclusive)
        return Token(
            text = source,
            start = start,
            endExclusive = endExclusive,
            key = MatchKey.of(source).text,
            weight = widthEstimate(source),
        )
    }

    /** Anchors that can carry a single canonical word: positive size, inside the image, and one token of text. */
    private fun usableAnchors(input: GeometryAlignmentInput): List<UsableAnchor> {
        val imageWidth = checkNotNull(input.image.width)
        val imageHeight = checkNotNull(input.image.height)
        val usable = ArrayList<UsableAnchor>(input.anchors.size)
        input.anchors.forEachIndexed { index, box ->
            val hasBlank = box.text.codePoints().anyMatch { PdfModelRules.isBlankCodePoint(it) }
            val inside = box.left >= 0 && box.top >= 0 &&
                box.left + box.width <= imageWidth && box.top + box.height <= imageHeight
            if (box.width > 0 && box.height > 0 && inside && !hasBlank) {
                val key = MatchKey.of(box.text).text
                if (key.isNotEmpty()) {
                    val rectangle = TextRectangle(box.left.toDouble(), box.top.toDouble(), box.width.toDouble(), box.height.toDouble())
                    usable += UsableAnchor(index, rectangle, key)
                }
            }
        }
        return usable
    }

    /** A canonical whitespace-delimited token, with the match key that compares it to a drawn word. */
    private class Token(val text: String, val start: Int, val endExclusive: Int, val key: String, val weight: Double)

    private class UsableAnchor(val originalIndex: Int, val rectangle: TextRectangle, val key: String)

    private fun widthEstimate(text: String): Double {
        var total = 0.0
        text.codePoints().forEach { codePoint ->
            if (!PdfModelRules.isBlankCodePoint(codePoint)) total += characterWeight(codePoint)
        }
        return total
    }

    private fun characterWeight(codePoint: Int): Double = when {
        codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT && NARROW_CHARACTERS.indexOf(codePoint.toChar()) >= 0 -> 0.5
        codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT && WIDE_CHARACTERS.indexOf(codePoint.toChar()) >= 0 -> 1.5
        else -> 1.0
    }

    /** Thrown inside [SegmentAligner] when the comparison budget runs out. Caught in [resolve] only. */
    private class BudgetExhausted : RuntimeException("alignment budget exhausted", null, false, false)

    private fun withinOneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    a.length == b.length -> { i++; j++ }
                    a.length > b.length -> i++
                    else -> j++
                }
            }
        }
        edits += (a.length - i) + (b.length - j)
        return edits <= 1
    }

    /** One-edit fuzzy match, only for keys long enough that one edit is meaningful. Never a ranking. */
    private fun isOneEditMatch(a: String, b: String): Boolean =
        a != b && minOf(a.length, b.length) >= MIN_FUZZY_KEY_LENGTH && withinOneEdit(a, b)

    /**
     * The per-segment candidate tables. A token's candidates are its exact matches in the segment, or, if it has
     * none, its one-edit matches. Positions are offsets into the token range and the anchor pool.
     */
    private class SegmentTable(
        val tokenStart: Int,
        val tokenCount: Int,
        val poolStart: Int,
        val poolCount: Int,
        val candidates: Array<IntArray>,
        val candidateIsExact: BooleanArray,
        val anchorTokenCount: IntArray,
        val anchorSoleToken: IntArray,
    )

    private class SegmentAligner(private val tokens: List<Token>, private val anchors: List<UsableAnchor>) {
        private val kinds = Array(tokens.size) { PlacementKind.UNPLACED }
        private val tokenAnchors: Array<List<Int>> = Array(tokens.size) { emptyList() }
        private val rectangles = arrayOfNulls<TextRectangle>(tokens.size)
        private val tokenPieces: Array<List<TokenPiece>> = Array(tokens.size) { emptyList() }
        private val anchorUsed = BooleanArray(anchors.size)
        private var spent = 0L

        fun run() {
            val islands = islands() ?: return
            var previousToken = -1
            var previousAnchor = -1
            for ((token, anchor) in islands) {
                resolveSegment(previousToken + 1 until token, previousAnchor + 1 until anchor)
                place(token, PlacementKind.EXACT, anchor)
                previousToken = token
                previousAnchor = anchor
            }
            resolveSegment(previousToken + 1 until tokens.size, previousAnchor + 1 until anchors.size)
        }

        fun comparisonsSpent(): Long = spent

        fun placements(): List<TokenPlacement> = tokens.indices.map { index ->
            val token = tokens[index]
            TokenPlacement(
                text = token.text,
                start = token.start,
                endExclusive = token.endExclusive,
                kind = kinds[index],
                anchors = tokenAnchors[index],
                rectangle = rectangles[index],
                pieces = tokenPieces[index],
            )
        }

        private fun charge(units: Long) {
            spent += units
            if (spent > MAX_COMPARISONS) throw BudgetExhausted()
        }

        /** Globally unique exact keys on both sides; empty when they conflict with the engine's order. */
        private fun islands(): List<Pair<Int, Int>>? {
            charge((tokens.size + anchors.size).toLong())
            val tokenCounts = HashMap<String, Int>()
            for (token in tokens) tokenCounts.merge(token.key, 1, Int::plus)
            val anchorCounts = HashMap<String, Int>()
            val anchorPosition = HashMap<String, Int>()
            anchors.forEachIndexed { position, anchor ->
                anchorCounts.merge(anchor.key, 1, Int::plus)
                anchorPosition[anchor.key] = position
            }
            val islands = ArrayList<Pair<Int, Int>>()
            tokens.forEachIndexed { index, token ->
                if (tokenCounts[token.key] == 1 && anchorCounts[token.key] == 1) {
                    islands += index to checkNotNull(anchorPosition[token.key])
                }
            }
            for (k in 1 until islands.size) {
                if (islands[k].second <= islands[k - 1].second) return null
            }
            return islands
        }

        private fun resolveSegment(tokenRange: IntRange, poolRange: IntRange) {
            if (tokenRange.isEmpty() || poolRange.isEmpty()) return
            charge(tokenRange.count().toLong() * poolRange.count().toLong())
            val table = buildTable(tokenRange, poolRange)
            if (tokenRange.count() == poolRange.count() && positionalMatch(table)) {
                for (offset in 0 until table.tokenCount) {
                    place(table.tokenStart + offset, kindOf(table.tokenStart + offset, table.poolStart + offset), table.poolStart + offset)
                }
                return
            }
            mutualUnique(table)
            splitWords(tokenRange, poolRange)
            mergeWords(tokenRange, poolRange)
        }

        private fun buildTable(tokenRange: IntRange, poolRange: IntRange): SegmentTable {
            val tokenCount = tokenRange.count()
            val poolCount = poolRange.count()
            val candidates = Array(tokenCount) { IntArray(0) }
            val candidateIsExact = BooleanArray(tokenCount)
            val anchorTokenCount = IntArray(poolCount)
            val anchorSoleToken = IntArray(poolCount) { -1 }
            for (offset in 0 until tokenCount) {
                val token = tokens[tokenRange.first + offset]
                val exact = ArrayList<Int>()
                val fuzzy = ArrayList<Int>()
                for (poolOffset in 0 until poolCount) {
                    val anchor = anchors[poolRange.first + poolOffset]
                    if (anchor.key == token.key) exact += poolRange.first + poolOffset
                    else if (isOneEditMatch(token.key, anchor.key)) fuzzy += poolRange.first + poolOffset
                }
                val chosen = if (exact.isNotEmpty()) exact else fuzzy
                candidates[offset] = chosen.toIntArray()
                candidateIsExact[offset] = exact.isNotEmpty()
                for (anchorAbsolute in chosen) {
                    val poolOffset = anchorAbsolute - poolRange.first
                    anchorTokenCount[poolOffset]++
                    anchorSoleToken[poolOffset] = tokenRange.first + offset
                }
            }
            return SegmentTable(tokenRange.first, tokenCount, poolRange.first, poolCount, candidates, candidateIsExact, anchorTokenCount, anchorSoleToken)
        }

        /** Pairwise in order: exact pairs, or one-edit pairs that are the only candidates on both sides. */
        private fun positionalMatch(table: SegmentTable): Boolean {
            for (offset in 0 until table.tokenCount) {
                val token = table.tokenStart + offset
                val anchorAbsolute = table.poolStart + offset
                val exactPair = tokens[token].key == anchors[anchorAbsolute].key
                if (exactPair) continue
                val soleCandidate = table.candidates[offset].size == 1 && table.candidates[offset][0] == anchorAbsolute
                val soleToken = table.anchorTokenCount[offset] == 1 && table.anchorSoleToken[offset] == token
                if (!(soleCandidate && soleToken)) return false
            }
            return true
        }

        private fun kindOf(token: Int, anchor: Int): PlacementKind =
            if (tokens[token].key == anchors[anchor].key) PlacementKind.EXACT else PlacementKind.FUZZY

        /** Each token with one candidate whose anchor has one candidate token; accepted only if order is kept. */
        private fun mutualUnique(table: SegmentTable) {
            val accepted = ArrayList<Pair<Int, Int>>()
            for (offset in 0 until table.tokenCount) {
                val candidate = table.candidates[offset]
                if (candidate.size != 1) continue
                val anchorAbsolute = candidate[0]
                val poolOffset = anchorAbsolute - table.poolStart
                if (table.anchorTokenCount[poolOffset] == 1) accepted += (table.tokenStart + offset) to anchorAbsolute
            }
            for (k in 1 until accepted.size) {
                if (accepted[k].second <= accepted[k - 1].second) return
            }
            for ((token, anchor) in accepted) place(token, kindOf(token, anchor), anchor)
        }

        /** Two adjacent unplaced canonical words that together are exactly one drawn box, unambiguously. */
        private fun splitWords(tokenRange: IntRange, poolRange: IntRange) {
            val candidates = ArrayList<Pair<Int, Int>>()
            for (left in tokenRange.first until tokenRange.last) {
                if (kinds[left] != PlacementKind.UNPLACED || kinds[left + 1] != PlacementKind.UNPLACED) continue
                val combined = tokens[left].key + tokens[left + 1].key
                charge(poolRange.count().toLong())
                for (anchor in poolRange) {
                    if (!anchorUsed[anchor] && anchors[anchor].key == combined) candidates += left to anchor
                }
            }
            val anchorCount = HashMap<Int, Int>()
            val tokenParticipation = HashMap<Int, Int>()
            for ((left, anchor) in candidates) {
                anchorCount.merge(anchor, 1, Int::plus)
                tokenParticipation.merge(left, 1, Int::plus)
                tokenParticipation.merge(left + 1, 1, Int::plus)
            }
            for ((left, anchor) in candidates) {
                val unambiguous = anchorCount[anchor] == 1 &&
                    tokenParticipation[left] == 1 && tokenParticipation[left + 1] == 1
                if (!unambiguous) continue
                val box = anchors[anchor].rectangle
                val total = tokens[left].weight + tokens[left + 1].weight
                val leftWidth = box.width * tokens[left].weight / total
                val rightWidth = box.width * tokens[left + 1].weight / total
                val anchorOriginal = anchors[anchor].originalIndex
                kinds[left] = PlacementKind.SPLIT
                kinds[left + 1] = PlacementKind.SPLIT
                tokenAnchors[left] = listOf(anchorOriginal)
                tokenAnchors[left + 1] = listOf(anchorOriginal)
                rectangles[left] = TextRectangle(box.left, box.top, leftWidth, box.height)
                rectangles[left + 1] = TextRectangle(box.left + leftWidth, box.top, rightWidth, box.height)
                anchorUsed[anchor] = true
            }
        }

        /**
         * One unplaced canonical word that is exactly two adjacent drawn boxes, unambiguously. Same-line boxes merge
         * into one union rectangle. Boxes on different lines split the word at the boundary of their two texts, and
         * each piece keeps its own box, so no rectangle covers the gap between the lines.
         */
        private fun mergeWords(tokenRange: IntRange, poolRange: IntRange) {
            val candidates = ArrayList<Pair<Int, Int>>()
            for (first in poolRange.first until poolRange.last) {
                if (anchorUsed[first] || anchorUsed[first + 1]) continue
                val a = anchors[first]
                val b = anchors[first + 1]
                val crossLine = !onOneLine(a, b)
                if (!crossLine && !sameLine(a, b)) continue
                val combined = a.key + b.key
                charge(tokenRange.count().toLong())
                for (token in tokenRange) {
                    if (kinds[token] != PlacementKind.UNPLACED || tokens[token].key != combined) continue
                    if (crossLine && lineBreakBoundary(tokens[token], a.key.length) == null) continue
                    candidates += token to first
                }
            }
            val tokenCount = HashMap<Int, Int>()
            val anchorParticipation = HashMap<Int, Int>()
            for ((token, first) in candidates) {
                tokenCount.merge(token, 1, Int::plus)
                anchorParticipation.merge(first, 1, Int::plus)
                anchorParticipation.merge(first + 1, 1, Int::plus)
            }
            for ((token, first) in candidates) {
                val unambiguous = tokenCount[token] == 1 &&
                    anchorParticipation[first] == 1 && anchorParticipation[first + 1] == 1
                if (!unambiguous) continue
                val a = anchors[first]
                val b = anchors[first + 1]
                if (sameLine(a, b)) {
                    val left = minOf(a.rectangle.left, b.rectangle.left)
                    val top = minOf(a.rectangle.top, b.rectangle.top)
                    val right = maxOf(a.rectangle.left + a.rectangle.width, b.rectangle.left + b.rectangle.width)
                    val bottom = maxOf(a.rectangle.top + a.rectangle.height, b.rectangle.top + b.rectangle.height)
                    kinds[token] = PlacementKind.MERGED
                    tokenAnchors[token] = listOf(a.originalIndex, b.originalIndex)
                    rectangles[token] = TextRectangle(left, top, right - left, bottom - top)
                } else {
                    val boundary = checkNotNull(lineBreakBoundary(tokens[token], a.key.length))
                    val word = tokens[token]
                    kinds[token] = PlacementKind.LINE_BREAK_SPLIT
                    tokenAnchors[token] = listOf(a.originalIndex, b.originalIndex)
                    tokenPieces[token] = listOf(
                        TokenPiece(word.start, boundary, a.originalIndex, a.rectangle),
                        TokenPiece(boundary, word.endExclusive, b.originalIndex, b.rectangle),
                    )
                }
                anchorUsed[first] = true
                anchorUsed[first + 1] = true
            }
        }

        /**
         * Canonical offset where the first anchor's text ends inside [token], found through the match key's offset
         * map. Null when that boundary would leave one side empty, so the word is never split to nothing.
         */
        private fun lineBreakBoundary(token: Token, firstKeyLength: Int): Int? {
            val boundary = token.start + MatchKey.of(token.text).canonicalEndOf(firstKeyLength - 1)
            return boundary.takeIf { it > token.start && it < token.endExclusive }
        }

        /** Two drawn boxes share a line when their vertical overlap is at least half the shorter box's height. */
        private fun onOneLine(a: UsableAnchor, b: UsableAnchor): Boolean {
            val overlap = minOf(a.rectangle.top + a.rectangle.height, b.rectangle.top + b.rectangle.height) -
                maxOf(a.rectangle.top, b.rectangle.top)
            val shorter = minOf(a.rectangle.height, b.rectangle.height)
            return overlap >= LINE_OVERLAP_FACTOR * shorter
        }

        /** Two boxes on one line with a horizontal gap inside the merge range: one word, one union rectangle. */
        private fun sameLine(a: UsableAnchor, b: UsableAnchor): Boolean {
            val shorter = minOf(a.rectangle.height, b.rectangle.height)
            val taller = maxOf(a.rectangle.height, b.rectangle.height)
            val gap = b.rectangle.left - (a.rectangle.left + a.rectangle.width)
            return onOneLine(a, b) && gap >= MERGE_MIN_GAP_FACTOR * shorter && gap <= MERGE_MAX_GAP_FACTOR * taller
        }

        private fun place(token: Int, kind: PlacementKind, anchor: Int) {
            kinds[token] = kind
            tokenAnchors[token] = listOf(anchors[anchor].originalIndex)
            rectangles[token] = anchors[anchor].rectangle
            anchorUsed[anchor] = true
        }
    }
}

/**
 * The match key for one piece of text: NFKC of each base character with its combining marks (so decomposed Å
 * becomes Å and a ligature becomes its letters), keeping letters and digits. Each key character maps back to the
 * canonical UTF-16 range it came from; a key that is only punctuation keeps its punctuation.
 */
internal class MatchKey private constructor(val text: String, private val starts: IntArray, private val ends: IntArray) {
    /** Canonical UTF-16 start of key character [index]. */
    fun canonicalStartOf(index: Int): Int = starts[index]

    /** Canonical UTF-16 end (exclusive) of key character [index]. */
    fun canonicalEndOf(index: Int): Int = ends[index]

    companion object {
        fun of(source: String, base: Int = 0): MatchKey {
            val pieces = ArrayList<Piece>()
            for ((clusterStart, clusterEnd) in clusters(source)) {
                val normalized = Normalizer.normalize(source.substring(clusterStart, clusterEnd), Normalizer.Form.NFKC)
                var index = 0
                while (index < normalized.length) {
                    val codePoint = normalized.codePointAt(index)
                    pieces += Piece(codePoint, clusterStart + base, clusterEnd + base)
                    index += Character.charCount(codePoint)
                }
            }
            val letters = pieces.filter { Character.isLetterOrDigit(it.codePoint) }
            val chosen = letters.ifEmpty { pieces }
            val text = StringBuilder()
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            for (piece in chosen) {
                text.appendCodePoint(piece.codePoint)
                repeat(Character.charCount(piece.codePoint)) {
                    starts += piece.start
                    ends += piece.end
                }
            }
            return MatchKey(text.toString(), starts.toIntArray(), ends.toIntArray())
        }

        private fun clusters(source: String): List<Pair<Int, Int>> {
            val clusters = ArrayList<Pair<Int, Int>>()
            var index = 0
            while (index < source.length) {
                val start = index
                index += Character.charCount(source.codePointAt(index))
                while (index < source.length && isCombiningMark(source.codePointAt(index))) {
                    index += Character.charCount(source.codePointAt(index))
                }
                clusters += start to index
            }
            return clusters
        }

        private fun isCombiningMark(codePoint: Int): Boolean {
            val type = Character.getType(codePoint)
            return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()
        }

        private class Piece(val codePoint: Int, val start: Int, val end: Int)
    }
}

/** How one canonical token was placed. [anchors] are indices into the input's anchor list. */
internal enum class PlacementKind { EXACT, FUZZY, MERGED, SPLIT, LINE_BREAK_SPLIT, UNPLACED }

/** One WORD span inside a canonical token that is split across two drawn boxes. [anchor] is an input anchor index. */
internal data class TokenPiece(val start: Int, val endExclusive: Int, val anchor: Int, val rectangle: TextRectangle)

internal data class TokenPlacement(
    val text: String,
    val start: Int,
    val endExclusive: Int,
    val kind: PlacementKind,
    val anchors: List<Int>,
    val rectangle: TextRectangle?,
    val pieces: List<TokenPiece> = emptyList(),
)

/** [comparisons] is the work spent, in the units [OcrGeometryAligner.MAX_COMPARISONS] bounds. */
internal data class AlignmentResolution(val tokens: List<TokenPlacement>, val reasonCode: String?, val comparisons: Long)
