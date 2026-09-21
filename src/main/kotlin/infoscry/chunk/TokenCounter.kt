package infoscry.chunk

/**
 * One token the embedder would receive: its identifier, and the span of the text it was produced from.
 *
 * [span] is `null` for a token the encoder added rather than read — the passage prefix and the special
 * tokens. Those are the tokens a chunk budget has to pay for without being able to see them, which is the
 * whole reason a count of them is carried instead of a hardcoded prefix length.
 */
data class EncodedToken(val id: Int, val span: IntRange?)

/**
 * One text encoded as the embedder receives it.
 *
 * Spans address the text that was encoded, not the prefixed string the model sees, so a caller can relate a
 * character offset in a unit to the tokens of the passage built from it.
 *
 * The body is contiguous by contract: a prefix comes before the text and special tokens after it, and a
 * counter that interleaved them would make [bodyRange] meaningless, so it is refused here rather than
 * producing offsets nobody can rely on.
 */
data class EncodedPassage(val tokens: List<EncodedToken>) {

    init {
        val body = tokens.withIndex().filter { (_, token) -> token.span != null }.map { (index, _) -> index }
        require(body.isEmpty() || body == (body.first()..body.last()).toList()) {
            "the tokens that came from the text must be contiguous, but they are at $body"
        }
    }

    /** How many tokens the passage carries in total: what the model's context budget is spent on. */
    val totalTokens: Int get() = tokens.size

    /** How many tokens the encoder added: the prefix and the special tokens. */
    val overheadTokens: Int get() = tokens.count { it.span == null }

    /** The tokens the passage's own text produced, or `null` when the text produced none. */
    val bodyRange: IntRange?
        get() = tokens.indices.filter { tokens[it].span != null }.let { indices ->
            if (indices.isEmpty()) null else indices.first()..indices.last()
        }

    /** How many tokens came from the text itself. */
    val bodyTokens: Int get() = bodyRange?.let { it.last - it.first + 1 } ?: 0

    /** The spans of the body tokens, in order. */
    fun bodySpans(): List<IntRange> = tokens.mapNotNull { it.span }
}

/**
 * How text is measured against one embedding model's passage.
 *
 * The counter is the only thing the chunker knows about the model, so the chunker's boundary arithmetic can
 * be tested against a counter with explicit overhead and token boundaries and then run unchanged against
 * the real tokenizer. [id] names the measurement: it travels into the chunking metadata, so chunks measured
 * by one tokenizer are never reused as if another had measured them.
 */
interface TokenCounter {

    /** Identifies this measurement, so stored chunks can be told apart from another tokenizer's. */
    val id: String

    /**
     * Encodes [text] as one passage, prefix and special tokens included.
     *
     * Truncation is never applied: a counter that shortened its input while its caller believed it had
     * measured the whole text would hide exactly the overflow the chunker exists to prevent. An oversized
     * input is the caller's problem to avoid, and the embedder's to refuse.
     */
    fun encodePassage(text: String): EncodedPassage
}

/**
 * The provisional counter: one token per whitespace-separated word, plus the prefix and special tokens a
 * passage carries.
 *
 * It exists so that the chunking stage is real and observable before the embedding model is wired, and it is
 * honest about being provisional in two ways. [id] names it, so the chunks it measured are re-chunked rather
 * than reused when the model's own tokenizer arrives. And it does not pretend to be that tokenizer: a word
 * is not a token, so a budget it accepts is not yet proof that the model's budget holds — which is why the
 * identity is recorded rather than assumed.
 *
 * Token identifiers are positional rather than a vocabulary. Nothing measured here is ever embedded, so the
 * only things that have to be stable are the count and the spans: a caller that needs real vocabulary ids
 * needs the real tokenizer, not this one.
 */
class WhitespaceTokenCounter(
    private val prefixTokens: Int = PREFIX_TOKENS,
    private val specialTokens: Int = SPECIAL_TOKENS,
) : TokenCounter {

    init {
        require(prefixTokens >= 0) { "prefixTokens must not be negative, was $prefixTokens" }
        require(specialTokens >= 0) { "specialTokens must not be negative, was $specialTokens" }
    }

    override val id: String = "whitespace-$prefixTokens-$specialTokens"

    override fun encodePassage(text: String): EncodedPassage {
        val tokens = ArrayList<EncodedToken>(prefixTokens + specialTokens + text.length / WORD_LENGTH)
        repeat(prefixTokens) { tokens += EncodedToken(id = tokens.size, span = null) }
        var index = 0
        while (index < text.length) {
            if (text[index].isWhitespace()) {
                index++
                continue
            }
            val start = index
            while (index < text.length && !text[index].isWhitespace()) index++
            tokens += EncodedToken(id = tokens.size, span = start until index)
        }
        repeat(specialTokens) { tokens += EncodedToken(id = tokens.size, span = null) }
        return EncodedPassage(tokens)
    }

    private companion object {
        /** The `passage: ` prefix the embedding model is trained with, as two word-shaped tokens. */
        const val PREFIX_TOKENS = 2

        /** The end-of-sequence token the model appends. */
        const val SPECIAL_TOKENS = 1

        /** A rough average word length, only used to size the token list. */
        const val WORD_LENGTH = 6
    }
}
