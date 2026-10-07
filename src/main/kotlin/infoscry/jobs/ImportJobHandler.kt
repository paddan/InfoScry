package infoscry.jobs

import infoscry.AppContext
import infoscry.chunk.Chunker
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.E5Embedder
import infoscry.embedding.GpuRuntime
import infoscry.embedding.ModelManager
import infoscry.embedding.GpuUnavailableException
import infoscry.search.LuceneIndex
import infoscry.search.ReindexService
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobType
import infoscry.domain.Job
import infoscry.extract.CalibreConverter
import infoscry.extract.DOCUMENT_TOO_LARGE_CODE
import infoscry.extract.DOCUMENT_UNREADABLE_CODE
import infoscry.extract.ENCRYPTED_DOCUMENT_CODE
import infoscry.extract.OCR_FAILED_CODE
import infoscry.extract.ExtractionSettings
import infoscry.extract.TesseractOcr
import infoscry.library.ManagedImportOutcome
import infoscry.library.ManagedLibrary
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.SuryaOcr
import infoscry.ocr.ocrReviewerFactory
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentStore
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import infoscry.storage.ImportItemStore
import infoscry.storage.JobStore
import io.ktor.client.engine.HttpClientEngine
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
 * - **Reading is not enough to call a document done.** Extraction is one unit at a time through the shared
 *   boundary [`DocumentIngest`] holds, so the mutation permit covers exactly that unit, and a document is
 *   left in `EXTRACTING`: indexing and embedding decide when it is complete, and they are somebody else's
 *   task.
 */
