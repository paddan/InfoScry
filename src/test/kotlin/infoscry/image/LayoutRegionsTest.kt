package infoscry.image

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.extract.OcrUnavailableException
import infoscry.domain.NormalizedBox
import infoscry.domain.SourceLocation
import infoscry.ocr.BlockKind
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.PageOcrEngines
import infoscry.ocr.PageStructure
import infoscry.ocr.PixelBox
import infoscry.ocr.STRUCTURE_VERSION
import infoscry.ocr.StructureBlock
import infoscry.ocr.SuryaStructureParser
import infoscry.ocr.SuryaOcr
import infoscry.ocr.SuryaWorkerException
import infoscry.ocr.TextRange
import infoscry.pdf.PdfModelRules
import infoscry.storage.CaptionSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LayoutRegionsTest {

    private lateinit var directory: Path

    private val parent = ParentUnit(ContentUnitId("page:1"), 0, SourceLocation.PdfPage(1))

    private val settings = OcrSettingsSnapshot(
        engine = OcrEngine.SURYA,
        mode = OcrImportMode.READ_ALL,
        language = "eng",
        extractorVersion = "schema-1",
        renderDpi = 300,
    )

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("infoscry-layout-regions")
    }

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `two figures become page regions and an adjacent caption is attached to its figure`() {
        val engine = FakeSurya { page -> reading(page, PAGE_TEXT, figureAWithCaption()) }
        val page = page(fills = listOf(FIGURE_A to Color.RED))

        val result = regions(engine, page, structure = null)

        assertEquals(2, result.images.size)
        assertTrue(result.failures.isEmpty())
        val (first, second) = result.images
        assertEquals(ImageSourceKind.PAGE_REGION, first.kind)
        assertEquals(parent, first.parent)
        assertEquals("Caption one.", first.caption)
        assertEquals(CaptionSource.LAYOUT_CAPTION, first.captionSource)
        assertEquals(NormalizedBox(0.1, 0.1, 0.5, 0.4), first.candidate.box)
        assertEquals(400, first.candidate.widthPx)
        assertEquals(300, first.candidate.heightPx)
        assertNull(second.caption)
        assertNull(second.captionSource)
        assertEquals(NormalizedBox(0.1, 0.6, 0.5, 0.9), second.candidate.box)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `a region is cropped from the page raster`() {
        val engine = FakeSurya { page -> reading(page, PAGE_TEXT, figureAWithCaption()) }
        val page = page(fills = listOf(FIGURE_A to Color.RED))

        val first = regions(engine, page, structure = null).images.first()
        val raster = (first.candidate.bytes as ImageBytes.Raster).image

        assertEquals(400, raster.width)
        assertEquals(300, raster.height)
        assertEquals(Color.RED.rgb and 0xFFFFFF, raster.getRGB(0, 0) and 0xFFFFFF)
    }

    @Test
    fun `a stored structure is used and the engine is not called`() {
        val engine = FakeSurya { error("the engine must not read a page with a stored structure") }
        val page = page()

        val result = regions(
            engine, page,
            structure = structureOf(figureAWithCaption(), imageSha256 = page.sha256, parser = SuryaStructureParser.PARSER),
        )

        assertEquals(2, result.images.size)
        assertEquals("Caption one.", result.images.first().caption)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `a structure from the older parser is treated as absent and the page is read for its layout`() {
        val page = page()
        val engine = FakeSurya { reading(page, PAGE_TEXT, figureAWithCaption()) }

        val result = regions(
            engine, page,
            structure = structureOf(figureAWithCaption(), imageSha256 = page.sha256, parser = SuryaStructureParser.LEGACY_PARSER),
        )

        assertEquals(1, engine.calls)
        assertEquals(2, result.images.size)
    }

    @Test
    fun `a structure made from another page image is treated as absent, never cropped from this raster`() {
        val page = page()
        val engine = FakeSurya { reading(page, PAGE_TEXT, figureAWithCaption()) }

        val result = regions(
            engine, page,
            structure = structureOf(figureAWithCaption(), imageSha256 = "b".repeat(64), parser = SuryaStructureParser.PARSER),
        )

        assertEquals(1, engine.calls)
        assertEquals(2, result.images.size)
    }

    @Test
    fun `a page without figures yields no images`() {
        val engine = FakeSurya { page -> reading(page, PAGE_TEXT, listOf(paragraph())) }

        val result = regions(engine, page(), structure = null)

        assertTrue(result.images.isEmpty())
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `a figure only 80 pixels tall is still returned`() {
        val short = block(BlockKind.FIGURE, 0, PixelBox(100, 100, 500, 180), FIGURE_A_RANGE)
        val engine = FakeSurya { page -> reading(page, PAGE_TEXT, listOf(short)) }

        val result = regions(engine, page(), structure = null)

        assertEquals(1, result.images.size)
        assertEquals(80, result.images.single().candidate.heightPx)
    }

    @Test
    fun `a box beyond the page edge is cropped to the page and a box left with no area is skipped`() {
        val beyond = block(BlockKind.FIGURE, 0, PixelBox(990, 100, 1010, 200), FIGURE_A_RANGE)
        val outside = block(BlockKind.FIGURE, 1, PixelBox(1000, 300, 1010, 400), FIGURE_B_RANGE)
        val engine = FakeSurya { page -> reading(page, PAGE_TEXT, listOf(beyond, outside)) }

        val result = regions(engine, page(), structure = null)

        assertEquals(1, result.images.size)
        assertEquals(NormalizedBox(0.99, 0.1, 1.0, 0.2), result.images.single().candidate.box)
    }

    @Test
    fun `a caption farther than three percent of the page height is not attached`() {
        val figure = block(BlockKind.FIGURE, 0, PixelBox(100, 100, 500, 400), FIGURE_A_RANGE)
        val far = block(BlockKind.CAPTION, 1, PixelBox(100, 500, 500, 540), CAPTION_ONE_RANGE)
        val engine = FakeSurya { page -> reading(page, PAGE_TEXT, listOf(figure, far)) }

        val image = regions(engine, page(), structure = null).images.single()

        assertNull(image.caption)
        assertNull(image.captionSource)
    }

    @Test
    fun `a caption that does not sit under its figure is not attached`() {
        val figure = block(BlockKind.FIGURE, 0, PixelBox(100, 100, 500, 400), FIGURE_A_RANGE)
        val beside = block(BlockKind.CAPTION, 1, PixelBox(480, 410, 900, 450), CAPTION_ONE_RANGE)
        val engine = FakeSurya { page -> reading(page, PAGE_TEXT, listOf(figure, beside)) }

        val image = regions(engine, page(), structure = null).images.single()

        assertNull(image.caption)
    }

    @Test
    fun `a page with no Surya engine is a layout unavailable failure`() {
        val engines = PageOcrEngines(emptyList())

        val failure = assertFailsWith<SuryaLayoutUnavailableException> {
            regionsWith(engines, page(), structure = null)
        }

        assertEquals("NEEDS_SURYA_LAYOUT", failure.code)
    }

    @Test
    fun `a Surya runtime that cannot start is a layout unavailable failure`() {
        val engine = FakeSurya {
            throw OcrUnavailableException(SuryaOcr.NEEDS_SURYA_CODE, "the Surya runtime is not usable")
        }

        val failure = assertFailsWith<SuryaLayoutUnavailableException> {
            regions(engine, page(), structure = null)
        }

        assertEquals("NEEDS_SURYA_LAYOUT", failure.code)
    }

    @Test
    fun `a page that Surya cannot read is an empty result with its code, not a throw`() {
        val engine = FakeSurya { throw SuryaWorkerException(SuryaWorkerException.EXITED, "the worker ended") }

        val result = regions(engine, page(), structure = null)

        assertTrue(result.images.isEmpty())
        assertEquals(listOf(SuryaWorkerException.EXITED), result.failures)
    }

    @Test
    fun `a page larger than the pixel bound is refused before it is decoded, on both paths`() {
        val engine = FakeSurya { page -> reading(page, PAGE_TEXT, figureAWithCaption()) }
        val page = page()
        val small = LayoutRegions(PageOcrEngines(listOf(engine)), maxRenderedPixels = 1_000L)

        val stored = runBlocking {
            small.regionsOf(parent, page, structureOf(figureAWithCaption()), PAGE_TEXT, settings)
        }
        val fresh = runBlocking { small.regionsOf(parent, page, structure = null, pageText = null, settings = settings) }

        assertTrue(stored.images.isEmpty())
        assertEquals(listOf(ImageRejectedException.IMAGE_TOO_LARGE), stored.failures)
        assertTrue(fresh.images.isEmpty())
        assertEquals(listOf(ImageRejectedException.IMAGE_TOO_LARGE), fresh.failures)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `a stored structure without figures on an oversized page is an empty result, not a refusal`() {
        val small = LayoutRegions(PageOcrEngines(emptyList()), maxRenderedPixels = 1_000L)

        val result = runBlocking {
            val page = page()
            small.regionsOf(parent, page, structureOf(listOf(paragraph()), imageSha256 = page.sha256), PAGE_TEXT, settings)
        }

        assertEquals(ParentImages.NONE, result)
    }

    @Test
    fun `a missing Surya engine is reported before an oversized page is refused`() {
        val small = LayoutRegions(PageOcrEngines(emptyList()), maxRenderedPixels = 1_000L)

        assertFailsWith<SuryaLayoutUnavailableException> {
            runBlocking { small.regionsOf(parent, page(), structure = null, pageText = null, settings = settings) }
        }
    }

    private fun regions(engine: PageOcrEngine, page: PageImage, structure: PageStructure?): ParentImages =
        regionsWith(PageOcrEngines(listOf(engine)), page, structure)

    private fun regionsWith(engines: PageOcrEngines, page: PageImage, structure: PageStructure?): ParentImages =
        runBlocking {
            LayoutRegions(engines).regionsOf(parent, page, structure, PAGE_TEXT, settings)
        }

    /** A reading whose structure is [blocks] over [PAGE_TEXT]. */
    private fun reading(page: PageImage, text: String, blocks: List<StructureBlock>): OcrPageResult =
        OcrPageResult(
            text = text,
            engine = OcrEngine.SURYA,
            imageSha256 = page.sha256,
            structure = structureOf(blocks, text),
        )

    private fun structureOf(
        blocks: List<StructureBlock>,
        text: String = PAGE_TEXT,
        imageSha256: String = "a".repeat(64),
        parser: String = "surya",
    ): PageStructure = PageStructure(
        version = STRUCTURE_VERSION,
        textSha256 = PdfModelRules.sha256Of(text),
        imageSha256 = imageSha256,
        parser = parser,
        blocks = blocks,
    )

    private fun figureAWithCaption(): List<StructureBlock> = listOf(
        block(BlockKind.FIGURE, 0, FIGURE_A, FIGURE_A_RANGE),
        block(BlockKind.CAPTION, 1, PixelBox(100, 410, 500, 450), CAPTION_ONE_RANGE),
        block(BlockKind.FIGURE, 2, FIGURE_B, FIGURE_B_RANGE),
    )

    private fun paragraph(): StructureBlock = block(BlockKind.PARAGRAPH, 0, PixelBox(100, 50, 900, 80), FIGURE_A_RANGE)

    private fun block(kind: BlockKind, order: Int, box: PixelBox, range: TextRange): StructureBlock =
        StructureBlock(kind = kind, order = order, bbox = box, extracted = range, search = range)

    /** A 1000 by 1000 white page with [fills] drawn on it, written as a PNG under the test directory. */
    private fun page(fills: List<Pair<PixelBox, Color>> = emptyList()): PageImage {
        val root = Files.createDirectories(directory.resolve("page"))
        val raster = BufferedImage(PAGE_SIDE, PAGE_SIDE, BufferedImage.TYPE_INT_RGB)
        val graphics = raster.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, PAGE_SIDE, PAGE_SIDE)
            for ((box, colour) in fills) {
                graphics.color = colour
                graphics.fillRect(box.left, box.top, box.right - box.left, box.bottom - box.top)
            }
        } finally {
            graphics.dispose()
        }
        check(ImageIO.write(raster, "png", root.resolve(PAGE_FILE).toFile())) { "no PNG writer is available" }
        return PageImage.ofFile(
            documentId = DocumentId("doc-1"),
            unitId = "page:1",
            ordinal = 0,
            imageRoot = root,
            imageReference = PAGE_FILE,
            artifactRoot = directory.resolve("artifacts"),
            renderDpi = 72,
            rotationDegrees = 0,
        )
    }

    /** A Surya engine that answers each page with [answer] and counts the pages it was asked to read. */
    private class FakeSurya(private val answer: (PageImage) -> OcrPageResult) : PageOcrEngine {
        override val engine: OcrEngine = OcrEngine.SURYA

        var calls: Int = 0
            private set

        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
            calls++
            return answer(page)
        }
    }

    private companion object {
        const val PAGE_SIDE = 1000
        const val PAGE_FILE = "page.png"

        /** The page text: figure A's caption, then two figures whose own text is a label each. */
        const val PAGE_TEXT = "Figure one.\nCaption one.\nFigure two."

        val FIGURE_A = PixelBox(100, 100, 500, 400)
        val FIGURE_B = PixelBox(100, 600, 500, 900)

        val FIGURE_A_RANGE = range("Figure one.")
        val CAPTION_ONE_RANGE = range("Caption one.")
        val FIGURE_B_RANGE = range("Figure two.")

        fun range(part: String): TextRange {
            val start = PAGE_TEXT.indexOf(part)
            check(start >= 0) { "the test text holds $part" }
            return TextRange(start, start + part.length)
        }
    }
}
