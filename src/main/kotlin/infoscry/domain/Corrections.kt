package infoscry.domain

import kotlinx.serialization.Serializable

/**
 * A page's text as it was read, before the collection's corrections rewrote it.
 *
 * It is kept only for a page the corrections changed, so its presence says "this page was corrected". The two
 * forms travel together, like a page's own text and search text.
 */
@Serializable
data class ReadingText(val extractedText: String, val searchText: String) {
    companion object {
        /** The reading the two stored columns name, or `null` when the page has none. */
        fun of(extractedText: String?, searchText: String?): ReadingText? {
            check((extractedText == null) == (searchText == null)) { "a page's reading is stored whole or not at all" }
            return if (extractedText == null || searchText == null) null else ReadingText(extractedText, searchText)
        }
    }
}

/** What one correction rule changed in one revision: its words, and how many places it rewrote. */
@Serializable
data class AppliedCorrection(val find: String, val replace: String, val matches: Int)
