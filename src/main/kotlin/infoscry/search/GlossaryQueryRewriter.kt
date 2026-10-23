package infoscry.search

import infoscry.glossary.Glossary
import infoscry.glossary.GlossaryGroup
import infoscry.glossary.GlossaryHit
import infoscry.glossary.GlossaryMatcher
import infoscry.glossary.GlossaryTerm
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.QueryVisitor
import org.apache.lucene.search.TermQuery

/** A rewritten query, the groups whose alternatives it gained (in query order), and whether some were left out. */
data class ExpansionResult(val query: Query, val groups: List<GlossaryGroup>, val truncated: Boolean)

/**
 * Adds a glossary's alternatives to a parsed keyword query.
 *
 * Runs of adjacent plain term clauses on the text field with the same occurrence are read as one token sequence, and
 * each phrase on the text field as another; a glossary hit in one gains the group's other terms. An optional (SHOULD)
 * hit keeps its clauses and gains the alternatives beside them; a required (MUST) hit becomes one required choice
 * between itself and the alternatives. A quoted phrase gains one variant per combination of alternatives. Every other
 * clause kind (prohibited, boosted, fielded, wildcard, fuzzy, range) is left as it is.
 *
 * At most [maxAddedClauses] clauses are added, counted as Lucene counts them (a phrase counts its terms); a hit that
 * does not fit is skipped and the result says so.
 */
