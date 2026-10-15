/*
 * Ticket 08 browser acceptance: Admin page browsing, revision-pinned Ask citations, and page-selective
 * PDF rescans. Chromium drives the built reader against a temporary InfoScry archive and local fakes.
 */
package infoscry.server

import infoscry.AppContext
import infoscry.EXTERNAL_TAG
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.document.RestoreFixtures
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DocumentExtractor
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionInput
import infoscry.extract.PdfPageSelector
import infoscry.extract.PlainTextExtractor
import infoscry.jobs.DispatchingJobHandler
import infoscry.jobs.Harness
import infoscry.jobs.JobRunner
import infoscry.jobs.RescanJobHandler
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmJson
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.ReadingMethod
import infoscry.ocr.RescanSelection
import infoscry.storage.Instants
import io.ktor.client.statement.bodyAsText
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.jupiter.api.Tag

@Tag(EXTERNAL_TAG)
class DocumentBrowserAcceptanceTest {
    private lateinit var tempDir: Path
    private lateinit var serverDataDir: Path
    private lateinit var seeded: Seeded
    private var citationRevisionId: String? = null
    private var citationUnitId: infoscry.domain.ContentUnitId? = null
    private var harness: ApiTestServer? = null
    private var scanEngine: RecordingPageEngine? = null

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("infoscry-document-browser-")
        serverDataDir = tempDir.resolve("data")
        seeded = seedThreePagePdf()
    }

    @AfterTest
    fun tearDown() {
        harness?.close()
        harness = null
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `Admin page review measures workspace geometry scrolling and divider input`() {
        startServer()
        runScenario("page-browser")
    }

    @Test
    fun `Ask displays a citation and opens its superseded revision without a live pager`() {
        FakeOpenAiServer(listOf(answerStream("The archive states this on page one [S1]."), titleCompletion("Page one"))).use { fake ->
            startServer()
            createAskProfile(fake.url)
            publishCopyOfActiveRevision()
            runScenario("ask-old-citation") { mark ->
                if (mark == "ask-finished") restoreOriginalRevision()
            }
            assertTrue(fake.handledRequests >= 2, "the local provider answered and titled the Ask")
            val citations = context().database.read { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM citations WHERE validated = 1 AND revision_id = ? AND source_unit_id = ?",
                ).use { query ->
                    query.setString(1, checkNotNull(citationRevisionId))
                    query.setString(2, checkNotNull(citationUnitId).value)
                    query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
            }
            assertEquals(1, citations, "the answer's citation was persisted before its revision became historical")
        }
    }

    @Test
    fun `Image citation opens its exact historical page image`() {
        seeded = seedSingleImageDocument()
        FakeOpenAiServer(listOf(answerStream("The image says cobalt lantern 419 [S1]."), titleCompletion("Image citation"))).use { fake ->
            startServer()
            createAskProfile(fake.url)
            publishCopyOfActiveRevision()
            runScenario("ask-image-old-citation") { mark ->
                if (mark == "ask-finished") restoreOriginalRevision()
            }
            assertTrue(fake.handledRequests >= 2, "the local provider answered and titled the Ask")
        }
    }

    @Test
    fun `Image citation without a revision shows its saved excerpt without pairing requests`() {
        seeded = seedSingleImageDocument()
        FakeOpenAiServer(listOf(answerStream("The image says cobalt lantern 419 [S1]."), titleCompletion("Image excerpt"))).use { fake ->
            startServer()
            createAskProfile(fake.url)
            publishCopyOfActiveRevision()
            // Generate and fully title the Ask in one browser process, then stop it before applying
            // the legacy unknown-revision fixture so the first history GET reads that actual DB state.
            runScenario("ask-image-create")
            assertTrue(fake.handledRequests >= 2, "the local provider answered and titled the Ask")
            restoreOriginalRevision()
            context().database.transaction { connection ->
                connection.prepareStatement("UPDATE citations SET revision_id = NULL WHERE revision_id = ?").use { update ->
                    update.setString(1, checkNotNull(citationRevisionId))
                    assertTrue(update.executeUpdate() > 0, "the fixture removes the saved citation revision")
                }
                connection.prepareStatement("SELECT COUNT(*) FROM citations WHERE revision_id = ?").use { query ->
                    query.setString(1, checkNotNull(citationRevisionId))
                    query.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1), "all saved citations for this image now have unknown revisions")
                    }
                }
            }
            runScenario("ask-image-unknown-citation")
            assertTrue(fake.handledRequests >= 2, "the local provider answered and titled the Ask")
        }
    }

    @Test
    fun `Ask uses a prompt created in Admin`() {
        // Opening Admin reads the provider's model catalog first, so that answer is the fake's first response.
        FakeOpenAiServer(listOf(catalogResponse(), answerStream("Short answer [S1]."), titleCompletion("Short prompt"))).use { fake ->
            startServer()
            createAskProfile(fake.url)
            runScenario("ask-prompt-from-admin")
            assertTrue(fake.handledRequests >= 2, "the local provider answered and titled the Ask")
            val promptsBody = runBlocking {
                harness!!.request(io.ktor.http.HttpMethod.Get, "/api/collections/default/prompts", body = null, credential = Credential.BEARER).bodyAsText()
            }
            val coreRules = LlmJson.parseToJsonElement(promptsBody).jsonObject.getValue("coreRules").jsonPrimitive.content
            val answerRequest = LlmJson.parseToJsonElement(fake.requests.first { it.path.endsWith("/chat/completions") }.body).jsonObject
            val systemMessages = answerRequest.getValue("messages").jsonArray
                .map { it.jsonObject }
                .filter { it.getValue("role").jsonPrimitive.content == "system" }
            assertEquals(
                coreRules + "\n\n" + "Answer in one line.",
                systemMessages.last().getValue("content").jsonPrimitive.content,
                "the answer's system message is the core rules followed by the selected Admin prompt",
            )
        }
    }

    @Test
    fun `rescan preview selects one of three PDF pages and sends only that page to the fake engine`() {
        startRescanServer()
        val baselinePages = context().revisions.pages(seeded.initialRevisionId)
        runScenario("page-selection")
        var operation = context().ocrOperations.operations(seeded.documentId).first()
        val rescanJob = context().jobs.list(limit = 10).first { it.type == JobType.RESCAN }
        assertEquals(JobState.COMPLETE, rescanJob.state, "the browser-confirmed page scan ran to completion")
        operation = context().ocrOperations.operation(operation.operationId)!!
        val jobs = context().jobs.list(10).joinToString { "${it.type}/${it.state}/${it.errorCode}" }
        assertEquals(OcrOperationStage.COMPLETE, operation.stage, "the selected-page attempt is terminal after its job finishes; jobs=$jobs")
        assertEquals(1, operation.pageTotal, "the admitted operation is scoped to the selected page; jobs=$jobs")
        assertEquals(1, operation.pagesCommitted, "the completed operation committed its one selected page; operation=$operation; jobs=$jobs")
        assertEquals(1, checkNotNull(scanEngine).calls.get(), "only the scanned page reached the page-reading engine; jobs=$jobs")
        assertEquals(listOf(0), scanEngine!!.ordinals.toList(), "the page reader received only zero-based clean-text PDF page 1")
        val document = context().documents.get(seeded.documentId)!!
        val revision = context().revisions.activeRevisionId(document.id)!!
        val publishedPages = context().revisions.pages(revision)
        val initialUnitIds = baselinePages.map { it.unitId }
        assertEquals(initialUnitIds, context().revisions.pages(revision).map { it.unitId }, "publication retained all three stable page IDs")
        assertEquals(3, publishedPages.size, "publication retained all three document pages")
        for (ordinal in 1..2) {
            assertEquals(baselinePages[ordinal].extractedText, publishedPages[ordinal].extractedText, "unselected page $ordinal keeps its published text")
            assertEquals(baselinePages[ordinal].sourceImage, publishedPages[ordinal].sourceImage, "unselected page $ordinal keeps its image provenance")
            assertEquals(baselinePages[ordinal].producerRevisionId, publishedPages[ordinal].producerRevisionId, "unselected page $ordinal keeps its producer provenance")
        }
        assertEquals("OCR result for scanned page", publishedPages.first().extractedText)
        assertEquals(infoscry.storage.RevisionState.SUPERSEDED, context().revisions.revision(seeded.initialRevisionId)?.state, "the previous complete reading remains available in history")
    }

    @Test
    fun `retry resumes an explicit page selection from its committed checkpoint`() {
        startRescanServer(failFirstOrdinal = 2)
        runScenario("page-selection-retry")

        val operations = context().ocrOperations.operations(seeded.documentId)
        val completed = operations.first { it.stage == OcrOperationStage.COMPLETE }
        assertEquals(1, operations.size, "resume continues the same admitted operation instead of creating a duplicate")
        assertEquals(2, completed.pageTotal, "retry retains the original two-page scope")
        assertEquals(2, completed.pagesCommitted, "resume keeps the first committed page and completes the remaining selection")
        assertEquals(RescanSelection.explicit(listOf(0, 2)), completed.selection, "resume does not broaden the user's page set")
        assertEquals(listOf(0, 2, 2), checkNotNull(scanEngine).ordinals.toList(), "committed page 0 is not read a second time")
        assertEquals(3, checkNotNull(scanEngine).calls.get(), "resume dispatches only the previously failed selected page")
        val active = context().revisions.activeRevisionId(seeded.documentId)!!
        assertEquals("OCR result for scanned page", context().revisions.pages(active).first { it.ordinal == 0 }.extractedText)
        assertEquals("OCR result for scanned page", context().revisions.pages(active).first { it.ordinal == 2 }.extractedText)
        assertTrue(
            context().revisions.pages(seeded.initialRevisionId).first { it.ordinal == 0 }.extractedText.contains("The first page records the archive summary."),
            "the original reading remains available in history",
        )
    }

    @Test
    fun `Selectable PDF actions build through the real pipeline, serve a verified download and fit every viewport width`() {
        startServer()
        // startServer() alone starts no background job runner (unlike startRescanServer(), which wires its
        // own RESCAN-only runner): the real production composition root is attached explicitly here, with
        // the same fake embedder/chunker every other browser-acceptance test uses, so the real
        // SelectablePdfJobHandler this feature registers in that one composition root actually runs.
        infoscry.jobs.ImportJobHandler.attachTo(
            context(),
            documentEmbedder = { TestDocumentEmbedder() },
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
        )
        runScenario("selectable-pdf")
        val revisionId = context().revisions.activeRevisionId(seeded.documentId)!!
        val status = context().selectablePdfService.status(CollectionId("default"), seeded.documentId, revisionId)
        assertEquals(infoscry.pdf.PdfArtifactState.READY, status.state, "the three native pages build to a fully selectable export")
        assertEquals(3, status.totalPages)
        assertEquals(3, status.selectablePages)
        assertEquals(
            1,
            context().jobs.list(limit = 50).count { it.type == JobType.SELECTABLE_PDF },
            "the browser's repeated GET/POST never enqueued a second selectable-PDF job",
        )
    }

    @Test
    fun `page resources stay paired across delayed and missing images and unsupported text stays readable`() {
        val textDocumentId = seedResourceTextDocument()
        startServer()
        runScenario("page-resources", unsupportedDocumentId = textDocumentId.value)
    }

    private fun seedResourceTextDocument(): DocumentId {
        val source = tempDir.resolve("sources/resource-notes.txt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "Plain source text remains readable without any page image.\n")
        return Harness(tempDir, seedCollection = false).use { importer ->
            importer.importDurably(listOf(source), PlainTextExtractor()).items.single().documentId!!
        }
    }

    private fun seedThreePagePdf(): Seeded {
        var documentId: DocumentId? = null
        lateinit var activeRevision: String
        val source = tempDir.resolve("sources/three-pages.pdf")
        Files.createDirectories(source.parent)
        PDDocument().use { pdf ->
            repeat(3) { index ->
                val page = PDPage()
                pdf.addPage(page)
                if (index < 2) PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.newLineAtOffset(72f, 700f)
                    cleanPdfLines(index).forEach { line ->
                        content.showText(line)
                        content.newLineAtOffset(0f, -18f)
                    }
                    content.endText()
                } else {
                    val scan = BufferedImage(400, 500, BufferedImage.TYPE_INT_RGB)
                    for (x in 0 until scan.width) for (y in 0 until scan.height) scan.setRGB(x, y, 0xFFFFFF)
                    PDPageContentStream(pdf, page).use { content ->
                        content.drawImage(LosslessFactory.createFromImage(pdf, scan), 80f, 120f, 450f, 600f)
                    }
                }
            }
            pdf.save(source.toFile())
        }
        Harness(tempDir).use { importer ->
            val result = importer.importDurably(listOf(source), ThreePageUnits())
            documentId = result.items.single().documentId!!
        }
        val importedDocumentId = checkNotNull(documentId)
        AppContext.open(tempDir.resolve("data")).use { archive ->
            activeRevision = archive.revisions.activeRevisionId(importedDocumentId)!!
            val managed = archive.library.managedPathOf(archive.documents.get(importedDocumentId)!!)
            Loader.loadPDF(managed.toFile()).use { pdf ->
                assertEquals(listOf(3), PdfPageSelector().pagesToRead(pdf), "the generated PDF has two clean text pages and one scan")
            }
        }
        return Seeded(importedDocumentId, activeRevision)
    }

    private fun seedSingleImageDocument(): Seeded {
        val imageWorkspace = tempDir.resolve("image-fixture")
        serverDataDir = imageWorkspace.resolve("data")
        val source = imageWorkspace.resolve("sources/citation-image.png")
        Files.createDirectories(source.parent)
        val image = BufferedImage(400, 500, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until image.width) for (y in 0 until image.height) image.setRGB(x, y, 0xFFFFFF)
        ImageIO.write(image, "png", source.toFile())
        val imported = Harness(imageWorkspace).use { it.importDurably(listOf(source), SingleImageUnit()) }
        val documentId = imported.items.single().documentId!!
        val revisionId = AppContext.open(serverDataDir).use { archive ->
            archive.revisions.activeRevisionId(documentId)!!
        }
        return Seeded(documentId, revisionId)
    }

    private fun startServer() {
        harness = ApiTestServer(
            serverDataDir,
            queryEmbedder = { infoscry.embedding.QueryEmbedder { FloatArray(768) { if (it == 0) 1f else 0f } } },
            rescanEmbedder = { true },
            restoreEmbedder = { TestDocumentEmbedder() },
        )
        context().attachChunker(Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)))
    }

    private fun startRescanServer(failFirstOrdinal: Int? = null) {
        startServer()
        val reader = context().ocrProfiles.create(
            "Local browser reader",
            OcrProfileRevisionDraft(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = "fake-vision",
                contextWindow = 32_000,
                maxOutputTokens = 2_048,
                endpoint = "http://127.0.0.1:9/v1",
                apiKeyEnvironmentVariable = PRESENT_KEY_VARIABLE,
            ),
            enabled = true,
        )
        context().ocrProfiles.recordImageCapability(reader.revision.revisionId, true, Instants.now())
        context().collections.updateOcrSettings(
            CollectionId("default"),
            CollectionOcrSettings("eng", ReadingMethod.Llm(reader.id)),
        )
        scanEngine = RecordingPageEngine(failFirstOrdinal)
        val handler = RescanJobHandler(
            paths = context().paths,
            collections = context().collections,
            documents = context().documents,
            library = context().library,
            mutations = context().mutations,
            jobs = context().jobs,
            revisions = context().revisions,
            operations = context().ocrOperations,
            publication = context().revisionPublication,
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = { TestDocumentEmbedder() },
            engineFor = { _, _, _ -> checkNotNull(scanEngine) },
            profileRevisionOf = { revisionId -> context().ocrProfiles.findRevision(revisionId) },
        )
        val runner = JobRunner(
            store = context().jobs,
            collections = context().collections,
            mutations = context().mutations,
            handler = DispatchingJobHandler(mapOf(JobType.RESCAN to handler)),
        )
        context().attachJobRunner(runner)
        runner.start()
    }

    private fun createAskProfile(endpoint: String) {
        context().llm.create(
            LlmProfile(
                id = "browser-ask-profile",
                name = "Browser Ask",
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = "fake-chat",
                contextWindow = 32_000,
                maxOutputTokens = 2_048,
                inputPricePerMillion = 0.0,
                outputPricePerMillion = 0.0,
                cacheReadPricePerMillion = 0.0,
                enabled = true,
                endpoint = endpoint,
                apiKeyEnvironmentVariable = PRESENT_KEY_VARIABLE,
            ),
        )
        check(context().llm.setDefault(LlmPromptRole.ASK, "Browser Ask"))
    }

    private fun publishCopyOfActiveRevision() = runBlocking {
        val current = context().revisions.activeRevisionId(seeded.documentId)!!
        val pages = context().revisions.pages(current)
        val candidate = RestoreFixtures.stage(
            context(), seeded.documentId, current,
            pages.map { it.extractedText }, pages.map { it.unitId },
            locators = pages.map { it.locator },
            extractionMethods = pages.map { it.extractionMethod },
        )
        context().revisionPublication.publish(seeded.documentId, current, candidate)
        citationRevisionId = candidate
        citationUnitId = pages.first().unitId
        assertEquals(candidate, context().revisions.activeRevisionId(seeded.documentId))
    }

    private fun restoreOriginalRevision() = runBlocking {
        val current = context().revisions.activeRevisionId(seeded.documentId)!!
        assertEquals(citationRevisionId, current, "the revision being cited is active when Ask starts")
        context().revisionRestore.restore(
            CollectionId("default"), seeded.documentId, "acceptance-second-copy", current, seeded.initialRevisionId,
        )
        assertEquals(
            infoscry.storage.RevisionState.SUPERSEDED,
            context().revisions.revision(checkNotNull(citationRevisionId))?.state,
            "the Ask's pinned revision must become historical before opening its citation",
        )
        val citedUnitId = checkNotNull(citationUnitId)
        check(context().revisions.pageForUnit(checkNotNull(citationRevisionId), citedUnitId) != null)
        val response = runBlocking {
            harness!!.get(
                "/api/collections/default/sources/${citedUnitId.value}?offset=0&limit=16384&revision=${citationRevisionId}",
            )
        }
        check(response.status == io.ktor.http.HttpStatusCode.OK) {
            "the superseded citation source must remain readable, got ${response.status}: ${response.bodyAsText()}"
        }
    }

    private fun context() = checkNotNull(harness).context

    private fun runScenario(name: String, unsupportedDocumentId: String? = null, onMark: (String) -> Unit = {}) {
        val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "document-browser-acceptance.mjs")
        val outputFile = Files.createTempFile("infoscry-document-browser-", ".log")
        val evidenceDirectory = Paths.get(System.getProperty("user.dir"), ".superpowers", "sdd", "2026-10-09-workspace-and-page-review", "W07-evidence")
        Files.createDirectories(evidenceDirectory)
        val process = ProcessBuilder("node", script.toString()).directory(script.parent.toFile())
            .redirectErrorStream(true).redirectOutput(outputFile.toFile())
            .apply {
                environment()["BASE_URL"] = checkNotNull(harness).url
                environment()["SCENARIO"] = name
                environment()["DOCUMENT_ID"] = seeded.documentId.value
                environment()["SCREENSHOT_DIR"] = Paths.get(System.getProperty("user.dir"), "build", "w07-browser-evidence").toString()
                environment()["INITIAL_REVISION_ID"] = seeded.initialRevisionId
                unsupportedDocumentId?.let { environment()["UNSUPPORTED_DOCUMENT_ID"] = it }
                citationRevisionId?.let { environment()["CITATION_REVISION_ID"] = it }
            }.start()
        val answered = mutableSetOf<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
        try {
            while (process.isAlive && System.nanoTime() < deadline) {
                val output = Files.readString(outputFile)
                Regex("MARK: ([a-z0-9-]+)").findAll(output).forEach { match ->
                    val mark = match.groupValues[1]
                    if (answered.add(mark)) onMark(mark)
                }
                process.waitFor(100, TimeUnit.MILLISECONDS)
            }
            if (process.isAlive) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                error("Document browser acceptance timed out: ${Files.readString(outputFile)}")
            }
            check(process.exitValue() == 0) { "Document browser acceptance failed: ${Files.readString(outputFile)}" }
        } finally {
            Files.copy(outputFile, evidenceDirectory.resolve("$name.log"), StandardCopyOption.REPLACE_EXISTING)
            if (process.isAlive) process.destroyForcibly()
            Files.deleteIfExists(outputFile)
        }
    }

    private data class Seeded(val documentId: DocumentId, val initialRevisionId: String)

    private class ThreePageUnits : DocumentExtractor {
        override val supportedMediaTypes = setOf("application/pdf")
        override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
            val texts = listOf(
                cleanPdfLines(0).joinToString(" "),
                cleanPdfLines(1).joinToString(" "),
                "Initial scan text.",
            )
            texts.forEachIndexed { ordinal, text ->
                input.boundary.unit {
                    emit(ExtractionEvent.UnitReady(
                        key = "page:${ordinal + 1}", ordinal = ordinal,
                        unit = ContentUnitDraft(
                            locator = infoscry.domain.SourceLocation.PdfPage(ordinal + 1),
                            extractedText = text, searchText = text,
                            method = infoscry.domain.ExtractionMethod.DIRECT_TEXT,
                        ),
                    ))
                }
            }
            input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = texts.size)) }
        }
    }

    private class SingleImageUnit : DocumentExtractor {
        override val supportedMediaTypes = setOf("image/png")
        override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
            input.boundary.unit {
                emit(ExtractionEvent.UnitReady(
                    key = "image:1", ordinal = 0,
                    unit = ContentUnitDraft(
                        locator = infoscry.domain.SourceLocation.Image(input.originalFilename),
                        extractedText = "The photographed artifact bears the unique phrase Cobalt Lantern 419.",
                        searchText = "The photographed artifact bears the unique phrase Cobalt Lantern 419.",
                        method = infoscry.domain.ExtractionMethod.DIRECT_TEXT,
                    ),
                ))
            }
            input.boundary.unit { emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1)) }
        }
    }

    private class RecordingPageEngine(private val failFirstOrdinal: Int? = null) : PageOcrEngine {
        override val engine = OcrEngine.LLM
        val calls = AtomicInteger()
        val ordinals = ConcurrentLinkedQueue<Int>()
        private val failedOnce = AtomicBoolean(false)
        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
            delay(3_000)
            calls.incrementAndGet()
            ordinals.add(page.ordinal)
            if (page.ordinal == failFirstOrdinal && failedOnce.compareAndSet(false, true)) {
                throw IllegalStateException("synthetic fake provider failure")
            }
            return OcrPageResult("OCR result for scanned page", engine, page.sha256, modelVersion = "fake-browser-1")
        }
    }

    private companion object {
        const val PRESENT_KEY_VARIABLE = "PATH"
        fun cleanPdfLines(page: Int): List<String> = when (page) {
            0 -> listOf(
                "Clean searchable text. The committee reviewed every transaction in the quarterly report",
                "and confirmed that the supporting documents were complete. Each payment is listed in",
                "the appendix with its date, amount and the name of the officer who approved it before",
                "the money was released. The first page records the archive summary.",
            ) + List(120) { index ->
                "Archive page detail ${index + 1}: the report preserves its date, amount, officer, " +
                    "approval note, and supporting source so readers can verify the complete record."
            }
            else -> listOf(
                "Clean searchable text. Digital archives preserve complete records, detailed names,",
                "dates, descriptions, reliable annotations, and readable paragraphs for administrative",
                "review across the entire document, preserving context and the original sequence.",
            )
        }
        fun answerStream(text: String) = FakeOpenAiResponse(
            stream = true,
            body = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8}}\n\ndata: [DONE]\n\n",
        )
        fun catalogResponse() = FakeOpenAiResponse(body = """{"data":[{"id":"fake-chat"}]}""")
        fun titleCompletion(title: String) = FakeOpenAiResponse(
            body = """{"choices":[{"message":{"role":"assistant","content":"$title"}}],"usage":{"prompt_tokens":9,"completion_tokens":3}}""",
        )
    }
}
