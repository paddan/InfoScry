package infoscry.jobs

import infoscry.chunk.Chunker
import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentStatus
import infoscry.document.RevisionPublicationService
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.EmbeddingException
import infoscry.embedding.GpuRuntime
import infoscry.embedding.GpuUnavailableException
import infoscry.embedding.ModelManager
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.ExtractionSink
import infoscry.extract.UnitBoundary
import infoscry.extract.UnsupportedMediaTypeException
import infoscry.ocr.CandidateRevisionPhases
import infoscry.ocr.OcrComparisonException
import infoscry.ocr.StagedPageReview
import infoscry.search.DocumentRow
import infoscry.search.LuceneIndex
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.PageApproval
import infoscry.storage.PublicationPhase
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

    /**
     * The reading was staged for a decision rather than committed as the document's text: its pages are
     * durable, its approved pages are published, and the document owes a person an answer about the rest.
     */
    data object Staged : IngestResult

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
    /**
     * Where a published reading becomes a revision.
     *
     * An import's text is already the document's published content, so it is recorded as a published
     * revision rather than staged as one: the reading a later replacement has to name as its base is a
     * revision from the moment the import finishes, and the text it replaced stays readable after it.
     */
    private val revisions: DocumentRevisionStore,
    /**
     * Where a staged reading's approved pages are published.
     *
     * An import in check-and-improve mode stages its reading as a candidate rather than committing it, so
     * the reading reaches the archive through the publication service like any other revision: the pages
     * nobody owes a decision about become the document's text, and the pages that do still owe one are left
     * out of it.
     */
    private val publication: RevisionPublicationService,
) {

    /** What a staged reading becomes: its passages, their vectors, and the publication of its pages. */
    private val phases = CandidateRevisionPhases(
        revisions = revisions,
        chunker = chunker,
        publication = publication,
    )

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
        /**
         * The attempt's authority for sending this document's pages off this machine, or null when it has
         * none. It is the caller's because only the caller knows what the allowance belongs to — an import
         * job's twenty files share one, an operation's one document has its own.
         */
        dispatch: infoscry.ocr.OcrDispatchAuthority? = null,
        /**
         * How this attempt judges a page it stages for a person's decision, or null when nothing does.
         *
         * A staged reading is a proposal, and whether it is worth a person's time is a question about the
         * page's *two* readings: the engine's and the one the page itself carries. Only the draft knows the
         * second one, so the comparison happens here, while that draft is in hand — see
         * [infoscry.ocr.StagedPageReview].
         */
        review: infoscry.ocr.StagedPageReview? = null,
    ): IngestResult {
        // The bytes are classified under the boundary that refuses a document the deletion removed. The
        // deletion parks the managed copy of a document it owns, and it can do that between this file's attach
        // and this read: bytes that are gone because of that are this file's cancellation, not an unreadable
        // document, and the two cannot be told apart after the fact. Inside one permit the guard and the read
        // are decided together, so the deletion can only be seen before them — never in between.
        val mediaType = stage.run(STAGE_RECORD) {
            if (documents.isDeletionTarget(document.id)) throw DocumentBeingDeletedException(document.id)
            pipeline.detector.detect(managedPath)
        }
        // The reader is selected once and asked about its runtime, rather than selected again per event: what
        // an attempt's fingerprint records has to be the runtime its *own* reader will read the pages with.
        val extractor = try {
            pipeline.registry.select(mediaType.value)
        } catch (unsupported: UnsupportedMediaTypeException) {
            onFailure(unsupported.code, "the pipeline has no extractor for ${mediaType.value}")
            return IngestResult.Failed
        }

        // Which sink this document's units go to is decided per document, because a check-and-improve
        // attempt's reading is a candidate revision of the document it read — a revision that has to name its
        // own document, which the pipeline cannot know when it is built.
        val sink = pipeline.sinkFor(document.id, settings.ocrMode)

        if (!sink.storesUnits) {
            // No durable unit store exists, so the extraction phase is an explicit no-op: extraction is not
            // run, because the committed text would be thrown away as soon as it was produced.
            return IngestResult.NoUnitStore
        }

        stage.run(STAGE_RECORD) { documents.updateStatus(document.id, DocumentStatus.EXTRACTING) }

        // What this attempt reads under is the runtime admission recorded, when it recorded one: the
        // identity travels in the settings, and nothing here asks the reader what it is *now*, so a restart
        // resumes the runtime it was admitted with rather than discovering a newly installed one and
        // adopting it. The one case that still has to ask is an attempt whose settings record no runtime at
        // all — a payload written before admission probed, or an engine that could not describe itself —
        // because then there is no identity for the fingerprint to be computed from, and the reader's own
        // answer is the only identity those pages can be keyed by. An absent identity is never treated as
        // though it were a discovered one: it is a value of its own, so nothing recorded is ever confused
        // with something that was discovered later.
        val settings = if (settings.ocrAttempt?.runtimeIdentity == null) {
            settings.withRuntimeIdentity(extractor::runtimeIdentity)
        } else {
            settings
        }
        val fingerprint = ExtractionFingerprint.of(document.sha256, settings)

        val input = ExtractionInput(
            documentId = document.id,
            managedPath = managedPath,
            artifactRoot = paths.artifactsDir(collection.id, document.id),
            settings = settings,
            fingerprint = fingerprint,
            // Which keys may be skipped is the whole difference between a resume and a retry.
            committedUnitKeys = if (revisitFailedUnits) {
                sink.retryKeys(document.id, fingerprint)
            } else {
                sink.committedKeys(document.id, fingerprint)
            },
            boundary = StageBoundary(stage),
            originalFilename = document.originalFilename,
            dispatch = dispatch,
        )

        var finished = false
        var lastFailureCode: String? = null
        try {
            // The collector commits inside the boundary's permit: a flow is collected inline, so `emit` does
            // not return until the sink has stored that unit, and the permit is still held while it does.
            // The events are read on the way past because the document's own outcome depends on them: only a
            // flow that reported it delivered everything may be called extracted.
            pipeline.registry.extract(input, mediaType.value).collect { event ->                when (event) {
                    is ExtractionEvent.Finished -> finished = true
                    is ExtractionEvent.UnitFailed -> lastFailureCode = event.code
                    // An announcement changes what the document's progress reads, not what this attempt
                    // decides about it: the sink persists it and the outcome is unaffected.
                    is ExtractionEvent.Progress -> Unit
                    is ExtractionEvent.UnitReady -> Unit
                }
                // A page a check-and-improve attempt stages is judged while its draft is in hand, and
                // *before* it is staged: the page's own text exists nowhere else, and a review that could
                // not be made — an unreachable reviewer is not one, it is an uncertain answer — leaves the
                // page unstaged rather than staged and unjudged, the way a rescan reviews before it
                // commits. Nothing in the comparison writes a page, so this is not a commit of its own.
                val judged = if (sink.stagesForReview && event is ExtractionEvent.UnitReady) {
                    review?.review(document.id, event.key, event.ordinal, event.unit)
                } else {
                    null
                }
                sink.deliver(document.id, fingerprint, event)
                if (judged == PageApproval.APPROVED && event is ExtractionEvent.UnitReady) {
                    // Nobody owes this page a decision: the staged reading is the text the page already
                    // carried, which is not a reading anybody has to accept. It is approved as the revision
                    // records it, and only now, because the page the approval is about exists only once the
                    // sink has staged it.
                    revisions.recordPageApproval(
                        revisionId = requireNotNull(sink.candidateRevisionId) {
                            "a page staged for review names the revision it was staged into"
                        },
                        ordinal = event.ordinal,
                        approval = PageApproval.APPROVED,
                    )
                }
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
        } catch (waiting: ImportAwaitingApproval) {
            // A page would have exceeded the attempt's external scope, so it was not sent. That is not this
            // document's failure and not a partial reading to keep: the caller ends the attempt in its own
            // durable waiting state, and the pages this document already committed stay committed.
            throw waiting
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

        if (sink.stagesForReview) {
            return publishStagedReading(document, sink, stage, onFailure)
        }

        chunkContent(document, stage)
        if (!embedAndIndex(collection, document, sink, stage, onFailure)) return IngestResult.Failed
        return IngestResult.Complete
    }

    /**
     * Makes a reading that was staged for review the document's own text, as far as it has been approved.
     *
     * An import's staged reading is a candidate like a rescan's, but it is also the document's *first*
     * reading: there is no published text a partial publication could drop, which is what lets the pages
     * nobody owes a decision about become searchable while the pages that still owe one keep their
     * pending-review state and no searchable text at all. Which pages those are is the publication service's
     * decision — it refuses a reading nobody approved, and publishes the rest — and what this pass
     * contributes is their passages, their vectors, and the document's own status afterwards.
     *
     * A refusal is not a failure of the import: the file was read and its reading is durable, and what the
     * document owes is a person's decision. Only a refusal about a reading nobody approved at all ends this
     * way; a publication that refused the reading itself is reported as the failure it is.
     */
    private suspend fun publishStagedReading(
        document: Document,
        sink: ExtractionSink,
        stage: JobStage,
        onFailure: suspend (String, String) -> Unit,
    ): IngestResult {
        val candidate = sink.candidateRevisionId
        if (candidate == null) {
            // Nothing was staged, so there is no reading to decide about and nothing to publish: the file
            // produced no unit at all, and what it owes is still a person's look rather than an index entry.
            stage.run(STAGE_RECORD) { documents.updateStatus(document.id, DocumentStatus.NEEDS_REVIEW) }
            return IngestResult.Staged
        }
        val embedder = documentEmbedder()
        if (embedder == null) {
            onFailure(ModelManager.MODEL_NOT_INSTALLED_CODE, ModelManager.installRemedy())
            return IngestResult.Failed
        }

        val publicationId = try {
            stage.run(STAGE_CHUNK) { documents.updateStatus(document.id, DocumentStatus.CHUNKING) }
            // No operation row: what this reading owes a reader is read from its document's status, which is
            // written below from the publication attempt itself.
            phases.chunkCandidate(operationId = null, candidate = candidate, document = document, stage = stage)
            stage.run(STAGE_EMBED) { documents.updateStatus(document.id, DocumentStatus.EMBEDDING) }
            phases.embedCandidate(candidate = candidate, embedder = embedder, stage = stage)
            stage.run(STAGE_INDEX) { documents.updateStatus(document.id, DocumentStatus.INDEXING) }
            // An import's candidate descends from no revision: the document publishes nothing yet, which is
            // exactly what lets the publication service publish the pages it has approved.
            publication.publish(
                documentId = document.id,
                baseRevisionId = null,
                candidateRevisionId = candidate,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notActive: CollectionNotActiveException) {
            throw notActive
        } catch (deleted: DocumentBeingDeletedException) {
            throw deleted
        } catch (failure: Exception) {
            onFailure(
                embeddingCodeFor(failure),
                failure.message ?: "the staged reading could not be embedded and published",
            )
            return IngestResult.Failed
        }
        val intent = revisions.intent(publicationId)
        if (intent?.phase != PublicationPhase.PUBLISHED) {
            // The publication refused in its own words. A reading nobody approved at all is the
            // pending-review state a person acts on — the status *is* the state, so the document keeps no
            // error code for it — while a refusal for any other reason (passages that are not complete
            // enough to index) is this file's failure rather than a decision somebody owes.
            val code = intent?.errorCode
            if (code != RevisionPublicationService.AWAITING_REVIEW_CODE) {
                onFailure(
                    code ?: EMBEDDING_FAILED,
                    intent?.errorMessage ?: "the staged reading could not be published",
                )
                return IngestResult.Failed
            }
            stage.run(STAGE_RECORD) { documents.updateStatus(document.id, DocumentStatus.NEEDS_REVIEW) }
            return IngestResult.Staged
        }
        // Part of the reading is published. The document is complete only when no page of it still owes a
        // decision, and otherwise the review-pending state is what says so.
        val pending = sink.awaitingDecision(document.id)
        stage.run(STAGE_RECORD) {
            documents.updateStatus(
                id = document.id,
                status = if (pending == 0) DocumentStatus.COMPLETE else DocumentStatus.NEEDS_REVIEW,
            )
        }
        return IngestResult.Staged
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
        sink: ExtractionSink,
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
                revisions.recordPublishedContent(document.id, PROVENANCE_IMPORT)
            }
            stage.run(STAGE_RECORD) {
                val failedUnits = content.extractionMarker(document.id)?.failedUnits ?: 0
                val awaiting = sink.awaitingDecision(document.id)
                val status = when {
                    // A page nobody has approved is not "done", however well the embedding worked: the
                    // document owes a person a decision, and no page that awaits one is complete text. It
                    // outranks a failed unit because the question a reader has to answer first is "did
                    // someone accept this reading", not "did every unit come back".
                    awaiting > 0 -> DocumentStatus.NEEDS_REVIEW
                    failedUnits > 0 -> DocumentStatus.COMPLETE_WITH_WARNINGS
                    else -> DocumentStatus.COMPLETE
                }
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
            onFailure(embeddingCodeFor(failure), failure.message ?: "the document could not be embedded")
            return false
        }
    }

    /**
     * Why embedding and publishing a reading failed, in the code a person can act on.
     *
     * A GPU that is not usable and a passage the model refuses name themselves; everything else is the
     * generic embedding failure, because the archive knows no more about it than that it did not work.
     */
    private fun embeddingCodeFor(failure: Exception): String = when (failure) {
        is GpuUnavailableException -> GpuRuntime.GPU_UNAVAILABLE_CODE
        is EmbeddingException -> failure.code
        else -> EMBEDDING_FAILED
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
        // A comparison that could not be *made* names why in its own code rather than as a generic
        // extraction failure: the attempt's prompt or policy version is not this build's, and the code is
        // what a person can act on. The message is the comparison's own and carries no page text.
        is OcrComparisonException -> failure.code
        else -> EXTRACTION_FAILED
    }

    internal companion object {

        /** The document was refused before embedding because it exceeds the configured ceiling. */
        internal const val INDEX_TOO_LARGE = "INDEX_TOO_LARGE"

        /** What the revision an import publishes records about where it came from. */
        internal const val PROVENANCE_IMPORT = "IMPORT"

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
