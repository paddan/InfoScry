/*
 * Browser acceptance for the OCR panels: profiles, collection settings, Scan again, page review, text
 * history and restore.
 *
 * The real built reader in Chromium is driven by `web/e2e/ocr-browser-acceptance.mjs` against a real
 * InfoScry server on a temporary archive. This test owns the archive, the fixtures, the fakes and the
 * assertions that belong to the archive rather than to the screen: which settings are stored, how many
 * operations a double click created, what is searchable before and after a publication.
 *
 * Substitutions, and what they mean for what this test may claim:
 *
 * - the page-reading engine is a deterministic fake that answers with a text the test dictates, and the
 *   reviewer is the project's recording reviewer (its answer is fixed) whose proposal is made durable the way
 *   the production comparison service makes it durable. The rescan worker is therefore the production
 *   `RescanJobHandler` with those two seams replaced; no Tesseract, Surya, image-model provider or network is
 *   involved, so nothing here proves OCR quality or that a real engine is installed;
 * - the document embedder is `TestDocumentEmbedder`, because a temporary archive has no pinned model and no
 *   accelerator. Nothing here proves CoreML;
 * - the documents are generated 8x8 pictures imported through the real import path, so a "page" is one
 *   picture. A multi-page PDF review is not covered here.
 *
 * Tagged `external` because it needs Node and Chromium: the default suite stays runnable everywhere.
 */
package infoscry.server

