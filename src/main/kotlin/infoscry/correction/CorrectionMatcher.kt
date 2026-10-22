package infoscry.correction

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.lang.UScript
import com.ibm.icu.text.Normalizer2
import java.util.Locale

/**
 * One replacement in a text: the characters `[start, endExclusive)` of the input read [replacement]. The offsets
 * are UTF-16 indices into the text the matcher was given, always on code-point boundaries.
 */
data class TextEdit(val start: Int, val endExclusive: Int, val replacement: String, val ruleId: String)

/** A text with a correction list applied, and the edits that made it. */
data class CorrectionResult(val text: String, val edits: List<TextEdit>) {

    val changed: Boolean get() = edits.isNotEmpty()

    /** How many places each rule rewrote, by rule id. */
    fun matchesByRule(): Map<String, Int> = edits.groupingBy { it.ruleId }.eachCount()
}

/**
 * Applies a collection's corrections to a text in one pass.
 *
 * Matching is over the text's NFC form, so a decomposed `å` matches a rule written with a composed one, but the
 * result is built from the text as given: only the matched characters are replaced, and everything around them
 * keeps its own bytes, normalization form included.
 *
 * - A rule matches whole words: its first and last code points sit at a word boundary, a neighbour that is not a
 *   letter, combining mark or decimal digit (or the text's edge). A space in a rule matches any run of whitespace,
 *   line breaks included.
 * - A rule written only in a script that has no spaces between words (Han, Kana, Thai, Lao, Khmer, Myanmar) has
 *   no boundaries to find, so it matches anywhere.
 * - Without exact case, words compare by Unicode full case folding, never by the default locale.
 * - Where several rules match at one place the longest match wins (then the exact-case rule, then the smaller
 *   id), and replaced text is never matched again: the result does not depend on the order of the list.
 */
class CorrectionMatcher(rules: List<CorrectionRule>) {

    private val compiled = rules.map(::compile)

    fun apply(text: String): CorrectionResult {
        if (compiled.isEmpty() || text.isEmpty()) return CorrectionResult(text, emptyList())
        val view = NfcView.of(text)
        val edits = mutableListOf<TextEdit>()
        var position = 0
        val source = view.text
        while (position < source.length) {
            val match = bestMatchAt(source, position)
            val start = match?.let { view.originalOffset(position) }
            val end = match?.let { view.originalOffset(it.end) }
            if (match != null && start != null && end != null) {
                edits += TextEdit(start, end, replacementFor(match.rule, source.substring(position, match.end)), match.rule.rule.id)
                position = match.end
            } else {
                position += Character.charCount(source.codePointAt(position))
            }
        }
        if (edits.isEmpty()) return CorrectionResult(text, edits)
        val out = StringBuilder(text.length)
        var copied = 0
        edits.forEach { edit ->
            out.append(text, copied, edit.start).append(edit.replacement)
            copied = edit.endExclusive
        }
        out.append(text, copied, text.length)
        return CorrectionResult(out.toString(), edits)
    }

    // ---- matching ----

    private class Compiled(
        val rule: CorrectionRule,
        val tokens: List<String>,
        val folded: List<String>,
        val substring: Boolean,
    )

    private class Match(val rule: Compiled, val end: Int, val codePoints: Int)

    private fun compile(rule: CorrectionRule): Compiled {
        val find = NFC.normalize(rule.find)
        val tokens = find.split(' ').filter(String::isNotEmpty)
        require(tokens.isNotEmpty()) { "a correction rule finds something" }
        val substring = find.codePoints().filter { it != ' '.code }.allMatch(::isSpacelessScript)
        return Compiled(rule, tokens, tokens.map(::fold), substring)
    }

    private fun bestMatchAt(text: String, position: Int): Match? {
        var best: Match? = null
        for (rule in compiled) {
            val end = matchEnd(rule, text, position) ?: continue
            val candidate = Match(rule, end, text.codePointCount(position, end))
            if (best == null || candidate.wins(best)) best = candidate
        }
        return best
    }

    private fun Match.wins(other: Match): Boolean = when {
        codePoints != other.codePoints -> codePoints > other.codePoints
        rule.rule.exactCase != other.rule.rule.exactCase -> rule.rule.exactCase
        else -> rule.rule.id < other.rule.rule.id
    }

    /** Where [rule] ends when it matches at [position], or `null`. */
    private fun matchEnd(rule: Compiled, text: String, position: Int): Int? {
        if (!rule.substring && !boundaryBefore(text, position)) return null
        var cursor = position
        rule.tokens.forEachIndexed { index, token ->
            val last = index == rule.tokens.lastIndex
            cursor = if (rule.substring) {
                if (!text.startsWith(token, cursor)) return null
                cursor + token.length
            } else {
                val runEnd = nonWhitespaceEnd(text, cursor)
                if (last) {
                    lastTokenEnd(rule, index, text, cursor, runEnd) ?: return null
                } else {
                    if (!same(rule, index, text.substring(cursor, runEnd))) return null
                    runEnd
                }
            }
            if (!last) {
                val afterSpace = whitespaceEnd(text, cursor)
                if (afterSpace == cursor) return null
                cursor = afterSpace
            }
        }
        return cursor
    }

