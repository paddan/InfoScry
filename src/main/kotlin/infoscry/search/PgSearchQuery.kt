package infoscry.search

import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.ConstantScoreQuery
import org.apache.lucene.search.FuzzyQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.MatchNoDocsQuery
import org.apache.lucene.search.MultiPhraseQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.RegexpQuery
import org.apache.lucene.search.SynonymQuery
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.TermRangeQuery
import org.apache.lucene.search.WildcardQuery

/** One pg_search query expression with its bound parameters, in order. */
data class TranslatedQuery(val sql: String, val parameters: List<Any>)

/**
 * Translates a query Lucene's classic parser (and the glossary rewriter) built into pg_search's query builders.
 *
 * The parser stays the authority on what a user's query means — phrases, `+`/`-`, `AND`/`OR`/`NOT`, prefixes,
 * wildcards, fuzziness and boosts — and the analyzer it parses with yields exactly the BM25 index's tokens, so
 * every term it produces is a term the index holds. Each node becomes the builder with the same meaning:
 * a boolean query's clauses keep their occurrence (a filter clause is scored as zero), and a prefix, wildcard
 * or regular expression is a constant-score match, as it is in Lucene. Every value is a bound parameter.
 */
object PgSearchQuery {
    const val TEXT_FIELD: String = "text"
    const val COLLECTION_FIELD: String = "collection_id"

    fun translate(query: Query): TranslatedQuery {
        val parameters = mutableListOf<Any>()
        val sql = node(query, parameters)
        return TranslatedQuery(sql, parameters)
    }

    /** The regular expression of every term that starts with [prefix]. */
    fun prefixPattern(prefix: String): String = quoteFree(prefix) + ".*"

    private fun node(query: Query, parameters: MutableList<Any>): String = when (query) {
        is TermQuery -> term(query.term, parameters)
        is BooleanQuery -> boolean(query, parameters)
        is PhraseQuery -> phrase(query.field, query.terms.map { it.text() }, query.slop, parameters)
        is MultiPhraseQuery -> {
            // The analyzer never stacks tokens, so each position holds one term; a stacked position would
            // need a regex phrase, and is refused rather than silently narrowed.
            val positions = query.termArrays
            require(positions.all { it.size == 1 }) { "a phrase with alternatives at one position is not supported" }
            phrase(positions.first().first().field(), positions.map { it.first().text() }, query.slop, parameters)
        }
        is PrefixQuery -> constant(regex(query.prefix.field(), prefixPattern(query.prefix.text()), parameters))
        is WildcardQuery -> constant(regex(query.term.field(), wildcardPattern(query.term.text()), parameters))
        is RegexpQuery -> constant(regex(query.field, query.regexp.text(), parameters))
        is FuzzyQuery -> {
            parameters += query.term.text()
            "paradedb.fuzzy_term(${field(query.term.field())}, ?::text, ${query.maxEdits}, ${query.transpositions})"
        }
        is BoostQuery -> "paradedb.boost(${query.boost}::real, ${node(query.query, parameters)})"
        is ConstantScoreQuery -> constant(node(query.query, parameters))
        is TermInSetQuery -> termSet(query, parameters)
        is SynonymQuery -> {
            val terms = query.terms
            "paradedb.boolean(should => ARRAY[${terms.joinToString(", ") { term(it, parameters) }}])"
        }
        is MatchAllDocsQuery -> "paradedb.all()"
        is MatchNoDocsQuery -> "paradedb.empty()"
        // A range over free text has no meaning in this archive's vocabulary; it matches nothing rather
        // than being guessed at.
        is TermRangeQuery -> "paradedb.empty()"
        else -> throw IllegalArgumentException("a ${query::class.simpleName} cannot be searched")
    }

    private fun term(term: Term, parameters: MutableList<Any>): String {
        parameters += term.text()
        return "paradedb.term(${field(term.field())}, ?::text)"
    }

    private fun termSet(query: TermInSetQuery, parameters: MutableList<Any>): String {
        val values = buildList {
            val iterator = query.bytesRefIterator
            while (true) add((iterator.next() ?: break).utf8ToString())
        }
        parameters.add(values.toTypedArray())
        return "paradedb.term_set(${field(query.field)}, ?::text[])"
    }

    private fun phrase(field: String, terms: List<String>, slop: Int, parameters: MutableList<Any>): String {
        if (terms.size == 1) {
            parameters += terms.single()
            return "paradedb.term(${field(field)}, ?::text)"
        }
        parameters.add(terms.toTypedArray())
        return "paradedb.phrase(${field(field)}, ?::text[], $slop)"
    }

    private fun regex(field: String, pattern: String, parameters: MutableList<Any>): String {
        parameters += pattern
        return "paradedb.regex(${field(field)}, ?::text)"
    }

    private fun boolean(query: BooleanQuery, parameters: MutableList<Any>): String {
        // Lucene matches nothing for a query of exclusions alone; so does the builder. Decided before any clause
        // is translated, so no parameter is bound for SQL that is not written.
        if (query.clauses().all { it.occur == BooleanClause.Occur.MUST_NOT }) return "paradedb.empty()"
        val must = mutableListOf<String>()
        val should = mutableListOf<String>()
        val mustNot = mutableListOf<String>()
        for (clause in query.clauses()) {
            when (clause.occur) {
                BooleanClause.Occur.MUST -> must += node(clause.query, parameters)
                // A filter decides membership and adds nothing to the score, as in Lucene.
                BooleanClause.Occur.FILTER -> must += "paradedb.const_score(0::real, ${node(clause.query, parameters)})"
                BooleanClause.Occur.SHOULD -> should += node(clause.query, parameters)
                BooleanClause.Occur.MUST_NOT -> mustNot += node(clause.query, parameters)
            }
        }
        return buildString {
            append("paradedb.boolean(")
            append(listOf("must" to must, "should" to should, "must_not" to mustNot)
                .filter { it.second.isNotEmpty() }
                .joinToString(", ") { (name, clauses) -> "$name => ARRAY[${clauses.joinToString(", ")}]" })
            append(")")
        }
    }

    /** A multi-term match scores like Lucene's constant-score rewrite: one, whatever the term frequency. */
    private fun constant(inner: String): String = "paradedb.const_score(1::real, $inner)"

    private fun field(name: String): String = when (name) {
        TEXT_FIELD -> "'text'"
        COLLECTION_FIELD -> "'collection_id'"
        else -> throw IllegalArgumentException("the field $name cannot be searched")
    }

    /** Lucene's wildcard syntax (`*` any run, `?` one character, `\` escapes) as a regular expression. */
    private fun wildcardPattern(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            when (val character = text[index]) {
                '*' -> append(".*")
                '?' -> append(".")
                '\\' -> if (index + 1 < text.length) {
                    index++
                    append(quoteChar(text[index]))
                }
                else -> append(quoteChar(character))
            }
            index++
        }
    }

    private fun quoteFree(text: String): String = text.map(::quoteChar).joinToString("")

    /** One character of literal text inside a regular expression: the syntax's own characters are escaped. */
    private fun quoteChar(character: Char): String =
        if (character in REGEX_SYNTAX) "\\$character" else character.toString()

    /** The characters with a meaning in pg_search's (Rust) regular expressions. */
    private const val REGEX_SYNTAX = "\\.+*?()|[]{}^$#&-~"
}
