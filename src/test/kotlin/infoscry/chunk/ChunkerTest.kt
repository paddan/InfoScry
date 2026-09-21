package infoscry.chunk

import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How a content unit becomes the chunks an embedder and an index receive.
 *
 * The rules these tests pin are the ones a citation depends on: a chunk never crosses a unit, the chunks
 * of a unit cover every character of it, an overlap is measured in body tokens rather than characters, and
 * a repeated header is paid for out of the same passage budget as the body. They run against counters
 * whose overhead and token boundaries are explicit, because the boundary arithmetic must not depend on any
 * particular model's tokenizer.
 */
class ChunkerTest {

    @Test
    fun `a short unit becomes one chunk`() {
        val unit = unitOf("alpha\nbeta\n")

        val plan = chunker().chunk(unit)

        assertEquals(1, plan.drafts.size)
        val draft = plan.drafts.single()
        assertEquals(unit.searchText, draft.text)
        assertEquals(0, draft.startOffset)
        assertEquals(unit.searchText.length, draft.endOffset)
        assertFalse(plan.headerDropped)
    }

    @Test
    fun `every chunk fits the passage budget including the prefix it does not carry`() {
        val unit = unitOf(words(4000))

        val plan = chunker().chunk(unit)

        assertTrue(plan.drafts.size > 1, "a 4000-word unit does not fit in one passage")
        plan.drafts.forEach { draft ->
            assertTrue(
                draft.tokenCount <= BUDGET,
                "chunk ${draft.ordinal} needs ${draft.tokenCount} tokens, budget is $BUDGET",
            )
        }
    }

    @Test
    fun `the chunks of a unit cover every character of it`() {
        val unit = unitOf(words(1500) + "\n" + words(900))

        val plan = chunker().chunk(unit)

        assertEquals(unit.searchText, covered(unit, plan), "the chunks do not reconstruct the unit")
    }

    @Test
    fun `a final section between 513 and 600 tokens is still covered`() {
        // One token per word plus a three-token overhead: 557 words is a unit that needs two passages and
        // whose tail is far smaller than a full one, which is the section that gets dropped when a walk
        // stops at the first boundary that does not fit.
        val unit = unitOf(words(557))

        val plan = chunker().chunk(unit)

        assertEquals(2, plan.drafts.size)
        assertEquals(unit.searchText, covered(unit, plan))
        val last = plan.drafts.last()
        assertTrue(last.tokenCount <= BUDGET)
        assertTrue(last.endOffset == unit.searchText.length)
    }

    @Test
    fun `an overlap is measured in body tokens, not characters`() {
        val unit = unitOf(words(2000))

        val plan = chunker().chunk(unit)

        assertTrue(plan.drafts.size > 2)
        for (index in 1 until plan.drafts.size) {
            val previous = plan.drafts[index - 1]
            val current = plan.drafts[index]
            assertTrue(current.startOffset < previous.endOffset, "chunk $index does not overlap its predecessor")
            val overlap = unit.searchText.substring(current.startOffset, previous.endOffset)
            assertEquals(
                OVERLAP,
                counter().encodePassage(overlap).bodyTokens,
                "the overlap before chunk $index is not $OVERLAP body tokens",
            )
        }
    }

    @Test
    fun `an overlap of zero tiles the unit without gaps`() {
        val unit = unitOf(words(1200))

        val plan = chunker().chunk(unit, maxSequenceTokens = BUDGET, overlapTokens = 0)

        assertTrue(plan.drafts.size > 1)
        plan.drafts.zipWithNext { previous, current ->
            assertEquals(previous.endOffset, current.startOffset)
        }
        assertEquals(unit.searchText, covered(unit, plan))
    }

    @Test
    fun `an overlap that nearly fills the body still makes progress`() {
        val unit = unitOf(words(400))

        val plan = chunker().chunk(unit, maxSequenceTokens = 20, overlapTokens = 19)

        assertTrue(plan.drafts.size > 1)
        plan.drafts.forEach { draft ->
            assertTrue(draft.endOffset > draft.startOffset, "chunk ${draft.ordinal} covers nothing")
            assertTrue(draft.tokenCount <= 20, "chunk ${draft.ordinal} needs ${draft.tokenCount} tokens")
        }
        assertEquals(unit.searchText, covered(unit, plan))
    }

