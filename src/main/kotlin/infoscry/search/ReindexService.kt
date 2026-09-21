package infoscry.search

import infoscry.config.AppPaths
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.chunk.Chunker
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.EmbeddingException
import infoscry.embedding.ModelManager
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.DocumentStore
import infoscry.storage.MutationCoordinator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.extension
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.io.path.name
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * What one rebuild was asked to rebuild.
 *
 * [collectionId] is the single collection a caller narrowed the rebuild to; `null` means every live
 * collection. Either way the result is one complete generation covering the whole archive, because a
 * search that spans collections has to read one index.
 */
data class ReindexRequest(val collectionId: CollectionId? = null)

/** How far a rebuild has come, reported after the work it describes is durable. */
data class ReindexProgress(val stage: String, val completed: Int, val total: Int)

/** What one rebuild produced, in the words `infoscry reindex --json` reports. */
data class ReindexResult(
    val generation: String,
    val documents: Int,
    val chunks: Int,
    val documentsRebuilt: Int,
)

/**
 * One instant a rebuild can be interrupted at, in the order they happen.
 *
 * The recovery test starts a real child JVM and stops it through this seam, so the state a kill leaves
 * behind is exactly the state production would leave — the instants are the service's, not a second
 * copy of its protocol.
 */
enum class ReindexStep {

    /** Every row has been written and the new generation's writer has not been committed yet. */
    GENERATION_BUILT,

    /** The new generation is committed, closed, reopened and validated; the marker still names the old one. */
    BEFORE_MARKER_SWAP,

    /** The marker names the new generation; this process has not started serving it yet. */
    AFTER_MARKER_SWAP,

    /** The swap is complete and the generation the process served before has been retired. */
    DONE,
}

/**
 * Rebuilds the search index from the persisted text, in a successor generation, under exclusive
 * maintenance.
 *
 * SQLite is authoritative and the index is derived, so a rebuild is not a repair of lost work — it is
 * the way the index and the database agree again after a model change, a schema change, or an import
 * whose index half never landed. The rebuild is one exclusive maintenance scope for its whole duration:
 * every other writer drains to its durable checkpoint before the snapshot is taken, and nothing may
 * publish into either generation while the successor is being built.
 *
 * The generation swap is the part the product's promises rest on:
 *
 * - **The marker is the truth.** A successor is built to completion, committed, closed, reopened and
 *   validated, and only then does the `current` marker name it — atomically, the way every open
 *   resolves it. A crash before the swap leaves the old generation serving; a crash after it leaves
 *   the successor as the one the next open selects.
 * - **Readers are never broken.** This process starts serving the successor only after the marker is
 *   durable, and the generation it replaced is retired only after the searches that leased it have
 *   drained.
 */