class GlossaryQueryRewriter(
    private val maxAddedClauses: Int = DEFAULT_MAX_ADDED_CLAUSES,
    private val maxPhraseVariants: Int = DEFAULT_MAX_PHRASE_VARIANTS,
) {

    fun rewrite(parsed: Query, glossary: Glossary): ExpansionResult {
        if (glossary.groups.isEmpty()) return ExpansionResult(parsed, emptyList(), false)
        val pass = Pass(GlossaryMatcher(glossary))
        val rewritten = when (parsed) {
            is BooleanQuery -> pass.rewriteBoolean(parsed)
            else -> pass.rewriteClauses(listOf(BooleanClause(parsed, Occur.SHOULD)))
                ?.let { clauses -> BooleanQuery.Builder().apply { clauses.forEach(::add) }.build() }
        }
        return ExpansionResult(rewritten ?: parsed, pass.groups.toList(), pass.truncated)
    }

    private inner class Pass(private val matcher: GlossaryMatcher) {
        val groups = LinkedHashSet<GlossaryGroup>()
        var truncated = false
        private var added = 0

        /** [query] rewritten, or `null` when nothing in it changed. */
        fun rewriteBoolean(query: BooleanQuery): BooleanQuery? {
            val clauses = rewriteClauses(query.clauses()) ?: return null
            return BooleanQuery.Builder().apply {
                setMinimumNumberShouldMatch(query.minimumNumberShouldMatch)
                clauses.forEach(::add)
            }.build()
        }

        fun rewriteClauses(clauses: List<BooleanClause>): List<BooleanClause>? {
            val out = mutableListOf<BooleanClause>()
            var changed = false
            var index = 0
            while (index < clauses.size) {
                val clause = clauses[index]
                val query = clause.query
                when {
                    query is TermQuery && query.term.field() == LuceneSchema.FIELD_TEXT && clause.occur.expands() -> {
                        var end = index
                        while (end < clauses.size && clauses[end].occur == clause.occur &&
                            (clauses[end].query as? TermQuery)?.term?.field() == LuceneSchema.FIELD_TEXT
                        ) {
                            end++
                        }
                        val run = clauses.subList(index, end)
                        val expanded = expandRun(run)
                        if (expanded != null) changed = true
                        out += expanded ?: run
                        index = end
                    }
                    query is PhraseQuery && clause.occur.expands() -> {
                        val alternatives = phraseVariants(query)
                        if (alternatives == null) {
                            out += clause
                        } else {
                            changed = true
                            out += choice(clause.occur, query, alternatives)
                        }
                        index++
                    }
                    query is BooleanQuery -> {
                        val nested = rewriteBoolean(query)
                        if (nested != null) changed = true
                        out += if (nested != null) BooleanClause(nested, clause.occur) else clause
                        index++
                    }
                    else -> {
                        out += clause
                        index++
                    }
                }
            }
            return if (changed) out else null
        }

        /** A run of term clauses with one occurrence, rewritten, or `null` when it has no hit that fits. */
        private fun expandRun(run: List<BooleanClause>): List<BooleanClause>? {
            val tokens = run.map { (it.query as TermQuery).term.text() }
            val occur = run.first().occur
            val out = mutableListOf<BooleanClause>()
            var changed = false
            var cursor = 0
            for (hit in matcher.hits(tokens)) {
                val alternatives = alternativesFor(hit, tokens) ?: continue
                out += run.subList(cursor, hit.start)
                val span = run.subList(hit.start, hit.endExclusive)
                if (occur == Occur.SHOULD) {
                    out += span
                    alternatives.forEach { out += BooleanClause(it, Occur.SHOULD) }
                } else {
                    val original = span.singleOrNull()?.query
                        ?: BooleanQuery.Builder().apply { span.forEach { add(it.query, Occur.MUST) } }.build()
                    out += choice(occur, original, alternatives)
                }
                cursor = hit.endExclusive
                changed = true
            }
            if (!changed) return null
            out += run.subList(cursor, run.size)
            return out
        }

        /** The queries [hit] gains outside a phrase, or `null` when they do not fit. */
        private fun alternativesFor(hit: GlossaryHit, tokens: List<String>): List<Query>? {
            val span = tokens.subList(hit.start, hit.endExclusive).joinToString(" ")
            val alternatives = hit.group.terms
                .filter { term -> term.prefix || term.matchKey != span }
                .map(::queryFor)
            if (alternatives.isEmpty()) return null
            return admit(hit.group, alternatives)
        }

        /** Variants of [phrase] with its hits replaced by alternatives, or `null` when there are none that fit. */
        private fun phraseVariants(phrase: PhraseQuery): List<Query>? {
            val field = phrase.field ?: return null
            if (field != LuceneSchema.FIELD_TEXT || phrase.positions.withIndex().any { (i, p) -> p != i }) return null
            val tokens = phrase.terms.map { it.text() }
            val hits = matcher.hits(tokens)
            if (hits.isEmpty()) return null
            // Each hit can read as itself or as any of its group's other terms; the first combination is the phrase.
            val options = hits.map { hit ->
                val span = tokens.subList(hit.start, hit.endExclusive)
                listOf(span) + hit.group.terms.map { it.tokens }.filter { it != span }
            }
            val variants = mutableListOf<List<String>>()
            fun combine(level: Int, chosen: List<List<String>>) {
                if (variants.size > maxPhraseVariants) return
                if (level == hits.size) {
                    if (chosen.indices.any { chosen[it] !== options[it][0] }) variants += splice(tokens, hits, chosen)
                    return
                }
                options[level].forEach { combine(level + 1, chosen + listOf(it)) }
            }
            combine(0, emptyList())
            if (variants.size > maxPhraseVariants) truncated = true
            val queries = variants.take(maxPhraseVariants).map { phraseOf(it, phrase.slop) }
            if (queries.isEmpty()) return null
            val admitted = mutableListOf<Query>()
            for (query in queries) {
                val cost = clauseCount(query)
                if (added + cost > maxAddedClauses) {
                    truncated = true
                    break
                }
                added += cost
                admitted += query
            }
            if (admitted.isEmpty()) return null
            hits.forEach { groups += it.group }
            return admitted
        }

        /** [alternatives] when all of them fit the clause bound, counting them in; otherwise `null`. */
        private fun admit(group: GlossaryGroup, alternatives: List<Query>): List<Query>? {
            val cost = alternatives.sumOf(::clauseCount)
            if (added + cost > maxAddedClauses) {
                truncated = true
                return null
            }
            added += cost
            groups += group
            return alternatives
        }
    }

    private fun Occur.expands() = this == Occur.SHOULD || this == Occur.MUST

    /** An optional match is the original and its alternatives side by side; a required one becomes one choice. */
    private fun choice(occur: Occur, original: Query, alternatives: List<Query>): List<BooleanClause> {
        val all = listOf(original) + alternatives
        return if (occur == Occur.SHOULD) {
            all.map { BooleanClause(it, Occur.SHOULD) }
        } else {
            listOf(BooleanClause(BooleanQuery.Builder().apply { all.forEach { add(it, Occur.SHOULD) } }.build(), occur))
        }
    }

    private fun queryFor(term: GlossaryTerm): Query = when {
        term.prefix -> PrefixQuery(Term(LuceneSchema.FIELD_TEXT, term.matchKey))
        term.tokens.size == 1 -> TermQuery(Term(LuceneSchema.FIELD_TEXT, term.matchKey))
        else -> phraseOf(term.tokens, 0)
    }

    private fun phraseOf(tokens: List<String>, slop: Int): Query =
        PhraseQuery.Builder().apply {
            setSlop(slop)
            tokens.forEach { add(Term(LuceneSchema.FIELD_TEXT, it)) }
        }.build()

    private fun splice(tokens: List<String>, hits: List<GlossaryHit>, chosen: List<List<String>>): List<String> = buildList {
        var cursor = 0
        hits.forEachIndexed { index, hit ->
            addAll(tokens.subList(cursor, hit.start))
            addAll(chosen[index])
            cursor = hit.endExclusive
        }
        addAll(tokens.subList(cursor, tokens.size))
    }

    companion object {
        const val DEFAULT_MAX_ADDED_CLAUSES = 256
        const val DEFAULT_MAX_PHRASE_VARIANTS = 16

        /** The clauses [query] counts against Lucene's ceiling: each term of a term or phrase query, one per other leaf. */
        fun clauseCount(query: Query): Int {
            var count = 0
            query.visit(object : QueryVisitor() {
                override fun consumeTerms(query: Query, vararg terms: Term) {
                    count += terms.size
                }

                override fun visitLeaf(query: Query) {
                    count++
                }

                override fun getSubVisitor(occur: Occur, parent: Query): QueryVisitor = this
            })
            return count
        }
    }
}
