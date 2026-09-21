package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.fixtures.EbookFixtureGenerator
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * E-books: what a book's structure becomes, and what happens when the book is hostile.
 *
 * The formats here are containers of markup a stranger wrote, wrapped in a zip by a tool nobody in this
 * pipeline controls. Three separate things are therefore under test, and they fail in different ways:
 *
 * - **Structure.** A chapter is not a page: what a citation can honestly point at is the section a reader
 *   would click through, named by the navigation document rather than by a file name. A footnote belongs to
 *   the section that carries it, and a chapter with a nested heading is two citable sections.
 * - **A picture-only chapter.** Text a person can search does not exist in the markup, so the chapter has to
 *   reach OCR instead of being indexed as an empty section — through the same tool, languages and artifact
 *   layout a scanned page uses.
 * - **Hostility.** A book can declare itself encrypted, can name a file outside the directory it is
 *   unpacked into, and can be a Kindle file only an optional external converter can read. Each is refused
 *   with a code a person can act on, and nothing is ever written outside the artifact root.
 *
 * The tool and the converter are scripts here rather than installed programs: which arguments are passed,
 * what happens when the tool is missing or reports DRM, and whether a resumed attempt pays for work it
 * already has are properties of this code, and a test that needed Calibre installed would fail for reasons
 * that have nothing to do with it.
 */
class EbookExtractorsTest {

    private lateinit var directory: Path
    private lateinit var artifactRoot: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-ebooks")
        artifactRoot = directory.resolve("artifacts")
        Files.createDirectories(artifactRoot)
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    // ---- What a book becomes -----------------------------------------------------------------------

    @Test
    fun `the package's own metadata reaches the finished document`() = runBlocking {
        val probe = probe()

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(managedEpub(), probe), probe)

