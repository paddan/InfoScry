package infoscry.image.analysis

import infoscry.chunk.Chunker
import infoscry.config.AppPaths
import infoscry.document.RevisionPublicationService
import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.EditConflictException
import infoscry.domain.UserRef
import infoscry.embedding.DocumentEmbedder
import infoscry.extract.TextNormalizer
import infoscry.image.ImageIdentity
import infoscry.ocr.chunkStagedPage
import infoscry.storage.Actor
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.ImageAnalysisStore
import infoscry.storage.ImageStore
import infoscry.storage.Instants
import infoscry.storage.RevisionPageDraft
import infoscry.storage.RevisionPageText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** An edit was refused because the picture's text cannot be republished right now. */
class ImageTagEditException(code: String, message: String) : ApiFaultException(ApiStatus.CONFLICT, code, message)

/** One tag edit as the viewer shows it: what was done, by whom, and when. */
@Serializable
data class ImageTagEditView(
    val editId: String,
    val action: TagEditAction,
    val tag: ImageTag,
    val createdBy: UserRef? = null,
    val createdAt: String,
)

/**
 * Everything the image viewer shows about one picture's tags.
 *
 * [tags] are the effective tags — the latest completed analysis with every person's edit applied — so the
 * viewer never has to know which part came from where. [edits] carries the raw edits with their attribution,
 * and [tagsVersion] is what an edit is made from: it moves with every edit of this picture and nowhere else.
 */
@Serializable
data class ImageTagsView(
    val documentId: String,
    val unitId: String,
    val description: String? = null,
    val tags: List<ImageTag> = emptyList(),
    val edits: List<ImageTagEditView> = emptyList(),
    val tagsVersion: Long = 0,
    /** The code of the latest failed analysis, when the last attempt to describe the picture failed. */
    val analysisErrorCode: String? = null,
)

/**
 * Reading and editing the tags of one picture.
 *
 * An edit is a person's statement on top of every analysis: it is stored first, so it applies to the next
 * analysis too, and the document is republished with the image unit's text rebuilt from the analysis, the edits
 * and the caption/page text the analysis was made with. The republication goes through the ordinary revision
 * protocol — a candidate descended from the published reading, its one rewritten unit re-chunked and embedded,
 * published with the search rows that carry the picture's new categories — so history and Restore work exactly
 * as they do for any other reading.
 *
 * The same edit id twice changes nothing: a client that retries a request it never saw the answer to gets the
 * first result, and a picture the edit was already published for is not republished. Two editors who both read
 * version N cannot both write: the second one's version is stale and its edit is refused.
 */
