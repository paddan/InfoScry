package infoscry.jobs

import infoscry.domain.CollectionId
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
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.OcrProfileStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ImportPreviewServiceTest {
    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var collections: CollectionStore
    private lateinit var service: ImportPreviewService
    private var collectionId: CollectionId = CollectionId("preview-test")

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("infoscry-import-preview")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        collections = CollectionStore(database)
        collectionId = collections.create("Preview collection", ocrLanguages = "swe+eng").id
        val selection = ImportSelection(MediaTypeDetector(), ExtractorRegistry(listOf(PlainTextExtractor())))
        service = ImportPreviewService(
            collections = collections,
            catalog = object : ReadingMethodCatalog {
                override fun availability(collectionId: CollectionId) = listOf(
                    MethodAvailability(
                        method = ReadingMethod.Tesseract,
                        label = "Tesseract",
                        destination = "this machine",
                        available = true,
                        unavailableReason = null,
                        external = false,
                    ),
                )
            },
            profiles = OcrProfileService(OcrProfileStore(database)),
            pageCounter = object : PageCounter {
                override fun pageCount(path: Path): Int? =
                    if (path.fileName.toString().contains("bad")) null else 1
            },
            selection = selection,
            enumerate = { paths, recursive, ignore -> enumerateImportSources(paths, recursive, ignore, selection) },
            ignorePatterns = { IgnorePatterns.NONE },
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `preview and hash share the exact sorted supported file set`() {
        val alpha = directory.resolve("alpha.txt").also { Files.writeString(it, "alpha") }
        val beta = directory.resolve("beta.txt").also { Files.writeString(it, "beta") }
        val request = ImportPreviewRequest(
            collection = collectionId,
            paths = listOf(beta.toString(), alpha.toString()),
            method = ReadingMethod.Tesseract,
        )

        val preview = service.preview(request)
        val reversed = service.preview(request.copy(paths = request.paths.reversed()))

        assertEquals(listOf(alpha.toRealPath().toString(), beta.toRealPath().toString()), preview.files.map { it.path })
        assertEquals(2, preview.totalPages)
        assertEquals(2, preview.files.size)
        assertEquals(service.hashOf(request), preview.previewHash)
        assertEquals(preview.previewHash, reversed.previewHash)
        assertEquals(preview, reversed)
        assertTrue(preview.previewHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `unknown page count stays in the preview and changed bytes change its hash`() {
        val readable = directory.resolve("readable.txt").also { Files.writeString(it, "first") }
        val malformed = directory.resolve("bad.txt").also { Files.writeString(it, "bad") }
        val request = ImportPreviewRequest(
            collection = collectionId,
            paths = listOf(readable.toString(), malformed.toString()),
            method = ReadingMethod.Tesseract,
        )

        val preview = service.preview(request)
        val hashBefore = service.hashOf(request)
        Files.writeString(readable, "changed bytes")
        val hashAfter = service.hashOf(request)

        assertEquals(null, preview.files.single { it.path == malformed.toRealPath().toString() }.pages)
        assertEquals("page count could not be read", preview.files.single { it.path == malformed.toRealPath().toString() }.reason)
        assertTrue(preview.atLeast)
        assertEquals(1, preview.totalPages)
        assertNotEquals(hashBefore, hashAfter)
    }

    @Test
    fun `the preview counts the pages that will be read and reports the document's own pages`() {
        val document = directory.resolve("mixed-document.pdf").also { Files.writeString(it, "placeholder") }
        val selection = ImportSelection(MediaTypeDetector(), ExtractorRegistry(listOf(PlainTextExtractor())))
        val counting = ImportPreviewService(
            collections = collections,
            catalog = object : ReadingMethodCatalog {
                override fun availability(collectionId: CollectionId) = listOf(
                    MethodAvailability(
                        method = ReadingMethod.Tesseract,
                        label = "Tesseract",
                        destination = "this machine",
                        available = true,
                        unavailableReason = null,
                        external = false,
                    ),
                )
            },
            profiles = OcrProfileService(OcrProfileStore(database)),
            pageCounter = object : PageCounter {
                override fun pageCount(path: Path): Int? = 48
                override fun readablePageCount(path: Path): Int? = 12
            },
            selection = selection,
            enumerate = { paths, recursive, ignore -> enumerateImportSources(paths, recursive, ignore, selection) },
            ignorePatterns = { IgnorePatterns.NONE },
        )

        val preview = counting.preview(
            ImportPreviewRequest(collectionId, listOf(document.toString()), method = ReadingMethod.Tesseract),
        )

        val file = preview.files.single()
        assertEquals(12, file.pages)
        assertEquals(48, file.documentPages)
        assertEquals(12, preview.totalPages)
        assertEquals(false, preview.atLeast)
    }

    @Test
    fun `unknown external page count has no misleading exact cost estimate`() {
        val source = directory.resolve("bad-external.txt").also { Files.writeString(it, "unknown pages") }
        val profiles = OcrProfileService(OcrProfileStore(database))
        val profile = profiles.create(
            name = "External reader",
            draft = OcrProfileRevisionDraft(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = "vision-model",
                contextWindow = 32_000,
                maxOutputTokens = 2_048,
                endpoint = "https://example.invalid/v1",
                inputPricePerMillion = 1.0,
                outputPricePerMillion = 2.0,
            ),
            enabled = true,
        )
        val method = ReadingMethod.Llm(profile.id)
        val selection = ImportSelection(MediaTypeDetector(), ExtractorRegistry(listOf(PlainTextExtractor())))
        val externalService = ImportPreviewService(
            collections = collections,
            catalog = object : ReadingMethodCatalog {
                override fun availability(collectionId: CollectionId) = listOf(
                    MethodAvailability(
                        method = method,
                        label = "LLM: External reader",
                        destination = "example.invalid",
                        available = true,
                        unavailableReason = null,
                        external = true,
                    ),
                )
            },
            profiles = profiles,
            pageCounter = object : PageCounter {
                override fun pageCount(path: Path): Int? = null
            },
            selection = selection,
            enumerate = { paths, recursive, ignore -> enumerateImportSources(paths, recursive, ignore, selection) },
            ignorePatterns = { IgnorePatterns.NONE },
        )

        val preview = externalService.preview(
            ImportPreviewRequest(collectionId, listOf(source.toString()), method = method),
        )

        assertEquals(0, preview.totalPages)
        assertTrue(preview.atLeast)
        assertEquals(null, preview.estimatedCostUsd)
        assertEquals("the page count is unknown, so no estimate can be made", preview.costBasis)
    }
}
