package infoscry.image

import infoscry.domain.SourceLocation
import infoscry.extract.ooxmlBlocks
import infoscry.extract.wordUnits
import infoscry.storage.CaptionSource
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture

class OfficeImageFinderTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createDirectory() {
        directory = createTempDirectory("infoscry-office-finder")
    }

    @AfterTest
    fun deleteDirectory() {
        directory.toFile().deleteRecursively()
    }

    private val finder = OfficeImageFinder()

    private fun imagesOf(file: Path, parent: ParentUnit): ParentImages =
        finder.open(file).use { it.imagesOf(parent) }

    private fun docx(build: (XWPFDocument) -> Unit): Path {
        val file = directory.resolve("doc.docx")
        XWPFDocument().use { document ->
            build(document)
            Files.newOutputStream(file).use { document.write(it) }
        }
        return file
    }

    private fun XWPFDocument.picture(seed: Int, description: String?) {
        val run = createParagraph().createRun()
        val picture = run.addPicture(
            ByteArrayInputStream(FinderFixtures.png(300, 200, seed)),
            XWPFDocument.PICTURE_TYPE_PNG,
            "picture$seed.png",
            3_000_000,
            2_000_000,
        )
        picture.ctPicture.nvPicPr.cNvPr.descr = description
    }

    private fun sectionLocators(file: Path): List<SourceLocation> =
        Files.newInputStream(file).use { XWPFDocument(it).use { d -> wordUnits(ooxmlBlocks(d)).map { u -> u.locator } } }

    @Test
    fun `a docx yields its pictures in document order with their alt text under their section`() {
        val file = docx { document ->
            document.createParagraph().createRun().setText("Uppsala in the spring")
            document.picture(1, "Map of Uppsala")
            document.createParagraph().createRun().setText("The cathedral")
            document.picture(2, "Map of Uppsala")
        }
        val section = sectionLocators(file).single()

        val result = imagesOf(file, FinderFixtures.parent(section))

        assertEquals(2, result.images.size)
        result.images.forEach {
            assertEquals(ImageSourceKind.EMBEDDED, it.kind)
            assertEquals("Map of Uppsala", it.caption)
            assertEquals(CaptionSource.ALT_TEXT, it.captionSource)
            assertEquals(null, it.candidate.box)
            assertEquals(300, it.candidate.widthPx)
            assertEquals("image/png", assertIs<ImageBytes.Encoded>(it.candidate.bytes).mediaType)
        }
        val first = assertIs<ImageBytes.Encoded>(result.images[0].candidate.bytes).bytes
        assertTrue(first.contentEquals(FinderFixtures.png(300, 200, 1)))
    }

    @Test
    fun `a picture between two heading sections belongs to the section before it`() {
        val file = docx { document ->
            document.createParagraph().apply { style = "Heading1" }.createRun().setText("First")
            document.createParagraph().createRun().setText("Body")
            document.picture(1, "Plate")
            document.createParagraph().apply { style = "Heading1" }.createRun().setText("Second")
            document.createParagraph().createRun().setText("More")
        }
        val sections = sectionLocators(file)
        assertEquals(2, sections.size)

        val first = imagesOf(file, FinderFixtures.parent(sections[0]))
        val second = imagesOf(file, FinderFixtures.parent(sections[1]))

        assertEquals(1, first.images.size)
        assertEquals(0, second.images.size)
    }

    @Test
    fun `a filename used as alt text is not a caption`() {
        val file = docx { document ->
            document.createParagraph().createRun().setText("Text")
            document.picture(1, "photo-3.png")
        }

        val image = imagesOf(file, FinderFixtures.parent(sectionLocators(file).single())).images.single()

        assertEquals(null, image.caption)
        assertEquals(null, image.captionSource)
    }

    @Test
    fun `a pptx picture on slide 2 belongs to slide 2 and carries its box`() {
        val file = directory.resolve("deck.pptx")
        XMLSlideShow().use { show ->
            show.createSlide()
            val slide = show.createSlide()
            val data = show.addPicture(FinderFixtures.png(300, 200), PictureData.PictureType.PNG)
            val picture = slide.createPicture(data)
            picture.anchor = Rectangle(72, 72, 360, 216)
            (picture.xmlObject as CTPicture).nvPicPr.cNvPr.descr = "The harbour"
            Files.newOutputStream(file).use { show.write(it) }
        }

        val first = imagesOf(file, FinderFixtures.parent(SourceLocation.Slide(1)))
        val second = imagesOf(file, FinderFixtures.parent(SourceLocation.Slide(2)))

        assertEquals(0, first.images.size)
        val found = second.images.single()
        assertEquals("The harbour", found.caption)
        assertEquals(CaptionSource.ALT_TEXT, found.captionSource)
        val box = checkNotNull(found.candidate.box)
        assertEquals(72.0 / 720, box.left, 0.001)
        assertEquals(432.0 / 720, box.right, 0.001)
        assertEquals(72.0 / 540, box.top, 0.001)
        assertEquals(288.0 / 540, box.bottom, 0.001)
    }

    @Test
    fun `an xlsx picture belongs to the range holding its anchor row`() {
        val file = directory.resolve("book.xlsx")
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Data")
            for (row in 0..3) sheet.createRow(row).createCell(0).setCellValue("v$row")
            val index = workbook.addPicture(FinderFixtures.png(300, 200), Workbook.PICTURE_TYPE_PNG)
            val drawing = sheet.createDrawingPatriarch()
            for ((row, text) in listOf(2 to "Inside", 20 to "Below")) {
                val anchor = workbook.creationHelper.createClientAnchor()
                anchor.setCol1(2)
                anchor.row1 = row
                val picture = drawing.createPicture(anchor, index)
                picture.ctPicture.nvPicPr.cNvPr.descr = text
            }
            Files.newOutputStream(file).use { workbook.write(it) }
        }
        val range = SourceLocation.SpreadsheetRange("Data", "A2", "A4")

        val result = imagesOf(file, FinderFixtures.parent(range))

        assertEquals(listOf("Inside"), result.images.map { it.caption })
        assertEquals(
            ParentImages.NONE,
            imagesOf(file, FinderFixtures.parent(SourceLocation.SpreadsheetRange("Other", "A2", "A4"))),
        )
    }

    @Test
    fun `a locator the finder does not own has no images`() {
        val file = docx { it.createParagraph().createRun().setText("Text") }

        assertEquals(ParentImages.NONE, imagesOf(file, FinderFixtures.parent(SourceLocation.PdfPage(1))))
    }

    @Test
    fun `a picture that cannot be decoded fails alone`() {
        val file = docx { document ->
            document.createParagraph().createRun().setText("Text")
            val run = document.createParagraph().createRun()
            run.addPicture(
                ByteArrayInputStream(ByteArray(80) { 7 }),
                XWPFDocument.PICTURE_TYPE_PNG,
                "bad.png",
                1_000_000,
                1_000_000,
            )
            document.picture(2, null)
        }

        val result = imagesOf(file, FinderFixtures.parent(sectionLocators(file).single()))

        assertEquals(listOf(ImageRejectedException.IMAGE_DECODE_FAILED), result.failures)
        assertEquals(1, result.images.size)
    }
}
