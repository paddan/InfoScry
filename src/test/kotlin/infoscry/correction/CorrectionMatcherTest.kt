package infoscry.correction

import infoscry.domain.CollectionId
import kotlin.test.Test
import kotlin.test.assertEquals

class CorrectionMatcherTest {

    /** A rule written as `find → replace`, the way a person reads it. */
    private fun rule(text: String, id: String = "r1", exactCase: Boolean = false): CorrectionRule {
        val (find, replace) = text.split(" → ")
        return CorrectionRule(id, CollectionId("c"), find, replace, exactCase, "2026-10-22T00:00:00Z", "2026-10-22T00:00:00Z")
    }

    private fun corrected(text: String, vararg rules: CorrectionRule): String = CorrectionMatcher(rules.toList()).apply(text).text

    private fun corrected(rule: String, text: String): String = corrected(text, rule(rule))

    @Test
    fun `a whole word is replaced and its punctuation kept`() {
        assertEquals("brev från Spinnars, igen", corrected("spinners → Spinnars", "brev från spinners, igen"))
        assertEquals("(Spinnars)", corrected("spinners → Spinnars", "(spinners)"))
    }

    @Test
    fun `part of a longer word is not a match`() {
        assertEquals("spinnersen ospinners", corrected("spinners → Spinnars", "spinnersen ospinners"))
    }

    @Test
    fun `a replacement with its own case is written as is unless the match is all upper case`() {
        assertEquals("SPINNARS", corrected("spinners → Spinnars", "SPINNERS"))
        assertEquals("Spinnars", corrected("spinners → Spinnars", "Spinners"))
        assertEquals("Spinnars", corrected("spinners → Spinnars", "spinners"))
    }

    @Test
    fun `a lower-case replacement follows the case of the match`() {
        assertEquals("Malmö MALMÖ malmö", corrected("malmo → malmö", "Malmo MALMO malmo"))
    }

    @Test
    fun `an exact-case rule matches only its own writing`() {
        assertEquals("Ab ab", corrected("AB ab", rule("AB → Ab", exactCase = true)))
    }

    @Test
    fun `Swedish letters are letters`() {
        assertEquals("Åsa", corrected("åsa → Åsa", "åsa"))
        assertEquals("såsa", corrected("åsa → Åsa", "såsa"))
    }

    @Test
    fun `a phrase matches across a line break and keeps it`() {
        assertEquals("Carl\nJohan", corrected("Karl Johan → Carl Johan", "Karl\nJohan"))
    }

    @Test
    fun `a phrase with another word count gets single spaces`() {
        assertEquals("Carl", corrected("Karl Johan → Carl", "Karl  \n Johan"))
    }

    @Test
    fun `case folding is Unicode full folding`() {
        assertEquals("GATAN", corrected("Straße → Gatan", "STRASSE"))
        assertEquals("Gatan", corrected("strasse → gatan", "Straße"))
    }

    @Test
    fun `case conversion never uses Turkish rules`() {
        assertEquals("İSTANBUL", corrected("istanbul → İstanbul", "ISTANBUL"))
    }

    @Test
    fun `decomposed text matches a composed rule and nothing else changes`() {
        assertEquals("Åsa", corrected("åsa → Åsa", "åsa"))
        assertEquals("x Åsa ý", corrected("åsa → Åsa", "x åsa ý"))
    }

    @Test
    fun `surrogate pairs are kept whole`() {
        assertEquals("Spinnars 😀", corrected("spinners → Spinnars", "spinners 😀"))
        assertEquals("前𠀌後", corrected("𠀋 → 𠀌", "前𠀋後"))
    }

    @Test
    fun `a rule in a script written without spaces matches as a substring`() {
        assertEquals("東亰都", corrected("東京 → 東亰", "東京都"))
    }

    @Test
    fun `a combining mark after the word means it is not a whole word`() {
        assertEquals("spinnerś", corrected("spinners → Spinnars", "spinnerś"))
    }

    @Test
    fun `the longest match wins`() {
        val result = CorrectionMatcher(listOf(rule("Karl → Carl", "r1"), rule("Karl Johan → Carl Johan", "r2"))).apply("Karl Johan")
        assertEquals("Carl Johan", result.text)
        assertEquals(mapOf("r2" to 1), result.matchesByRule())
    }

    @Test
    fun `replaced text is not matched again`() {
        assertEquals("b1 c1", corrected("a1 b1", rule("a1 → b1", "r1"), rule("b1 → c1", "r2")))
    }

    @Test
    fun `the order of the list does not change the result`() {
        val rules = listOf(
            rule("spinners → Spinnars", "r1"),
            rule("Karl → Carl", "r2"),
            rule("Karl Johan → Carl Johan", "r3"),
            rule("malmo → malmö", "r4"),
            rule("AB → Ab", "r5", exactCase = true),
        )
        val text = "Karl Johan och spinners i Malmo, AB och Karl."
        val expected = CorrectionMatcher(rules).apply(text).text
        listOf(rules.reversed(), rules.shuffled(java.util.Random(7)), rules.drop(2) + rules.take(2)).forEach { order ->
            assertEquals(expected, CorrectionMatcher(order).apply(text).text)
        }
        assertEquals("Carl Johan och Spinnars i Malmö, Ab och Carl.", expected)
    }

    @Test
    fun `applying to the same reading twice gives the same result`() {
        val matcher = CorrectionMatcher(listOf(rule("spinners → Spinnars")))
        assertEquals(matcher.apply("hos spinners"), matcher.apply("hos spinners"))
    }

    @Test
    fun `edits name the input's own positions`() {
        val result = CorrectionMatcher(listOf(rule("spinners → Spinnarsson"))).apply("hos spinners nu")
        assertEquals(listOf(TextEdit(4, 12, "Spinnarsson", "r1")), result.edits)
        assertEquals(true, result.changed)
    }

    @Test
    fun `text without a match is unchanged and has no edits`() {
        val result = CorrectionMatcher(listOf(rule("spinners → Spinnars"))).apply("inget här")
        assertEquals("inget här", result.text)
        assertEquals(emptyList(), result.edits)
        assertEquals(false, result.changed)
    }

    @Test
    fun `a rule that writes a word back as it was makes no edit`() {
        val result = CorrectionMatcher(listOf(rule("SPINNERS → spinners"))).apply("hos spinners")

        assertEquals(emptyList(), result.edits)
        assertEquals(false, result.changed)
    }

    @Test
    fun `long runs of punctuation stay fast`() {
        val rules = (1..20).map { index -> rule("ord$index → Ord$index", "r$index") }
        val page = (1..40).joinToString("\n") { "Kapitel $it " + ".".repeat(300) + " $it" }
        val started = System.nanoTime()

        CorrectionMatcher(rules).apply(page)

        val millis = (System.nanoTime() - started) / 1_000_000
        kotlin.test.assertTrue(millis < 500, "a page of dot leaders took $millis ms")
    }

    @Test
    fun `a long run without spaces stays fast`() {
        val rules = (1..20).map { index -> rule("ord$index → Ord$index", "r$index") }
        val page = (1..4).joinToString(" ") { (1..20_000).joinToString(".") { "a" } }
        val started = System.nanoTime()

        CorrectionMatcher(rules).apply(page)

        val millis = (System.nanoTime() - started) / 1_000_000
        kotlin.test.assertTrue(millis < 1000, "a page of one long dotted token took $millis ms")
    }
}
