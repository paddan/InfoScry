package infoscry.image

import infoscry.domain.ContentUnitId
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation

/**
 * Where the image stage sends an image unit once its artifact is written and its metadata recorded: the import's
 * or rescan's sink, which turns it into a unit with the caption as its text.
 *
 * [deliver] runs inside the parent unit's boundary and may run again for the same image after an interruption,
 * so it must be idempotent: the same [unitId] and [image] leave one unit, and a repeat with a different [ordinal]
 * for an existing [unitId] (an earlier image of the parent was filtered differently) moves that unit rather than failing.
 */
interface ImageUnitTarget {

    /**
     * What a checkpoint is kept under: the text fingerprint for an import, the candidate revision for a rescan.
     * A checkpoint is only valid for the target that committed it.
     */
    val target: String

    /**
     * @param text the unit's text: the image's caption, a blank line, then a bounded excerpt of the page text around it
     *   (either part may be absent), or an empty string when there is neither; an empty unit is not indexed.
     * @param provenanceRoot where [image]'s path resolves: the managed copy for a standalone picture, the
     *   document's artifacts otherwise.
     */
    suspend fun deliver(
        ordinal: Int,
        unitId: ContentUnitId,
        locator: SourceLocation.ImageRegion,
        text: String,
        image: WrittenImage,
        provenanceRoot: SourceImageRoot,
    )

    /**
     * Removes the image units this target holds within [fromOrdinal]..[toOrdinal] whose identity is not in [keep]: what an
     * earlier delivery left that this one did not produce. Runs inside the parent unit's boundary, and is a no-op
     * when nothing is stale, so a repeat converges.
     */
    suspend fun retainImages(fromOrdinal: Int, toOrdinal: Int, keep: Set<ContentUnitId>)
}