    @Test
    fun `swedish text is split by the counter's own boundaries and reconstructs exactly`() {
        val unit = unitOf("Ångström\nÄrende: återbetalning av överskott\nÖvertrassering\n".repeat(120))

        val plan = chunker().chunk(unit, maxSequenceTokens = 60, overlapTokens = 10)

        assertTrue(plan.drafts.size > 1)
        assertEquals(unit.searchText, covered(unit, plan))
        plan.drafts.forEach { draft ->
            assertEquals(unit.searchText.substring(draft.startOffset, draft.endOffset), draft.text)
        }
    }

    @Test
    fun `a unit whose text is blank produces no chunks`() {
        val unit = unitOf("   \n\t\n")

        val plan = chunker().chunk(unit)

        assertTrue(plan.drafts.isEmpty())
    }

    @Test
    fun `a spreadsheet unit repeats its header in every chunk and pays for it`() {
        // One long unbroken run of cells below the header, so the boundary is decided by the passage budget
        // rather than by a line break: that is the only way the header's own cost is visible as a shorter
        // body.
        val cells = (1..400).joinToString(" ") { "row$it value$it" }
        val text = "account\tamount\n$cells"
        val spreadsheet = unitOf(text, locator = SourceLocation.SpreadsheetRange("Sheet1", "A1", "B401"))
        val plain = unitOf(text, locator = SourceLocation.TextLines(1, 401))

        val withHeader = chunker().chunk(spreadsheet)
        val withoutHeader = chunker().chunk(plain)

        // The header is paid for out of the same passage budget as the body, so the first chunk of a
        // spreadsheet unit carries fewer of its own characters than the first chunk of the same text read
        // as plain lines.
        val headerBody = withHeader.drafts.first().let { it.endOffset - it.startOffset }
        val plainBody = withoutHeader.drafts.first().let { it.endOffset - it.startOffset }
        assertTrue(
            headerBody < plainBody,
            "the repeated header did not consume the budget: $headerBody body characters against $plainBody",
        )
        withHeader.drafts.forEach { draft ->
            assertTrue(draft.text.startsWith("account\tamount\n"), "chunk ${draft.ordinal} lost the header")
            assertTrue(draft.tokenCount <= BUDGET, "chunk ${draft.ordinal} needs ${draft.tokenCount} tokens")
            assertEquals(
                "account\tamount\n".length + draft.endOffset - draft.startOffset,
                draft.text.length,
                "chunk ${draft.ordinal} carries something other than the header and its own span",
            )
        }
        assertEquals(text, covered(spreadsheet, withHeader))
    }

    @Test
    fun `a non-spreadsheet unit repeats nothing`() {
        val rows = (1..400).joinToString("\n") { "row $it\tvalue $it" }
        val unit = unitOf("account\tamount\n$rows\n", locator = SourceLocation.TextLines(1, 401))

        val plan = chunker().chunk(unit)

        plan.drafts.drop(1).forEach { draft ->
            assertFalse(draft.text.startsWith("account\tamount\n"), "chunk ${draft.ordinal} repeats a header")
        }
        assertEquals(unit.searchText, covered(unit, plan))
    }

    @Test
    fun `a header too large to leave room for the body is dropped rather than truncating the text`() {
        val longHeader = (1..600).joinToString(" ") { "column$it" }
        val text = "$longHeader\n" + words(1200)
        val unit = unitOf(text, locator = SourceLocation.SpreadsheetRange("Sheet1", "A1", "ZZ2"))

        val plan = chunker().chunk(unit)

        assertTrue(plan.headerDropped, "an unusable header was not reported")
        plan.drafts.forEach { draft ->
            assertTrue(draft.tokenCount <= BUDGET, "chunk ${draft.ordinal} needs ${draft.tokenCount} tokens")
        }
        assertEquals(text, covered(unit, plan))
    }

    @Test
    fun `the token offsets delimit the body inside the passage the chunk encodes`() {
        val unit = unitOf(words(900))

        val plan = chunker().chunk(unit)

        plan.drafts.forEach { draft ->
            val passage = counter().encodePassage(draft.text)
            assertEquals(passage.totalTokens, draft.tokenCount)
            val body = passage.bodyRange
            assertNotNull(body)
            assertEquals(body.first, draft.tokenStart)
            assertEquals(body.last, draft.tokenEnd)
        }
    }