class ImageTagService(
    private val analyses: ImageAnalysisStore,
    private val revisions: DocumentRevisionStore,
    private val documents: DocumentStore,
    private val images: ImageStore,
    private val publication: RevisionPublicationService,
    private val chunker: () -> Chunker,
    private val documentEmbedder: () -> DocumentEmbedder?,
    private val paths: AppPaths,
) {

    /**
     * One picture's tags, from the stored analysis and edits.
     *
     * It does not require the picture to be in the document's *current* reading: a viewer may pin an older
     * revision, and the tags of the image are the same whichever revision is shown.
     */
    fun tags(collectionId: CollectionId, documentId: DocumentId, unitId: ContentUnitId): ImageTagsView {
        val document = documents.get(documentId)?.takeIf { it.collectionId == collectionId }
            ?: throw NoSuchElementException("no document with id ${documentId.value} exists in this collection")
        return viewOf(document, unitId)
    }

    suspend fun edit(
        collectionId: CollectionId,
        documentId: DocumentId,
        unitId: ContentUnitId,
        editId: String,
        action: TagEditAction,
        tag: ImageTag,
        expectedVersion: Long?,
    ): ImageTagsView {
        val document = documents.get(documentId)?.takeIf { it.collectionId == collectionId }
            ?: throw NoSuchElementException("no document with id ${documentId.value} exists in this collection")
        val version = analyses.editsVersion(documentId, unitId)
        val existing = analyses.edits(documentId, unitId).firstOrNull { it.editId == editId }
        if (existing != null) {
            // A repeated edit is the first one's answer even when another person edited since; a caller that
            // asked for a different change under this id, from a stale version, is a conflict, not a replay.
            if ((existing.action != action || existing.tag != tag) && expectedVersion != null && expectedVersion != version) {
                throw EditConflictException(version, null)
            }
        } else {
            pageOf(collectionId, documentId, unitId)
            analyses.addEditIfVersion(
                ImageTagEdit(
                    editId = editId,
                    documentId = documentId,
                    unitId = unitId,
                    action = action,
                    tag = tag,
                    createdBy = Actor.current(),
                    createdAt = Instants.now(),
                ),
                expectedVersion,
            )
        }
        // The picture may have been removed by a rescan after the edit was stored; its tags still read from the
        // stored metadata, and there is no unit to republish.
        val page = runCatching { pageOf(collectionId, documentId, unitId).second }.getOrNull()
        if (page != null) republish(document, page, unitId)
        return viewOf(document, unitId)
    }

    /** The document and its published image unit, refusing a unit that is not an image of this reading. */
    private fun pageOf(
        collectionId: CollectionId,
        documentId: DocumentId,
        unitId: ContentUnitId,
    ): Pair<Document, RevisionPageText> {
        val document = documents.get(documentId)?.takeIf { it.collectionId == collectionId }
            ?: throw NoSuchElementException("no document with id ${documentId.value} exists in this collection")
        val active = revisions.activeRevisionId(documentId)
            ?: throw NoSuchElementException("the document publishes no reading to edit")
        val page = revisions.pages(active).firstOrNull { it.unitId == unitId }
            ?: throw NoSuchElementException("no image with id ${unitId.value} exists in this reading")
        if (page.sourceImage == null && !ImageIdentity.isImageOrdinal(page.ordinal)) {
            throw NoSuchElementException("no image with id ${unitId.value} exists in this reading")
        }
        return document to page
    }

    private fun viewOf(document: Document, unitId: ContentUnitId): ImageTagsView {
        val sha256 = images.imageSha256Of(document.id, unitId)
            ?: throw NoSuchElementException("no image with id ${unitId.value} exists in this document")
        val analysis = analyses.latestDone(document.id, unitId, sha256)
        val edits = analyses.edits(document.id, unitId)
        val latest = analyses.latest(document.id, unitId)
        val authorNames = analyses.editAuthorNames(document.id, unitId)
        return ImageTagsView(
            documentId = document.id.value,
            unitId = unitId.value,
            description = analysis?.description,
            tags = ImageUnitText.tagsWithEdits(analysis?.tags.orEmpty(), edits),
            edits = edits.map { edit ->
                ImageTagEditView(
                    editId = edit.editId,
                    action = edit.action,
                    tag = edit.tag,
                    createdBy = edit.createdBy?.let { UserRef(it.value, authorNames[edit.editId] ?: it.value) },
                    createdAt = edit.createdAt,
                )
            },
            tagsVersion = analyses.editsVersion(document.id, unitId),
            analysisErrorCode = latest?.takeIf { it.outcome == AnalysisOutcome.FAILED }?.errorCode,
        )
    }

    /**
     * Publishes the picture's unit again when its text is not what the analysis and the edits say it should be.
     *
     * The text is rebuilt exactly as the image stage builds it — the caption and page text the analysis was made
     * with, the description, and the effective tag lines — so an edit changes the tags without losing the text
     * around the picture. A picture whose unit already carries that text needs no new revision, which is what
     * makes a repeated edit id a no-op.
     */
    private suspend fun republish(document: Document, page: RevisionPageText, unitId: ContentUnitId) {
        val sha256 = page.sourceImage?.sha256 ?: return
        val analysis = analyses.latestDone(document.id, unitId, sha256) ?: return
        val edits = analyses.edits(document.id, unitId)
        val text = ImageUnitText.of(
            caption = analysis.caption,
            description = analysis.description,
            tags = ImageUnitText.tagsWithEdits(analysis.tags, edits),
            context = analysis.pageContext,
        )
        if (page.extractedText == TextNormalizer.normalize(text).extracted) return
        val active = revisions.activeRevisionId(document.id) ?: return
        val embedder = documentEmbedder() ?: throw ImageTagEditException(
            EMBEDDING_UNAVAILABLE,
            "this machine has no embedding model available, so the edited tags cannot be published; " +
                "the edit is kept and the picture's current text is unchanged",
        )
        val candidate = revisions.stageCorrectionCandidate(
            documentId = document.id,
            baseRevisionId = active,
            fingerprint = "image-tags:$active:${analyses.editsVersion(document.id, unitId)}",
        )
        val normalized = TextNormalizer.normalize(text)
        revisions.appendPage(
            candidate,
            RevisionPageDraft(
                ordinal = page.ordinal,
                unitId = page.unitId,
                locator = page.locator,
                extractedText = normalized.extracted,
                searchText = normalized.search,
                extractionMethod = page.extractionMethod,
                meanConfidence = page.meanConfidence,
                artifactRelativePath = page.artifactRelativePath,
                artifactSha256 = page.artifactSha256,
                sourceImage = page.sourceImage,
                approval = infoscry.storage.PageApproval.APPROVED,
                unitKey = infoscry.jobs.SinkImageTarget.keyOf(page.unitId),
                producerRevisionId = null,
                layoutReference = page.layoutReference,
                structureReference = page.structureReference,
                reading = page.reading,
            ),
        )
        val staged = revisions.pages(candidate).first { it.unitId == page.unitId }
        val root = paths.artifactsDir(document.collectionId, document.id)
        val drafts = chunker().chunkStagedPage(staged, document.id, root)
        revisions.recordPageChunks(candidate, page.ordinal, drafts)
        val vectors = embedder.embedDocuments(drafts.map { it.text })
        check(vectors.size == drafts.size) {
            "the embedder returned ${vectors.size} vectors for ${drafts.size} passages"
        }
        revisions.recordChunkVectors(candidate, page.ordinal, vectors)
        try {
            publication.publish(document.id, active, candidate)
        } catch (cancelled: CancellationException) {
            revisions.withdrawCandidateIfCandidate(candidate)
            throw cancelled
        } catch (_: Exception) {
            revisions.withdrawCandidateIfCandidate(candidate)
            throw EditConflictException(analyses.editsVersion(document.id, unitId), null)
        }
    }

    private companion object {
        const val EMBEDDING_UNAVAILABLE: String = "EMBEDDING_UNAVAILABLE"
    }
}
