/*
 * The ticket-12 browser acceptance for the complete Collections workflow.
 *
 * The real built reader in Chromium is driven by `web/e2e/collections-browser-acceptance.mjs` against a
 * real InfoScry server on a temporary archive. This test owns the server, the redistributable fixtures,
 * the picker the server's route calls instead of a native dialog, and the assertions that belong to the
 * archive rather than to the screen: document statuses, committed progress, per-file dispositions,
 * deletion recovery, and which files outside InfoScry still exist.
 *
 * Substitutions, and what they mean for what this test may claim:
 *
 * - the extraction pipeline is the acceptance extractor (`CollectionsAcceptancePipeline.kt`), so nothing
 *   here proves Tesseract, a real PDF's page structure, or the pinned model. Real OCR and CoreML remain
 *   their own gates, and the native picker remains a manual one;
 * - the document embedder is `TestDocumentEmbedder`, because a temporary archive has no model.
 *
 * Tagged `external` because it needs Node and Chromium: the default suite stays runnable everywhere.
 */
package infoscry.server

import infoscry.AppContext
import infoscry.EXTERNAL_TAG
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.diagnostics.ToolProbe
import infoscry.document.RetryPrerequisites
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.domain.UnitKind
import infoscry.embedding.TestDocumentEmbedder
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.jobs.ImportJobHandler
import infoscry.storage.Database
import infoscry.storage.DocumentListing
import infoscry.storage.ImportItemOutcome
import infoscry.storage.Instants
import infoscry.storage.SchemaMigrator
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag

@Tag(EXTERNAL_TAG)
class CollectionsBrowserAcceptanceTest {

