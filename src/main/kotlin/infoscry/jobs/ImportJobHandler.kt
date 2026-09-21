package infoscry.jobs

import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentStatus
import infoscry.domain.Job
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.ExtractionSink
import infoscry.extract.UnitBoundary
import infoscry.extract.UnsupportedMediaTypeException
import infoscry.library.ManagedImportOutcome
import infoscry.library.ManagedLibrary
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
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
        )

        try {
            // The collector commits inside the boundary's permit: a flow is collected inline, so `emit` does
            // not return until the sink has stored that unit, and the permit is still held while it does.
            pipeline.registry.extract(input, mediaType.value).collect { event ->
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

        stage.run(STAGE_RECORD) { items.record(job.id, source.key, attached.outcome, document.id) }
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

    companion object {

        private const val STAGE_QUEUE = "queue"
        private const val STAGE_COPY = "copy"
        private const val STAGE_EXTRACT = "extract"
        private const val STAGE_RECORD = "record"
        private const val SOURCE_MISSING = "SOURCE_MISSING"
        private const val SOURCE_UNREADABLE = "SOURCE_UNREADABLE"
        private const val COPY_FAILED = "COPY_FAILED"
        private const val EXTRACTION_FAILED = "EXTRACTION_FAILED"
        private const val GONE_MESSAGE = "the file was gone before the import reached it"

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
            pipeline: ImportPipeline = ImportPipeline.production(),
        ): JobRunner {
            val handler = ImportJobHandler(
                paths = context.paths,
                collections = context.collections,
                documents = context.documents,
                library = context.library,
                items = context.importItems,
                pipeline = pipeline,
            )
            val runner = JobRunner(
                store = context.jobs,
                collections = context.collections,
                mutations = context.mutations,
                handler = handler,
            )
            context.attachJobRunner(runner)
            runner.start()
            return runner
        }
    }
}
