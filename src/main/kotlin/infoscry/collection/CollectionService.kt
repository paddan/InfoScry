package infoscry.collection

import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.jobs.IgnorePatterns
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.requireOcrLanguages
import infoscry.ocr.OcrProfileRole
import infoscry.ocr.OcrProfileService
import infoscry.ocr.ReadingMethod
import infoscry.storage.CollectionConfirmationMismatchException
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DeletionKind
import infoscry.storage.DeletionOperation
import infoscry.storage.DeletionPhase
import infoscry.storage.DeletionStore
import infoscry.storage.Instants
import infoscry.storage.MutationCoordinator
import infoscry.storage.OcrProfileStore
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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
 * Removes a collection's search entries. Task 16 supplies the Lucene implementation; until an index
 * exists, the phase is an explicit no-op.
 *
 * The seam exists because deletion must be recoverable when index removal fails: a test has to be able
 * to make that step fail without corrupting a real index.
 */
fun interface CollectionIndexRemover {

    suspend fun removeCollection(collectionId: CollectionId)

    companion object {
        /** Used until an index exists. Deleting a collection is complete without it. */
        val NONE: CollectionIndexRemover = CollectionIndexRemover { }
    }
}

/**
 * A collection deletion cannot be finished without risking the files it parked, so every mutating
 * command is refused until an operator resolves the recorded error.
 *
 * This is deliberately not a crash: reads, source viewing and diagnostics keep working, which is what
 * an operator needs in order to inspect the state that blocked the deletion.
 */
class DeletionRecoveryBlockedException(val operations: List<DeletionOperation>) : IllegalStateException(
    "an unfinished deletion cannot be recovered safely: " +
        operations.joinToString("; ") { operation ->
            "${operation.kind.name.lowercase()} " +
                "${operation.targets.joinToString { it.documentId.value }.ifEmpty { operation.collectionId.value }} " +
                "is ${operation.phase} (${operation.lastError ?: "no error recorded"})"
        } + ". Mutating commands are refused until the parked files are resolved; " +
        "nothing has been discarded.",
) {

    /**
     * The sentence a client may see: the same refusal, with the error *code* instead of the recorded
     * message.
     *
     * The message above is for whoever reads the log, and it names managed and parked directories inside
     * the data directory. None of that belongs on the wire — a client is told which deletion stopped and
     * what it stopped on, and [DeletionOperation.errorCode] is the stable vocabulary for the rest.
     */
    val clientMessage: String =
        "an unfinished deletion cannot be recovered safely: " +
            operations.joinToString("; ") { operation ->
                "${operation.kind.name.lowercase()} " +
                    "${operation.targets.joinToString { it.documentId.value }.ifEmpty { operation.collectionId.value }} " +
                    "is ${operation.phase.name} (${operation.errorCode ?: "no error code recorded"})"
            } + ". Mutating commands are refused until the parked files are resolved; " +
            "nothing has been discarded. See the InfoScry log for the detail."
}

/**
 * Continuing a deletion could lose files: what the record says and what is on disk disagree, so the
 * operation stops where it is and mutations are refused until an operator resolves it.
 *
 * [errorCode] is the part a client may see; [message] keeps the paths an operator needs in the log.
 */
class UnsafeDeletionRecoveryException(val errorCode: String, message: String) : IllegalStateException(message)

/** What a startup roll-forward did: which operations finished, and which are stuck and why. */
data class DeletionRecoveryReport(
    val recovered: List<DeletionOperation>,
    val blocked: List<DeletionOperation>,
)

/**
 * One instant in a deletion: the phase the operation is in or is about to enter, and whether that phase
 * is already recorded.
 *
 * `recorded = false` marks the instants a crash is hardest to recover from — the step's side effect has
 * happened but the phase that makes it durable has not been written yet. The deletion-recovery harness
 * stops a real child process at one of these instants to prove the next startup can still tell what
 * happened. Every phase whose side effect and phase write are one transaction (the row deletion) has no
 * such instant, because it cannot be observed from outside by construction.
 */
