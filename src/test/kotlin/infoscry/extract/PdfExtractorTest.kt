package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.ocr.OCR_TRANSCRIPTION_PROMPT_VERSION
import infoscry.ocr.OcrAttemptIdentity
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.PageOcrEngines
import infoscry.fixtures.PdfFixtureGenerator
import infoscry.fixtures.PdfFixtureGenerator.MIXED_NAME
import infoscry.fixtures.PdfFixtureGenerator.MIXED_SCANNED_PAGES
import infoscry.fixtures.PdfFixtureGenerator.MIXED_TEXT_PAGES
import infoscry.fixtures.PdfFixtureGenerator.MIXED_UNRENDERABLE_PAGE
import infoscry.fixtures.PdfFixtureGenerator.PROTECTED_NAME
import infoscry.fixtures.PdfFixtureGenerator.TEXT_NAME
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * The PDF extractor: which pages it reads, which pages it hands to OCR, and what it does with a page it
 * cannot produce.
 *
 * The tests run against the committed fixtures rather than hand-built objects, because the promise a
 * citation makes is that page N is the same page next time, and the place that promise breaks is exactly
 * what a hand-built model hides: a page whose text layer is only a header, a page that is a picture, a
 * page whose declared size cannot be rendered, and a document that cannot be opened at all.
 *
 * OCR is never invoked here. The seam is injected, so what these tests observe is the *decision* — which
 * pages were rendered, at what resolution, and in what order — while the real reading of a page belongs
 * to the Tesseract task.
 */
class PdfExtractorTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-pdf-extractor")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    // ---- The page decision itself, without a document in the way -------------------------------------

    @Test
    fun `thirty-nine alphanumeric characters still ask for ocr`() {
        assertTrue(PdfPageCandidate.needsOcr("a".repeat(39)))
    }

    @Test
    fun `forty alphanumeric characters are enough when the page is otherwise clean`() {
        assertFalse(PdfPageCandidate.needsOcr("a".repeat(40)))
    }

    @Test
    fun `a page whose alphanumeric ratio is below half asks for ocr`() {
        // Forty letters and forty-one marks: 40/81, which is just under half.
        assertTrue(PdfPageCandidate.needsOcr("a".repeat(40) + "!".repeat(41)))
    }

    @Test
    fun `a page whose alphanumeric ratio is exactly half does not ask for ocr`() {
        assertFalse(PdfPageCandidate.needsOcr("a".repeat(40) + "!".repeat(40)))
    }

    @Test
    fun `whitespace is removed before the ratio is measured`() {
        val text = buildString { repeat(20) { append("od\n") } }

        assertFalse(PdfPageCandidate.needsOcr(text))
        assertEquals(1.0, PdfPageCandidate.alphanumericRatio(text))
    }

    @Test
    fun `a page with no text at all asks for ocr`() {
        assertTrue(PdfPageCandidate.needsOcr(""))
        assertTrue(PdfPageCandidate.needsOcr("   \n\t "))
    }

    @Test
    fun `a candidate keeps the page number it was read from`() {
        val candidate = PdfPageCandidate(page = 7, text = "kort")

        assertEquals(7, candidate.page)
        assertTrue(candidate.needsOcr)
    }

    // ---- A readable document never reaches OCR ------------------------------------------------------

    @Test
    fun `every page of a readable pdf is cited by its page number and none reaches ocr`() {
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(pdfExtractor(spy), inputFor(fixture(TEXT_NAME), probe), probe)
        val units = units(events)

        assertEquals(listOf(1, 2, 3), units.map { (it.unit.locator as SourceLocation.PdfPage).page })
        assertEquals(listOf(0, 1, 2), units.map { it.ordinal })
        assertEquals(listOf("page:1", "page:2", "page:3"), units.map { it.key })
        assertContains(units.first().unit.extractedText, "Protokollet")
        assertEquals(3, (events.last() as ExtractionEvent.Finished).totalUnits)
        assertTrue(spy.pages.isEmpty(), "a page with its own text was rendered for OCR")
    }

    // ---- The mode the attempt was admitted with -------------------------------------------------------

    @Test
    fun `a long but incorrect text layer is read by ocr when the attempt says to check and improve`() {
        // Every page of this fixture carries a long, usable text layer, so fill-missing never renders it —
        // which is what makes it the case the mode exists for: a text layer that is long is not a text
        // layer that is right, and an attempt admitted to check and improve reads the page images anyway.
        val legacy = OcrSpy()
        val legacyEvents = collect(pdfExtractor(legacy), inputFor(fixture(TEXT_NAME), probe()), probe())

        assertTrue(
            legacy.pages.isEmpty(),
            "fill-missing rendered a page whose own text passes every current heuristic",
        )
        assertEquals(listOf(1, 2, 3), units(legacyEvents).map { pageOf(it.key) })

        val rescan = OcrSpy()
        val rescanEvents = collect(
            pdfExtractor(rescan),
            inputFor(fixture(TEXT_NAME), probe(), settings = checkAndImprove()),
            probe(),
        )

        assertEquals(listOf(1, 2, 3), rescan.readPages, "the rescan did not read every page image")
        assertEquals(
            listOf("läst sida 1", "läst sida 2", "läst sida 3"),
            units(rescanEvents).map { it.unit.extractedText },
            "the rescan's page text is the embedded layer it was asked to check rather than what it read",
        )
        assertTrue(units(rescanEvents).all { it.unit.meanConfidence == null || it.unit.method == ExtractionMethod.OCR })
    }

    @Test
    fun `a page image read for review is kept as the attempt's evidence`() {
        // A rescan's pages are reviewed against the image they were read from, so those images outlive the
        // attempt. A fill-missing render is working material instead, which the test above the helpers
        // asserts: it is deleted as soon as the page it belongs to is committed.
        val spy = OcrSpy()
        val settings = checkAndImprove()
        val input = inputFor(fixture(TEXT_NAME), probe(), settings = settings)

        collect(pdfExtractor(spy), input, probe())

        assertTrue(spy.pages.isNotEmpty(), "nothing was rendered, so this test proves nothing")
        spy.pages.forEach { image ->
            assertTrue(Files.isRegularFile(image.imagePath), "${image.imagePath} was removed after it was read")
            assertTrue(
                image.imagePath.startsWith(input.artifactRoot.resolve(fingerprint(settings).value)),
                "a page image was written outside this attempt's artifacts: ${image.imagePath}",
            )
        }
    }

    @Test
    fun `a rotated page keeps the rotation and the raster dimensions it was read at`() {
        val path = writeRotated(directory.resolve("rotated.pdf"), rotation = 90)
        val spy = OcrSpy()

        val events = collect(
            pdfExtractor(spy),
            inputFor(path, probe(), settings = checkAndImprove()),
            probe(),
        )

        val image = spy.pages.single()
        assertEquals(90, image.rotationDegrees, "the page's declared rotation did not reach the reading")
        assertEquals(0, image.ordinal)
        assertTrue(
            assertNotNull(image.width) > assertNotNull(image.height),
            "a page rotated a quarter turn was recorded with the dimensions of the unrotated page",
        )
        assertEquals(1, units(events).size)
    }

    @Test
    fun `check and improve refuses a page it cannot render while the other pages still deliver`() {
        val spy = OcrSpy()

        val events = collect(
            pdfExtractor(spy),
            inputFor(fixture(MIXED_NAME), probe(), settings = checkAndImprove()),
            probe(),
        )

        assertEquals(
            MIXED_TEXT_PAGES + MIXED_SCANNED_PAGES,
            spy.readPages,
            "a page whose raster fits the bound was not read",
        )
        assertEquals(
            listOf(MIXED_UNRENDERABLE_PAGE),
            failures(events).map { it.key }.map(::pageOf),
            "a page whose declared size cannot be rendered did not fail on its own",
        )
        assertEquals(PdfExtractor.PAGE_RENDER_REFUSED_CODE, failures(events).single().code)
        assertEquals(MIXED_TEXT_PAGES + MIXED_SCANNED_PAGES, units(events).map { pageOf(it.key) })
        assertIs<ExtractionEvent.Finished>(events.last())
    }

    @Test
    fun `check and improve refuses an encrypted document instead of rendering it`() {
        val spy = OcrSpy()

        val events = collect(
            pdfExtractor(spy),
            inputFor(fixture(PROTECTED_NAME), probe(), settings = checkAndImprove()),
            probe(),
        )

        assertEquals(listOf(ENCRYPTED_DOCUMENT_CODE), failures(events).map { it.code })
        assertTrue(spy.pages.isEmpty(), "a protected document was rendered")
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    // ---- Pages that have to be read by OCR --------------------------------------------------------

    @Test
    fun `only the pages whose text layer is empty are rendered, at the default resolution`() {
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        collect(pdfExtractor(spy), inputFor(fixture(MIXED_NAME), probe), probe)

        assertEquals(MIXED_SCANNED_PAGES, spy.readPages)
        assertTrue(spy.pages.all { it.renderDpi == PdfExtractor.DEFAULT_RENDER_DPI })
        assertTrue(spy.imagesPresent.all { it }, "ocr was asked to read an image that was not there")
        assertTrue(spy.settings.all { it.language == "eng" }, "the collection's languages did not reach the engine")
        assertTrue(spy.pages.all { it.documentId == DocumentId("doc-1") })
    }

    @Test
    fun `the text ocr read becomes the unit text for that page`() {
        val spy = OcrSpy(text = { page -> "läst sida $page" })

        val units = units(collect(pdfExtractor(spy), inputFor(fixture(MIXED_NAME), probe()), probe()))
        val perPage = units.associate { (it.unit.locator as SourceLocation.PdfPage).page to it.unit.extractedText }

        assertEquals("läst sida 3", perPage[3])
        assertEquals("läst sida 4", perPage[4])
        assertEquals("läst sida 5", perPage[5])
        assertContains(perPage.getValue(1), "Protokollet")
    }

    @Test
    fun `the rendered images are gone once the attempt ends`() {
        val spy = OcrSpy()

        collect(pdfExtractor(spy), inputFor(fixture(MIXED_NAME), probe()), probe())

        assertTrue(spy.pages.isNotEmpty(), "nothing was rendered, so this test proves nothing")
        spy.pages.forEach { page ->
            assertFalse(Files.exists(page.imagePath), "${page.imagePath} was left behind")
        }
        spy.pages.map { it.imagePath.parent }.distinct().forEach { root ->
            assertFalse(Files.exists(root), "the attempt left its working directory $root behind")
        }
    }

    @Test
    fun `the job's own render resolution is the one used`() {
        val spy = OcrSpy()
        val input = inputFor(
            fixture(MIXED_NAME),
            probe(),
            settings = ExtractionSettings(ocrLanguages = "eng", renderDpi = 150),
        )

        collect(pdfExtractor(spy), input, probe())

        assertEquals(MIXED_SCANNED_PAGES, spy.readPages)
        assertTrue(spy.pages.all { it.renderDpi == 150 }, "the resolution in the job's settings was ignored")
    }

    @Test
    fun `a page is rendered at the highest resolution that fits the pixel bound`() {
        val spy = OcrSpy()
        val extractor = pdfExtractor(spy, maxRenderedPixels = 2_500_000L)

        collect(extractor, inputFor(fixture(MIXED_NAME), probe()), probe())

        assertEquals(MIXED_SCANNED_PAGES, spy.readPages)
        assertTrue(
            spy.pages.all { it.renderDpi in PdfExtractor.MIN_RENDER_DPI until PdfExtractor.DEFAULT_RENDER_DPI },
            "a page that cannot be rendered at the default resolution was not brought within the bound: " +
                spy.pages.map { it.renderDpi },
        )
    }

    @Test
    fun `a page the pixel bound cannot hold at all fails instead of being allocated`() {
        val spy = OcrSpy()
        val extractor = pdfExtractor(spy, maxRenderedPixels = 100_000L)

        val events = collect(extractor, inputFor(fixture(MIXED_NAME), probe()), probe())

        assertEquals(
            MIXED_SCANNED_PAGES + listOf(MIXED_UNRENDERABLE_PAGE),
            failures(events).map { it.key }.map(::pageOf),
            "a page whose raster does not fit the bound did not fail",
        )
        assertTrue(
            failures(events).all { it.code == PdfExtractor.PAGE_RENDER_REFUSED_CODE },
            "a page that was never rendered failed for another reason",
        )
        assertTrue(spy.pages.isEmpty(), "an image past the bound was allocated anyway")
    }

    @Test
    fun `a scan encoded as jpeg2000 is rendered instead of dropped`() {
        // PDFBox has no JPEG 2000 decoder of its own. With none on the classpath it does not fail: it logs
        // the missing reader and draws nothing, so the page renders blank and OCR hands back an empty
        // reading that is committed as though the page had been read. The check is on the raster, because
        // a blank raster is what the bug actually produces.
        val source = directory.resolve("jpeg2000.pdf")
        val scan = scanImage()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val image = PDImageXObject(
                document,
                ByteArrayInputStream(encodeJpeg2000(scan)),
                COSName.JPX_DECODE,
                scan.width,
                scan.height,
                8,
                PDDeviceRGB.INSTANCE,
            )
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
            }
            document.save(source.toFile())
        }

        val raster = Loader.loadPDF(source.toFile()).use { loaded ->
            ImageIO.read(
                PngPageRenderer.render(PDFRenderer(loaded), 1, 150, directory).toFile(),
            )
        }

        val inked = (0 until raster.height).sumOf { y ->
            (0 until raster.width).count { x -> raster.getRGB(x, y) and 0xFFFFFF != 0xFFFFFF }
        }
        assertTrue(inked > 0, "the page's image was dropped and the page rendered blank")
    }

    // ---- The OCR reading travels with its page -------------------------------------------------------

    @Test
    fun `an ocred page's unit carries its word boxes and the confidence ocr reported`() {
        val spy = OcrSpy(
            text = { page -> "läst sida $page" },
            meanConfidence = { 88.5 },
            artifact = { page -> "ocr/page-$page.tsv.gz" to "c".repeat(64) },
        )

        val settings = ExtractionSettings(ocrLanguages = "eng")
        val units = units(
            collect(pdfExtractor(spy), inputFor(fixture(MIXED_NAME), probe(), settings = settings), probe()),
        )
        val ocred = units.single { pageOf(it.key) == 3 }
        val parsed = units.single { pageOf(it.key) == 1 }

        assertEquals("${fingerprint(settings).value}/ocr/page-3.tsv.gz", ocred.unit.artifactRelativePath)
        assertEquals("c".repeat(64), ocred.unit.artifactSha256)
        assertEquals(88.5, ocred.unit.meanConfidence)
        assertEquals(
            ExtractionMethod.OCR,
            ocred.unit.method,
            "the page the tool read says so itself, rather than being recognised by its confidence",
        )
        assertEquals(
            null,
            parsed.unit.artifactRelativePath,
            "a page with its own text layer claimed an artifact it never wrote",
        )
        assertEquals(null, parsed.unit.meanConfidence)
        assertEquals(
            ExtractionMethod.DIRECT_TEXT,
            parsed.unit.method,
            "a parser's page and a recognised page are two methods, whatever their confidences",
        )
        assertNull(
            parsed.unit.sourceImage,
            "a page whose own text layer was read named an image nobody rendered for it",
        )
        // The page the tool read names the image it was read from, at the place a later reader resolves
        // artifacts from: this attempt's own directory under the document's artifact root, with the name a
        // page's image has. A fill-missing render is working material, so it is the attempt's `working`
        // directory rather than the `pages` a reviewed rescan keeps — the reading says which image it came
        // from either way, which is the point. A page whose text came out of the container names nothing.
        val rendered = assertNotNull(ocred.unit.sourceImage)
        assertEquals(SourceImageRoot.ARTIFACTS, rendered.root)
        assertEquals(
            "${fingerprint(settings).value}/working/page-000003.png",
            rendered.relativePath,
            "the reading does not name the page image it was rendered from where a reader can find it",
        )
        val pageThree = spy.pages.single { image -> image.ordinal == 2 }
        assertEquals(pageThree.width, rendered.width)
        assertEquals(pageThree.height, rendered.height)
        assertEquals(PageImage.RENDER_VERSION, rendered.renderVersion)
    }

    // ---- What the document says about itself before it is read ----------------------------------------

    @Test
    fun `a pdf announces its page count and its unit kind before it reads a page`() {
        val events = collect(pdfExtractor(OcrSpy()), inputFor(fixture(MIXED_NAME), probe()), probe())

        val announced = events.filterIsInstance<ExtractionEvent.Progress>().single()
        assertEquals(UnitKind.PAGE, announced.unitKind)
        assertEquals(
            events.filterIsInstance<ExtractionEvent.Finished>().single().totalUnits,
            announced.totalUnits,
            "the announced total is the page count the finished pass reports, not a guess about the rest",
        )
        assertTrue(MIXED_SCANNED_PAGES.size < announced.totalUnits, "the fixture has pages beyond the scans")
        assertEquals(
            events.first(),
            announced,
            "the total is announced before anything is read, so progress has a denominator from the start",
        )
    }

    // ---- A page whose own text cannot be read is handed over, not failed ------------------------------

    @Test
    fun `a page whose own text cannot be read is handed to ocr instead of failing`() {
        // Page 1 has a usable text layer, so it would be cited from its own text if the read succeeded.
        // PDFBox is deliberately forgiving about damaged content and cannot be made to produce this
        // failure from a fixture, which is why the reader is the seam that makes it reachable at all.
        val spy = OcrSpy()

        val events = collect(
            pdfExtractor(spy, pageText = FailingPageTextReader(failOn = { page -> page == 1 })),
            inputFor(fixture(MIXED_NAME), probe()),
            probe(),
        )

        assertEquals(
            listOf(1) + MIXED_SCANNED_PAGES,
            spy.readPages,
            "the page whose own text could not be read was not handed to ocr",
        )
        assertEquals(MIXED_TEXT_PAGES + MIXED_SCANNED_PAGES, units(events).map { pageOf(it.key) })
        assertTrue(
            failures(events).none { it.code == PdfExtractor.PAGE_UNREADABLE_CODE },
            "a page that OCR could still read was reported as unreadable",
        )
        assertEquals(
            "1",
            (events.last() as ExtractionEvent.Finished).metadata[PdfExtractor.EXTRACTION_WARNINGS_METADATA],
        )
    }

    @Test
    fun `a page whose text and raster both fail is reported as unreadable`() {
        val events = collect(
            pdfExtractor(
                OcrSpy(),
                pageText = FailingPageTextReader(failOn = { page -> page == MIXED_UNRENDERABLE_PAGE }),
            ),
            inputFor(fixture(MIXED_NAME), probe()),
            probe(),
        )

        assertEquals(
            PdfExtractor.PAGE_UNREADABLE_CODE,
            failures(events).single { it.key == "page:$MIXED_UNRENDERABLE_PAGE" }.code,
            "a page with neither text nor raster was not reported as unreadable",
        )
    }

    @Test
    fun `a rendered image that cannot be removed is a warning rather than a lost page`() {
        // The image is a real page image the attempt is not allowed to delete: it was written into a
        // directory with no write permission, which is what makes the removal fail the way a real one
        // would. A page image that is not a readable image at all is a render failure instead; the test
        // below covers that.
        val renderer = PdfPageRenderer { _, page, _, where ->
            val folder = where.resolve("page-$page")
            Files.createDirectories(folder)
            val target = folder.resolve("page-%06d.png".format(page))
            ImageIO.write(blankRaster(), "png", target.toFile())
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"))
            target
        }

        val spy = OcrSpy()
        val events = try {
            collect(
                pdfExtractor(spy, pageRenderer = renderer),
                inputFor(fixture(MIXED_NAME), probe()),
                probe(),
            )
        } finally {
            // The permission that made the removal fail is restored, or the test's own directory could
            // never be cleaned up.
            spy.pages.map { image -> image.imagePath.parent }.distinct().forEach { folder ->
                Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
            }
        }

        assertEquals(MIXED_TEXT_PAGES + MIXED_SCANNED_PAGES, units(events).map { pageOf(it.key) })
        assertTrue(
            failures(events).none { it.code == OCR_FAILED_CODE },
            "a page whose working image could not be deleted was reported as an OCR failure",
        )
        assertEquals(
            MIXED_SCANNED_PAGES.size.toString(),
            (events.last() as ExtractionEvent.Finished).metadata[PdfExtractor.EXTRACTION_WARNINGS_METADATA],
        )
    }

    @Test
    fun `a render that produces something no engine can read is a refused page`() {
        // The image the renderer names is a directory: there is no raster to read and no hash to record, so
        // the page cannot be produced. It is one refused page rather than an attempt that reads paper.
        val renderer = PdfPageRenderer { _, page, _, where ->
            val stubborn = where.resolve("page-$page.png")
            Files.createDirectory(stubborn)
            stubborn
        }

        val events = collect(
            pdfExtractor(OcrSpy(), pageRenderer = renderer),
            inputFor(fixture(MIXED_NAME), probe()),
            probe(),
        )

        assertEquals(
            MIXED_SCANNED_PAGES + listOf(MIXED_UNRENDERABLE_PAGE),
            failures(events).map { it.key }.map(::pageOf),
            "a page whose raster could not be read back was not refused",
        )
        assertTrue(failures(events).all { it.code == PdfExtractor.PAGE_RENDER_REFUSED_CODE })
        assertEquals(MIXED_TEXT_PAGES, units(events).map { pageOf(it.key) })
    }

    // ---- One page that cannot be produced does not take the document with it -------------------------

    @Test
    fun `a page whose image never reached the raster fails instead of being read as empty`() {
        // A renderer that produces blank paper, which is what PDFBox yields for an image whose decoder is
        // missing: it logs the error and draws nothing. OCR then reads empty paper, and the page must not
        // be committed as an empty reading of text that is really on the page.
        val renderer = PdfPageRenderer { _, page, _, where ->
            val blank = where.resolve("page-$page.png")
            ImageIO.write(blankRaster(), "png", blank.toFile())
            blank
        }
        val spy = OcrSpy(text = { "" })

        val events = collect(
            pdfExtractor(spy, pageRenderer = renderer),
            inputFor(fixture(MIXED_NAME), probe()),
            probe(),
        )

        assertEquals(
            MIXED_SCANNED_PAGES.map { "page:$it" },
            failures(events).filter { it.code == PAGE_BLANK_CODE }.map { it.key },
            "a page whose image was dropped was not reported as blank",
        )
        assertEquals(
            MIXED_SCANNED_PAGES,
            spy.readPages,
            "the pages whose rasters came out blank were not the ones read",
        )
    }

    @Test
    fun `a page that reads as empty but carries ink fails its unit rather than being committed`() {
        // Empty output is not an extraction: the raster is asked whether the page is blank, and a page whose
        // ink OCR could not turn into text is neither blank paper nor read, so its unit fails under the code
        // an engine answers an empty reading with.
        val spy = OcrSpy(text = { "" }, errorCode = OcrPageResult.EMPTY_READING_CODE)

        val events = collect(pdfExtractor(spy), inputFor(fixture(MIXED_NAME), probe()), probe())

        assertTrue(
            failures(events).none { it.code == PAGE_BLANK_CODE },
            "an inked page whose reading was empty was reported as blank paper",
        )
        assertEquals(
            MIXED_SCANNED_PAGES.map { page -> "page:$page" to OcrPageResult.EMPTY_READING_CODE },
            failures(events).filter { it.code == OcrPageResult.EMPTY_READING_CODE }
                .map { failure -> failure.key to failure.code },
            "the pages that read as empty were not failed under the code that says they are not readings",
        )
        assertEquals(MIXED_TEXT_PAGES, units(events).map { pageOf(it.key) })
    }

    @Test
    fun `a page that cannot be rendered fails as a unit while every other page still delivers`() {
        val events = collect(pdfExtractor(OcrSpy()), inputFor(fixture(MIXED_NAME), probe()), probe())

        val failed = failures(events)
        assertEquals(listOf("page:$MIXED_UNRENDERABLE_PAGE"), failed.map { it.key })
        assertEquals(PdfExtractor.PAGE_RENDER_REFUSED_CODE, failed.single().code)
        assertEquals(
            MIXED_TEXT_PAGES + MIXED_SCANNED_PAGES,
            units(events).map { (it.unit.locator as SourceLocation.PdfPage).page },
        )
        assertIs<ExtractionEvent.Finished>(events.last())
        assertEquals(
            MIXED_TEXT_PAGES.size + MIXED_SCANNED_PAGES.size + 1,
            (events.last() as ExtractionEvent.Finished).totalUnits,
            "the page that failed was dropped from the document's own count",
        )
    }

    @Test
    fun `a page whose ocr fails does not discard the pages around it`() {
        val spy = OcrSpy(failOn = { page -> page == 4 })

        val events = collect(pdfExtractor(spy), inputFor(fixture(MIXED_NAME), probe()), probe())

        val ocrFailures = failures(events).filter { it.code == OCR_FAILED_CODE }
        assertEquals(listOf("page:4"), ocrFailures.map { it.key })
        assertEquals(
            MIXED_SCANNED_PAGES,
            spy.readPages,
            "the pages around the failing one were not handed over",
        )
        assertEquals(
            MIXED_TEXT_PAGES + listOf(MIXED_SCANNED_PAGES.first(), MIXED_SCANNED_PAGES.last()),
            units(events).map { (it.unit.locator as SourceLocation.PdfPage).page },
        )
        assertIs<ExtractionEvent.Finished>(events.last())
    }

    @Test
    fun `an ocr tool that is not available fails the document instead of every page`() {
        val spy = OcrSpy(unavailable = "NEEDS_TESSERACT")

        val events = collect(pdfExtractor(spy), inputFor(fixture(MIXED_NAME), probe()), probe())

        assertEquals(listOf(PdfExtractor.DOCUMENT_KEY), failures(events).map { it.key })
        assertEquals("NEEDS_TESSERACT", failures(events).single().code)
        assertEquals(
            MIXED_TEXT_PAGES,
            units(events).map { (it.unit.locator as SourceLocation.PdfPage).page },
            "the pages already read before the tool was missing were dropped",
        )
        assertTrue(
            events.none { it is ExtractionEvent.Finished },
            "a document that stopped early reported itself as finished",
        )
        assertEquals(MIXED_SCANNED_PAGES.take(1), spy.readPages)
    }

    // ---- Documents that cannot be read at all -------------------------------------------------------

    @Test
    fun `a password protected pdf is refused as encrypted`() {
        val spy = OcrSpy()

        val events = collect(pdfExtractor(spy), inputFor(fixture(PROTECTED_NAME), probe()), probe())

        assertEquals(listOf(ENCRYPTED_DOCUMENT_CODE), failures(events).map { it.code })
        assertEquals(listOf(PdfExtractor.DOCUMENT_KEY), failures(events).map { it.key })
        assertTrue(events.none { it is ExtractionEvent.Finished })
        assertTrue(units(events).isEmpty())
        assertTrue(spy.pages.isEmpty())
    }

    @Test
    fun `a pdf that opens with an owner password only is still refused`() {
        val path = writeOwnerProtected(directory.resolve("owner-protected.pdf"))

        val events = collect(pdfExtractor(OcrSpy()), inputFor(path, probe()), probe())

        assertEquals(listOf(ENCRYPTED_DOCUMENT_CODE), failures(events).map { it.code })
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    @Test
    fun `a container past the size bound is refused before it is opened`() {
        val extractor = pdfExtractor(OcrSpy(), maxDocumentBytes = 1L)

        val events = collect(extractor, inputFor(fixture(TEXT_NAME), probe()), probe())

        assertEquals(listOf(DOCUMENT_TOO_LARGE_CODE), failures(events).map { it.code })
        assertEquals(listOf(DOCUMENT_TOO_LARGE_KEY), failures(events).map { it.key })
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    @Test
    fun `a refusal that is already committed is not reported a second time`() {
        val extractor = pdfExtractor(OcrSpy(), maxDocumentBytes = 1L)
        val input = inputFor(fixture(TEXT_NAME), probe(), committed = setOf(DOCUMENT_TOO_LARGE_KEY))

        assertEquals(emptyList(), collect(extractor, input, probe()))
    }

    @Test
    fun `an encrypted refusal that is already committed is not reported a second time`() {
        val input = inputFor(fixture(PROTECTED_NAME), probe(), committed = setOf(PdfExtractor.DOCUMENT_KEY))

        assertEquals(emptyList(), collect(pdfExtractor(OcrSpy()), input, probe()))
    }

    @Test
    fun `an empty document finishes with no units and asks for nothing`() {
        val spy = OcrSpy()
        val path = writeEmpty(directory.resolve("empty.pdf"))

        val events = collect(pdfExtractor(spy), inputFor(path, probe()), probe())

        assertEquals(emptyList(), units(events))
        assertEquals(
            0,
            events.filterIsInstance<ExtractionEvent.Finished>().single().totalUnits,
            "a document with no pages finishes with a total of zero, announced beside the one it started with",
        )
        assertTrue(spy.pages.isEmpty())
    }

    @Test
    fun `a container that is not a readable pdf is refused instead of thrown`() {
        val path = writeGarbage(directory.resolve("garbage.pdf"))

        val events = collect(pdfExtractor(OcrSpy()), inputFor(path, probe()), probe())

        assertEquals(listOf(DOCUMENT_UNREADABLE_CODE), failures(events).map { it.code })
        assertEquals(listOf(PdfExtractor.DOCUMENT_KEY), failures(events).map { it.key })
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    // ---- Resume: committed pages are skipped before anything expensive happens -----------------------

    @Test
    fun `a resumed attempt asks ocr only for the pages that are still unfinished`() {
        // The first three pages of the fixture are the readable pair plus the first scanned page, so a
        // committed page that *would* have needed OCR is exactly the case this asserts.
        val committed = setOf("page:1", "page:2", "page:3")
        val spy = OcrSpy()

        val events = collect(
            pdfExtractor(spy),
            inputFor(fixture(MIXED_NAME), probe(), committed = committed),
            probe(),
        )

        assertEquals(MIXED_SCANNED_PAGES.drop(1), spy.readPages)
        assertEquals(listOf("page:4", "page:5"), units(events).map { it.key })
        assertEquals(
            MIXED_TEXT_PAGES.size + MIXED_SCANNED_PAGES.size + 1,
            (events.last() as ExtractionEvent.Finished).totalUnits,
            "the committed pages were dropped from the document's own count",
        )
    }

    @Test
    fun `a cancelled attempt never renders a page it was told is finished`() {
        val committed = setOf("page:1", "page:2", "page:3")
        val spy = OcrSpy(cancelOn = { page -> page == 5 })

        assertFailsWith<CancellationException> {
            collect(
                pdfExtractor(spy),
                inputFor(fixture(MIXED_NAME), probe(), committed = committed),
                probe(),
            )
        }

        assertEquals(listOf(4, 5), spy.readPages)
        assertTrue(spy.readPages.none { it <= 3 }, "a page that was already committed was rendered again")
    }

    @Test
    fun `a resumed attempt never renders a page it was told is finished`() {
        // Page 3 is a scanned page, which is exactly the case the skip has to cover: without the resume
        // check it would be rendered again, so a counting renderer tells "skipped before rendering" apart
        // from "skipped before OCR".
        val committed = setOf("page:1", "page:2", "page:3")
        val renderer = CountingRenderer()

        collect(
            pdfExtractor(OcrSpy(), pageRenderer = renderer),
            inputFor(fixture(MIXED_NAME), probe(), committed = committed),
            probe(),
        )

        assertEquals(listOf(4, 5), renderer.pages, "a page that was already committed was rendered again")
    }

    @Test
    fun `a programming error while rendering is not reported as a bad page`() {
        val renderer = PdfPageRenderer { _, _, _, _ ->
            throw IllegalStateException("a bug in the extractor, not a page that cannot be rendered")
        }

        val failure = assertFailsWith<IllegalStateException> {
            collect(
                pdfExtractor(OcrSpy(), pageRenderer = renderer),
                inputFor(fixture(MIXED_NAME), probe()),
                probe(),
            )
        }

        assertContains(failure.message.orEmpty(), "a bug in the extractor")
    }

    @Test
    fun `a managed file that is gone is refused instead of throwing at the caller`() {
        val missing = directory.resolve("managed").resolve("gone.pdf")

        val events = collect(pdfExtractor(OcrSpy()), inputFor(missing, probe()), probe())

        assertEquals(listOf(PdfExtractor.DOCUMENT_KEY), failures(events).map { it.key })
        assertEquals(listOf(DOCUMENT_UNREADABLE_CODE), failures(events).map { it.code })
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    // ---- The boundary protocol ----------------------------------------------------------------------

    @Test
    fun `every event is emitted inside a unit permit, one permit per event`() {
        val probe = PermitProbeBoundary()

        val events = collect(pdfExtractor(OcrSpy()), inputFor(fixture(MIXED_NAME), probe), probe)

        assertEquals(emptyList(), probe.eventsOutsidePermit)
        assertEquals(
            events.size,
            probe.permits,
            "the extractor took a permit it did not need, or emitted without one",
        )
    }

    @Test
    fun `a refusal found mid-page is emitted inside that page's permit`() {
        val probe = PermitProbeBoundary()
        val spy = OcrSpy(unavailable = TesseractOcr.NEEDS_TESSERACT_CODE)

        val events = collect(pdfExtractor(spy), inputFor(fixture(MIXED_NAME), probe), probe)

        assertEquals(emptyList(), probe.eventsOutsidePermit)
        assertEquals(
            events.size,
            probe.permits,
            "the document-level refusal opened a permit of its own instead of using the page's",
        )
        val refusal = failures(events).single()
        assertEquals(DOCUMENT_REFUSED_KEY, refusal.key)
        assertEquals(TesseractOcr.NEEDS_TESSERACT_CODE, refusal.code)
        assertTrue(events.none { it is ExtractionEvent.Finished }, "a refused document was reported done")
    }

    @Test
    fun `the extractor produces the same keys locators and ordinals twice`() {
        val first = units(collect(pdfExtractor(OcrSpy()), inputFor(fixture(MIXED_NAME), probe()), probe()))
        val second = units(collect(pdfExtractor(OcrSpy()), inputFor(fixture(MIXED_NAME), probe()), probe()))

        assertEquals(first.map { it.key }, second.map { it.key })
        assertEquals(first.map { it.ordinal }, second.map { it.ordinal })
        assertEquals(first.map { it.unit.locator }, second.map { it.unit.locator })
        assertNotEquals(first.first().key, first.last().key)
    }

    // ---- Wiring and fixtures -------------------------------------------------------------------------

    @Test
    fun `a pdf fixture is detected from its bytes and routed to the pdf extractor`() {
        val mediaType = MediaTypeDetector().detect(fixture(TEXT_NAME)).value

        assertEquals(PdfExtractor.PDF_MEDIA_TYPE, mediaType)
        assertEquals(PdfExtractor::class, ExtractorRegistry.production().select(mediaType)::class)
    }

    @Test
    fun `the extractor claims the media type the detector reports for every fixture`() {
        val extractor = pdfExtractor(OcrSpy())

        listOf(TEXT_NAME, MIXED_NAME, PROTECTED_NAME).forEach { name ->
            val mediaType = MediaTypeDetector().detect(fixture(name)).value
            assertTrue(mediaType in extractor.supportedMediaTypes, "$name was not claimed by the extractor")
        }
    }

    @Test
    fun `the generator reproduces the committed fixtures byte for byte`() {
        val regenerated = PdfFixtureGenerator.writeAll(Files.createTempDirectory("infoscry-pdf-regenerated"))

        // Protected PDFs are excluded on purpose: encrypting a document generates a fresh file key (and a
        // trailer id over it) on every save, which is what encrypting a document means. The committed file
        // is what a reader uses; nothing compares its bytes.
        val comparable = regenerated.filter { it.fileName.toString() != PROTECTED_NAME }
        assertEquals(2, comparable.size, "every fixture PDFBox writes itself is regenerated")
        comparable.forEach { path ->
            val committed = fixtureDirectory().resolve(path.fileName.toString())
            assertTrue(Files.exists(committed), "${path.fileName} is not committed")
            assertTrue(
                Files.readAllBytes(path).contentEquals(Files.readAllBytes(committed)),
                "${path.fileName} differs from the committed fixture",
            )
        }
        assertTrue(
            Files.exists(fixtureDirectory().resolve(PROTECTED_NAME)),
            "the protected fixture is committed, because its bytes cannot be reproduced",
        )
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private fun units(events: List<ExtractionEvent>): List<ExtractionEvent.UnitReady> =
        events.filterIsInstance<ExtractionEvent.UnitReady>()

    private fun failures(events: List<ExtractionEvent>): List<ExtractionEvent.UnitFailed> =
        events.filterIsInstance<ExtractionEvent.UnitFailed>()

    private fun pageOf(key: String): Int = key.substringAfter("page:").toInt()

    /** The extractor under test, with the one fake engine this file reads pages with. */
    private fun pdfExtractor(
        spy: OcrSpy,
        pageText: PdfPageTextReader = PdfBoxPageTextReader,
        pageRenderer: PdfPageRenderer = PngPageRenderer,
        maxDocumentBytes: Long = PdfExtractor.MAX_DOCUMENT_BYTES,
        maxRenderedPixels: Long = PdfExtractor.MAX_RENDERED_PIXELS,
    ): PdfExtractor = PdfExtractor(
        ocr = PageOcrEngines(listOf(spy)),
        pageText = pageText,
        pageRenderer = pageRenderer,
        maxDocumentBytes = maxDocumentBytes,
        maxRenderedPixels = maxRenderedPixels,
    )

    /** The fingerprint one attempt's checkpoints are keyed by, which is where its artifacts live. */
    private fun fingerprint(settings: ExtractionSettings): ExtractionFingerprint =
        ExtractionFingerprint.of("b".repeat(64), settings)

    /** The settings an attempt that reads page images even where a text layer exists runs with. */
    private fun checkAndImprove(
        renderDpi: Int = PdfExtractor.DEFAULT_RENDER_DPI,
    ): ExtractionSettings = ExtractionSettings(
        ocrLanguages = "eng",
        renderDpi = renderDpi,
        ocrMode = OcrImportMode.CHECK_AND_IMPROVE,
        ocrAttempt = OcrAttemptIdentity(
            engine = OcrEngine.TESSERACT,
            language = "eng",
            transcriptionPromptVersion = OCR_TRANSCRIPTION_PROMPT_VERSION,
            extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
            toolVersion = "tesseract 5.3.0",
            renderDpi = renderDpi,
        ),
    )

    private fun probe(): PermitProbeBoundary = PermitProbeBoundary()

    private fun collect(
        extractor: DocumentExtractor,
        input: ExtractionInput,
        probe: PermitProbeBoundary,
    ): List<ExtractionEvent> = runBlocking {
        extractor.extract(input).onEach { probe.observed(it) }.toList()
    }

    private fun inputFor(
        source: Path,
        boundary: UnitBoundary,
        committed: Set<String> = emptySet(),
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = "eng"),
    ): ExtractionInput = ExtractionInput(
        documentId = DocumentId("doc-1"),
        managedPath = source,
        artifactRoot = directory.resolve("artifacts"),
        settings = settings,
        fingerprint = ExtractionFingerprint.of("b".repeat(64), settings),
        committedUnitKeys = committed,
        boundary = boundary,
    )

    /** Copies a committed fixture into the managed area a real extraction would read from. */
    private fun fixture(name: String): Path {
        val target = directory.resolve("managed").resolve(name)
        Files.createDirectories(target.parent)
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "fixture /fixtures/$name is missing"
        }
        stream.use { Files.copy(it, target, REPLACE_EXISTING) }
        return target
    }

    private fun fixtureDirectory(): Path = Path.of("src/test/resources/fixtures")

    /** Blank paper: what a page whose image was dropped, or a page with no content at all, renders as. */
    private fun blankRaster(): BufferedImage {
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** A scan-like page: dark bars on white, the shape a photographed page has. */
    private fun scanImage(): BufferedImage {
        val image = BufferedImage(595, 842, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(0x22, 0x22, 0x22)
            graphics.fillRect(80, 120, 420, 40)
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** [image] encoded as JPEG 2000, through the ImageIO plugin PDFBox reads that format with. */
    private fun encodeJpeg2000(image: BufferedImage): ByteArray {
        val bytes = ByteArrayOutputStream()
        val written = ImageIO.write(image, "JPEG2000", ImageIO.createImageOutputStream(bytes))
        assertTrue(written, "no JPEG 2000 writer is on the classpath")
        return bytes.toByteArray()
    }

    /** A PDF that opens without a password but is still encrypted, which the extractor must refuse. */
    private fun writeOwnerProtected(target: Path): Path {
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.protect(
                StandardProtectionPolicy("owner-only", "", AccessPermission()),
            )
            document.save(target.toFile())
        }
        return target
    }

    private fun writeEmpty(target: Path): Path {
        PDDocument().use { document -> document.save(target.toFile()) }
        return target
    }

    /**
     * One page with a long, usable text layer of its own and a declared rotation.
     *
     * Written by hand rather than added to the fixture generator: the committed fixtures are compared byte
     * for byte against what the generator writes, and a rotated page is a shape one test needs rather than a
     * document the rest of the suite is built around.
     */
    private fun writeRotated(target: Path, rotation: Int): Path {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            page.rotation = rotation
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                content.newLineAtOffset(72f, 760f)
                repeat(4) { line ->
                    content.showText("Protokollet sammanfattar överföringarna i ärendet, rad $line")
                    content.newLineAtOffset(0f, -18f)
                }
                content.endText()
            }
            document.save(target.toFile())
        }
        return target
    }

    /** A file that announces itself as a PDF and then has nothing a reader can follow. */
    private fun writeGarbage(target: Path): Path {
        Files.write(target, "%PDF-1.7\nthis is not a pdf\n".toByteArray(Charsets.ISO_8859_1))
        return target
    }
}

/**
 * The injected page-image engine, recording what it was asked to read.
 *
 * The seam exists so that the extractor's decisions can be observed without a Tesseract installation: the
 * tests assert which pages were handed over, at what resolution, and that the image was still on disk when
 * the call was made — none of which needs the tool itself. The page number its answers are keyed by is
 * derived the way a PDF page number is, from the ordinal the page image carries.
 */
private class OcrSpy(
    private val text: (Int) -> String = { page -> "läst sida $page" },
    private val meanConfidence: (Int) -> Double? = { null },
    private val artifact: (Int) -> Pair<String?, String?>? = { null },
    private val failOn: (Int) -> Boolean = { false },
    private val unavailable: String? = null,
    private val cancelOn: (Int) -> Boolean = { false },
    private val errorCode: String? = null,
    override val engine: OcrEngine = OcrEngine.TESSERACT,
) : PageOcrEngine {

    val pages: MutableList<PageImage> = mutableListOf()

    /** What the engine was told about each attempt, in the order it was asked. */
    val settings: MutableList<OcrSettingsSnapshot> = mutableListOf()

    /** The pages the engine was asked to read, by page number. */
    val readPages: List<Int> get() = pages.map { image -> image.ordinal + 1 }

    /** Whether the rendered image existed at the moment OCR was asked to read it. */
    val imagesPresent: MutableList<Boolean> = mutableListOf()

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        pages += page
        this.settings += settings
        imagesPresent += Files.isRegularFile(page.imagePath)
        val number = page.ordinal + 1
        if (cancelOn(number)) throw CancellationException("cancelled by the test")
        unavailable?.let { code -> throw OcrUnavailableException(code, "no OCR tool in this build") }
        if (failOn(number)) throw IOException("OCR could not read page $number")
        val written = artifact(number)
        return OcrPageResult(
            text = text(number),
            engine = engine,
            imageSha256 = page.sha256,
            meanConfidence = meanConfidence(number),
            artifactRelativePath = written?.first,
            artifactSha256 = written?.second,
            errorCode = errorCode,
        )
    }
}

/**
 * A text reader that cannot read one page.
 *
 * It delegates to the real reader for every other page, so the pages around the failure are read exactly
 * as the extractor reads them in production: what the test varies is the failure, not the reading.
 */
private class FailingPageTextReader(private val failOn: (Int) -> Boolean) : PdfPageTextReader {

    override fun read(document: PDDocument, page: Int): String {
        if (failOn(page)) throw IOException("the page's own text layer could not be read")
        return PdfBoxPageTextReader.read(document, page)
    }
}

/**
 * A renderer that records the pages it was asked for, delegating the work to the real one.
 *
 * Rendering is what a resume must not repeat, and the working directory it happens in is gone before a
 * test can look at it, so counting the calls is the only way to see that a committed page was skipped
 * before the expensive step rather than after it.
 */
private class CountingRenderer : PdfPageRenderer {

    val pages: MutableList<Int> = mutableListOf()

    override fun render(renderer: PDFRenderer, page: Int, dpi: Int, directory: Path): Path {
        pages += page
        return PngPageRenderer.render(renderer, page, dpi, directory)
    }
}
