package infoscry.search

/**
 * The revisions a search must not read.
 *
 * A reversal of the obvious form on purpose. The set of *published* revisions is the whole archive —
 * one per document, and a document count in the tens of thousands — while the number of revisions that
 * must be hidden at any instant is bounded by the publications in flight. A search therefore carries
 * the hidden set, and the steady state (nothing in flight) costs no clause at all.
 *
 * Rows that carry no revision tag at all are always visible: they were written before revisions
 * existed, and the only way a document can hold two readings is a publication, which replaces every
 * row of the document in the same writer operation. A document with one untagged reading has nothing to
 * mix, so keeping those rows visible is what preserves an archive that has not been rebuilt yet.
 */
data class RevisionScope(val hiddenRevisionIds: Set<String>) {

    init {
        require(hiddenRevisionIds.none(String::isBlank)) { "a hidden revision id must not be blank" }
    }

    /** Whether this scope restricts anything, which is the caller's test for adding a clause at all. */
    val unrestricted: Boolean get() = hiddenRevisionIds.isEmpty()

    /** This scope with [revisionId] also hidden; the same scope when it is null or already hidden. */
    fun hiding(revisionId: String?): RevisionScope =
        if (revisionId == null) this else RevisionScope(hiddenRevisionIds + revisionId)

    /** This scope with [revisionId] visible again. */
    fun revealing(revisionId: String?): RevisionScope =
        if (revisionId == null) this else RevisionScope(hiddenRevisionIds - revisionId)

    /**
     * Whether a reader under this scope may see [revisionId]'s rows.
     *
     * A publication asks this of the revision it made authoritative: a scope that already reveals it is a
     * scope the switch happened under, while a hidden one is a publication whose new rows no reader is
     * allowed to see yet.
     */
    fun reveals(revisionId: String): Boolean = revisionId !in hiddenRevisionIds

    companion object {
        /** Everything a row can be is readable: the state of a data directory with no publication in flight. */
        val EVERYTHING: RevisionScope = RevisionScope(emptySet())
    }
}
