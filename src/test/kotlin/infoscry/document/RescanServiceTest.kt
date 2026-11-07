package infoscry.document

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.image.ImageExtractionMode
import infoscry.jobs.FakePageEngine
import infoscry.jobs.RescanHarness
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.MethodAvailability
import infoscry.ocr.OcrEngine
import infoscry.ocr.ReadingMethod
import infoscry.ocr.ReadingMethodCatalog
import infoscry.ocr.ReadingMethodUnavailableException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** What a rescan preview and the reading-method catalog say about the rescan-only method *Keep current text*. */
class RescanServiceTest {

    @Test
    fun `an import, a retry and a collection default refuse Keep current text`() {
        val catalog = object : ReadingMethodCatalog {
            override fun availability(collectionId: CollectionId) = listOf(
                MethodAvailability(ReadingMethod.Tesseract, "Tesseract", "this machine", true, null, false),
            )
        }
        val id = CollectionId("default")

        val refused = assertFailsWith<ReadingMethodUnavailableException> { catalog.require(id, ReadingMethod.KeepText) }
        assertEquals("METHOD_UNAVAILABLE", refused.code)
        assertFalse(catalog.availability(id).any { it.method == ReadingMethod.KeepText })
        assertEquals(ReadingMethod.KeepText, catalog.requireForRescan(id, ReadingMethod.KeepText).method)
        assertEquals(ReadingMethod.KeepText, catalog.rescanAvailability(id).last().method)
        assertEquals("Tesseract", catalog.requireForRescan(id, ReadingMethod.Tesseract).label)

        assertFailsWith<IllegalArgumentException> { CollectionOcrSettings("eng", ReadingMethod.KeepText) }
        assertEquals(ReadingMethod.KeepText, ReadingMethod.parse("keep"))
        assertEquals("keep", ReadingMethod.KeepText.id)
    }

    @Test
    fun `the preview of Keep current text reads no page and costs nothing`() {
        val directory = Files.createTempDirectory("infoscry-rescan-service")
        try {
            RescanHarness(directory).use { harness ->
                val picture = harness.importCleanAndScannedPdf("clean page", "scanned page")
                val preview = AppContext.open(harness.archiveDir).use { context ->
                    runBlocking {
                        harness.rescanServiceOf(context, FakePageEngine()).preview(
                            CollectionId("default"), picture.documentId, ReadingMethod.KeepText,
                            imageExtraction = ImageExtractionMode.EMBEDDED,
                        )
                    }
                }

                assertEquals(0, preview.pageTotal, "no page is read")
                assertEquals(2, preview.documentPages)
                assertEquals(0, preview.externalPageUpperBound)
                assertTrue(preview.snapshot.keepsText)
                assertEquals(OcrEngine.NONE, preview.snapshot.engine)
                assertEquals(ImageExtractionMode.EMBEDDED, preview.snapshot.imageExtraction)
                assertTrue(preview.destinations.isEmpty(), "nothing is sent anywhere")
                assertEquals(0, preview.externalAllowance)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
