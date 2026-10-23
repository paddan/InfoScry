package infoscry.glossary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlossaryRulesTest {

    private fun terms(vararg texts: String) = texts.map { GlossaryTermInput(it) }

    @Test
    fun `terms that differ only in case merge into the first`() {
        val normalized = GlossaryRules.normalize(terms("Sthlm", "STHLM", "Stockholm"))

        assertEquals(listOf("Sthlm", "Stockholm"), normalized.map { it.text })
        assertEquals(listOf("sthlm", "stockholm"), normalized.map { it.matchKey })
    }

    @Test
    fun `decomposed and composed writing share one key and are stored composed`() {
        val normalized = GlossaryRules.normalize(terms("Malmö", "Malmö stad"))

        assertEquals("Malmö", normalized[0].text)
        assertEquals("malmo", normalized[0].matchKey)
        assertEquals("malmo stad", normalized[1].matchKey)
        assertEquals(listOf("malmo", "stad"), normalized[1].tokens)
    }

    @Test
    fun `whitespace is trimmed and collapsed`() {
        assertEquals("Sthlms stad", GlossaryRules.normalize(terms("  Sthlms \n stad ", "Stockholm"))[0].text)
    }

    @Test
    fun `a term that analyzes to nothing is refused with its index`() {
        val refused = assertFailsWith<InvalidGlossaryException> { GlossaryRules.normalize(terms("Stockholm", "!!!")) }
        assertEquals(1, refused.term)
    }

    @Test
    fun `a group needs two to twenty distinct terms`() {
        assertNull(assertFailsWith<InvalidGlossaryException> { GlossaryRules.normalize(terms("Stockholm")) }.term)
        assertNull(assertFailsWith<InvalidGlossaryException> { GlossaryRules.normalize(terms("Sthlm", "sthlm")) }.term)
        val many = (1..21).map { "term$it" }.toTypedArray()
        assertFailsWith<InvalidGlossaryException> { GlossaryRules.normalize(terms(*many)) }
        assertEquals(20, GlossaryRules.normalize(terms(*many.take(20).toTypedArray())).size)
    }

    @Test
    fun `a term longer than two hundred characters or eight words is refused`() {
        val long = "a".repeat(201)
        assertEquals(1, assertFailsWith<InvalidGlossaryException> { GlossaryRules.normalize(terms("Stockholm", long)) }.term)
        val wordy = (1..9).joinToString(" ") { "w$it" }
        assertEquals(1, assertFailsWith<InvalidGlossaryException> { GlossaryRules.normalize(terms("Stockholm", wordy)) }.term)
        GlossaryRules.normalize(terms("Stockholm", (1..8).joinToString(" ") { "w$it" }))
    }

    @Test
    fun `the prefix flag needs a single word of at least three characters`() {
        val phrase = listOf(GlossaryTermInput("Stockholm"), GlossaryTermInput("Sthlms stad", prefix = true))
        assertEquals(1, assertFailsWith<InvalidGlossaryException> { GlossaryRules.normalize(phrase) }.term)
        val short = listOf(GlossaryTermInput("KF", prefix = true), GlossaryTermInput("Kooperativa förbundet"))
        assertEquals(0, assertFailsWith<InvalidGlossaryException> { GlossaryRules.normalize(short) }.term)

        val accepted = GlossaryRules.normalize(listOf(GlossaryTermInput("Sthlm", prefix = true), GlossaryTermInput("Stockholm")))
        assertTrue(accepted[0].prefix)
    }

    @Test
    fun `scripts without spaces are tokenized as search tokenizes them`() {
        val normalized = GlossaryRules.normalize(terms("東京", "Tokyo"))
        assertEquals(normalized[0].tokens.joinToString(" "), normalized[0].matchKey)
        assertTrue(normalized[0].tokens.isNotEmpty())
    }
}
