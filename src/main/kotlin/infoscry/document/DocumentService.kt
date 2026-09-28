package infoscry.document

import infoscry.collection.DeletionBlockers
import infoscry.collection.DeletionRecoveryReport
import infoscry.collection.DeletionStep
import infoscry.collection.UnsafeDeletionRecoveryException
import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.DocumentId
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DeletionKind
import infoscry.storage.DeletionOperation
import infoscry.storage.DeletionPhase
import infoscry.storage.DeletionStore
import infoscry.storage.DocumentDeletionTarget
import infoscry.storage.DocumentStore
import infoscry.storage.Instants
import infoscry.storage.MutationCoordinator
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Removes a document's search entries. Until an index exists this is an explicit no-op, and the seam
 * exists so a test can make the step fail without corrupting a real index.
 */
fun interface DocumentIndexRemover {

    suspend fun removeDocument(documentId: DocumentId)

    companion object {
        /** Used when no index is wired; deleting a document is complete without it. */
        val NONE: DocumentIndexRemover = DocumentIndexRemover { }
    }
}

/**
 * Deleting chosen documents from one collection, durably.
 *
 * This is the document half of the deletion machine the collection service owns. It is the same shape —
 * park files, delete rows, remove index entries, purge — because the failure modes are the same: the
 * work spans files on disk, rows in SQLite and entries in the search index, and no two of them can be
 * changed together. What differs is the scope:
 *
 * - **Only the chosen documents are removed.** An import job may hold other files, so nothing here
 *   cancels a job. The items that hold a targeted document get a durable `CANCELLED` disposition, and a
 *   resumed attempt obeys it; the other items keep running.
 * - **The guard is a durable row, not a flag in memory.** [DocumentStore.updateStatus] and every other
 *   write boundary reads the target row, which outlives the document it names, so a stage that resumes
 *   after the deletion finished still refuses to write the document back.
 * - **Admission is durable before the answer.** The target rows, the operation and the item dispositions
 *   are one transaction; the destructive phases then belong to a coroutine this service owns, so a
 *   request that disconnects or a process that dies cannot lose the work.
 */
