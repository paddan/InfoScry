/*
 * Image tagging browser acceptance: one picture is added with *Describe images with* a checked local fake
 * profile and *Identify from appearance*; its description and a `possibly` tag are shown, an editor confirms
 * the tag, the *People* search filter finds the picture, and an Ask citation opens it. Chromium drives the
 * built reader against a temporary InfoScry archive, the test embedder and one local fake provider serving
 * both the analysis and the Ask. No real vision model is involved.
 */
package infoscry.server

import infoscry.EXTERNAL_TAG
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.domain.DocumentId
import infoscry.embedding.QueryEmbedder
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.MediaTypeDetector
import infoscry.image.ImageArtifacts
import infoscry.image.ImageFinders
import infoscry.image.ImageIdentity
import infoscry.image.ImageStage
import infoscry.image.LayoutRegions
import infoscry.image.analysis.ImageAnalysisStep
import infoscry.image.analysis.ImageAnalyzer
import infoscry.image.analysis.ImageCategories
import infoscry.image.analysis.TagCategory
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.ImportPipeline
import infoscry.jobs.StoredUnitsSink
import infoscry.jobs.seedCollectionWithId
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import infoscry.ocr.ImageLlmClient
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.PageOcrEngines
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import infoscry.storage.ImageAnalysisStore
import infoscry.storage.Instants
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Tag

