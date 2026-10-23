package infoscry.glossary

/** Tokens [start] until [endExclusive] of a token sequence are [term] of [group]. */
data class GlossaryHit(val start: Int, val endExclusive: Int, val group: GlossaryGroup, val term: GlossaryTerm)

/**
 * Finds a glossary's terms in a sequence of analyzed tokens: longest match at each position, left to right, never
 * overlapping. A prefix term matches a single token that starts with its key. Only the given tokens are read, so a
 * term's alternatives are never matched again (no chaining).
 */
class GlossaryMatcher(glossary: Glossary) {

    private val byFirstToken: Map<String, List<Pair<GlossaryTerm, GlossaryGroup>>> = glossary.groups
        .flatMap { group -> group.terms.map { it to group } }
        .groupBy { (term, _) -> term.tokens.first() }

    private val prefixes: Map<String, Pair<GlossaryTerm, GlossaryGroup>> = glossary.groups
        .flatMap { group -> group.terms.filter { it.prefix }.map { it.matchKey to (it to group) } }
        .toMap()

    private val longestPrefix = prefixes.keys.maxOfOrNull { it.length } ?: 0

    fun hits(tokens: List<String>): List<GlossaryHit> {
        if (byFirstToken.isEmpty()) return emptyList()
        val hits = mutableListOf<GlossaryHit>()
        var position = 0
        while (position < tokens.size) {
            val hit = longestAt(tokens, position)
            if (hit == null) {
                position++
            } else {
                hits += hit
                position = hit.endExclusive
            }
        }
        return hits
    }

    private fun longestAt(tokens: List<String>, position: Int): GlossaryHit? {
        val exact = byFirstToken[tokens[position]].orEmpty()
            .filter { (term, _) -> term.tokens == tokens.subList(position, minOf(tokens.size, position + term.tokens.size)) }
            .maxByOrNull { (term, _) -> term.tokens.size }
        if (exact != null) return GlossaryHit(position, position + exact.first.tokens.size, exact.second, exact.first)
        val token = tokens[position]
        for (length in minOf(token.length, longestPrefix) downTo GlossaryRules.MIN_PREFIX_LENGTH) {
            val (term, group) = prefixes[token.substring(0, length)] ?: continue
            return GlossaryHit(position, position + 1, group, term)
        }
        return null
    }
}
