package infoscry.search

import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.chunk.Chunker
import infoscry.chunk.ChunkDraft
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.E5Embedder
import infoscry.embedding.QueryEmbedder
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.storage.Instants
import infoscry.storage.MaintenanceInProgressException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The generation swap is the protocol the product's citations rest on, so the tests below ask the two
 * questions the brief names: what does a rebuild leave behind when the process dies at each instant of
 * the swap, and what does the archive serve afterwards. The kills are real — a child JVM runs the
 * service's own protocol through its observation seam and is terminated forcibly, the way
 * [infoscry.collection.DeletionHarness] is — so the state a test inspects is the state production would
 * leave, not a second copy of the protocol that could drift.
 *
 * The invariant every kill test shares is the marker's: the generation the `current` marker names is the
 * one the next open serves, and a successor that was never named is swept on the way in, whatever its
 * state. A narrowed rebuild additionally has to keep every other collection's rows, because one index
 * serves every collection.
 */
class ReindexRecoveryTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-reindex")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    // ---- Terminated mid-rebuild ----

    @Test
    fun `a rebuild killed while building the successor leaves the old generation serving`() =
        assertSwapSurvived(ReindexStep.GENERATION_BUILT)

    @Test
    fun `a rebuild killed before the marker swap leaves the old generation serving`() =
        assertSwapSurvived(ReindexStep.BEFORE_MARKER_SWAP)

    @Test
    fun `a rebuild killed after the marker swap makes the successor the next startup's generation`() =
        assertSwapSelected(ReindexStep.AFTER_MARKER_SWAP)

    // ---- What a finished rebuild keeps ----

    @Test
    fun `a completed rebuild is idempotent for the documents it copied`() {
        val (context, collectionA) = openSeededArchive(collections = 1, documentsPerCollection = 1)
        context.use { closed ->
            val rowsBefore = closed.index().rowCount(collectionA)
            val result = runBlocking {
                reindexService(closed).reindex(ReindexRequest(collectionId = collectionA))
            }
            assertEquals(0, result.documentsRebuilt, "a document whose copy already agrees is not rebuilt")
            assertEquals(rowsBefore, closed.index().rowCount(collectionA), "the copied rows are the same rows")
        }
    }

    @Test
    fun `a narrowed rebuild keeps every other collection exactly its live rows`() {
        val (context, collectionA, collectionB) = openSeededArchive(collections = 2, documentsPerCollection = 1)
        assertNotNull(collectionB, "the fixture named two collections")
        context.use { closed ->
            val result = runBlocking {
                reindexService(closed).reindex(ReindexRequest(collectionId = collectionA))
            }
            assertEquals(1, result.documents, "the narrowed rebuild walks only the named collection")
            assertEquals(
                chunkCount(closed, collectionA),
                closed.index().rowCount(collectionA),
                "the named collection's rows match the chunks its database holds",
            )
            assertEquals(
                chunkCount(closed, collectionB),
                closed.index().rowCount(collectionB),
                "the other collection's rows were carried forward unchanged",
            )
            val outcome = runBlocking {
                searchService(closed).search("nightfall", filters = SearchFilters(collectionId = collectionB))
            }
            // Hits are per chunk, and both of the carried-forward collection's chunks contain the term.
            assertEquals(2, outcome.hits.size, "the carried-forward collection is still searchable")
        }
    }

    // ---- What maintenance excludes ----

    @Test
    fun `a rebuild excludes a concurrent import and a concurrent deletion, and admits them again afterwards`() {
        val (context, collectionA) = openSeededArchive(collections = 1, documentsPerCollection = 1)
        context.use { closed ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val service = reindexService(closed) { BlockingDocumentEmbedder(entered, release) }

            runBlocking {
                val rebuild = launch { service.reindex(ReindexRequest(collectionId = collectionA)) }
                withTimeout(SCREEN_TIMEOUT_MILLIS) { entered.await() }

                assertFailsWith<MaintenanceInProgressException> {
                    closed.mutations.withMutation { }
                }
                assertFailsWith<MaintenanceInProgressException> {
                    closed.collectionService.deleteConfirmed(collectionA, COLLECTION_A)
                }

                release.complete(Unit)
                withTimeout(SCREEN_TIMEOUT_MILLIS) { rebuild.join() }

                closed.mutations.withMutation { }
            }
        }
    }

    @Test
    fun `readers keep searching the generation they leased while a rebuild holds maintenance`() {
        val (context, collectionA) = openSeededArchive(collections = 1, documentsPerCollection = 1)
        context.use { closed ->
            val before = runBlocking {
                searchService(closed).search("nightfall", filters = SearchFilters(collectionId = collectionA))
            }
            // Hits are per chunk, and the seeded document's two chunks both contain the term.
            assertEquals(2, before.hits.size, "the archive is searchable before the rebuild")

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val service = reindexService(closed) { BlockingDocumentEmbedder(entered, release) }

            runBlocking {
                val rebuild = launch { service.reindex(ReindexRequest(collectionId = collectionA)) }
                withTimeout(SCREEN_TIMEOUT_MILLIS) { entered.await() }

                val during = runBlocking {
                    searchService(closed).search("nightfall", filters = SearchFilters(collectionId = collectionA))
                }
                assertEquals(
                    before.hits.size,
                    during.hits.size,
                    "a reader is not broken by the maintenance a rebuild holds",
                )

                release.complete(Unit)
                withTimeout(SCREEN_TIMEOUT_MILLIS) { rebuild.join() }
            }
        }
    }

    // ---- What a kill at one instant leaves behind ----

    /**
     * Kills the rebuild at [step] before it published, then asserts the archive still serves the
     * generation it served before, that the unreferenced successor is swept on the way in, and that a
     * later rebuild still works.
     */
    private fun assertSwapSurvived(step: ReindexStep) {
        val (context, collectionA) = openSeededArchive(collections = 1, documentsPerCollection = 1)
        val beforeName = context.index().name
        context.close()

        terminateReindexAt(step)
        assertEquals(
            beforeName,
            LuceneIndex.currentGenerationName(AppPaths.from(dataDir).indexDir),
            "the marker still names the generation that was serving when the process died",
        )

        AppContext.open(dataDir).use { reopened ->
            assertEquals(beforeName, reopened.index().name, "the old generation is the one served")
            val outcome = runBlocking {
                searchService(reopened).search("nightfall", filters = SearchFilters(collectionId = collectionA))
            }
            // Hits are per chunk, and the seeded document's two chunks both contain the term.
            assertEquals(2, outcome.hits.size, "the archive is searchable from the generation it kept")
            assertTrue(
                generationDirectories().none { it != beforeName },
                "an unreferenced successor is swept on the way in, kept: ${generationDirectories()}",
            )
            runBlocking { reindexService(reopened).reindex(ReindexRequest(collectionId = collectionA)) }
            assertTrue(reopened.index().name != beforeName, "a later rebuild still works")
        }
    }

    /**
     * Kills the rebuild at [step] after it published the marker, then asserts the successor is the one
     * the next startup selects — the durable swap is the truth, not the process's memory of it.
     */
    private fun assertSwapSelected(step: ReindexStep) {
        val (context, collectionA) = openSeededArchive(collections = 1, documentsPerCollection = 1)
        val beforeName = context.index().name
        context.close()

        terminateReindexAt(step)
        val named = LuceneIndex.currentGenerationName(AppPaths.from(dataDir).indexDir)
        assertTrue(named != beforeName, "the marker names the successor once it was published, was $named")

        AppContext.open(dataDir).use { reopened ->
            assertEquals(named, reopened.index().name, "the next startup selects the generation the marker names")
            val outcome = runBlocking {
                searchService(reopened).search("nightfall", filters = SearchFilters(collectionId = collectionA))
            }
            // Hits are per chunk, and the seeded document's two chunks both contain the term.
            assertEquals(2, outcome.hits.size, "the successor serves the same citations")
            assertTrue(
                generationDirectories().none { it == beforeName },
                "the generation the marker replaced is swept",
            )
        }
    }

    // ---- Fixtures ----

    /** Seeds [collections] collections of [documentsPerCollection] searchable documents each. */
    private fun openSeededArchive(collections: Int, documentsPerCollection: Int): SeededArchive {
        val context = AppContext.open(dataDir)
        val ids = mutableListOf<CollectionId>()
        runBlocking {
            repeat(collections) { position ->
                val name = if (position == 0) COLLECTION_A else "$COLLECTION_A-$position"
                val collection = context.collectionService.create(name)
                ids += collection.id
                repeat(documentsPerCollection) { number ->
                    seedSearchableDocument(
                        context = context,
                        collectionId = collection.id,
                        filename = "nightfall-$position-$number.txt",
                        paragraphs = listOf("nightfall report line one", "nightfall report line two"),
                    )
                }
            }
        }
        return SeededArchive(context, ids[0], ids.getOrNull(1))
    }

    /**
     * One document whose chunks and index rows were produced by the same tokenizer and the same
     * identity the rebuild compares against, so the copy path can stand in for a rebuild.
     */
    private suspend fun seedSearchableDocument(
        context: AppContext,
        collectionId: CollectionId,
        filename: String,
        paragraphs: List<String>,
    ): DocumentId {
        val now = Instants.now()
        val document = Document(
            id = DocumentId.new(),
            collectionId = collectionId,
            sha256 = "sha-$filename",
            mediaType = "text/plain",
            originalFilename = filename,
            sourcePath = "tmp/original/$filename",
            sizeBytes = 1L,
            status = DocumentStatus.COMPLETE,
            title = filename,
            author = null,
            language = null,
            createdAt = now,
            updatedAt = now,
        )
        context.documents.insert(document)

        val settings = ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "reindex-test")
        val fingerprint = ExtractionFingerprint.of(document.sha256, settings)
        val chunker = Chunker(
            E5Embedder.productionCounter(context.paths.modelsDir, context.paths.embeddingProfileDir),
        )
        paragraphs.forEachIndexed { ordinal, paragraph ->
            context.content.commitExtractedUnit(
                documentId = document.id,
                fingerprint = fingerprint,
                key = "unit-$ordinal",
                ordinal = ordinal,
                draft = ContentUnitDraft(
                    locator = SourceLocation.TextLines(ordinal + 1, ordinal + 1),
                    extractedText = paragraph,
                    searchText = paragraph,
                ),
                artifactRoot = context.paths.libraryDir,
            )
        }

        var walked = 0
        var afterOrdinal = -1
        val rows = mutableListOf<DocumentRow>()
        while (true) {
            val batch = context.content.listUnits(document.id, afterOrdinal = afterOrdinal, limit = UNIT_BATCH)
            if (batch.isEmpty()) break
            batch.forEach { unit ->
                val plan = chunker.chunk(
                    unit,
                    Chunker.DEFAULT_MAX_SEQUENCE_TOKENS,
                    Chunker.DEFAULT_OVERLAP_TOKENS,
                )
                context.content.replaceUnitChunks(
                    unitId = unit.id,
                    drafts = plan.drafts,
                    chunkerVersion = chunker.version,
                    tokenizerId = chunker.counterId,
                    maxSequenceTokens = Chunker.DEFAULT_MAX_SEQUENCE_TOKENS,
                    overlapTokens = Chunker.DEFAULT_OVERLAP_TOKENS,
                )
                // The index replaces per document, so the fixture accumulates every unit's rows and
                // publishes them in one call, exactly as ReindexService.indexDocument does.
                rows += plan.drafts.map { draft ->
                    DocumentRow(
                        collectionId = collectionId,
                        documentId = document.id,
                        unitId = unit.id,
                        locator = unit.locator,
                        locatorLabel = unit.locator.describe(),
                        chunk = draft.asChunk(unit.id),
                        vector = vectorFor(draft.text),
                    )
                }
                afterOrdinal = unit.ordinal
                walked++
            }
            if (batch.size < UNIT_BATCH) break
        }
        context.index().replaceDocument(rows)
        context.content.finishChunking(
            documentId = document.id,
            chunkerVersion = chunker.version,
            tokenizerId = chunker.counterId,
            maxSequenceTokens = Chunker.DEFAULT_MAX_SEQUENCE_TOKENS,
            overlapTokens = Chunker.DEFAULT_OVERLAP_TOKENS,
            unitCount = walked,
        )
        return document.id
    }

    private fun chunkCount(context: AppContext, collectionId: CollectionId): Int =
        context.documents.listByCollection(collectionId, Int.MAX_VALUE).sumOf { document ->
            context.content.chunkCount(document.id)
        }

    private fun searchService(context: AppContext): SearchService = SearchService(
        collections = context.collections,
        documents = context.documents,
        index = { context.index() },
        queryEmbedder = { QueryEmbedder { vectorFor(it) } },
    )

    /**
     * The wiring the job handler uses in production, with the document embedder a test names: the copy
     * path never embeds, so a rebuild that stays a copy is proven by an embedder that counts.
     */
    internal fun reindexService(
        context: AppContext,
        documentEmbedder: () -> DocumentEmbedder = { TestDocumentEmbedder() },
    ): ReindexService = ReindexService(
        mutations = context.mutations,
        collections = context.collections,
        documents = context.documents,
        content = context.content,
        chunker = Chunker(
            E5Embedder.productionCounter(context.paths.modelsDir, context.paths.embeddingProfileDir),
        ),
        paths = context.paths,
        identity = AppContext.identityForCurrentModel(),
        documentEmbedder = documentEmbedder,
        current = { context.index() },
        publish = { next, previous -> context.publishGeneration(next, previous) },
    )

    private fun terminateReindexAt(step: ReindexStep) {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            ReindexHarness::class.java.name,
            "--data-dir",
            dataDir.toString(),
            "--stop-after",
            step.name,
        ).redirectErrorStream(true).start()

        val output = Collections.synchronizedList(mutableListOf<String>())
        val pump = Thread {
            process.inputReader().useLines { lines -> lines.forEach { output += it } }
        }
        pump.isDaemon = true
        pump.start()

        val ready = "HARNESS READY ${step.name}"
        val deadline = System.nanoTime() + HARNESS_TIMEOUT_NANOS
        var reached = output.contains(ready)
        while (!reached && System.nanoTime() < deadline && process.isAlive) {
            Thread.sleep(POLL_MILLIS)
            reached = output.contains(ready)
        }
        if (!reached) {
            process.destroyForcibly()
            process.waitFor()
            throw AssertionError(
                "the reindex harness never reached $step; output was:\n${output.joinToString("\n")}",
            )
        }
        process.destroyForcibly()
        process.waitFor()
    }

    private fun generationDirectories(): List<String> =
        Files.list(AppPaths.from(dataDir).indexDir).use { entries ->
            entries.map { it.fileName.toString() }.filter { it.startsWith(GENERATION_PREFIX) }.sorted().toList()
        }

    /** The collections a seeded archive produced, with the context its caller closes. */
    private data class SeededArchive(
        val context: AppContext,
        val first: CollectionId,
        val second: CollectionId?,
    )

    private fun ChunkDraft.asChunk(contentUnitId: ContentUnitId): Chunk = Chunk(
        id = ChunkId.new(),
        contentUnitId = contentUnitId,
        ordinal = ordinal,
        text = text,
        startOffset = startOffset,
        endOffset = endOffset,
        tokenCount = tokenCount,
        tokenStart = tokenStart,
        tokenEnd = tokenEnd,
    )

    companion object {
        private const val COLLECTION_A = "nightfall"
        private const val UNIT_BATCH = 64
        private const val GENERATION_PREFIX = "lucene-"
        private const val HARNESS_TIMEOUT_NANOS: Long = 120_000_000_000L
        private const val SCREEN_TIMEOUT_MILLIS: Long = 60_000L
        private const val POLL_MILLIS: Long = 100L
    }
}

