package infoscry

import infoscry.collection.CollectionIndexRemover
import infoscry.collection.CollectionService
import infoscry.collection.DeletionBlockers
import infoscry.collection.DeletionRecoveryReport
import infoscry.config.AppPaths
import infoscry.config.ProcessLock
import infoscry.document.DocumentIndexRemover
import infoscry.document.DocumentService
import infoscry.document.RetryPrerequisites
import infoscry.document.RetryService
import infoscry.document.PublicationRecoveryReport
import infoscry.document.RescanService
import infoscry.document.RevisionHistoryService
import infoscry.document.RevisionRestoreService
import infoscry.document.RevisionPublicationService
import infoscry.jobs.rescanEngineFactory
import infoscry.domain.Collection
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.chunk.Chunker
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.E5Embedder
import infoscry.embedding.ModelManager
import infoscry.embedding.ModelManifest
import infoscry.embedding.QueryEmbedder
import infoscry.jobs.JobRunner
import infoscry.jobs.ImportPipeline
import infoscry.jobs.ImportPreviewService
import infoscry.jobs.ImportSelection
import infoscry.jobs.ImportStartService
import infoscry.jobs.enumerateImportSources
import infoscry.library.ManagedLibrary
import infoscry.logging.LoggingBootstrap
import infoscry.ocr.OcrProfileService
import infoscry.ocr.OcrReadingMethodCatalog
import infoscry.ocr.StaleOcrStateCleanup
import infoscry.search.IndexIdentity
import infoscry.search.LuceneIndex
import infoscry.search.LuceneSchema
import infoscry.search.RevisionSnapshotGate
import infoscry.search.SearchService
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.DeletionStore
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.LlmStore
import infoscry.storage.ImportItemStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.OcrOperationStore
import infoscry.storage.OcrProfileStore
import infoscry.storage.OcrReviewStore
import infoscry.storage.SchemaMigrator
import infoscry.storage.StartRequestStore
import java.nio.file.Path
import infoscry.extract.DefaultPageCounter
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
    /**
     * The immutable revisions of documents: their page texts, their chunks and vectors, which revision
     * each document publishes, and the publications still in flight.
     */
    val revisions: DocumentRevisionStore,
    /**
     * The snapshot readers lease and that a publication swaps.
     *
     * One object per process, shared by the search service and the publication service, because the two
     * have to agree on which revisions a reader may see — a second gate would let a search read a
     * revision the publication had already replaced.
     */
    val revisionSnapshots: RevisionSnapshotGate,
    val deletions: DeletionStore,
    val importItems: ImportItemStore,
    val library: ManagedLibrary,
    val mutations: MutationCoordinator,
    val jobs: JobStore,
    val llm: LlmStore,
    /**
     * The unsafe deletion states that refuse every mutating command. One object, shared by the
     * collection and document deletion machines, so the refusal is the same answer whichever service a
     * route asks first.
     */
    val blockers: DeletionBlockers,
    private val injectedIndexRemover: CollectionIndexRemover,
    private val injectedDocumentIndexRemover: DocumentIndexRemover,
    initialIndex: LuceneIndex,
    private val lock: ProcessLock,
    /**
     * The query embedder the retrieval path uses, or null for the pinned CoreML model.
     *
     * This is the same kind of seam as [injectedIndexRemover]: a test that has no GPU and no pinned
     * model can drive a whole route with a deterministic vector, because [QueryEmbedder] exists
     * exactly so a search can be proved without a model. Nothing in production passes it.
     */
    private val injectedQueryEmbedder: (() -> QueryEmbedder?)? = null,
    /**
     * What a retry may assume about this machine, or null for the machine's own answer.
     *
     * The same kind of seam as [injectedQueryEmbedder]: the tools a retry needs are a property of the
     * machine, and a test that would otherwise depend on Tesseract, Calibre or the pinned model being
     * installed has to be able to say what it wants the answer to be. Nothing in production passes it.
     */
    private val injectedRetryPrerequisites: (suspend (Collection) -> RetryPrerequisites)? = null,
    /**
     * Whether a rescan could embed a replacement on this machine, or null for the pinned model's own answer.
     *
     * The same kind of seam as [injectedRetryPrerequisites]: whether the model is installed is a property of
     * the machine, and a test that drives rescan admission has to be able to say what the answer is rather
     * than depend on what happens to be installed where the suite runs. Nothing in production passes it.
     */
    private val injectedRescanEmbedder: (() -> Boolean)? = null,
    /**
     * The embedder a restore re-embeds a historical reading's passages with, or null for the process's own.
     *
     * The same kind of seam as [injectedQueryEmbedder]: a test that has no GPU and no pinned model has to be
     * able to say what the embedder is — or that there is none. Nothing in production passes it; production
     * uses the embedder the job worker was attached with ([attachDocumentEmbedder]) and otherwise the pinned
     * model's own, so a restore and an import never hold two accelerator sessions.
     */
    private val injectedRestoreEmbedder: (() -> DocumentEmbedder?)? = null,
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

    /**
     * OCR profiles, kept apart from the Ask/Investigate profiles.
     *
     * An image-model transcription is a different decision from an answer's model — it sends page images to an
     * endpoint, which is an explicit external-processing choice — so it has its own profiles, its own routes
     * and no effect on the LLM defaults.
     */
    val ocrProfiles: OcrProfileStore = OcrProfileStore(database)

    val ocr: OcrProfileService = OcrProfileService(
        profiles = ocrProfiles,
        // The same engines a page image would be read through, so an import or a retry records the runtime
        // it was admitted under rather than discovering another one when it happens to run.
        engineFor = rescanEngineFactory(ocrProfiles),
    )

    /** The methods that the current machine and OCR profiles can run for a collection. */
    val readingMethodCatalog: OcrReadingMethodCatalog = OcrReadingMethodCatalog(ocr)

    /** Durable request ids shared by import starts and document retries. */
    val startRequests: StartRequestStore by lazy { StartRequestStore(database) }

    /** Preview uses the same detector, extractor registry, directory walker and file selection as import. */
    val importPreviewService: ImportPreviewService by lazy {
        val pipeline = ImportPipeline.production(this)
        val selection = ImportSelection(pipeline.detector, pipeline.registry)
        ImportPreviewService(
            collections = collections,
            catalog = readingMethodCatalog,
            profiles = ocr,
            pageCounter = DefaultPageCounter(),
            selection = selection,
            enumerate = { sources, recursive, ignore ->
                enumerateImportSources(sources, recursive, ignore, selection)
            },
            ignorePatterns = { collectionId -> collectionService.ignorePatterns(collectionId) },
        )
    }

    /** Shared method/hash/idempotency admission used by HTTP imports and the standalone CLI import. */
    val importStartService: ImportStartService by lazy {
        ImportStartService(
            previews = importPreviewService,
            catalog = readingMethodCatalog,
            ocr = ocr,
            jobs = jobs,
            requests = startRequests,
            mutations = mutations,
            collectionService = collectionService,
            ignorePatterns = { collectionId -> collectionService.ignorePatterns(collectionId) },
        )
    }

    /**
     * The durable rescan operations, with their previews, their external-page admission and their approvals.
     */
    val ocrOperations: OcrOperationStore = OcrOperationStore(database)

    /** The reviews a comparison writes and a person decides about. */
    val ocrReviews: OcrReviewStore = OcrReviewStore(database)

    /**
     * Publishing a staged candidate revision of one document, and finishing what a previous process left
     * unfinished. It shares the mutation gate with every other writer, because the publication boundary is
     * where a deletion or a tombstoned collection gets to win.
     */
    val revisionPublication: RevisionPublicationService = RevisionPublicationService(
        revisions = revisions,
        documents = documents,
        collections = collections,
        mutations = mutations,
        index = { index() },
        gate = revisionSnapshots,
    )

    /**
     * Admitting, following, approving and cancelling a rescan of one document.
     *
     * It shares the mutation gate, the deletion blockers and the publication service with every other writer,
     * because a rescan admits durable work on a document an operator may be deleting, and it publishes through
     * exactly the same recoverable protocol an import and a restore do.
     */
    val rescanService: RescanService = RescanService(
        paths = paths,
        collections = collections,
        documents = documents,
        revisions = revisions,
        jobs = jobs,
        cancelAttempt = { id -> cancelJob(id) },
        operations = ocrOperations,
        mutations = mutations,
        blockers = blockers,
        publication = revisionPublication,
        profileOf = { profileId -> ocrProfiles.findById(profileId) },
        profileRevisionOf = { revisionId -> ocrProfiles.findRevision(revisionId) },
        methodCatalog = readingMethodCatalog,
        engines = rescanEngineFactory(ocrProfiles),
        // The cheap installation check rather than a session: admission asks whether a replacement could ever
        // be published, not whether the accelerator is reachable this second.
        embedderAvailable = injectedRescanEmbedder
            ?: { ModelManager.production().isInstalled(paths.modelsDir) },
    )

    /**
     * The document embedder this process's job worker was attached with, so a restore re-embeds with the same
     * session an import does. Null until a composition root attaches one.
     */
    @Volatile
    private var attachedDocumentEmbedder: (() -> DocumentEmbedder?)? = null

    /** The pinned model's own tokenizer, built on first use and only when no chunker was attached. */
    private val productionChunker: Chunker by lazy {
        Chunker(E5Embedder.productionCounter(paths.modelsDir, paths.embeddingProfileDir))
    }

    /** The embedder a restored revision uses: the injected one, the attached one, or the pinned model's. */
    private fun currentDocumentEmbedder(): DocumentEmbedder? =
        (injectedRestoreEmbedder ?: attachedDocumentEmbedder ?: productionDocumentEmbedder).invoke()

    /** The pinned model's embedder, built on first use and only when nothing else was injected or attached. */
    private val productionDocumentEmbedder: () -> DocumentEmbedder? by lazy {
        E5Embedder.productionDocumentEmbedder(paths.modelsDir, paths.embeddingProfileDir)
    }

    /**
     * Records the embedder this process's worker embeds with, so [revisionRestore] shares it.
     *
     * An embedder a test injected at [open] keeps winning: the seam exists to say what the answer is.
     */
    fun attachDocumentEmbedder(embedder: () -> DocumentEmbedder?) {
        attachedDocumentEmbedder = embedder
    }

    /**
     * The chunker this process's worker uses, shared by restored revisions and imports. Null until a composition root attaches one.
     */
    @Volatile
    private var attachedChunker: Chunker? = null

    /** Records the chunker this process's worker chunks with, so [revisionRestore] uses the same tokenizer. */
    fun attachChunker(chunker: Chunker) {
        attachedChunker = chunker
    }

    /**
     * Explicit restoration of a historical revision as a new publication.
     *
     * It shares the mutation gate, the deletion blockers, the operation store and the publication service with
     * every other writer: a restore is refused while a rescan owns the document or a deletion targets it, and
     * it becomes the document's text only through the same recoverable protocol a rescan publishes through.
     */
    val revisionRestore: RevisionRestoreService = RevisionRestoreService(
        revisions = revisions,
        documents = documents,
        collections = collections,
        mutations = mutations,
        blockers = blockers,
        operations = ocrOperations,
        publication = revisionPublication,
        embedder = { currentDocumentEmbedder() },
    )

    /** The published text history of a document, as the history view and its restore action read it. */
    val revisionHistory: RevisionHistoryService = RevisionHistoryService(
        revisions = revisions,
        documents = documents,
        operations = ocrOperations,
        reviews = ocrReviews,
        profileRevisionOf = { revisionId -> ocrProfiles.findRevision(revisionId) },
    )

    val collectionService: CollectionService = CollectionService(
        database = database,
        paths = paths,
        collections = collections,
        deletions = deletions,
        coordinator = mutations,
        ocrProfiles = ocr,
        index = indexRemover,
        blockers = blockers,
    )

    /**
     * The document phase of a deletion removes one document's rows from the generation the process
     * serves right now, for the same reason the collection remover reads through the accessor.
     */
    private val documentIndexRemover: DocumentIndexRemover =
        if (injectedDocumentIndexRemover === DocumentIndexRemover.NONE) {
            DocumentIndexRemover { documentId -> index().deleteDocument(documentId) }
        } else {
            injectedDocumentIndexRemover
        }

    /**
     * Deleting chosen documents from a collection, durably. It shares the mutation gate, the process
     * lock and the deletion blockers with the collection service, because an unsafe state of either kind
     * has to refuse the same mutations.
     */
    val documentService: DocumentService = DocumentService(
        database = database,
        paths = paths,
        collections = collections,
        documents = documents,
        deletions = deletions,
        coordinator = mutations,
        blockers = blockers,
        index = documentIndexRemover,
    )

    /**
     * Admitting a retry: which documents may be read again from their managed copies, and the durable
     * attempt that does it. It shares the mutation gate and the deletion blockers with both deletion
     * machines, because an unsafe deletion state of either kind has to refuse this mutation too.
     */
    val retryService: RetryService = RetryService(
        database = database,
        collections = collections,
        documents = documents,
        jobs = jobs,
        coordinator = mutations,
        blockers = blockers,
        // A retry with a chosen OCR method is admitted through the rescan service's own validation and frozen
        // as its snapshot, so the two can never disagree about what an engine or profile may be used for.
        pageCountOf = { document -> infoscry.extract.DefaultPageCounter().pageCount(library.managedPathOf(document)) },
        choices = object : infoscry.document.RetryReadingChoices {
            override suspend fun resolve(
                collection: Collection,
                choice: infoscry.ocr.ReadingMethod,
            ) = rescanService.resolveChosenReading(collection, choice)

            override fun pageImagesRefusal(document: infoscry.domain.Document) =
                rescanService.pageImagesRefusal(document)

            override fun publishesText(documentId: infoscry.domain.DocumentId) =
                revisions.activeRevisionId(documentId) != null
        },
        prerequisites = injectedRetryPrerequisites ?: { collection ->
            RetryPrerequisites.probe(
                collection = collection,
                modelsDir = paths.modelsDir,

            )
        },
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
            queryEmbedder = injectedQueryEmbedder
                ?: E5Embedder.productionQueryEmbedder(paths.modelsDir, paths.embeddingProfileDir),
            snapshots = revisionSnapshots,
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

    /**
     * What the startup roll-forward found among unfinished publications. Reported rather than acted on:
     * by the time this is readable, the contexts's state already agrees with it.
     */
    @Volatile
    var publicationRecovery: PublicationRecoveryReport = PublicationRecoveryReport(emptyList(), emptyList())
        private set

    override fun close() {
        // Deletions stop before the stores they write to are closed: one may be holding exclusive
        // maintenance and working through its phases, and its last written phase is what makes the next
        // startup able to finish it.
        runCatching { collectionService.close() }
        runCatching { documentService.close() }
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
            queryEmbedder: (() -> QueryEmbedder?)? = null,
            documentIndex: DocumentIndexRemover = DocumentIndexRemover.NONE,
            retryPrerequisites: (suspend (Collection) -> RetryPrerequisites)? = null,
            rescanEmbedder: (() -> Boolean)? = null,
            restoreEmbedder: (() -> DocumentEmbedder?)? = null,
        ): AppContext = open(
            AppPaths.from(dataDir),
            index,
            queryEmbedder,
            documentIndex,
            retryPrerequisites,
            rescanEmbedder,
            restoreEmbedder,
        )

        fun open(
            paths: AppPaths,
            index: CollectionIndexRemover = CollectionIndexRemover.NONE,
            queryEmbedder: (() -> QueryEmbedder?)? = null,
            documentIndex: DocumentIndexRemover = DocumentIndexRemover.NONE,
            retryPrerequisites: (suspend (Collection) -> RetryPrerequisites)? = null,
            rescanEmbedder: (() -> Boolean)? = null,
            restoreEmbedder: (() -> DocumentEmbedder?)? = null,
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
                    val revisions = DocumentRevisionStore(database, content)
                    val deletions = DeletionStore(database)
                    val jobs = JobStore(database)
                    val importItems = ImportItemStore(database)
                    val mutations = MutationCoordinator()
                    val llm = LlmStore(database)

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
                            revisions = revisions,
                            revisionSnapshots = RevisionSnapshotGate(),
                            deletions = deletions,
                            importItems = importItems,
                            library = ManagedLibrary(paths, documents),
                            mutations = mutations,
                            jobs = jobs,
                            llm = llm,
                            blockers = DeletionBlockers(),
                            injectedIndexRemover = index,
                            injectedDocumentIndexRemover = documentIndex,
                            initialIndex = searchIndex,
                            lock = lock,
                            injectedQueryEmbedder = queryEmbedder,
                            injectedRetryPrerequisites = retryPrerequisites,
                            injectedRescanEmbedder = rescanEmbedder,
                            injectedRestoreEmbedder = restoreEmbedder,
                        )
                        // A publication that a previous process did not finish is resolved before the
                        // marker is resolved and before anything may read: an intent that moved authority
                        // is completed from its own staged artifacts, and one that did not leaves the
                        // reading it was replacing in place.
                        context.publicationRecovery = runBlocking {
                            context.revisionPublication.recoverUnfinished()
                        }
                        // A restore request a previous process left in flight is resolved from what that
                        // recovery just decided about its publication, still before anything may read.
                        runBlocking { context.revisionRestore.recoverInterrupted() }
                        context.deletionRecovery = runBlocking {
                            // Both kinds are finished before the marker is resolved and before any job is
                            // admitted, so no worker can see a document whose rows are half removed.
                            val collections = context.collectionService.recoverDeletions()
                            val documents = context.documentService.recoverDeletions()
                            DeletionRecoveryReport(
                                recovered = collections.recovered + documents.recovered,
                                blocked = collections.blocked + documents.blocked,
                            )
                        }
                        // The sweep runs after the marker is resolved and before any writer is admitted,
                        // so the set it removes is exactly the unreferenced one: a half-built successor
                        // and a generation a previous process could not retire.
                        val swept = LuceneIndex.sweepUnreferenced(paths.indexDir, searchIndex.name)
                        if (swept.isNotEmpty()) {
                            LOGGER.atInfo()
                                .addKeyValue(SWEPT_GENERATIONS_FIELD, swept.joinToString(", "))
                                .log("removed unreferenced search index generations")
                        }
                        // No worker is attached until AppContext.open has returned, so a persisted RUNNING
                        // owner belongs to the process that stopped; clean its operation before jobs are
                        // requeued below. Running this before resetInterrupted preserves the job's recovery
                        // decision while ensuring no operation can remain a stale document hold.
                        StaleOcrStateCleanup(
                            jobs,
                            context.ocrOperations,
                            revisions,
                            documents,
                            hasLiveAttempt = { false },
                        ).run()
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