@Tag(EXTERNAL_TAG)
class ImageTaggingBrowserAcceptanceTest {
    private lateinit var tempDir: Path
    private var harness: ApiTestServer? = null

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("infoscry-image-tagging-browser-")
    }

    @AfterTest
    fun tearDown() {
        harness?.close()
        harness = null
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `describe a picture, confirm its tag, filter it by People and cite it`() {
        val picture = writePicture(tempDir.resolve("sources/car.png"))
        FakeOpenAiServer(
            listOf(
                analysisAnswer(),
                answerStream("A man stands by the red car [S1]."),
                titleCompletion("By the car"),
            ),
        ).use { fake ->
            startServer(fake.url)
            createImageProfile(fake.url)

            runScenario("import", chosenFile = picture)
            val documentId = documentId()
            val revision = checkNotNull(context().revisions.activeRevisionId(documentId))
            val imagePage = context().revisions.pages(revision).single { ImageIdentity.isImageOrdinal(it.ordinal) }
            assertTrue(imagePage.extractedText.contains(DESCRIPTION), imagePage.extractedText)
            assertTrue(imagePage.extractedText.contains("man: possibly Jeffrey Epstein"), imagePage.extractedText)
            assertEquals(
                setOf("PERSON", "OBJECT"),
                ImageCategories(ImageAnalysisStore(context().database), context().imageStore)
                    .ofUnit(documentId, imagePage.unitId),
            )

            runScenario("confirm", documentId = documentId)
            val afterConfirm = checkNotNull(context().revisions.activeRevisionId(documentId))
            val edited = context().revisions.pages(afterConfirm).single { it.unitId == imagePage.unitId }
            assertTrue(edited.extractedText.contains("man: Jeffrey Epstein"), edited.extractedText)
            assertTrue(!edited.extractedText.contains("man: possibly Jeffrey Epstein"), edited.extractedText)

            val people = runBlocking {
                context().search.search(
                    "Jeffrey",
                    mode = SearchMode.KEYWORD,
                    filters = SearchFilters(documentCollectionId(), pictureCategory = TagCategory.PERSON),
                )
            }
            assertEquals(listOf(imagePage.unitId), people.hits.map { it.unitId })

            createAskProfile(fake.url)
            try {
                runScenario("ask", documentId = documentId)
            } catch (failure: IllegalStateException) {
                throw IllegalStateException(
                    "${failure.message}; provider saw ${fake.requests.map { it.path + ' ' + it.body.take(2000) }}",
                    failure,
                )
            }
            assertTrue(fake.handledRequests >= 3, "the analysis and the Ask were answered")
        }
    }

    private fun startServer(endpoint: String) {
        harness = ApiTestServer(
            tempDir.resolve("data"),
            queryEmbedder = { QueryEmbedder { query -> FloatArray(768).also { if (query.isNotBlank()) it[0] = 1f } } },
            rescanEmbedder = { true },
            restoreEmbedder = { TestDocumentEmbedder() },
        )
        val context = context()
        context.seedCollectionWithId("default", "Default")
        val chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1))
        val pipeline = ImportPipeline(
            detector = MediaTypeDetector(),
            registry = infoscry.extract.ExtractorRegistry.production(surya = null),
            sink = StoredUnitsSink(context.paths, context.documents, context.content, context.corrections),
            images = ImageStage(
                ImageFinders.standard(), LayoutRegions(PageOcrEngines(emptyList())), ImageArtifacts(),
                context.imageStore, context.documents,
                analysis = ImageAnalysisStep(
                    analyzer = ImageAnalyzer { profile, dispatch ->
                        ImageLlmClient(
                            profile = profile,
                            permits = dispatch,
                            calls = dispatch?.let { authority -> authority::attemptAboutToBeSent },
                        )
                    },
                    store = ImageAnalysisStore(context.database),
                    profileRevisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
                ),
            ),
        )
        ImportJobHandler.attachTo(context, pipeline = pipeline, documentEmbedder = { TestDocumentEmbedder() }, chunker = chunker)
    }

    private fun createImageProfile(endpoint: String) {
        val profile = context().ocrProfiles.create(
            name = "Fake vision",
            draft = OcrProfileRevisionDraft(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = "fake-vision",
                contextWindow = 32_000,
                maxOutputTokens = 4_096,
                endpoint = endpoint,
            ),
            enabled = true,
        )
        context().ocrProfiles.recordImageCapability(profile.revision.revisionId, true, Instants.now())
    }

    private fun createAskProfile(endpoint: String) {
        context().llm.create(
            LlmProfile(
                id = "tagging-ask-profile",
                name = "Tagging Ask",
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = "fake-chat",
                contextWindow = 32_000,
                maxOutputTokens = 2_048,
                inputPricePerMillion = 0.0,
                outputPricePerMillion = 0.0,
                cacheReadPricePerMillion = 0.0,
                enabled = true,
                endpoint = endpoint,
                apiKeyEnvironmentVariable = "PATH",
            ),
        )
        check(context().llm.setDefault(LlmPromptRole.ASK, "Tagging Ask"))
    }

    private fun documentId(): DocumentId = context().database.read { connection ->
        connection.prepareStatement("SELECT id FROM documents WHERE collection_id = ?").use { query ->
            query.setString(1, "default")
            query.executeQuery().use { rows ->
                check(rows.next()) { "the browser import created no document" }
                DocumentId(rows.getString(1))
            }
        }
    }

    private fun documentCollectionId(): infoscry.domain.CollectionId = context().documents.get(documentId())!!.collectionId

    private fun context() = checkNotNull(harness).context

    private fun runScenario(name: String, chosenFile: Path? = null, documentId: DocumentId? = null) {
        val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "image-tagging-browser-acceptance.mjs")
        val outputFile = Files.createTempFile("infoscry-image-tagging-browser-", ".log")
        val process = ProcessBuilder("node", script.toString()).directory(script.parent.toFile())
            .redirectErrorStream(true).redirectOutput(outputFile.toFile())
            .apply {
                environment()["BASE_URL"] = checkNotNull(harness).url
                environment()["INFOSCRY_SESSION_COOKIE"] = checkNotNull(harness).sessionCookie
                environment()["SCENARIO"] = name
                chosenFile?.let { environment()["CHOSEN_FILE"] = it.toString() }
                documentId?.let { environment()["DOCUMENT_ID"] = it.value }
            }.start()
        try {
            if (!process.waitFor(240, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                error("Image tagging browser acceptance '$name' timed out: ${Files.readString(outputFile)}")
            }
            check(process.exitValue() == 0) { "Image tagging browser acceptance '$name' failed: ${Files.readString(outputFile)}" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            Files.deleteIfExists(outputFile)
        }
    }

    private fun writePicture(target: Path): Path {
        Files.createDirectories(target.parent)
        val raster = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = Color(120, 160, 200)
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.color = Color(200, 40, 40)
                graphics.fillRect(170, 260, 300, 100)
                graphics.color = Color.WHITE
                graphics.fillOval(200, 340, 60, 60)
                graphics.fillOval(380, 340, 60, 60)
                graphics.color = Color.BLACK
                graphics.drawString("VOLVO", 260, 320)
                graphics.color = Color(80, 120, 60)
                graphics.fillRect(0, 400, image.width, 80)
            } finally {
                graphics.dispose()
            }
        }
        check(ImageIO.write(raster, "png", target.toFile())) { "no PNG writer is available" }
        return target
    }

    private fun analysisAnswer(): FakeOpenAiResponse {
        val content = buildJsonObject {
            put("description", DESCRIPTION)
            putJsonArray("tags") {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("category", "PERSON")
                    put("label", "man")
                    put("name", "Jeffrey Epstein")
                    put("source", "APPEARANCE")
                })
                add(kotlinx.serialization.json.buildJsonObject {
                    put("category", "OBJECT")
                    put("label", "bil")
                    put("name", "Volvo PV444")
                    put("source", "TEXT")
                })
            }
        }.toString()
        val body = buildJsonObject {
            put("model", "fake-vision")
            putJsonArray("choices") {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("finish_reason", "stop")
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content)
                    }
                })
            }
        }.toString()
        return FakeOpenAiResponse(body = body)
    }

    private companion object {
        const val DESCRIPTION = "A red car by a quay in the sun."

        fun answerStream(text: String) = FakeOpenAiResponse(
            stream = true,
            body = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8}}\n\ndata: [DONE]\n\n",
        )

        fun titleCompletion(title: String) = FakeOpenAiResponse(
            body = """{"choices":[{"message":{"role":"assistant","content":"$title"}}],"usage":{"prompt_tokens":9,"completion_tokens":3}}""",
        )
    }
}