/**
 * A child JVM that runs one real rebuild and stops it through the service's own observation seam.
 *
 * It prints `HARNESS READY <step>` once the rebuild reached the instant the test asked for, and then
 * holds the process open — the data directory lock included, exactly as a running server would — until
 * the test terminates it. The service it drives is the production wiring over this data directory, so
 * the state a kill leaves behind is the state production would leave.
 */
object ReindexHarness {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList()
        val dataDir = Path.of(value(options, "--data-dir"))
        val stopAfter = runCatching { ReindexStep.valueOf(value(options, "--stop-after")) }.getOrElse {
            System.err.println("--stop-after must be one of ${ReindexStep.entries.joinToString()}")
            exitProcess(2)
        }
        val collection = options.firstOrNull { it == "--collection" }?.let { value(options, "--collection") }

        AppContext.open(dataDir).use { context ->
            runBlocking {
                reindexService(context).reindex(
                    request = ReindexRequest(collectionId = collection?.let(::CollectionId)),
                    onProgress = { },
                    observe = { step -> if (step == stopAfter) waitForKill(stopAfter) },
                )
            }
        }
    }

    private fun reindexService(context: AppContext): ReindexService = ReindexService(
        mutations = context.mutations,
        collections = context.collections,
        documents = context.documents,
        content = context.content,
        chunker = Chunker(
            E5Embedder.productionCounter(context.paths.modelsDir, context.paths.embeddingProfileDir),
        ),
        paths = context.paths,
        identity = AppContext.identityForCurrentModel(),
        documentEmbedder = { TestDocumentEmbedder() },
        current = { context.index() },
        publish = { next, previous -> context.publishGeneration(next, previous) },
    )

    /**
     * Reports the instant and holds this JVM open. A bounded sleep would race the kill; an unbounded
     * one is safe because the test always terminates the process.
     */
    private fun waitForKill(stopAfter: ReindexStep) {
        println("HARNESS READY ${stopAfter.name}")
        System.out.flush()
        while (true) {
            Thread.sleep(HOLD_MILLIS)
        }
    }

    private fun value(options: List<String>, name: String): String {
        val index = options.indexOf(name)
        if (index < 0 || index + 1 >= options.size) {
            System.err.println("$name is required")
            exitProcess(2)
        }
        return options[index + 1]
    }

    private const val HOLD_MILLIS: Long = 250L
}

/**
 * An embedder the test controls: it parks on [entered] before returning and resumes on [release], so a
 * rebuild can be held inside exclusive maintenance while the test attempts the operations it excludes.
 */
private class BlockingDocumentEmbedder(
    private val entered: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
) : DocumentEmbedder {

    val calls = AtomicInteger()

    override fun embedDocuments(texts: List<String>): List<FloatArray> {
        calls.incrementAndGet()
        entered.complete(Unit)
        runBlocking { release.await() }
        return texts.map(::vectorFor)
    }
}