    /** The end of the last token inside the run `[from, runEnd)`, at a word boundary, or `null`. */
    private fun lastTokenEnd(rule: Compiled, index: Int, text: String, from: Int, runEnd: Int): Int? {
        var end = from
        while (end < runEnd) {
            end += Character.charCount(text.codePointAt(end))
            if (boundaryAfter(text, end) && same(rule, index, text.substring(from, end))) return end
        }
        return null
    }

    private fun same(rule: Compiled, index: Int, word: String): Boolean =
        if (rule.rule.exactCase) word == rule.tokens[index] else fold(word) == rule.folded[index]

    // ---- the replacement's writing ----

    private fun replacementFor(rule: Compiled, matched: String): String {
        val replace = NFC.normalize(rule.rule.replace)
        val written = if (rule.rule.exactCase) replace else casedLike(replace, matched)
        val words = written.split(' ')
        if (rule.tokens.size < 2 || words.size != rule.tokens.size) return written
        // As many words as the rule finds: each gap keeps the text's own whitespace, so a line break stays one.
        val gaps = Regex("\\s+").findAll(matched).map { it.value }.toList()
        if (gaps.size != words.size - 1) return written
        return buildString {
            words.forEachIndexed { index, word ->
                if (index > 0) append(gaps[index - 1])
                append(word)
            }
        }
    }

    private fun casedLike(replace: String, matched: String): String {
        val cased = matched.codePoints().filter { UCharacter.hasBinaryProperty(it, UProperty.CASED) }.toArray()
        val allUpper = cased.isNotEmpty() && cased.all(UCharacter::isUUppercase)
        val ownCase = replace.codePoints().anyMatch { UCharacter.isUUppercase(it) || UCharacter.isTitleCase(it) }
        if (allUpper) return replace.uppercase(Locale.ROOT)
        if (ownCase) return replace
        val titleLike = cased.isNotEmpty() && UCharacter.isUUppercase(cased.first()) &&
            cased.drop(1).none(UCharacter::isUUppercase)
        if (!titleLike || replace.isEmpty()) return replace
        val first = replace.codePointAt(0)
        return UCharacter.toTitleCase(Locale.ROOT, String(Character.toChars(first)), null) +
            replace.substring(Character.charCount(first))
    }

    // ---- characters ----

    private fun boundaryBefore(text: String, position: Int): Boolean =
        position == 0 || !isWordCodePoint(text.codePointBefore(position))

    private fun boundaryAfter(text: String, position: Int): Boolean =
        position == text.length || !isWordCodePoint(text.codePointAt(position))

    private fun nonWhitespaceEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && !UCharacter.isUWhiteSpace(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end))
        return end
    }

    private fun whitespaceEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && UCharacter.isUWhiteSpace(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end))
        return end
    }

    /**
     * The text in NFC with a map back to the given text. Only the start of a normalization segment has a
     * counterpart there, which is enough: a match starts and ends at word boundaries, and a match whose ends do
     * not map is left alone rather than rewriting characters it did not cover.
     */
    private class NfcView(val text: String, private val toOriginal: IntArray?) {

        fun originalOffset(offset: Int): Int? =
            if (toOriginal == null) offset else toOriginal[offset].takeIf { it >= 0 }

        companion object {
            fun of(text: String): NfcView {
                if (NFC.isNormalized(text)) return NfcView(text, null)
                val normalized = StringBuilder(text.length)
                val starts = mutableListOf<Pair<Int, Int>>()
                var segmentStart = 0
                var index = 0
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    if (index > 0 && NFC.hasBoundaryBefore(codePoint)) {
                        starts += normalized.length to segmentStart
                        normalized.append(NFC.normalize(text.substring(segmentStart, index)))
                        segmentStart = index
                    }
                    index += Character.charCount(codePoint)
                }
                starts += normalized.length to segmentStart
                normalized.append(NFC.normalize(text.substring(segmentStart)))
                val map = IntArray(normalized.length + 1) { -1 }
                starts.forEach { (nfc, original) -> map[nfc] = original }
                map[normalized.length] = text.length
                return NfcView(normalized.toString(), map)
            }
        }
    }

    private companion object {
        val NFC: Normalizer2 = Normalizer2.getNFCInstance()

        val SPACELESS_SCRIPTS = setOf(
            UScript.HAN, UScript.HIRAGANA, UScript.KATAKANA, UScript.THAI, UScript.LAO, UScript.KHMER, UScript.MYANMAR,
        )

        fun fold(text: String): String = UCharacter.foldCase(text, UCharacter.FOLD_CASE_DEFAULT)

        fun isSpacelessScript(codePoint: Int): Boolean = UScript.getScript(codePoint) in SPACELESS_SCRIPTS

        fun isWordCodePoint(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER,
            -> true
            else -> false
        }
    }
}
