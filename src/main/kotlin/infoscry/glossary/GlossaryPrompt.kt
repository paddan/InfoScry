package infoscry.glossary

import infoscry.domain.CollectionId
import infoscry.logging.LogFields
import infoscry.search.TextAnalysis
import org.slf4j.LoggerFactory

/**
 * The glossary block of an Ask or Investigate request: which of the collection's groups a request touches, and
 * how they are written into the system message.
 *
 * The glossary is the user's own configuration, trusted like the collection prompt, but its terms are written only
 * as data: line breaks and control characters become spaces, so no term can become a line of its own.
 */
class GlossaryPrompt(glossary: Glossary, private val investigate: Boolean) {

    private val matcher = GlossaryMatcher(glossary)

    /** The groups [texts] name, in order of first occurrence across them, each once. */
    fun groupsIn(texts: List<String>): List<GlossaryGroup> {
        val groups = LinkedHashSet<GlossaryGroup>()
        texts.forEach { text -> matcher.hits(TextAnalysis.tokens(text)).forEach { groups += it.group } }
        return groups.toList()
    }

    /** The block for [groups]; empty when there are none. */
    fun render(groups: List<GlossaryGroup>): String {
        if (groups.isEmpty()) return ""
        return buildList {
            add(HEADER)
            if (investigate) add(INVESTIGATE_NOTE)
            groups.forEach { group -> add("- " + group.terms.joinToString(" = ") { asData(it.text) }) }
        }.joinToString("\n")
    }

    /** [systemPrompt] followed by the block for [groups]; [systemPrompt] itself when there are none. */
    fun withBlock(systemPrompt: String, groups: List<GlossaryGroup>): String =
        if (groups.isEmpty()) systemPrompt else systemPrompt + "\n\n" + render(groups)

    /**
     * [fixed] followed by as many of [candidates], in order, as keep the block within [MAX_GROUPS] and [MAX_TOKENS]
     * (as [cost] measures a block of groups) and keep the whole request fitting. A candidate that does not fit is
     * left out and later, smaller ones are still tried.
     */
    fun choose(
        fixed: List<GlossaryGroup>,
        candidates: List<GlossaryGroup>,
        cost: (List<GlossaryGroup>) -> Int,
        fits: (List<GlossaryGroup>) -> Boolean,
    ): List<GlossaryGroup> {
        val chosen = fixed.toMutableList()
        for (candidate in candidates) {
            if (chosen.size >= MAX_GROUPS) break
            if (candidate in chosen) continue
            val trial = chosen + candidate
            if (cost(trial) <= MAX_TOKENS && fits(trial)) chosen += candidate
        }
        return chosen
    }

    private fun asData(text: String): String = buildString {
        text.forEach { character -> append(if (Character.getType(character) == Character.CONTROL.toInt() || character == ' ' || character == ' ') ' ' else character) }
    }.replace(SPACES, " ").trim()

    companion object {
        /** The most groups one request is told about. */
        const val MAX_GROUPS = 40

        /** The most the block may add to a request, in the request budget's own units. */
        const val MAX_TOKENS = 1_500

        const val HEADER =
            "Glossary for this collection. Each line lists names with the same meaning; treat them as the same thing when reading the sources."
        const val INVESTIGATE_NOTE =
            "Keyword search already includes these alternatives; you do not need to search each name separately."

        private val SPACES = Regex(" {2,}")
    }
}

/**
 * The glossary block's source for [collectionId], or `null` when the collection has no groups or its glossary cannot
 * be read. A failure is logged by type only, never by its terms, and the request goes out without the block.
 */
fun glossaryPromptFor(lookup: (CollectionId) -> Glossary, collectionId: CollectionId, investigate: Boolean): GlossaryPrompt? =
    try {
        lookup(collectionId).takeIf { it.groups.isNotEmpty() }?.let { GlossaryPrompt(it, investigate) }
    } catch (failure: RuntimeException) {
        LoggerFactory.getLogger("infoscry.glossary").atWarn()
            .addKeyValue(LogFields.COMPONENT, "glossary")
            .addKeyValue("error_type", failure.javaClass.simpleName)
            .log("the collection glossary could not be read; the request is sent without it")
        null
    }
