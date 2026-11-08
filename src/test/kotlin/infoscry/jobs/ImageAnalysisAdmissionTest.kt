package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.extract.ExtractionSettings
import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.PageCounter
import infoscry.image.ImageExtractionMode
import infoscry.llm.LlmProvider
import infoscry.ocr.ExternalPageAllowanceExceededException
import infoscry.ocr.ImageAnalysisRequest
import infoscry.ocr.ImageProfileUnavailableException
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.OcrProfileService
import infoscry.ocr.PageDispatchIdentity
import infoscry.ocr.ReadingMethod
import infoscry.ocr.ReadingMethodCatalog
import infoscry.ocr.ReadingMethodUnavailableException
import infoscry.storage.StartRequestConflictException
import java.awt.Color
import java.awt.image.BufferedImage
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/**
 * Admitting a run's picture analysis: which profile may describe pictures, what an external one may send, and how
 * the choice is part of the request.
 */
class ImageAnalysisAdmissionTest {

    private fun picture(path: Path) {
        val raster = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
            } finally {
                graphics.dispose()
            }
        }
        check(ImageIO.write(raster, "png", path.toFile()))
    }

    private fun draft(endpoint: String = "http://127.0.0.1:11434/v1") = OcrProfileRevisionDraft(
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "vision-model",
        contextWindow = 32_000,
        maxOutputTokens = 2_048,
        endpoint = endpoint,
    )

    private fun services(context: AppContext): Pair<ImportPreviewService, ImportStartService> {
        val catalog = object : ReadingMethodCatalog {
            override fun availability(collectionId: infoscry.domain.CollectionId) = listOf(
                infoscry.ocr.MethodAvailability(
                    method = ReadingMethod.None,
                    label = "No OCR",
                    destination = "this machine",
                    available = true,
                    unavailableReason = null,
                    external = false,
                ),
            )
        }
        val selection = ImportSelection(
            MediaTypeDetector(),
            ExtractorRegistry(
                listOf(
                    infoscry.extract.ImageExtractor(infoscry.ocr.PageOcrEngines(emptyList())),
                    infoscry.extract.PdfExtractor(infoscry.ocr.PageOcrEngines(emptyList())),
                ),
                infoscry.extract.TextualFallbackExtractor(),
            ),
        )
        val ocr = OcrProfileService(profiles = context.ocrProfiles, environment = { null })
        val previews = ImportPreviewService(
            collections = context.collections,
            catalog = catalog,
            profiles = ocr,
            pageCounter = object : PageCounter {
                override fun pageCount(path: Path): Int? = 1
            },
            selection = selection,
            enumerate = { paths, recursive, ignore -> enumerateImportSources(paths, recursive, ignore, selection) },
            ignorePatterns = { context.collectionService.ignorePatterns(it) },
        )
        val start = ImportStartService(
            previews = previews,
            catalog = catalog,
            ocr = ocr,
            jobs = context.jobs,
            requests = context.startRequests,
            mutations = context.mutations,
            collectionService = context.collectionService,
            ignorePatterns = { context.collectionService.ignorePatterns(it) },
            extractionSettings = { languages -> ExtractionSettings(ocrLanguages = languages) },
        )
        return previews to start
    }

    @Test
    fun `an unchecked image profile is refused before anything is uploaded`() {
        val data = Files.createTempDirectory("infoscry-image-admission-unchecked")
        val source = data.resolve("one.png").also(::picture)
        AppContext.open(data.resolve("archive")).use { context ->
            val collection = context.collections.create("Pictures")
            val profile = context.ocrProfiles.create("Unchecked vision", draft(), enabled = true)
            val (previews, _) = services(context)

            val failure = assertFailsWith<ImageProfileUnavailableException> {
                previews.preview(
                    ImportPreviewRequest(
                        collection.id,
                        listOf(source.toString()),
                        method = ReadingMethod.None,
                        imageExtraction = ImageExtractionMode.EMBEDDED,
                        imageAnalysis = ImageAnalysisRequest(profile.id),
                    ),
                )
            }

            assertEquals("IMAGE_PROFILE_UNAVAILABLE", failure.code)
        }
        data.toFile().deleteRecursively()
    }

    @Test
    fun `a disabled image profile is refused`() {
        val data = Files.createTempDirectory("infoscry-image-admission-deleted")
        val source = data.resolve("one.png").also(::picture)
        AppContext.open(data.resolve("archive")).use { context ->
            val collection = context.collections.create("Pictures")
            val profile = context.ocrProfiles.create("Deleted vision", draft(), enabled = true)
            context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, "2026-11-07T10:00:00Z")
            context.ocr.disable(profile.id)
            val (previews, _) = services(context)

            assertFailsWith<ImageProfileUnavailableException> {
                previews.preview(
                    ImportPreviewRequest(
                        collection.id,
                        listOf(source.toString()),
                        method = ReadingMethod.None,
                        imageExtraction = ImageExtractionMode.EMBEDDED,
                        imageAnalysis = ImageAnalysisRequest(profile.id),
                    ),
                )
            }
        }
        data.toFile().deleteRecursively()
    }

    @Test
    fun `an external profile is admitted with the confirmed picture count and refuses the first one past it`() {
        val data = Files.createTempDirectory("infoscry-image-admission-external")
        val first = data.resolve("one.png").also(::picture)
        val second = data.resolve("two.png").also(::picture)
        AppContext.open(data.resolve("archive")).use { context ->
            val collection = context.collections.create("Pictures")
            val profile = context.ocrProfiles.create("External vision", draft("https://example.invalid/v1"), enabled = true)
            context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, "2026-11-07T10:00:00Z")
            val (previews, start) = services(context)
            val choice = ImageAnalysisRequest(profile.id)
            val previewRequest = ImportPreviewRequest(
                collection.id,
                listOf(first.toString(), second.toString()),
                method = ReadingMethod.None,
                imageExtraction = ImageExtractionMode.EMBEDDED,
                imageAnalysis = choice,
            )

            val preview = previews.preview(previewRequest)

            assertEquals(2, preview.imageCount)
            assertEquals(2, preview.imageAllowance)
            assertEquals(true, preview.imageExternal)

            val job = runBlocking {
                start.start(
                    ImportStartRequest(
                        collectionId = collection.id,
                        paths = previewRequest.paths,
                        method = ReadingMethod.None,
                        previewHash = preview.previewHash,
                        requestId = "image-external-import",
                        imageExtraction = ImageExtractionMode.EMBEDDED,
                        imageAnalysis = choice,
                    ),
                )
            }
            val snapshot = assertNotNull(ImportJobPayload.decode(assertNotNull(context.jobs.get(job.id)).payload).ocr)
            assertEquals(2, snapshot.imageAnalysisExternalLimit)
            assertEquals(profile.revision.revisionId, assertNotNull(snapshot.imageAnalysis).profileRevisionId)

            val authority = OcrDispatchAuthority(
                operations = context.ocrOperations,
                owner = OcrExternalOwner.job(job.id.value),
                documentId = DocumentId("external-document"),
                stage = OcrDispatchStage.IMAGE_ANALYSIS,
                profileRevisionId = profile.revision.revisionId,
                configuredAllowance = 2,
            )
            val picture = { index: Int ->
                PageDispatchIdentity("picture-$index", 0, "external-document")
            }
            assertEquals(true, authority.isPermitted(infoscry.ocr.ExternalDispatchPermitRequest(profile.revision.revisionId, picture(1))))
            assertEquals(true, authority.isPermitted(infoscry.ocr.ExternalDispatchPermitRequest(profile.revision.revisionId, picture(2))))
            assertFailsWith<ExternalPageAllowanceExceededException> {
                authority.isPermitted(infoscry.ocr.ExternalDispatchPermitRequest(profile.revision.revisionId, picture(3)))
            }
        }
        data.toFile().deleteRecursively()
    }

    @Test
    fun `an external profile whose picture count cannot be stated is refused`() {
        val data = Files.createTempDirectory("infoscry-image-admission-unconfirmed")
        val source = data.resolve("notes.txt").also { Files.writeString(it, "no pictures here") }
        AppContext.open(data.resolve("archive")).use { context ->
            val collection = context.collections.create("Notes")
            val profile = context.ocrProfiles.create("External vision", draft("https://example.invalid/v1"), enabled = true)
            context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, "2026-11-07T10:00:00Z")
            val (previews, _) = services(context)

            val failure = assertFailsWith<ImageProfileUnavailableException> {
                previews.preview(
                    ImportPreviewRequest(
                        collection.id,
                        listOf(source.toString()),
                        method = ReadingMethod.None,
                        imageExtraction = ImageExtractionMode.EMBEDDED,
                        imageAnalysis = ImageAnalysisRequest(profile.id),
                    ),
                )
            }

            assertEquals("IMAGE_PROFILE_UNAVAILABLE", failure.code)
        }
        data.toFile().deleteRecursively()
    }

    @Test
    fun `a pdf's embedded pictures are counted for the confirmed allowance`() {
        val data = Files.createTempDirectory("infoscry-image-admission-pdf")
        val source = data.resolve("survey.pdf").also(::pdfWithPicture)
        AppContext.open(data.resolve("archive")).use { context ->
            val collection = context.collections.create("Surveys")
            val profile = context.ocrProfiles.create("External vision", draft("https://example.invalid/v1"), enabled = true)
            context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, "2026-11-07T10:00:00Z")
            val (previews, start) = services(context)
            val choice = ImageAnalysisRequest(profile.id)
            val previewRequest = ImportPreviewRequest(
                collection.id,
                listOf(source.toString()),
                method = ReadingMethod.None,
                imageExtraction = ImageExtractionMode.EMBEDDED,
                imageAnalysis = choice,
            )

            val preview = previews.preview(previewRequest)

            assertEquals(1, preview.imageCount)
            assertEquals(1, preview.imageAllowance)
            val job = runBlocking {
                start.start(
                    ImportStartRequest(
                        collectionId = collection.id,
                        paths = previewRequest.paths,
                        method = ReadingMethod.None,
                        previewHash = preview.previewHash,
                        requestId = "image-pdf-import",
                        imageExtraction = ImageExtractionMode.EMBEDDED,
                        imageAnalysis = choice,
                    ),
                )
            }
            val snapshot = assertNotNull(ImportJobPayload.decode(assertNotNull(context.jobs.get(job.id)).payload).ocr)
            assertEquals(1, snapshot.imageAnalysisExternalLimit)
        }
        data.toFile().deleteRecursively()
    }

    private fun pdfWithPicture(target: java.nio.file.Path) {
        val raster = BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.RED
                graphics.fillRect(0, 0, image.width, image.height)
            } finally {
                graphics.dispose()
            }
        }
        PDDocument().use { pdf ->
            val page = PDPage().also(pdf::addPage)
            PDPageContentStream(pdf, page).use { content ->
                content.drawImage(LosslessFactory.createFromImage(pdf, raster), 72f, 500f, 160f, 120f)
            }
            pdf.save(target.toFile())
        }
    }

    @Test
    fun `a changed analysis choice under the same request id is a different request`() {
        val data = Files.createTempDirectory("infoscry-image-admission-identity")
        val source = data.resolve("one.png").also(::picture)
        AppContext.open(data.resolve("archive")).use { context ->
            val collection = context.collections.create("Pictures")
            val profile = context.ocrProfiles.create("Local vision", draft(), enabled = true)
            context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, "2026-11-07T10:00:00Z")
            val (previews, start) = services(context)
            fun request(appearance: Boolean) = ImportPreviewRequest(
                collection.id,
                listOf(source.toString()),
                method = ReadingMethod.None,
                imageExtraction = ImageExtractionMode.EMBEDDED,
                imageAnalysis = ImageAnalysisRequest(profile.id, appearance),
            )
            val without = previews.preview(request(false))
            runBlocking {
                start.start(
                    ImportStartRequest(
                        collectionId = collection.id,
                        paths = request(false).paths,
                        method = ReadingMethod.None,
                        previewHash = without.previewHash,
                        requestId = "choice-identity",
                        imageExtraction = ImageExtractionMode.EMBEDDED,
                        imageAnalysis = ImageAnalysisRequest(profile.id, false),
                    ),
                )
            }
            val withAppearance = previews.preview(request(true))

            assertFailsWith<StartRequestConflictException> {
                runBlocking {
                    start.start(
                        ImportStartRequest(
                            collectionId = collection.id,
                            paths = request(true).paths,
                            method = ReadingMethod.None,
                            previewHash = withAppearance.previewHash,
                            requestId = "choice-identity",
                            imageExtraction = ImageExtractionMode.EMBEDDED,
                            imageAnalysis = ImageAnalysisRequest(profile.id, true),
                        ),
                    )
                }
            }
        }
        data.toFile().deleteRecursively()
    }

    @Test
    fun `keep current text with an image profile and images off is refused`() {
        val directory = Files.createTempDirectory("infoscry-image-admission-keep")
        RescanHarness(directory).use { harness ->
            val picture = harness.importPicture("published text")
            AppContext.open(harness.archiveDir).use { context ->
                val profile = context.ocrProfiles.create("Local vision", draft(), enabled = true)
                context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, "2026-11-07T10:00:00Z")

                val failure = assertFailsWith<ReadingMethodUnavailableException> {
                    runBlocking {
                        context.rescanService.preview(
                            collectionId = infoscry.domain.CollectionId("default"),
                            documentId = picture.documentId,
                            method = ReadingMethod.KeepText,
                            imageExtraction = ImageExtractionMode.OFF,
                            imageAnalysis = ImageAnalysisRequest(profile.id),
                        )
                    }
                }

                assertEquals("METHOD_UNAVAILABLE", failure.code)
            }
        }
        directory.toFile().deleteRecursively()
    }
}