import infoscry.AppContext
import infoscry.EXTERNAL_TAG
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.document.PageReviewer
import infoscry.document.ReviewChoice
import infoscry.domain.CollectionId
import infoscry.domain.JobType
import infoscry.embedding.TestDocumentEmbedder
import infoscry.jobs.DispatchingJobHandler
import infoscry.jobs.FakePageEngine
import infoscry.jobs.JobRunner
import infoscry.jobs.RecordingReviewer
import infoscry.jobs.RescanHarness
import infoscry.jobs.RescanJobHandler
import infoscry.llm.LlmProvider
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageComparisonInput
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.PageReview
import infoscry.ocr.PublicationDisposition
import infoscry.ocr.ReviewerRecommendation
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    fun `OCR profiles are created, listed by key presence only, edited, and a stale edit keeps its draft`() {
        startServer(tempDir.resolve("data"))
        runScenario("profiles")

        val profiles = context().ocrProfiles.list()
        assertEquals(
            setOf(PROFILE_NAME, HTML_PROFILE_NAME, "Absent key"),
            profiles.map { it.name }.toSet(),
            "the browser created exactly these three profiles",
        )
        val main = profiles.single { it.name == PROFILE_NAME }
        assertEquals("stale-draft-model", main.revision.model, "the second tab's deliberate overwrite is the one that stands")
        assertEquals(3, main.revision.sequence, "create, the first tab's edit and the deliberate overwrite are three revisions")
        assertEquals(PRESENT_KEY_VARIABLE, main.revision.apiKeyEnvironmentVariable)
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
        val vision = context().ocrProfiles.list().single { it.name == "Vision reviewer" }
        assertEquals(
            CollectionOcrSettings(
                language = alpha.ocrLanguages,
                engine = OcrEngine.LLM,
                importMode = OcrImportMode.CHECK_AND_IMPROVE,
                transcriptionProfileId = local.id,
                reviewProfileId = vision.id,
                externalPageLimit = 3,
            ),
            alpha.ocrSettings(),
            "Alpha stores what the browser saved",
        )
        assertEquals(
            CollectionOcrSettings(language = beta.ocrLanguages),
            beta.ocrSettings(),
            "Beta keeps its defaults: nothing Alpha showed or saved leaked into it",
        )
    }

    @Test
    fun `Scan again previews, starts one operation however often Start is pressed, and resumes after a reload`() {
        val picture = seedPicture(review = false)
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
    fun `a proposed page is reviewed, decided, kept across a reload, published, and shown as literal text`() {
        val picture = seedPicture(review = true)
        engine = BrowserFakeEngine(listOf(MARKUP_READING), null)
        startRescanServer(picture)
        runScenario("review-use-new")

        assertEquals(MARKUP_READING, publishedText(picture), "the decided reading is the published text only after the publication")
        assertEquals(1, rescanJobCount())
    }

    @Test
    fun `an edited page publishes the reader's own text`() {
        val picture = seedPicture(review = true)
        engine = BrowserFakeEngine(listOf(MARKUP_READING), null)
        startRescanServer(picture)
        runScenario("review-edit-text")

        assertEquals(EDITED_READING, publishedText(picture), "Edit text publishes exactly what the reader wrote")
    }

    @Test
    fun `keeping the existing text publishes the old reading and leaves search as it was`() {
        val picture = seedPicture(review = true)
        engine = BrowserFakeEngine(listOf(MARKUP_READING), null)
        startRescanServer(picture)
        runScenario("review-keep-existing")

        assertEquals(FIRST_READING, publishedText(picture), "Keep existing leaves the published text alone")
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

    // ---- Seeding ----

    /** What a seeded archive holds, in the words the browser script needs. */
    private class Seeded(
        val archive: Path,
        val picture: RescanHarness.Picture,
        val reviewProfileId: String,
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
    private fun seedPicture(review: Boolean): RescanHarness.Picture {
        lateinit var picture: RescanHarness.Picture
        lateinit var archive: Path
        var reviewProfileId = ""
        RescanHarness(tempDir).use { rescan ->
            picture = rescan.importPicture(FIRST_READING)
            archive = rescan.archiveDir
            reviewProfileId = rescan.reviewProfileId
        }
        AppContext.open(archive).use { context ->
            val reader = localProfile(context, "Local reader")
            context.collections.updateOcrSettings(
                CollectionId("default"),
                CollectionOcrSettings(
                    language = "eng",
                    engine = OcrEngine.LLM,
                    importMode = OcrImportMode.CHECK_AND_IMPROVE,
                    transcriptionProfileId = reader.id,
                    reviewProfileId = if (review) reviewProfileId else null,
                    externalPageLimit = if (review) 1 else 0,
                ),
            )
        }
        seeded = Seeded(archive, picture, reviewProfileId)
        return picture
    }

    /** An archive whose document has two published versions: the import and one reviewed rescan. */
    private fun seedPublishedRescan(): RescanHarness.Picture {
        lateinit var picture: RescanHarness.Picture
        lateinit var archive: Path
        RescanHarness(tempDir).use { rescan ->
            picture = rescan.importPicture(FIRST_READING)
            archive = rescan.archiveDir
            val staged = rescan.rescan(
                picture,
                engine = FakePageEngine(readings = listOf(SECOND_READING)),
                reviewer = RecordingReviewer(),
                reviewRevisionId = rescan.reviewRevisionId,
                externalPageLimit = 1,
            )
            val decided = rescan.decide(
                picture,
                staged.operation,
                assertNotNull(staged.candidatePage),
                ReviewChoice.USE_NEW,
            )
            rescan.publish(picture, decided.operation)
            assertEquals(SECOND_READING, rescan.publishedTextOf(picture), "the seeded rescan is published")
        }
        seeded = Seeded(archive, picture, "")
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
        ),
        enabled = true,
    )

    private fun externalProfile(context: AppContext, name: String) = context.ocrProfiles.create(
        name = name,
        draft = OcrProfileRevisionDraft(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            model = "external-vision-model",
            contextWindow = 32_000,
            maxOutputTokens = 2_048,
            endpoint = "https://example.invalid/v1",
        ),
        enabled = true,
    )

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

    /** A server whose job worker runs the production rescan handler over the fake engine and reviewer. */
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
        val recording = RecordingReviewer(ReviewerRecommendation.NEW_BETTER)
        val handler = RescanJobHandler(
            paths = context.paths,
            collections = context.collections,
            documents = context.documents,
            library = context.library,
            mutations = context.mutations,
            jobs = context.jobs,
            revisions = context.revisions,
            operations = context.ocrOperations,
            reviews = context.ocrReviews,
            publication = context.revisionPublication,
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = { TestDocumentEmbedder() },
            engineFor = { _, _, _ -> fake },
            reviewerFor = { _, dispatch ->
                recording.dispatchingThrough(dispatch)
                DurableReviewer(context, recording)
            },
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
        const val HTML_PROFILE_NAME = "<b>Bold</b> <img src=x onerror=\"window.__ocrXss=true\">"

        /** A variable every process has, so the profile list can say "present" without the test setting any. */
        const val PRESENT_KEY_VARIABLE = "PATH"
        const val FIRST_READING = "first reading"
        const val SECOND_READING = "second reading"
        const val EDITED_READING = "hand edited reading"

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
) : PageOcrEngine {

    override val engine: OcrEngine = OcrEngine.LLM

    val calls = AtomicInteger(0)

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        val index = calls.getAndIncrement()
        hold?.await()
        return OcrPageResult(
            text = readings[index.coerceAtMost(readings.size - 1)],
            engine = engine,
            imageSha256 = page.sha256,
            modelVersion = "fake-browser-1",
        )
    }
}

/**
 * The project's recording reviewer, with its proposal made durable the way the comparison service does it.
 *
 * A page the reviewer does not keep is recorded, so the review surface lists it as waiting; the recording
 * reviewer on its own answers without persisting.
 */
private class DurableReviewer(
    private val context: AppContext,
    private val inner: RecordingReviewer,
) : PageReviewer {

    override suspend fun compare(input: PageComparisonInput): PageReview {
        val review = inner.compare(input)
        if (review.disposition != PublicationDisposition.KEEP) context.ocrReviews.record(review)
        return review
    }
}
