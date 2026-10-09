package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.JobState
import infoscry.extract.ExtractionSettings
import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.PageCounter
import infoscry.extract.PlainTextExtractor
import infoscry.llm.LlmProvider
import infoscry.ocr.MethodAvailability
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.OcrProfileService
import infoscry.ocr.ReadingMethod
import infoscry.ocr.ReadingMethodCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class ImportStartServiceTest {

    @Test
    fun `external start freezes manifest scope when at least one confirmed page count is unknown`() {
        val data = Files.createTempDirectory("infoscry-import-start-scope")
        val source = data.resolve("unknown-pages.txt").also { Files.writeString(it, "confirmed bytes") }
        AppContext.open(data.resolve("archive")).use { context ->
            val collection = context.collections.create("External import")
            val profile = context.ocrProfiles.create(
                name = "External reader",
                draft = OcrProfileRevisionDraft(
                    provider = LlmProvider.OPENAI_COMPATIBLE,
                    model = "vision-model",
                    contextWindow = 32_000,
                    maxOutputTokens = 2_048,
                    endpoint = "https://example.invalid/v1",
                    apiKeyEnvironmentVariable = "TEST_OCR_KEY",
                ),
                enabled = true,
            )
            val method = ReadingMethod.Llm(profile.id)
            val catalog = object : ReadingMethodCatalog {
                override fun availability(collectionId: infoscry.domain.CollectionId) = listOf(
                    MethodAvailability(
                        method = method,
                        label = "LLM: External reader",
                        destination = "example.invalid",
                        available = true,
                        unavailableReason = null,
                        external = true,
                    ),
                )
            }
            val selection = ImportSelection(
                MediaTypeDetector(),
                ExtractorRegistry(listOf(PlainTextExtractor())),
            )
            val ocr = OcrProfileService(
                profiles = context.ocrProfiles,
                environment = { "test-key" },
            )
            val previews = ImportPreviewService(
                collections = context.collections,
                catalog = catalog,
                profiles = ocr,
                pageCounter = object : PageCounter {
                    override fun pageCount(path: Path): Int? = null
                },
                selection = selection,
                enumerate = { paths, recursive, ignore ->
                    enumerateImportSources(paths, recursive, ignore, selection)
                },
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
            val preview = previews.preview(
                ImportPreviewRequest(collection.id, listOf(source.toString()), method = method),
            )
            assertTrue(preview.atLeast)
            assertEquals(null, preview.estimatedCostUsd)

            val job = kotlinx.coroutines.runBlocking {
                start.start(
                    ImportStartRequest(
                        collectionId = collection.id,
                        paths = listOf(source.toString()),
                        method = method,
                        previewHash = preview.previewHash,
                        requestId = "unknown-external-import",
                    ),
                )
            }
            val payload = ImportJobPayload.decode(assertNotNull(context.jobs.get(job.id)).payload)

            assertEquals(0, payload.ocr?.externalPageLimit)
            assertEquals(true, payload.ocr?.externalConfirmedSourceScope)
            assertEquals(listOf(source.toRealPath().toString()), payload.confirmedSources?.map { it.path })
        }
        data.toFile().deleteRecursively()
    }

    @Test
    fun `opted-in CLI start replaces failed job once while ordinary replay and complete replay are unchanged`() = runBlocking {
        val data = Files.createTempDirectory("infoscry-import-restart")
        val source = data.resolve("source.txt").also { Files.writeString(it, "import this") }
        AppContext.open(data.resolve("archive")).use { context ->
            val collection = context.collections.create("Restart collection")
            val (previews, service) = services(context)
            val previewRequest = ImportPreviewRequest(
                collection = collection.id,
                paths = listOf(source.toString()),
                method = ReadingMethod.Tesseract,
            )
            val preview = previews.preview(previewRequest)
            val cliRequest = ImportStartRequest(
                collectionId = collection.id,
                paths = previewRequest.paths,
                method = ReadingMethod.Tesseract,
                previewHash = preview.previewHash,
                requestId = "cli-stable-import-id",
                restartStopped = true,
            )

            val first = service.start(cliRequest)
            context.jobs.fail(context.jobs.claimNextQueued()!!.id, "TEST_FAILURE", "test failure")

            val concurrent = (1..2).map { async(Dispatchers.IO) { service.start(cliRequest) } }.awaitAll()
            val replacement = concurrent.first()
            assertNotEquals(first.id, replacement.id)
            assertEquals(replacement.id, concurrent.last().id)
            assertEquals(JobState.QUEUED, replacement.state)
            assertEquals(2, context.jobs.list(10).count { it.type == infoscry.domain.JobType.IMPORT })

            val activeReplay = service.start(cliRequest)
            assertEquals(replacement.id, activeReplay.id)
            context.jobs.complete(context.jobs.claimNextQueued()!!.id)
            val completedReplay = service.start(cliRequest)
            assertEquals(replacement.id, completedReplay.id)

            val ordinaryRequest = cliRequest.copy(requestId = "http-strict-replay", restartStopped = false)
            val ordinary = service.start(ordinaryRequest)
            context.jobs.fail(context.jobs.claimNextQueued()!!.id, "TEST_FAILURE", "test failure")
            assertEquals(ordinary.id, service.start(ordinaryRequest).id)

            val cancelledRequest = cliRequest.copy(requestId = "cli-cancelled-import-id")
            val cancelled = service.start(cancelledRequest)
            context.jobs.cancel(cancelled.id)
            val afterCancellation = service.start(cancelledRequest)
            assertNotEquals(cancelled.id, afterCancellation.id)
            assertEquals(JobState.QUEUED, afterCancellation.state)
        }
        data.toFile().deleteRecursively()
    }

    private fun services(context: AppContext): Pair<ImportPreviewService, ImportStartService> {
        val selection = ImportSelection(
            MediaTypeDetector(),
            ExtractorRegistry(listOf(PlainTextExtractor())),
        )
        val catalog = object : ReadingMethodCatalog {
            override fun availability(collectionId: infoscry.domain.CollectionId) = listOf(
                MethodAvailability(
                    method = ReadingMethod.Tesseract,
                    label = "Tesseract",
                    destination = "this machine",
                    available = true,
                    unavailableReason = null,
                    external = false,
                ),
            )
        }
        val ocr = OcrProfileService(profiles = context.ocrProfiles, engineFor = { _, _, _ -> null })
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
}
