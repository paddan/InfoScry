package infoscry

import infoscry.collection.CollectionIndexRemover
import infoscry.collection.CollectionService
import infoscry.collection.DeletionRecoveryReport
import infoscry.config.AppPaths
import infoscry.config.ProcessLock
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.embedding.E5Embedder
import infoscry.embedding.ModelManifest
import infoscry.jobs.JobRunner
import infoscry.library.ManagedLibrary
import infoscry.logging.LoggingBootstrap
import infoscry.search.IndexIdentity
import infoscry.search.LuceneIndex
import infoscry.search.LuceneSchema
import infoscry.search.SearchService
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.DeletionStore
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.ImportItemStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.SchemaMigrator
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

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
    val content: ContentStore,
    val deletions: DeletionStore,
    val importItems: ImportItemStore,
    val library: ManagedLibrary,
    val mutations: MutationCoordinator,
    val jobs: JobStore,
    private val injectedIndexRemover: CollectionIndexRemover,
    initialIndex: LuceneIndex,
    private val lock: ProcessLock,
) : AutoCloseable {

    /**
     * The generation this data directory currently serves.
     *
     * A rebuild builds a successor and publishes it while this process keeps running, so readers take
     * the current generation *by reference here* rather than holding the one they were opened with: a
     * search that starts after the swap reads the new generation, and one that started before it
     * finishes on the generation it already leased.
     */
    @Volatile
    private var currentGeneration: LuceneIndex = initialIndex

    /** Serializes publishing a successor, so two rebuilds cannot swap out of order. */
    private val indexAdmission = Mutex()

    /**
     * The index phase of a deletion removes the collection's rows from the generation the process
     * serves *right now*, which is why the remover reads through the accessor rather than holding the
     * generation it was opened with: a deletion that finishes after a rebuild must clean the successor,
     * not the one it replaced.
     */
    private val indexRemover: CollectionIndexRemover =
        if (injectedIndexRemover === CollectionIndexRemover.NONE) {
            CollectionIndexRemover { collectionId -> index().deleteCollection(collectionId) }
        } else {
            injectedIndexRemover
        }

    val collectionService: CollectionService = CollectionService(
        database = database,
        paths = paths,
        collections = collections,
        deletions = deletions,
        coordinator = mutations,
        index = indexRemover,
    )

    /** The generation the process currently serves. Reading it is a single atomic reference read. */
    fun index(): LuceneIndex = currentGeneration

    /**
     * Publishes [next] as the generation this process serves and hands [previous] back to retirement.
     *
     * The swap is ordered after the marker by the caller, so anything that reads the new generation
     * here can rely on it being the durable one. The check is what stops two rebuilds from interleaving
     * their swaps: the second one sees that the generation it built against is no longer served and
     * refuses rather than publishing over it.
     */
    internal suspend fun publishGeneration(next: LuceneIndex, previous: LuceneIndex) {
        indexAdmission.withLock {
            check(currentGeneration === previous) {
                "another rebuild published ${currentGeneration.name} while this one was building"
            }
            currentGeneration = next
        }
    }

    /**
     * Retrieval over this context's index, resolved lazily so a command that never searches does not
     * touch the query embedder or the accelerator.
     */
    val search: SearchService by lazy {
        SearchService(
            collections = collections,
            documents = documents,
            index = { index() },
            queryEmbedder = E5Embedder.productionQueryEmbedder(paths.modelsDir, paths.embeddingProfileDir),
        )
    }

    /**
     * The worker that owns this data directory's job queue, once a composition root has wired its
     * handlers. `serve` and a foreground `import` attach one; a short-lived CLI does not, because it
     * cannot run jobs and must not pretend it could.
     */
    @Volatile
    private var jobRunner: JobRunner? = null

    /** Wires the process's worker. There is one per process, because there is one writer. */
    fun attachJobRunner(runner: JobRunner) {
        check(jobRunner == null) { "a job runner is already attached to this data directory" }
        jobRunner = runner
    }

    /**
     * Records a cancellation request for one job and interrupts the attempt that is running it.
     *
     * With a runner attached the attempt is stopped promptly; without one the durable request is the
     * whole action and nothing is lost, because only the process holding the data-directory lock can be
     * running work for it — and that process is this one.
     */
    suspend fun cancelJob(id: JobId): Job = jobRunner?.cancel(id) ?: jobs.cancel(id)

    /**
     * What the startup roll-forward found. Non-empty [DeletionRecoveryReport.blocked] means the
     * archive still holds a deletion that could not be finished safely, and every mutating command is
     * refused until an operator resolves it.
     */
    @Volatile
    var deletionRecovery: DeletionRecoveryReport = DeletionRecoveryReport(emptyList(), emptyList())
        private set

    override fun close() {
        // The worker stops first: it writes, so it must not still be running when the database closes.
        // Its close hands unfinished attempts back to the queue, which is what makes a clean shutdown
        // resumable.
        runCatching { jobRunner?.close() }
            .onFailure { failure ->
                // Worth a line: a worker that could not hand its attempts back leaves the next process to
                // repair the queue, and without this nothing records why it did not happen here.
                LOGGER.atWarn()
                    .addKeyValue(COMPONENT_FIELD, JOBS_COMPONENT)
                    .setCause(failure)
                    .log("the job runner did not finish handing its attempts back to the queue")
            }
        // The index closes before the database: both are derived from it, and an index writer that held
        // the directory open while the database closed would be the one thing this process owns that
        // outlived its archive. The generation served right now is the one to close; a retired one was
        // closed by the rebuild that retired it.
        runCatching { currentGeneration.close() }
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
            // The whole layout first, before anything can write into it. Opening a data directory creates its
            // database, so this is not a read-only operation and must not pretend to be one: the import path
            // copies through the scratch directory, and a layout that is missing `tmp` would make the first
            // import of a fresh archive fail on a directory nobody had created yet.
            paths.ensureDirectories()

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
                    val content = ContentStore(database)
                    val deletions = DeletionStore(database)
                    val jobs = JobStore(database)
                    val importItems = ImportItemStore(database)
                    val mutations = MutationCoordinator()

                    // The search index is part of the data directory, so every open has exactly one. It is
                    // created before recovery so an unfinished deletion can finish its index phase, and it
                    // records the pinned model's identity so stale vectors are detected rather than searched.
                    val searchIndex = LuceneIndex.open(paths.indexDir, identityForCurrentModel())
                    try {
                        val context = AppContext(
                            paths = paths,
                            database = database,
                            collections = collections,
                            documents = documents,
                            content = content,
                            deletions = deletions,
                            importItems = importItems,
                            library = ManagedLibrary(paths, documents),
                            mutations = mutations,
                            jobs = jobs,
                            injectedIndexRemover = index,
                            initialIndex = searchIndex,
                            lock = lock,
                        )
                        context.deletionRecovery = runBlocking { context.collectionService.recoverDeletions() }
                        // The sweep runs after the marker is resolved and before any writer is admitted,
                        // so the set it removes is exactly the unreferenced one: a half-built successor
                        // and a generation a previous process could not retire.
                        val swept = LuceneIndex.sweepUnreferenced(paths.indexDir, searchIndex.name)
                        if (swept.isNotEmpty()) {
                            LOGGER.atInfo()
                                .addKeyValue(SWEPT_GENERATIONS_FIELD, swept.joinToString(", "))
                                .log("removed unreferenced search index generations")
                        }
                        // After deletions are finished, because finishing one cascades its jobs away: an
                        // attempt that a previous process was inside is queued again, and a cancellation
                        // request that previous process recorded is honoured rather than re-run.
                        val resumed = jobs.resetInterrupted()
                        if (resumed > 0) {
                            LOGGER.atInfo().addKeyValue(RESUMED_JOBS_FIELD, resumed)
                                .log("queued interrupted job attempts again")
                        }
                        return context
                    } catch (failure: Throwable) {
                        runCatching { searchIndex.close() }
                        runCatching { database.close() }
                        throw failure
                    }
                } catch (failure: Throwable) {
                    runCatching { database.close() }
                    throw failure
                }
            } catch (failure: Throwable) {
                lock.close()
                throw failure
            }
        }

        /** The index identity of the pinned model: what the vectors are, and what a mismatch is measured on. */
        internal fun identityForCurrentModel(): IndexIdentity {
            val manifest = ModelManifest.load()
            return IndexIdentity(
                schemaVersion = LuceneSchema.SCHEMA_VERSION,
                model = manifest.model,
                modelRevision = manifest.revision,
                modelFingerprint = manifest.fingerprint(),
                dimension = manifest.dimension,
            )
        }

        private const val RESUMED_JOBS_FIELD = "resumed_jobs"
        private const val SWEPT_GENERATIONS_FIELD = "swept_generations"
        private const val COMPONENT_FIELD = "component"
        private const val JOBS_COMPONENT = "jobs"
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.startup")
