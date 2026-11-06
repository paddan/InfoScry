package infoscry.search

import infoscry.chunk.Chunker
import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.EmbeddingException
import infoscry.embedding.ModelManager
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.MutationCoordinator
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory

/**
 * What one rebuild was asked to rebuild.
 *
 * [collectionId] is the single collection a caller narrowed the rebuild to; `null` means every collection.
 */
data class ReindexRequest(val collectionId: CollectionId? = null)

/** How far a rebuild has come, reported after the work it describes is durable. */
data class ReindexProgress(val stage: String, val completed: Int, val total: Int)

/** What one rebuild produced, in the words `infoscry reindex --json` reports. */
data class ReindexResult(
    val documents: Int,
    val chunks: Int,
    val documentsRebuilt: Int,
    val shedRows: Int = 0,
)

/** One instant a rebuild can be interrupted at, in the order they happen. */
enum class ReindexStep {

    /** Every document in scope has rows that agree with its text; a full rebuild has not recorded its model yet. */
    DOCUMENTS_REBUILT,

    /** The rebuild is complete. */
    DONE,
}

/**
 * Rebuilds the searchable rows from the persisted text, under exclusive maintenance.
 *
 * The rows live in the same database as the text, and each document is rebuilt in a transaction of its own,
 * so a rebuild never has a half-swapped state: an interrupted rebuild leaves every document either with its
 * old rows or its new ones, and running it again continues where it stopped. A document whose rows already
 * agree with its chunks, under the model this build embeds with, keeps them and its vectors. A full rebuild
 * records the model as the last step, which is what makes semantic search compare against the new vectors.
 */
