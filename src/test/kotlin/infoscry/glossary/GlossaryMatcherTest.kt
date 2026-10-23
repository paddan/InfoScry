package infoscry.glossary

import infoscry.domain.CollectionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlossaryMatcherTest {

    private val collection = CollectionId("c")

    private fun group(id: String, vararg terms: GlossaryTermInput) =
        GlossaryGroup(id, collection, GlossaryRules.normalize(terms.toList()), "t", "t")

    private fun t(text: String, prefix: Boolean = false) = GlossaryTermInput(text, prefix)

    private val stockholm = group("a", t("Stockholm"), t("Sthlms stad"))
    private val sthlm = group("b", t("Sthlm", prefix = true), t("Sthlm city"))
    private val kf = group("k", t("KF"), t("Kooperativa förbundet"))
    private val matcher = GlossaryMatcher(Glossary(collection, listOf(stockholm, sthlm, kf)))

    @Test
    fun `the longest term wins over a prefix on the same word`() {
        val hits = matcher.hits(listOf("sthlms", "stad", "hamn"))

        assertEquals(1, hits.size)
        assertEquals(GlossaryHit(0, 2, stockholm, stockholm.terms[1]), hits.single())
    }

    @Test
    fun `a prefix term hits a word that starts with it`() {
        val hit = matcher.hits(listOf("i", "sthlmsregionen")).single()

        assertEquals(1, hit.start)
        assertEquals("b", hit.group.id)
        assertEquals(sthlm.terms[0], hit.term)
    }

    @Test
    fun `a repeated term hits each time`() {
        assertEquals(listOf(0, 1), matcher.hits(listOf("stockholm", "stockholm")).map { it.start })
    }

    @Test
    fun `an exact term does not hit a longer word`() {
        assertTrue(matcher.hits(listOf("kfum")).isEmpty())
        assertEquals("k", matcher.hits(listOf("kooperativa", "forbundet")).single().group.id)
    }

    @Test
    fun `hits do not overlap`() {
        val hits = matcher.hits(listOf("sthlm", "city", "stockholm"))

        assertEquals(listOf(0 to 2, 2 to 3), hits.map { it.start to it.endExclusive })
    }

    @Test
    fun `an empty glossary hits nothing`() {
        assertTrue(GlossaryMatcher(Glossary.empty(collection)).hits(listOf("stockholm")).isEmpty())
    }
}