internal data class DeletionStep(val phase: DeletionPhase, val recorded: Boolean)

/**
 * One OCR-settings edit, as a request states it.
 *
 * Both fields are optional so a request can change just the language or just the default reading method.
 * A missing field keeps its current value; the engine and profile stay bundled in [ReadingMethod].
 */
data class OcrSettingsUpdate(
    val language: String? = null,
    val defaultMethod: ReadingMethod? = null,
) {

    /**
     * The settings this edit asks for, given what the collection has now.
     *
     * Its own function so the "absent keeps the old value" rule has exactly one implementation, and so what an
     * edit amounts to can be stated without writing it.
     */
    fun appliedTo(current: CollectionOcrSettings): CollectionOcrSettings = CollectionOcrSettings(
        language = language?.let { languages -> requireOcrLanguages(languages) }
            ?: current.language,
        defaultMethod = defaultMethod ?: current.defaultMethod,
    )
}

/**
 * Collections: the one place that changes them, and the state machine that removes one.
 *
 * Deletion is a durable, resumable operation rather than a sequence of calls, because it spans three
 * places that cannot be changed together — files on disk, rows in SQLite, and entries in the search
 * index. Every phase is recorded before the next one starts, so a process that dies anywhere between
 * them is resumed by [recoverDeletions] instead of leaving an archive that is half deleted.
 *
 * Ordinary mutations go through [MutationCoordinator.withMutation] and are refused while maintenance
 * runs. Deletion itself is the exclusive maintenance operation, so its steps call the stores directly:
 * the maintenance scope is already the only writer.
 */
