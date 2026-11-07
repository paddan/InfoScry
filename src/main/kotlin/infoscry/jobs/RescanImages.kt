package infoscry.jobs

import infoscry.domain.Document
import infoscry.extract.UnitBoundary
import infoscry.image.IMAGE_EXTRACTOR_VERSION
import infoscry.image.ImageExtractionMode
import infoscry.image.ImageIdentity
import infoscry.image.ImageStage
import infoscry.image.ParentUnitContext
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageStructureStore
import infoscry.ocr.RescanSelection
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.ImageCheckpoint
import infoscry.storage.ImageCheckpointOutcome
import infoscry.storage.ImageStore
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageDraft
import infoscry.storage.RevisionPageText
import java.nio.file.Path
import kotlinx.coroutines.CancellationException

/**
 * The image step of a rescan: runs after the candidate's text is complete and before it is chunked.
 *
 * Which parents are selected is the rescan's page scope (`selection.selects(ordinal, true)`): every page for an
 * automatic scope, the chosen pages for an explicit one, whichever reading method is used. A selected parent is
 * extracted fresh. Every other parent keeps what the baseline revision holds for it: its image units are appended
 * unchanged (keeping their producer), and a checkpoint is recorded for it under this candidate so that the stage,
 * which is given every parent, judges repetition across the whole document and counts the carried images against
 * the per-document cap exactly as a full run would, without looking at the parent again.
 *
 * With the mode off nothing is appended: the candidate starts empty, so the revision it publishes has no image
 * units (earlier revisions keep theirs).
 *
 * Every write is an upsert by unit or ordinal, so the step is safe to run again after an interruption at any
 * point and converges on the same pages and checkpoints.
 */
internal class RescanImages(
    private val revisions: DocumentRevisionStore,
    private val imageStore: ImageStore,
    private val images: ImageStage,
) {

    /**
     * @return the distinct warning codes of the step, which make the rescan complete with a warning. A failure of
     *   the stage itself is [DocumentIngest.IMAGE_EXTRACTION_FAILED] and never fails the text it was run beside.
     */
    suspend fun run(
        document: Document,
        managedPath: Path,
        artifactRoot: Path,
        candidate: String,
        baselineRevisionId: String?,
        selection: RescanSelection,
        snapshot: OcrSettingsSnapshot,
        stage: JobStage,
    ): List<String> {
        val mode = snapshot.imageExtraction
        if (mode == ImageExtractionMode.OFF) return emptyList()
        val workDirectory = artifactRoot.resolve(ImageParents.PAGES_DIRECTORY)
        try {
            val parents = stage.run(STAGE_RECORD) {
                ImageParents.clearDirectory(workDirectory)
                carryForward(document, managedPath, artifactRoot, workDirectory, candidate, baselineRevisionId, selection, snapshot, mode)
            }
            return images.run(
                document = document,
                managedPath = managedPath,
                artifactRoot = artifactRoot,
                parents = parents,
                mode = mode,
                target = CandidateImageTarget(revisions, candidate, document),
                boundary = object : UnitBoundary {
                    override suspend fun <T> unit(block: suspend () -> T): T = stage.run(STAGE_IMAGES) { block() }
                },
                settings = snapshot,
            ).warnings
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notActive: CollectionNotActiveException) {
            throw notActive
        } catch (deleted: DocumentBeingDeletedException) {
            throw deleted
        } catch (failure: Exception) {
            LOGGER.atWarn()
                .addKeyValue("document_id", document.id.value)
                .setCause(failure)
                .log("the images of a rescanned document could not be extracted; its text is kept")
            return listOf(DocumentIngest.IMAGE_EXTRACTION_FAILED)
        } finally {
            runCatching { ImageParents.clearDirectory(workDirectory) }
        }
    }

    /**
     * Appends the baseline's image units of every unselected parent to the candidate and records their checkpoints,
     * and answers the contexts of all the candidate's text units for the stage.
     */
    private fun carryForward(
        document: Document,
        managedPath: Path,
        artifactRoot: Path,
        workDirectory: Path,
        candidate: String,
        baselineRevisionId: String?,
        selection: RescanSelection,
        snapshot: OcrSettingsSnapshot,
        mode: ImageExtractionMode,
    ): List<ParentUnitContext> {
        val staged = revisions.pages(candidate)
        val textPages = staged.filterNot { ImageIdentity.isImageOrdinal(it.ordinal) }
        val stagedImages = staged.filter { ImageIdentity.isImageOrdinal(it.ordinal) }.associateBy { it.ordinal }
        val selected = textPages.filter { selection.selects(it.ordinal, true) }.mapTo(HashSet()) { it.unitId }
        val byOrdinal = textPages.associateBy { it.ordinal }

        val carried = baselineRevisionId?.let { revisions.pages(it) }.orEmpty()
            .filter { ImageIdentity.isImageOrdinal(it.ordinal) && it.approval == PageApproval.APPROVED }
            .filter { image -> byOrdinal[parentOrdinalOf(image.ordinal)]?.unitId !in selected }
        carried.forEach { image ->
            // A resumed attempt finds them staged already; writing one again would only drop its passages.
            val present = stagedImages[image.ordinal]
            if (present?.unitId == image.unitId && present.sourceImage == image.sourceImage) return@forEach
            revisions.appendPage(candidate, draftOf(image))
        }

        val carriedPerParent = carried.groupingBy { byOrdinal[parentOrdinalOf(it.ordinal)]?.unitId }.eachCount()
        textPages.filter { it.unitId !in selected }.forEach { page ->
            imageStore.commitCheckpoint(
                document.id,
                candidate,
                mode,
                IMAGE_EXTRACTOR_VERSION,
                ImageCheckpoint(page.unitId, ImageCheckpointOutcome.DONE, carriedPerParent[page.unitId] ?: 0, null),
            )
        }

        return textPages.map { page ->
            ImageParents.contextOf(
                document, document.mediaType, managedPath, mode, artifactRoot, workDirectory, snapshot,
                page.unitId, page.ordinal, page.locator, page.extractedText,
            ) {
                page.structureReference?.let { PageStructureStore.read(artifactRoot, it, page.extractedText) }
            }
        }
    }

    /** The ordinal of the text unit an image unit at [imageOrdinal] was found under. */
    private fun parentOrdinalOf(imageOrdinal: Int): Int =
        (imageOrdinal - ImageIdentity.ORDINAL_BASE) / ImageIdentity.MAX_PER_PARENT

    /** An image unit as the baseline holds it, ready to be staged again: same unit, same provenance, same producer. */
    private fun draftOf(page: RevisionPageText) = RevisionPageDraft(
        ordinal = page.ordinal,
        unitId = page.unitId,
        locator = page.locator,
        extractedText = page.extractedText,
        searchText = page.searchText,
        extractionMethod = page.extractionMethod,
        meanConfidence = page.meanConfidence,
        artifactRelativePath = page.artifactRelativePath,
        artifactSha256 = page.artifactSha256,
        sourceImage = page.sourceImage,
        approval = PageApproval.APPROVED,
        unitKey = SinkImageTarget.keyOf(page.unitId),
        producerRevisionId = page.producerRevisionId,
        layoutReference = page.layoutReference,
        structureReference = page.structureReference,
        reading = page.reading,
    )

    private companion object {
        const val STAGE_IMAGES = "images"
        const val STAGE_RECORD = "record"
    }
}

private val LOGGER = org.slf4j.LoggerFactory.getLogger("infoscry.rescan.images")
