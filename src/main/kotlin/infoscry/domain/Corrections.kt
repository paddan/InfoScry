package infoscry.domain

import infoscry.ocr.StructureReference
import infoscry.pdf.LayoutReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A page's text as it was read, before the collection's corrections rewrote it, with the reading's own structure
 * and layout references (the page's current references describe the corrected text, not this one).
 *
 * It is kept only for a page the corrections changed, so its presence says "this page was corrected". The two
 * texts travel together, like a page's own text and search text.
 */
@Serializable
data class ReadingText(
    val extractedText: String,
    val searchText: String,
    val structure: StructureReference? = null,
    val layout: LayoutReference? = null,
) {
    /** The two references as the `reading_references` column stores them, or `null` when there are none. */
    fun encodedReferences(): String? =
        if (structure == null && layout == null) null else JSON.encodeToString(References.serializer(), References(structure, layout))

    @Serializable
    private data class References(val structure: StructureReference? = null, val layout: LayoutReference? = null)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The reading the stored columns name, or `null` when the page has none. */
        fun of(extractedText: String?, searchText: String?, references: String? = null): ReadingText? {
            check((extractedText == null) == (searchText == null)) { "a page's reading is stored whole or not at all" }
            if (extractedText == null || searchText == null) return null
            val decoded = references?.let { JSON.decodeFromString(References.serializer(), it) }
            return ReadingText(extractedText, searchText, decoded?.structure, decoded?.layout)
        }
    }
}

/** What one correction rule changed in one revision: its words, and how many places it rewrote. */
@Serializable
data class AppliedCorrection(val find: String, val replace: String, val matches: Int)
