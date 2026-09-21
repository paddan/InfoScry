package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.fixtures.PdfFixtureGenerator
import infoscry.fixtures.PdfFixtureGenerator.MIXED_NAME
import infoscry.fixtures.PdfFixtureGenerator.MIXED_SCANNED_PAGES
import infoscry.fixtures.PdfFixtureGenerator.MIXED_TEXT_PAGES
import infoscry.fixtures.PdfFixtureGenerator.MIXED_UNRENDERABLE_PAGE
import infoscry.fixtures.PdfFixtureGenerator.PROTECTED_NAME
import infoscry.fixtures.PdfFixtureGenerator.TEXT_NAME
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
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

        val events = collect(PdfExtractor(spy.seam), inputFor(fixture(TEXT_NAME), probe), probe)
        val units = units(events)

        assertEquals(listOf(1, 2, 3), units.map { (it.unit.locator as SourceLocation.PdfPage).page })
        assertEquals(listOf(0, 1, 2), units.map { it.ordinal })
        assertEquals(listOf("page:1", "page:2", "page:3"), units.map { it.key })
        assertContains(units.first().unit.extractedText, "Protokollet")
        assertEquals(3, (events.last() as ExtractionEvent.Finished).totalUnits)
        assertTrue(spy.pages.isEmpty(), "a page with its own text was rendered for OCR")
    }

    // ---- Pages that have to be read by OCR --------------------------------------------------------

    @Test
    fun `only the pages whose text layer is empty are rendered, at the default resolution`() {
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        collect(PdfExtractor(spy.seam), inputFor(fixture(MIXED_NAME), probe), probe)

        assertEquals(MIXED_SCANNED_PAGES, spy.pages.map { it.page })
        assertTrue(spy.pages.all { it.renderDpi == PdfExtractor.DEFAULT_RENDER_DPI })
        assertTrue(spy.imagesPresent.all { it }, "ocr was asked to read an image that was not there")
        assertTrue(spy.pages.all { it.ocrLanguages == "eng" })
        assertTrue(spy.pages.all { it.documentId == DocumentId("doc-1") })
    }

    @Test
    fun `the text ocr read becomes the unit text for that page`() {
        val spy = OcrSpy(text = { page -> "läst sida $page" })

        val units = units(collect(PdfExtractor(spy.seam), inputFor(fixture(MIXED_NAME), probe()), probe()))
        val perPage = units.associate { (it.unit.locator as SourceLocation.PdfPage).page to it.unit.extractedText }

        assertEquals("läst sida 3", perPage[3])
        assertEquals("läst sida 4", perPage[4])
        assertEquals("läst sida 5", perPage[5])
        assertContains(perPage.getValue(1), "Protokollet")
    }

    @Test
    fun `the rendered images are gone once the attempt ends`() {
        val spy = OcrSpy()

        collect(PdfExtractor(spy.seam), inputFor(fixture(MIXED_NAME), probe()), probe())

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

        collect(PdfExtractor(spy.seam), input, probe())

        assertEquals(MIXED_SCANNED_PAGES, spy.pages.map { it.page })
        assertTrue(spy.pages.all { it.renderDpi == 150 }, "the resolution in the job's settings was ignored")
    }

    @Test
    fun `a page is rendered at the highest resolution that fits the pixel bound`() {
        val spy = OcrSpy()
        val extractor = PdfExtractor(spy.seam, maxRenderedPixels = 2_500_000L)

        collect(extractor, inputFor(fixture(MIXED_NAME), probe()), probe())

        assertEquals(MIXED_SCANNED_PAGES, spy.pages.map { it.page })
        assertTrue(
            spy.pages.all { it.renderDpi in PdfExtractor.MIN_RENDER_DPI until PdfExtractor.DEFAULT_RENDER_DPI },
            "a page that cannot be rendered at the default resolution was not brought within the bound: " +
                spy.pages.map { it.renderDpi },
        )
    }

    @Test
    fun `a page the pixel bound cannot hold at all fails instead of being allocated`() {
        val spy = OcrSpy()
        val extractor = PdfExtractor(spy.seam, maxRenderedPixels = 100_000L)

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

    // ---- The OCR reading travels with its page -------------------------------------------------------

    @Test
    fun `an ocred page's unit carries its word boxes and the confidence ocr reported`() {
        val spy = OcrSpy(
            text = { page -> "läst sida $page" },
            meanConfidence = { 88.5 },
            artifact = { page -> "ocr/page-$page.tsv.gz" to "c".repeat(64) },
        )

        val units = units(collect(PdfExtractor(spy.seam), inputFor(fixture(MIXED_NAME), probe()), probe()))
        val ocred = units.single { pageOf(it.key) == 3 }
        val parsed = units.single { pageOf(it.key) == 1 }

        assertEquals("ocr/page-3.tsv.gz", ocred.unit.artifactRelativePath)
        assertEquals("c".repeat(64), ocred.unit.artifactSha256)
        assertEquals(88.5, ocred.unit.meanConfidence)
        assertEquals(
            null,
            parsed.unit.artifactRelativePath,
            "a page with its own text layer claimed an artifact it never wrote",
        )
        assertEquals(null, parsed.unit.meanConfidence)
    }

    // ---- A page whose own text cannot be read is handed over, not failed ------------------------------

    @Test
    fun `a page whose own text cannot be read is handed to ocr instead of failing`() {
        // Page 1 has a usable text layer, so it would be cited from its own text if the read succeeded.
        // PDFBox is deliberately forgiving about damaged content and cannot be made to produce this
        // failure from a fixture, which is why the reader is the seam that makes it reachable at all.
        val spy = OcrSpy()

        val events = collect(
            PdfExtractor(spy.seam, pageText = FailingPageTextReader(failOn = { page -> page == 1 })),
            inputFor(fixture(MIXED_NAME), probe()),
            probe(),
        )

        assertEquals(
            listOf(1) + MIXED_SCANNED_PAGES,
            spy.pages.map { it.page },
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
            PdfExtractor(
                OcrSpy().seam,
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
        // A directory where the renderer was asked to put an image: it exists, so the delete is attempted,
        // and it holds a file, so the delete fails the way a real one would.
        val renderer = PdfPageRenderer { _, page, _, where ->
            val stubborn = where.resolve("page-$page.png")
            Files.createDirectory(stubborn)
            Files.write(stubborn.resolve("still-here.txt"), "not empty".toByteArray())
            stubborn
        }

        val events = collect(
            PdfExtractor(OcrSpy().seam, pageRenderer = renderer),
            inputFor(fixture(MIXED_NAME), probe()),
            probe(),
        )

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

    // ---- One page that cannot be produced does not take the document with it -------------------------

    @Test
    fun `a page that cannot be rendered fails as a unit while every other page still delivers`() {
        val events = collect(PdfExtractor(OcrSpy().seam), inputFor(fixture(MIXED_NAME), probe()), probe())

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

        val events = collect(PdfExtractor(spy.seam), inputFor(fixture(MIXED_NAME), probe()), probe())

        val ocrFailures = failures(events).filter { it.code == OCR_FAILED_CODE }
        assertEquals(listOf("page:4"), ocrFailures.map { it.key })
        assertEquals(
            MIXED_SCANNED_PAGES,
            spy.pages.map { it.page },
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

        val events = collect(PdfExtractor(spy.seam), inputFor(fixture(MIXED_NAME), probe()), probe())

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
        assertEquals(MIXED_SCANNED_PAGES.take(1), spy.pages.map { it.page })
    }

    // ---- Documents that cannot be read at all -------------------------------------------------------

    @Test
    fun `a password protected pdf is refused as encrypted`() {
        val spy = OcrSpy()

        val events = collect(PdfExtractor(spy.seam), inputFor(fixture(PROTECTED_NAME), probe()), probe())

        assertEquals(listOf(ENCRYPTED_DOCUMENT_CODE), failures(events).map { it.code })
        assertEquals(listOf(PdfExtractor.DOCUMENT_KEY), failures(events).map { it.key })
        assertTrue(events.none { it is ExtractionEvent.Finished })
        assertTrue(units(events).isEmpty())
        assertTrue(spy.pages.isEmpty())
    }

    @Test
    fun `a pdf that opens with an owner password only is still refused`() {
        val path = writeOwnerProtected(directory.resolve("owner-protected.pdf"))

        val events = collect(PdfExtractor(OcrSpy().seam), inputFor(path, probe()), probe())

        assertEquals(listOf(ENCRYPTED_DOCUMENT_CODE), failures(events).map { it.code })
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    @Test
    fun `a container past the size bound is refused before it is opened`() {
        val extractor = PdfExtractor(OcrSpy().seam, maxDocumentBytes = 1L)

        val events = collect(extractor, inputFor(fixture(TEXT_NAME), probe()), probe())

        assertEquals(listOf(DOCUMENT_TOO_LARGE_CODE), failures(events).map { it.code })
        assertEquals(listOf(DOCUMENT_TOO_LARGE_KEY), failures(events).map { it.key })
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    @Test
    fun `a refusal that is already committed is not reported a second time`() {
        val extractor = PdfExtractor(OcrSpy().seam, maxDocumentBytes = 1L)
        val input = inputFor(fixture(TEXT_NAME), probe(), committed = setOf(DOCUMENT_TOO_LARGE_KEY))

        assertEquals(emptyList(), collect(extractor, input, probe()))
    }

    @Test
    fun `an encrypted refusal that is already committed is not reported a second time`() {
        val input = inputFor(fixture(PROTECTED_NAME), probe(), committed = setOf(PdfExtractor.DOCUMENT_KEY))

        assertEquals(emptyList(), collect(PdfExtractor(OcrSpy().seam), input, probe()))
    }

    @Test
    fun `an empty document finishes with no units and asks for nothing`() {
        val spy = OcrSpy()
        val path = writeEmpty(directory.resolve("empty.pdf"))

        val events = collect(PdfExtractor(spy.seam), inputFor(path, probe()), probe())

        assertEquals(emptyList(), units(events))
        assertEquals(0, (events.single() as ExtractionEvent.Finished).totalUnits)
        assertTrue(spy.pages.isEmpty())
    }

    @Test
    fun `a container that is not a readable pdf is refused instead of thrown`() {
        val path = writeGarbage(directory.resolve("garbage.pdf"))

        val events = collect(PdfExtractor(OcrSpy().seam), inputFor(path, probe()), probe())

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
            PdfExtractor(spy.seam),
            inputFor(fixture(MIXED_NAME), probe(), committed = committed),
            probe(),
        )

        assertEquals(MIXED_SCANNED_PAGES.drop(1), spy.pages.map { it.page })
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
                PdfExtractor(spy.seam),
                inputFor(fixture(MIXED_NAME), probe(), committed = committed),
                probe(),
            )
        }

        assertEquals(listOf(4, 5), spy.pages.map { it.page })
        assertTrue(spy.pages.none { it.page <= 3 }, "a page that was already committed was rendered again")
    }

    @Test
    fun `a resumed attempt never renders a page it was told is finished`() {
        // Page 3 is a scanned page, which is exactly the case the skip has to cover: without the resume
        // check it would be rendered again, so a counting renderer tells "skipped before rendering" apart
        // from "skipped before OCR".
        val committed = setOf("page:1", "page:2", "page:3")
        val renderer = CountingRenderer()

        collect(
            PdfExtractor(OcrSpy().seam, pageRenderer = renderer),
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
                PdfExtractor(OcrSpy().seam, pageRenderer = renderer),
                inputFor(fixture(MIXED_NAME), probe()),
                probe(),
            )
        }

        assertContains(failure.message.orEmpty(), "a bug in the extractor")
    }

    @Test
    fun `a managed file that is gone is refused instead of throwing at the caller`() {
        val missing = directory.resolve("managed").resolve("gone.pdf")

        val events = collect(PdfExtractor(OcrSpy().seam), inputFor(missing, probe()), probe())

        assertEquals(listOf(PdfExtractor.DOCUMENT_KEY), failures(events).map { it.key })
        assertEquals(listOf(DOCUMENT_UNREADABLE_CODE), failures(events).map { it.code })
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    // ---- The boundary protocol ----------------------------------------------------------------------

    @Test
    fun `every event is emitted inside a unit permit, one permit per event`() {
        val probe = PermitProbeBoundary()

        val events = collect(PdfExtractor(OcrSpy().seam), inputFor(fixture(MIXED_NAME), probe), probe)

        assertEquals(emptyList(), probe.eventsOutsidePermit)
        assertEquals(
            events.size,
            probe.permits,
            "the extractor took a permit it did not need, or emitted without one",
        )
    }

    @Test
    fun `the extractor produces the same keys locators and ordinals twice`() {
        val first = units(collect(PdfExtractor(OcrSpy().seam), inputFor(fixture(MIXED_NAME), probe()), probe()))
        val second = units(collect(PdfExtractor(OcrSpy().seam), inputFor(fixture(MIXED_NAME), probe()), probe()))

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
        val extractor = PdfExtractor(OcrSpy().seam)

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

    /** A file that announces itself as a PDF and then has nothing a reader can follow. */
    private fun writeGarbage(target: Path): Path {
        Files.write(target, "%PDF-1.7\nthis is not a pdf\n".toByteArray(Charsets.ISO_8859_1))
        return target
    }
}

/**
 * The injected OCR seam, recording what it was asked to read.
 *
 * The seam exists so that the extractor's decisions can be observed without a Tesseract installation: the
 * tests assert which pages were handed over, at what resolution, and that the image was still on disk when
 * the call was made — none of which needs the tool itself.
 */
private class OcrSpy(
    private val text: (Int) -> String = { page -> "läst sida $page" },
    private val meanConfidence: (Int) -> Double? = { null },
    private val artifact: (Int) -> Pair<String?, String?>? = { null },
    private val failOn: (Int) -> Boolean = { false },
    private val unavailable: String? = null,
    private val cancelOn: (Int) -> Boolean = { false },
) {

    val pages: MutableList<RenderedPage> = mutableListOf()

    /** Whether the rendered image existed at the moment OCR was asked to read it. */
    val imagesPresent: MutableList<Boolean> = mutableListOf()

    /** The seam, ready to hand to the extractor. */
    val seam: suspend (RenderedPage) -> OcrResult = { page -> recognize(page) }

    private suspend fun recognize(page: RenderedPage): OcrResult {
        pages += page
        imagesPresent += Files.isRegularFile(page.imagePath)
        if (cancelOn(page.page)) throw CancellationException("cancelled by the test")
        unavailable?.let { code -> throw OcrUnavailableException(code, "no OCR tool in this build") }
        if (failOn(page.page)) throw IOException("OCR could not read page ${page.page}")
        val written = artifact(page.page)
        return OcrResult(
            text = text(page.page),
            meanConfidence = meanConfidence(page.page),
            artifactRelativePath = written?.first,
            artifactSha256 = written?.second,
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
