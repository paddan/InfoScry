package infoscry.glossary

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId

/** A term as a person writes it, before it is analyzed. */
data class GlossaryTermInput(val text: String, val prefix: Boolean = false)

/**
 * One name of a group: [text] as typed (NFC), and [matchKey], its tokens under the index's analyzer joined by one
 * space. A [prefix] term also matches words that start with its key.
 */
data class GlossaryTerm(val text: String, val matchKey: String, val prefix: Boolean) {
    val tokens: List<String> get() = matchKey.split(' ')
}

/** Names with the same meaning, in the order they were typed; the first is the group's title only. */
data class GlossaryGroup(
    val id: String,
    val collectionId: CollectionId,
    val terms: List<GlossaryTerm>,
    val createdAt: String,
    val updatedAt: String,
)

/** A collection's glossary, groups oldest first. */
data class Glossary(val collectionId: CollectionId, val groups: List<GlossaryGroup>) {
    companion object {
        fun empty(collectionId: CollectionId) = Glossary(collectionId, emptyList())
    }
}

/**
 * A group that cannot be stored as written. [term] is the index of the term concerned in the submitted list, or
 * `null` when the problem is the group as a whole. The message never repeats the terms.
 */
class InvalidGlossaryException(message: String, val term: Int? = null) :
    ApiFaultException(ApiStatus.BAD_REQUEST, "INVALID_GLOSSARY_GROUP", message) {
    override fun details(): Map<String, String>? = term?.let { mapOf("term" to it.toString()) }
}

/** Term [term] of the submitted list already belongs to group [groupId] of the collection. */
class GlossaryTermTakenException(val term: Int, val groupId: String) :
    ApiFaultException(ApiStatus.CONFLICT, "GLOSSARY_TERM_TAKEN", "another glossary group already has that term") {
    override fun details(): Map<String, String> = mapOf("term" to term.toString(), "groupId" to groupId)
}

/** A group id that another collection's group already uses. */
class GlossaryIdTakenException : ApiFaultException(
    ApiStatus.CONFLICT,
    "GLOSSARY_ID_TAKEN",
    "another collection already has a glossary group with that id",
)
