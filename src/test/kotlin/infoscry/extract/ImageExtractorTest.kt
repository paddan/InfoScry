package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.fixtures.OcrFixtureGenerator
import infoscry.ocr.OCR_TRANSCRIPTION_PROMPT_VERSION
import infoscry.ocr.OcrAttemptIdentity
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.PageOcrEngines
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
        val events = collect(ImageExtractor(PageOcrEngines(listOf(TesseractOcr(executable = tool.toString())))), input, probe)

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
            val settings = ExtractionSettings(ocrLanguages = "swe+eng+nor")

            collect(
                ImageExtractor(PageOcrEngines(listOf(spy))),
                inputFor(managed, probe, settings = settings),
                probe,
            )

            val image = spy.pages.single()
            assertEquals("swe+eng+nor", spy.settings.single().language)
            assertEquals(
                artifactRoot.resolve(ExtractionFingerprint.of("d".repeat(64), settings).value),
                image.artifactRoot,
                "the reading was written under another attempt's artifacts",
            )
            assertNull(image.renderDpi, "a picture was given a resolution this pipeline never chose")
            assertEquals(managed, image.imagePath)
            assertEquals(sha256Of(image.imagePath), image.sha256, "the picture's hash is not its own bytes")
        }

    @Test
    fun `a picture read as itself names the managed copy it was read from`() = runBlocking {
        // A picture that fits the bound a tool is handed *is* the document, so the reading has to name the
        // managed copy: the hash is the bytes of that copy, the dimensions are its own, and the render
        // version says this pipeline reduced nothing to read it.
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val probe = PermitProbeBoundary()

        val unit = units(collect(ImageExtractor(PageOcrEngines(listOf(OcrSpy()))), inputFor(managed, probe), probe))
            .single()
            .unit

        val source = assertNotNull(unit.sourceImage, "a picture that was read named no image it was read from")
        val declared = assertNotNull(ImageIO.read(managed.toFile()))
        assertEquals(SourceImageRoot.MANAGED_COPY, source.root)
        assertEquals(
            managed.fileName.toString(),
            source.relativePath,
            "the reference is not the managed copy's own name inside its directory",
        )
        assertEquals(sha256Of(managed), source.sha256, "the reading names a hash that is not the copy's bytes")
        assertEquals(declared.width, source.width)
        assertEquals(declared.height, source.height)
        assertEquals(PageImage.RENDER_VERSION, source.renderVersion)
    }

    @Test
    fun `a picture's recorded reference resolves inside the root it names and is never an absolute path`() =
        runBlocking {
            // An as-is picture names the directory holding the managed copy, a reduced copy names the
            // document's artifact root; neither may be an absolute path a reviewer could see, and each has to
            // resolve to a file that exists inside its own root after the attempt.
            val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
            val probe = PermitProbeBoundary()
            val asIs = units(collect(ImageExtractor(PageOcrEngines(listOf(OcrSpy()))), inputFor(managed, probe), probe))
                .single().unit.sourceImage
            val oversized = oversizedPaper("oversized.png")
            val reducedProbe = PermitProbeBoundary()
            val reduced = units(
                collect(
                    ImageExtractor(PageOcrEngines(listOf(OcrSpy()))),
                    inputFor(oversized, reducedProbe),
                    reducedProbe,
                ),
            ).single().unit.sourceImage

            val asIsSource = assertNotNull(asIs)
            val reducedSource = assertNotNull(reduced)
            assertEquals(SourceImageRoot.MANAGED_COPY, asIsSource.root)
            assertEquals(SourceImageRoot.ARTIFACTS, reducedSource.root)
            listOf(
                asIsSource to managed.toAbsolutePath().normalize().parent,
                reducedSource to artifactRoot.toAbsolutePath().normalize(),
            ).forEach { (source, root) ->
                assertFalse(Path.of(source.relativePath).isAbsolute, "'${source.relativePath}' is an absolute path")
                assertFalse(
                    Path.of(source.relativePath).any { segment -> segment.toString() == ".." },
                    "'${source.relativePath}' climbs out of its root",
                )
                val resolved = root.resolve(source.relativePath).normalize()
                assertTrue(resolved.startsWith(root) && resolved != root, "$resolved is outside $root")
                assertTrue(Files.isRegularFile(resolved), "$resolved is named by the reading and is not there")
                assertEquals(sha256Of(resolved), source.sha256)
            }
            assertTrue(
                !asIsSource.relativePath.contains('/'),
                "the managed copy is named by its own name inside its directory, was ${asIsSource.relativePath}",
            )
        }

    @Test
    fun `the picture's text is what the tool read, and the unit is its only one`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy(text = "sidan ett", confidence = 0.5)
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(managed, probe), probe)

        assertEquals(1, units(events).size)
        assertEquals(listOf(0), units(events).map { it.ordinal })
        assertEquals(listOf(ImageExtractor.UNIT_KEY), units(events).map { it.key })
        val metadata = (events.last() as ExtractionEvent.Finished).metadata
        assertEquals("1", metadata[ImageExtractor.IMAGES_METADATA])
        assertEquals("1", metadata[ImageExtractor.OCR_PAGES_METADATA])
        assertContains(metadata.getValue(ImageExtractor.OCR_MEAN_CONFIDENCE_METADATA), "0.5")
    }

    @Test
    fun `a picture reports its ocr reading and no direct text beside it`() = runBlocking {
        // A picture has no text layer to fill in or to check, so there is no second reading of this page to
        // hand over: both import modes read it with the selected engine, and the engine's reading is the
        // only one there is.
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val probe = PermitProbeBoundary()

        val unit = units(
            collect(ImageExtractor(PageOcrEngines(listOf(OcrSpy()))), inputFor(managed, probe), probe),
        ).single().unit

        assertEquals(ExtractionMethod.OCR, unit.method)
        assertEquals("Rapporten är hemligstämplad", unit.extractedText)
        assertNull(unit.directText, "a picture was given a text layer it has not got")
    }

    // ---- What a resumed attempt pays for ------------------------------------------------------------

    @Test
    fun `a committed picture is skipped before the tool is asked to read it`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(
            ImageExtractor(PageOcrEngines(listOf(spy))),
            inputFor(managed, probe, committed = setOf(ImageExtractor.UNIT_KEY)),
            probe,
        )

        assertTrue(spy.pages.isEmpty(), "a committed picture was read again")
        assertEquals(emptyList(), units(events))
        assertEquals(1, (events.last() as ExtractionEvent.Finished).totalUnits)
    }

    @Test
    fun `the same picture produces the same key locator and ordinal twice`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val extractor = ImageExtractor(PageOcrEngines(listOf(OcrSpy())))

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

    @Test
    fun `an engine this build does not have is refused rather than reading the picture with another one`() =
        runBlocking {
            val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
            val spy = OcrSpy()
            val probe = PermitProbeBoundary()
            // The picture is read by an attempt that selects Surya; this build has Tesseract.
            val settings = ExtractionSettings(
                ocrLanguages = "swe+eng",
                ocrMode = OcrImportMode.CHECK_AND_IMPROVE,
                ocrAttempt = OcrAttemptIdentity(
                    engine = OcrEngine.SURYA,
                    language = "swe+eng",
                    transcriptionPromptVersion = OCR_TRANSCRIPTION_PROMPT_VERSION,
                    extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
                ),
            )

            val events = collect(
                ImageExtractor(PageOcrEngines(listOf(spy))),
                inputFor(managed, probe, settings = settings),
                probe,
            )

            assertEquals(emptyList(), units(events))
            assertEquals(listOf(DOCUMENT_REFUSED_KEY), failures(events).map { it.key })
            assertEquals(listOf("NEEDS_SURYA"), failures(events).map { it.code })
            assertTrue(spy.pages.isEmpty(), "the installed tool read a picture that selects another engine")
            assertTrue(events.none { it is ExtractionEvent.Finished })
        }

    @Test
    fun `a picture that is blank paper is reported as a blank page rather than an empty reading`() = runBlocking {
        // White paper and an engine that read nothing: the raster is the only thing that can say whether the
        // page is blank or was missed, and it says blank. Committing that as an ordinary empty reading would
        // record a picture with no text as though the tool had read it and found nothing to say.
        val blank = directory.resolve("managed").resolve("blank.png")
        Files.createDirectories(blank.parent)
        val paper = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        val graphics = paper.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, paper.width, paper.height)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(paper, "png", blank.toFile())

        val probe = PermitProbeBoundary()
        val events = collect(
            ImageExtractor(PageOcrEngines(listOf(OcrSpy(text = "", confidence = null)))),
            inputFor(blank, probe),
            probe,
        )

        assertEquals(emptyList(), units(events), "blank paper was committed as a reading of the picture")
        assertEquals(listOf(ImageExtractor.UNIT_KEY), failures(events).map { it.key })
        assertEquals(listOf(PAGE_BLANK_CODE), failures(events).map { it.code })
    }

    @Test
    fun `a picture past the bound is reduced to a bounded raster and then verified against it`() = runBlocking {
        // Sixteen million eight hundred thousand pixels, declared by a few kilobytes of PNG: past the bound
        // this process hands to a tool, and read from the header before anything is decoded. So the picture
        // is reduced to a copy within the bound, that copy is what the engine reads, and that copy is what
        // the blankness question is asked about — white paper stays white paper when it is subsampled, and an
        // empty reading of white paper is a blank page rather than an empty reading committed as this
        // picture's text. The page image says it came from a reduced copy, so the verdict is never attributed
        // to the original pixels.
        val oversized = oversizedPaper("oversized.png")
        val spy = OcrSpy(text = "", confidence = null)
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(oversized, probe), probe)

        assertEquals(emptyList(), units(events), "blank paper was committed as a reading of the picture")
        assertEquals(listOf(ImageExtractor.UNIT_KEY), failures(events).map { it.key })
        assertEquals(listOf(PAGE_BLANK_CODE), failures(events).map { it.code })
        assertEquals(
            PageImage.REDUCED_RENDER_VERSION,
            spy.pages.single().renderVersion,
            "the blankness verdict is about the original picture rather than the copy that was read",
        )
    }

    @Test
    fun `a picture read from a bounded copy names the copy rather than the pixels it was reduced from`() =
        runBlocking {
            // The bounded copy is a different image from the managed picture: it holds a subsampled raster
            // rather than the declared one. A durable reading of it therefore names the copy — its
            // reference, its own bytes and its own dimensions — so a later comparison, or a person shown
            // the page, is never told the text came from the original pixels.
            val oversized = oversizedPaper("oversized.png")
            val spy = OcrSpy(text = "läst från en reducerad kopia")
            val probe = PermitProbeBoundary()

            val unit = units(collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(oversized, probe), probe))
                .single()
                .unit
            val copy = spy.pages.single()

            val source = assertNotNull(unit.sourceImage, "a reduced picture named no image it was read from")
            assertEquals(SourceImageRoot.ARTIFACTS, source.root, "a bounded copy is this attempt's own artifact")
            assertEquals(
                copy.imagePath,
                artifactRoot.resolve(source.relativePath),
                "the reading names a file that is not the copy the tool was handed",
            )
            assertEquals(sha256Of(copy.imagePath), source.sha256)
            assertNotEquals(
                sha256Of(oversized),
                source.sha256,
                "a reading of the bounded copy was attributed to the managed picture's bytes",
            )
            assertEquals(copy.width, source.width)
            assertEquals(copy.height, source.height)
            assertTrue(
                assertNotNull(source.width) < OVER_THE_SCAN_BOUND_SIDE,
                "the record names the original's dimensions rather than the bounded copy's: ${source.width}",
            )
            assertEquals(
                PageImage.REDUCED_RENDER_VERSION,
                source.renderVersion,
                "the reading does not say a reduction produced the pixels it was made from",
            )
        }

    @Test
    fun `an extreme declared frame is bounded without integer overflow`() {
        val extractor = ImageExtractor(PageOcrEngines(listOf(OcrSpy())))

        // Int.MAX_VALUE + step - 1 overflows in Int arithmetic, and the overflowing value is negative — which
        // the step search reads as "this step already fits", so it would stop at two and ask the reader for a
        // billion-pixel raster before the bound was consulted. This arithmetic is what stands between a
        // declared size and an allocation, so it may never under-report one.
        assertTrue(
            extractor.subsampledPixels(Int.MAX_VALUE, 1, 2) > PdfExtractor.MAX_RENDERED_PIXELS,
            "a step of two does not bring an extreme width within the bound",
        )
        val step = extractor.subsampleStep(Int.MAX_VALUE, 1)
        assertTrue(step > 2, "an extreme width must not be answered with an overflowing step, was $step")
        assertTrue(
            extractor.subsampledPixels(Int.MAX_VALUE, 1, step) <= PdfExtractor.MAX_RENDERED_PIXELS,
            "the chosen step has to bring the declared frame within the bound, was $step",
        )
    }

    @Test
    fun `a picture whose reading is empty but which carries ink fails its unit rather than committing it`() =
        runBlocking {
            val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
            val probe = PermitProbeBoundary()

            val events = collect(
                ImageExtractor(
                    PageOcrEngines(
                        listOf(
                            OcrSpy(
                                text = "",
                                confidence = null,
                                errorCode = OcrPageResult.EMPTY_READING_CODE,
                            ),
                        ),
                    ),
                ),
                inputFor(managed, probe),
                probe,
            )

            assertEquals(
                emptyList(),
                units(events),
                "an empty reading of a picture that carries ink was committed as the picture's text",
            )
            assertEquals(
                listOf(ImageExtractor.UNIT_KEY to OcrPageResult.EMPTY_READING_CODE),
                failures(events).map { failure -> failure.key to failure.code },
                "the picture's unit was not failed under the code an engine answers an empty reading with",
            )
        }

    // ---- A reading that cannot happen ----------------------------------------------------------------

    @Test
    fun `an empty reading that names no code is still failed as an empty reading`() = runBlocking {
        // The engine contract asks an empty reading to carry `OCR_EMPTY`, and a consumer that trusted the
        // code to be there would commit a page of nothing as the picture's text if an engine left it out.
        // The raster carries ink, so this is not a blank page either: it is an empty reading, whichever way
        // the engine spelled it.
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val probe = PermitProbeBoundary()

        val events = collect(
            ImageExtractor(
                PageOcrEngines(listOf(OcrSpy(text = "", confidence = null))),
            ),
            inputFor(managed, probe),
            probe,
        )

        assertEquals(
            emptyList(),
            units(events),
            "an empty reading that named no code was committed as the picture's text",
        )
        assertEquals(
            listOf(ImageExtractor.UNIT_KEY to OcrPageResult.EMPTY_READING_CODE),
            failures(events).map { failure -> failure.key to failure.code },
            "an empty reading without a code was not failed as an empty reading",
        )
    }

    @Test
    fun `a missing tool fails the document once rather than the picture's unit`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy(unavailable = TesseractOcr.NEEDS_TESSERACT_CODE)
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(managed, probe), probe)

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

        val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(managed, probe), probe)

        assertEquals(listOf(ImageExtractor.UNIT_KEY), failures(events).map { it.key })
        assertEquals(listOf(OCR_FAILED_CODE), failures(events).map { it.code })
        assertIs<ExtractionEvent.Finished>(events.last(), "a picture whose reading failed is not a document that failed")
    }

    @Test
    fun `a picture whose managed copy is gone is refused instead of throwing at the caller`() = runBlocking {
        val missing = directory.resolve("managed").resolve("gone.png")
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(missing, probe), probe)

        assertEquals(listOf(DOCUMENT_REFUSED_KEY), failures(events).map { it.key })
        assertEquals(listOf(DOCUMENT_UNREADABLE_CODE), failures(events).map { it.code })
        assertTrue(spy.pages.isEmpty(), "the tool was asked to read a file that is not there")
        assertTrue(events.none { it is ExtractionEvent.Finished })
    }

    @Test
    fun `a picture past the byte bound is refused before the tool runs`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(
            ImageExtractor(PageOcrEngines(listOf(spy)), maxImageBytes = 4),
            inputFor(managed, probe),
            probe,
        )

        assertEquals(listOf(DOCUMENT_TOO_LARGE_KEY), failures(events).map { it.key })
        assertEquals(listOf(DOCUMENT_TOO_LARGE_CODE), failures(events).map { it.code })
        assertTrue(spy.pages.isEmpty())
    }

    @Test
    fun `a bounded copy of a picture is what the engine reads and what the unit cites`() = runBlocking {
        // The reduction is not allowed to lose the picture: the reading is committed against the copy that
        // was written, and the record about that copy is about the file itself — its dimensions and hash are
        // read back from what is on disk, it lives under the attempt's own artifacts rather than beside the
        // immutable managed copy, and it is a file, not a plan.
        val oversized = oversizedPaper("oversized.png")
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(oversized, probe), probe)

        val image = spy.pages.single()
        val width = assertNotNull(image.width)
        val height = assertNotNull(image.height)
        assertTrue(
            width.toLong() * height <= PdfExtractor.MAX_RENDERED_PIXELS,
            "the engine was handed a $width by $height raster, which is past the bound",
        )
        assertTrue(
            width < OVER_THE_SCAN_BOUND_SIDE,
            "the copy is as wide as the picture, so nothing was reduced: $width",
        )
        assertNotEquals(oversized, image.imagePath, "the engine was handed the unbounded managed copy")
        assertTrue(
            image.imagePath.startsWith(artifactRoot),
            "the bounded copy was written outside the attempt's own artifacts: ${image.imagePath}",
        )
        assertTrue(Files.isRegularFile(image.imagePath), "the record names a copy that is not there")
        assertEquals(sha256Of(image.imagePath), image.sha256, "the record's hash is not the copy's own bytes")
        assertNotEquals(sha256Of(oversized), image.sha256, "the bounded copy is the managed picture itself")
        assertEquals(
            PageImage.REDUCED_RENDER_VERSION,
            image.renderVersion,
            "the reading does not say it came from a reduced copy of the picture",
        )
        assertEquals("Rapporten är hemligstämplad", units(events).single().unit.extractedText)
    }

    @Test
    fun `a picture whose declared raster cannot be decoded is refused rather than handed to the tool`() =
        runBlocking {
            // A header that declares more pixels than this process decodes, with no pixels behind it. The
            // picture cannot be read, so it cannot be reduced, and the one thing that must not happen is
            // handing the declared raster to a tool. The page is refused with the reason instead.
            val managed = truncatedPicture("broken.png")
            val spy = OcrSpy()
            val probe = PermitProbeBoundary()

            val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(managed, probe), probe)

            assertEquals(emptyList(), units(events))
            assertEquals(listOf(ImageExtractor.UNIT_KEY), failures(events).map { it.key })
            assertEquals(listOf(ImageExtractor.PAGE_RASTER_UNBOUNDED_CODE), failures(events).map { it.code })
            assertTrue(spy.pages.isEmpty(), "the tool was asked to read a picture this process could not bound")
        }

    @Test
    fun `a picture no reader claims is refused rather than handed to the tool`() = runBlocking {
        val managed = directory.resolve("managed").resolve("nothing.png")
        Files.createDirectories(managed.parent)
        Files.writeString(managed, "the bytes of no image format at all")
        val spy = OcrSpy()
        val probe = PermitProbeBoundary()

        val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(managed, probe), probe)

        assertEquals(emptyList(), units(events))
        assertEquals(listOf(ImageExtractor.UNIT_KEY), failures(events).map { it.key })
        assertEquals(listOf(ImageExtractor.PAGE_RASTER_UNBOUNDED_CODE), failures(events).map { it.code })
        assertTrue(spy.pages.isEmpty(), "the tool was asked to read a picture nobody could measure")
    }

    @Test
    fun `a refusal an earlier attempt committed is not reported twice`() = runBlocking {
        val missing = directory.resolve("managed").resolve("gone.png")
        val probe = PermitProbeBoundary()

        val events = collect(
            ImageExtractor(PageOcrEngines(listOf(OcrSpy()))),
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

        val events = collect(ImageExtractor(PageOcrEngines(listOf(OcrSpy()))), inputFor(managed, probe), probe)

        assertEquals(emptyList(), probe.eventsOutsidePermit)
        assertEquals(
            events.size,
            probe.permits,
            "the extractor took a permit it did not need, or emitted without one",
        )
    }

    @Test
    fun `a refusal found mid-unit is emitted inside that unit's permit`() = runBlocking {
        val managed = managedImage(OcrFixtureGenerator.IMAGE_NAME)
        val probe = PermitProbeBoundary()
        val spy = OcrSpy(unavailable = TesseractOcr.NEEDS_TESSERACT_CODE)

        val events = collect(ImageExtractor(PageOcrEngines(listOf(spy))), inputFor(managed, probe), probe)

        assertEquals(emptyList(), probe.eventsOutsidePermit)
        assertEquals(
            events.size,
            probe.permits,
            "the document-level refusal opened a second permit for one step of work",
        )
        val refusal = failures(events).single()
        assertEquals(DOCUMENT_REFUSED_KEY, refusal.key)
        assertEquals(TesseractOcr.NEEDS_TESSERACT_CODE, refusal.code)
        assertTrue(events.none { it is ExtractionEvent.Finished }, "a refused document was reported done")
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

    /**
     * A picture that declares more pixels than this build hands to a tool: white paper, small on disk,
     * sixteen million eight hundred thousand pixels when decoded.
     */
    private fun oversizedPaper(name: String): Path {
        val target = directory.resolve("managed").resolve(name)
        Files.createDirectories(target.parent)
        val paper = BufferedImage(
            OVER_THE_SCAN_BOUND_SIDE,
            OVER_THE_SCAN_BOUND_SIDE,
            BufferedImage.TYPE_BYTE_GRAY,
        )
        Arrays.fill((paper.raster.dataBuffer as DataBufferByte).data, 0xFF.toByte())
        check(ImageIO.write(paper, "png", target.toFile())) { "no PNG writer is available" }
        return target
    }

    /** A real picture cut off after its header: the raster it declares is not there to be decoded. */
    private fun truncatedPicture(name: String): Path {
        val whole = oversizedPaper("whole.png")
        val target = directory.resolve("managed").resolve(name)
        Files.write(target, Files.readAllBytes(whole).copyOf(TRUNCATED_PICTURE_BYTES))
        Files.delete(whole)
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
        settings: ExtractionSettings = ExtractionSettings(ocrLanguages = languages),
    ): ExtractionInput = ExtractionInput(
        documentId = DocumentId("doc-1"),
        managedPath = source,
        artifactRoot = artifactRoot,
        settings = settings,
        fingerprint = ExtractionFingerprint.of("d".repeat(64), settings),
        committedUnitKeys = committed,
        boundary = boundary,
        originalFilename = originalFilename,
    )

    private fun fingerprint(): ExtractionFingerprint =
        ExtractionFingerprint.of("d".repeat(64), ExtractionSettings(ocrLanguages = "swe+eng"))

    private fun sha256Of(path: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    /** The injected page-image engine, recording what it was asked to read. */
    private class OcrSpy(
        private val text: String = "Rapporten är hemligstämplad",
        private val confidence: Double? = 0.94,
        private val unavailable: String? = null,
        private val failWith: IOException? = null,
        private val errorCode: String? = null,
        override val engine: OcrEngine = OcrEngine.TESSERACT,
    ) : PageOcrEngine {

        val pages: MutableList<PageImage> = mutableListOf()

        /** What the engine was told about each attempt, in the order it was asked. */
        val settings: MutableList<OcrSettingsSnapshot> = mutableListOf()

        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
            pages += page
            this.settings += settings
            unavailable?.let { code -> throw OcrUnavailableException(code, "no OCR tool in this build") }
            failWith?.let { failure -> throw failure }
            return OcrPageResult(
                text = text,
                engine = engine,
                imageSha256 = page.sha256,
                meanConfidence = confidence,
                errorCode = errorCode,
            )
        }
    }

    private companion object {

        /**
         * The smallest square page past the bound this build scans: 16_785_409 pixels.
         *
         * The paper the test writes really is white, so a build that scanned it would call it blank.
         */
        const val OVER_THE_SCAN_BOUND_SIDE: Int = 4097

        /**
         * How much of a picture is written before it is cut off: its signature and its header.
         *
         * A PNG's IHDR chunk ends at byte thirty-three, so this leaves the declared raster legible and no
         * pixels behind it — the file a reader can measure and cannot decode.
         */
        const val TRUNCATED_PICTURE_BYTES: Int = 64

        /** The reading the fake tool reports: one line, two words, a confidence per word. */
        val TSV: String = listOf(
            "1\t1\t0\t0\t0\t0\t0\t0\t900\t240\t-1\t",
            "5\t1\t1\t1\t1\t1\t44\t52\t214\t41\t94\tRapporten",
            "5\t1\t1\t1\t1\t2\t277\t52\t39\t33\t94\tär",
            "5\t1\t1\t1\t1\t3\t335\t50\t348\t43\t94\themligstämplad",
        ).joinToString("\n")
    }
}