class DocumentService(
    private val database: Database,
    private val paths: AppPaths,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val deletions: DeletionStore,
    private val coordinator: MutationCoordinator,
    private val blockers: DeletionBlockers,
    private val index: DocumentIndexRemover = DocumentIndexRemover.NONE,
) : AutoCloseable {

    /**
     * Owns the deletions this process admitted and has not finished, for the same reason the collection
     * service does: a deletion outlives the response that admitted it.
     */
    private val deletionsInProgress = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var runningDeletion: kotlinx.coroutines.Job? = null

    /**
     * Admits one document deletion durably and returns as soon as that admission is recorded.
     *
     * The tombstone — the target rows, the item dispositions and the operation record — is durable
     * before the caller is told anything, so a lost response or a killed process cannot lose the
     * deletion: what is left of it is finished by the coroutine started here or by [recoverDeletions] at
     * the next startup, which runs before jobs are admitted.
     *
     * A repeated confirmation of the same set of documents is that operation, not a second one. The set
     * is compared before the documents are validated, because a retry after their rows are gone must
     * still find the operation it is repeating rather than a 404.
     */
    suspend fun requestDeletion(collectionId: CollectionId, documentIds: List<DocumentId>): DeletionOperation {
        requireMutationsAllowed()
        existingFor(collectionId, documentIds)?.let { return it }

        val admitted = CompletableDeferred<DeletionOperation>()
        runningDeletion = deletionsInProgress.launch {
            try {
                coordinator.withExclusiveMaintenance(
                    "delete ${documentIds.size} document(s) from ${collectionId.value}",
                ) {
                    requireMutationsAllowed()
                    val operation = beginDeletion(collectionId, documentIds)
                    admitted.complete(operation)
                    finishDeletion(operation)
                }
            } catch (failure: Throwable) {
                // Before admission this is the caller's failure (an unknown id, a maintenance run, an
                // unknown collection) and travels as one; after it, the caller already has its answer and
                // the operation stays unfinished for the next attempt.
                admitted.completeExceptionally(failure)
                if (failure is CancellationException) throw failure
            }
        }
        return admitted.await()
    }

    /**
     * Deletes [documentIds] from one collection in the caller's coroutine and answers with the finished
     * operation.
     *
     * The API admits through [requestDeletion] instead, because a response must not wait for the whole
     * deletion. This form is what a caller that has to know the deletion finished uses — and what the
     * tests that interrupt a real deletion at a chosen instant drive.
     */
    suspend fun deleteConfirmed(collectionId: CollectionId, documentIds: List<DocumentId>): DeletionOperation =
        deleteConfirmed(collectionId, documentIds) { }

    /**
     * [deleteConfirmed] with the observation seam the deletion-recovery harness stops a child process
     * through: every instant of the deletion is reported, and the harness kills the process at one.
     */
    internal suspend fun deleteConfirmed(
        collectionId: CollectionId,
        documentIds: List<DocumentId>,
        observe: suspend (DeletionStep) -> Unit,
    ): DeletionOperation =
        coordinator.withExclusiveMaintenance("delete ${documentIds.size} document(s) from ${collectionId.value}") {
            requireMutationsAllowed()
            rollForward(beginDeletion(collectionId, documentIds), observe)
        }

    /** Stops deletions this process was running; what is left of them stays durable for the next start. */
    override fun close() {
        deletionsInProgress.cancel()
        runBlocking { runningDeletion?.join() }
    }

    /**
     * Finishes every document deletion that was started but not completed, and reports which are stuck.
     *
     * Startup runs this before admitting jobs, so no worker can see a document whose row is half gone.
     */
    suspend fun recoverDeletions(): DeletionRecoveryReport {
        val recovered = mutableListOf<DeletionOperation>()
        val stuck = mutableListOf<DeletionOperation>()

        coordinator.withExclusiveMaintenance("recover document deletions") {
            for (operation in deletions.listUnfinished(DeletionKind.DOCUMENT)) {
                try {
                    recovered += rollForward(operation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    recordFailure(operation, failure)
                    stuck += deletions.get(operation.id) ?: operation
                }
            }
        }

        blockers.replace(DeletionKind.DOCUMENT, stuck)
        return DeletionRecoveryReport(recovered = recovered, blocked = stuck)
    }

    /** The mutating commands the shared deletion gate refuses; document and collection states both stop them. */
    fun requireMutationsAllowed() {
        blockers.requireMutationsAllowed()
    }

    /**
     * Records the target rows for [documentIds] and returns the operation to resume.
     *
     * Idempotent, and the validation is deliberately here — inside the maintenance scope and before any
     * side effect — because every id has to belong to the active collection: an unknown id, or one that
     * belongs to another collection, rejects the whole request and records nothing. The caller cannot
     * learn from the refusal which of the two it was, because both are the same not-found answer.
     */
    internal fun beginDeletion(collectionId: CollectionId, documentIds: List<DocumentId>): DeletionOperation {
        val requested = documentIds.distinct()
        require(requested.isNotEmpty()) { "a document deletion needs at least one document" }
        val collection = activeCollection(collectionId)
        existingFor(collectionId, requested)?.let { return it }

        val targets = requested.map { documentId ->
            val document = documents.get(documentId)?.takeIf { it.collectionId == collectionId }
                ?: throw NoSuchElementException(
                    "no document with id ${documentId.value} exists in this collection",
                )
            DocumentDeletionTarget(
                documentId = document.id,
                managedExisted = Files.exists(paths.documentDir(collectionId, document.id)),
                // The path is read here, while the document's row still exists: it is what lets a running
                // operation find and remove the documents a raced import goes on to create from it.
                sourcePath = document.sourcePath,
            )
        }

        val now = Instants.now()
        return deletions.beginDocument(
            DeletionOperation(
                id = UUID.randomUUID().toString(),
                collectionId = collectionId,
                collectionName = collection.name,
                trashBasename = infoscry.collection.CollectionService.TRASH_PREFIX + UUID.randomUUID(),
                managedOriginalsExisted = targets.any { it.managedExisted },
                phase = DeletionPhase.PREPARED,
                kind = DeletionKind.DOCUMENT,
                targets = targets,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Runs whatever is left of [operation], returning it in its final state.
     *
     * The order of the phases lives here and nowhere else. [observe] is called at each instant of the
     * deletion so the recovery harness can stop a child process at one of them; the default does nothing,
     * which is what every production caller uses.
     */
    internal suspend fun rollForward(
        operation: DeletionOperation,
        observe: suspend (DeletionStep) -> Unit = {},
    ): DeletionOperation {
        var current = operation
        while (current.phase != DeletionPhase.DONE) {
            observe(DeletionStep(current.phase, recorded = true))
            current = when (current.phase) {
                DeletionPhase.PREPARED -> {
                    assertRecoverable(current)
                    parkDocumentDirectories(current)
                    observe(DeletionStep(DeletionPhase.FILES_MOVED, recorded = false))
                    deletions.advance(current.id, DeletionPhase.FILES_MOVED)
                }

                DeletionPhase.FILES_MOVED -> {
                    assertRecoverable(current)
                    deleteDocumentRows(current)
                }

                DeletionPhase.DB_DELETED -> {
                    removeSearchEntries(current)
                    observe(DeletionStep(DeletionPhase.INDEX_DELETED, recorded = false))
                    deletions.advance(current.id, DeletionPhase.INDEX_DELETED)
                }

                DeletionPhase.INDEX_DELETED -> {
                    // The last moment the operation owns this collection's writer, and therefore the last
                    // moment a document a target's path led to can be removed without racing whatever
                    // created it: see [sweepDocumentsOnTargetPaths].
                    sweepDocumentsOnTargetPaths(current)
                    purgeParkedDirectories(current)
                    observe(DeletionStep(DeletionPhase.DONE, recorded = false))
                    deletions.advance(current.id, DeletionPhase.DONE)
                }

                DeletionPhase.DONE -> current
            }
        }
        return current
    }

    /**
     * Moves each targeted document's managed directory under the operation's one parked directory.
     *
     * Safe to run twice: a document directory that is already parked, or one that never existed, is not
     * an error. The row removal in the next phase cannot be undone, which is why nothing is removed here
     * — the files are only renamed inside the library, where a failure leaves them recoverable.
     *
     * [targets] exists for the sweep, which adopts documents after the phases have already parked the
     * caller's targets: it does the same work, in the same layout, for the documents it found.
     */
    internal fun parkDocumentDirectories(
        operation: DeletionOperation,
        targets: List<DocumentDeletionTarget> = operation.targets,
    ) {
        val trash = paths.trashDirectory(operation.trashBasename)
        Files.createDirectories(paths.libraryDir)
        targets.forEach { target ->
            val source = paths.documentDir(operation.collectionId, target.documentId)
            if (!Files.exists(source)) return@forEach
            Files.createDirectories(trash)
            val parked = trash.resolve(target.documentId.value)
            try {
                Files.move(source, parked, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, parked)
            }
        }
    }

    /**
     * Removes the targeted documents' rows and the rows that cascade from them in one transaction with
     * the phase that records that they are gone.
     *
     * The cascade is what makes this "the document and its related data": content units, chunks,
     * checkpoints, the finished extraction, the progress row and the chunking record all reference the
     * document with `ON DELETE CASCADE`, and the import items that pointed at it have their reference
     * cleared. The phase write is inside the same transaction as the deletion it records, so an observer
     * outside it can never see rows that are gone while the phase still says they are not.
     */
    internal fun deleteDocumentRows(operation: DeletionOperation): DeletionOperation =
        database.transaction {
            removeDocumentRows(operation.targets)
            deletions.advance(operation.id, DeletionPhase.DB_DELETED)
        }

    /**
     * Deletes [targets]' rows and everything that cascades from them. The phase write is the caller's,
     * because the sweep adopts documents outside the phase order and must not move the phase.
     */
    private fun removeDocumentRows(targets: List<DocumentDeletionTarget>) {
        targets.forEach { target -> documents.delete(target.documentId) }
    }

    /** Removes the documents' search entries, one document at a time; each call commits. */
    internal suspend fun removeSearchEntries(
        operation: DeletionOperation,
        targets: List<DocumentDeletionTarget> = operation.targets,
    ) {
        targets.forEach { target -> index.removeDocument(target.documentId) }
    }

    /**
     * Removes the parked directories once the rows and index entries are durably gone. An absent parked
     * directory is success, not a failure: it means a previous attempt already purged it.
     */
    internal fun purgeParkedDirectories(operation: DeletionOperation) {
        val trash = paths.trashDirectory(operation.trashBasename)
        if (!Files.exists(trash)) return
        Files.walk(trash).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Removes the documents the operation's target paths still lead to, exactly as if the caller had
     * selected them.
     *
     * This is what makes the deletion own a path rather than depend on a file's attempt to notice the
     * deletion: the caller selected a document, and an import that was already in flight can finish
     * copying that document's source path a moment later — under a new identifier, which no target row
     * names — and then read and publish it. A file's own checks are best effort by construction (its
     * attempt can be killed, interrupted or simply be writing when admission lands), so the operation
     * finds those documents itself, in its own last phase, while it holds the only writer in the process
     * and nothing else can be creating anything.
     *
     * Each document is handled exactly like a target, and in the order the phases use, so a crash at any
     * point leaves work a resumed attempt repeats safely: its directory is parked inside the operation's
     * trash (a no-op when it is already parked or never existed), its search entries are removed, and its
     * rows are deleted in the same transaction that records it as one of the operation's targets. That
     * record is the durable half: it is what refuses every later write for the document as "being
     * deleted" — so an attempt still holding it cannot publish it, no matter when it resumes — and it
     * puts the document in the operation's target list, where the shared purge and an operator's reads
     * already account for it.
     *
     * Nothing outside the operation is touched: only this collection's documents, only the paths its own
     * targets were imported from, never a document that was already recorded as a target, and never a
     * document the caller did not cause (a document created before admission by someone else is not the
     * deletion's to remove). The path itself is never refused afterwards: a later explicit import of the
     * same path is an ordinary new document, because nothing consults the record once the operation is
     * `DONE`.
     */
    internal suspend fun sweepDocumentsOnTargetPaths(operation: DeletionOperation): List<DocumentDeletionTarget> {
        val ownedPaths = operation.targets.mapNotNull { it.sourcePath }.distinct()
        if (ownedPaths.isEmpty()) return emptyList()
        val known = operation.targets.map { it.documentId }.toSet()
        val swept = deletions.documentsOnTargetPaths(operation, ownedPaths)
            .filterNot { candidate -> known.contains(candidate.documentId) }
            .map { candidate ->
                DocumentDeletionTarget(
                    documentId = candidate.documentId,
                    managedExisted = Files.exists(paths.documentDir(operation.collectionId, candidate.documentId)),
                    sourcePath = candidate.sourcePath,
                )
            }
        if (swept.isEmpty()) return emptyList()

        parkDocumentDirectories(operation, swept)
        removeSearchEntries(operation, swept)
        database.transaction {
            deletions.recordDerivedTargets(operation.id, swept)
            removeDocumentRows(swept)
        }
        return swept
    }

    /**
     * Refuses to continue when the recorded state and the disk disagree in a way that could lose files.
     *
     * Two directories for one document mean the rename happened without the phase that records it; no
     * directory at all where the record says one existed means files were lost. Files are never discarded
     * to make a deletion look finished, so the operation stops and the whole archive refuses mutations
     * until an operator resolves it.
     */
    private fun assertRecoverable(operation: DeletionOperation) {
        val trash = paths.trashDirectory(operation.trashBasename)
        operation.targets.forEach { target ->
            val managed = paths.documentDir(operation.collectionId, target.documentId)
            val parked = trash.resolve(target.documentId.value)
            val managedExists = Files.exists(managed)
            val parkedExists = Files.exists(parked)

            if (managedExists && parkedExists) {
                throw UnsafeDeletionRecoveryException(
                    infoscry.collection.CollectionService.UNSAFE_RECOVERY_CODE,
                    "both the managed directory $managed and its parked copy $parked exist for document " +
                        "${target.documentId.value}; refusing to delete either before an operator decides " +
                        "which one holds the document",
                )
            }
            if (!managedExists && !parkedExists && target.managedExisted) {
                throw UnsafeDeletionRecoveryException(
                    infoscry.collection.CollectionService.UNSAFE_RECOVERY_CODE,
                    "the managed directory for document ${target.documentId.value} is missing from both " +
                        "$managed and $parked, but the deletion record says it held a managed copy; " +
                        "refusing to finish the deletion and lose it silently",
                )
            }
        }
    }

    /**
     * Runs what is left of [operation] and records why it stopped, if it did.
     *
     * Never throws: this runs after the caller's answer, so a failure is a state to record rather than
     * something to report.
     */
    private suspend fun finishDeletion(operation: DeletionOperation) {
        try {
            rollForward(operation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(operation, failure)
        }
    }

    /** Records why [operation] could not continue, and refuses mutations when the state is unsafe. */
    private fun recordFailure(operation: DeletionOperation, failure: Exception) {
        val code = if (failure is UnsafeDeletionRecoveryException) {
            failure.errorCode
        } else {
            infoscry.collection.CollectionService.DELETION_FAILED_CODE
        }
        val message = failure.message ?: failure::class.simpleName ?: "unknown failure"
        runCatching { deletions.recordError(operation.id, code, message) }
        if (code == infoscry.collection.CollectionService.UNSAFE_RECOVERY_CODE) {
            blockers.record(runCatching { deletions.get(operation.id) }.getOrNull() ?: operation)
        }
    }

    /** The unfinished document deletion whose targets are exactly [documentIds], for a repeated request. */
    private fun existingFor(collectionId: CollectionId, documentIds: List<DocumentId>): DeletionOperation? {
        val requested = documentIds.toSet()
        return deletions.unfinishedFor(collectionId, DeletionKind.DOCUMENT)
            .firstOrNull { operation -> operation.targets.map { it.documentId }.toSet() == requested }
    }

    /** The collection the deletion is aimed at; a tombstoned one is the same not-found answer. */
    private fun activeCollection(collectionId: CollectionId): infoscry.domain.Collection =
        collections.get(collectionId)?.takeIf { it.lifecycle == CollectionLifecycle.ACTIVE }
            ?: throw NoSuchElementException(
                "no usable collection with id ${collectionId.value}; it does not exist or its deletion is in progress",
            )

    /** Where the parked directories live, exposed so tests can name the layout without repeating it. */
    internal fun parkedDirectoryOf(operation: DeletionOperation): Path = paths.trashDirectory(operation.trashBasename)
}
