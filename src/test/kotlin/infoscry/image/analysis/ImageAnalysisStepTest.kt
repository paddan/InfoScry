package infoscry.image.analysis

import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.image.WrittenImage
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmProvider
import infoscry.llm.RetryPolicy
import infoscry.ocr.ImageAnalysisChoice
import infoscry.ocr.ImageLlmClient
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DocumentStore
import infoscry.storage.ImageAnalysisStore
import infoscry.storage.SchemaMigrator
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The analysis step of the image stage: reuse, failure, edits and the text a picture is indexed with.
 */
class ImageAnalysisStepTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: ImageAnalysisStore
    private lateinit var document: Document
    private lateinit var imagePath: Path

    private val unitId = ContentUnitId("image-unit-1")
    private val sha256 = "a".repeat(64)
    private val written = WrittenImage(
        sha256 = sha256,
        relativePath = "images/picture.png",
        thumbnailRelativePath = "images/thumbs/picture.jpg",
        mediaType = "image/png",
        widthPx = 400,
        heightPx = 300,
    )

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("infoscry-image-analysis-step")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = ImageAnalysisStore(database)
        val collection = CollectionStore(database).create("Pictures")
        document = DocumentStore(database).insert(
            Document(
                id = DocumentId.new(),
                collectionId = collection.id,
                sha256 = "h".repeat(64),
                mediaType = "image/png",
                originalFilename = "picture.png",
                sourcePath = "/picture.png",
                sizeBytes = 1,
                status = DocumentStatus.COMPLETE,
                createdAt = "2026-11-07T00:00:00Z",
                updatedAt = "2026-11-07T00:00:00Z",
            ),
        )
        imagePath = picture()
    }

    @AfterTest
    fun tearDown() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun picture(): Path {
        val raster = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
            } finally {
                graphics.dispose()
            }
        }
        val path = directory.resolve("picture.png")
        check(ImageIO.write(raster, "png", path.toFile())) { "no PNG writer is available" }
        return path
    }

    private fun revision(endpoint: String) = OcrProfileRevision(
        revisionId = "revision-1",
        profileId = "profile-1",
        sequence = 1,
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "vision-model",
        contextWindow = 32_000,
        maxOutputTokens = 2_048,
        inputPricePerMillion = 0.0,
        outputPricePerMillion = 0.0,
        createdAt = "2026-11-07T00:00:00.000Z",
        endpoint = endpoint,
        imageCapabilityMeasured = true,
    )

    private fun stepFor(server: FakeOpenAiServer, profile: OcrProfileRevision) = ImageAnalysisStep(
        analyzer = ImageAnalyzer { _, _ ->
            ImageLlmClient(
                profile = profile,
                timeout = 10.seconds,
                retryPolicy = RetryPolicy(maxRetries = 1, retryDelay = { }),
            )
        },
        store = store,
        profileRevisionOf = { revisionId -> profile.takeIf { it.revisionId == revisionId } },
    )

    private fun choice(identifyAppearance: Boolean = false) =
        ImageAnalysisChoice(profileRevisionId = "revision-1", identifyAppearance = identifyAppearance)

    private suspend fun textFor(
        step: ImageAnalysisStep,
        choice: ImageAnalysisChoice?,
        caption: String? = "Fig. 3",
        context: String? = "Sidan 14: en man vid en bil.",
    ) = step.textFor(
        document = document,
        unitId = unitId,
        image = written,
        imagePath = imagePath,
        caption = caption,
        context = context,
        choice = choice,
        language = "swe",
        dispatch = null,
    )

    private fun openAiAnswer(content: String): String = buildJsonObject {
        put("model", "vision-model")
        putJsonArray("choices") {
            addJsonObject {
                put("finish_reason", "stop")
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", content)
                }
            }
        }
    }.toString()

    private fun analysisAnswer(
        description: String = "Två män vid en bil.",
        tags: String = """[{"category":"PERSON","label":"man","name":"Jeffrey Epstein","source":"APPEARANCE"}]""",
    ): String = buildJsonObject {
        put("description", description)
        put("tags", kotlinx.serialization.json.Json.parseToJsonElement(tags))
    }.toString()

    @Test
    fun `the analysis choice is not part of the reading's identity`() {
        val snapshot = OcrSettingsSnapshot(
            engine = OcrEngine.SURYA,
            mode = OcrImportMode.READ_ALL,
            language = "swe",
            extractorVersion = "schema-1",
            imageExtraction = infoscry.image.ImageExtractionMode.EMBEDDED,
        )

        assertEquals(
            snapshot.attemptIdentity(),
            snapshot.copy(imageAnalysis = ImageAnalysisChoice("revision-1", true)).attemptIdentity(),
            "switching image analysis must not invalidate committed page text",
        )
    }

    @Test
    fun `a repeated request makes one model call and answers the same text`() = runBlocking {
        val server = FakeOpenAiServer(listOf(FakeOpenAiResponse(body = openAiAnswer(analysisAnswer()))))
        try {
            val profile = revision(server.url)
            val step = stepFor(server, profile)
            val first = textFor(step, choice(identifyAppearance = true))
            val second = textFor(step, choice(identifyAppearance = true))

            assertEquals(1, server.handledRequests)
            assertEquals(first, second)
            assertEquals(
                "Fig. 3\n\nTvå män vid en bil.\n\nman: possibly Jeffrey Epstein\n\nSidan 14: en man vid en bil.",
                first.text,
            )
            assertEquals(setOf(TagCategory.PERSON), first.categories)
            assertNull(first.warning)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a run with no analysis choice keeps the latest completed analysis without a call`() = runBlocking {
        val server = FakeOpenAiServer(listOf(FakeOpenAiResponse(body = openAiAnswer(analysisAnswer()))))
        try {
            val profile = revision(server.url)
            val step = stepFor(server, profile)
            val analysed = textFor(step, choice())
            val imageOnly = textFor(step, choice = null)

            assertEquals(1, server.handledRequests)
            assertEquals(analysed.text, imageOnly.text)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a refused image answers the caption and page text with its code`() = runBlocking {
        val server = FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 400, body = """{"error":"no"}""")))
        try {
            val profile = revision(server.url)
            val result = textFor(stepFor(server, profile), choice())

            assertEquals("Fig. 3\n\nSidan 14: en man vid en bil.", result.text)
            assertEquals(AnalysisRules.REFUSED, result.warning)
            assertEquals(emptySet(), result.categories)
            assertNull(store.latestDone(document.id, unitId, sha256), "a refused analysis is not a completed one")
            assertEquals(
                AnalysisOutcome.FAILED,
                store.get(
                    ImageAnalysisKey(document.id, unitId, sha256, "revision-1", 1, "swe", false),
                )?.outcome,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `edits change the delivered text and its categories`() = runBlocking {
        val server = FakeOpenAiServer(listOf(FakeOpenAiResponse(body = openAiAnswer(analysisAnswer()))))
        try {
            val profile = revision(server.url)
            val step = stepFor(server, profile)
            val before = textFor(step, choice(identifyAppearance = true))
            store.addEdit(
                ImageTagEdit(
                    editId = "e1",
                    documentId = document.id,
                    unitId = unitId,
                    action = TagEditAction.REMOVE,
                    tag = ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.MANUAL),
                    createdBy = null,
                    createdAt = "2026-11-07T10:00:00Z",
                ),
            )

            val after = textFor(step, choice(identifyAppearance = true))

            assertTrue(before.text.contains("Jeffrey Epstein"))
            assertEquals("Fig. 3\n\nTvå män vid en bil.\n\nSidan 14: en man vid en bil.", after.text)
            assertEquals(emptySet(), after.categories)
            assertEquals(1, server.handledRequests, "an edit is not a new analysis")
        } finally {
            server.close()
        }
    }

    @Test
    fun `without a caption or page text the analysis is still the picture's text`() = runBlocking {
        val server = FakeOpenAiServer(listOf(FakeOpenAiResponse(body = openAiAnswer(analysisAnswer(description = "En man.")))))
        try {
            val profile = revision(server.url)
            val result = textFor(stepFor(server, profile), choice(identifyAppearance = true), caption = null, context = null)

            assertEquals("En man.\n\nman: possibly Jeffrey Epstein", result.text)
        } finally {
            server.close()
        }
    }
}