    @Test
    fun `a counter with more overhead produces smaller bodies from the same text`() {
        val unit = unitOf(words(2000))

        val small = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)).chunk(unit)
        val large = Chunker(WhitespaceTokenCounter(prefixTokens = 9, specialTokens = 4)).chunk(unit)

        // The same budget has to pay for a longer prefix, so less of it is left for the body: a chunker that
        // subtracted a hardcoded prefix length would produce identical bodies for both counters.
        val smallBody = counter().encodePassage(small.drafts.first().text).bodyTokens
        val largeBody = counter().encodePassage(large.drafts.first().text).bodyTokens
        assertTrue(
            largeBody < smallBody,
            "a longer prefix has to shorten the body; got $largeBody against $smallBody body tokens",
        )
        large.drafts.forEach { draft -> assertTrue(draft.tokenCount <= BUDGET) }
        assertEquals(unit.searchText, covered(unit, large))
    }

    @Test
    fun `a counter that tokenises per character is honoured`() {
        val unit = unitOf(words(600))

        val plan = Chunker(CharacterTokenCounter()).chunk(unit, maxSequenceTokens = 100, overlapTokens = 10)

        assertTrue(plan.drafts.size > 1)
        plan.drafts.forEach { draft ->
            assertTrue(draft.tokenCount <= 100, "chunk ${draft.ordinal} needs ${draft.tokenCount} tokens")
        }
        assertEquals(unit.searchText, covered(unit, plan))
    }

    @Test
    fun `a counter that reads no body token is refused rather than handed fabricated offsets`() {
        val unit = unitOf("alpha beta")

        val failure = assertFailsWith<IllegalStateException> { Chunker(HeadlessTokenCounter()).chunk(unit) }

        assertTrue(
            failure.message.orEmpty().contains("no body token"),
            "the refusal has to name what went wrong, was: ${failure.message}",
        )
    }

    /** The characters the chunks of [unit] cover, in order, with the overlap counted once. */
    private fun covered(unit: ContentUnit, plan: ChunkPlan): String {
        val text = unit.searchText
        val builder = StringBuilder()
        var cursor = 0
        plan.drafts.forEach { draft ->
            assertTrue(draft.startOffset <= cursor, "chunk ${draft.ordinal} leaves a gap before it")
            assertTrue(draft.endOffset > cursor, "chunk ${draft.ordinal} covers no new text")
            builder.append(text, cursor, draft.endOffset)
            cursor = draft.endOffset
        }
        assertEquals(text.length, cursor, "the chunks do not reach the end of the unit")
        return builder.toString()
    }

    private fun chunker(): Chunker = Chunker(counter())

    private fun counter(): WhitespaceTokenCounter = WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)

    private fun words(count: Int): String = (1..count).joinToString(" ") { "word$it" }

    private fun unitOf(
        text: String,
        locator: SourceLocation = SourceLocation.TextLines(1, 1),
    ): ContentUnit = ContentUnit(
        id = ContentUnitId.new(),
        documentId = DocumentId.new(),
        ordinal = 0,
        locator = locator,
        extractedText = text,
        searchText = text,
    )

    private companion object {
        const val BUDGET = 512
        const val OVERLAP = 100
    }
}

/** A counter with one token per character and an explicit two-token prefix, to prove the shapes differ. */
private class CharacterTokenCounter : TokenCounter {

    override val id: String = "character-2-1"

    override fun encodePassage(text: String): EncodedPassage = EncodedPassage(
        buildList {
            repeat(2) { add(EncodedToken(id = -1, span = null)) }
            text.forEachIndexed { index, character -> add(EncodedToken(id = character.code, span = index..index)) }
            add(EncodedToken(id = -2, span = null))
        },
    )
}

/**
 * A counter that reads no body token at all: every token it produces is one the encoder added.
 *
 * A real tokenizer cannot behave this way, which is exactly why the chunker has to refuse it instead of
 * inventing a range — a chunk built from fabricated offsets would cite characters it does not cover.
 */
private class HeadlessTokenCounter : TokenCounter {

    override val id: String = "headless"

    override fun encodePassage(text: String): EncodedPassage =
        EncodedPassage(listOf(EncodedToken(id = 0, span = null), EncodedToken(id = 1, span = null)))
}
