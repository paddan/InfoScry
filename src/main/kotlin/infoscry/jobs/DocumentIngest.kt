package infoscry.jobs

import infoscry.chunk.Chunker
import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentStatus
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.EmbeddingException
import infoscry.embedding.GpuRuntime
import infoscry.embedding.GpuUnavailableException
import infoscry.embedding.ModelManager
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.UnitBoundary
import infoscry.extract.UnsupportedMediaTypeException
import infoscry.search.DocumentRow
import infoscry.search.LuceneIndex
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentStore
import kotlinx.coroutines.CancellationException

/**
 * How one existing managed copy's reading ended.
 *
 * The caller decides what a result means to *it* — an import records a file outcome, a retry records the
 * document's own status — which is why this is a result and not a side effect.
 */
internal sealed interface IngestResult {

    /** Every unit was delivered, and the document is chunked, embedded and published. */
    data object Complete : IngestResult

    /**
     * The pipeline has nowhere durable to put units, so extraction was not run at all. The caller decides
     * what the document's status is, because only the caller knows what it was before.
     */
    data object NoUnitStore : IngestResult

    /** The attempt failed, and [DocumentIngest.ingest]'s reporter has already recorded why. */
    data object Failed : IngestResult
}

/**
 * Reads one *existing* managed copy into committed, searchable content.
 *
 * This is the half of ingestion that both attempts share and neither owns: an import reached its
 * document by copying a source file, a retry reached the same document through an identifier it already
 * had. What they have in common is what happens next — select an extractor for the managed bytes, commit
 * the units an earlier attempt has not already committed, chunk them, embed them, and publish them. That
 * is this class, so the two paths cannot drift apart about what "read again" means.
 *
 * What deliberately is *not* here:
 *
 * - **Copying or classifying bytes.** A retry must never be routed through the import path's duplicate
 *   detection, so the classification belongs to the caller that has new bytes to classify.
 * - **Deciding the document's outcome.** The caller has its own record to keep (an import item, a
 *   document status) and supplies [ingest]'s failure reporter, which runs while the attempt's own permit
 *   is held.
 * - **Skipping failed units.** [ingest] is told whether this attempt resumes (skip what was committed,
 *   failed ones included) or retries (revisit what failed). See [ExtractionSink.retryKeys].
 */
