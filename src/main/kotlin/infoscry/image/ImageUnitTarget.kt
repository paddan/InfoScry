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
     * @param caption the image's caption, or an empty string when it has none; an empty unit is not indexed.
     * @param provenanceRoot where [image]'s path resolves: the managed copy for a standalone picture, the
     *   document's artifacts otherwise.
     */
    suspend fun deliver(
        ordinal: Int,
        unitId: ContentUnitId,
        locator: SourceLocation.ImageRegion,
        caption: String,
        image: WrittenImage,
        provenanceRoot: SourceImageRoot,
    )
}
