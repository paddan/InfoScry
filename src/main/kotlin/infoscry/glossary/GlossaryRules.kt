package infoscry.glossary

import com.ibm.icu.text.Normalizer2
import infoscry.search.TextAnalysis

/** The rules every stored glossary group obeys. */
object GlossaryRules {

    const val MIN_TERMS = 2
    const val MAX_TERMS = 20

    /** The longest term, in characters. */
    const val MAX_LENGTH = 200

    /** The most analyzed tokens one term may have. */
    const val MAX_TOKENS = 8

    /** The shortest key a prefix term may have; the same floor as search's lexical anchor. */
    const val MIN_PREFIX_LENGTH = 3

    private val NFC: Normalizer2 = Normalizer2.getNFCInstance()
    private val WHITESPACE = Regex("\\s+")

    /** [text] as stored: composed, trimmed, whitespace collapsed. */
    fun clean(text: String): String = NFC.normalize(text).trim().replace(WHITESPACE, " ")

    /** The key [text] matches by: its analyzed tokens joined by one space; empty when it has none. */
    fun key(text: String): String = TextAnalysis.tokens(clean(text)).joinToString(" ")

    /**
     * Composes, trims and analyzes [input], merges terms with the same key into the first of them, and validates
     * the group. Indexes in a refusal refer to [input] as submitted.
     *
     * @throws InvalidGlossaryException when the group cannot be stored as written.
     */
    fun normalize(input: List<GlossaryTermInput>): List<GlossaryTerm> {
        if (input.size > MAX_TERMS) throw InvalidGlossaryException("a glossary group has at most $MAX_TERMS terms")
        val terms = LinkedHashMap<String, GlossaryTerm>()
        input.forEachIndexed { index, term ->
            val text = clean(term.text)
            if (text.length > MAX_LENGTH) {
                throw InvalidGlossaryException("a glossary term is at most $MAX_LENGTH characters", index)
            }
            val tokens = TextAnalysis.tokens(text)
            if (tokens.isEmpty()) throw InvalidGlossaryException("a glossary term needs a letter or digit", index)
            if (tokens.size > MAX_TOKENS) {
                throw InvalidGlossaryException("a glossary term is at most $MAX_TOKENS words", index)
            }
            if (term.prefix && (tokens.size != 1 || tokens[0].length < MIN_PREFIX_LENGTH)) {
                throw InvalidGlossaryException(
                    "only a single word of at least $MIN_PREFIX_LENGTH characters can match the start of words",
                    index,
                )
            }
            val key = tokens.joinToString(" ")
            terms.putIfAbsent(key, GlossaryTerm(text, key, term.prefix))
        }
        if (terms.size < MIN_TERMS) throw InvalidGlossaryException("a glossary group needs at least $MIN_TERMS different terms")
        return terms.values.toList()
    }
}
