package infoscry.ocr

import infoscry.chunk.ChunkDraft
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DOCUMENT_REFUSED_KEY
import infoscry.extract.EXTRACTOR_SCHEMA_VERSION
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.DocumentExtractor
import infoscry.extract.ExtractionSink
import infoscry.extract.ImageExtractor
import infoscry.extract.OcrUnavailableException
import infoscry.extract.PdfExtractor
import infoscry.extract.PdfPageRenderer
import infoscry.extract.PermitProbeBoundary
import infoscry.ocr.PageImageRenderer
import infoscry.extract.PngPageRenderer
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.Instants
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageText
import infoscry.storage.RevisionState
import infoscry.storage.SchemaMigrator
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * The page-image seam: what one page image is, what a reading may claim, and who reads it.
 *
 * These are the properties the rest of the OCR work stands on, so each of them is asserted against the
 * thing that can produce it rather than against a value built by hand:
 *
 * - a page image can only name a file inside the root its producer chose, and the reading it is read
 *   into is refused if the artifact is no longer the bytes the recorded hash describes,
 * - the engine that reads is the one the settings select, and a build that does not have it refuses the
 *   document by name instead of quietly reading with a different tool,
 * - the attempt records the resolution its pages were actually rendered at, so two attempts at different
 *   resolutions are two readings and neither reuses the other's committed pages,
 * - a cancelled render stages no page and leaves the published revision exactly as it was.
 *
 * The engines here are fakes and the archive is a real temporary SQLite file: a fake tool can prove what
 * this project asks of an engine and what it does with the answer, which is the half a stand-in cannot
 * observe, while the reuse and staging rules are about what a *store* reads back rather than about a
 * value compared in memory.
 */
class OcrEngineContractTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-ocr-contract")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    // ---- What a page image is ------------------------------------------------------------------------

    @Test
    fun `a page image reference can only resolve inside the root it was produced under`() {
        val root = Files.createDirectory(directory.resolve("pages"))
        createImage(root.resolve("page-000001.png"))

        pageImage(root, "page-000001.png").let { image ->
            assertEquals(root.resolve("page-000001.png"), image.imagePath)
            assertTrue(image.imagePath.startsWith(root))
            assertEquals(sha256Of(image.imagePath), image.sha256, "the hash is not the artifact's own")
            assertEquals(120, image.width)
            assertEquals(80, image.height)
        }
        listOf("../elsewhere.png", "sub/../../elsewhere.png", "/etc/passwd").forEach { escape ->
            assertFailsWith<IllegalArgumentException>("'$escape' was accepted as a page image reference") {
                pageImage(root, escape)
            }
        }
    }

    @Test
    fun `a page image whose artifact changed is not the image its hash describes`() {
        val root = Files.createDirectory(directory.resolve("changed"))
        val path = root.resolve("page-000001.png")
        createImage(path)
        val image = pageImage(root, "page-000001.png")

        assertTrue(image.isIntact(), "an artifact that has not changed was not recognised as itself")
        Files.writeString(path, "the artifact another attempt replaced it with")
        assertTrue(!image.isIntact(), "a replaced artifact was still described by the old hash")

        Files.delete(path)
        assertTrue(!image.isIntact(), "a missing artifact was still described by the old hash")
    }

    @Test
    fun `an artifact that cannot be measured has no dimensions rather than zero dimensions`() {
        // Not an image at all: the bytes hash, so a reading may still say what it read, but the size is
        // unknown — and unknown is not a claim of zero by zero.
        val root = Files.createDirectory(directory.resolve("unmeasurable"))
        Files.writeString(root.resolve("page-000001.png"), "not an image")

        val image = pageImage(root, "page-000001.png")

        assertNull(image.width)
        assertNull(image.height)
        assertEquals(sha256Of(root.resolve("page-000001.png")), image.sha256)
    }

    @Test
    fun `blank paper is blank and one dark pixel on it is not`() {
        val root = Files.createDirectory(directory.resolve("paper"))
        writePaper(root.resolve("blank.png"), width = 120, height = 80)
        writePaper(root.resolve("inked.png"), width = 120, height = 80, darkPixel = 57)

        assertTrue(pageImage(root, "blank.png").isBlankPaper(), "white paper was not recognised as blank")
        assertFalse(
            pageImage(root, "inked.png").isBlankPaper(),
            "a page carrying one dark pixel was called blank paper",
        )
    }

    @Test
    fun `a container whose later frame carries ink is not blank paper`() {
        // A multi-page TIFF is one unit, and its first picture can be white paper while a later one carries
        // ink: asking the reader about frame 0 alone would call that artifact blank and commit an empty
        // reading over a scan that has text on it, which is a loss nothing downstream can notice. So every
        // frame has to be white — and an artifact whose frames all are must still be recognised.
        val root = Files.createDirectory(directory.resolve("multi-frame"))
        writeMultiFramePaper(root.resolve("white-then-inked.tiff"), inkedFrame = 1)
        writeMultiFramePaper(root.resolve("all-white.tiff"), inkedFrame = null)

        assertFalse(
            pageImage(root, "white-then-inked.tiff").isBlankPaper(),
            "the ink on the second frame was not seen, so the artifact was called blank paper",
        )
        assertTrue(
            pageImage(root, "all-white.tiff").isBlankPaper(),
            "an artifact whose every frame is white paper was not recognised as blank paper",
        )
    }

    @Test
    fun `an artifact whose header declares more pixels than this process scans is not called blank`() {
        // The declaration is a number a small, highly compressed file chooses, so the raster is refused
        // before it is allocated rather than after. The file really is white paper — decoding it here says
        // so — and the answer is still "not blank": an artifact this process will not read is not evidence
        // about the page it describes.
        val root = Files.createDirectory(directory.resolve("oversized-paper"))
        val path = root.resolve("page-000001.png")
        writePaper(path, width = OVER_THE_SCAN_BOUND_SIDE, height = OVER_THE_SCAN_BOUND_SIDE)

        assertTrue(
            OVER_THE_SCAN_BOUND_SIDE.toLong() * OVER_THE_SCAN_BOUND_SIDE > PdfExtractor.MAX_RENDERED_PIXELS,
            "this page is not past the bound this process scans, so it would prove nothing",
        )
        assertTrue(decodesToWhitePaper(path), "the artifact is not blank paper, so refusing it proves nothing")
        assertFalse(
            pageImage(root, "page-000001.png").isBlankPaper(),
            "an oversized raster was scanned and called blank paper",
        )
    }

    @Test
    fun `a header declaring a raster no page could hold is refused without decoding it`() {
        // Forty-six thousand square is over two billion pixels: a build that decoded this instead of reading
        // the declaration first would try to allocate a raster of several gigabytes.
        val root = Files.createDirectory(directory.resolve("absurd-paper"))
        writePngDeclaring(root.resolve("page-000001.png"), width = ABSURD_SIDE, height = ABSURD_SIDE)

        val image = pageImage(root, "page-000001.png")
        assertEquals(ABSURD_SIDE, image.width, "the declared size is the one the header states")

        assertFalse(image.isBlankPaper(), "a raster no page could hold was decoded and called blank paper")
    }

    @Test
    fun `an artifact no reader can claim is not called blank paper`() {
        val root = Files.createDirectory(directory.resolve("unreadable-paper"))
        Files.writeString(root.resolve("page-000001.png"), "the bytes of no image format at all")

        assertFalse(
            pageImage(root, "page-000001.png").isBlankPaper(),
            "an artifact that could not be read was called blank paper",
        )
    }

    // ---- Which engine reads -------------------------------------------------------------------------

    @Test
    fun `an engine this build does not have is refused rather than substituted`() {
        val tesseract = RecordingEngine()
        val engines = PageOcrEngines(listOf(tesseract))

        val failure = assertFailsWith<OcrUnavailableException> { engines.forKind(OcrEngine.SURYA) }

        assertEquals("NEEDS_SURYA", failure.code, "the thing to install has to be the thing that is named")
        assertContains(failure.message.orEmpty(), "SURYA")
        assertEquals(setOf(OcrEngine.TESSERACT), engines.configuredKinds)

        // And through an extraction: a document whose settings select an engine this build does not have is
        // refused as a document, and the tool that *is* installed is never asked to read it.
        val probe = PermitProbeBoundary()
        val events = collect(
            PdfExtractor(engines),
            inputFor(fixture("mixed.pdf"), attemptSettings(engine = OcrEngine.SURYA)),
            probe,
        )

        assertEquals(
            listOf("NEEDS_SURYA"),
            events.filterIsInstance<ExtractionEvent.UnitFailed>()
                .filter { it.key == DOCUMENT_REFUSED_KEY }
                .map { it.code },
            "an unconfigured engine was not refused as a document-level reason",
        )
        assertTrue(tesseract.pages.isEmpty(), "another engine read a document that selects Surya")
        assertTrue(
            events.none { it is ExtractionEvent.Finished },
            "a document that could not be read reported itself as finished",
        )
    }

    @Test
    fun `two engines of one kind are a wiring mistake rather than a coin toss`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            PageOcrEngines(listOf(RecordingEngine(), RecordingEngine()))
        }

        assertContains(failure.message.orEmpty(), "TESSERACT")
    }

    @Test
    fun `a reading an engine attributes to another engine is refused`() {
        val liar = object : PageOcrEngine {

            override val engine: OcrEngine = OcrEngine.TESSERACT

            override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult =
                OcrPageResult(text = "a reading", engine = OcrEngine.SURYA, imageSha256 = page.sha256)
        }
        val engines = PageOcrEngines(listOf(liar))
        val image = writtenPageImage()

        assertFailsWith<IllegalStateException> {
            runBlocking { engines.transcribe(OcrEngine.TESSERACT, image, attemptSettings().pageOcrSettings(300)) }
        }
    }

    // ---- What the engine is told ---------------------------------------------------------------------

    @Test
    fun `the engine is told what the attempt is and which page the image is`() {
        val engine = RecordingEngine()
        val settings = attemptSettings(requestedDpi = 300, mode = OcrImportMode.CHECK_AND_IMPROVE)
        val input = inputFor(fixture("mixed.pdf"), settings)
        val probe = PermitProbeBoundary()

        collect(PdfExtractor(PageOcrEngines(listOf(engine))), input, probe)

        // Every page of this document is read, because check-and-improve reads page images even where a
        // text layer exists: the third one is the page whose image the reader had to produce.
        val image = engine.pages.single { page -> page.ordinal == 2 }
        assertEquals(DocumentId("doc-1"), image.documentId)
        assertEquals("page:3", image.unitId, "the page the engine read is not the page the extractor names")
        assertEquals(PageImage.RENDER_VERSION, image.renderVersion)
        assertEquals(0, image.rotationDegrees)
        assertEquals(300, image.renderDpi)
        assertTrue(Files.isRegularFile(image.imagePath), "the engine was handed an image that was never written")
        assertTrue(
            image.imagePath.startsWith(input.artifactRoot),
            "a page image was written outside the artifact root: ${image.imagePath}",
        )
        assertEquals(sha256Of(image.imagePath), image.sha256)
        assertNotNull(image.width)
        assertNotNull(image.height)

        val told = engine.settings.first()
        assertEquals(
            OcrImportMode.CHECK_AND_IMPROVE,
            told.mode,
            "the engine was not told the mode the extraction was admitted with",
        )
        assertEquals("eng", told.language, "the collection's languages did not reach the engine")
        assertEquals(
            settings.ocrAttempt,
            told.attemptIdentity(),
            "the attempt the engine was told is another one",
        )
    }

    @Test
    fun `the resolution a page was rendered at is what its reading records`() {
        // The pixel bound is below what 300 dpi asks for, so the page is rendered at a fitted resolution:
        // "the resolution this attempt asked for" and "the resolution this page was rendered at" are then
        // different numbers, and the attempt has to record the second one — a reading of a page at another
        // resolution is another reading, and an attempt that claimed the requested resolution would let a
        // later attempt reuse pages it never read.
        val engine = RecordingEngine()
        val extractor = PdfExtractor(PageOcrEngines(listOf(engine)), maxRenderedPixels = FITTED_PIXEL_BOUND)
        val probe = PermitProbeBoundary()

        collect(extractor, inputFor(fixture("mixed.pdf"), attemptSettings(requestedDpi = 300)), probe)

        val image = engine.pages.first()
        val fitted = assertNotNull(image.renderDpi)
        assertTrue(
            fitted in PdfExtractor.MIN_RENDER_DPI until 300,
            "the page was not brought within the pixel bound, so this proves nothing: $fitted",
        )
        assertEquals(
            fitted,
            engine.settings.first().renderDpi,
            "the reading recorded the resolution it asked for rather than the one it used",
        )
        assertEquals(fitted, engine.settings.first().attemptIdentity().renderDpi)
    }

    @Test
    fun `a reading is keyed by the resolution it was rendered at and not by re-embedding`() {
        val archive = Archive(directory)
        try {
            val document = archive.document()
            val pageKey = "page:3"
            val reading = "the reading that was committed at three hundred dpi"
            val unit = archive.content.commitExtractedUnit(
                documentId = document.id,
                fingerprint = pageFingerprint(document, dpi = 300),
                key = pageKey,
                ordinal = 2,
                draft = ContentUnitDraft(
                    locator = SourceLocation.PdfPage(3),
                    extractedText = reading,
                    searchText = reading,
                    method = ExtractionMethod.OCR,
                ),
                artifactRoot = archive.artifacts,
            ).unit

            assertTrue(
                pageKey in archive.content
                    .reusableCheckpoints(document.id, pageFingerprint(document, 300), archive.artifacts)
                    .skipKeys,
                "the page this attempt read is not reusable by the same attempt",
            )
            assertTrue(
                archive.content.reusableCheckpoints(document.id, pageFingerprint(document, 150), archive.artifacts)
                    .skipKeys.isEmpty(),
                "a page read at 300 dpi was treated as already read at 150 dpi",
            )

            // Re-embedding is not re-reading: the chunks of the page are replaced, and what the attempt read
            // is still what it read.
            archive.content.replaceUnitChunks(
                unitId = unit.id,
                drafts = listOf(
                    ChunkDraft(
                        ordinal = 0,
                        text = reading,
                        startOffset = 0,
                        endOffset = reading.length,
                        tokenCount = 8,
                        tokenStart = 0,
                        tokenEnd = 7,
                    ),
                ),
                chunkerVersion = "test-chunker",
                tokenizerId = "test-tokenizer",
                maxSequenceTokens = 512,
                overlapTokens = 0,
            )
            assertTrue(
                pageKey in archive.content
                    .reusableCheckpoints(document.id, pageFingerprint(document, 300), archive.artifacts)
                    .skipKeys,
                "re-embedding a page invalidated the reading it was embedded from",
            )
        } finally {
            archive.close()
        }
    }

    // ---- The candidate sink -------------------------------------------------------------------------

    @Test
    fun `a rescan's pages are staged into a candidate and the published text is untouched`() {
        val archive = Archive(directory)
        try {
            val document = archive.document()
            val published = listOf("the published first page", "the published second page")
            published.forEachIndexed { ordinal, text ->
                archive.content.commitExtractedUnit(
                    documentId = document.id,
                    fingerprint = baselineFingerprint(document),
                    key = "page:${ordinal + 1}",
                    ordinal = ordinal,
                    draft = ContentUnitDraft(
                        locator = SourceLocation.PdfPage(ordinal + 1),
                        extractedText = text,
                        searchText = text,
                        method = ExtractionMethod.DIRECT_TEXT,
                    ),
                    artifactRoot = archive.artifacts,
                )
            }
            val baseline = assertNotNull(archive.revisions.recordPublishedContent(document.id, "IMPORT"))
            val baselinePages = archive.revisions.pages(baseline)

            val sink = CandidateRevisionSink(archive.revisions, document.id, "RESCAN")
            val engine = RecordingEngine(text = { page -> "the new reading of page $page" })
            val settings = attemptSettings(mode = OcrImportMode.CHECK_AND_IMPROVE)
            extractInto(
                PdfExtractor(PageOcrEngines(listOf(engine))),
                inputFor(
                    fixture("text.pdf"),
                    settings,
                    documentId = document.id,
                    artifactRoot = archive.artifacts,
                    boundary = PermitProbeBoundary(),
                ),
                sink,
            )

            assertEquals(3, engine.pages.size, "the rescan did not read every page image")
            val candidate = assertNotNull(sink.candidateRevisionId)
            assertEquals(RevisionState.CANDIDATE, assertNotNull(archive.revisions.revision(candidate)).state)
            val staged = archive.revisions.pages(candidate)
            assertEquals(listOf(0, 1, 2), staged.map { it.ordinal })
            assertEquals(
                baselinePages.map { it.unitId },
                staged.take(baselinePages.size).map { it.unitId },
                "staging renamed the pages of the document it is replacing",
            )
            assertTrue(
                staged.last().unitId !in baselinePages.map { it.unitId },
                "a page with no baseline at its ordinal inherited another page's identity",
            )
            assertEquals(
                listOf(PageApproval.PENDING, PageApproval.PENDING, PageApproval.PENDING),
                staged.map { it.approval },
                "a staged page was already approved by the attempt that read it",
            )
            assertTrue(staged.all { it.extractionMethod == ExtractionMethod.OCR })
            assertEquals("the new reading of page 1", staged.first().extractedText)

            assertEquals(
                published,
                archive.revisions.pages(baseline).map { it.extractedText },
                "the published reading changed while a replacement was staged",
            )
            assertEquals(baseline, archive.revisions.activeRevisionId(document.id))
            assertEquals(published, archive.content.listUnits(document.id, afterOrdinal = -1, limit = 10).map { it.extractedText })
        } finally {
            archive.close()
        }
    }

    @Test
    fun `a page staged from a fill-missing render names an image that is still there after the attempt`() {
        val archive = Archive(directory)
        try {
            val document = archive.document()
            val sink = CandidateRevisionSink(archive.revisions, document.id, "IMPORT")
            val engine = RecordingEngine()
            val input = inputFor(
                fixture("mixed.pdf"),
                attemptSettings(mode = OcrImportMode.FILL_MISSING),
                documentId = document.id,
                artifactRoot = archive.artifacts,
                boundary = PermitProbeBoundary(),
                retainsPageImages = true,
            )

            extractInto(PdfExtractor(PageOcrEngines(listOf(engine))), input, sink)

            val staged = archive.revisions.pages(assertNotNull(sink.candidateRevisionId))
            val read = staged.filter { page -> page.extractionMethod == ExtractionMethod.OCR }
            assertTrue(read.isNotEmpty(), "no page was read from a render, so this proves nothing")
            read.forEach { page ->
                val source = assertNotNull(page.sourceImage, "a page read from a render names no image")
                val artifact = archive.artifacts.resolve(source.relativePath)
                assertTrue(Files.isRegularFile(artifact), "page ${page.ordinal} names a render that is gone")
                assertEquals(sha256Of(artifact), source.sha256)
            }
            assertEquals(
                0,
                staged.count { page -> page.extractionMethod != ExtractionMethod.OCR && page.sourceImage != null },
                "a page whose own text was read named an image",
            )

            // A withdrawn candidate keeps its rows, so what its rows name stays: nothing is swept out from
            // under a page that can still be opened, and the document's deletion is what removes both.
            sink.withdraw()
            read.forEach { page ->
                assertTrue(
                    Files.isRegularFile(archive.artifacts.resolve(assertNotNull(page.sourceImage).relativePath)),
                    "withdrawing the candidate removed an image its page still names",
                )
            }
        } finally {
            archive.close()
        }
    }

    @Test
    fun `a page staged from a render nobody asked to keep records no image rather than a dead one`() {
        val archive = Archive(directory)
        try {
            val document = archive.document()
            val sink = CandidateRevisionSink(archive.revisions, document.id, "IMPORT")
            val input = inputFor(
                fixture("mixed.pdf"),
                attemptSettings(mode = OcrImportMode.FILL_MISSING),
                documentId = document.id,
                artifactRoot = archive.artifacts,
                boundary = PermitProbeBoundary(),
            )

            extractInto(PdfExtractor(PageOcrEngines(listOf(RecordingEngine()))), input, sink)

            val staged = archive.revisions.pages(assertNotNull(sink.candidateRevisionId))
            assertTrue(
                staged.any { page -> page.extractionMethod == ExtractionMethod.OCR },
                "no page was read from a render, so this proves nothing",
            )
            staged.forEach { page ->
                // The rule under test is the invariant itself: whatever a staged page names exists.
                page.sourceImage?.let { source ->
                    assertTrue(
                        Files.isRegularFile(archive.artifacts.resolve(source.relativePath)),
                        "page ${page.ordinal} names ${source.relativePath}, which is not there",
                    )
                }
            }
            assertEquals(
                0,
                staged.count { page -> page.sourceImage != null },
                "a render that was deleted with the attempt was still recorded as a page's image",
            )
        } finally {
            archive.close()
        }
    }

    @Test
    fun `a staged revision page names the image the reading was made from`() {
        val archive = Archive(directory)
        try {
            val document = archive.document()
            val sink = CandidateRevisionSink(archive.revisions, document.id, "RESCAN")
            val engine = RecordingEngine()
            val input = inputFor(
                fixture("text.pdf"),
                attemptSettings(mode = OcrImportMode.CHECK_AND_IMPROVE),
                documentId = document.id,
                artifactRoot = archive.artifacts,
                boundary = PermitProbeBoundary(),
            )

            extractInto(PdfExtractor(PageOcrEngines(listOf(engine))), input, sink)

            val candidate = assertNotNull(sink.candidateRevisionId)
            val staged = archive.revisions.pages(candidate)
            assertEquals(3, staged.size, "the rescan did not stage every page it read")

            // Reading the page back has to give the same answer the file itself does, because the point of
            // the record is that a later reader can open the pixels this page's text came from: the
            // reference resolves under the document's artifact root, and the hash and the dimensions are the
            // artifact's own rather than what the producer meant to write.
            staged.zip(engine.pages).forEach { (page, image) ->
                val source = assertNotNull(page.sourceImage, "a staged page names no image it was read from")
                assertEquals(SourceImageRoot.ARTIFACTS, source.root)
                assertEquals(
                    "${input.fingerprint.value}/${PageImageRenderer.PAGES_DIRECTORY}/page-%06d.png"
                        .format(page.ordinal + 1),
                    source.relativePath,
                )
                val artifact = archive.artifacts.resolve(source.relativePath)
                assertTrue(Files.isRegularFile(artifact), "the staged page names an image that is not there")
                assertEquals(sha256Of(artifact), source.sha256, "the page names a hash that is not its bytes")
                assertEquals(image.sha256, source.sha256)
                assertEquals(image.width, source.width)
                assertEquals(image.height, source.height)
                assertEquals(
                    PageImage.RENDER_VERSION,
                    source.renderVersion,
                    "a page this pipeline rendered was recorded as a reduction of something else",
                )
            }
        } finally {
            archive.close()
        }
    }

    @Test
    fun `a staged page rebuilds the image it was read from and refuses one whose bytes changed`() {
        val archive = Archive(directory)
        try {
            val document = archive.document()
            val sink = CandidateRevisionSink(archive.revisions, document.id, "RESCAN")
            val engine = RecordingEngine()
            val input = inputFor(
                fixture("text.pdf"),
                attemptSettings(mode = OcrImportMode.CHECK_AND_IMPROVE),
                documentId = document.id,
                artifactRoot = archive.artifacts,
                boundary = PermitProbeBoundary(),
            )

            extractInto(PdfExtractor(PageOcrEngines(listOf(engine))), input, sink)

            // What a page read under check-and-improve keeps is enough to get its image back, which is what a
            // later comparison needs: the record is the reference, the hash, the dimensions and the rendering
            // version, and the page image rebuilt from it is the one the reading was made from.
            val staged = archive.revisions.pages(assertNotNull(sink.candidateRevisionId))
            assertEquals(3, staged.size, "the rescan did not stage every page it read")
            staged.zip(engine.pages).forEach { (page, read) ->
                val source = assertNotNull(page.sourceImage, "a staged page names no image it was read from")
                val rebuilt = rebuiltFrom(archive, document.id, page)

                assertEquals(page.unitId.value, rebuilt.unitId)
                assertEquals(page.ordinal, rebuilt.ordinal)
                assertTrue(Files.isRegularFile(rebuilt.imagePath), "the rebuilt page names no artifact")
                assertEquals(read.sha256, rebuilt.sha256, "the rebuilt page is not the image that was read")
                assertEquals(source.sha256, rebuilt.sha256)
                assertEquals(read.width, rebuilt.width)
                assertEquals(read.height, rebuilt.height)
                assertEquals(source.width, rebuilt.width)
                assertEquals(source.height, rebuilt.height)
                assertEquals(source.renderVersion, rebuilt.renderVersion)
                assertTrue(rebuilt.isIntact(), "the rebuilt page is not the image its own hash describes")
            }

            // An image whose bytes are no longer the ones the page was read from is not that page's image, so
            // a comparison is refused rather than shown other pixels under this page's name — and neither is
            // a record whose artifact is gone.
            val page = staged.first()
            val artifact = archive.artifacts.resolve(
                assertNotNull(page.sourceImage, "a staged page names no image it was read from").relativePath,
            )
            Files.write(artifact, "another image entirely".toByteArray())
            val changed = assertFailsWith<IllegalStateException> { rebuiltFrom(archive, document.id, page) }
            assertContains(changed.message.orEmpty(), "no longer the image that page was read from")

            Files.delete(artifact)
            assertFailsWith<IllegalStateException> { rebuiltFrom(archive, document.id, page) }

            // A picture read under check-and-improve names the other root: the page *is* the managed copy, so
            // its rebuild resolves against the directory the copy lives in rather than against the artifacts.
            val picture = directory.resolve("managed").resolve("scan.png")
            writePaper(picture, width = MULTI_FRAME_WIDTH, height = MULTI_FRAME_HEIGHT)
            val pictureDocument = archive.document(name = "scan.png", collection = "Rescan pictures")
            val pictureSink = CandidateRevisionSink(archive.revisions, pictureDocument.id, "RESCAN")
            extractInto(
                ImageExtractor(PageOcrEngines(listOf(RecordingEngine()))),
                inputFor(
                    picture,
                    attemptSettings(mode = OcrImportMode.CHECK_AND_IMPROVE),
                    documentId = pictureDocument.id,
                    artifactRoot = archive.artifacts,
                    boundary = PermitProbeBoundary(),
                ),
                pictureSink,
            )

            val picturePage = archive.revisions.pages(assertNotNull(pictureSink.candidateRevisionId)).single()
            assertEquals(SourceImageRoot.MANAGED_COPY, assertNotNull(picturePage.sourceImage).root)
            assertEquals(sha256Of(picture), rebuiltFrom(archive, pictureDocument.id, picturePage).sha256)
        } finally {
            archive.close()
        }
    }

    @Test
    fun `a staged picture page names the copy it was read from rather than the picture behind it`() {
        val archive = Archive(directory)
        try {
            // A picture whose declared raster is past the bound a tool is handed: it is read from a bounded
            // copy this attempt wrote, and that copy is a different image from the managed original. A
            // durable page that named only a hash or only the document would leave a reviewer unable to tell
            // the two apart, so the copy is what the staged page names.
            val managed = directory.resolve("managed").resolve("oversized.png")
            Files.createDirectories(managed.parent)
            writePaper(managed, width = OVER_THE_SCAN_BOUND_SIDE, height = OVER_THE_SCAN_BOUND_SIDE)
            val document = archive.document(name = "scan.png")
            val sink = CandidateRevisionSink(archive.revisions, document.id, "RESCAN")
            val engine = RecordingEngine(text = { "the reading of the reduced copy" })
            val input = inputFor(
                managed,
                attemptSettings(mode = OcrImportMode.CHECK_AND_IMPROVE),
                documentId = document.id,
                artifactRoot = archive.artifacts,
                boundary = PermitProbeBoundary(),
            )

            extractInto(ImageExtractor(PageOcrEngines(listOf(engine))), input, sink)

            val staged = archive.revisions.pages(assertNotNull(sink.candidateRevisionId)).single()
            val source = assertNotNull(staged.sourceImage, "a reduced picture named no image it was read from")
            assertEquals(SourceImageRoot.ARTIFACTS, source.root, "a bounded copy is this attempt's own artifact")
            assertEquals(
                "${input.fingerprint.value}/${PageImageRenderer.PAGES_DIRECTORY}/${ImageExtractor.REDUCED_PICTURE_NAME}",
                source.relativePath,
            )
            val copy = archive.artifacts.resolve(source.relativePath)
            assertTrue(Files.isRegularFile(copy), "the staged picture page names a copy that is not there")
            assertEquals(sha256Of(copy), source.sha256)
            assertNotEquals(
                sha256Of(managed),
                source.sha256,
                "the staged page was attributed to the managed picture's own pixels",
            )
            assertEquals(engine.pages.single().width, source.width)
            assertEquals(engine.pages.single().height, source.height)
            assertTrue(
                assertNotNull(source.width) < OVER_THE_SCAN_BOUND_SIDE,
                "the staged page carries the original's dimensions rather than the copy's: ${source.width}",
            )
            assertEquals(PageImage.REDUCED_RENDER_VERSION, source.renderVersion)
        } finally {
            archive.close()
        }
    }

    @Test
    fun `staged keys answer only for the document and fingerprint they were staged for`() {
        val archive = Archive(directory)
        try {
            val document = archive.document()
            val otherDocument = DocumentId("doc-another")
            val first = ExtractionFingerprint.of("1".repeat(64), ExtractionSettings(ocrLanguages = "eng"))
            val second = ExtractionFingerprint.of("2".repeat(64), ExtractionSettings(ocrLanguages = "eng"))
            val sink = CandidateRevisionSink(archive.revisions, document.id, "RESCAN")

            runBlocking { sink.deliver(document.id, first, stagedPage("page:1", 0)) }

            assertEquals(
                setOf("page:1"),
                runBlocking { sink.committedKeys(document.id, first) },
                "the attempt's own page was not answered as committed",
            )
            assertEquals(
                emptySet(),
                runBlocking { sink.committedKeys(document.id, second) },
                "another reading of the same document inherited this reading's pages",
            )
            assertEquals(
                emptySet(),
                runBlocking { sink.committedKeys(otherDocument, first) },
                "another document inherited this document's staged pages",
            )

            runBlocking { sink.deliver(document.id, second, stagedPage("page:2", 1)) }

            assertEquals(
                setOf("page:1"),
                runBlocking { sink.committedKeys(document.id, first) },
                "a delivery under another fingerprint was added to the first reading's keys",
            )
            assertEquals(setOf("page:2"), runBlocking { sink.committedKeys(document.id, second) })
        } finally {
            archive.close()
        }
    }

    @Test
    fun `cancelling a render stages no page of it and leaves the published baseline untouched`() {
        val archive = Archive(directory)
        try {
            val document = archive.document()
            val published = "the reading this document publishes"
            archive.content.commitExtractedUnit(
                documentId = document.id,
                fingerprint = baselineFingerprint(document),
                key = "page:1",
                ordinal = 0,
                draft = ContentUnitDraft(
                    locator = SourceLocation.PdfPage(1),
                    extractedText = published,
                    searchText = published,
                    method = ExtractionMethod.DIRECT_TEXT,
                ),
                artifactRoot = archive.artifacts,
            )
            val baseline = assertNotNull(archive.revisions.recordPublishedContent(document.id, "IMPORT"))

            val sink = CandidateRevisionSink(archive.revisions, document.id, "RESCAN")
            // Page three is the first page this rescan has to render, and the render is cancelled there.
            val cancelled = PdfPageRenderer { renderer, page, dpi, where ->
                if (page == 3) throw CancellationException("the attempt was cancelled while rendering")
                PngPageRenderer.render(renderer, page, dpi, where)
            }
            val input = inputFor(
                fixture("mixed.pdf"),
                attemptSettings(mode = OcrImportMode.CHECK_AND_IMPROVE),
                documentId = document.id,
                artifactRoot = archive.artifacts,
                boundary = PermitProbeBoundary(),
            )

            assertFailsWith<CancellationException> {
                extractInto(PdfExtractor(PageOcrEngines(listOf(RecordingEngine())), pageRenderer = cancelled), input, sink)
            }

            val candidate = assertNotNull(sink.candidateRevisionId)
            assertEquals(
                listOf(0, 1),
                archive.revisions.pages(candidate).map { it.ordinal },
                "the page whose render was cancelled was staged, or the pages before it were not",
            )
            assertEquals(
                emptyList(),
                archive.content.loadCheckpoints(document.id, input.fingerprint),
                "a cancelled attempt left a checkpoint behind",
            )
            assertEquals(baseline, archive.revisions.activeRevisionId(document.id))
            assertEquals(listOf(published), archive.revisions.pages(baseline).map { it.extractedText })
            assertEquals(published, archive.content.listUnits(document.id, afterOrdinal = -1, limit = 10).single().extractedText)

            sink.withdraw()
            assertEquals(RevisionState.WITHDRAWN, assertNotNull(archive.revisions.revision(candidate)).state)
        } finally {
            archive.close()
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    /** What the fixture pages are read as, so a test can tell one page's reading from another's. */
    private fun collect(
        extractor: PdfExtractor,
        input: ExtractionInput,
        probe: PermitProbeBoundary,
    ): List<ExtractionEvent> = runBlocking {
        extractor.extract(input).onEach { probe.observed(it) }.toList()
    }

    /** Collects an extraction into [sink] the way the pipeline does: every event is committed inline. */
    private fun extractInto(
        extractor: DocumentExtractor,
        input: ExtractionInput,
        sink: ExtractionSink,
    ): List<ExtractionEvent> = runBlocking {
        extractor.extract(input)
            .onEach { event -> sink.deliver(input.documentId, input.fingerprint, event) }
            .toList()
    }

    private fun inputFor(
        source: Path,
        settings: ExtractionSettings,
        documentId: DocumentId = DocumentId("doc-1"),
        artifactRoot: Path = directory.resolve("artifacts"),
        boundary: PermitProbeBoundary = PermitProbeBoundary(),
        retainsPageImages: Boolean = false,
    ): ExtractionInput = ExtractionInput(
        documentId = documentId,
        managedPath = source,
        artifactRoot = artifactRoot,
        settings = settings,
        fingerprint = ExtractionFingerprint.of("b".repeat(64), settings),
        committedUnitKeys = emptySet(),
        boundary = boundary,
        retainsPageImages = retainsPageImages,
    )

    /** The settings one OCR attempt runs with: the mode, the engine and the resolution live in them. */
    private fun attemptSettings(
        requestedDpi: Int? = 300,
        mode: OcrImportMode = OcrImportMode.FILL_MISSING,
        engine: OcrEngine = OcrEngine.TESSERACT,
    ): ExtractionSettings = ExtractionSettings(
        ocrLanguages = "eng",
        ocrTool = "tesseract 5.3.0",
        renderDpi = requestedDpi,
        ocrMode = mode,
        ocrAttempt = OcrAttemptIdentity(
            engine = engine,
            language = "eng",
            transcriptionPromptVersion = OCR_TRANSCRIPTION_PROMPT_VERSION,
            extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
            toolVersion = "tesseract 5.3.0",
            renderDpi = requestedDpi,
        ),
    )

    /** The fingerprint of a reading committed for a page, under the resolution the attempt rendered at. */
    private fun pageFingerprint(document: Document, dpi: Int): ExtractionFingerprint =
        ExtractionFingerprint.of(
            document.sha256,
            ExtractionSettings(
                ocrLanguages = "eng",
                renderDpi = dpi,
                ocrAttempt = OcrAttemptIdentity(
                    engine = OcrEngine.TESSERACT,
                    language = "eng",
                    transcriptionPromptVersion = OCR_TRANSCRIPTION_PROMPT_VERSION,
                    extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
                    renderDpi = dpi,
                ),
            ),
        )

    private fun baselineFingerprint(document: Document): ExtractionFingerprint =
        ExtractionFingerprint.of(document.sha256, ExtractionSettings(ocrLanguages = "eng"))

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

    /** A page image over a file this test wrote, with the hash and dimensions read from it. */
    private fun pageImage(root: Path, reference: String): PageImage = PageImage.ofFile(
        documentId = DocumentId("doc-1"),
        unitId = "page:1",
        ordinal = 0,
        imageRoot = root,
        imageReference = reference,
        artifactRoot = directory.resolve("artifacts"),
        renderDpi = 300,
        rotationDegrees = 0,
    )

    /** The page image a staged page's own record names, rebuilt the way a later phase has to. */
    private fun rebuiltFrom(archive: Archive, documentId: DocumentId, page: RevisionPageText): PageImage =
        PageImage.ofProvenance(
            documentId = documentId,
            unitId = page.unitId.value,
            ordinal = page.ordinal,
            provenance = assertNotNull(page.sourceImage, "a staged page names no image it was read from"),
            // The document's artifact root and the directory holding its managed copy: the two roots a
            // provenance names, and the two an archive lays down per document.
            artifactRoot = archive.artifacts,
            managedCopyRoot = directory.resolve("managed"),
        )

    /** A page image over a written PNG, for the tests that only need one that exists. */
    private fun writtenPageImage(): PageImage {
        val root = Files.createDirectory(directory.resolve("written"))
        createImage(root.resolve("page-000001.png"))
        return pageImage(root, "page-000001.png")
    }

    /** One page as an extractor would deliver it, for the sink's own bookkeeping tests. */
    private fun stagedPage(key: String, ordinal: Int): ExtractionEvent.UnitReady = ExtractionEvent.UnitReady(
        key = key,
        ordinal = ordinal,
        unit = ContentUnitDraft(
            locator = SourceLocation.PdfPage(ordinal + 1),
            extractedText = "a staged reading",
            searchText = "a staged reading",
            method = ExtractionMethod.OCR,
        ),
    )

    /**
     * A page as a PNG: white paper, with one dark pixel at [darkPixel] when it is at or above zero.
     *
     * The raster is filled through its own buffer rather than pixel by pixel, because the oversized case
     * writes more than sixteen million of them.
     */
    private fun writePaper(path: Path, width: Int, height: Int, darkPixel: Int = -1) {
        val paper = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        Arrays.fill((paper.raster.dataBuffer as DataBufferByte).data, 0xFF.toByte())
        if (darkPixel >= 0) paper.setRGB(darkPixel % width, darkPixel / width, Color.BLACK.rgb)
        check(ImageIO.write(paper, "png", path.toFile())) { "no PNG writer is available" }
    }

    /** Whether the artifact at [path] really is white paper, decoded here as the control for a refusal. */
    private fun decodesToWhitePaper(path: Path): Boolean {
        val raster = ImageIO.read(path.toFile()) ?: return false
        val row = IntArray(raster.width)
        for (y in 0 until raster.height) {
            raster.getRGB(0, y, raster.width, 1, row, 0, raster.width)
            if (row.any { pixel -> pixel and WHITE_PIXEL != WHITE_PIXEL }) return false
        }
        return true
    }

    /**
     * A PNG that declares [width] by [height] in its header and stops there.
     *
     * The header is the only part of a file that has to be right for it to choose the raster this process
     * would allocate, which is why it is what a bound has to be checked against before anything is decoded.
     */
    private fun writePngDeclaring(path: Path, width: Int, height: Int) {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val ihdr = ByteBuffer.allocate(IHDR_CHUNK_BYTES)
            .put("IHDR".toByteArray(Charsets.US_ASCII))
            .putInt(width)
            .putInt(height)
            // Eight bits per channel, truecolour, no interlace: a header a PNG reader has to accept.
            .put(byteArrayOf(8, 2, 0, 0, 0))
            .array()
        val crc = CRC32().apply { update(ihdr) }
        Files.write(
            path,
            ByteBuffer.allocate(signature.size + LENGTH_FIELD_BYTES + ihdr.size + LENGTH_FIELD_BYTES)
                .put(signature)
                // The IHDR chunk's data is thirteen bytes long, and its CRC covers the type and the data.
                .putInt(IHDR_CHUNK_BYTES - PNG_TYPE_BYTES)
                .put(ihdr)
                .putInt(crc.value.toInt())
                .array(),
        )
    }

    /**
     * A container of two pictures, in which [inkedFrame] carries one dark pixel and the other is white.
     *
     * Written as a real multi-page TIFF, because a container of several pictures is exactly what the
     * blankness question has more than one frame to ask about.
     */
    private fun writeMultiFramePaper(path: Path, inkedFrame: Int?) {
        val frames = (0 until MULTI_FRAME_COUNT).map { frame ->
            BufferedImage(MULTI_FRAME_WIDTH, MULTI_FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB).also { page ->
                val graphics = page.createGraphics()
                try {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, page.width, page.height)
                } finally {
                    graphics.dispose()
                }
                if (frame == inkedFrame) page.setRGB(MULTI_FRAME_WIDTH / 2, MULTI_FRAME_HEIGHT / 2, Color.BLACK.rgb)
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("tiff").next()
        try {
            val parameter = writer.defaultWriteParam
            ImageIO.createImageOutputStream(path.toFile()).use { output ->
                writer.output = output
                writer.prepareWriteSequence(null)
                frames.forEach { frame -> writer.writeToSequence(IIOImage(frame, null, null), parameter) }
                writer.endWriteSequence()
            }
        } finally {
            writer.dispose()
        }
    }

    private fun createImage(path: Path) {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK
            graphics.fillRect(10, 10, 40, 8)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(image, "png", path.toFile())
    }

    private fun sha256Of(path: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    private companion object {

        /** A pixel bound that cannot hold an A4 page at 300 dpi, so a resolution has to be fitted. */
        const val FITTED_PIXEL_BOUND: Long = 2_500_000L

        /** The smallest square page past the bound this build scans: 16_785_409 pixels.
         *
         * The smallest one on purpose: the page really is decodable and really is white, so a build that
         * scanned it would answer "blank", and the refusal is what makes the difference observable.
         */
        const val OVER_THE_SCAN_BOUND_SIDE: Int = 4097

        /** A side whose square is over two billion pixels: more than any page's raster could hold. */
        const val ABSURD_SIDE: Int = 46_000

        /** How many pictures the multi-frame artifact holds, and how big each of them is. */
        const val MULTI_FRAME_COUNT: Int = 2
        const val MULTI_FRAME_WIDTH: Int = 120
        const val MULTI_FRAME_HEIGHT: Int = 80

        /** Every channel of the white a page of paper has. */
        const val WHITE_PIXEL: Int = 0xFFFFFF

        /** A PNG chunk's type as its four ASCII bytes, and its length as a four-byte field. */
        const val PNG_TYPE_BYTES: Int = 4
        const val LENGTH_FIELD_BYTES: Int = 4

        /** An IHDR chunk: its four-byte type and the thirteen bytes of data every PNG starts with. */
        const val IHDR_CHUNK_BYTES: Int = 17
    }
}

/**
 * A real SQLite archive, because the rules under test are about what a store reads back.
 *
 * An OCR attempt's reuse answer and its candidate staging are answers a *store* gives, so a test that
 * compared values in memory would assert nothing about them: this is the smallest archive that can hold a
 * collection, a document, committed page text and a published revision.
 */
private class Archive(directory: Path) : AutoCloseable {

    val database: Database = Database(directory.resolve("infoscry.db"))

    val collections: CollectionStore = CollectionStore(database)

    val documents: DocumentStore = DocumentStore(database)

    val content: ContentStore = ContentStore(database)

    val revisions: DocumentRevisionStore = DocumentRevisionStore(database, content)

    val artifacts: Path = directory.resolve("artifacts")

    init {
        SchemaMigrator(database).migrate()
    }

    /** A document of this archive that a test can commit pages for, in a collection of its own. */
    fun document(name: String = "rescan.pdf", collection: String = "Rescan"): Document {
        val owner = collections.create(collection)
        return documents.insert(
            Document(
                id = DocumentId("doc-$name"),
                collectionId = owner.id,
                sha256 = "b".repeat(64),
                mediaType = "application/pdf",
                originalFilename = name,
                sourcePath = "/tmp/$name",
                sizeBytes = 42,
                status = DocumentStatus.COMPLETE,
                createdAt = Instants.now(),
                updatedAt = Instants.now(),
            ),
        )
    }

    override fun close() {
        database.close()
    }
}

/**
 * The injected engine: it records what it was asked to read, and answers what the test tells it to.
 *
 * The page number its `text` answers for is derived the way a PDF page number is: the ordinal plus one, so
 * a test can still say "the reading of page three" about a record that carries the page's ordinal.
 */
private class RecordingEngine(
    override val engine: OcrEngine = OcrEngine.TESSERACT,
    private val text: (Int) -> String = { page -> "the reading of page $page" },
) : PageOcrEngine {

    val pages: MutableList<PageImage> = mutableListOf()

    val settings: MutableList<OcrSettingsSnapshot> = mutableListOf()

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        pages += page
        this.settings += settings
        return OcrPageResult(
            text = text(page.ordinal + 1),
            engine = engine,
            imageSha256 = page.sha256,
            meanConfidence = 0.9,
        )
    }
}
