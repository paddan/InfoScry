package infoscry.ocr

/**
 * A 0–100 heuristic for how clean a page's extracted text looks (higher is better).
 *
 * It judges the text alone, so it applies to every engine and to direct text. It is a reading aid, not a
 * decision input: nothing is admitted, refused or published because of it. The weights are ported from the
 * palmemordsarkivet project's `quality.py`, without its spell-check dimension.
 */
object OcrQualityScorer {
    private const val WEIGHT_JUNK = 200.0
    private const val WEIGHT_SHORT_WORD = 80.0
    private const val WEIGHT_LONG_WORD = 100.0
    private const val WEIGHT_DIGIT_MIXED = 150.0
    private const val WEIGHT_VOWEL = 100.0
    private const val VOWEL_TARGET = 0.40
    private const val LONG_WORD_LENGTH = 18

    /** The most characters of a text that are judged; a longer text is scored on its first sample alone. */
    const val SAMPLE_CHARS = 4000

    private const val VOWELS = "aeiouyåäöAEIOUYÅÄÖ"
    private const val PUNCTUATION = ".,;:!?\"'()-—–…/\\[]{}<>"
    private val whitespaceRun = Regex("\\S+")
    private val alphaWord = Regex("[A-Za-zÅÄÖåäö\\-]+")
    private val letter = Regex("[A-Za-zÅÄÖåäö]")

    /** One scored page's size and score, the inputs of [documentScore]. */
    data class PageScore(val chars: Int, val score: Double)

    /** The score of [text], or null when it holds nothing to judge (a blank page is not a zero reading). */
    fun score(text: String): Double? {
        val sample = sampleOf(text)
        if (sample.isBlank()) return null
        val chars = sample.length
        val tokens = whitespaceRun.findAll(sample).map { it.value }.toList()
        val words = alphaWord.findAll(sample).map { it.value }.toList()

        val junk = sample.count { !(it.isLetterOrDigit() || it.isWhitespace() || it in PUNCTUATION) }
        val short = words.count { it.length <= 2 }
        val long = words.count { it.length >= LONG_WORD_LENGTH }
        val digitMixed = tokens.count { token -> token.any { it.isDigit() } && letter.containsMatchIn(token) }
        val letters = sample.count { it.isLetter() }
        val vowels = sample.count { it in VOWELS }

        var score = 100.0
        score -= junk.toDouble() / chars * WEIGHT_JUNK
        score -= short.toDouble() / maxOf(words.size, 1) * WEIGHT_SHORT_WORD
        score -= long.toDouble() / maxOf(words.size, 1) * WEIGHT_LONG_WORD
        score -= digitMixed.toDouble() / maxOf(tokens.size, 1) * WEIGHT_DIGIT_MIXED
        // A text with no letters has no vowel ratio to judge; the vowel term is skipped rather than charged.
        if (letters > 0) score -= Math.abs(vowels.toDouble() / letters - VOWEL_TARGET) * WEIGHT_VOWEL
        return Math.round(score.coerceIn(0.0, 100.0) * 10) / 10.0
    }

    /**
     * The document score over the texts of its units: each unit is scored on its own and weighted by its
     * sampled length. A unit with nothing to judge is left out, so all-blank units give null, not zero.
     */
    fun documentScoreOfTexts(texts: List<String>): Double? = documentScore(
        texts.mapNotNull { text ->
            score(text)?.let { PageScore(chars = minOf(text.length, SAMPLE_CHARS), score = it) }
        },
    )

    /** The first [SAMPLE_CHARS] characters of [text], backed off one unit if the cut would split a surrogate pair. */
    private fun sampleOf(text: String): String {
        if (text.length <= SAMPLE_CHARS) return text
        val end = if (text[SAMPLE_CHARS - 1].isHighSurrogate()) SAMPLE_CHARS - 1 else SAMPLE_CHARS
        return text.substring(0, end)
    }

    /** The mean of [pages] weighted by their length, or null when none was scored. */
    fun documentScore(pages: List<PageScore>): Double? {
        val total = pages.sumOf { it.chars.toLong() }
        if (total == 0L) return null
        val weighted = pages.sumOf { it.score * it.chars }
        return Math.round(weighted / total * 10) / 10.0
    }
}
