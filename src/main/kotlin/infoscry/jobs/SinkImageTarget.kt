package infoscry.jobs

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSink
import infoscry.extract.TextNormalizer
import infoscry.image.ImageUnitTarget
import infoscry.image.WrittenImage

/**
 * Delivers the image stage's units into an import's [ExtractionSink], so an image unit is committed exactly like a
 * text unit: through the plain sink into the document's content, or through the candidate sink into the candidate
 * revision.
 *
 * Delivery is idempotent because the unit carries its own identity and a stable key (`image:<unitId>`): the sinks
 * upsert by that key, so a repeat after an interruption leaves one unit, and a repeat with a different ordinal moves
 * the unit instead of duplicating it. An image unit is never pending review, whichever sink it goes to.
 */
class SinkImageTarget(
    private val sink: ExtractionSink,
    private val documentId: DocumentId,
    private val fingerprint: ExtractionFingerprint,
) : ImageUnitTarget {

    /** Image checkpoints of an import belong to the text fingerprint they were extracted beside. */
    override val target: String = fingerprint.value

    override suspend fun deliver(
        ordinal: Int,
        unitId: ContentUnitId,
        locator: SourceLocation.ImageRegion,
        text: String,
        image: WrittenImage,
        provenanceRoot: SourceImageRoot,
    ) {
        val normalized = TextNormalizer.normalize(text)
        val draft = ContentUnitDraft(
            locator = locator,
            extractedText = normalized.extracted,
            searchText = normalized.search,
            method = ExtractionMethod.DIRECT_TEXT,
            sourceImage = SourceImageProvenance(
                root = provenanceRoot,
                relativePath = image.relativePath,
                sha256 = image.sha256,
                width = image.widthPx,
                height = image.heightPx,
                renderVersion = IMAGE_RENDER_VERSION,
            ),
            unitId = unitId,
        )
        sink.deliver(
            documentId,
            fingerprint,
            ExtractionEvent.UnitReady(key = keyOf(unitId), ordinal = ordinal, unit = draft),
            infoscry.storage.PageApproval.APPROVED,
        )
    }

    override suspend fun retainImages(fromOrdinal: Int, toOrdinal: Int, keep: Set<ContentUnitId>) {
        sink.retainImageUnits(documentId, fromOrdinal, toOrdinal, keep)
    }

    companion object {
        /** The version of the procedure that produced an image's pixels: the stored file itself. */
        const val IMAGE_RENDER_VERSION: Int = 1

        /** The checkpoint key an image unit is committed under. */
        fun keyOf(unitId: ContentUnitId): String = "image:${unitId.value}"
    }
}
