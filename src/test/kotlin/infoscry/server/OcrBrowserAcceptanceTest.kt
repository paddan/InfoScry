/*
 * Browser acceptance for the OCR panels: profiles, collection settings, Scan again, automatic publication, text
 * history and restore.
 *
 * The real built reader in Chromium is driven by `web/e2e/ocr-browser-acceptance.mjs` against a real
 * InfoScry server on a temporary archive. This test owns the archive, the fixtures, the fakes and the
 * assertions that belong to the archive rather than to the screen: which settings are stored, how many
 * operations a double click created, what is searchable before and after a publication.
 *
 * Substitutions, and what they mean for what this test may claim:
 *
 * - the page-reading engine is a deterministic fake that answers with text the test dictates.
 *   The production RescanJobHandler handles staging and publication; no real OCR tool or provider
 *   is involved, so nothing here proves OCR quality or that a real engine is installed;
 * - the document embedder is `TestDocumentEmbedder`, because a temporary archive has no pinned model and no
 *   accelerator. Nothing here proves CoreML;
 * - the documents are generated 8x8 pictures imported through the real import path, so a "page" is one
 *   picture. A multi-page PDF reading is not covered here.
 *
 * Tagged `external` because it needs Node and Chromium: the default suite stays runnable everywhere.
 */
package infoscry.server

import infoscry.AppContext
import infoscry.EXTERNAL_TAG
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.domain.CollectionId
import infoscry.domain.JobType
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.EXTRACTOR_SCHEMA_VERSION
import infoscry.jobs.DispatchingJobHandler
import infoscry.jobs.FakePageEngine
import infoscry.jobs.Harness
import infoscry.jobs.JobRunner
import infoscry.jobs.PictureUnits
import infoscry.jobs.RecordingUnits
import infoscry.jobs.RescanHarness
import infoscry.jobs.RescanJobHandler
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.ImageLlmClient
import infoscry.ocr.PageOcrEngine
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Tag

@Tag(EXTERNAL_TAG)
class OcrBrowserAcceptanceTest {

