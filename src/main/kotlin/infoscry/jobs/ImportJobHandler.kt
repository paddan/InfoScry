package infoscry.jobs

import infoscry.AppContext
import infoscry.chunk.Chunker
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.E5Embedder
import infoscry.embedding.EmbeddingException
import infoscry.embedding.GpuRuntime
import infoscry.embedding.GpuUnavailableException
import infoscry.embedding.ModelManager
import infoscry.search.DocumentRow
import infoscry.search.LuceneIndex
import infoscry.search.ReindexService
import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentStatus
import infoscry.domain.JobType
import infoscry.domain.Job
import infoscry.extract.CalibreConverter
import infoscry.extract.DOCUMENT_TOO_LARGE_CODE
import infoscry.extract.DOCUMENT_UNREADABLE_CODE
import infoscry.extract.ENCRYPTED_DOCUMENT_CODE
import infoscry.extract.OCR_FAILED_CODE
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.TesseractOcr
import infoscry.extract.UnitBoundary
import infoscry.extract.UnsupportedMediaTypeException
import infoscry.library.ManagedImportOutcome
import infoscry.library.ManagedLibrary
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.DocumentStore
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import infoscry.storage.ImportItemStore
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The import: one job's worth of source files become managed documents with a per-file result.
 *
 * What it guarantees, and why each guarantee is here:
 *
 * - **The user's files are only read.** The managed copy is made by [ManagedLibrary], and nothing in this
 *   class ever writes to or deletes a source path.
 * - **One file's failure is one file's failure.** A format nobody can read, a file that vanished, a tool
 *   that died: each is recorded against that item and the attempt moves to the next file. A job that gave
 *   up on the second of a thousand files would be useless for an archive.
 * - **An interrupted attempt resumes instead of starting over.** The item records the managed document as
 *   soon as the bytes are in place, and the extractor is handed the units an earlier attempt committed
 *   under the same fingerprint, so a restart does not redo OCR that already succeeded.
 * - **Reading is not enough to call a document done.** Extraction is one unit at a time through
 *   [UnitBoundary], which holds the mutation permit while a unit is produced and committed, and a document
 *   is left in `EXTRACTING`: indexing and embedding decide when it is complete, and they are somebody
 *   else's task.
 */
