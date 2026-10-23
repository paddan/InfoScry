package infoscry.search

import infoscry.domain.CollectionId
import infoscry.glossary.Glossary
import infoscry.glossary.GlossaryGroup
import infoscry.glossary.GlossaryRules
import infoscry.glossary.GlossaryTermInput
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.Query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GlossaryQueryRewriterTest {

    private val collection = CollectionId("c")
    private val rewriter = GlossaryQueryRewriter()

    private fun parse(text: String): Query = QueryParser(LuceneSchema.FIELD_TEXT, LuceneIndex.newAnalyzer()).parse(text)

    private fun t(text: String, prefix: Boolean = false) = GlossaryTermInput(text, prefix)

    private fun group(id: String, vararg terms: GlossaryTermInput) =
        GlossaryGroup(id, collection, GlossaryRules.normalize(terms.toList()), "t", "t")

    private fun glossary(vararg groups: GlossaryGroup) = Glossary(collection, groups.toList())

    private val stockholm = glossary(group("s", t("Sthlm"), t("Stockholm"), t("Sthlms stad")))

    private fun rewrite(text: String, glossary: Glossary = stockholm) = rewriter.rewrite(parse(text), glossary)

    @Test
    fun `a single word gains its alternatives`() {
        val result = rewrite("Sthlm")

        assertEquals("""text:sthlm text:stockholm text:"sthlms stad"""", result.query.toString())
        assertEquals(listOf("s"), result.groups.map { it.id })
        assertFalse(result.truncated)
    }

    @Test
    fun `a required word stays required with its alternatives`() {
        assertEquals("+(text:sthlm text:stockholm text:\"sthlms stad\") +text:hamn", rewrite("+Sthlm +hamn").query.toString())
    }

    @Test
    fun `operators around an expanded word keep their meaning`() {
        assertEquals(
            "+(text:sthlm text:stockholm text:\"sthlms stad\") -text:hamn",
            rewrite("+Sthlm -hamn").query.toString(),
        )
        assertEquals(
            "+(text:sthlm text:stockholm text:\"sthlms stad\") +text:\"stora torget\"",
            rewrite("Sthlm AND \"stora torget\"").query.toString(),
        )
    }

    @Test
    fun `other clause kinds are left as they are`() {
        listOf("-Sthlm +hamn", "Sthl*", "Sthlm~1", "title:Sthlm", "[a TO b]", "Sthlm^2").forEach { text ->
            val parsed = parse(text)
            assertEquals(parsed.toString(), rewriter.rewrite(parsed, stockholm).query.toString(), text)
        }
    }

    @Test
    fun `an unquoted phrase term keeps its optional words and adds the alternatives`() {
        assertEquals("text:sthlms text:stad text:sthlm text:stockholm", rewrite("Sthlms stad").query.toString())
    }

    @Test
    fun `an unquoted required phrase term becomes one required choice`() {
        assertEquals(
            "+((+text:sthlms +text:stad) text:sthlm text:stockholm) +text:hamn",
            rewrite("+Sthlms +stad +hamn").query.toString(),
        )
    }

    @Test
    fun `a quoted phrase gains one variant per alternative`() {
        assertEquals(
            """text:"hamnen i sthlm" text:"hamnen i stockholm" text:"hamnen i sthlms stad"""",
            rewrite("\"hamnen i Sthlm\"").query.toString(),
        )
    }

    @Test
    fun `a prefix alternative is a prefix query outside a phrase and exact inside one`() {
        val prefixed = glossary(group("p", t("Stockholm"), t("Sthlm", prefix = true)))

        assertEquals("text:stockholm text:sthlm*", rewrite("Stockholm", prefixed).query.toString())
        assertEquals("""text:"i stockholm" text:"i sthlm"""", rewrite("\"i Stockholm\"", prefixed).query.toString())
    }

    @Test
    fun `a word that starts with a prefix term hits its group`() {
        val prefixed = glossary(group("p", t("Stockholm"), t("Sthlm", prefix = true)))

        assertEquals("text:sthlms text:stockholm text:sthlm*", rewrite("Sthlms", prefixed).query.toString())
    }

    @Test
    fun `a phrase has at most sixteen variants`() {
        val many = glossary(group("m", *(1..20).map { t("w$it") }.toTypedArray()))

        val result = rewrite("\"w1 w2\"", many)

        assertEquals(17, result.query.toString().split("text:").size - 1)
        assertTrue(result.truncated)
    }

    @Test
    fun `expansion stops at the clause bound, in query order`() {
        val groups = (1..300).map { group("g$it", t("w$it"), t("x$it")) }.toTypedArray()
        val query = (1..300).joinToString(" ") { "w$it" }

        val result = GlossaryQueryRewriter(maxAddedClauses = 256).rewrite(parse(query), glossary(*groups))

        assertEquals(256, result.groups.size)
        assertEquals("g1", result.groups.first().id)
        assertTrue(result.truncated)
        assertEquals(300 + 256, GlossaryQueryRewriter.clauseCount(result.query))
    }

    @Test
    fun `a repeated word lists its group once`() {
        assertEquals(listOf("s"), rewrite("Sthlm Sthlm").groups.map { it.id })
    }

    @Test
    fun `nested groups are rewritten`() {
        assertEquals(
            "text:hamn (+(text:sthlm text:stockholm text:\"sthlms stad\") +text:torg)",
            rewrite("hamn (+Sthlm +torg)").query.toString(),
        )
    }

    @Test
    fun `nothing to expand returns the parsed query itself`() {
        val parsed = parse("hamnen")
        assertSame(parsed, rewriter.rewrite(parsed, stockholm).query)
        assertSame(parsed, rewriter.rewrite(parsed, Glossary.empty(collection)).query)
    }
}
