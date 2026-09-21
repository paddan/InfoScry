package infoscry

import infoscry.collection.CollectionIndexRemover
import infoscry.collection.CollectionService
import infoscry.collection.DeletionRecoveryReport
import infoscry.config.AppPaths
import infoscry.config.ProcessLock
import infoscry.library.ManagedLibrary
import infoscry.logging.LoggingBootstrap
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DeletionStore
import infoscry.storage.DocumentStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.SchemaMigrator
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * One open InfoScry data directory: every service the process needs, wired by hand and closed together.
 *
 * There is one of these per process, because there is one authoritative SQLite database and one search
 * index. [open] takes the single-writer [ProcessLock] first, so a second process fails with an
 * actionable error instead of corrupting the archive, and it finishes any collection deletion that a
 * previous process did not complete **before** the context is handed out: from that moment the server
 * may serve requests and jobs may run, and neither may see a half-deleted collection.
 *
 * Wiring is explicit — no dependency-injection framework — because the object graph is small and the
 * construction order matters: the database is migrated before any store reads it, and recovery runs
 * before anything else can write.
 */
class AppContext private constructor(
    val paths: AppPaths,
    val database: Database,
    val collections: CollectionStore,
    val documents: DocumentStore,
    val deletions: DeletionStore,
    val library: ManagedLibrary,
    val mutations: MutationCoordinator,
    val collectionService: CollectionService,
    private val lock: ProcessLock,
) : AutoCloseable {

    /**
     * What the startup roll-forward found. Non-empty [DeletionRecoveryReport.blocked] means the
     * archive still holds a deletion that could not be finished safely, and every mutating command is
     * refused until an operator resolves it.
     */
    @Volatile
    var deletionRecovery: DeletionRecoveryReport = DeletionRecoveryReport(emptyList(), emptyList())
        private set

    override fun close() {
        // The lock is released last: while the database is closing, this process still owns the data
        // directory, so no second process may open it in between.
        runCatching { database.close() }
        lock.close()
    }

    companion object {

        /** Opens one data directory, creating its layout and finishing interrupted deletions. */
        fun open(
            dataDir: Path,
            index: CollectionIndexRemover = CollectionIndexRemover.NONE,
        ): AppContext = open(AppPaths.from(dataDir), index)

        fun open(
            paths: AppPaths,
            index: CollectionIndexRemover = CollectionIndexRemover.NONE,
        ): AppContext {
            // Before anything logs: the log sink is the data directory's, and that is only true if
            // Logback learns about it before it configures itself.
            LoggingBootstrap.useLogsDirectory(paths)

            val lock = ProcessLock.acquire(paths.lockFile)
            try {
                val database = Database(paths.databaseFile)
                try {
                    SchemaMigrator(database).migrate()
                    val collections = CollectionStore(database)
                    val documents = DocumentStore(database)
                    val deletions = DeletionStore(database)
                    val mutations = MutationCoordinator()
                    val service = CollectionService(
                        database = database,
                        paths = paths,
                        collections = collections,
                        deletions = deletions,
                        coordinator = mutations,
                        index = index,
                    )
                    val context = AppContext(
                        paths = paths,
                        database = database,
                        collections = collections,
                        documents = documents,
                        deletions = deletions,
                        library = ManagedLibrary(paths, documents),
                        mutations = mutations,
                        collectionService = service,
                        lock = lock,
                    )
                    context.deletionRecovery = runBlocking { service.recoverDeletions() }
                    return context
                } catch (failure: Throwable) {
                    runCatching { database.close() }
                    throw failure
                }
            } catch (failure: Throwable) {
                lock.close()
                throw failure
            }
        }
    }
}