class ReindexService(
    private val mutations: MutationCoordinator,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val content: ContentStore,
    private val chunker: Chunker,
    private val paths: AppPaths,
    private val identity: IndexIdentity,
    private val documentEmbedder: () -> DocumentEmbedder?,
    private val current: () -> LuceneIndex,
    private val publish: suspend (LuceneIndex, LuceneIndex) -> Unit,
    private val maxChunksPerDocument: Int = MAX_CHUNKS_PER_DOCUMENT,
) {

    /**
     * Runs one rebuild.
     *
     * [onProgress] is called between documents with how many are durable; the reindex job passes its
     * maintenance-owner reporter there, which is also where it notices that the job was cancelled.
     * [observe] is the interruption seam the recovery test drives.
     */
    suspend fun reindex(
        request: ReindexRequest,
        onProgress: suspend (ReindexProgress) -> Unit = {},
        observe: (ReindexStep) -> Unit = {},
    ): ReindexResult = mutations.withExclusiveMaintenance("reindex the search index") {
        val previous = current()
        val nextName = GENERATION_PREFIX + "next-" + UUID.randomUUID()

        // A narrowed rebuild carries every other collection's rows forward unchanged, which is only
        // correct while the generation it copies from was built by the same mathematics. A changed
        // model, export, tokenizer or schema invalidates every vector in the archive, and the caller
        // is told so rather than handed a half-stale index.
        if (request.collectionId != null) {
            val status = previous.schemaStatus
            if (status !is SchemaStatus.Ready) {
                throw IllegalArgumentException(
                    "a rebuild of one collection cannot reuse vectors an unchanged model produced; " +
                        "the index reports: ${status.reasons().joinToString("; ")}. " +
                        "Run `infoscry reindex` without --collection to rebuild every collection.",
                )
            }
        }
        val requested = request.collectionId?.let { collectionId ->
            collections.get(collectionId) ?: throw IllegalArgumentException(
                "no collection with id ${collectionId.value} exists",
            )
        }
        if (requested != null && requested.lifecycle != infoscry.domain.CollectionLifecycle.ACTIVE) {
            throw IllegalArgumentException(
                "collection ${requested.name} is not active, so its index cannot be rebuilt",
            )
        }

        val result = try {
            buildGeneration(previous, nextName, request, requested, onProgress, observe)
        } catch (failure: Throwable) {
            discardUnpublished(nextName, previous.name, failure)
            throw failure
        }
        observe(ReindexStep.DONE)
        result
    }

    /**
     * Builds the successor generation and publishes it, or leaves the current one serving.
     *
     * The copy is what keeps a narrowed rebuild cheap: the successor starts as a copy of the committed
     * current generation, so every collection the caller did not name keeps its rows and vectors
     * without being rewritten, and the named collection's rows are deleted from the copy and built
     * again from the persisted text. Nothing is published until the whole successor validates.
     */
    private suspend fun buildGeneration(
        previous: LuceneIndex,
        nextName: String,
        request: ReindexRequest,
        requested: infoscry.domain.Collection?,
        onProgress: suspend (ReindexProgress) -> Unit,
        observe: (ReindexStep) -> Unit,
    ): ReindexResult {
        val scope = if (requested == null) {
            collections.list()
        } else {
            listOf(requested)
        }

        // The snapshot has to be the committed state, not the writer's memory: a segment file that
        // exists only in the writer's hands is not on disk for the copy to pick up.
        previous.commit()
        val previousDirectory = paths.indexDir.resolve(previous.name)
        val nextDirectory = paths.indexDir.resolve(nextName)
        Files.createDirectories(nextDirectory)
        copyGenerationFiles(previousDirectory, nextDirectory)

        val next = LuceneIndex.openGeneration(paths.indexDir, nextName, identity)
        try {
            // The copied generation carries rows the previous reading wrote. A document keeps them
            // when the chunking marker still agrees with this tokenizer and the copy holds exactly
            // as many rows as the database holds chunks, because that is the state where the index
            // and the database already agree; anything else is rebuilt from the persisted text.
            val vectorsAreCurrent = previous.schemaStatus is SchemaStatus.Ready
            val totals = scope.sumOf { collection -> documents.countByCollection(collection.id) }
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
                        val agrees = vectorsAreCurrent &&
                            copiedRowsAgree(previous, document.id, chunker)
                        if (!agrees) {
                            next.deleteDocument(document.id)
                            val built = indexDocument(next, collection.id, document.id)
                            chunks += built.chunks
                            rebuilt++
                        }
                        completed++
                        onProgress(ReindexProgress(STAGE_REBUILD, completed, totals))
                    }
                    offset += page.size
                }
            }
            observe(ReindexStep.GENERATION_BUILT)

            // The writer is closed before the marker is touched, so the successor is durable and
            // complete while the marker still names the generation this process is serving.
            next.commit()
            next.close()
            validate(nextName, scope)

            val reopened = LuceneIndex.openGeneration(paths.indexDir, nextName, identity)
            observe(ReindexStep.BEFORE_MARKER_SWAP)
            LuceneIndex.publishMarker(paths.indexDir, nextName)
            observe(ReindexStep.AFTER_MARKER_SWAP)
            publish(reopened, previous)
            previous.drainLeases(RETIRE_TIMEOUT_MILLIS)
            previous.close()
            removeGenerationDirectory(previousDirectory, nextName)

            return ReindexResult(
                generation = nextName,
                documents = completed,
                chunks = chunks,
                documentsRebuilt = rebuilt,
            )
        } catch (failure: Throwable) {
            runCatching { next.close() }
            discardUnpublished(nextName, previous.name, failure)
            throw failure
        }
    }

    /**
     * Whether the copied generation's rows for one document already agree with the database.
     *
     * Three things have to agree for a copy to stand in for a rebuild: the chunking marker names this
     * tokenizer and budget (so the chunks in the database are the ones the index would be built from),
     * the copied rows are exactly as many as those chunks (so the import's index half landed), and the
     * generation being copied was built by the model this build embeds with (so its vectors are not
     * stale). Any one of the three missing and the document is rebuilt from its text instead.
     */
    private fun copiedRowsAgree(previous: LuceneIndex, documentId: DocumentId, chunker: Chunker): Boolean {
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
        return previous.rowCount(documentId) == content.chunkCount(documentId)
    }

    /**
     * Rebuilds one document's rows from its persisted chunks, reusing the vectors it does not have to
     * pay for again.
     *
     * A document with no chunks has no rows, which is not a failure: an extraction pass can finish with
     * nothing searchable, and an index row for it would be a row nothing can explain. Its status is
     * left alone — the rebuild repairs derived data and does not pretend an unfinished import finished.
     */
    private suspend fun indexDocument(
        next: LuceneIndex,
        collectionId: CollectionId,
        documentId: DocumentId,
    ): BuiltDocument {
        ensureChunksUnderTarget(documentId)
        val rows = mutableListOf<DocumentRow>()
        var afterOrdinal = -1
        while (true) {
            val batch = content.listUnits(documentId, afterOrdinal = afterOrdinal, limit = UNIT_BATCH)
            if (batch.isEmpty()) break
            batch.forEach { unit ->
                for (chunk in content.chunksOf(unit.id)) {
                    rows.add(
                        DocumentRow(
                            collectionId = collectionId,
                            documentId = documentId,
                            unitId = unit.id,
                            locator = unit.locator,
                            locatorLabel = unit.locator.describe(),
                            chunk = chunk,
                            vector = embed(chunk.text, documentId),
                        ),
                    )
                }
                afterOrdinal = unit.ordinal
            }
            if (batch.size < UNIT_BATCH) break
        }
        if (rows.size > maxChunksPerDocument) {
            throw IllegalArgumentException(
                "document ${documentId.value} has ${rows.size} chunks, more than the " +
                    "$maxChunksPerDocument one index transaction may hold",
            )
        }
        if (rows.isNotEmpty()) {
            next.replaceDocument(rows)
        }
        return BuiltDocument(chunks = rows.size)
    }

    /**
     * Re-chunks a document whose chunks were measured by another tokenizer, before anything reads them.
     *
     * Chunking is derived from the text, so a rebuild under this generation's tokenizer rebuilds the
     * chunks the same way the import stage would — writing them, which keeps the database the thing the
     * next rebuild and the next citation agree on. The document's extraction checkpoints are untouched:
     * re-chunking is not re-reading.
     */
    private suspend fun ensureChunksUnderTarget(documentId: DocumentId) {
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
                val plan = chunker.chunk(unit, maxSequenceTokens, overlapTokens)
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

    private suspend fun embed(text: String, documentId: DocumentId): FloatArray {
        val embedder = documentEmbedder()
            ?: throw EmbeddingException(
                code = ModelManager.MODEL_NOT_INSTALLED_CODE,
                message = ModelManager.installRemedy(),
            )
        val vectors = embedder.embedDocuments(listOf(text))
        if (vectors.size != 1) {
            throw IllegalStateException(
                "the embedder returned ${vectors.size} vectors for 1 passage of document " +
                    "${documentId.value}",
            )
        }
        return vectors.single()
    }

    /**
     * Confirms the successor agrees with the database before anything is published.
     *
     * The count is the check that means something: one Lucene row per chunk, so a generation whose row
     * count does not match the chunks the database holds is a generation that lost or invented rows,
     * and publishing it would publish a citation set nothing can account for.
     */
    private suspend fun validate(nextName: String, scope: List<infoscry.domain.Collection>) {
        val reopened = LuceneIndex.openGeneration(paths.indexDir, nextName, identity)
        try {
            if (reopened.schemaStatus !is SchemaStatus.Ready) {
                throw IllegalStateException(
                    "the rebuilt generation does not record this build's model identity",
                )
            }
            for (collection in scope) {
                val expected = documents.listByCollection(collection.id, Int.MAX_VALUE).sumOf { document ->
                    content.chunkCount(document.id)
                }
                val actual = reopened.rowCount(collection.id)
                if (actual != expected) {
                    throw IllegalStateException(
                        "collection ${collection.name} has $expected chunks in the database and " +
                            "$actual rows in the rebuilt generation; refusing to publish",
                    )
                }
            }
        } finally {
            reopened.close()
        }
    }

    /**
     * Copies the committed generation's files into the successor's directory.
     *
     * The copy is plain file work, done while the source writer is idle: the maintenance scope has
     * drained every other writer, so the segments on disk are exactly the ones the last commit named.
     * The lock file is left behind on purpose — the successor's writer makes its own.
     */
    private fun copyGenerationFiles(source: Path, target: Path) {
        Files.list(source).use { entries ->
            entries
                .filter { it.name != "write.lock" && it.extension != "tmp" }
                .forEach { entry -> Files.copy(entry, target.resolve(entry.name), StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    /** Deletes a retired generation, or says why it could not. */
    private fun removeGenerationDirectory(directory: Path, current: String) {
        if (directory.fileName.toString() == current) {
            LOGGER.atWarn().addKeyValue(GENERATION_FIELD, current)
                .log("refused to remove the generation the marker names")
            return
        }
        deleteRecursively(directory)
    }

    /**
     * Discards a successor that was never published.
     *
     * A pre-swap failure leaves the marker naming the generation the caller was already served by, so
     * the half-built directory is unreferenced and deleting it keeps the disk from filling with
     * abandoned attempts. A directory the marker names is never touched, whatever the failure was.
     */
    private fun discardUnpublished(nextName: String, currentName: String, failure: Throwable) {
        val durable = LuceneIndex.currentGenerationName(paths.indexDir)
        if (durable == nextName) {
            LOGGER.atWarn()
                .addKeyValue(GENERATION_FIELD, nextName)
                .setCause(failure)
                .log("a rebuild failed after publishing its marker; the next open selects the new generation")
            return
        }
        val directory = paths.indexDir.resolve(nextName)
        if (Files.isDirectory(directory)) {
            deleteRecursively(directory)
            LOGGER.atInfo()
                .addKeyValue(GENERATION_FIELD, nextName)
                .setCause(failure)
                .log("discarded an unpublished search index generation")
        }
    }

    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }

    /** What one document contributed to the rebuild's totals. */
    private data class BuiltDocument(val chunks: Int)

    companion object {

        /** How many documents the rebuild reads from the database at a time. */
        private const val DOCUMENT_PAGE = 200

        /** How many units the rebuild reads at a time, the import stage's own page size. */
        private const val UNIT_BATCH = 64

        /** The stage name a rebuild reports while it is building the successor. */
        const val STAGE_REBUILD: String = "rebuild"

        /** How long retirement waits for the readers it replaced before giving up. */
        const val RETIRE_TIMEOUT_MILLIS: Long = 5_000L

        /**
         * A document may hold at most this many chunks in one index transaction's in-memory rows, the
         * same ceiling the import stage keeps.
         */
        const val MAX_CHUNKS_PER_DOCUMENT: Int = 50_000

        private const val GENERATION_PREFIX: String = "lucene-"
        private const val GENERATION_FIELD = "generation"
        private val LOGGER = LoggerFactory.getLogger("infoscry.reindex")
    }
}
