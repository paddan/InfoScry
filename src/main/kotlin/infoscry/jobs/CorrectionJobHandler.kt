package infoscry.jobs

import infoscry.chunk.Chunker
import infoscry.correction.CandidateCorrector
import infoscry.correction.CorrectionJobFailedException
import infoscry.correction.CorrectionRules
import infoscry.document.PublicationStep
import infoscry.document.RevisionPublicationService
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.Job
import infoscry.embedding.DocumentEmbedder
import infoscry.ocr.chunkStagedPage
import infoscry.storage.CorrectionStore
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes every document of a collection follow its correction list.
 *
 * Each pass walks the collection's documents. A document whose published text already equals its reading with the
 * list applied is skipped; any other gets a `CORRECTIONS` revision: a copy of the published one, corrected from its
 * readings, with only the rewritten pages chunked and embedded again, published through the ordinary publication
 * protocol. A list changed during a pass makes the job take one more pass, so the job ends only when the list it
 * last saw is the list every document follows.
 *
 * Nothing waits: a document another publication moved meanwhile is redone from its new revision, a resumed job
 * reuses the candidate it staged, and the comparison makes a second run a no-op.
 */
class CorrectionJobHandler(
    private val revisions: DocumentRevisionStore,
    private val documents: DocumentStore,
    private val corrections: CorrectionStore,
    private val corrector: CandidateCorrector,
    private val publication: RevisionPublicationService,
    private val chunker: Chunker,
    private val documentEmbedder: () -> DocumentEmbedder?,
    /** Test seam: runs before a document's corrected revision is published. */
    private val beforePublish: suspend (DocumentId) -> Unit = {},
    /** Test seam: runs after each document of a pass. */
    private val afterDocument: suspend (DocumentId) -> Unit = {},
    /** Test seam: the publication's own steps. */
    private val observePublication: (PublicationStep) -> Unit = {},
) : JobHandler {

    override suspend fun handle(job: Job, stage: JobStage) {
        val collectionId = requireNotNull(job.collectionId) { "a correction job names its collection" }
        while (true) {
            val fingerprint = fingerprintOf(collectionId)
            val ids = documentIds(collectionId)
            stage.reportProgress(completed = 0, total = ids.size)
            ids.forEachIndexed { index, documentId ->
                correctDocument(collectionId, documentId, stage)
                stage.reportProgress(completed = index + 1, total = ids.size)
                afterDocument(documentId)
            }
            if (fingerprintOf(collectionId) == fingerprint) return
        }
    }

    private suspend fun correctDocument(collectionId: CollectionId, documentId: DocumentId, stage: JobStage) {
        repeat(MAX_ATTEMPTS) {
            val active = revisions.activeRevisionId(documentId) ?: return
            if (documents.isDeletionTarget(documentId)) return
            if (!corrector.wouldChange(collectionId, active)) return
            val embedder = documentEmbedder() ?: throw CorrectionJobFailedException(
                EMBEDDING_UNAVAILABLE,
                "this machine has no embedding model available, so the corrected text cannot be published; " +
                    "the rules are saved and keyword search and source viewing are unaffected",
            )
            val candidate = stage.run(STAGE_CORRECT) {
                val staged = revisions.stageCorrectionCandidate(documentId, active, fingerprintOf(collectionId))
                corrector.correct(collectionId, documentId, staged)
                chunkMissingPages(collectionId, documentId, staged)
                staged
            }
            try {
                embedMissingVectors(candidate, embedder)
                beforePublish(documentId)
                publication.publish(documentId, active, candidate, observePublication)
            } catch (cancelled: CancellationException) {
                revisions.withdrawCandidateIfCandidate(candidate)
                throw cancelled
            } catch (failure: RuntimeException) {
                // Another publication moved the document while this one was staged (the publication refuses a
                // stale base before and while it stages): correct its new revision instead.
                if (revisions.activeRevisionId(documentId) == active) throw failure
                revisions.withdrawCandidateIfCandidate(candidate)
                return@repeat
            }
            if (revisions.activeRevisionId(documentId) == candidate) return
            revisions.withdrawCandidateIfCandidate(candidate)
            throw CorrectionJobFailedException(
                CORRECTIONS_REFUSED,
                "a corrected revision could not be published, so that document still shows its current text",
            )
        }
        throw CorrectionJobFailedException(
            CORRECTIONS_CONFLICT,
            "a document kept changing while its corrections were applied; start the corrections again",
        )
    }

    private fun chunkMissingPages(collectionId: CollectionId, documentId: DocumentId, candidate: String) {
        val chunked = revisions.chunks(candidate).mapTo(mutableSetOf()) { it.unitOrdinal }
        val root = corrector.artifactRoot(collectionId, documentId)
        revisions.pages(candidate).filter { it.ordinal !in chunked }.forEach { page ->
            revisions.recordPageChunks(candidate, page.ordinal, chunker.chunkStagedPage(page, documentId, root))
        }
    }

    /** Embeds the passages with no vector, page by page; the pages the pass did not change keep theirs. */
    private suspend fun embedMissingVectors(candidate: String, embedder: DocumentEmbedder) {
        withContext(Dispatchers.IO) {
            revisions.chunks(candidate).groupBy { it.unitOrdinal }.forEach { (ordinal, chunks) ->
                val ordered = chunks.sortedBy { it.ordinal }
                val missing = ordered.filter { !it.isStaged }
                if (missing.isEmpty()) return@forEach
                val vectors = missing.chunked(EMBED_BATCH).flatMap { batch ->
                    embedder.embedDocuments(batch.map { it.text }).also { embedded ->
                        check(embedded.size == batch.size) { "the embedder returned ${embedded.size} vectors for ${batch.size} passages" }
                    }
                }
                var next = 0
                revisions.recordChunkVectors(candidate, ordinal, ordered.map { chunk -> chunk.embedding ?: vectors[next++] })
            }
        }
    }

    private fun fingerprintOf(collectionId: CollectionId): String = CorrectionRules.fingerprint(corrections.list(collectionId))

    private fun documentIds(collectionId: CollectionId): List<DocumentId> {
        val count = documents.countByCollection(collectionId)
        if (count == 0) return emptyList()
        return documents.listByCollection(collectionId, limit = count).map { it.id }.reversed()
    }

    companion object {
        const val STAGE_CORRECT = "correcting"
        const val EMBEDDING_UNAVAILABLE = "EMBEDDING_UNAVAILABLE"
        const val CORRECTIONS_CONFLICT = "CORRECTIONS_CONFLICT"
        const val CORRECTIONS_REFUSED = "CORRECTIONS_REFUSED"
        private const val MAX_ATTEMPTS = 3
        private const val EMBED_BATCH = 32
    }
}
