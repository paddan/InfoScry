package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * The Word, spreadsheet, and presentation extractors, on both container families.
 *
 * These tests run against committed fixtures rather than hand-built objects, because what a citation
 * promises is that it points at the same paragraphs, the same cells, and the same slide next time — and
 * the places that promise breaks are exactly the ones a hand-built model hides: a table whose rows end
 * with a marker paragraph, a cached formula result, a heading whose style a converter flattened.
 *
 * The two families are not treated as interchangeable. The OOXML fixtures carry heading styles because
 * the generator writes them; the committed `.doc` was produced by a converter that flattens every
 * paragraph to `Normal`, so it is asserted as what it is: one section whose heading path is empty. The
 * heading *rule* is tested directly against the style names Word writes, which is the part a styled
 * legacy document would exercise. The legacy notes path is treated the same way: POI 5.5.1 cannot give a
 * slide a notes slide, so [notesText] is tested directly and the committed `.ppt` carries no notes.
 */
class OfficeExtractorsTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-office-extractors")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `docx cites each heading section with the paragraphs it covers`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(WordExtractor(OfficeFormat.OOXML), inputFor("sample.docx", probe), probe))

        assertEquals(
            listOf(
                SourceLocation.WordSection(headingPath = listOf("Inledning"), paragraphStart = 1, paragraphEnd = 2),
                SourceLocation.WordSection(
                    headingPath = listOf("Inledning", "Bilaga"),
                    paragraphStart = 3,
                    paragraphEnd = 7,
                ),
            ),
            units.map { it.unit.locator },
        )
        assertEquals(listOf(0, 1), units.map { it.ordinal })
        assertEquals(listOf("section:0", "section:1"), units.map { it.key })
    }

    @Test
    fun `docx keeps a table's cells in reading order inside the section that holds it`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(WordExtractor(OfficeFormat.OOXML), inputFor("sample.docx", probe), probe))

        val section = units.last().unit
        assertContains(section.extractedText, "Bilaga")
        assertFalse(
            section.extractedText.contains("Inledning"),
            "an ancestor heading is the locator's job, not text repeated in every descendant section",
        )
        assertContains(section.extractedText, "Mottagare | Belopp")
        assertContains(section.extractedText, "Anna Öberg | 1200")
        assertTrue(
            section.extractedText.indexOf("Mottagare") < section.extractedText.indexOf("Anna Öberg"),
            "the header row must read before the row it labels",
        )
    }

    @Test
    fun `doc reads a legacy document's paragraphs and table rows in order`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(WordExtractor(OfficeFormat.LEGACY), inputFor("sample.doc", probe), probe))

        val unit = units.single()
        assertEquals(
            SourceLocation.WordSection(headingPath = emptyList(), paragraphStart = 1, paragraphEnd = 7),
            unit.unit.locator,
            "the committed .doc has no heading styles left, so it is one section over its paragraphs",
        )
        assertContains(unit.unit.extractedText, "Inledning")
        assertContains(unit.unit.extractedText, "Rapporten sammanfattar överföringarna i ärendet.")
        assertContains(unit.unit.extractedText, "Mottagare | Belopp")
        assertContains(unit.unit.extractedText, "Anna Öberg | 1200")
    }

    @Test
    fun `a paragraph range counts the paragraphs a reader sees and not a row marker`() {
        val probe = PermitProbeBoundary()
        val units = units(collect(WordExtractor(OfficeFormat.LEGACY), inputFor("sample.doc", probe), probe))

        val text = units.single().unit.extractedText
        assertEquals(2, text.lines().count { it == "Mottagare | Belopp" || it == "Anna Öberg | 1200" })
        assertEquals(
            7,
            units.single().unit.locator.let { (it as SourceLocation.WordSection).paragraphEnd },
            "the two empty paragraphs that close the legacy table rows are not paragraphs a reader sees",
        )
    }

    @Test
    fun `a heading style name or an outline level names the same level`() {
        assertEquals(1, WordHeadingStyles.levelOf(styleId = "Heading1", styleName = null, outlineLevel = null))
        assertEquals(1, WordHeadingStyles.levelOf(styleId = null, styleName = "heading 1", outlineLevel = null))
        assertEquals(2, WordHeadingStyles.levelOf(styleId = null, styleName = "Rubrik 2", outlineLevel = null))
        assertEquals(3, WordHeadingStyles.levelOf(styleId = null, styleName = null, outlineLevel = 2))
        assertEquals(3, WordHeadingStyles.levelOf(styleId = "Überschrift3", styleName = null, outlineLevel = null))
        assertNull(WordHeadingStyles.levelOf(styleId = "Normal", styleName = "Normal", outlineLevel = null))
        assertNull(WordHeadingStyles.levelOf(styleId = null, styleName = null, outlineLevel = 9))
        assertNull(WordHeadingStyles.levelOf(styleId = "Heading", styleName = "Heading", outlineLevel = null))
    }

    @Test
    fun `xlsx cites each sheet by name and exact A1 bounds`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(SpreadsheetExtractor(OfficeFormat.OOXML), inputFor("sample.xlsx", probe), probe))

        assertEquals(
            listOf(
                SourceLocation.SpreadsheetRange(sheet = "Transfers", startCell = "A2", endCell = "C4"),
                SourceLocation.SpreadsheetRange(sheet = "Sammanfattning", startCell = "A2", endCell = "B2"),
            ),
            units.map { it.unit.locator },
        )
        assertEquals(listOf("rows:Transfers:2-4", "rows:Sammanfattning:2-2"), units.map { it.key })
        assertContains(units.first().unit.extractedText, "2020-01-02,Anna Öberg,1200")
        assertContains(units.first().unit.extractedText, "2020-01-03,Bo Lind,800")
    }

    @Test
    fun `a formula is read from its stored result and never evaluated`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(SpreadsheetExtractor(OfficeFormat.OOXML), inputFor("sample.xlsx", probe), probe))

        val transfers = units.first().unit
        assertContains(transfers.extractedText, "2000", message = "the stored result of SUM(C2:C3) is 2000")
        assertFalse(transfers.extractedText.contains("SUM("), "a formula was not evaluated but printed")
        assertContains(units.last().unit.extractedText, "2000")
        assertFalse(units.last().unit.extractedText.contains("Transfers!"), "a cross-sheet formula was printed")
    }

    @Test
    fun `a spreadsheet unit carries the header above its rows in the searchable text`() {
        val probe = PermitProbeBoundary()

        val unit = units(collect(SpreadsheetExtractor(OfficeFormat.OOXML), inputFor("sample.xlsx", probe), probe))
            .first().unit

        assertContains(unit.searchText, "Datum\tMottagare\tBelopp")
        assertFalse(
            unit.extractedText.contains("Datum"),
            "the evidence text is the rows themselves; the repeated header is the searchable form",
        )
    }

    @Test
    fun `rows are batched and every batch keeps the sheet's row numbers`() {
        val probe = PermitProbeBoundary()

        val units = units(
            collect(
                SpreadsheetExtractor(OfficeFormat.OOXML, rowsPerUnit = 1),
                inputFor("sample.xlsx", probe),
                probe,
            ),
        )

        assertEquals(
            listOf(
                SourceLocation.SpreadsheetRange("Transfers", "A2", "C2"),
                SourceLocation.SpreadsheetRange("Transfers", "A3", "C3"),
                SourceLocation.SpreadsheetRange("Transfers", "A4", "C4"),
                SourceLocation.SpreadsheetRange("Sammanfattning", "A2", "B2"),
            ),
            units.map { it.unit.locator },
        )
    }

    @Test
    fun `units are numbered across the whole workbook and not once per sheet`() {
        val probe = PermitProbeBoundary()

        val units = units(
            collect(
                SpreadsheetExtractor(OfficeFormat.OOXML, rowsPerUnit = 1),
                inputFor("sample.xlsx", probe),
                probe,
            ),
        )

        // Three batches in `Transfers` and one in `Sammanfattning`. An ordinal says where a unit sits in
        // the document, so a second sheet does not restart at zero and collide with the first.
        assertEquals(listOf(0, 1, 2, 3), units.map { it.ordinal })
    }

    @Test
    fun `a row that reaches past the column bound is refused instead of widened`() {
        val probe = PermitProbeBoundary()
        val source = directory.resolve("wide.xlsx")
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Bred")
            sheet.createRow(0).createCell(0).setCellValue("Kolumn")
            sheet.createRow(1).apply {
                createCell(0).setCellValue("första")
                // One stray far-right cell is all it takes: the row is read across every column the file
                // says it has, so this would become a range of more than a thousand columns.
                createCell(MAX_SPREADSHEET_COLUMNS).setCellValue("långt bort")
            }
            Files.newOutputStream(source).use { workbook.write(it) }
        }

        val events = collect(SpreadsheetExtractor(OfficeFormat.OOXML), inputFor(source, probe), probe)

        val failure = events.filterIsInstance<ExtractionEvent.UnitFailed>().single()
        assertEquals(SHEET_RANGE_TOO_WIDE_CODE, failure.code)
        assertEquals("rows:Bred:2-2", failure.key)
        assertTrue(
            units(events).isEmpty(),
            "an over-wide row must be refused, not cut down to a citable range that dropped cells",
        )
    }

    @Test
    fun `xls cites the same sheets and reads the same stored results`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(SpreadsheetExtractor(OfficeFormat.LEGACY), inputFor("sample.xls", probe), probe))

        assertEquals(
            listOf(
                SourceLocation.SpreadsheetRange("Transfers", "A2", "C4"),
                SourceLocation.SpreadsheetRange("Sammanfattning", "A2", "B2"),
            ),
            units.map { it.unit.locator },
        )
        assertContains(units.first().unit.extractedText, "2020-01-02,Anna Öberg,1200")
        assertContains(units.first().unit.extractedText, "2000")
        assertFalse(units.first().unit.extractedText.contains("SUM("))
    }

    @Test
    fun `pptx cites each slide and reads its speaker notes`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(PresentationExtractor(OfficeFormat.OOXML), inputFor("sample.pptx", probe), probe))

        assertEquals(listOf(SourceLocation.Slide(1), SourceLocation.Slide(2)), units.map { it.unit.locator })
        assertEquals(listOf("slide:1", "slide:2"), units.map { it.key })
        assertContains(units.first().unit.extractedText, "Översikt")
        assertContains(units.first().unit.extractedText, "Två överföringar i ärendet.")
        assertContains(units.first().unit.extractedText, "Kom ihåg att citera källan.")
        assertFalse(
            units.first().unit.extractedText.contains("Click to edit Master text styles"),
            "the notes master's prompt is layout, not something anyone wrote about this document",
        )
        assertEquals("Bilaga", units.last().unit.extractedText.trim())
    }

    @Test
    fun `each slide gets a preview artifact whose checksum matches the file`() {
        val probe = PermitProbeBoundary()
        val input = inputFor("sample.pptx", probe)

        val units = units(collect(PresentationExtractor(OfficeFormat.OOXML), input, probe))

        val first = units.first().unit
        assertEquals("preview/slide-000001.png", first.artifactRelativePath)
        val artifact = input.artifactRoot.resolve(first.artifactRelativePath.orEmpty())
        assertTrue(Files.exists(artifact), "the preview a unit names must exist")
        assertTrue(Files.size(artifact) > 0, "an empty preview is not a preview")
        assertEquals(sha256Of(artifact), first.artifactSha256)
        assertTrue(
            readAll(artifact).take(8).toByteArray().contentEquals(PNG_SIGNATURE),
            "a preview must be a PNG",
        )
        assertEquals("preview/slide-000002.png", units.last().unit.artifactRelativePath)
    }

    @Test
    fun `ppt cites the same slides and reads their text and previews`() {
        val probe = PermitProbeBoundary()
        val input = inputFor("sample.ppt", probe)

        val units = units(collect(PresentationExtractor(OfficeFormat.LEGACY), input, probe))

        assertEquals(listOf(SourceLocation.Slide(1), SourceLocation.Slide(2)), units.map { it.unit.locator })
        assertContains(units.first().unit.extractedText, "Översikt")
        assertEquals("Bilaga", units.last().unit.extractedText.trim())
        assertEquals("preview/slide-000001.png", units.first().unit.artifactRelativePath)
        assertTrue(Files.exists(input.artifactRoot.resolve("preview/slide-000001.png")))
    }

    @Test
    fun `notes keep what a person wrote and drop the notes master's own layout`() {
        assertEquals("Kom ihåg att citera källan.", notesText(listOf("Kom ihåg att citera källan."), emptySet()))
        assertEquals("Första\nAndra", notesText(listOf("Första", "Andra"), emptySet()))
        assertNull(
            notesText(listOf("Click to edit Master text styles"), setOf("Click to edit Master text styles")),
            "the notes master's prompt is layout, not something anyone wrote about this document",
        )
        assertNull(notesText(listOf("   ", ""), emptySet()), "a notes shape that says nothing is not notes")
        assertNull(notesText(emptyList(), emptySet()))
    }

    @Test
    fun `a slide whose preview cannot be rendered still delivers its text`() {
        val probe = PermitProbeBoundary()
        val input = inputFor("sample.pptx", probe)
        // A file where the preview directory must be, so the preview cannot be written — the same outcome
        // as a headless machine or a missing font, without depending on either.
        Files.createDirectories(input.artifactRoot)
        Files.writeString(input.artifactRoot.resolve("preview"), "not a directory")
        val extractor = PresentationExtractor(OfficeFormat.OOXML)

        val events = collect(extractor, input, probe)

        assertEquals(2, units(events).size)
        assertContains(units(events).first().unit.extractedText, "Översikt")
        assertNull(units(events).first().unit.artifactRelativePath)
        val finished = events.last() as ExtractionEvent.Finished
        assertEquals("2", finished.metadata["rendering_warnings"], "both slides recorded their failure")
    }

    @Test
    fun `a slide whose page size would not fit a preview keeps its text and records a warning`() {
        val probe = PermitProbeBoundary()
        val source = directory.resolve("huge-slides.pptx")
        XMLSlideShow().use { show ->
            show.pageSize = Dimension(20_000, 20_000)
            show.createSlide().createTextBox().setText("Översikt")
            Files.newOutputStream(source).use { show.write(it) }
        }
        val input = inputFor(source, probe)

        val events = collect(PresentationExtractor(OfficeFormat.OOXML), input, probe)

        val unit = units(events).single()
        assertContains(unit.unit.extractedText, "Översikt")
        assertNull(unit.unit.artifactRelativePath, "a page size past the pixel bound is not rendered")
        assertEquals("1", (events.last() as ExtractionEvent.Finished).metadata["rendering_warnings"])
        assertFalse(Files.exists(input.artifactRoot.resolve("preview/slide-000001.png")))
    }

    @Test
    fun `a document above the office bound is refused without claiming completion`() {
        val probe = PermitProbeBoundary()
        val oversized = directory.resolve("oversized.docx")
        java.io.RandomAccessFile(oversized.toFile(), "rw").use { it.setLength(MAX_OFFICE_DOCUMENT_BYTES + 1) }
        val input = inputFor(oversized, probe)

        val events = collect(WordExtractor(OfficeFormat.OOXML), input, probe)

        assertEquals(1, events.size)
        val failure = events.single() as ExtractionEvent.UnitFailed
        assertEquals(DOCUMENT_TOO_LARGE_CODE, failure.code)
        assertEquals(DOCUMENT_TOO_LARGE_KEY, failure.key)
    }

    @Test
    fun `a container that expands past its own size is refused before any unit is produced`() {
        val probe = PermitProbeBoundary()
        val expanding = directory.resolve("expanding.xlsx")
        writeExpandingContainer(expanding, MAX_OFFICE_EXPANDED_BYTES + 1)
        assertTrue(
            Files.size(expanding) < MAX_OFFICE_DOCUMENT_BYTES,
            "the fixture has to fit on the way in, or it would be refused for its size instead",
        )

        val events = collect(SpreadsheetExtractor(OfficeFormat.OOXML), inputFor(expanding, probe), probe)

        assertEquals(1, events.size)
        val failure = events.single() as ExtractionEvent.UnitFailed
        assertEquals(DOCUMENT_TOO_LARGE_CODE, failure.code)
        assertEquals(DOCUMENT_TOO_LARGE_KEY, failure.key)
    }

    @Test
    fun `a compressed document that expands within the bound is still read`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(SpreadsheetExtractor(OfficeFormat.OOXML), inputFor("sample.xlsx", probe), probe))

        assertEquals(2, units.size, "a committed workbook must not be refused by the expansion bound")
    }

    /**
     * A container that stays small on disk while declaring more uncompressed bytes than [expandedBytes].
     *
     * Nothing in it is a real workbook: what is under test is the measurement, and a few hundred kilobytes
     * of zeros deflate to almost nothing, which is exactly the shape the expansion bound exists for.
     */
    private fun writeExpandingContainer(target: Path, expandedBytes: Long) {
        val zeros = ByteArray(1 shl 20)
        java.util.zip.ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("xl/workbook.xml"))
            var written = 0L
            while (written < expandedBytes) {
                val chunk = minOf(zeros.size.toLong(), expandedBytes - written).toInt()
                zip.write(zeros, 0, chunk)
                written += chunk
            }
            zip.closeEntry()
        }
    }

    @Test
    fun `a refusal that is already durable is not reported again`() {
        val oversized = directory.resolve("oversized.xlsx")
        java.io.RandomAccessFile(oversized.toFile(), "rw").use { it.setLength(MAX_OFFICE_DOCUMENT_BYTES + 1) }
        val probe = PermitProbeBoundary()
        val input = inputFor(oversized, probe, committed = setOf(DOCUMENT_TOO_LARGE_KEY))

        val events = collect(SpreadsheetExtractor(OfficeFormat.OOXML), input, probe)

        assertTrue(events.isEmpty(), "a committed refusal was re-derived and reported a second time")
    }

    @Test
    fun `every office extractor emits each event inside a unit permit`() {
        officeExtractors().forEach { (name, extractor) ->
            val probe = PermitProbeBoundary()
            val events = collect(extractor, inputFor(name, probe), probe)

            assertTrue(
                probe.eventsOutsidePermit.isEmpty(),
                "$name emitted ${probe.eventsOutsidePermit.size} event(s) outside the unit boundary",
            )
            assertEquals(
                units(events).size + 1,
                probe.permits,
                "$name did not take exactly one permit per unit plus one for the finished event",
            )
        }
    }

    @Test
    fun `every office extractor skips committed units on resume and still counts them`() {
        officeExtractors().forEach { (name, extractor) ->
            val probe = PermitProbeBoundary()
            val firstRun = units(collect(extractor, inputFor(name, probe), probe))
            val committed = firstRun.map { it.key }.toSet()

            val resumeProbe = PermitProbeBoundary()
            val resumed = collect(extractor, inputFor(name, resumeProbe, committed), resumeProbe)

            assertTrue(units(resumed).isEmpty(), "$name re-extracted a committed unit")
            assertEquals(
                firstRun.size,
                (resumed.last() as ExtractionEvent.Finished).totalUnits,
                "$name lost the committed units from its total",
            )
        }
    }

    @Test
    fun `every office extractor produces the same citations twice`() {
        officeExtractors().forEach { (name, extractor) ->
            val first = units(collect(extractor, inputFor(name, PermitProbeBoundary()), PermitProbeBoundary()))
            val second = units(collect(extractor, inputFor(name, PermitProbeBoundary()), PermitProbeBoundary()))

            assertEquals(first.map { it.key }, second.map { it.key }, "$name produced unstable keys")
            assertEquals(first.map { it.ordinal }, second.map { it.ordinal }, "$name produced unstable ordinals")
            assertEquals(
                first.map { it.unit.locator },
                second.map { it.unit.locator },
                "$name produced unstable locators",
            )
        }
    }

    @Test
    fun `the production registry routes each office type to its own extractor`() {
        val registry = ExtractorRegistry.production()

        assertEquals(WordExtractor::class, registry.select(DOCX_MEDIA_TYPE)::class)
        assertEquals(WordExtractor::class, registry.select(DOC_MEDIA_TYPE)::class)
        assertEquals(SpreadsheetExtractor::class, registry.select(XLSX_MEDIA_TYPE)::class)
        assertEquals(SpreadsheetExtractor::class, registry.select(XLS_MEDIA_TYPE)::class)
        assertEquals(PresentationExtractor::class, registry.select(PPTX_MEDIA_TYPE)::class)
        assertEquals(PresentationExtractor::class, registry.select(PPT_MEDIA_TYPE)::class)
    }

    @Test
    fun `each office fixture reaches its extractor through detection`() {
        val expectations = mapOf(
            "sample.docx" to WordExtractor::class,
            "sample.doc" to WordExtractor::class,
            "sample.xlsx" to SpreadsheetExtractor::class,
            "sample.xls" to SpreadsheetExtractor::class,
            "sample.pptx" to PresentationExtractor::class,
            "sample.ppt" to PresentationExtractor::class,
        )
        val registry = ExtractorRegistry.production()

        expectations.forEach { (name, expected) ->
            val mediaType = MediaTypeDetector().detect(fixture(name)).value
            assertEquals(expected, registry.select(mediaType)::class, "$name was routed to the wrong reader")
        }
    }

    @Test
    fun `the generator reproduces the committed fixtures byte for byte`() {
        // The legacy `.doc` is the one fixture a system converter writes, so it is deliberately not
        // regenerated here: LibreOffice would produce different bytes than the committed file, and a check
        // that failed for having better tools would say nothing about the extractors.
        val regenerated = infoscry.fixtures.OfficeFixtureGenerator.writeAll(
            Files.createTempDirectory("infoscry-office-regenerated"),
            withConvertedLegacyWord = false,
        )

        assertEquals(5, regenerated.size, "every fixture POI writes itself is regenerated")
        regenerated.forEach { path ->
            val committed = fixtureDirectory().resolve(path.fileName.toString())
            assertTrue(Files.exists(committed), "${path.fileName} is not committed")
            assertTrue(
                readAll(path).contentEquals(readAll(committed)),
                "${path.fileName} differs from the committed fixture",
            )
        }
        assertTrue(
            Files.exists(fixtureDirectory().resolve("sample.doc")),
            "the legacy .doc is committed, because no test run can produce it",
        )
    }

    private fun officeExtractors(): List<Pair<String, DocumentExtractor>> = listOf(
        "sample.docx" to WordExtractor(OfficeFormat.OOXML),
        "sample.doc" to WordExtractor(OfficeFormat.LEGACY),
        "sample.xlsx" to SpreadsheetExtractor(OfficeFormat.OOXML),
        "sample.xls" to SpreadsheetExtractor(OfficeFormat.LEGACY),
        "sample.pptx" to PresentationExtractor(OfficeFormat.OOXML),
        "sample.ppt" to PresentationExtractor(OfficeFormat.LEGACY),
    )

    private fun collect(
        extractor: DocumentExtractor,
        input: ExtractionInput,
        probe: PermitProbeBoundary,
    ): List<ExtractionEvent> = runBlocking {
        extractor.extract(input).onEach { probe.observed(it) }.toList()
    }

    private fun units(events: List<ExtractionEvent>): List<ExtractionEvent.UnitReady> =
        events.filterIsInstance<ExtractionEvent.UnitReady>()

    private fun inputFor(
        name: String,
        boundary: UnitBoundary,
        committed: Set<String> = emptySet(),
    ): ExtractionInput = inputFor(fixture(name), boundary, committed)

    private fun inputFor(
        source: Path,
        boundary: UnitBoundary,
        committed: Set<String> = emptySet(),
    ): ExtractionInput = ExtractionInput(
        documentId = DocumentId("doc-1"),
        managedPath = source,
        artifactRoot = directory.resolve("artifacts"),
        settings = ExtractionSettings(ocrLanguages = "eng"),
        fingerprint = ExtractionFingerprint.of("a".repeat(64), ExtractionSettings(ocrLanguages = "eng")),
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
        stream.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        return target
    }

    private fun fixtureDirectory(): Path = Path.of("src/test/resources/fixtures")

    private fun readAll(path: Path): ByteArray = Files.readAllBytes(path)

    private fun sha256Of(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(readAll(path))
        .joinToString("") { "%02x".format(it) }

    private fun ByteArray.take(n: Int): List<Byte> = toList().take(n)

    companion object {

        /** The bytes every PNG starts with. */
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
