package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.fixtures.OcrFixtureGenerator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Pictures: which ones are read, what a citation points at, and what happens when the reading cannot
 * happen.
 *
 * An image has no structure to parse, so everything about it is decided by the OCR path: whether a
 * committed picture is read again, whether a missing tool fails the document once instead of the unit,
 * whether the artifact is written where the unit says it is, and whether the locator names the picture a
 * person recognises rather than the managed copy this pipeline made.
 *
 * The tool is a script rather than the installed Tesseract, and the JPEG and TIFF are written by
 * ImageIO rather than committed: what these tests are about is this project's routing and bookkeeping,
 * and a fixture per encodable image format would be evidence about the format, not about the code.
 */
class ImageExtractorTest {

    private lateinit var directory: Path
    private lateinit var artifactRoot: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-image-extractor")
        artifactRoot = directory.resolve("artifacts")
        Files.createDirectories(artifactRoot)
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    // ---- The reading --------------------------------------------------------------------------------

    @Test
    fun `a picture is read through the ocr path and cited by the file it came from`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val tool = writeFakeExecutable(directory, "tesseract", "cat <<'TSV'\n$TSV\nTSV")
        val probe = PermitProbeBoundary()

        val input = inputFor(managed, probe, originalFilename = "handling-1986-04.png")
        val events = collect(ImageExtractor(TesseractOcr(executable = tool.toString())::recognize), input, probe)