class ImportJobHandler(
    private val paths: AppPaths,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val library: ManagedLibrary,
    private val items: ImportItemStore,
    private val pipeline: ImportPipeline,
    private val content: ContentStore,
    private val chunker: Chunker,
    private val index: () -> LuceneIndex,
    private val documentEmbedder: () -> DocumentEmbedder?,
    private val maxChunksPerDocument: Int,
) : JobHandler {

    override suspend fun handle(job: Job, stage: JobStage) {
        val payload = ImportJobPayload.decode(job.payload)
        val collectionId = CollectionId(payload.collectionId)
        // The payload names the collection, and the attempt refuses to run if the database does not agree:
        // an import into a collection being deleted, or into one that no longer exists, must not quietly
        // deposit documents somewhere else.
        val collection = collections.get(collectionId)
            ?: throw IllegalArgumentException("no collection with id ${collectionId.value} exists")
        if (collection.lifecycle != CollectionLifecycle.ACTIVE) {
            throw CollectionNotActiveException(collectionId)
        }

        val sources = enumerate(payload.sources)
        stage.reportProgress(completed = 0, total = sources.size)

        var completed = 0
        for (source in sources) {
            process(job, collection, payload.settings, source, stage)
            completed++
            stage.reportProgress(completed, sources.size)
        }
    }

    /**
     * One source file: copy it if it is new, then extract what the type allows.
     *
     * Every durable step is its own stage, so a collection deletion or an index rebuild waits for one
     * bounded unit at a time instead of for a whole directory.
     */
    private suspend fun process(
        job: Job,
        collection: Collection,
        settings: ExtractionSettings,
        source: ImportSource,
        stage: JobStage,
    ) {
        val item = stage.run(STAGE_QUEUE) { items.queue(job.id, source.key, source.path.toString()) }
        if (isAlreadyImported(item)) return

        // The item's stored document is consulted before the source path is: an earlier attempt may have put
        // the bytes in the managed library before the user moved or deleted the file, and that stored copy
        // is what the resume has to read.
        val attached = attachDocument(job, collection, source, item, stage) ?: return
        val document = attached.document
        val fingerprint = ExtractionFingerprint.of(document.sha256, settings)

        val mediaType = pipeline.detector.detect(attached.managedPath)
        val extractor = try {
            pipeline.registry.select(mediaType.value)
        } catch (unsupported: UnsupportedMediaTypeException) {
            recordFailure(
                job = job,
                source = source,
                stage = stage,
                document = document,
                code = unsupported.code,
                message = "the pipeline has no extractor for ${mediaType.value}",
            )
            return
        }

        if (!pipeline.sink.storesUnits) {
            // No durable unit store exists yet, so the extraction phase is an explicit no-op: the document
            // is stored, detected, and left in EXTRACTING rather than being reported as readable.
            stage.run(STAGE_RECORD) { documents.updateStatus(document.id, DocumentStatus.EXTRACTING) }
            stage.run(STAGE_RECORD) {
                items.record(job.id, source.key, attached.outcome, document.id)
            }
            return
        }

        stage.run(STAGE_RECORD) { documents.updateStatus(document.id, DocumentStatus.EXTRACTING) }

        val input = ExtractionInput(
            documentId = document.id,
            managedPath = attached.managedPath,
            artifactRoot = paths.artifactsDir(collection.id, document.id),
            settings = settings,
            fingerprint = fingerprint,
            committedUnitKeys = pipeline.sink.committedKeys(document.id, fingerprint),
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
                    is ExtractionEvent.UnitReady -> Unit
                }
                pipeline.sink.deliver(document.id, fingerprint, event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notActive: CollectionNotActiveException) {
            // The collection is being deleted. That is not this file's problem: the job is over and the
            // runner records it as such.
            throw notActive
        } catch (failure: Exception) {
            recordFailure(
                job = job,
                source = source,
                stage = stage,
                document = document,
                code = codeFor(failure),
                message = failure.message ?: "the extractor failed without a message",
            )
            return
        }

        if (!finished) {
            // The extractor stopped without saying it delivered everything: a refusal it recognised before
            // it had a unit to name, or an abort in the middle. What it did commit stays committed — a page
            // read before the abort is still evidence — but the document is reported as failed rather than as
            // extracted with warnings, because nothing about it is complete enough to search.
            val code = lastFailureCode
                ?: content.loadCheckpoints(document.id, fingerprint).lastOrNull { !it.succeeded }?.errorCode
                ?: EXTRACTION_FAILED
            recordFailure(job, source, stage, document, code = code, message = messageFor(code))
            return
        }

        chunkContent(document, stage)
        if (!embedAndIndex(job, collection, document, source, stage)) {
            // The failure is recorded per item inside embedAndIndex; nothing further may overwrite the
            // item's outcome with `attached.outcome` (which would turn a failed document into an
            // "imported" one that a later resume walked past).
            return
        }
        stage.run(STAGE_RECORD) { items.record(job.id, source.key, attached.outcome, document.id) }
    }

    /**
     * Turns the document's persisted chunks into vectors and publishes them to the search index.
     *
     * This is where ingestion stops being "read this file" and becomes "make it searchable". Embedding
     * runs chunk by chunk inside the mutation permit (the expensive half, and the half that may need the
     * accelerator); the index publication is a single replacement of the document's chunks followed by one
     * commit, done under its own permit after a final lifecycle recheck, and only then is the document
     * marked complete. Cancel before that commit and nothing is durable: the resume replays from the
     * persisted chunks without re-reading the source or re-running OCR.
     */
    private suspend fun embedAndIndex(
        job: Job,
        collection: Collection,
        document: Document,
        source: ImportSource,
        stage: JobStage,
    ): Boolean {
        val embedder = documentEmbedder()
        if (embedder == null) {
            recordFailure(
                job = job,
                source = source,
                stage = stage,
                document = document,
                code = ModelManager.MODEL_NOT_INSTALLED_CODE,
                message = ModelManager.installRemedy(),
            )
            return false
        }

        try {
            // The index publishes a single replacement per document, so every row lives in one in-memory
            // list until the commit. A document's rows are bounded by its chunks; the ceiling turns "however
            // large a real archive can get" into a documented limit a later resume can out-grow. It is
            // checked inside the failure mapping so an over-ceiling document is recorded per item with an
            // actionable code, and before any embedding work or index publication.
            val chunkCount = content.chunkCount(document.id)
            if (chunkCount > maxChunksPerDocument) {
                throw EmbeddingException(
                    INDEX_TOO_LARGE,
                    "the document has $chunkCount chunks, which exceeds the " +
                        "$maxChunksPerDocument that an import may publish in one index transaction",
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
        } catch (failure: Exception) {
            val code = when (failure) {
                is GpuUnavailableException -> GpuRuntime.GPU_UNAVAILABLE_CODE
                is EmbeddingException -> failure.code
                else -> EMBEDDING_FAILED
            }
            recordFailure(
                job = job,
                source = source,
                stage = stage,
                document = document,
                code = code,
                message = failure.message ?: "the document could not be embedded",
            )
            return false
        }
    }

    /**
     * Splits every unit of a finished extraction into embeddable chunks, one bounded unit per stage.
     *
     * The pass reads the units back out of the store rather than chunking them as they are extracted, which
     * is what lets re-chunking happen without re-reading the document: another tokenizer or another passage
     * budget rebuilds the chunks from the text that is already there and touches neither the text nor the OCR
     * checkpoints.
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
                        .addKeyValue(COMPONENT_FIELD, IMPORT_COMPONENT)
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
     * The document this item's bytes belong to, copied if they are not stored yet.
     *
     * Returns `null` when there is nothing left to do, which is the case of a file whose bytes are already
     * stored *and* whose document is finished: re-importing a document that is already searchable is a
     * duplicate, not a reason to extract it again.
     */
    private suspend fun attachDocument(
        job: Job,
        collection: Collection,
        source: ImportSource,
        item: ImportItem,
        stage: JobStage,
    ): Attached? {
        // An earlier attempt already attached a document to this item. That is the resume path: pick up
        // the stored bytes rather than reading a source file the user may have moved since.
        val owned = item.documentId?.let { documents.get(it) }
        if (owned != null) {
            val managedPath = library.managedPathOf(owned)
            if (Files.exists(managedPath)) {
                return Attached(owned, managedPath, duplicate = true, item = item)
            }
            // The documented crash window: a row whose bytes never landed. The row is the stale half, and
            // the item's reference to it is cleared by the foreign key when it goes.
            stage.run(STAGE_COPY) { documents.delete(owned.id) }
        }

        // Nothing usable is stored for this item, so the source file is the only way in -- and it may be
        // gone. That is the item's result rather than a reason to skip it: a file the user selected and that
        // no longer exists is something the user has to see. A stored document that is still there never
        // reaches this point, which is what lets a moved or deleted source be finished from the copy.
        if (!source.exists) {
            recordFailure(job, source, stage, document = null, code = SOURCE_MISSING, message = GONE_MESSAGE)
            return null
        }

        val imported = try {
            stage.run(STAGE_COPY) { library.importFile(collection.id, source.path) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notActive: CollectionNotActiveException) {
            throw notActive
        } catch (failure: Exception) {
            // One file's failure is one file's failure, whichever way the copy failed: a file that cannot be
            // read and a managed library that cannot lay the bytes down are both this item's result, not the
            // job's. Leaving only `IOException` isolated would make an unforeseen copy failure abandon every
            // file after it, which is the opposite of what an archive of a thousand files needs.
            recordFailure(
                job = job,
                source = source,
                stage = stage,
                document = null,
                code = if (failure is IOException) SOURCE_UNREADABLE else COPY_FAILED,
                message = failure.message ?: "the file could not be copied",
            )
            return null
        }

        val updated = stage.run(STAGE_QUEUE) {
            items.attachDocument(job.id, source.key, imported.document.id)
        }
        val duplicate = imported.outcome == ManagedImportOutcome.DUPLICATE
        if (duplicate && imported.document.status in FINISHED_STATUSES) {
            stage.run(STAGE_QUEUE) {
                items.record(job.id, source.key, ImportItemOutcome.DUPLICATE, imported.document.id)
            }
            return null
        }
        return Attached(imported.document, imported.managedPath, duplicate, updated)
    }

    private suspend fun recordFailure(
        job: Job,
        source: ImportSource,
        stage: JobStage,
        document: Document?,
        code: String,
        message: String,
    ) {
        stage.run(STAGE_RECORD) {
            items.record(
                jobId = job.id,
                itemKey = source.key,
                outcome = ImportItemOutcome.FAILED,
                documentId = document?.id,
                errorCode = code,
                errorMessage = message,
            )
            if (document != null) {
                documents.updateStatus(document.id, DocumentStatus.FAILED, code, message)
            }
        }
    }

    private fun isAlreadyImported(item: ImportItem): Boolean =
        item.outcome == ImportItemOutcome.IMPORTED &&
            item.documentId != null &&
            documents.get(item.documentId) != null

    /** What an item's bytes became, and whether they were already stored. */
    private data class Attached(
        val document: Document,
        val managedPath: Path,
        val duplicate: Boolean,
        val item: ImportItem,
    ) {

        val outcome: ImportItemOutcome
            get() = if (duplicate) ImportItemOutcome.DUPLICATE else ImportItemOutcome.IMPORTED
    }

    /**
     * One unit of extraction, under the same permit discipline as every other mutation: the stage asserts
     * that the attempt may still continue, and the permit is released when the unit's event has been
     * delivered and committed.
     */
    private class StageBoundary(private val stage: JobStage) : UnitBoundary {

        override suspend fun <T> unit(block: suspend () -> T): T = stage.run(STAGE_EXTRACT) { block() }
    }

    /** One file to import, identified by the path it resolves to. */
    private data class ImportSource(val key: String, val path: Path, val exists: Boolean)

    /**
     * The files a request names, in a stable order and without duplicates.
     *
     * Directories are walked without following symbolic links, because a link inside a tree is a second
     * name for something the tree already contains — following it would import one document twice and
     * attribute it to a path the user never named. A symbolic link the user names *explicitly* is different:
     * it is resolved once, and the resolved path is what the item records, so the same file named twice
     * under two names is one item.
     */
    private fun enumerate(sources: List<String>): List<ImportSource> {
        val byKey = LinkedHashMap<String, ImportSource>()
        sources.forEach { raw ->
            val given = Path.of(raw)
            when {
                Files.isRegularFile(given) || Files.isSymbolicLink(given) -> {
                    val resolved = runCatching { given.toRealPath() }
                        .getOrElse { given.toAbsolutePath().normalize() }
                    if (Files.isRegularFile(resolved)) {
                        byKey.putIfAbsent(resolved.toString(), ImportSource(resolved.toString(), resolved, true))
                    } else {
                        val key = given.toAbsolutePath().normalize().toString()
                        byKey.putIfAbsent(key, ImportSource(key, given, false))
                    }
                }

                Files.isDirectory(given) -> {
                    // Walked from the resolved directory, so every file the walk reports has the same
                    // canonical form a named file does -- otherwise the same document named twice, once
                    // directly and once through its directory, would produce two items on a filesystem
                    // that reaches the same tree by two paths.
                    val root = runCatching { given.toRealPath() }.getOrElse { given.toAbsolutePath().normalize() }
                    regularFilesUnder(root).forEach { file ->
                        byKey.putIfAbsent(file.toString(), ImportSource(file.toString(), file, true))
                    }
                }

                else -> {
                    val key = given.toAbsolutePath().normalize().toString()
                    byKey.putIfAbsent(key, ImportSource(key, given, false))
                }
            }
        }
        return byKey.values.toList()
    }

    private fun regularFilesUnder(directory: Path): List<Path> {
        val found = mutableListOf<Path>()
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile) found.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found.sortedBy { it.toString() }
    }

    /**
     * How an extractor failure is reported to the user.
     *
     * Only the failure the extractor itself classifies is inferred here. Everything else stays a failure
     * with its own message, so a defect inside an extractor cannot be dressed up as a problem with the
     * user's request; a code for a result the pipeline expected is declared by whatever produces it.
     */
    private fun codeFor(failure: Exception): String = when (failure) {
        is UnsupportedMediaTypeException -> failure.code
        else -> EXTRACTION_FAILED
    }

    /**
     * What a refusal code means to the person who has to act on it.
     *
     * A code alone is a word; the item's message is where the remedy lives. Only the codes whose remedy is
     * not obvious are spelled out. Anything else keeps a generic sentence, because a sentence invented for a
     * code nobody documented would be a guess presented as an explanation — the code itself is still stored
     * beside it for whoever needs to look it up.
     */
    private fun messageFor(code: String): String = when (code) {
        DOCUMENT_TOO_LARGE_CODE ->
            "the document is larger than the pipeline reads in one piece, so nothing was extracted"

        ENCRYPTED_DOCUMENT_CODE ->
            "the document is password-protected or DRM-encrypted, and InfoScry does not decrypt documents"

        DOCUMENT_UNREADABLE_CODE ->
            "the container could not be opened: its bytes do not match what the file says it is"

        TesseractOcr.NEEDS_TESSERACT_CODE ->
            "the OCR tool (Tesseract) is not installed, so pages without a text layer cannot be read"

        ModelManager.MODEL_NOT_INSTALLED_CODE -> ModelManager.installRemedy()

        GpuRuntime.GPU_UNAVAILABLE_CODE -> GpuRuntime.remedy()

        CalibreConverter.NEEDS_CALIBRE_CODE ->
            "this e-book format needs the Calibre converter, which is not installed"

        OCR_FAILED_CODE ->
            "the OCR tool ran but could not read part of this document; the rest of it was extracted"

        else -> "the extractor stopped before it delivered every unit of this document"
    }

    companion object {

        /** Where a verified CoreML session writes its one profile, under the data directory's temp root. */
        private const val STAGE_QUEUE = "queue"
        private const val STAGE_COPY = "copy"
        private const val STAGE_EXTRACT = "extract"
        private const val STAGE_CHUNK = "chunk"
        private const val STAGE_EMBED = "embed"
        private const val STAGE_INDEX = "index"
        private const val STAGE_RECORD = "record"
        private const val EMBEDDING_FAILED = "EMBEDDING_FAILED"
        private const val SOURCE_MISSING = "SOURCE_MISSING"
        private const val SOURCE_UNREADABLE = "SOURCE_UNREADABLE"
        private const val COPY_FAILED = "COPY_FAILED"
        private const val EXTRACTION_FAILED = "EXTRACTION_FAILED"
        private const val GONE_MESSAGE = "the file was gone before the import reached it"

        /** How many units one read of the chunking walk takes, so a large document stays interruptible. */
        private const val CHUNK_BATCH = 64

        /** How many chunks one embedding step holds at once, so a long document stays interruptible. */
        private const val EMBED_BATCH = 64

        /**
         * The largest document one import may publish in a single index transaction.
         *
         * Each published row carries its chunk text (bounded by the 512-token chunker, a few KB) plus a
         * 768-float vector (3072 bytes), so 50 000 rows are on the order of 250-300 MB of heap held for one
         * document — generous, and far past what a realistic single document (≈ 25 million tokens) reaches.
         * It is deliberately explicit rather than silent: an over-ceiling document is refused with an
         * actionable code before any embedding work, and its chunks stay durable so raising the ceiling
         * later lets the same resume re-embed without re-extraction or OCR.
         */
        const val MAX_CHUNKS_PER_DOCUMENT: Int = 50_000

        /** The document was refused before embedding because it exceeds [MAX_CHUNKS_PER_DOCUMENT]. */
        private const val INDEX_TOO_LARGE = "INDEX_TOO_LARGE"

        private const val COMPONENT_FIELD = "component"
        private const val DOCUMENT_FIELD = "document_id"
        private const val ORDINAL_FIELD = "unit_ordinal"
        private const val IMPORT_COMPONENT = "import"

        /** A document with this status has nothing left to extract. */
        private val FINISHED_STATUSES = setOf(
            DocumentStatus.COMPLETE,
            DocumentStatus.COMPLETE_WITH_WARNINGS,
        )

        /**
         * Wires the import worker for one open data directory and starts it.
         *
         * This is the only place that knows how an import attempt is put together, so the server and a
         * foreground command cannot end up running two different import pipelines against one archive.
         */
        fun attachTo(
            context: AppContext,
            pipeline: ImportPipeline = ImportPipeline.production(context),
            // The pinned model's own tokenizer measures a passage, so what the chunker fits and what the
            // embedder consumes are the same measurement. It is built on first use, so a machine that has
            // not installed the model yet still serves requests and reports the remedy per document.
            chunker: Chunker = Chunker(
                E5Embedder.productionCounter(
                    modelsDir = context.paths.modelsDir,
                    profileDirectory = context.paths.embeddingProfileDir,
                ),
            ),
            // The document embedder, resolved lazily and per process, and null when the model is not
            // installed. Tests inject a deterministic fake here; production uses the pinned model.
            documentEmbedder: () -> DocumentEmbedder? = E5Embedder.productionDocumentEmbedder(
                modelsDir = context.paths.modelsDir,
                profileDirectory = context.paths.embeddingProfileDir,
            ),
            // A document may hold at most this many chunks in a single index transaction's in-memory rows.
            maxChunksPerDocument: Int = MAX_CHUNKS_PER_DOCUMENT,
            reindexService: (AppContext) -> ReindexService = ::productionReindexService,
        ): JobRunner {
            val handler = ImportJobHandler(
                paths = context.paths,
                collections = context.collections,
                documents = context.documents,
                library = context.library,
                items = context.importItems,
                pipeline = pipeline,
                content = context.content,
                chunker = chunker,
                index = { context.index() },
                documentEmbedder = documentEmbedder,
                maxChunksPerDocument = maxChunksPerDocument,
            )
            val runner = JobRunner(
                store = context.jobs,
                collections = context.collections,
                mutations = context.mutations,
                handler = DispatchingJobHandler(
                    mapOf(
                        JobType.IMPORT to handler,
                        JobType.REINDEX to ReindexJobHandler(reindexService(context)),
                    ),
                ),
            )
            context.attachJobRunner(runner)
            runner.start()
            return runner
        }

        /**
         * The rebuild the process runs with: the pinned model's identity, the tokenizer the import
         * stage measures with, and the index accessor and publication hook that make the swap land in
         * this process.
         */
        private fun productionReindexService(context: AppContext): ReindexService = ReindexService(
            mutations = context.mutations,
            collections = context.collections,
            documents = context.documents,
            content = context.content,
            chunker = Chunker(
                E5Embedder.productionCounter(
                    modelsDir = context.paths.modelsDir,
                    profileDirectory = context.paths.embeddingProfileDir,
                ),
            ),
            paths = context.paths,
            identity = AppContext.identityForCurrentModel(),
            documentEmbedder = E5Embedder.productionDocumentEmbedder(
                modelsDir = context.paths.modelsDir,
                profileDirectory = context.paths.embeddingProfileDir,
            ),
            current = { context.index() },
            publish = { next, previous -> context.publishGeneration(next, previous) },
        )
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.import")