class CollectionService(
    private val database: Database,
    private val paths: AppPaths,
    private val collections: CollectionStore,
    private val deletions: DeletionStore,
    private val coordinator: MutationCoordinator,
    /**
     * The profile rules a settings save is validated against.
     *
     * Defaulted from the same database so a caller that constructs this service by hand (a recovery test,
     * a tool that only renames collections) does not have to wire a second object to get a working store.
     */
    private val ocrProfiles: OcrProfileService = OcrProfileService(OcrProfileStore(database)),
    private val index: CollectionIndexRemover = CollectionIndexRemover.NONE,
    // Shared with document deletion: an unsafe state of either kind refuses the same mutations.
    private val blockers: DeletionBlockers = DeletionBlockers(),
) : AutoCloseable {

    /**
     * Owns the deletions this process admitted and has not finished.
     *
     * Deliberately not the request's coroutine: a deletion outlives the HTTP response that admitted it,
     * so a caller that goes away — or a response that never arrives — must not be able to cancel it.
     * What is still unfinished when the process stops stays durable for [recoverDeletions].
     */
    private val deletionsInProgress = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The background roll-forward of the operation this process admitted last, if any. */
    private var runningDeletion: kotlinx.coroutines.Job? = null

    /** Deletions that could not be recovered; while non-empty, every mutation is refused. */
    val blockedDeletions: List<DeletionOperation> get() = blockers.operations

    // ---- Reads. No permit: they touch neither the managed files nor the index writer. ----

    /**
     * The usable collections, each carrying how many documents it holds. A collection mid-deletion
     * is not one of them.
     */
    fun list(): List<Collection> =
        collections.listWithDocumentCounts().filter { it.lifecycle == CollectionLifecycle.ACTIVE }

    fun get(id: CollectionId): Collection? =
        collections.get(id)?.takeIf { it.lifecycle == CollectionLifecycle.ACTIVE }

    /**
     * The collection work may be aimed at, or a failure naming why it may not.
     *
     * Every import and read path that names a collection goes through this: a tombstoned collection
     * must not receive new documents after its rows are gone, and after the tombstone the row may not
     * exist at all, so "not found" and "being deleted" are the same answer to a caller.
     */
    fun requireActive(id: CollectionId): Collection =
        get(id) ?: throw NoSuchElementException(
            "no usable collection with id ${id.value}; it does not exist or its deletion is in progress",
        )

    /**
     * The collection a person meant, named either by its identifier or by its name.
     *
     * Both spellings exist because both are natural: a script has the identifier, and a person typing a
     * command has the name. Names are matched case-insensitively, the way the store rejects duplicates,
     * so "nightfall" and "Nightfall" are not two different answers.
     */
    fun requireActiveByNameOrId(reference: String): Collection {
        require(reference.isNotBlank()) { "a collection reference must not be blank" }
        val byId = get(CollectionId(reference))
        if (byId != null) return requireActive(byId.id)
        val byName = list().firstOrNull { it.name.equals(reference, ignoreCase = true) }
        return requireActive(
            byName?.id ?: throw NoSuchElementException(
                "no usable collection named or identified by '$reference'",
            ),
        )
    }

    // ---- Ordinary mutations ----

    suspend fun create(
        name: String,
        description: String? = null,
        ocrLanguages: String = CollectionStore.DEFAULT_OCR_LANGUAGES,
    ): Collection = coordinator.withMutation {
        requireMutationsAllowed()
        collections.create(name, description, ocrLanguages)
    }

    suspend fun rename(id: CollectionId, newName: String): Collection = coordinator.withMutation {
        requireMutationsAllowed()
        requireActive(id)
        collections.rename(id, newName)
    }

    /** Updates the settings future import jobs snapshot; already queued payloads remain unchanged. */
    suspend fun updateOcrLanguages(id: CollectionId, ocrLanguages: String): Collection = updateOcrSettings(
        id,
        OcrSettingsUpdate(language = ocrLanguages),
    )

    /**
     * Saves a collection's OCR settings.
     *
     * Validated in this order: the collection has to be usable, and an explicitly selected LLM profile has
     * to exist and be enabled. A profile carried over from the saved default is not re-checked, because it is
     * not this request's claim: a profile disabled after a collection chose it must not freeze every other
     * edit to that collection. Whether the effective settings are still usable is decided when work is
     * admitted, where the profiles are resolved again into a snapshot.
     */
    suspend fun updateOcrSettings(id: CollectionId, update: OcrSettingsUpdate): Collection =
        coordinator.withMutation {
            requireMutationsAllowed()
            val existing = requireActive(id)
            val settings = update.appliedTo(existing.ocrSettings())
            if (settings.defaultMethod != existing.ocrSettings().defaultMethod) {
                (settings.defaultMethod as? ReadingMethod.Llm)?.let { method ->
                    ocrProfiles.requireSelectable(method.profileId, OcrProfileRole.TRANSCRIPTION)
                }
            }
            collections.updateOcrSettings(id, settings)
        }

    /**
     * The collection's saved ignore patterns. A read like the others here: it takes no permit, and it answers
     * only for a usable collection.
     */
    fun ignorePatterns(id: CollectionId): IgnorePatterns {
        requireActive(id)
        return IgnorePatterns(collections.ignorePatterns(id))
    }

    /**
     * Replaces the collection's ignore patterns with [lines].
     *
     * The list is validated here, when it is saved, so an invalid pattern is refused with the collection untouched
     * and is never first met by an import. It changes future imports only: a queued import carries the list it was
     * admitted with.
     */
    suspend fun updateIgnorePatterns(id: CollectionId, lines: List<String>): IgnorePatterns =
        coordinator.withMutation {
            requireMutationsAllowed()
            requireActive(id)
            val validated = IgnorePatterns.of(lines)
            collections.replaceIgnorePatterns(id, validated.patterns)
            validated
        }

    // ---- Deletion reads ----

    /**
     * One deletion operation by its identifier, terminal ones included, or null when no such operation
     * exists.
     *
     * It answers after the collection's row is gone, because the record outlives the collection: a
     * caller that was told "this deletion is running" has to be able to read how it ended.
     */
    fun deletionOperation(id: String): DeletionOperation? = deletions.get(id)

    /**
     * The deletions that still need work, of either kind, oldest first: what a reopened Admin shows as
     * in progress. The reads are kind-agnostic because the operation read is the one surface both kinds
     * share.
     */
    fun unfinishedDeletions(): List<DeletionOperation> = deletions.listUnfinished()

    // ---- Deletion ----

    /**
     * Deletes one collection and everything InfoScry holds for it, in the caller's coroutine, and
     * answers with the finished operation. External source files are not touched: they were never
     * InfoScry's to remove.
     *
     * The API admits through [requestDeletion] instead, because a response must not wait for the whole
     * deletion. This form is what a caller that has to know the deletion finished uses — and what the
     * tests that interrupt a real deletion at a chosen instant drive.
     */
    suspend fun deleteConfirmed(collectionId: CollectionId, confirmName: String): DeletionOperation =
        deleteConfirmed(collectionId, confirmName) { }

    /**
     * Admits one deletion durably and returns as soon as that admission is recorded, leaving the
     * destructive work to a coroutine this service owns.
     *
     * This is what the API answers with: the tombstone, the cancellation requests and the operation
     * record are durable before the caller is told anything, so a lost response, a disconnected caller,
     * or this process being killed cannot lose the deletion — what is left of it is finished by the
     * roll-forward started here or by [recoverDeletions] at the next startup.
     *
     * A deletion already admitted for this collection is that operation, not a second one: a repeated
     * request answers with the same id and phase, which is what makes a repeated confirmation
     * idempotent. The confirmation is still checked while the collection's row exists.
     */
    suspend fun requestDeletion(collectionId: CollectionId, confirmName: String): DeletionOperation {
        requireMutationsAllowed()
        val existing = deletions.unfinishedFor(collectionId).firstOrNull()
        if (existing != null) {
            collections.get(collectionId)?.let { collection -> requireConfirmation(collection.name, confirmName) }
            return existing
        }

        val admitted = CompletableDeferred<DeletionOperation>()
        runningDeletion = deletionsInProgress.launch {
            try {
                coordinator.withExclusiveMaintenance("delete collection ${collectionId.value}") {
                    requireMutationsAllowed()
                    val operation = beginDeletion(collectionId, confirmName)
                    // The caller's answer is the durable admission, not the finished deletion: the
                    // phases below take minutes and nothing about them belongs to this request.
                    admitted.complete(operation)
                    finishDeletion(operation)
                }
            } catch (failure: Throwable) {
                // Before admission this is the caller's failure (a taken name, a maintenance run, an
                // unknown collection) and travels as one; after it, the caller already has its answer
                // and the operation stays unfinished for the next attempt.
                admitted.completeExceptionally(failure)
                if (failure is CancellationException) throw failure
            }
        }
        return admitted.await()
    }

    /**
     * Runs what is left of [operation] and records why it stopped, if it did.
     *
     * Never throws: this runs after the caller's answer, so a failure is a state to record rather than
     * something to report. The phase still says what is true on disk, and the next startup resumes it.
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

    /**
     * Records why [operation] could not continue, and refuses mutations when the state is unsafe.
     *
     * A failure that only stopped a phase is work to retry; a disagreement between the record and the
     * disk is not, and it is the one that must stop every other mutating command until an operator has
     * looked at it — exactly as a blocked startup recovery does.
     */
    private fun recordFailure(operation: DeletionOperation, failure: Exception) {
        val code = if (failure is UnsafeDeletionRecoveryException) failure.errorCode else DELETION_FAILED_CODE
        val message = failure.message ?: failure::class.simpleName ?: "unknown failure"
        runCatching { deletions.recordError(operation.id, code, message) }
        if (code == UNSAFE_RECOVERY_CODE) {
            val current = runCatching { deletions.get(operation.id) }.getOrNull() ?: operation
            blockers.record(current)
        }
    }

    /** Stops deletions this process was running; what is left of them stays durable for the next start. */
    override fun close() {
        deletionsInProgress.cancel()
        runBlocking { runningDeletion?.join() }
    }

    /**
     * [deleteConfirmed] with the observation seam the deletion-recovery harness stops a child process
     * through.
     *
     * The seam is internal and has no production caller: the tests that prove recovery need to interrupt
     * the real deletion at a named instant, and the alternative — a harness that drives the individual
     * steps itself — would spell the phase order a second time and could keep passing while exercising a
     * sequence production no longer runs.
     */
    internal suspend fun deleteConfirmed(
        collectionId: CollectionId,
        confirmName: String,
        observe: suspend (DeletionStep) -> Unit,
    ): DeletionOperation =
        coordinator.withExclusiveMaintenance("delete collection ${collectionId.value}") {
            requireMutationsAllowed()
            rollForward(beginDeletion(collectionId, confirmName), observe)
        }

    /**
     * Finishes every deletion that was started but not completed, and reports which ones are stuck.
     *
     * Startup runs this before serving requests or admitting jobs, so no caller can see a collection
     * that is half deleted.
     */
    suspend fun recoverDeletions(): DeletionRecoveryReport {
        val recovered = mutableListOf<DeletionOperation>()
        val stuck = mutableListOf<DeletionOperation>()

        coordinator.withExclusiveMaintenance("recover collection deletions") {
            for (operation in deletions.listUnfinished(DeletionKind.COLLECTION)) {
                try {
                    recovered += rollForward(operation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // The phase still describes what is true on disk, so the operation stays where it
                    // is and the next attempt retries the same step. Recording the reason is best
                    // effort: if the database is the thing that failed, the phase alone carries it.
                    recordFailure(operation, failure)
                    stuck += deletions.get(operation.id) ?: operation
                }
            }
        }

        blockers.replace(DeletionKind.COLLECTION, stuck)
        return DeletionRecoveryReport(recovered = recovered, blocked = stuck)
    }

    /**
     * Records the tombstone for [collectionId] and returns the operation to resume.
     *
     * Idempotent: called again for a collection whose deletion is already recorded, it returns the
     * existing operation rather than starting a second one.
     */
    internal fun beginDeletion(collectionId: CollectionId, confirmName: String): DeletionOperation {
        val collection = collections.get(collectionId)
            ?: throw NoSuchElementException("no collection with id ${collectionId.value}")
        requireConfirmation(collection.name, confirmName)
        deletions.unfinishedFor(collectionId).firstOrNull()?.let { return it }

        val now = Instants.now()
        val operation = DeletionOperation(
            id = UUID.randomUUID().toString(),
            collectionId = collectionId,
            collectionName = collection.name,
            trashBasename = TRASH_PREFIX + UUID.randomUUID(),
            managedOriginalsExisted = Files.isDirectory(paths.collectionDir(collectionId)),
            phase = DeletionPhase.PREPARED,
            createdAt = now,
            updatedAt = now,
        )
        return deletions.begin(operation)
    }

    /**
     * Runs whatever is left of [operation], returning it in its final state.
     *
     * The order of the phases lives here and nowhere else. [observe] is called at each instant of the
     * deletion so the recovery harness can stop a child process at one of them; the default does
     * nothing, which is what every production caller uses.
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
                    parkManagedDirectory(current)
                    observe(DeletionStep(DeletionPhase.FILES_MOVED, recorded = false))
                    deletions.advance(current.id, DeletionPhase.FILES_MOVED)
                }

                DeletionPhase.FILES_MOVED -> {
                    assertRecoverable(current)
                    deleteCollectionRows(current)
                }

                DeletionPhase.DB_DELETED -> {
                    removeSearchEntries(current)
                    observe(DeletionStep(DeletionPhase.INDEX_DELETED, recorded = false))
                    deletions.advance(current.id, DeletionPhase.INDEX_DELETED)
                }

                DeletionPhase.INDEX_DELETED -> {
                    purgeTrash(current)
                    observe(DeletionStep(DeletionPhase.DONE, recorded = false))
                    deletions.advance(current.id, DeletionPhase.DONE)
                }

                DeletionPhase.DONE -> current
            }
        }
        return current
    }

    /**
     * Renames the collection's managed directory into the trash. Safe to run twice: a directory that
     * is already parked, or one that never existed, is not an error.
     */
    internal fun parkManagedDirectory(operation: DeletionOperation) {
        val source = paths.collectionDir(operation.collectionId)
        if (!Files.exists(source)) return

        val trash = paths.trashDirectory(operation.trashBasename)
        Files.createDirectories(paths.libraryDir)
        try {
            Files.move(source, trash, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Same filesystem is the normal case, so this is the fallback rather than the expectation.
            Files.move(source, trash)
        }
    }

    /**
     * Removes the collection's rows, its documents and its jobs in one transaction with the phase
     * that records that they are gone, and returns the advanced operation.
     */
    internal fun deleteCollectionRows(operation: DeletionOperation): DeletionOperation =
        database.transaction {
            // Returns false when a previous attempt already removed the rows, which is why a retry is
            // safe. A name mismatch here would mean the collection was renamed while being deleted,
            // which the tombstone prevents.
            collections.delete(operation.collectionId, operation.collectionName)
            // The phase write is inside the same transaction as the deletion it records: an observer
            // outside it can never see rows that are gone while the phase still says they are not.
            deletions.advance(operation.id, DeletionPhase.DB_DELETED)
        }

    /**
     * Removes the collection's search entries. Before an index exists this is an explicit no-op.
     */
    internal suspend fun removeSearchEntries(operation: DeletionOperation) {
        index.removeCollection(operation.collectionId)
    }

    /**
     * Removes the parked directory once the rows and index entries are durably gone. An absent trash
     * directory is success, not a failure: it means a previous attempt already purged it.
     */
    internal fun purgeTrash(operation: DeletionOperation) {
        val trash: Path = paths.trashDirectory(operation.trashBasename)
        if (!Files.exists(trash)) return

        Files.walkFileTree(
            trash,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * Refuses to continue when the recorded state and the disk disagree in a way that could lose files.
     *
     * Two directories mean the rename happened without the phase that records it; no directory at all
     * means either that there was never one (which the record says) or that files were lost. Files are
     * never discarded to make a deletion look finished.
     */
    private fun assertRecoverable(operation: DeletionOperation) {
        val managed = paths.collectionDir(operation.collectionId)
        val trash = paths.trashDirectory(operation.trashBasename)
        val managedExists = Files.exists(managed)
        val trashExists = Files.exists(trash)

        if (managedExists && trashExists) {
            throw UnsafeDeletionRecoveryException(
                UNSAFE_RECOVERY_CODE,
                "both the managed directory $managed and its parked copy $trash exist for collection " +
                    "${operation.collectionId.value}; refusing to delete either before an operator " +
                    "decides which one holds the documents",
            )
        }
        if (!managedExists && !trashExists && operation.managedOriginalsExisted) {
            throw UnsafeDeletionRecoveryException(
                UNSAFE_RECOVERY_CODE,
                "the managed directory for collection ${operation.collectionId.value} is missing " +
                    "from both $managed and $trash, but the deletion record says it held managed " +
                    "originals; refusing to finish the deletion and lose them silently",
            )
        }
    }

    /** The exact name is the confirmation; the store's own check is what a refused deletion reports. */
    private fun requireConfirmation(collectionName: String, confirmName: String) {
        if (collectionName != confirmName.trim()) {
            throw CollectionConfirmationMismatchException(collectionName, confirmName)
        }
    }

    /** Refuses unrelated settings writes too when startup deletion recovery is blocked. */
    fun requireMutationsAllowed() {
        blockers.requireMutationsAllowed()
    }

    internal companion object {
        /** Distinguishes parked directories from collection directories in the same parent. */
        const val TRASH_PREFIX = ".deleted-"

        /** The record and the disk disagree; files are preserved and mutations are refused. */
        const val UNSAFE_RECOVERY_CODE = "UNSAFE_RECOVERY"

        /** A phase could not be completed; the operation stays at its durable phase for a retry. */
        const val DELETION_FAILED_CODE = "DELETION_FAILED"
    }
}
