package infoscry.image.analysis

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.storage.ImageAnalysisStore
import infoscry.storage.ImageStore

/**
 * The picture categories one image unit's search rows carry.
 *
 * It is the latest completed analysis of the image with the edits people made applied — the same tags the
 * unit's text is built from — so a removed person tag stops matching the *People* filter once its document is
 * republished, and a confirmed one stays. A text unit, or an image never analysed, has no categories.
 *
 * The lookup is by document and unit, because that is what a search row knows about its unit; the image's own
 * SHA-256 is accepted as well, so a caller that already holds the image's provenance does not query for it.
 */
class ImageCategories(
    private val analyses: ImageAnalysisStore,
    private val images: ImageStore,
) {

    /** The categories of the image unit [unitId] whose displayed image is [imageSha256]. */
    fun of(documentId: DocumentId, unitId: ContentUnitId, imageSha256: String): Set<String> {
        val analysis = analyses.latestDone(documentId, unitId, imageSha256) ?: return emptySet()
        val tags = ImageUnitText.tagsWithEdits(analysis.tags, analyses.edits(documentId, unitId))
        return ImageUnitText.categoriesOf(tags).mapTo(linkedSetOf()) { it.name }
    }

    /** The categories of [unitId], resolving the image it shows from its stored metadata. */
    fun ofUnit(documentId: DocumentId, unitId: ContentUnitId): Set<String> {
        val sha256 = images.imageSha256Of(documentId, unitId) ?: return emptySet()
        return of(documentId, unitId, sha256)
    }
}