class ImportJobHandler internal constructor(
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val library: ManagedLibrary,
    private val items: ImportItemStore,
    /**
     * The attempt-level wiring this handler and the retry share: how one job-owned external page scope
     * becomes the dispatch authority a page is sent through, and how a snapshotted reviewer profile becomes
     * the review a staged page is judged by — the same allowance for every file of the job and the same
     * reviewer rules a rescan uses, so there is no second wiring of either in this class.
     */
    private val attemptDispatch: AttemptDispatch,
    /** The job's own row, which is where a wait for an external page approval is written durably. */
    private val jobs: JobStore,
    /**
     * Where an attached managed copy is read into searchable content. Shared with the retry attempt, so
     * "read this document's bytes again" cannot mean two different things depending on how the document was
     * reached — only whether failed units are revisited differs, and the caller says which it wants.
     */
    private val ingest: DocumentIngest,
    /**
     * The instant between a file's last document write and its per-item result, called once per file.
     *
     * That instant is where a document deletion can still race the item write: the file's document is
     * already written, and the deletion's durable disposition may already have marked the item cancelled
     * and removed the document's row. It is a seam because only a test can hold an attempt exactly there
     * — it lands a deletion in the window and proves the item write obeys the disposition instead of
     * failing the job. No production caller passes it.
     */
    private val afterIngestion: suspend () -> Unit = {},
    /**
     * The instant between a file's item being read and its bytes being copied, called once per file.
     *
     * It is the window a deletion admission needs to be landed in to test the interleaving this handler
     * has to survive: the item was read as pending, and admission cancels it by source path while the
     * copy is still ahead. Only a test can hold the attempt exactly there. No production caller passes it.
     */
    private val beforeCopy: suspend () -> Unit = {},
    /**
     * The instant between a file's attach and its reading, called once per file.
     *
     * It is the window the verifier's interleaving needs: the file's document has been copied and attached
     * (so a document exists for the path), the deletion is admitted, and the reading that follows would
     * publish what the deletion decided against. Only a test can hold the attempt exactly there. No
     * production caller passes it.
     */
    private val afterAttach: suspend () -> Unit = {},
    /**
     * The instant between a file's copy committing and its attach, called once per file.
     *
     * It is the crash window a document deletion has to survive: a document exists for the file's path and
     * the item has not been attached to it yet, and the deletion can be admitted exactly there. Only a test
     * can hold an attempt at that instant. No production caller passes it.
     */
    private val afterCopy: suspend () -> Unit = {},
    /**
     * Removes a document this attempt created itself, when the deletion cancelled the file it was copied
     * for.
     *
     * It is a seam because removing it spans what the handler does not own — the search index, the row and
     * its cascade, and the bytes on disk — and the composition root is what wires those together. The
     * implementation never touches another item, another document or another collection.
     */
    private val discardCreatedDocument: suspend (CollectionId, DocumentId) -> Unit = { _, _ -> },
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

        val sources = enumerate(payload.sources, payload.recursive)
        stage.reportProgress(completed = 0, total = sources.size)

        var completed = 0
        for (source in sources) {
            try {
                process(job, collection, payload, source, stage)
            } catch (deleted: DocumentBeingDeletedException) {
                // One document was removed while this file was being worked on. That is only this file's
                // outcome: its item is durably marked CANCELLED so no later attempt copies the deleted
                // document again, and the remaining files of the import keep running.
                stage.run(STAGE_RECORD) { items.cancelTargeting(job.id, deleted.documentId) }
            } catch (waiting: AttemptAwaitingApproval) {
                // A page would have exceeded this job's external scope, and it was not sent. The waiting
                // state is durable and the attempt ends here rather than reading the next file: no page of
                // any file may be dispatched until a person approves the scope. The files this attempt
                // already imported are skipped when the approved job runs again, so the wait costs no work.
                stage.run(STAGE_RECORD) { jobs.progress(job.id, stage = JobStore.AWAITING_APPROVAL_STAGE) }
                LOGGER.atInfo()
                    .addKeyValue(JOB_ID_FIELD, job.id.value)
                    .log("an import waits for an external page scope to be approved before another page is sent")
                return
            }
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
        payload: ImportJobPayload,
        source: ImportSource,
        stage: JobStage,
    ) {
        val settings = payload.settings
        // The file's own name, reported before anything is done with it, so a reader watching the import
        // sees which of the selected files the wait belongs to. Only the name: the path it was selected
        // from is the archive's own bookkeeping and never crosses the API boundary.
        stage.reportCurrentItem(sourceNameOf(source.path))
        val item = stage.run(STAGE_QUEUE) { items.queue(job.id, source.key, source.path.toString()) }
        // The durable disposition, read before anything else is done: a file whose document was deleted
        // is skipped on this attempt and on every resume, which is what stops the deleted target from
        // being copied again from an old source item.
        if (item.outcome == ImportItemOutcome.CANCELLED) return
        if (isAlreadyImported(item)) return

        // Where a deletion and this file interleave: the item is read, the bytes are not copied yet. Nothing
        // below trusts that read — the durable row is read again under the permit that copies and under the
        // permit that attaches — so this seam exists only to put a test's deletion exactly here.
        beforeCopy()

        // The item's stored document is consulted before the source path is: an earlier attempt may have put
        // the bytes in the managed library before the user moved or deleted the file, and that stored copy
        // is what the resume has to read.
        val attached = attachDocument(job, collection, source, item, stage) ?: return
        val document = attached.document

        // Where a deletion and this file interleave once the file's document exists: the bytes are in the
        // managed library and the item names them, and the reading has not begun. The deletion owns the
        // path, so what it decides below decides for this file too.
        afterAttach()

        val result = ingest.ingest(
            collection = collection,
            settings = settings,
            document = document,
            managedPath = attached.managedPath,
            stage = stage,
            // An import resumes rather than retries: a unit an earlier attempt already failed is a known
            // result and is skipped, which is what the sink's `committedKeys` answers. An explicit Retry asks
            // for the opposite and reaches the same code through the retry handler.
            revisitFailedUnits = false,
            onFailure = { code, message -> recordFailure(job, source, stage, document, code, message) },
            // The page allowance this file's pages leave under: the *job's*, because twenty files share one
            // scope, and this attempt's, because nothing may be dispatched before the scope was approved.
            dispatch = attemptDispatch.authorityFor(
                job = job,
                document = document,
                snapshot = payload.ocr,
                dispatchStage = OcrDispatchStage.TRANSCRIPTION,
            ),
            // How this file's staged pages are judged, or null when the attempt has no reviewer: the page's
            // own text is compared with the engine's reading while the draft is in hand, and the review is
            // recorded for a person.
            review = attemptDispatch.stagedPageReviewOf(
                job = job,
                document = document,
                collection = collection,
                snapshot = payload.ocr,
            ),
        )
        // A document deletion can land here: the file's document is written and its item is not. Nothing
        // below may fail the attempt because of that — the item writes obey the deletion's disposition
        // (see `ImportItemStore`), so this file ends cancelled and the rest of the import keeps running.
        afterIngestion()
        when (result) {
            // A staged reading is this file's outcome in the same sense a published one is: the bytes are
            // stored, the reading is durable and the item records it once. What differs is the document,
            // whose status `ingest` has already written from the reading's own outcome — complete when no
            // page of it still owes a decision, review-pending otherwise.
            IngestResult.Complete, IngestResult.Staged -> stage.run(STAGE_RECORD) {
                items.record(job.id, source.key, attached.outcome, document.id)
            }

            IngestResult.NoUnitStore -> {
                // No durable unit store exists, so the extraction phase is an explicit no-op: the document
                // is stored, detected, and left in EXTRACTING rather than being reported as readable.
                stage.run(STAGE_RECORD) { documents.updateStatus(document.id, DocumentStatus.EXTRACTING) }
                stage.run(STAGE_RECORD) {
                    items.record(job.id, source.key, attached.outcome, document.id)
                }
            }

            // The failure is recorded per item inside the reporter; nothing further may overwrite the item's
            // outcome with `attached.outcome`, which would turn a failed document into an "imported" one that
            // a later resume walked past.
            IngestResult.Failed -> Unit
        }

        // The disposition, read once more now the reading has finished: a deletion admitted while this file
        // was being read cancels it, and a document this attempt created for the cancelled file may not stay
        // live — reading a file the deletion decided against and then publishing it is the same resurrection
        // by another route. A document that already existed (`duplicate`) is never removed: it belongs to
        // whatever imported it first, and this attempt only found it.
        if (!attached.duplicate && cancelledSinceRead(job, source, stage)) {
            discard(collection.id, document.id)
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
            if (documents.isDeletionTarget(owned.id)) return cancelTargeting(job, stage, owned.id)
            val managedPath = library.managedPathOf(owned)
            if (Files.exists(managedPath)) {
                // The item was read before this file was reached, and the deletion may have cancelled it in
                // between: reading the stored bytes of a cancelled file would put the deleted path back into
                // the index just as surely as copying them would.
                if (cancelledSinceRead(job, source, stage)) return null
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

        // The item read at the start of this file is a snapshot, and the deletion cancels the item by source
        // path: the durable row is therefore read *inside the copy's own permit*, immediately before the
        // bytes are read. Admission cannot commit while that permit is held, so the copy below is decided by
        // a disposition that is still true, and a cancelled file is never copied at all.
        val copy = try {
            stage.run(STAGE_COPY) {
                if (cancelledSinceReadInPermit(job, source)) {
                    null
                } else {
                    library.importFile(
                        collectionId = collection.id,
                        source = source.path,
                        // The link between this file and what it made is written by the copy's own commit, so
                        // it survives everything that can happen next: a refused attach, a kill, or a deletion
                        // admitted the moment the copy returns. Without it the document a copy created would be
                        // findable by nobody but this attempt's own in-memory state.
                        onDocumentCreated = { connection, documentId ->
                            items.noteCreatedDocument(connection, job.id, source.key, documentId)
                        },
                    )
                }
            }
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
        // The deletion cancelled the file while it waited for the permit: nothing was copied and there is
        // nothing to attach, read or report.
        val imported = copy ?: return null

        // The one instant where a document exists for this path and this file has not yet claimed it in its
        // own bookkeeping: a deletion admitted here cancels the item, and the attach below is refused. The
        // copy transaction already wrote the durable link, which is what lets the deletion still account for
        // this document; this seam is where a test puts it. Nothing in production passes it.
        afterCopy()

        val updated = stage.run(STAGE_QUEUE) {
            items.attachDocument(
                jobId = job.id,
                itemKey = source.key,
                documentId = imported.document.id,
                // The copy's own answer: a deletion of this path may only remove what this file *made*.
                createdByThisCopy = imported.outcome == ManagedImportOutcome.CREATED,
            )
        }
        val duplicate = imported.outcome == ManagedImportOutcome.DUPLICATE
        // A deletion can also land *while* the bytes are being copied, and the row just read back is what
        // says whether it did: the attach itself refuses to write to an item admission dispositioned. An item
        // the deletion cancelled is not attached, not ingested and not reported as imported, and a document
        // this attempt created for it is removed again rather than left to be read and published — the copy
        // finished after the deletion decided against the file, so the file leaves nothing behind.
        if (updated.outcome == ImportItemOutcome.CANCELLED) {
            if (!duplicate) discard(collection.id, imported.document.id)
            return null
        }
        // The bytes already existed (a duplicate) and belong to a document that is being removed: the
        // item is dispositioned rather than attached to it, and the parked directory is the deletion's to
        // purge. The guard is re-read here because the copy and the deletion can race, which is exactly
        // the window this check closes.
        if (documents.isDeletionTarget(imported.document.id)) return cancelTargeting(job, stage, imported.document.id)
        if (duplicate && imported.document.status in FINISHED_STATUSES) {
            stage.run(STAGE_QUEUE) {
                items.record(job.id, source.key, ImportItemOutcome.DUPLICATE, imported.document.id)
            }
            return null
        }
        return Attached(imported.document, imported.managedPath, duplicate, updated)
    }

    /**
     * Dispositions one file as `CANCELLED` because its document is being deleted, and ends the file.
     *
     * The item's own row is the durable record, so a later attempt skips the file even when the document
     * row is already gone and the item's reference to it has been cleared.
     */
    private suspend fun cancelTargeting(job: Job, stage: JobStage, documentId: DocumentId?): Nothing? {
        if (documentId != null) stage.run(STAGE_RECORD) { items.cancelTargeting(job.id, documentId) }
        return null
    }

    /**
     * Whether the deletion has cancelled this file since it was read, as its own bounded stage.
     *
     * The item a file's processing was handed is a snapshot from before the file was reached, and admission
     * cancels the item by source path. This is the read that has to happen again as late as possible: in
     * the copy's own permit for the copy (see `attachDocument`), and under a bounded stage of its own for
     * the attach, for the stored bytes of a resumed file and for the result. Admission cannot commit while
     * such a permit is held, so a read taken inside one is a disposition that is still true when the work
     * it guards begins.
     */
    private suspend fun cancelledSinceRead(job: Job, source: ImportSource, stage: JobStage): Boolean =
        stage.run(STAGE_QUEUE) { cancelledSinceReadInPermit(job, source) }

    /** The disposition as it stands right now; the caller must already hold a permit. */
    private fun cancelledSinceReadInPermit(job: Job, source: ImportSource): Boolean =
        items.find(job.id, source.key)?.outcome == ImportItemOutcome.CANCELLED

    /**
     * Removes the document this attempt created, because the deletion cancelled the file it was copied for.
     *
     * Only this attempt's own copy is removed. A document that already existed belongs to whatever imported
     * it first, and a document the deletion targeted belongs to that deletion's own machine, which is already
     * parking and purging it — two removals racing would undo each other's work. Nothing else is touched: not
     * another item, not another document, not another collection.
     */
    private suspend fun discard(collectionId: CollectionId, documentId: DocumentId) {
        if (documents.isDeletionTarget(documentId)) return
        discardCreatedDocument(collectionId, documentId)
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
                documents.updateStatus(document.id, statusForFailureCode(code), code, message)
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

    /** One file to import, identified by the path it resolves to. */
    /**
     * The file's own last segment, whichever platform's separator its path carries.
     *
     * [Path.fileName] follows the platform's separator rules, so on macOS a Windows-style path typed into
     * the manual fallback would be reported whole. The API's promise is the file's name and never the
     * directories it sits in, so both separators are honoured — the same derivation the item view uses.
     */
    private fun sourceNameOf(path: Path): String {
        val text = path.toString()
        return text.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotEmpty) ?: text
    }

    private data class ImportSource(val key: String, val path: Path, val exists: Boolean)

    /**
     * The files a request names, in a stable order and without duplicates.
     *
     * Directories are walked without following symbolic links, because a link inside a tree is a second
     * name for something the tree already contains — following it would import one document twice and
     * attribute it to a path the user never named. A symbolic link the user names *explicitly* is different:
     * it is resolved once, and the resolved path is what the item records, so the same file named twice
     * under two names is one item.
     *
     * A directory is only read down to its own files unless [recursive] says otherwise: importing a
     * directory without `--recursive` takes what is directly inside it, and takes the whole tree with it.
     */
    private fun enumerate(sources: List<String>, recursive: Boolean): List<ImportSource> {
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
                    regularFilesUnder(root, recursive).forEach { file ->
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

    /**
     * The regular files under [directory], sorted by path string for a stable import order.
     *
     * One walk serves both modes: with [recursive] the walk descends the whole tree, and without it the
     * walk's maximum depth is the directory's own level, so only the files directly inside it are found.
     * The attributes come from the walk either way, which keeps the two modes identical about symbolic
     * links: a link is read as a link, never followed.
     */
    private fun regularFilesUnder(directory: Path, recursive: Boolean): List<Path> {
        val found = mutableListOf<Path>()
        Files.walkFileTree(
            directory,
            setOf(),
            if (recursive) Int.MAX_VALUE else 1,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile) found.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found.sortedBy { it.toString() }
    }

    companion object {

        /**
         * The status a whole-document failure leaves behind.
         *
         * A missing tool is not a broken document: the bytes, the collection and the archive are all fine
         * and one thing has to be installed. That gets `NEEDS_TOOL`, so a listing can say what the document
         * needs and Retry can honestly offer to read it again once the tool is there. Everything else is
         * `FAILED`, because the document itself is what could not be read.
         *
         * The four Surya runtime failures are that same shape: a Python environment, a `llama-server` and a
         * set of weights are three things to install, and a person who selected Surya and did not install it
         * has a document that is waiting, not a document that is broken.
         */
        internal fun statusForFailureCode(code: String): DocumentStatus =
            if (code == TesseractOcr.NEEDS_TESSERACT_CODE || code == CalibreConverter.NEEDS_CALIBRE_CODE ||
                code in SuryaOcr.UNAVAILABLE_CODES
            ) {
                DocumentStatus.NEEDS_TOOL
            } else {
                DocumentStatus.FAILED
            }

        /**
         * What a refusal code means to the person who has to act on it.
         *
         * A code alone is a word; the message is where the remedy lives. Only the codes whose remedy is not
         * obvious are spelled out. Anything else keeps a generic sentence, because a sentence invented for a
         * code nobody documented would be a guess presented as an explanation — the code itself is still
         * stored beside it for whoever needs to look it up. Every sentence here is InfoScry's own, which is
         * what makes this safe to serve over the API: the message beside an item's stored outcome may be an
         * exception's own words (a tool's complaint about a page, a path, a pointer into a document), and
         * those are diagnostics for the machine that ran the import. That stored text is what the CLI reads
         * directly, and an unrecognised code served here gets the generic sentence instead of it.
         */
        internal fun messageFor(code: String): String =
            // The Surya runtime failures get their words from the engine that raises them, so the sentence a
            // person reads in the queue and the one a failed page carries cannot drift apart.
            SuryaOcr.remedyFor(code) ?: when (code) {
            SOURCE_MISSING -> GONE_MESSAGE

            SOURCE_UNREADABLE -> "the file could not be read from disk"

            COPY_FAILED -> "the file could not be copied into the library"

            UNSUPPORTED_MEDIA_TYPE -> "the pipeline has no extractor for this kind of file"

            DocumentIngest.EMBEDDING_FAILED -> "the document could not be embedded and published to the search index"

            DocumentIngest.INDEX_TOO_LARGE ->
                "the document has more passages than one import may publish; import it in smaller pieces"

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

        /** Where a verified CoreML session writes its one profile, under the data directory's temp root. */
        private const val STAGE_QUEUE = "queue"
        private const val STAGE_COPY = "copy"
        private const val STAGE_RECORD = "record"
        private const val SOURCE_MISSING = "SOURCE_MISSING"
        private const val SOURCE_UNREADABLE = "SOURCE_UNREADABLE"
        private const val COPY_FAILED = "COPY_FAILED"
        private const val GONE_MESSAGE = "the file was gone before the import reached it"

        const val JOB_ID_FIELD = "job_id"
        const val DISTINCT_PAGES_FIELD = "distinct_external_pages"
        const val ALLOWANCE_FIELD = "external_page_allowance"

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

        private const val UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE"

        private const val COMPONENT_FIELD = "component"
        private const val DOCUMENT_FIELD = "document_id"

        /**
         * A document with this status has nothing left to extract.
         *
         * `NEEDS_REVIEW` is one of them because such a document *has* been read: its bytes are stored, its
         * reading is on record, and what it owes is a decision no re-reading can answer. Importing the same
         * bytes again is therefore a duplicate rather than a reason to read a document that is already
         * waiting — and the reading a second pass would produce is exactly what a person has still to decide
         * about, so it could not answer the question either.
         *
         * The retry handler reuses this same set for the same reason: a document an earlier run of its job
         * already finished must not be read again when the job resumes.
         */
        internal val FINISHED_STATUSES = setOf(
            DocumentStatus.COMPLETE,
            DocumentStatus.COMPLETE_WITH_WARNINGS,
            DocumentStatus.NEEDS_REVIEW,
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
            // The instant between a file's last document write and its per-item result, for the test that
            // lands a document deletion exactly there. Nothing in production passes it.
            afterIngestion: suspend () -> Unit = {},
            // The instant between a file's item being read and its bytes being copied, for the test that
            // lands a deletion exactly there. Nothing in production passes it.
            beforeCopy: suspend () -> Unit = {},
            // The instant between a file's attach and its reading, for the test that lands a deletion
            // exactly there. Nothing in production passes it.
            afterAttach: suspend () -> Unit = {},
            // The instant between a file's copy committing and its attach, for the test that lands a
            // deletion exactly there. Nothing in production passes it.
            afterCopy: suspend () -> Unit = {},
            // The transport engine the attempt's image-model clients are built on, or null for each
            // client's own CIO engine. Production passes nothing (its default); a test injects a
            // recording engine here so an external transcription or review dispatch can be observed
            // without leaving the machine. The client still applies its own redirect and key rules to
            // whatever transport it is built on.
            clientEngine: HttpClientEngine? = null,
        ): JobRunner {
            // A restore re-embeds with the session this worker embeds with, so one process never holds two
            // accelerator sessions for the same pinned model.
            context.attachDocumentEmbedder(documentEmbedder)
            // One reader of managed copies, shared by both attempts: an import reaches its document by
            // copying a source file, a retry through an identifier it already had, and from there on the
            // reading is the same work.
            val ingest = DocumentIngest(
                paths = context.paths,
                collections = context.collections,
                documents = context.documents,
                pipeline = pipeline,
                content = context.content,
                chunker = chunker,
                index = { context.index() },
                documentEmbedder = documentEmbedder,
                maxChunksPerDocument = maxChunksPerDocument,
                revisions = context.revisions,
                // The same publication service a rescan publishes its candidate through: an import's staged
                // reading is a revision like any other, and a second way to publish one would be a second
                // answer to what an unapproved page costs.
                publication = context.revisionPublication,
            )
            val attemptDispatch = AttemptDispatch(
                operations = context.ocrOperations,
                profileRevisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
                paths = context.paths,
                // The same reviewer factory a rescan runs with: an import's staged page, a retry's and a
                // rescan's are judged by one set of rules, and a second wiring here would be a second answer
                // to what a reviewer may do.
                reviewerFor = ocrReviewerFactory(
                    revisions = context.revisions,
                    reviews = context.ocrReviews,
                    profiles = context.ocrProfiles,
                    clientEngine = clientEngine,
                ),
            )
            val handler = ImportJobHandler(
                collections = context.collections,
                documents = context.documents,
                library = context.library,
                items = context.importItems,
                attemptDispatch = attemptDispatch,
                jobs = context.jobs,
                ingest = ingest,
                afterIngestion = afterIngestion,
                beforeCopy = beforeCopy,
                afterAttach = afterAttach,
                afterCopy = afterCopy,
                discardCreatedDocument = { collectionId, documentId ->
                    discardCreatedDocument(context, collectionId, documentId)
                },
            )
            val retryHandler = RetryJobHandler(
                collections = context.collections,
                documents = context.documents,
                jobs = context.jobs,
                library = context.library,
                mutations = context.mutations,
                attemptDispatch = attemptDispatch,
                ingest = ingest,
            )
            // A rescan is its own kind of work, so it has its own handler. It is wired here because this is
            // the one place that knows how an attempt against this data directory is put together — the
            // engines, the embedder, the chunker and the publication service are the same ones an import uses,
            // and a second composition root would be a second answer to what a page image is read with.
            val rescanHandler = RescanJobHandler(
                paths = context.paths,
                collections = context.collections,
                documents = context.documents,
                library = context.library,
                mutations = context.mutations,
                jobs = context.jobs,
                revisions = context.revisions,
                operations = context.ocrOperations,
                reviews = context.ocrReviews,
                publication = context.revisionPublication,
                chunker = chunker,
                documentEmbedder = documentEmbedder,
                engineFor = rescanEngineFactory(context.ocrProfiles, clientEngine = clientEngine),
                reviewerFor = ocrReviewerFactory(
                    revisions = context.revisions,
                    reviews = context.ocrReviews,
                    profiles = context.ocrProfiles,
                    clientEngine = clientEngine,
                ),
                profileRevisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
            )
            val runner = JobRunner(
                store = context.jobs,
                collections = context.collections,
                mutations = context.mutations,
                handler = DispatchingJobHandler(
                    mapOf(
                        JobType.IMPORT to handler,
                        JobType.RETRY to retryHandler,
                        JobType.REINDEX to ReindexJobHandler(reindexService(context)),
                        JobType.RESCAN to rescanHandler,
                    ),
                ),
            )
            context.attachJobRunner(runner)
            runner.start()
            return runner
        }

        /**
         * Removes a document an attempt created itself, after a deletion cancelled the file it was copied
         * for.
         *
         * The order is deliberate. The search entries go first, so a file cancelled after it was published
         * stops being findable; then the row, whose cascade owns the units, chunks, checkpoints and progress;
         * then the bytes and artifacts this attempt laid down. Every step is idempotent, and every step is
         * best effort: the file is cancelled already, and a cleanup that could not finish may not fail the
         * rest of an import. What could not be removed is logged rather than swallowed silently.
         *
         * The document is this attempt's own: nothing has cited it, and the item that would have named it is
         * cancelled. No other document, item or collection is touched — a document a deletion targeted is the
         * deletion machine's to remove, and the caller never gets here for one.
         */
        private suspend fun discardCreatedDocument(
            context: AppContext,
            collectionId: CollectionId,
            documentId: DocumentId,
        ) {
            runCatching { context.index().deleteDocument(documentId) }.onFailure { failure ->
                LOGGER.atWarn()
                    .addKeyValue(DOCUMENT_FIELD, documentId.value)
                    .setCause(failure)
                    .log("a cancelled file's search entries could not be removed")
            }
            runCatching { context.documents.delete(documentId) }.onFailure { failure ->
                LOGGER.atWarn()
                    .addKeyValue(DOCUMENT_FIELD, documentId.value)
                    .setCause(failure)
                    .log("a cancelled file's document row could not be removed")
            }
            removeTree(context.paths.documentDir(collectionId, documentId), documentId)
            removeTree(context.paths.artifactsDir(collectionId, documentId), documentId)
        }

        /** Removes one of a cancelled file's directories, tolerating a tree that is already gone. */
        private fun removeTree(directory: Path, documentId: DocumentId) {
            if (!Files.isDirectory(directory)) return
            runCatching {
                Files.walk(directory).use { entries ->
                    entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }.onFailure { failure ->
                LOGGER.atWarn()
                    .addKeyValue(DOCUMENT_FIELD, documentId.value)
                    .setCause(failure)
                    .log("a cancelled file's directory could not be removed")
            }
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
            revisions = context.revisions,
        )
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.import")