class ReindexService(
    private val mutations: MutationCoordinator,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val content: ContentStore,
    /** Where each document's published revision is read from, so its rows name the revision they belong to. */
    private val revisions: DocumentRevisionStore,
    private val chunker: Chunker,
    private val paths: AppPaths,
    private val documentEmbedder: () -> DocumentEmbedder?,
    private val index: () -> SearchIndex,
    private val maxChunksPerDocument: Int = MAX_CHUNKS_PER_DOCUMENT,
) {

    suspend fun reindex(
        request: ReindexRequest,
        onProgress: suspend (ReindexProgress) -> Unit = {},
        observe: (ReindexStep) -> Unit = {},
    ): ReindexResult = mutations.withExclusiveMaintenance("reindex the search index") {
        val index = index()
        val vectorsAreCurrent = index.schemaStatus is SchemaStatus.Ready
        if (request.collectionId != null && !vectorsAreCurrent) {
            throw IllegalArgumentException(
                "a rebuild of one collection cannot reuse vectors an unchanged model produced; " +
                    "the index reports: ${index.schemaStatus.reasons().joinToString("; ")}. " +
                    "Run `infoscry reindex` without --collection to rebuild every collection.",
            )
        }
        val requested = request.collectionId?.let { collectionId ->
            collections.get(collectionId)
                ?: throw IllegalArgumentException("no collection with id ${collectionId.value} exists")
        }
        if (requested != null && requested.lifecycle != infoscry.domain.CollectionLifecycle.ACTIVE) {
            throw IllegalArgumentException("collection ${requested.name} is not active, so its index cannot be rebuilt")
        }
        val scope = if (requested == null) collections.list() else listOf(requested)

        // Rows of a reading a document no longer publishes are not searchable text, wherever they came from.
        val shed = index.deleteRowsOfUnpublishedRevisions()
        if (shed > 0) LOGGER.atInfo().addKeyValue(SHED_ROWS_FIELD, shed).log("removed rows of revisions no document publishes")

        val total = scope.sumOf { collection -> documents.countByCollection(collection.id) }
        var completed = 0
        var chunks = 0
        var rebuilt = 0
        for (collection in scope) {
            var offset = 0
            while (true) {
                val page = documents.listByCollection(collection.id, DOCUMENT_PAGE, offset)
                if (page.isEmpty()) break
                for (document in page) {
                    coroutineContext.ensureActive()
                    if (!(vectorsAreCurrent && rowsAgree(index, document.id))) {
                        chunks += rebuildDocument(index, collection.id, document.id)
                        rebuilt++
                    }
                    completed++
                    onProgress(ReindexProgress(STAGE_REBUILD, completed, total))
                }
                offset += page.size
            }
        }
        observe(ReindexStep.DOCUMENTS_REBUILT)
        // Only a rebuild of every collection may say every vector is this model's.
        if (requested == null) index.recordIdentity()
        observe(ReindexStep.DONE)
        ReindexResult(documents = completed, chunks = chunks, documentsRebuilt = rebuilt, shedRows = shed)
    }

    /**
     * Whether a document's rows already agree with its text: its chunks were measured by this tokenizer and
     * budget, and it has exactly one row per chunk.
     */
    private fun rowsAgree(index: SearchIndex, documentId: DocumentId): Boolean {
        if (content.needsChunking(
                documentId,
                chunker.version,
                chunker.counterId,
                Chunker.DEFAULT_MAX_SEQUENCE_TOKENS,
                Chunker.DEFAULT_OVERLAP_TOKENS,
            )
        ) {
            return false
        }
        return index.rowCount(documentId) == content.chunkCount(documentId)
    }

    /**
     * Rebuilds one document's rows from its persisted chunks and replaces them in one transaction.
     *
     * A document with no chunks has no rows, which is not a failure: an extraction pass can finish with
     * nothing searchable. Its status is left alone — the rebuild repairs derived data and does not pretend an
     * unfinished import finished.
     */
    private suspend fun rebuildDocument(index: SearchIndex, collectionId: CollectionId, documentId: DocumentId): Int {
        ensureChunksUnderTarget(collectionId, documentId)
        val revisionId = revisions.activeRevisionId(documentId)
        val rows = mutableListOf<DocumentRow>()
        var afterOrdinal = -1
        while (true) {
            val batch = content.listUnits(documentId, afterOrdinal = afterOrdinal, limit = UNIT_BATCH)
            if (batch.isEmpty()) break
            batch.forEach { unit ->
                val unitChunks = content.chunksOf(unit.id)
                unitChunks.chunked(EMBED_BATCH).forEach { group ->
                    val vectors = embed(group.map { it.text }, documentId)
                    group.forEachIndexed { position, chunk ->
                        rows += DocumentRow(
                            collectionId = collectionId,
                            documentId = documentId,
                            unitId = unit.id,
                            locator = unit.locator,
                            locatorLabel = unit.locator.describe(),
                            chunk = chunk,
                            vector = vectors[position],
                            revisionId = revisionId,
                        )
                    }
                }
                afterOrdinal = unit.ordinal
            }
            if (batch.size < UNIT_BATCH) break
        }
        if (rows.size > maxChunksPerDocument) {
            throw IllegalArgumentException(
                "document ${documentId.value} has ${rows.size} chunks, more than the " +
                    "$maxChunksPerDocument one rebuild may hold",
            )
        }
        if (rows.isEmpty()) index.deleteDocument(documentId) else index.replaceDocument(rows)
        return rows.size
    }

    /**
     * Re-chunks a document whose chunks were measured by another tokenizer, before anything reads them.
     *
     * Chunking is derived from the text, so a rebuild rebuilds the chunks the same way the import stage would.
     * The document's extraction checkpoints are untouched: re-chunking is not re-reading.
     */
    private suspend fun ensureChunksUnderTarget(collectionId: CollectionId, documentId: DocumentId) {
        val artifactRoot = paths.artifactsDir(collectionId, documentId)
        val version = chunker.version
        val tokenizerId = chunker.counterId
        val maxSequenceTokens = Chunker.DEFAULT_MAX_SEQUENCE_TOKENS
        val overlapTokens = Chunker.DEFAULT_OVERLAP_TOKENS
        if (!content.needsChunking(documentId, version, tokenizerId, maxSequenceTokens, overlapTokens)) return

        var afterOrdinal = -1
        var walked = 0
        while (true) {
            val batch = content.listUnits(documentId, afterOrdinal = afterOrdinal, limit = UNIT_BATCH)
            if (batch.isEmpty()) break
            batch.forEach { unit ->
                val plan = chunker.chunk(unit, maxSequenceTokens, overlapTokens, content.structureOf(unit, artifactRoot))
                content.replaceUnitChunks(
                    unitId = unit.id,
                    drafts = plan.drafts,
                    chunkerVersion = version,
                    tokenizerId = tokenizerId,
                    maxSequenceTokens = maxSequenceTokens,
                    overlapTokens = overlapTokens,
                )
                afterOrdinal = unit.ordinal
                walked++
            }
            if (batch.size < UNIT_BATCH) break
        }
        content.finishChunking(
            documentId = documentId,
            chunkerVersion = version,
            tokenizerId = tokenizerId,
            maxSequenceTokens = maxSequenceTokens,
            overlapTokens = overlapTokens,
            unitCount = walked,
        )
    }

    private suspend fun embed(texts: List<String>, documentId: DocumentId): List<FloatArray> {
        val embedder = documentEmbedder()
            ?: throw EmbeddingException(code = ModelManager.MODEL_NOT_INSTALLED_CODE, message = ModelManager.installRemedy())
        val vectors = embedder.embedDocuments(texts)
        check(vectors.size == texts.size) {
            "the embedder returned ${vectors.size} vectors for ${texts.size} passages of document ${documentId.value}"
        }
        return vectors
    }

    companion object {
        /** How many documents the rebuild reads from the database at a time. */
        private const val DOCUMENT_PAGE = 200

        /** How many units the rebuild reads at a time, the import stage's own page size. */
        private const val UNIT_BATCH = 64

        /** How many passages are embedded in one call. */
        private const val EMBED_BATCH = 32

        /** The stage name a rebuild reports while it rebuilds documents. */
        const val STAGE_REBUILD: String = "rebuild"

        /** A document may hold at most this many chunks in one rebuild, the import stage's own ceiling. */
        const val MAX_CHUNKS_PER_DOCUMENT: Int = 50_000

        private const val SHED_ROWS_FIELD = "shed_rows"
        private val LOGGER = LoggerFactory.getLogger("infoscry.reindex")
    }
}
