/*
 * Image foundation browser acceptance: a PDF is added with Extract images set to Embedded images, its page 2 shows
 * two thumbnails that open in a viewer, a rescan with Keep current text and picture regions from scanned pages
 * outlines a region on the scanned page 3, its caption is searchable, and an Ask citation opens the picture.
 * Chromium drives the built reader against a temporary InfoScry archive, the test embedder, a fake Surya layout
 * engine and a local fake provider. Real Surya is not involved.
 */
package infoscry.server

import infoscry.EXTERNAL_TAG
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.domain.DocumentId
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.MediaTypeDetector
import infoscry.image.ImageArtifacts
import infoscry.image.ImageFinders
import infoscry.image.ImageStage
import infoscry.image.LayoutRegions
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.ImportPipeline
import infoscry.jobs.StoredUnitsSink
import infoscry.jobs.seedCollectionWithId
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import infoscry.ocr.CandidateRevisionSink
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.PageOcrEngines
import infoscry.ocr.PixelBox
import infoscry.ocr.StructureDerivation
import infoscry.ocr.SuryaBlockInput
import infoscry.ocr.SuryaStructureParser
import infoscry.storage.CaptionSource
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.jupiter.api.Tag

@Tag(EXTERNAL_TAG)
class ImagesBrowserAcceptanceTest {
    private lateinit var tempDir: Path
    private var harness: ApiTestServer? = null
    private val layout = FakeLayoutSurya()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("infoscry-images-browser-")
    }

    @AfterTest
    fun tearDown() {
        harness?.close()
        harness = null
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `thumbnails, Keep current text with regions, caption search and an Ask citation open the pictures`() {
        val pdf = writeSurveyPdf(tempDir.resolve("sources/survey.pdf"))
        FakeOpenAiServer(listOf(answerStream("The northern quay was photographed at dusk in 1923 [S1]."), titleCompletion("Northern quay"))).use { fake ->
            startServer()

            // 1. Add the PDF with Embedded images: two thumbnails under page 2, one opens in the viewer.
            runScenario("import", chosenFile = pdf)
            val documentId = documentId()
            val importedRevision = checkNotNull(context().revisions.activeRevisionId(documentId))
            val embedded = context().imageStore.imagesOf(importedRevision)
            assertEquals(2, embedded.size, "page 2's two pictures were extracted; the scan and the text pages add none")
            assertTrue(embedded.all { it.kind == infoscry.image.ImageSourceKind.EMBEDDED && it.caption == null })
            assertEquals(0, layout.calls.get(), "Embedded images never asks Surya for layout")
            val before = context().revisions.pages(importedRevision).filter { !infoscry.image.ImageIdentity.isImageOrdinal(it.ordinal) }
            assertEquals(3, before.size, "three text pages")

            // 2. Rescan with Keep current text and regions from scanned pages: a region outlined on page 3.
            runScenario("rescan", documentId = documentId)
            val revision = checkNotNull(context().revisions.activeRevisionId(documentId))
            val images = context().imageStore.imagesOf(revision)
            assertEquals(3, images.size, "the two embedded pictures are kept and one region is added")
            val region = images.single { it.kind == infoscry.image.ImageSourceKind.PAGE_REGION }
            assertEquals(CAPTION, region.caption)
            assertEquals(CaptionSource.LAYOUT_CAPTION, region.captionSource)
            assertEquals(1, layout.calls.get(), "only the scanned page was read for layout")
            val after = context().revisions.pages(revision).filter { !infoscry.image.ImageIdentity.isImageOrdinal(it.ordinal) }
            assertEquals(before.map { it.extractedText }, after.map { it.extractedText }, "Keep current text leaves every page's text as it was")

            // 3. Ask cites the caption's picture and the citation opens the viewer.
            // The provider is only configured now: pages that list profiles ask a configured provider for its models.
            createAskProfile(fake.url)
            try {
                runScenario("ask", documentId = documentId)
            } catch (failure: IllegalStateException) {
                throw IllegalStateException("${failure.message}; provider saw ${fake.requests.map { it.path + ' ' + it.body.take(3000) }}", failure)
            }
            assertTrue(fake.handledRequests >= 2, "the local provider answered and titled the Ask")
            val evidence = fake.requests.first { it.path.endsWith("/chat/completions") }.body
            assertTrue(evidence.contains("northern quay at dusk"), "the caption's passage was sent to the provider as evidence")
            val citations = context().database.read { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM citations WHERE validated = 1 AND source_unit_id = ?").use { query ->
                    query.setString(1, region.unitId.value)
                    query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
            }
            assertEquals(1, citations, "the answer's citation names the region's image unit")
        }
    }

    private fun startServer() {
        harness = ApiTestServer(
            tempDir.resolve("data"),
            queryEmbedder = { infoscry.embedding.QueryEmbedder { FloatArray(768) { if (it == 0) 1f else 0f } } },
            rescanEmbedder = { true },
            restoreEmbedder = { TestDocumentEmbedder() },
        )
        val context = context()
        context.seedCollectionWithId("default", "Default")
        val chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1))
        // The production pipeline with the fake Surya layout engine in place of the installed runtime.
        val pipeline = ImportPipeline(
            detector = MediaTypeDetector(),
            registry = infoscry.extract.ExtractorRegistry.production(surya = null),
            sink = StoredUnitsSink(context.paths, context.documents, context.content, context.corrections),
            candidateSinkFor = { documentId, reading ->
                CandidateRevisionSink(
                    revisions = context.revisions,
                    documentId = documentId,
                    provenance = ImportPipeline.PROVENANCE_CHECK_AND_IMPROVE,
                    readingSnapshot = reading,
                )
            },
            images = ImageStage(
                ImageFinders.standard(), LayoutRegions(PageOcrEngines(listOf(layout))), ImageArtifacts(),
                context.imageStore, context.documents,
            ),
        )
        ImportJobHandler.attachTo(context, pipeline = pipeline, documentEmbedder = { TestDocumentEmbedder() }, chunker = chunker)
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
                apiKeyEnvironmentVariable = "PATH",
            ),
        )
        check(context().llm.setDefault(LlmPromptRole.ASK, "Browser Ask"))
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

    private fun context() = checkNotNull(harness).context

    private fun runScenario(name: String, chosenFile: Path? = null, documentId: DocumentId? = null) {
        val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "images-browser-acceptance.mjs")
        val outputFile = Files.createTempFile("infoscry-images-browser-", ".log")
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
                error("Images browser acceptance '$name' timed out: ${Files.readString(outputFile)}")
            }
            check(process.exitValue() == 0) { "Images browser acceptance '$name' failed: ${Files.readString(outputFile)}" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            Files.deleteIfExists(outputFile)
        }
    }

    /**
     * Page 1 and page 2 are text; page 2 also draws two different pictures. Page 3 is a full-page scan whose
     * photograph sits at 15%-85% across and 10%-55% down, where the fake layout engine puts its figure.
     */
    private fun writeSurveyPdf(target: Path): Path {
        Files.createDirectories(target.parent)
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val first = PDPage().also(pdf::addPage)
            PDPageContentStream(pdf, first).use { content ->
                content.beginText()
                content.setFont(font, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("Harbour survey of 1923. The survey party recorded the soundings in fathoms.")
                content.endText()
            }
            val second = PDPage().also(pdf::addPage)
            PDPageContentStream(pdf, second).use { content ->
                content.beginText()
                content.setFont(font, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("Second page: the lanterns along the quay were counted twice.")
                content.endText()
                content.drawImage(LosslessFactory.createFromImage(pdf, picture(300, 225, 11)), 72f, 440f, 200f, 150f)
                content.drawImage(LosslessFactory.createFromImage(pdf, picture(280, 210, 29)), 320f, 440f, 200f, 150f)
            }
            val third = PDPage().also(pdf::addPage)
            PDPageContentStream(pdf, third).use { content ->
                content.drawImage(LosslessFactory.createFromImage(pdf, scannedPage()), 0f, 0f, 612f, 792f)
            }
            pdf.save(target.toFile())
        }
        return target
    }

    private fun scannedPage(): BufferedImage {
        val scan = BufferedImage(612, 792, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until scan.width) for (y in 0 until scan.height) scan.setRGB(x, y, 0xFFFFFF)
        val photo = picture((612 * 0.70).toInt(), (792 * 0.45).toInt(), 5)
        scan.createGraphics().apply { drawImage(photo, (612 * 0.15).toInt(), (792 * 0.10).toInt(), null); dispose() }
        return scan
    }

    /** A picture with a smooth gradient and noise, so it is neither one colour nor a duplicate of another seed. */
    private fun picture(width: Int, height: Int, seed: Int): BufferedImage {
        val random = Random(seed.toLong())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val red = (x * 255 / width + random.nextInt(40) + seed * 7) % 256
            val green = (y * 255 / height + random.nextInt(40)) % 256
            val blue = ((x + y) * 255 / (width + height) + random.nextInt(40) + seed * 3) % 256
            image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
        }
        return image
    }

    /**
     * A Surya layout reading of the scanned page: a figure at 15%-85% across and 10%-55% down with its caption
     * just below. The figure block carries placeholder text because the Surya parser drops a block with no words.
     */
    private class FakeLayoutSurya : PageOcrEngine {
        override val engine = OcrEngine.SURYA
        val calls = AtomicInteger()

        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
            calls.incrementAndGet()
            val raster = ImageIO.read(page.imagePath.toFile())
            val w = raster.width
            val h = raster.height
            fun box(left: Double, top: Double, right: Double, bottom: Double) =
                PixelBox((left * w).toInt(), (top * h).toInt(), (right * w).toInt(), (bottom * h).toInt())
            val derived = StructureDerivation.derive(
                SuryaStructureParser.parse(
                    listOf(
                        SuryaBlockInput("<p>photograph</p>", "Picture", 0, box(0.15, 0.10, 0.85, 0.55)),
                        SuryaBlockInput("<p>$CAPTION</p>", "Caption", 1, box(0.15, 0.56, 0.85, 0.60)),
                    ),
                ),
                page.sha256,
            )
            return OcrPageResult(
                text = derived.extractedText,
                engine = engine,
                imageSha256 = page.sha256,
                meanConfidence = 0.9,
                modelVersion = "fake-surya-layout-1",
                structure = derived.structure,
            )
        }
    }

    private companion object {
        const val CAPTION = "Figure 1. The northern quay at dusk, photographed 1923."
        fun answerStream(text: String) = FakeOpenAiResponse(
            stream = true,
            body = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8}}\n\ndata: [DONE]\n\n",
        )
        fun titleCompletion(title: String) = FakeOpenAiResponse(
            body = """{"choices":[{"message":{"role":"assistant","content":"$title"}}],"usage":{"prompt_tokens":9,"completion_tokens":3}}""",
        )
    }
}
