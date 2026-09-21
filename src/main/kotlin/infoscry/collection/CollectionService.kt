package infoscry.collection

import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.storage.CollectionConfirmationMismatchException
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DeletionOperation
import infoscry.storage.DeletionPhase
import infoscry.storage.DeletionStore
import infoscry.storage.Instants
import infoscry.storage.MutationCoordinator
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException

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
    "collection deletion cannot be recovered safely: " +
        operations.joinToString("; ") { operation ->
            "collection ${operation.collectionId.value} is ${operation.phase} " +
                "(${operation.lastError ?: "no error recorded"})"
        } + ". Mutating commands are refused until the parked files are resolved; " +
        "nothing has been discarded.",
)

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
    private val index: CollectionIndexRemover = CollectionIndexRemover.NONE,
) {

    @Volatile
    private var blocked: List<DeletionOperation> = emptyList()

    /** Deletions that could not be recovered; while non-empty, every mutation is refused. */
    val blockedDeletions: List<DeletionOperation> get() = blocked

    // ---- Reads. No permit: they touch neither the managed files nor the index writer. ----

    /** The usable collections. A collection mid-deletion is not one of them. */
    fun list(): List<Collection> =
        collections.list().filter { it.lifecycle == CollectionLifecycle.ACTIVE }

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

    // ---- Ordinary mutations ----

    suspend fun create(
        name: String,
        description: String? = null,
        ocrLanguages: String = CollectionStore.DEFAULT_OCR_LANGUAGES,
    ): Collection = coordinator.withMutation {
        assertMutationsAllowed()
        collections.create(name, description, ocrLanguages)
    }

    suspend fun rename(id: CollectionId, newName: String): Collection = coordinator.withMutation {
        assertMutationsAllowed()
        requireActive(id)
        collections.rename(id, newName)
    }

    // ---- Deletion ----

    /**
     * Deletes one collection and everything InfoScry holds for it. External source files are not
     * touched: they were never InfoScry's to remove.
     */
    suspend fun deleteConfirmed(collectionId: CollectionId, confirmName: String): DeletionOperation =
        deleteConfirmed(collectionId, confirmName) { }

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
            assertMutationsAllowed()
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
            for (operation in deletions.listUnfinished()) {
                try {
                    recovered += rollForward(operation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // The phase still describes what is true on disk, so the operation stays where it
                    // is and the next attempt retries the same step. Recording the reason is best
                    // effort: if the database is the thing that failed, the phase alone carries it.
                    val message = failure.message ?: failure::class.simpleName ?: "unknown failure"
                    runCatching { deletions.recordError(operation.id, message) }
                    stuck += deletions.get(operation.id) ?: operation
                }
            }
        }

        blocked = stuck
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
        if (collection.name != confirmName.trim()) {
            throw CollectionConfirmationMismatchException(collection.name, confirmName)
        }
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
            throw IllegalStateException(
                "both the managed directory $managed and its parked copy $trash exist for collection " +
                    "${operation.collectionId.value}; refusing to delete either before an operator " +
                    "decides which one holds the documents",
            )
        }
        if (!managedExists && !trashExists && operation.managedOriginalsExisted) {
            throw IllegalStateException(
                "the managed directory for collection ${operation.collectionId.value} is missing " +
                    "from both $managed and $trash, but the deletion record says it held managed " +
                    "originals; refusing to finish the deletion and lose them silently",
            )
        }
    }

    private fun assertMutationsAllowed() {
        if (blocked.isNotEmpty()) throw DeletionRecoveryBlockedException(blocked)
    }

    private companion object {
        /** Distinguishes parked directories from collection directories in the same parent. */
        const val TRASH_PREFIX = ".deleted-"
    }
}