internal class DocumentIngest(
    private val paths: AppPaths,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val pipeline: ImportPipeline,
    private val content: ContentStore,
    private val chunker: Chunker,
    private val index: () -> LuceneIndex,
    private val documentEmbedder: () -> DocumentEmbedder?,
    private val maxChunksPerDocument: Int,
) {

    /**
     * Reads [document]'s bytes at [managedPath] under [settings] and publishes the result.
     *
     * [revisitFailedUnits] is the one thing that separates an explicit retry from a crash resume: a retry
     * is handed only the units an earlier attempt committed *successfully*, so the ones that failed are
     * read again, while a resume skips them as known results.
     *
     * Every failure is handed to [onFailure] — which the caller runs under its own record permit — and
     * reported as [IngestResult.Failed]; a deletion or a tombstoned collection is not a failure of this
     * document and travels as the exception it is.
     */
    suspend fun ingest(
        collection: Collection,
        settings: ExtractionSettings,
        document: Document,
        managedPath: java.nio.file.Path,
        stage: JobStage,
        revisitFailedUnits: Boolean,
        onFailure: suspend (code: String, message: String) -> Unit,
    ): IngestResult {
        val fingerprint = ExtractionFingerprint.of(document.sha256, settings)
        // The bytes are classified under the boundary that refuses a document the deletion removed. The
        // deletion parks the managed copy of a document it owns, and it can do that between this file's attach
        // and this read: bytes that are gone because of that are this file's cancellation, not an unreadable
        // document, and the two cannot be told apart after the fact. Inside one permit the guard and the read
        // are decided together, so the deletion can only be seen before them — never in between.
        val mediaType = stage.run(STAGE_RECORD) {
            if (documents.isDeletionTarget(document.id)) throw DocumentBeingDeletedException(document.id)
            pipeline.detector.detect(managedPath)
        }
        try {
            pipeline.registry.select(mediaType.value)
        } catch (unsupported: UnsupportedMediaTypeException) {
            onFailure(unsupported.code, "the pipeline has no extractor for ${mediaType.value}")
            return IngestResult.Failed
        }

        if (!pipeline.sink.storesUnits) {
            // No durable unit store exists, so the extraction phase is an explicit no-op: extraction is not
            // run, because the committed text would be thrown away as soon as it was produced.
            return IngestResult.NoUnitStore
        }

        stage.run(STAGE_RECORD) { documents.updateStatus(document.id, DocumentStatus.EXTRACTING) }

        val input = ExtractionInput(
            documentId = document.id,
            managedPath = managedPath,
            artifactRoot = paths.artifactsDir(collection.id, document.id),
            settings = settings,
            fingerprint = fingerprint,
            // Which keys may be skipped is the whole difference between a resume and a retry.
            committedUnitKeys = if (revisitFailedUnits) {
                pipeline.sink.retryKeys(document.id, fingerprint)
            } else {
                pipeline.sink.committedKeys(document.id, fingerprint)
            },
            boundary = StageBoundary(stage),
            originalFilename = document.originalFilename,
        )

        var finished = false
        var lastFailureCode: String? = null
        try {
            // The collector commits inside the boundary's permit: a flow is collected inline, so `emit` does
            // not return until the sink has stored that unit, and the permit is still held while it does.
            // The events are read on the way past because the document's own outcome depends on them: only a
            // flow that reported it delivered everything may be called extracted.
            pipeline.registry.extract(input, mediaType.value).collect { event ->
                when (event) {
                    is ExtractionEvent.Finished -> finished = true
                    is ExtractionEvent.UnitFailed -> lastFailureCode = event.code
                    // An announcement changes what the document's progress reads, not what this attempt
                    // decides about it: the sink persists it and the outcome is unaffected.
                    is ExtractionEvent.Progress -> Unit
                    is ExtractionEvent.UnitReady -> Unit
                }
                pipeline.sink.deliver(document.id, fingerprint, event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notActive: CollectionNotActiveException) {
            // The collection is being deleted. That is not this document's problem: the attempt is over and
            // the runner records it as such.
            throw notActive
        } catch (deleted: DocumentBeingDeletedException) {
            // One file's document was removed while this attempt was reading it, and the removal outlived
            // the check that noticed it: a failure reporter would try to write the row and its item
            // reference that the deletion has already taken away. The attempt's own record of that is the
            // item's `CANCELLED` disposition, which the caller writes when this exception reaches it.
            throw deleted
        } catch (failure: Exception) {
            onFailure(codeFor(failure), failure.message ?: "the extractor failed without a message")
            return IngestResult.Failed
        }

        if (!finished) {
            // The extractor stopped without saying it delivered everything: a refusal it recognised before
            // it had a unit to name, or an abort in the middle. What it did commit stays committed — a page
            // read before the abort is still evidence — but the document is reported as failed rather than as
            // extracted with warnings, because nothing about it is complete enough to search.
            val code = lastFailureCode
                ?: content.loadCheckpoints(document.id, fingerprint).lastOrNull { !it.succeeded }?.errorCode
                ?: EXTRACTION_FAILED
            onFailure(code, ImportJobHandler.messageFor(code))
            return IngestResult.Failed
        }

        chunkContent(document, stage)
        if (!embedAndIndex(collection, document, stage, onFailure)) return IngestResult.Failed
        return IngestResult.Complete
    }

    /**
     * Turns the document's persisted chunks into vectors and publishes them to the search index.
     *
     * This is where ingestion stops being "read this file" and becomes "make it searchable". Embedding
     * runs chunk by chunk inside the mutation permit (the expensive half, and the half that may need the
     * accelerator); the index publication is a single replacement of the document's chunks followed by one
     * commit, done under its own permit after a final lifecycle recheck, and only then is the document
     * marked complete. Cancel before that commit and nothing is durable: a retry replays from the
     * persisted chunks without re-reading the source or re-running OCR.
     */
    private suspend fun embedAndIndex(
        collection: Collection,
        document: Document,
        stage: JobStage,
        onFailure: suspend (String, String) -> Unit,
    ): Boolean {
        val embedder = documentEmbedder()
        if (embedder == null) {
            onFailure(ModelManager.MODEL_NOT_INSTALLED_CODE, ModelManager.installRemedy())
            return false
        }

        try {
            // The index publishes a single replacement per document, so every row lives in one in-memory
            // list until the commit. A document's rows are bounded by its chunks; the ceiling turns "however
            // large a real archive can get" into a documented limit a later attempt can out-grow. It is
            // checked inside the failure mapping so an over-ceiling document is recorded with an actionable
            // code, and before any embedding work or index publication.
            val chunkCount = content.chunkCount(document.id)
            if (chunkCount > maxChunksPerDocument) {
                throw EmbeddingException(
                    INDEX_TOO_LARGE,
                    "the document has $chunkCount chunks, which exceeds the " +
                        "$maxChunksPerDocument that one attempt may publish in one index transaction",
                )
            }

            stage.run(STAGE_EMBED) { documents.updateStatus(document.id, DocumentStatus.EMBEDDING) }
            val rows = ArrayList<DocumentRow>(chunkCount)
            var afterOrdinal = -1
            while (true) {
                val units = content.listUnits(document.id, afterOrdinal = afterOrdinal, limit = CHUNK_BATCH)
                if (units.isEmpty()) break
                units.forEach { unit ->
                    val chunks = content.chunksOf(unit.id)
                    chunks.chunked(EMBED_BATCH).forEach { batch ->
                        val vectors = stage.run(STAGE_EMBED) {
                            embedder.embedDocuments(batch.map { it.text })
                        }
                        require(vectors.size == batch.size) {
                            "the embedder returned ${vectors.size} vectors for ${batch.size} passages"
                        }
                        batch.forEachIndexed { index, chunk ->
                            rows += DocumentRow(
                                collectionId = collection.id,
                                documentId = document.id,
                                unitId = unit.id,
                                locator = unit.locator,
                                locatorLabel = unit.locator.describe(),
                                chunk = chunk,
                                vector = vectors[index],
                            )
                        }
                    }
                    afterOrdinal = unit.ordinal
                }
                if (units.size < CHUNK_BATCH) break
            }

            if (rows.isEmpty()) {
                // Nothing to index means an extraction pass with no units, which the chunking stage
                // would already have refused as a refusal rather than a finished pass. This guard keeps
                // the record honest instead of silently publishing an empty replacement.
                throw EmbeddingException(EMBEDDING_FAILED, "the document produced no searchable chunks")
            }

            stage.run(STAGE_INDEX) { documents.updateStatus(document.id, DocumentStatus.INDEXING) }
            stage.run(STAGE_INDEX) {
                // Recheck under the permit before publication: the document may have been targeted for
                // deletion while it was being embedded, and publishing it would leave index entries nothing
                // else could account for.
                if (documents.isDeletionTarget(document.id)) throw DocumentBeingDeletedException(document.id)
                // Recheck under the permit before publication: the collection may have been tombstoned
                // while this document was being embedded, and publishing into a deleted collection would
                // leave index entries nothing else could account for.
                val live = collections.get(collection.id)
                    ?: throw CollectionNotActiveException(collection.id)
                if (live.lifecycle != CollectionLifecycle.ACTIVE) {
                    throw CollectionNotActiveException(collection.id)
                }
                index().replaceDocument(rows)
            }
            stage.run(STAGE_RECORD) {
                val failedUnits = content.extractionMarker(document.id)?.failedUnits ?: 0
                val status = if (failedUnits > 0) DocumentStatus.COMPLETE_WITH_WARNINGS else DocumentStatus.COMPLETE
                documents.updateStatus(document.id, status)
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notActive: CollectionNotActiveException) {
            throw notActive
        } catch (deleted: DocumentBeingDeletedException) {
            // Same as the extraction loop: a document being removed is the caller's cancellation to record,
            // never this attempt's embedding failure.
            throw deleted
        } catch (failure: Exception) {
            val code = when (failure) {
                is GpuUnavailableException -> GpuRuntime.GPU_UNAVAILABLE_CODE
                is EmbeddingException -> failure.code
                else -> EMBEDDING_FAILED
            }
            onFailure(code, failure.message ?: "the document could not be embedded")
            return false
        }
    }

    /**
     * Splits every unit of a finished extraction into embeddable chunks, one bounded unit per stage.
     *
     * The pass reads the units back out of the store rather than chunking them as they are extracted, which
     * is what lets re-chunking happen without re-reading the document: another tokenizer or another passage
     * budget rebuilds the chunks from the text that is already there and touches neither the text nor the OCR
     * checkpoints. That is also why re-embedding a document never invalidates compatible OCR checkpoints.
     *
     * It runs unit by unit on purpose. A document can hold tens of thousands of units, and one list of them
     * would be both a memory cost and a permit held for a whole document — and the exclusive side of the
     * mutation gate has no timeout, so that hold would stop every other writer in the process.
     */
    private suspend fun chunkContent(document: Document, stage: JobStage) {
        val version = chunker.version
        val tokenizerId = chunker.counterId
        val maxSequenceTokens = Chunker.DEFAULT_MAX_SEQUENCE_TOKENS
        val overlapTokens = Chunker.DEFAULT_OVERLAP_TOKENS
        if (!content.needsChunking(document.id, version, tokenizerId, maxSequenceTokens, overlapTokens)) return

        stage.run(STAGE_CHUNK) { documents.updateStatus(document.id, DocumentStatus.CHUNKING) }
        var afterOrdinal = -1
        var walked = 0
        while (true) {
            val batch = content.listUnits(document.id, afterOrdinal = afterOrdinal, limit = CHUNK_BATCH)
            if (batch.isEmpty()) break
            batch.forEach { unit ->
                val plan = chunker.chunk(unit, maxSequenceTokens, overlapTokens)
                stage.run(STAGE_CHUNK) {
                    content.replaceUnitChunks(
                        unitId = unit.id,
                        drafts = plan.drafts,
                        chunkerVersion = version,
                        tokenizerId = tokenizerId,
                        maxSequenceTokens = maxSequenceTokens,
                        overlapTokens = overlapTokens,
                    )
                }
                if (plan.headerDropped) {
                    LOGGER.atWarn()
                        .addKeyValue(COMPONENT_FIELD, INGEST_COMPONENT)
                        .addKeyValue(DOCUMENT_FIELD, document.id.value)
                        .addKeyValue(ORDINAL_FIELD, unit.ordinal)
                        .log("a repeated header left no room for a body, so the unit was chunked without it")
                }
                afterOrdinal = unit.ordinal
                walked++
            }
            if (batch.size < CHUNK_BATCH) break
        }
        // Only now is the document chunked: a marker written per unit would let a pass that died halfway look
        // like a pass that finished.
        stage.run(STAGE_CHUNK) {
            content.finishChunking(
                documentId = document.id,
                chunkerVersion = version,
                tokenizerId = tokenizerId,
                maxSequenceTokens = maxSequenceTokens,
                overlapTokens = overlapTokens,
                unitCount = walked,
            )
        }
    }

    /**
     * One unit of extraction, under the same permit discipline as every other mutation: the stage asserts
     * that the attempt may still continue, and the permit is released when the unit's event has been
     * delivered and committed.
     */
    private class StageBoundary(private val stage: JobStage) : UnitBoundary {

        override suspend fun <T> unit(block: suspend () -> T): T = stage.run(STAGE_EXTRACT) { block() }
    }

    private fun codeFor(failure: Exception): String = when (failure) {
        is UnsupportedMediaTypeException -> failure.code
        else -> EXTRACTION_FAILED
    }

    internal companion object {

        /** The document was refused before embedding because it exceeds the configured ceiling. */
        internal const val INDEX_TOO_LARGE = "INDEX_TOO_LARGE"

        /** The document could not be embedded and published. */
        internal const val EMBEDDING_FAILED = "EMBEDDING_FAILED"

        /** The extractor stopped without delivering every unit. */
        internal const val EXTRACTION_FAILED = "EXTRACTION_FAILED"

        private const val STAGE_EXTRACT = "extract"
        private const val STAGE_CHUNK = "chunk"
        private const val STAGE_EMBED = "embed"
        private const val STAGE_INDEX = "index"
        private const val STAGE_RECORD = "record"

        /** How many units one read of the chunking walk takes, so a large document stays interruptible. */
        private const val CHUNK_BATCH = 64

        /** How many chunks one embedding step holds at once, so a long document stays interruptible. */
        private const val EMBED_BATCH = 64

        private const val COMPONENT_FIELD = "component"
        private const val DOCUMENT_FIELD = "document_id"
        private const val ORDINAL_FIELD = "unit_ordinal"
        private const val INGEST_COMPONENT = "ingest"
    }
}

private val LOGGER = org.slf4j.LoggerFactory.getLogger("infoscry.ingest")
