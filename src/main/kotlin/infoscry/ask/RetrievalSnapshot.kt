package infoscry.ask

import infoscry.search.SearchMode

/**
 * The retrieval settings every Ask and Investigate conversation locks at creation. Shared so the
 * two services persist byte-identical snapshot values; the JSON never contains document text.
 */
object RetrievalSnapshot {
    const val TOP_HITS = 30
    const val MAX_EVIDENCE = ContextPacker.DEFAULT_MAX_EVIDENCE

    fun value(): String =
        "{\"mode\":\"${SearchMode.HYBRID.name}\",\"topHits\":$TOP_HITS,\"maxEvidence\":$MAX_EVIDENCE}"
}