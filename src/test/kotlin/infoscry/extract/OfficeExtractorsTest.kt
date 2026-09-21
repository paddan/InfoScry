package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
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
 * legacy document would exercise.
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
        val regenerated = infoscry.fixtures.OfficeFixtureGenerator.writeAll(
            Files.createTempDirectory("infoscry-office-regenerated"),
        )

        regenerated.forEach { path ->
            val committed = fixtureDirectory().resolve(path.fileName.toString())
            assertTrue(Files.exists(committed), "${path.fileName} is not committed")
            assertTrue(
                readAll(path).contentEquals(readAll(committed)),
                "${path.fileName} differs from the committed fixture",
            )
        }
        assertTrue(
            regenerated.any { it.fileName.toString() == "sample.doc" } ||
                Files.exists(fixtureDirectory().resolve("sample.doc")),
            "the legacy .doc is neither regenerated nor committed",
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