    private lateinit var tempDir: Path
    private var harness: ApiTestServer? = null
    private var gate: AcceptanceGate = AcceptanceGate()
    private var engine: BrowserFakeEngine? = null
    private var facts: JsonObject = JsonObject(emptyMap())

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("infoscry-ocr-acceptance")
        gate = AcceptanceGate()
    }

    @AfterTest
    fun tearDown() {
        // A reading still waiting on the gate must not keep the archive open while it closes.
        gate.release()
        harness?.close()
        harness = null
        tempDir.toFile().deleteRecursively()
    }

    // ---- Scenarios ----

    @Test
    fun `an LLM profile is checked for image reading in the browser and then offered as a reading method`() {
        val data = tempDir.resolve("data")
        // The check sends the synthetic image to the profile's endpoint: a loopback fake answers it.
        FakeOpenAiServer(listOf(FakeOpenAiResponse(body = imageProbeAnswer()))).use { vision ->
            AppContext.open(data).use { context ->
                context.collections.create("Default")
                llmProfile(context, PROFILE_NAME, "vision-model", vision.url)
            }
            startServer(data)
            runScenario("llm-image-reading")
            // The form also lists the provider's models by itself, so the fake sees more than the one image check.
            assertTrue(vision.handledRequests >= 1, "the provider received the image check")
        }
        val copy = context().ocrProfiles.list().single()
        assertEquals(PROFILE_NAME + infoscry.ocr.LLM_COPY_NAME_SUFFIX, copy.name)
        assertEquals(true, copy.revision.imageCapabilityMeasured, "the browser's check recorded a passed measurement")
        assertNotNull(copy.sourceLlmProfileId, "the OCR profile is the copy of the LLM profile, not a separate one")
    }

    @Test
    fun `collection OCR settings persist across a reload and never leak between collections`() {
        val data = tempDir.resolve("data")
        AppContext.open(data).use { context ->
            context.collections.create("Alpha")
            context.collections.create("Beta")
            localProfile(context, "Local reader")
            externalProfile(context, "Vision reviewer")
        }
        startServer(data)
        runScenario("collection-settings")

        val alpha = collectionNamed("Alpha")
        val beta = collectionNamed("Beta")
        val local = context().ocrProfiles.list().single { it.name == "Local reader" }
        assertEquals(CollectionOcrSettings(alpha.ocrLanguages, infoscry.ocr.ReadingMethod.Llm(local.id)), alpha.ocrSettings())
        assertEquals(CollectionOcrSettings(beta.ocrLanguages), beta.ocrSettings())
    }

    @Test
    fun `Scan again previews, starts one operation however often Start is pressed, and resumes after a reload`() {
        val picture = seedPicture()
        engine = BrowserFakeEngine(listOf(FIRST_READING), gate)
        startRescanServer(picture)
        runScenario("scan-again") { mark ->
            // The reading is held until the browser has reloaded and found the same operation again.
            if (mark == "scan-held-and-reloaded") gate.release()
        }

        assertEquals(1, rescanJobCount(), "pressing Start twice must have admitted one rescan, not two")
        assertEquals(1, engine!!.calls.get(), "the held page was read once")
        assertEquals(1, operationsOf(picture).size, "the document has exactly one operation")
        assertEquals("COMPLETE", operationsOf(picture).single().getValue("stage").jsonPrimitive.content)
        assertEquals(FIRST_READING, publishedText(picture), "an identical reading publishes nothing different")
    }

    @Test
    fun `a completed scan publishes differing text automatically and keeps the previous version`() {
        val picture = seedPicture()
        engine = BrowserFakeEngine(listOf(MARKUP_READING), null)
        startRescanServer(picture)
        runScenario("publish-on-completion")
        assertEquals(MARKUP_READING, publishedText(picture))
        assertEquals(2, revisionsOf(picture).size)
        assertEquals(0, operationsOf(picture).single().getValue("pendingReviewCount").jsonPrimitive.content.toInt())
    }

    @Test
    fun `a failed scan is immediately restartable from the dialog`() {
        val picture = seedPicture()
        engine = BrowserFakeEngine(listOf(SECOND_READING), null, failFirst = true)
        startRescanServer(picture)
        runScenario("retry-failed")
        assertEquals(2, rescanJobCount())
        assertEquals(SECOND_READING, publishedText(picture))
    }

    @Test
    fun `cancel and start again leaves old text intact until the successful scan finishes`() {
        val picture = seedPicture()
        engine = BrowserFakeEngine(listOf(SECOND_READING), gate)
        startRescanServer(picture)
        runScenario("cancel-start") { mark ->
            if (mark == "cancelled-old-text") {
                assertEquals(FIRST_READING, publishedText(picture))
                gate.release()
            }
        }
        assertEquals(SECOND_READING, publishedText(picture))
        assertEquals(2, rescanJobCount())
    }

    @Test
    fun `stopping the server mid scan preserves old text and allows a fresh scan after restart`() {
        val picture = seedPicture()
        engine = BrowserFakeEngine(listOf(SECOND_READING), gate)
        startRescanServer(picture)
        runScenario("restart-held")
        assertEquals(FIRST_READING, publishedText(picture))
        harness!!.close()
        harness = null
        engine = BrowserFakeEngine(listOf(SECOND_READING), null)
        startRescanServer(picture)
        assertEquals(FIRST_READING, publishedText(picture))
        runScenario("restart-start")
        assertEquals(SECOND_READING, publishedText(picture))
        assertEquals(2, rescanJobCount())
    }

    @Test
    fun `the text history lists both versions and a restore confirms, completes and changes search and source`() {
        val picture = seedPublishedRescan()
        startRestoreServer(picture)
        runScenario("history-restore")

        assertEquals(FIRST_READING, publishedText(picture), "restoring the older version makes its text the active text")
        val history = revisionsOf(picture)
        assertEquals(3, history.size, "history keeps every version and adds the restore")
        assertTrue(
            history.any { it.getValue("provenance").jsonPrimitive.content == "RESTORE" },
            "the restore is recorded as its own version",
        )
    }

    @Test
    fun `a restore from a history list that changed elsewhere is refused as stale and restores nothing`() {
        val picture = seedPublishedRescan()
        startRestoreServer(picture)
        runScenario("history-stale-restore") { mark ->
            if (mark == "history-listed") {
                // Another tab (here: the API the other tab would use) restores the older version first.
                val before = revisionsOf(picture)
                val response = restoreThroughApi(
                    picture,
                    restoring = before.single { !it.isActive() },
                    expected = before.single { it.isActive() },
                )
                assertEquals(HttpStatusCode.Accepted, response.status, "the restore made elsewhere must succeed")
            }
        }

        // The browser's own restore was refused: the only restore in the archive is the one made elsewhere.
        val history = revisionsOf(picture)
        assertEquals(3, history.size, "the stale restore added nothing")
        assertEquals(FIRST_READING, publishedText(picture))
    }

    // ---- Text history reading (local-testing-feedback 03) and OCR profile and LLM choice (04) ----

    @Test
    fun `the text history names the OCR engine, mode and language an import was made with, and says no page needed OCR for direct text`() {
        Harness(tempDir).use { importer ->
            // A picture read with its frozen import engine, and a text file that needs no reading.
            val picture = importer.sourcesDir.resolve(PICTURE_NAME)
            writePicture(picture)
            importer.importDurably(listOf(picture), PictureUnits(FIRST_READING), ocr = TESSERACT_IMPORT)
            val notes = importer.writeText("notes.txt", "Ordinary notes about the meeting\n")
            importer.importDurably(listOf(notes), RecordingUnits(units = 1), ocr = TESSERACT_IMPORT)
        }
        startServer(tempDir.resolve("data"))
        runScenario("history-import-reading")
    }

    private fun llmProfile(context: AppContext, name: String, model: String, endpoint: String): LlmProfile = context.llm.create(
        LlmProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            provider = LlmProvider.OPENAI_COMPATIBLE,
            model = model,
            contextWindow = 64_000,
            maxOutputTokens = 2_048,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
            cacheReadPricePerMillion = 0.5,
            enabled = true,
            endpoint = endpoint,
            apiKeyEnvironmentVariable = PRESENT_KEY_VARIABLE,
        ),
    )

    /** An 8x8 white picture: the stand-in extractor reads it, so no engine or GPU is involved. */
    private fun writePicture(file: Path) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 8) {
            for (y in 0 until 8) image.setRGB(x, y, 0xFFFFFF)
        }
        ImageIO.write(image, "png", file.toFile())
    }

    // ---- Seeding ----

    /** What a seeded archive holds, in the words the browser script needs. */
    private class Seeded(
        val archive: Path,
        val picture: RescanHarness.Picture,
    )

    private lateinit var seeded: Seeded

    /**
     * An archive with one imported picture, the OCR selection a rescan reads, and the profiles it names.
     *
     * The picture goes through the real import path with a stand-in extractor (the project's own rescan
     * harness), and the server is only started afterwards: the archive is a single-process directory.
     * The engine setting is the image-model engine because the production rescan service only previews an
     * engine this machine can use; its transcription profile is a loopback one, and the job worker below
     * replaces what actually reads the page.
     */
    private fun seedPicture(): RescanHarness.Picture {
        lateinit var picture: RescanHarness.Picture
        lateinit var archive: Path
        RescanHarness(tempDir).use { rescan ->
            picture = rescan.importPicture(FIRST_READING)
            archive = rescan.archiveDir
        }
        AppContext.open(archive).use { context ->
            val reader = localProfile(context, "Local reader")
            context.collections.updateOcrSettings(
                CollectionId("default"),
                CollectionOcrSettings(language = "eng", defaultMethod = infoscry.ocr.ReadingMethod.Llm(reader.id)),
            )
        }
        seeded = Seeded(archive, picture)
        return picture
    }

    /** An archive whose document has two published versions: the import and one automatically published rescan. */
    private fun seedPublishedRescan(): RescanHarness.Picture {
        lateinit var picture: RescanHarness.Picture
        lateinit var archive: Path
        RescanHarness(tempDir).use { rescan ->
            picture = rescan.importPicture(FIRST_READING)
            archive = rescan.archiveDir
            rescan.rescan(picture, engine = FakePageEngine(readings = listOf(SECOND_READING)))
            assertEquals(SECOND_READING, rescan.publishedTextOf(picture), "the seeded rescan is published")
        }
        seeded = Seeded(archive, picture)
        return picture
    }

    private fun localProfile(context: AppContext, name: String) = context.ocrProfiles.create(
        name = name,
        draft = OcrProfileRevisionDraft(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            model = "local-vision-model",
            contextWindow = 32_000,
            maxOutputTokens = 2_048,
            endpoint = "http://127.0.0.1:9/v1",
            apiKeyEnvironmentVariable = PRESENT_KEY_VARIABLE,
        ),
        enabled = true,
    ).also { context.ocrProfiles.recordImageCapability(it.revision.revisionId, true, infoscry.storage.Instants.now()) }

    private fun externalProfile(context: AppContext, name: String) = context.ocrProfiles.create(
        name = name,
        draft = OcrProfileRevisionDraft(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            model = "external-vision-model",
            contextWindow = 32_000,
            maxOutputTokens = 2_048,
            endpoint = "https://example.invalid/v1",
            apiKeyEnvironmentVariable = PRESENT_KEY_VARIABLE,
        ),
        enabled = true,
    ).also { context.ocrProfiles.recordImageCapability(it.revision.revisionId, true, infoscry.storage.Instants.now()) }

    // ---- Server ----

    private fun startServer(dataDir: Path) {
        harness = ApiTestServer(
            dataDir,
            rescanEmbedder = { true },
            restoreEmbedder = { TestDocumentEmbedder() },
        )
        // A kept or edited page is chunked and embedded when its decision is published, with the same
        // deterministic tokenizer the attempt's own handler uses.
        harness!!.context.attachChunker(Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)))
    }

    /** A server whose job worker runs the production rescan handler over a fake engine. */
    private fun startRescanServer(picture: RescanHarness.Picture) {
        startServer(seeded.archive)
        attachRescanWorker(context(), checkNotNull(engine))
        facts = factsOf(picture)
    }

    private fun startRestoreServer(picture: RescanHarness.Picture) {
        startServer(seeded.archive)
        facts = factsOf(picture)
    }

    private fun factsOf(picture: RescanHarness.Picture): JsonObject = buildJsonObject {
        put("documentId", picture.documentId.value)
        put("unitId", picture.unitId)
        put("firstReading", FIRST_READING)
        put("secondReading", SECOND_READING)
        put("markupReading", MARKUP_READING)
        put("editedReading", EDITED_READING)
    }

    private fun attachRescanWorker(context: AppContext, fake: BrowserFakeEngine) {
        val handler = RescanJobHandler(
            paths = context.paths,
            collections = context.collections,
            documents = context.documents,
            library = context.library,
            mutations = context.mutations,
            jobs = context.jobs,
            revisions = context.revisions,
            operations = context.ocrOperations,
            publication = context.revisionPublication,
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = { TestDocumentEmbedder() },
            engineFor = { _, _, _ -> fake },
            profileRevisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
        )
        val runner = JobRunner(
            store = context.jobs,
            collections = context.collections,
            mutations = context.mutations,
            handler = DispatchingJobHandler(mapOf(JobType.RESCAN to handler)),
        )
        context.attachJobRunner(runner)
        runner.start()
    }

    private fun context(): AppContext = harness!!.context

    // ---- The browser ----

    /**
     * Runs one scenario and lets the test answer the marks the browser cannot answer itself.
     *
     * The output is polled rather than read at the end, because a mark is a request for the test to do
     * something — release a held reading, change the archive from "another tab" — that the scenario then
     * waits on. The process is killed on the deadline, so a scenario that never reaches its mark fails
     * instead of hanging the suite. A missing browser fails the scenario; it is never skipped.
     */
    private fun runScenario(name: String, onMark: (String) -> Unit = {}) {
        val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "ocr-browser-acceptance.mjs")
        check(Files.exists(script)) { "browser acceptance script is missing: $script" }
        val outputFile = Files.createTempFile("infoscry-ocr-browser-", ".log")
        val process = ProcessBuilder("node", script.toString())
            .directory(script.parent.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .apply {
                environment()["BASE_URL"] = harness!!.url
                environment()["SCENARIO"] = name
                environment()["OCR_FACTS"] = facts.toString()
                environment()["OCR_PRESENT_KEY_VARIABLE"] = PRESENT_KEY_VARIABLE
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
        // A scenario's last mark can arrive with the process's final flush, so it is answered even when the
        // process has already ended: a mark nobody answers is a hold nobody releases.
        answerMarks(outputFile, answered, onMark)
        val output = Files.readString(outputFile)
        outputFile.toFile().delete()
        if (!finished) error("browser scenario '$name' timed out\n$output")
        check(process.exitValue() == 0) { "browser scenario '$name' failed:\n$output" }
    }

    private fun answerMarks(outputFile: Path, answered: MutableSet<String>, onMark: (String) -> Unit) {
        Files.readString(outputFile).lineSequence()
            .filter { it.startsWith(MARK_PREFIX) }
            .map { it.removePrefix(MARK_PREFIX).trim() }
            .forEach { mark -> if (answered.add(mark)) onMark(mark) }
    }

    // ---- Archive facts ----

    private fun collectionNamed(name: String) = context().collections.list().single { it.name == name }

    private fun rescanJobCount(): Int = context().jobs.list(limit = 100).count { it.type == JobType.RESCAN }

    private fun prefix(picture: RescanHarness.Picture): String =
        "/api/collections/default/documents/${picture.documentId.value}/ocr"

    private fun operationsOf(picture: RescanHarness.Picture): List<JsonObject> = runBlocking {
        val body = harness!!.get("${prefix(picture)}/operations").bodyAsText()
        Json.parseToJsonElement(body).jsonObject.getValue("operations").jsonArray.map { it.jsonObject }
    }

    /** The document's published versions as revision ids, oldest first, as the history route reports them. */
    private fun revisionsOf(picture: RescanHarness.Picture): List<JsonObject> = runBlocking {
        val body = harness!!.get("${prefix(picture)}/revisions").bodyAsText()
        Json.parseToJsonElement(body).jsonObject.getValue("revisions").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.isActive(): Boolean = getValue("active").jsonPrimitive.content == "true"

    private fun publishedText(picture: RescanHarness.Picture): String {
        val revisionId = checkNotNull(context().revisions.activeRevisionId(picture.documentId))
        return context().revisions.pages(revisionId).single().extractedText
    }

    private fun restoreThroughApi(picture: RescanHarness.Picture, restoring: JsonObject, expected: JsonObject) = runBlocking {
        val body = """{"requestId":"elsewhere-${System.nanoTime()}",""" +
            """"expectedRevisionId":"${expected.getValue("revisionId").jsonPrimitive.content}",""" +
            """"restoreRevisionId":"${restoring.getValue("revisionId").jsonPrimitive.content}"}"""
        harness!!.request(HttpMethod.Post, "${prefix(picture)}/restore", body, Credential.CSRF)
    }

    private companion object {
        const val MARK_PREFIX = "MARK:"
        const val MARK_POLL_MILLIS = 100L
        const val SCENARIO_TIMEOUT_NANOS = 240_000_000_000L
        const val PROFILE_NAME = "Vision reader"
        const val PICTURE_NAME = "page.png"

        /** The synthetic-image check's answer: a transcription the probe accepts. */
        fun imageProbeAnswer(): String = buildJsonObject {
            put("model", "vision-model-2026-02-01")
            putJsonArray("choices") {
                addJsonObject {
                    put("finish_reason", "stop")
                    putJsonObject("message") {
                        put("role", "assistant")
                        put(
                            "content",
                            buildJsonObject {
                                put("unitId", ImageLlmClient.CAPABILITY_PROBE_UNIT_ID)
                                put("ordinal", 0)
                                put("text", "InfoScry image capability probe")
                                putJsonArray("unreadable") { }
                            }.toString(),
                        )
                    }
                }
            }
        }.toString()

        /** A variable every process has, so the profile list can say "present" without the test setting any. */
        const val PRESENT_KEY_VARIABLE = "PATH"
        const val FIRST_READING = "first reading"
        const val SECOND_READING = "second reading"
        const val EDITED_READING = "hand edited reading"

        /** The OCR selection an import was admitted with: what the text history must name for its revision. */
        val TESSERACT_IMPORT = OcrSettingsSnapshot(
            engine = OcrEngine.TESSERACT,
            mode = OcrImportMode.FILL_MISSING,
            language = "eng",
            extractorVersion = EXTRACTOR_SCHEMA_VERSION,
            toolVersion = "tesseract 5.5",
        )

        /** Markup on purpose: OCR output is untrusted data and must reach the screen as text. */
        const val MARKUP_READING =
            "<script>window.__ocrXss=true</script><img src=x onerror=\"window.__ocrXss=true\"> zebra reread"
    }
}

/**
 * A page-reading engine the test dictates: it answers with the next scripted text, and may wait on a gate so
 * the browser can observe, reload and resume an operation that is genuinely mid-flight.
 *
 * It names the image-model engine because that is the engine the collection selects (the production preview
 * only accepts an engine this machine has), and it describes no runtime, exactly like the engine it stands
 * in for.
 */
private class BrowserFakeEngine(
    private val readings: List<String>,
    private val hold: AcceptanceGate?,
    private val failFirst: Boolean = false,
) : PageOcrEngine {

    override val engine: OcrEngine = OcrEngine.LLM

    val calls = AtomicInteger(0)

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        val index = calls.getAndIncrement()
        if (failFirst && index == 0) throw IllegalStateException("fake provider unavailable")
        hold?.await()
        return OcrPageResult(
            text = readings[index.coerceAtMost(readings.size - 1)],
            engine = engine,
            imageSha256 = page.sha256,
            modelVersion = "fake-browser-1",
        )
    }
}