        val metadata = (events.last() as ExtractionEvent.Finished).metadata
        assertEquals(EbookFixtureGenerator.TITLE, metadata["title"])
        assertEquals(EbookFixtureGenerator.AUTHOR, metadata["author"])
        assertEquals(EbookFixtureGenerator.PUBLISHER, metadata["publisher"])
        assertEquals(EbookFixtureGenerator.LANGUAGE, metadata["language"])
        assertEquals(EbookFixtureGenerator.IDENTIFIER, metadata["identifier"])
        assertEquals("epub", metadata[EXTRACTOR_METADATA])
        assertEquals("3", metadata[EpubExtractor.SPINE_ITEMS_METADATA])
    }

    @Test
    fun `chapters follow the spine and carry the navigation title a reader would click`() = runBlocking {
        val probe = probe()

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(managedEpub(), probe), probe)

        val locations = textSections(events)
        assertEquals(
            listOf(
                EbookFixtureGenerator.FIRST_CHAPTER,
                EbookFixtureGenerator.SECOND_CHAPTER,
                EbookFixtureGenerator.SECOND_CHAPTER,
            ),
            locations.map { it.chapter },
            "the navigation document names the chapters, and a citation has to use those names",
        )
        assertEquals(
            listOf(0, 1, 1),
            locations.map { it.spineIndex },
            "the spine index is zero-based and names the chapter in reading order",
        )
        assertEquals(
            EbookFixtureGenerator.THIRD_CHAPTER,
            (pictureUnit(events).unit.locator as SourceLocation.EbookSection).chapter,
            "the picture-only chapter is named by the navigation document too",
        )
    }

    @Test
    fun `a chapter with a nested heading becomes two citable sections`() = runBlocking {
        val probe = probe()

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(managedEpub(), probe), probe)

        assertEquals(
            listOf(
                listOf(EbookFixtureGenerator.FIRST_CHAPTER),
                listOf(EbookFixtureGenerator.SECOND_CHAPTER),
                listOf(EbookFixtureGenerator.SECOND_CHAPTER, EbookFixtureGenerator.SECOND_SECTION),
            ),
            textSections(events).map { it.headingPath },
        )
        assertContains(
            units(events).map { it.unit.extractedText }.joinToString("\n"),
            EbookFixtureGenerator.SECOND_SECTION_SENTENCE,
        )
    }

    @Test
    fun `a footnote belongs to the section that carries it`() = runBlocking {
        val probe = probe()

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(managedEpub(), probe), probe)

        val first = units(events).first()
        assertContains(first.unit.extractedText, EbookFixtureGenerator.FIRST_CHAPTER_SENTENCE)
        assertTrue(
            first.unit.extractedText.contains(EbookFixtureGenerator.FOOTNOTE_TEXT),
            "the footnote is part of the section a reader would cite",
        )
        assertEquals("1", (events.last() as ExtractionEvent.Finished).metadata[EpubExtractor.FOOTNOTES_METADATA])
    }

    @Test
    fun `ordinals and keys run over the whole book in reading order`() = runBlocking {
        val probe = probe()

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(managedEpub(), probe), probe)

        assertEquals(listOf(0, 1, 2, 3), units(events).map { it.ordinal })
        assertEquals(
            listOf("ebook:s000:000", "ebook:s001:000", "ebook:s001:001", "ebook:s002:image"),
            units(events).map { it.key },
            "a resumed attempt recognises these keys, so they have to be stable and per-book",
        )
        assertEquals(4, (events.last() as ExtractionEvent.Finished).totalUnits)
    }

    @Test
    fun `pictures in a chapter with text are counted rather than read`() = runBlocking {
        val spy = EbookOcrSpy()
        val probe = probe()

        val events = collect(EpubExtractor(spy.seam), inputFor(managedEpub(), probe), probe)

        assertEquals("2", (events.last() as ExtractionEvent.Finished).metadata[EpubExtractor.IMAGES_METADATA])
        assertEquals(
            1,
            spy.readings.size,
            "only the picture-only chapter is read; the plate inside a chapter with text is counted instead",
        )
        assertEquals(
            2,
            spy.pages.single().page,
            "the picture is numbered by its place in the book, not by how many readings this attempt made",
        )
    }

    // ---- A chapter that is only a picture ----------------------------------------------------------

    @Test
    fun `a chapter with no text of its own is read with the tool`() = runBlocking {
        val spy = EbookOcrSpy(text = "Faksimilet är daterat i mars")
        val probe = probe()

        val events = collect(EpubExtractor(spy.seam), inputFor(managedEpub(), probe), probe)

        val reading = spy.readings.single()
        val page = reading.page
        assertEquals("swe+eng", page.ocrLanguages)
        assertEquals(fingerprint(), page.fingerprint)
        assertEquals(artifactRoot, page.artifactRoot)
        assertNull(
            page.renderDpi,
            "the picture is the source, not a rendering this pipeline chose a resolution for",
        )
        assertTrue(reading.pictureExisted, "the picture was never written where the tool could read it")

        val read = pictureUnit(events)
        assertEquals("Faksimilet är daterat i mars", read.unit.extractedText)
        assertContains(units(events).map { it.key }, "ebook:s002:image")
    }

    @Test
    fun `the reading's artifact and confidence travel with the unit`() = runBlocking {
        val spy = EbookOcrSpy(text = "Faksimilet", confidence = 0.88, artifact = true)
        val probe = probe()

        val events = collect(EpubExtractor(spy.seam), inputFor(managedEpub(), probe), probe)

        val read = pictureUnit(events)
        assertEquals(0.88, read.unit.meanConfidence)
        assertEquals("${fingerprint().value}/ocr/page-000001.tsv.gz", read.unit.artifactRelativePath)
        assertEquals("a".repeat(64), read.unit.artifactSha256)
        val metadata = (events.last() as ExtractionEvent.Finished).metadata
        assertEquals("1", metadata[ImageExtractor.OCR_PAGES_METADATA])
        assertContains(metadata.getValue(ImageExtractor.OCR_MEAN_CONFIDENCE_METADATA), "0.88")
    }

    @Test
    fun `a book with no tool installed is refused once rather than page by page`() = runBlocking {
        val spy = EbookOcrSpy(unavailable = TesseractOcr.NEEDS_TESSERACT_CODE)
        val probe = probe()

        val events = collect(EpubExtractor(spy.seam), inputFor(managedEpub(), probe), probe)

        // The two chapters that have their own text are still delivered: a missing tool is not a broken
        // document, it is a document this machine cannot read the pictures of.
        assertEquals(3, units(events).size)
        assertEquals(TesseractOcr.NEEDS_TESSERACT_CODE, failures(events).last().code)
        assertTrue(events.none { it is ExtractionEvent.Finished }, "a refused document is not a finished one")
    }

    @Test
    fun `a book whose sections are all committed does not ask the tool again`() = runBlocking {
        val spy = EbookOcrSpy()
        val probe = probe()
        val committed = setOf("ebook:s000:000", "ebook:s001:000", "ebook:s001:001", "ebook:s002:image")

        val events = collect(
            EpubExtractor(spy.seam),
            inputFor(managedEpub(), probe, committed = committed),
            probe,
        )

        assertTrue(units(events).isEmpty(), "a committed section was produced again")
        assertTrue(spy.pages.isEmpty(), "a committed picture-only chapter was read again")
        assertEquals(4, (events.last() as ExtractionEvent.Finished).totalUnits)
    }

    @Test
    fun `the tool is asked inside the permit the unit is committed under`() = runBlocking {
        val probe = probe()
        val spy = EbookOcrSpy(insidePermit = probe)

        collect(EpubExtractor(spy.seam), inputFor(managedEpub(), probe), probe)

        assertTrue(spy.pages.isNotEmpty())
        assertTrue(
            probe.eventsOutsidePermit.isEmpty(),
            "an event reached the collector outside a permit: ${probe.eventsOutsidePermit}",
        )
    }

    // ---- Hostile books -----------------------------------------------------------------------------

    @Test
    fun `a book that declares itself encrypted is refused as encrypted`() = runBlocking {
        val probe = probe()
        val book = managedEpub(adding = { zip -> zip.encryptionXml(PROTECTED_DECLARATION) })

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(book, probe), probe)

        assertTrue(units(events).isEmpty(), "an encrypted book produced a citable unit")
        val refusal = failures(events).single()
        assertEquals(ENCRYPTED_DOCUMENT_CODE, refusal.code)
        assertEquals(DOCUMENT_REFUSED_KEY, refusal.key)
    }

    @Test
    fun `a book whose only encrypted entries are obfuscated fonts is still read`() = runBlocking {
        val probe = probe()
        val book = managedEpub(adding = { zip -> zip.encryptionXml(FONT_OBFUSCATION_DECLARATION) })

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(book, probe), probe)

        assertEquals(
            4,
            units(events).size,
            "a declaration that exists only to satisfy a font licence is not a book nobody can read",
        )
        assertIs<ExtractionEvent.Finished>(events.last())
    }

    @Test
    fun `a book whose package document is missing is refused as unreadable`() = runBlocking {
        val probe = probe()
        val book = managedEpub(without = "OEBPS/content.opf")

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(book, probe), probe)

        assertEquals(DOCUMENT_UNREADABLE_CODE, failures(events).single().code)
    }

    @Test
    fun `a book whose package document is not xml is refused as unreadable`() = runBlocking {
        val probe = probe()
        val book = managedEpub(replacing = "OEBPS/content.opf" to "<package><manifest>")

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(book, probe), probe)

        assertEquals(DOCUMENT_UNREADABLE_CODE, failures(events).single().code)
    }

    @Test
    fun `a book whose package document names a missing chapter is refused as unreadable`() = runBlocking {
        val probe = probe()
        val book = managedEpub(without = "OEBPS/chapter1.xhtml")

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(book, probe), probe)

        assertEquals(DOCUMENT_UNREADABLE_CODE, failures(events).single().code)
    }

    @Test
    fun `a book with an entry that walks out of the root is refused and writes nothing`() = runBlocking {
        val probe = probe()
        val book = managedEpub(adding = { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt"))
            zip.write("hostile".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        })

        val events = collect(EpubExtractor(EbookOcrSpy().seam), inputFor(book, probe), probe)

        assertEquals(SafeArchive.ARCHIVE_UNSAFE_CODE, failures(events).single().code)
        val strays = Files.walk(directory).use { paths ->
            paths.filter { it.fileName.toString() == "escape.txt" }.toList()
        }
        assertEquals(emptyList(), strays, "an escaping entry reached the filesystem")
    }

    @Test
    fun `an oversized book is refused before anything is read`() = runBlocking {
        val probe = probe()

        val events = collect(
            EpubExtractor(EbookOcrSpy().seam, maxDocumentBytes = 16),
            inputFor(managedEpub(), probe),
            probe,
        )

        assertEquals(DOCUMENT_TOO_LARGE_CODE, failures(events).single().code)
    }

    // ---- The other family --------------------------------------------------------------------------

    @Test
    fun `a fictionbook's sections and metadata are read`() = runBlocking {
        val probe = probe()

        val events = collect(Fb2Extractor(), inputFor(managedFb2(), probe), probe)

        val metadata = (events.last() as ExtractionEvent.Finished).metadata
        assertEquals(EbookFixtureGenerator.TITLE, metadata["title"])
        assertEquals("Anna Lind", metadata["author"])
        assertEquals(EbookFixtureGenerator.LANGUAGE, metadata["language"])
        assertEquals(EbookFixtureGenerator.IDENTIFIER, metadata["identifier"])
        assertEquals(
            listOf(
                listOf(EbookFixtureGenerator.FIRST_CHAPTER),
                listOf(EbookFixtureGenerator.FIRST_CHAPTER, EbookFixtureGenerator.SECOND_SECTION),
            ),
            sections(events).map { it.headingPath },
        )
        assertEquals(listOf(0, 0), sections(events).map { it.spineIndex })
        assertContains(units(events).first().unit.extractedText, EbookFixtureGenerator.FB2_SENTENCE)
        assertContains(units(events).last().unit.extractedText, EbookFixtureGenerator.SECOND_SECTION_SENTENCE)
    }

    @Test
    fun `a zipped fictionbook is read through the guarded container`() = runBlocking {
        val probe = probe()
        val zipped = directory.resolve("managed").resolve("sample.fbz")
        Files.createDirectories(zipped.parent)
        ZipOutputStream(Files.newOutputStream(zipped)).use { zip ->
            zip.putNextEntry(ZipEntry("book/sample.fb2"))
            zip.write(Files.readAllBytes(managedFb2()))
            zip.closeEntry()
        }

        val events = collect(Fb2Extractor(), inputFor(zipped, probe), probe)

        assertEquals(2, units(events).size)
        assertEquals(
            EbookFixtureGenerator.TITLE,
            (events.last() as ExtractionEvent.Finished).metadata["title"],
        )
    }

    // ---- How the registry wires the two entrances ---------------------------------------------------

    @Test
    fun `a converted book is decided by the same reader as a native one`() = runBlocking {
        val probe = probe()
        val registry = ExtractorRegistry.production(
            tesseract = TesseractOcr(executable = readingTool().toString()),
            calibre = CalibreConverter(executable = fakeConverter(directory.resolve("args.txt")).toString()),
        )

        val native = collect(
            registry.extract(inputFor(managedEpub(name = "native.epub"), probe), EPUB_MEDIA_TYPE),
            probe,
        )
        val converted = collect(
            registry.extract(
                inputFor(managedEpub(name = "kindle.azw3"), probe),
                MOBIPOCKET_MEDIA_TYPE,
            ),
            probe,
        )

        assertEquals(
            units(native).map { it.key },
            units(converted).map { it.key },
            "the converted book's chapters were decided by a different reader than the native book's",
        )
        assertEquals(4, units(converted).size)
    }

    /** A stand-in Tesseract that reports one word per page, so a picture-only chapter has a reading. */
    private fun readingTool(): Path = writeFakeExecutable(
        directory,
        "tesseract",
        """
        cat <<'TSV'
        1	1	0	0	0	0	0	0	900	240	-1	
        5	1	1	1	1	1	44	52	214	41	94	Faksimilet
        TSV
        """.trimIndent(),
    )

    // ---- The optional converter --------------------------------------------------------------------

    @Test
    fun `a kindle file is converted with the configured tool and then read`() = runBlocking {
        val probe = probe()
        val args = directory.resolve("converter-args.txt")
        val converter = CalibreConverter(executable = fakeConverter(args).toString())
        val book = managedEpub(name = "kindle.azw3")

        val events = collect(
            CalibreBackedEbookExtractor(converter, EpubExtractor(EbookOcrSpy().seam)),
            inputFor(book, probe),
            probe,
        )

        val passed = Files.readAllLines(args)
        assertEquals(book.toString(), passed[0], "the managed original is what the converter is given")
        assertEquals(expectedConvertedBook().toString(), passed[1])
        assertEquals(
            SourceLocation.EbookSection(
                EbookFixtureGenerator.FIRST_CHAPTER,
                listOf(EbookFixtureGenerator.FIRST_CHAPTER),
                0,
            ),
            units(events).first().unit.locator,
            "the converted book is cited by the structure it was converted into",
        )
    }

    @Test
    fun `a converted book carries the original job's fingerprint into its readings`() = runBlocking {
        val probe = probe()
        val spy = EbookOcrSpy()
        val converter = CalibreConverter(executable = fakeConverter(directory.resolve("args.txt")).toString())

        collect(
            CalibreBackedEbookExtractor(converter, EpubExtractor(spy.seam)),
            inputFor(managedEpub(name = "kindle.mobi"), probe),
            probe,
        )

        val page = spy.pages.single()
        assertEquals(fingerprint(), page.fingerprint, "the converted book was read under another fingerprint")
        assertEquals(artifactRoot, page.artifactRoot, "the converted book wrote its artifacts elsewhere")
    }

    @Test
    fun `the conversion leaves a bounded log beside the converted book`() = runBlocking {
        val probe = probe()
        val converter = CalibreConverter(executable = fakeConverter(directory.resolve("args.txt")).toString())

        collect(
            CalibreBackedEbookExtractor(converter, EpubExtractor(EbookOcrSpy().seam)),
            inputFor(managedEpub(name = "kindle.mobi"), probe),
            probe,
        )

        val log = expectedConvertedBook().resolveSibling(CalibreConverter.CONVERSION_LOG_NAME)
        assertTrue(Files.isRegularFile(log), "the conversion's own output was not kept")
        assertContains(Files.readString(log), "converted")
        assertTrue(
            Files.size(log) <= CalibreConverter.MAX_CONVERSION_LOG_BYTES,
            "the conversion log is unbounded",
        )
    }

    @Test
    fun `a converter that floods its output cannot fill the disk`() = runBlocking {
        val probe = probe()
        val noisy = writeFakeExecutable(
            directory,
            "ebook-convert-noisy",
            "yes 'conversion chatter' | head -n 400000\ncp \"\$1\" \"\$2\"",
        )
        val converter = CalibreConverter(executable = noisy.toString())

        collect(
            CalibreBackedEbookExtractor(converter, EpubExtractor(EbookOcrSpy().seam)),
            inputFor(managedEpub(name = "kindle.mobi"), probe),
            probe,
        )

        val log = expectedConvertedBook().resolveSibling(CalibreConverter.CONVERSION_LOG_NAME)
        assertTrue(
            Files.size(log) <= CalibreConverter.MAX_CONVERSION_LOG_BYTES,
            "the conversion log grew past its bound (${Files.size(log)} bytes)",
        )
    }

    @Test
    fun `an existing conversion is reused rather than run again`() = runBlocking {
        val probe = probe()
        val invocations = directory.resolve("invocations.txt")
        val converter = CalibreConverter(
            executable = fakeConverter(directory.resolve("args.txt"), invocations).toString(),
        )
        val extractor = CalibreBackedEbookExtractor(converter, EpubExtractor(EbookOcrSpy().seam))
        val input = inputFor(managedEpub(name = "kindle.azw3"), probe)

        val first = collect(extractor, input, probe)
        val second = collect(extractor, input, probe)

        assertEquals(1, Files.readAllLines(invocations).size, "the converter ran twice for one book")
        assertEquals(units(first).size, units(second).size)
        assertTrue(units(second).isNotEmpty())
    }

    @Test
    fun `a missing converter refuses the document and writes nothing outside the root`() = runBlocking {
        val probe = probe()
        val converter = CalibreConverter(executable = directory.resolve("no-such-ebook-convert").toString())

        val events = collect(
            CalibreBackedEbookExtractor(converter, EpubExtractor(EbookOcrSpy().seam)),
            inputFor(managedEpub(name = "kindle.azw3"), probe),
            probe,
        )

        val refusal = failures(events).single()
        assertEquals(CalibreConverter.NEEDS_CALIBRE_CODE, refusal.code)
        assertTrue(
            Files.walk(artifactRoot).use { paths -> paths.noneMatch { Files.isRegularFile(it) } },
            "a refused conversion wrote something into the artifact root",
        )
    }

    @Test
    fun `a book the converter reports as protected is refused as encrypted`() = runBlocking {
        val probe = probe()
        val converter = CalibreConverter(
            executable = refusingConverter("This book has DRM protection", 1).toString(),
        )

        val events = collect(
            CalibreBackedEbookExtractor(converter, EpubExtractor(EbookOcrSpy().seam)),
            inputFor(managedEpub(name = "kindle.azw3"), probe),
            probe,
        )

        assertEquals(ENCRYPTED_DOCUMENT_CODE, failures(events).single().code)
    }

    @Test
    fun `a conversion that fails for another reason is refused as unreadable`() = runBlocking {
        val probe = probe()
        val converter = CalibreConverter(
            executable = refusingConverter("cannot parse the container", 2).toString(),
        )

        val events = collect(
            CalibreBackedEbookExtractor(converter, EpubExtractor(EbookOcrSpy().seam)),
            inputFor(managedEpub(name = "kindle.azw3"), probe),
            probe,
        )

        assertEquals(DOCUMENT_UNREADABLE_CODE, failures(events).single().code)
    }

    @Test
    fun `a converter that reports success without writing the book is refused as unreadable`() = runBlocking {
        val probe = probe()
        val converter = CalibreConverter(
            executable = writeFakeExecutable(directory, "ebook-convert-silent", "exit 0").toString(),
        )

        val events = collect(
            CalibreBackedEbookExtractor(converter, EpubExtractor(EbookOcrSpy().seam)),
            inputFor(managedEpub(name = "kindle.azw3"), probe),
            probe,
        )

        assertEquals(DOCUMENT_UNREADABLE_CODE, failures(events).single().code)
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private fun units(events: List<ExtractionEvent>): List<ExtractionEvent.UnitReady> =
        events.filterIsInstance<ExtractionEvent.UnitReady>()

    private fun failures(events: List<ExtractionEvent>): List<ExtractionEvent.UnitFailed> =
        events.filterIsInstance<ExtractionEvent.UnitFailed>()

    private fun sections(events: List<ExtractionEvent>): List<SourceLocation.EbookSection> =
        units(events).map { it.unit.locator }.filterIsInstance<SourceLocation.EbookSection>()

    /**
     * The units that came from a chapter's markup, in reading order.
     *
     * A picture-only chapter produces a unit too — the chapter is a place in the book whoever reads it — so
     * a test about headings and chapter names has to say which of the two it means.
     */
    private fun textSections(events: List<ExtractionEvent>): List<SourceLocation.EbookSection> =
        sections(events).filter { it.headingPath.isNotEmpty() }

    /** The unit a picture-only chapter produced, if it produced one. */
    private fun pictureUnit(events: List<ExtractionEvent>): ExtractionEvent.UnitReady =
        units(events).single { it.unit.locator == SourceLocation.EbookSection(EbookFixtureGenerator.THIRD_CHAPTER, emptyList(), 2) }

    private fun collect(
        extractor: DocumentExtractor,
        input: ExtractionInput,
        probe: PermitProbeBoundary,
    ): List<ExtractionEvent> = collect(extractor.extract(input), probe)

    private fun collect(
        events: kotlinx.coroutines.flow.Flow<ExtractionEvent>,
        probe: PermitProbeBoundary,
    ): List<ExtractionEvent> = runBlocking {
        events.onEach { probe.observed(it) }.toList()
    }

    private fun probe(): PermitProbeBoundary = PermitProbeBoundary()

    /** Where the converted book of the current fingerprint belongs, as the converter computes it. */
    private fun expectedConvertedBook(): Path = artifactRoot.resolve(
        "${fingerprint().value}/${CalibreConverter.ARTIFACT_DIRECTORY}/${CalibreConverter.ARTIFACT_NAME}",
    )

    /**
     * Copies the committed EPUB fixture into the managed area, editing it on the way.
     *
     * The hostile cases are edits of a book that works: an encryption declaration added, the package
     * document removed or replaced with something that is not XML. That way a refusal is evidence about the
     * one thing that was changed rather than about a fixture that was never readable in the first place.
     */
    private fun managedEpub(
        name: String = EbookFixtureGenerator.EPUB_NAME,
        without: String? = null,
        replacing: Pair<String, String>? = null,
        adding: ((ZipOutputStream) -> Unit)? = null,
    ): Path {
        val target = directory.resolve("managed").resolve(name)
        Files.createDirectories(target.parent)
        fixtureZip().use { source ->
            ZipOutputStream(Files.newOutputStream(target)).use { zip ->
                source.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { it.name != without && it.name != replacing?.first }
                    .forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(source.getInputStream(entry).use { it.readBytes() })
                        zip.closeEntry()
                    }
                replacing?.let { (entryName, content) ->
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(content.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
                adding?.invoke(zip)
            }
        }
        return target
    }

    private fun fixtureZip(): ZipFile = ZipFile(
        requireNotNull(javaClass.getResource("/fixtures/${EbookFixtureGenerator.EPUB_NAME}")) {
            "fixture /fixtures/${EbookFixtureGenerator.EPUB_NAME} is missing"
        }.toURI().let(Path::of).toFile(),
    )

    /** Adds a `META-INF/encryption.xml` to the book being built. */
    private fun ZipOutputStream.encryptionXml(declaration: String) {
        putNextEntry(ZipEntry("META-INF/encryption.xml"))
        write(declaration.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    /** Copies the committed FB2 fixture into the managed area. */
    private fun managedFb2(): Path {
        val target = directory.resolve("managed").resolve(EbookFixtureGenerator.FB2_NAME)
        Files.createDirectories(target.parent)
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/${EbookFixtureGenerator.FB2_NAME}")) {
            "fixture /fixtures/${EbookFixtureGenerator.FB2_NAME} is missing"
        }
        stream.use { Files.copy(it, target, REPLACE_EXISTING) }
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

    /**
     * A stand-in `ebook-convert`: it records the arguments it was given and copies the book through.
     *
     * The real converter reads a Kindle container this suite cannot author and writes an EPUB whose bytes
     * depend on its own version, so the properties under test — which arguments are passed, that the input
     * is the managed original, and that a converted book is reused — are produced deliberately instead.
     */
    private fun fakeConverter(argsFile: Path, invocations: Path? = null): Path = writeFakeExecutable(
        directory,
        "ebook-convert",
        buildString {
            appendLine("echo \"\$1\" > '$argsFile'")
            appendLine("echo \"\$2\" >> '$argsFile'")
            invocations?.let { appendLine("echo run >> '$it'") }
            appendLine("echo \"converted \$1\"")
            appendLine("cp \"\$1\" \"\$2\"")
        },
    )

    /** The declarations a book can carry in `META-INF/encryption.xml`. */
    private companion object {

        /**
         * A book whose *content* is encrypted: the encryption method is not the font one, which is what
         * DRM looks like from this reader.
         */
        val PROTECTED_DECLARATION: String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
                <enc:CipherData><enc:CipherReference URI="OEBPS/chapter1.xhtml"/></enc:CipherData>
              </enc:EncryptedData>
            </encryption>
        """.trimIndent()

        /**
         * A book whose only encrypted entries are its embedded fonts: the standard obfuscation algorithm,
         * which exists to satisfy a font licence and leaves the text readable.
         */
        val FONT_OBFUSCATION_DECLARATION: String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                <enc:CipherData><enc:CipherReference URI="OEBPS/fonts/serif.otf"/></enc:CipherData>
              </enc:EncryptedData>
            </encryption>
        """.trimIndent()
    }

    /** A stand-in converter that fails with [message] and [status]. */
    private fun refusingConverter(message: String, status: Int): Path = writeFakeExecutable(
        directory,
        "ebook-convert-refusing-$status",
        "echo '$message' >&2\nexit $status",
    )

    /**
     * The injected OCR seam, recording what it was asked to read.
     *
     * [insidePermit] is the probe the seam checks before recording a page: the property that matters is not
     * only that the tool was called, but that it was called while the collector held the unit's permit — a
     * picture read outside it could be committed against a unit a deletion had already removed.
     */
    private class EbookOcrSpy(
        private val text: String = "Faksimilet",
        private val confidence: Double? = 0.9,
        private val artifact: Boolean = false,
        private val unavailable: String? = null,
        private val insidePermit: PermitProbeBoundary? = null,
    ) {

        /** One call to the tool: the page it was given and what was true about it at that moment. */
        data class Reading(val page: RenderedPage, val pictureExisted: Boolean)

        val readings: MutableList<Reading> = mutableListOf()

        val pages: List<RenderedPage> get() = readings.map { reading -> reading.page }

        val seam: suspend (RenderedPage) -> OcrResult = { page ->
            insidePermit?.let { probe ->
                check(probe.openPermits > 0) { "the tool was asked to read a page outside a permit" }
            }
            // The existence of the file is recorded here rather than after the attempt, because the private
            // directory the picture is written into is removed when the attempt ends: what matters is that
            // the tool could read a file at the moment it was called.
            readings += Reading(page = page, pictureExisted = java.nio.file.Files.isRegularFile(page.imagePath))
            unavailable?.let { code -> throw OcrUnavailableException(code, "no OCR tool in this build") }
            OcrResult(
                text = text,
                meanConfidence = confidence,
                artifactRelativePath = if (artifact) "${page.fingerprint.value}/ocr/page-000001.tsv.gz" else null,
                artifactSha256 = if (artifact) "a".repeat(64) else null,
            )
        }
    }
}