        val unit = units(events).single()
        assertEquals(SourceLocation.Image("handling-1986-04.png"), unit.unit.locator)
        assertEquals("Rapporten är hemligstämplad", unit.unit.extractedText)
        assertEquals("Rapporten är hemligstämplad", unit.unit.searchText)
        assertEquals(0.94, unit.unit.meanConfidence)
        val artifact = artifactRoot.resolve(unit.unit.artifactRelativePath!!)
        assertTrue(Files.isRegularFile(artifact), "the unit names an artifact that is not there")
        assertTrue(artifact.startsWith(artifactRoot.resolve(fingerprint().value)))
        assertIs<ExtractionEvent.Finished>(events.last())
    }

    @Test
    fun `the collection's languages and the fingerprint reach the tool, and no resolution is claimed`() =
        runBlocking {
            val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
            val spy = OcrSpy()
            val probe = PermitProbeBoundary()

            collect(
                ImageExtractor(spy.seam),
                inputFor(managed, probe, languages = "swe+eng+nor"),
                probe,
            )

            val page = spy.pages.single()
            assertEquals("swe+eng+nor", page.ocrLanguages)
            assertEquals(fingerprint(), page.fingerprint)
            assertNull(page.renderDpi, "a picture was given a resolution this pipeline never chose")
            assertEquals(managed, page.imagePath)
        }

    @Test
    fun `the picture's text is what the tool read, and the unit is its only one`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy(text = "sidan ett", confidence = 0.5)
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(spy.seam), inputFor(managed, probe), probe)

        assertEquals(1, units(events).size)
        assertEquals(listOf(0), units(events).map { it.ordinal })
        assertEquals(listOf(ImageExtractor.UNIT_KEY), units(events).map { it.key })
        val metadata = (events.last() as ExtractionEvent.Finished).metadata
        assertEquals("1", metadata[ImageExtractor.IMAGES_METADATA])
        assertEquals("1", metadata[ImageExtractor.OCR_PAGES_METADATA])
        assertContains(metadata.getValue(ImageExtractor.OCR_MEAN_CONFIDENCE_METADATA), "0.5")
    }

    // ---- What a resumed attempt pays for ------------------------------------------------------------

    @Test
    fun `a committed picture is skipped before the tool is asked to read it`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(
            ImageExtractor(spy.seam),
            inputFor(managed, probe, committed = setOf(ImageExtractor.UNIT_KEY)),
            probe,
        )

        assertEquals(emptyList(), spy.pages.map { it.page }, "a committed picture was read again")
        assertEquals(emptyList(), units(events))
        assertEquals(1, (events.last() as ExtractionEvent.Finished).totalUnits)
    }

    @Test
    fun `the same picture produces the same key locator and ordinal twice`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val extractor = ImageExtractor(OcrSpy().seam)

        val first = units(collect(extractor, inputFor(managed, probe()), probe())).single()
        val second = units(collect(extractor, inputFor(managed, probe()), probe())).single()

        assertEquals(first.key, second.key)
        assertEquals(first.ordinal, second.ordinal)
        assertEquals(first.unit.locator, second.unit.locator)
        // The locator names the picture as it was published. A caller that knows the original name passes
        // it; a caller that does not falls back to the managed copy rather than to nothing at all.
        assertEquals(
            SourceLocation.Image("reports/1986-04-handling.png"),
            units(
                collect(
                    extractor,
                    inputFor(managed, probe(), originalFilename = "reports/1986-04-handling.png"),
                    probe(),
                ),
            ).single().unit.locator,
        )
    }

    // ---- A reading that cannot happen ----------------------------------------------------------------

    @Test
    fun `a missing tool fails the document once rather than the picture's unit`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy(unavailable = TesseractOcr.NEEDS_TESSERACT_CODE)
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(spy.seam), inputFor(managed, probe), probe)

        assertEquals(emptyList(), units(events))
        assertEquals(listOf(DOCUMENT_REFUSED_KEY), failures(events).map { it.key })
        assertEquals(
            listOf(TesseractOcr.NEEDS_TESSERACT_CODE),
            failures(events).map { it.code },
            "the thing to install has to be the thing that is named",
        )
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    @Test
    fun `a tool that failed on this picture fails its unit and leaves the document readable`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy(failWith = IOException("this reading failed"))
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(spy.seam), inputFor(managed, probe), probe)

        assertEquals(listOf(ImageExtractor.UNIT_KEY), failures(events).map { it.key })
        assertEquals(listOf(OCR_FAILED_CODE), failures(events).map { it.code })
        assertIs<ExtractionEvent.Finished>(events.last(), "a picture whose reading failed is not a document that failed")
    }

    @Test
    fun `a picture whose managed copy is gone is refused instead of throwing at the caller`() = runBlocking {
        val missing = directory.resolve("managed").resolve("gone.png")
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(spy.seam), inputFor(missing, probe), probe)

        assertEquals(listOf(DOCUMENT_REFUSED_KEY), failures(events).map { it.key })
        assertEquals(listOf(DOCUMENT_UNREADABLE_CODE), failures(events).map { it.code })
        assertEquals(emptyList(), spy.pages, "the tool was asked to read a file that is not there")
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    @Test
    fun `a picture past the byte bound is refused before the tool runs`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(
            ImageExtractor(spy.seam, maxImageBytes = 4),
            inputFor(managed, probe),
            probe,
        )

        assertEquals(listOf(DOCUMENT_TOO_LARGE_KEY), failures(events).map { it.key })
        assertEquals(listOf(DOCUMENT_TOO_LARGE_CODE), failures(events).map { it.code })
        assertEquals(emptyList(), spy.pages)
    }

    @Test
    fun `a refusal an earlier attempt committed is not reported twice`() = runBlocking {
        val missing = directory.resolve("managed").resolve("gone.png")
        val probe = PermitProbeBoundary()

        val events = collect(
            ImageExtractor(OcrSpy().seam),
            inputFor(missing, probe, committed = setOf(DOCUMENT_REFUSED_KEY)),
            probe,
        )

        assertEquals(emptyList(), events)
    }

    // ---- The boundary protocol -----------------------------------------------------------------------

    @Test
    fun `every event is emitted inside a unit permit, one permit per event`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(OcrSpy().seam), inputFor(managed, probe), probe)

        assertEquals(emptyList(), probe.eventsOutsidePermit)
        assertEquals(
            events.size,
            probe.permits,
            "the extractor took a permit it did not need, or emitted without one",
        )
    }

    // ---- Wiring and formats ------------------------------------------------------------------------

    @Test
    fun `a png a jpeg and a tiff are all detected from their bytes and routed to the image reader`() {
        val png = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val jpeg = writtenImage("photo.jpeg", "jpg")
        val tiff = writtenImage("scan.tiff", "tiff")

        val registry = ExtractorRegistry.production()
        listOf(png, jpeg, tiff).forEach { path ->
            val mediaType = MediaTypeDetector().detect(path).value
            assertTrue(
                mediaType in registry.claimedMediaTypes(),
                "the image reader does not claim $mediaType, which ${path.fileName} was detected as",
            )
            assertIs<ImageExtractor>(registry.select(mediaType))
        }
    }

    @Test
    fun `the production registry reads a picture with the tesseract tool`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val tool = writeFakeExecutable(directory, "tesseract", "cat <<'TSV'\n$TSV\nTSV")
        val mediaType = MediaTypeDetector().detect(managed).value
        val probe = PermitProbeBoundary()

        val events = ExtractorRegistry.production(TesseractOcr(executable = tool.toString()))
            .extract(inputFor(managed, probe), mediaType)
            .onEach { probe.observed(it) }
            .toList()

        val unit = units(events).single()
        assertEquals("Rapporten är hemligstämplad", unit.unit.extractedText)
        assertTrue(
            Files.isRegularFile(artifactRoot.resolve(unit.unit.artifactRelativePath!!)),
            "the production registry read the picture without writing the reading down",
        )
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private fun units(events: List<ExtractionEvent>): List<ExtractionEvent.UnitReady> =
        events.filterIsInstance<ExtractionEvent.UnitReady>()

    private fun failures(events: List<ExtractionEvent>): List<ExtractionEvent.UnitFailed> =
        events.filterIsInstance<ExtractionEvent.UnitFailed>()

    private fun collect(
        extractor: DocumentExtractor,
        input: ExtractionInput,
        probe: PermitProbeBoundary,
    ): List<ExtractionEvent> = runBlocking {
        extractor.extract(input).onEach { probe.observed(it) }.toList()
    }

    private fun probe(): PermitProbeBoundary = PermitProbeBoundary()

    /** Copies the committed image fixture into the managed area a real extraction would read from. */
    private fun managedImage(name: String): Path {
        val target = directory.resolve("managed").resolve("original.${name.substringAfterLast('.')}")
        Files.createDirectories(target.parent)
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "fixture /fixtures/$name is missing"
        }
        stream.use { Files.copy(it, target, REPLACE_EXISTING) }
        return target
    }

    /** Writes a real image of another format, so detection is exercised on the format's own bytes. */
    private fun writtenImage(name: String, format: String): Path {
        val target = directory.resolve("managed").resolve(name)
        Files.createDirectories(target.parent)
        val source = ImageIO.read(managedImage(OcrFixtureGenerator.IMAGE_NAME).toFile())
        check(ImageIO.write(source, format, target.toFile())) { "no $format writer is available" }
        source.flush()
        return target
    }

    private fun inputFor(
        source: Path,
        boundary: UnitBoundary,
        committed: Set<String> = emptySet(),
        languages: String = "swe+eng",
        originalFilename: String = source.fileName.toString(),
    ): ExtractionInput = ExtractionInput(
        documentId = DocumentId("doc-1"),
        managedPath = source,
        artifactRoot = artifactRoot,
        settings = ExtractionSettings(ocrLanguages = languages),
        fingerprint = fingerprint(),
        committedUnitKeys = committed,
        boundary = boundary,
        originalFilename = originalFilename,
    )

    private fun fingerprint(): ExtractionFingerprint =
        ExtractionFingerprint.of("d".repeat(64), ExtractionSettings(ocrLanguages = "swe+eng"))

    /** The injected OCR seam, recording what it was asked to read. */
    private class OcrSpy(
        private val text: String = "Rapporten är hemligstämplad",
        private val confidence: Double? = 0.94,
        private val unavailable: String? = null,
        private val failWith: IOException? = null,
    ) {

        val pages: MutableList<RenderedPage> = mutableListOf()

        val seam: suspend (RenderedPage) -> OcrResult = { page ->
            pages += page
            unavailable?.let { code -> throw OcrUnavailableException(code, "no OCR tool in this build") }
            failWith?.let { failure -> throw failure }
            OcrResult(text = text, meanConfidence = confidence)
        }
    }

    private companion object {

        /** The reading the fake tool reports: one line, two words, a confidence per word. */
        val TSV: String = listOf(
            "1\t1\t0\t0\t0\t0\t0\t0\t900\t240\t-1\t",
            "5\t1\t1\t1\t1\t1\t44\t52\t214\t41\t94\tRapporten",
            "5\t1\t1\t1\t1\t2\t277\t52\t39\t33\t94\tär",
            "5\t1\t1\t1\t1\t3\t335\t50\t348\t43\t94\themligstämplad",
        ).joinToString("\n")
    }
}
