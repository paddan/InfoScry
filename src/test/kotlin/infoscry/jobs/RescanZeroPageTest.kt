package infoscry.jobs

import infoscry.AppContext
import infoscry.document.RescanRefusalException
import infoscry.document.RescanService
import infoscry.domain.CollectionId
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.ReadingMethod
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RescanZeroPageTest {

    @Test
    fun `zero page preview is explicit and repeated starts refuse without creating work`() {
        val directory = Files.createTempDirectory("infoscry-rescan-zero-pages")
        try {
            val harness = Harness(directory)
            val source = harness.sourcesDir.resolve("clean.pdf")
            val cleanText = "This searchable page has a complete and reliable text layer for its entire document."
            PDDocument().use { pdf ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText(cleanText)
                    stream.endText()
                }
                pdf.save(source.toFile())
            }
            val imported = harness.importDurably(listOf(source), PdfPageUnits(listOf(cleanText)))
            val documentId = assertNotNull(imported.items.single().documentId)

            AppContext.open(harness.dataDir).use { context ->
                val collectionId = CollectionId("default")
                val service = RescanService(
                    paths = context.paths,
                    collections = context.collections,
                    documents = context.documents,
                    revisions = context.revisions,
                    jobs = context.jobs,
                    operations = context.ocrOperations,
                    mutations = context.mutations,
                    blockers = context.blockers,
                    publication = context.revisionPublication,
                    profileOf = context.ocrProfiles::findById,
                    profileRevisionOf = context.ocrProfiles::findRevision,
                    engines = { engine, _, _ -> if (engine == OcrEngine.SURYA) NoReadEngine else null },
                    embedderAvailable = { true },
                )
                val baselineRevision = context.revisions.activeRevisionId(documentId)
                val jobsBefore = context.jobs.list(1000).size
                val preview = runBlocking { service.preview(collectionId, documentId, ReadingMethod.Surya) }

                assertEquals(0, preview.pageTotal)
                assertEquals(1, preview.documentPages)

                repeat(2) {
                    val refusal = runCatching {
                        runBlocking {
                            service.admitRescan(collectionId, documentId, preview.previewId, "zero-page-start")
                        }
                    }.exceptionOrNull()
                    val typed = assertIs<RescanRefusalException>(refusal)
                    assertEquals("RESCAN_NO_PAGES_TO_READ", typed.code)
                    assertEquals("No page needs reading; the document keeps its text.", typed.message)
                }

                assertEquals(emptyList(), context.ocrOperations.operations(documentId))
                assertEquals(jobsBefore, context.jobs.list(1000).size)
                assertEquals(baselineRevision, context.revisions.activeRevisionId(documentId))
            }
            harness.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private object NoReadEngine : PageOcrEngine {
        override val engine = OcrEngine.SURYA
        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult =
            error("a zero-page rescan must not invoke an OCR engine")
    }
}