    private lateinit var tempDir: Path
    private lateinit var sourcesDir: Path
    private lateinit var picker: AcceptancePicker
    private lateinit var gate: AcceptanceGate
    /** Set for the scenario that has to hold a collection deletion open inside its index phase. */
    private var indexHold: AcceptanceGate? = null
    /** What the acceptance extractor actually read and reused, per file name. */
    private lateinit var extractorRun: AcceptanceExtractorRun
    private var harness: ApiTestServer? = null

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("infoscry-collections-acceptance")
        sourcesDir = AcceptanceFixtures.write(tempDir.resolve("sources"))
        picker = AcceptancePicker(sourcesDir)
        gate = AcceptanceGate()
    }

    @AfterTest
    fun tearDown() {
        // An extraction still waiting on the gate must not keep the archive open while it closes.
        gate.release()
        harness?.close()
        harness = null
        tempDir.toFile().deleteRecursively()
    }

    // ---- Scenarios ----

    @Test
    fun `an empty archive creates its first collection and keeps the rest of Admin usable`() {
        startServer()
        seedProfile("acceptance-profile")
        runScenario("collection-creation")

        assertEquals(listOf("Notes"), collectionNames(), "the acceptance archive must hold exactly the collection the browser created")
        assertTrue(context().llm.list().any { it.name == "acceptance-profile" }, "managing collections must not touch the LLM profiles")
    }

    @Test
    fun `external import confirms the method destination and page count once in the start dialog`() {
        startServer("Notes")
        val profile = context().ocrProfiles.create("External reader", infoscry.ocr.OcrProfileRevisionDraft(
            provider = LlmProvider.OPENAI_COMPATIBLE, model = "fake-vision", contextWindow = 32000,
            maxOutputTokens = 2048, endpoint = "https://example.invalid/v1", apiKeyEnvironmentVariable = "PATH"), enabled = true)
        context().ocrProfiles.recordImageCapability(profile.revision.revisionId, true, Instants.now())
        val collection = context().collections.list().single { it.name == "Notes" }
        context().collections.updateOcrSettings(collection.id,
            infoscry.ocr.CollectionOcrSettings("eng", infoscry.ocr.ReadingMethod.Llm(profile.id)))
        picker.files = AcceptanceFixtures.files(sourcesDir, "mixed-pages.png")
        runScenario("external-import-dialog")
        val job = context().jobs.listImports(collection.id, 10).single()
        val snapshot = checkNotNull(infoscry.jobs.ImportJobPayload.decode(job.payload).ocr)
        assertEquals(profile.revision.revisionId, snapshot.transcriptionProfileRevisionId)
        assertEquals(1, snapshot.externalPageLimit)
        assertEquals(infoscry.ocr.OcrImportMode.READ_ALL, snapshot.mode)
    }

    /**
     * A `Default` collection that already holds a document is kept until the reader renames or deletes it
     * themselves.
     *
     * The archive is built straight on the baseline schema with that collection and document in it, and
     * then handed to a real server. What the browser sees is therefore the archive's own state, not a
     * seeded imitation of it.
     */
    @Test
    fun `a used Default collection is kept until the reader chooses`() {
        val usedArchive = usedDefaultArchive("used-default") { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                        "VALUES ('default', 'Default', 'eng', 'ACTIVE', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')",
                )
                statement.execute(
                    "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                        "VALUES ('notes', 'Notes', 'eng', 'ACTIVE', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')",
                )
                statement.execute(
                    "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, " +
                        "original_path, size_bytes, status, created_at, updated_at) " +
                        "VALUES ('legacy-doc', 'default', 'legacy-sha', 'text/plain', " +
                        "'legacy-document.txt', 'fixtures/legacy-document.txt', 42, 'COMPLETE', " +
                        "'2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')",
                )
            }
        }
        startServerOver(usedArchive)
        runScenario("legacy-default")

        assertEquals(
            listOf("Default", "Notes"),
            collectionNames().sorted(),
            "the used legacy Default is gone only because the reader deleted it, and the new Default is theirs",
        )
        assertTrue(
            context().documents.listByCollection(CollectionId("default"), limit = 10).isEmpty(),
            "the deleted legacy collection takes its document with it",
        )
        assertEquals(0, context().documents.listByCollection(CollectionId("notes"), limit = 10).size)
    }

    @Test
    fun `adding documents reports duplicates and keeps the listing and history durable`() {
        startServer("Notes")
        picker.folder = AcceptanceFixtures.folder(sourcesDir, "bulk")
        picker.files = AcceptanceFixtures.files(sourcesDir, "duplicates/dup-a.txt", "duplicates/dup-b.txt", "duplicates/dup-c.txt")
        runScenario("documents-and-history")

        val collectionId = collectionId("Notes")
        val listing = DocumentListing(collectionId = collectionId)
        assertEquals(56, context().documents.countListing(listing), "55 folder fixtures plus one of the three identical files")
        assertTrue(
            findDocument("Notes", "dup-a.txt") != null,
            "the first of the identical files is the one that becomes a document",
        )
        val imports = context().jobs.listImports(collectionId, limit = 10)
        assertEquals(2, imports.size, "both imports must be recorded")
        val duplicateItems = context().importItems.listForJob(imports.first().id)
        assertEquals(3, duplicateItems.size, "the duplicate import has three files")
        assertEquals(
            2,
            duplicateItems.count { it.outcome == ImportItemOutcome.DUPLICATE },
            "the two later identical files must be dispositions, not documents",
        )
        assertEquals(1, duplicateItems.count { it.outcome == ImportItemOutcome.IMPORTED })
    }

    @Test
    fun `document details show honest counters and the reader opens the first unit`() {
        startServer("Notes")
        picker.files = AcceptanceFixtures.files(sourcesDir, "mixed-pages.png", "single/one.txt")
        val legacyId = seedLegacySummaryDocument("Notes")
        runScenario("document-details-and-reader")

        val mixed = findDocument("Notes", "mixed-pages.png")!!
        val mixedProgress = progressOf(mixed.id)!!
        assertEquals(UnitKind.PAGE, mixedProgress.unitKind, "a page document counts pages")
        assertEquals(3, mixedProgress.totalUnits, "the announced total is the document's page count")
        assertEquals(3, mixedProgress.processedUnits)
        assertEquals(1, mixedProgress.directTextUnits, "one page had its own text layer")
        assertEquals(2, mixedProgress.ocrUnits, "two pages were read by the tool")
        assertEquals(DocumentStatus.COMPLETE, mixed.status)

        val legacy = progressOf(legacyId)!!
        assertNull(legacy.unitKind, "a legacy summary recorded no unit kind, and that stays unknown rather than being guessed")
        assertEquals(7, legacy.totalUnits, "the legacy total is still the document's own fact")
        assertEquals(0, legacy.processedUnits)
        assertNull(legacy.directTextUnits, "an unrecorded method is unknown, never zero")
        assertNull(legacy.ocrUnits)
    }

    @Test
    fun `settings, a single retry and retry all act on the managed collection`() {
        startServer("Notes", "Archive")
        picker.files = AcceptanceFixtures.files(sourcesDir, "fail-once-a.txt", "fail-once-b.txt")
        runScenario("settings-and-retry")

        val collection = context().collections.list().single { it.name == "Notes renamed" }
        assertEquals("Archive", context().collections.list().single { it.id != collection.id }.name, "a refused rename removes nothing")
        assertEquals("eng+deu", collection.ocrLanguages, "the saved OCR languages are the collection's own")
        assertEquals(DocumentStatus.COMPLETE, findDocument("Notes renamed", "fail-once-a.txt")!!.status, "the single retry read the managed copy again")
        assertEquals(DocumentStatus.COMPLETE, findDocument("Notes renamed", "fail-once-b.txt")!!.status, "retry all read the other eligible document")
        assertEquals(
            2,
            context().jobs.list(limit = 50).count { it.type == JobType.RETRY },
            "a single retry and a collection-wide retry are two attempts, and the all-refused call queues none",
        )
        // The retry re-read the copy InfoScry already held with the same OCR languages, so it reused the
        // unit the first attempt committed; the acceptance extractor records what each attempt produced and
        // skipped, which makes "no repeated compatible work" observed rather than assumed. (This is the
        // checkpoint-reuse path; reusing real OCR results is ticket 04's own gate.)
        assertEquals(
            listOf("unit-0"),
            extractorRun.reused("fail-once-a.txt"),
            "a retry with unchanged settings must skip the unit the first attempt committed",
        )
        assertEquals(
            listOf("unit-0", "unit-1"),
            extractorRun.produced("fail-once-a.txt"),
            "the committed unit is not read again, and the unit the attempt never reached is",
        )
        // Changing the default affects new readings, while Retry resumes this document's failed snapshot.
        assertEquals(
            listOf("unit-0", "unit-1"),
            extractorRun.produced("fail-once-b.txt"),
            "a default change does not invalidate the failed attempt’s frozen reading",
        )
        assertEquals(2, context().documents.countListing(DocumentListing(collectionId = collection.id)), "a retry never adds a document")
    }

    @Test
    fun `single and bulk deletion leave other collections and external originals alone`() {
        startServer("Notes", "Archive")
        importThroughTheRoute("Archive", AcceptanceFixtures.files(sourcesDir, "archive/keep-a.txt", "archive/keep-b.txt"))
        picker.files = AcceptanceFixtures.files(
            sourcesDir,
            "single/one.txt",
            "single/two.txt",
            "single/three.txt",
            "single/four.txt",
        )
        runScenario("document-deletion")

        for (name in listOf("one.txt", "two.txt", "three.txt", "four.txt")) {
            assertNull(findDocument("Notes", name), "$name must be gone from the archive, not only from the table")
        }
        assertEquals(0, context().documents.countListing(DocumentListing(collectionId = collectionId("Notes"))))
        assertEquals(2, context().documents.countListing(DocumentListing(collectionId = collectionId("Archive"))), "another collection keeps its documents")
        // InfoScry never modifies an original outside its own library, whether or not a document was deleted.
        for (name in listOf("single/one.txt", "single/two.txt", "single/three.txt", "single/four.txt")) {
            assertTrue(Files.exists(sourcesDir.resolve(name)), "the external original $name must be untouched")
        }
    }

    @Test
    fun `deleting a collection during an import restores its status and finishes without it`() {
        startServer("Notes", "Archive")
        importThroughTheRoute("Archive", AcceptanceFixtures.files(sourcesDir, "archive/keep-a.txt", "archive/keep-b.txt"))
        val archiveDocuments = documentCount("Archive")
        val notesId = collectionId("Notes")
        // The deletion's index phase waits for this, so the operation is genuinely unfinished while the
        // browser reloads and finds it again; the extraction waits for the other gate, so the import is
        // genuinely still running when the collection goes away.
        val deletionHold = AcceptanceGate()
        indexHold = deletionHold
        picker.folder = AcceptanceFixtures.folder(sourcesDir, "held")
        runScenario("collection-deletion-during-import") { mark ->
            when (mark) {
                "deletion-pending-restored" -> deletionHold.release()
                // The deletion is finished; the import that was reading a document of the deleted
                // collection is now let go, and has to stop rather than write into what is gone.
                "collection-deleted" -> gate.release()
            }
        }

        assertEquals(listOf("Archive"), collectionNames(), "the deleted collection is gone and the other one stays")
        assertEquals(archiveDocuments, documentCount("Archive"), "a deletion beside a collection removes nothing of it")
        for (name in listOf("held/held-01.txt", "held/held-02.txt", "held/held-03.txt")) {
            assertTrue(Files.exists(sourcesDir.resolve(name)), "the import's external originals must survive the collection deletion")
        }
        assertTrue(deletionHold.isReleased, "the scenario must have reached the restored deletion status")
        assertTrue(gate.isReleased, "the held extraction must have been released after the deletion finished")
        assertEquals(
            0,
            context().documents.countListing(DocumentListing(collectionId = notesId)),
            "nothing of the deleted collection may be left to read",
        )
        // The collection's own records went with it: `jobs.collection_id` cascades, so the running
        // import's history is not left behind as an orphan the browser could list again.
        assertTrue(
            context().jobs.list(limit = 50).none { it.collectionId == notesId },
            "a deleted collection takes its import history with it",
        )
    }

    @Test
    fun `deleting a document during its own multi-file import continues the rest and survives a restart`() {
        startServer("Notes")
        picker.folder = AcceptanceFixtures.folder(sourcesDir, "held")
        runScenario("multi-file-continuation") { mark ->
            if (mark == "document-deleted") gate.release()
        }

        assertNull(findDocument("Notes", "held-01.txt"), "the removed document must not be resurrected by the import that held it")
        assertEquals(DocumentStatus.COMPLETE, findDocument("Notes", "held-02.txt")!!.status, "the import continues with the next file")
        assertEquals(DocumentStatus.COMPLETE, findDocument("Notes", "held-03.txt")!!.status, "and with the one after it")
        assertEquals(2, documentCount("Notes"), "the removed document leaves no row behind")
        val job = context().jobs.listImports(collectionId("Notes"), limit = 1).single()
        assertEquals(JobState.COMPLETE, job.state, "the import finishes once nothing is left for it")
        val items = context().importItems.listForJob(job.id)
        assertEquals(
            ImportItemOutcome.CANCELLED,
            items.single { it.sourcePath.contains("held-01.txt") == true }.outcome,
            "the removed file keeps its own disposition instead of reading as imported",
        )
        assertEquals(2, items.count { it.outcome == ImportItemOutcome.IMPORTED }, "the other two files were imported")

        // A real restart: a second process over the same archive has to find the same durable state.
        stopServer()
        startServer()
        runScenario("after-restart")
        assertEquals(2, documentCount("Notes"), "the archive after the restart holds what the first process left")
        assertNull(findDocument("Notes", "held-01.txt"))
    }

    /** Ticket 02: a finished import's history row carries no stage, in particular not "Queued". */
    @Test
    fun `a finished import's history row shows no stale stage`() {
        startServer("Notes")
        picker.folder = AcceptanceFixtures.folder(sourcesDir, "single")
        runScenario("history-final-stage")

        val job = context().jobs.listImports(collectionId("Notes"), limit = 1).single()
        assertEquals(JobState.COMPLETE, job.state)
        assertEquals(4, documentCount("Notes"))
    }

    /** Ticket 05: an unsupported file is neither a document nor an import item nor part of the counts. */
    @Test
    fun `a folder with an unsupported file imports only the supported files`() {
        startServer("Notes")
        picker.folder = AcceptanceFixtures.folder(sourcesDir, "unsupported")
        runScenario("unsupported-skipped")

        assertEquals(2, documentCount("Notes"))
        assertNull(findDocument("Notes", "blob.bin"), "the unsupported file must leave no document row")
        val job = context().jobs.listImports(collectionId("Notes"), limit = 1).single()
        val items = context().importItems.listForJob(job.id)
        assertEquals(2, items.size, "the unsupported file must leave no import item")
        assertTrue(items.none { it.sourcePath.contains("blob.bin") == true })
        assertTrue(Files.exists(sourcesDir.resolve("unsupported").resolve("blob.bin")), "the external original is untouched")
    }

    /** Ticket 06: "Only these extensions" in one collection and "All except these extensions" in another. */
    @Test
    fun `include and exclude extension filters import only the expected files`() {
        startServer("Notes", "Archive")
        picker.folder = AcceptanceFixtures.folder(sourcesDir, "types")
        runScenario("extension-filters")

        assertEquals(listOf("alpha.txt", "beta.TXT"), documentNames("Notes"), "include txt keeps both, whatever the case")
        assertEquals(
            listOf("alpha.txt", "beta.TXT", "delta.dat"),
            documentNames("Archive"),
            "exclude log keeps everything else",
        )
    }

    /** Ticket 07: the collection's own ignore list, edited in SETTINGS, applies to the next import. */
    @Test
    fun `edited ignore patterns keep system and temporary files out of an import`() {
        startServer("Notes")
        picker.folder = AcceptanceFixtures.folder(sourcesDir, "ignored")
        runScenario("ignore-patterns")

        assertEquals(listOf("real-a.txt", "real-b.txt"), documentNames("Notes"))
        val saved = context().collections.ignorePatterns(collectionId("Notes"))
        assertTrue("scratch-*" in saved, "the pattern the reader added is the collection's own now: $saved")
        assertTrue(".DS_Store" in saved && "*.tmp" in saved, "the defaults stay in the list: $saved")
        for (name in listOf(".DS_Store", "draft.tmp", "scratch-1.txt")) {
            assertTrue(Files.exists(sourcesDir.resolve("ignored").resolve(name)), "$name must be untouched outside InfoScry")
        }
    }

    /** Ticket 08: a failed document is retried with a chosen OCR method and becomes searchable. */
    @Test
    fun `a failed document retried with a chosen OCR method becomes searchable`() {
        startServer("Notes")
        picker.files = AcceptanceFixtures.files(sourcesDir, "fail-once-scan.png")
        runScenario("retry-with-chosen-ocr")

        assertEquals(DocumentStatus.COMPLETE, findDocument("Notes", "fail-once-scan.png")!!.status)
        val retry = context().jobs.list(limit = 50).single { it.type == JobType.RETRY }
        assertEquals(JobState.COMPLETE, retry.state)
        assertEquals(
            listOf("unit-0", "unit-1"),
            extractorRun.produced("fail-once-scan.png"),
            "retry resumes the frozen reading without repeating the committed unit",
        )
    }

    // ---- Server ----

    private fun startServer(vararg collections: String) = startServerOver(tempDir.resolve("data"), *collections)

    /**
     * A server over one archive directory.
     *
     * The used-Default case starts it over an archive it built on the baseline schema beforehand.
     */
    private fun startServerOver(dataDir: Path, vararg collections: String) {
        val started = ApiTestServer(
            dataDir,
            // The production index removal, with an optional hold before it: the index phase is what
            // keeps an admitted collection deletion unfinished, which is the state a reopened Admin has
            // to restore. Nothing is skipped, so no stale index rows survive the scenario.
            index = { collectionId ->
                indexHold?.await()
                harness!!.context.index().deleteCollection(collectionId)
            },
            picker = picker::choose,
            // A chosen engine is admitted like a rescan, which needs an embedder this archive has no model for.
            rescanEmbedder = { true },
            // A retry asks this machine what it can do. The acceptance archive has no pinned model and the
            // extractor needs no tool, so the answer is stated rather than probed.
            retryPrerequisites = { collection ->
                RetryPrerequisites(
                    settings = ToolProbe.extractionSettings(collection.ocrLanguages),
                    ocrToolAvailable = true,
                    ebookToolAvailable = true,
                    embeddingModelAvailable = true,
                )
            },
        )
        harness = started
        val extractor = CollectionsAcceptanceExtractor(gate)
        extractorRun = extractor.recorded
        ImportJobHandler.attachTo(
            started.context,
            acceptancePipeline(started.context, extractor),
            Chunker(WhitespaceTokenCounter()),
            documentEmbedder = { TestDocumentEmbedder() },
        )
        collections.forEach { started.context.collections.create(it) }
    }

    private fun stopServer() {
        harness?.close()
        harness = null
    }

    private fun context(): AppContext = harness!!.context

    // ---- The browser ----

    /**
     * Runs one scenario and lets the test answer the marks the browser cannot answer itself.
     *
     * The output is polled rather than read at the end, because a mark is a request for the test to do
     * something — release a held extraction — that the scenario then waits on. The process is killed on
     * the deadline, so a scenario that never reaches its mark fails instead of hanging the suite.
     */
    private fun runScenario(name: String, onMark: (String) -> Unit = {}) {
        val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "collections-browser-acceptance.mjs")
        check(Files.exists(script)) { "browser acceptance script is missing: $script" }
        val outputFile = Files.createTempFile("infoscry-collections-browser-", ".log")
        val process = ProcessBuilder("node", script.toString())
            .directory(script.parent.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .apply {
                environment()["BASE_URL"] = harness!!.url
                environment()["SCENARIO"] = name
                environment()["FIXTURES"] = sourcesDir.toString()
            }
            .start()
        val answered = LinkedHashSet<String>()
        val deadline = System.nanoTime() + SCENARIO_TIMEOUT_NANOS
        while (process.isAlive && System.nanoTime() < deadline) {
            answerMarks(outputFile, answered, onMark)
            Thread.sleep(MARK_POLL_MILLIS)
        }
        val finished = !process.isAlive
        if (!finished) process.destroyForcibly()
        // A scenario's last mark can arrive with the process's final flush, so it is answered even when
        // the process has already ended: a mark nobody answers is a hold nobody releases.
        answerMarks(outputFile, answered, onMark)
        val output = Files.readString(outputFile)
        outputFile.toFile().delete()
        if (!finished) error("browser scenario '$name' timed out\n$output")
        check(process.exitValue() == 0) { "browser scenario '$name' failed:\n$output" }
    }

    /** Answers every mark in [outputFile] that has not been answered yet, in the order it was printed. */
    private fun answerMarks(outputFile: Path, answered: MutableSet<String>, onMark: (String) -> Unit) {
        Files.readString(outputFile).lineSequence()
            .filter { it.startsWith(MARK_PREFIX) }
            .map { it.removePrefix(MARK_PREFIX).trim() }
            .forEach { mark -> if (answered.add(mark)) onMark(mark) }
    }

    // ---- Archive facts ----

    private fun collectionNames(): List<String> = context().collections.list().map { it.name }

    private fun documentNames(name: String): List<String> = context().documents.listListing(
        DocumentListing(collectionId = collectionId(name)),
        limit = 200,
    ).map { it.originalFilename }.sorted()

    private fun collectionId(name: String): CollectionId =
        context().collectionService.requireActiveByNameOrId(name).id

    private fun documentCount(name: String): Int = context().documents.countListing(DocumentListing(collectionId = collectionId(name)))

    private fun findDocument(collectionName: String, filename: String): Document? {
        val collection = context().collectionService.requireActiveByNameOrId(collectionName)
        return context().documents.listListing(
            DocumentListing(collectionId = collection.id, filenameContains = filename),
            limit = 200,
        ).singleOrNull { it.originalFilename == filename }
    }

    private fun progressOf(documentId: DocumentId) = context().content.documentProgress(listOf(documentId))[documentId]

    /**
     * One import through the real route, awaited, for the archive a scenario starts from.
     *
     * A scenario's own imports are driven by the browser; this is only for state that must exist *before*
     * the reader opens, and it goes through the same route the CLI and the browser use.
     */
    private fun importThroughTheRoute(collectionName: String, paths: List<String>) = runBlocking {
        val collection = context().collectionService.requireActiveByNameOrId(collectionName)
        val previewRequest = infoscry.jobs.ImportPreviewRequest(collection.id, paths,
            method = collection.ocrSettings().defaultMethod)
        val preview = context().importPreviewService.preview(previewRequest)
        val body = ApiJson.encodeToString(ImportRequest(collection = collection.id.value, paths = paths,
            method = previewRequest.method.id, previewHash = preview.previewHash, requestId = UUID.randomUUID().toString()))
        val response = harness!!.request(HttpMethod.Post, "/api/imports", body, Credential.BEARER)
        check(response.status == HttpStatusCode.Accepted) {
            "the acceptance import must be accepted, was ${response.status}: ${response.bodyAsText()}"
        }
        val jobId = ApiJson.decodeFromString<ImportAcceptedResponse>(response.bodyAsText()).job.id
        withTimeout(IMPORT_TIMEOUT_MILLIS) {
            while (true) {
                val job = ApiJson.decodeFromString<JobResponse>(harness!!.get("/api/jobs/${jobId.value}").bodyAsText()).job
                if (job.state in TERMINAL_JOB_STATES) {
                    check(job.state == JobState.COMPLETE) {
                        "the acceptance import must complete, was ${job.state} (${job.errorCode})"
                    }
                    return@withTimeout
                }
                delay(JOB_POLL_MILLIS)
            }
        }
    }

    private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** An archive directory whose baseline-schema database [seed] has filled before any server opens it. */
    private fun usedDefaultArchive(name: String, seed: (java.sql.Connection) -> Unit): Path {
        val dataDir = tempDir.resolve(name)
        Files.createDirectories(dataDir)
        val database = Database(dataDir.resolve("infoscry.db"))
        try {
            SchemaMigrator(database).migrate()
            database.transaction { connection -> seed(connection) }
        } finally {
            database.close()
        }
        return dataDir
    }

    private fun seedProfile(name: String) {
        context().llm.create(
            LlmProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = "model",
                contextWindow = 10_000,
                maxOutputTokens = 64,
                inputPricePerMillion = 0.0,
                outputPricePerMillion = 0.0,
                cacheReadPricePerMillion = 0.0,
                enabled = true,
            ),
        )
    }

    /**
     * A document as an archive written before progress was recorded looks: a finished summary and no
     * content units, so its unit kind and its methods are genuinely unknown.
     */
    private fun seedLegacySummaryDocument(collectionName: String): DocumentId {
        val collection = context().collectionService.requireActiveByNameOrId(collectionName)
        val documentId = DocumentId.new()
        val now = Instants.now()
        context().documents.insert(
            Document(
                id = documentId,
                collectionId = collection.id,
                sha256 = "b".repeat(64),
                mediaType = "text/plain",
                originalFilename = AcceptanceFixtures.LEGACY_FILENAME,
                sourcePath = "fixtures/${AcceptanceFixtures.LEGACY_FILENAME}",
                sizeBytes = 128,
                status = DocumentStatus.COMPLETE,
                createdAt = now,
                updatedAt = now,
                title = null,
                author = null,
                language = "en",
            ),
        )
        context().database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO document_extractions (document_id, fingerprint, total_units, failed_units, metadata, completed_at) " +
                    "VALUES (?, ?, ?, 0, '{}', ?)",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, "legacy-summary-fingerprint")
                statement.setInt(3, AcceptanceFixtures.LEGACY_TOTAL_UNITS)
                statement.setString(4, now)
                statement.executeUpdate()
            }
        }
        return documentId
    }

    private companion object {
        const val MARK_PREFIX = "MARK:"
        const val MARK_POLL_MILLIS = 100L
        const val JOB_POLL_MILLIS = 50L
        const val IMPORT_TIMEOUT_MILLIS = 60_000L
        const val SCENARIO_TIMEOUT_NANOS = 300_000_000_000L
        val TERMINAL_JOB_STATES = setOf(JobState.COMPLETE, JobState.FAILED, JobState.CANCELLED)
    }
}
