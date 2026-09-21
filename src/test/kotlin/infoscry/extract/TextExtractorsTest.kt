package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * The text, Markdown, HTML, and CSV extractors, and the two properties every extractor owes the
 * pipeline: citable locations that survive a re-import, and work that happens inside the unit boundary.
 *
 * The locators are the reason these tests are written against real fixtures rather than hand-built
 * strings: a citation is only worth anything if it points at the same lines, the same heading section, or
 * the same spreadsheet rows next time, and a quoted CSV field or a CRLF line ending is exactly where an
 * offset quietly drifts.
 *
 * The boundary tests are the reason the probe exists. Nothing in the framework stops an extractor from
 * emitting outside `input.boundary.unit { }`, and an extractor that does keeps working until a collection
 * deletion or an index rebuild lands between a unit and the checkpoint that describes it.
 */
class TextExtractorsTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-text-extractors")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `plain text reads the whole file as line batches`() {
        val probe = PermitProbeBoundary()
        val extractor = PlainTextExtractor()

        val events = collect(extractor, inputFor("sample.txt", probe), probe)

        val units = units(events)
        assertEquals(1, units.size, "a six-line file is one batch")
        assertEquals(SourceLocation.TextLines(start = 1, end = 6), units.single().unit.locator)
        assertEquals(setOf(0), units.map { it.ordinal }.toSet())
        assertEquals(1, (events.last() as ExtractionEvent.Finished).totalUnits)
    }

    @Test
    fun `plain text normalises the search form but leaves the evidence alone`() {
        val probe = PermitProbeBoundary()

        val unit = units(collect(PlainTextExtractor(), inputFor("sample.txt", probe), probe)).single().unit

        assertContains(unit.extractedText, "A\u030Aterremitteras", message = "NFC was written over evidence")
        assertContains(unit.extractedText, "\r\n", message = "the evidence text lost its line endings")
        assertContains(unit.extractedText, "\u0007", message = "the evidence text lost a control character")
        assertContains(unit.extractedText, "Kolumn1\tKolumn2", message = "the evidence text lost a tab")
        assertFalse(unit.extractedText.contains("\uFEFF"), "the byte-order mark is not document text")

        assertContains(unit.searchText, "\u00C5terremitteras")
        assertFalse(unit.searchText.contains("\r"))
        assertFalse(unit.searchText.contains("\u0007"))
        assertContains(unit.searchText, "Kolumn1\tKolumn2")
    }

    @Test
    fun `plain text splits a long file into batches of whole lines`() {
        val source = source("long.txt", "ett\ntva\ntre\nfyra\nfem\n")
        val probe = PermitProbeBoundary()

        val units = units(collect(PlainTextExtractor(linesPerUnit = 2), inputFor(source, probe), probe))

        assertEquals(
            listOf(
                SourceLocation.TextLines(1, 2),
                SourceLocation.TextLines(3, 4),
                SourceLocation.TextLines(5, 5),
            ),
            units.map { it.unit.locator },
        )
        assertEquals(listOf(0, 1, 2), units.map { it.ordinal })
        assertEquals(listOf("lines:1-2", "lines:3-4", "lines:5-5"), units.map { it.key })
    }

    @Test
    fun `plain text keeps every line exactly once`() {
        val lines = (1..7).joinToString("\n") { "rad $it" }
        val source = source("every-line.txt", "$lines\n")
        val probe = PermitProbeBoundary()

        val units = units(collect(PlainTextExtractor(linesPerUnit = 3), inputFor(source, probe), probe))

        val covered = units.flatMap { it.unit.extractedText.trimEnd('\n').lines() }.filter { it.isNotBlank() }
        assertEquals((1..7).map { "rad $it" }, covered)
    }

    @Test
    fun `markdown gives every heading section the lines it covers`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(MarkdownExtractor(), inputFor("sample.md", probe), probe))

        assertEquals(
            listOf(
                SourceLocation.TextLines(1, 3),
                SourceLocation.TextLines(4, 6),
                SourceLocation.TextLines(7, 9),
                SourceLocation.TextLines(10, 11),
            ),
            units.map { it.unit.locator },
        )
        assertContains(
            units[0].unit.extractedText,
            "Utredningen",
            message = "the heading belongs to its section",
        )
        assertContains(units[1].unit.extractedText, "## Handlingar")
        assertContains(units[2].unit.extractedText, "Bilaga 1 och bilaga 2.")
    }

    @Test
    fun `a nested markdown section does not repeat its parent's text`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(MarkdownExtractor(), inputFor("sample.md", probe), probe))

        val sentences = listOf(
            "Oversikt av arendet.",
            "Diariet innehaller tre volymer.",
            "Bilaga 1 och bilaga 2.",
            "Tva vittnen hordes.",
        )
        sentences.forEach { sentence ->
            val owners = units.filter { it.unit.extractedText.contains(sentence) }
            assertEquals(1, owners.size, "'$sentence' belongs to exactly one unit, was in ${owners.size}")
        }
    }

    @Test
    fun `markdown text before the first heading is its own unit`() {
        val source = source("preamble.md", "Inledning.\n\n# Rubrik\nText.\n")
        val probe = PermitProbeBoundary()

        val units = units(collect(MarkdownExtractor(), inputFor(source, probe), probe))

        assertEquals(listOf(SourceLocation.TextLines(1, 2), SourceLocation.TextLines(3, 4)), units.map { it.unit.locator })
        assertContains(units[0].unit.extractedText, "Inledning.")
        assertFalse(units[0].unit.extractedText.contains("Rubrik"), "the heading opened the next unit")
    }

    @Test
    fun `html locates a section by its heading path`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(HtmlExtractor(), inputFor("sample.html", probe), probe))

        assertEquals(
            listOf(
                SourceLocation.HtmlSection(listOf("Diariet")),
                SourceLocation.HtmlSection(listOf("Diariet", "Volym 1")),
                SourceLocation.HtmlSection(listOf("Diariet", "Volym 1", "Bilaga A")),
                SourceLocation.HtmlSection(listOf("Diariet", "Volym 2")),
            ),
            units.map { it.unit.locator },
        )
        assertContains(units[0].unit.extractedText, "Forsta stycket.")
        assertContains(units[3].unit.extractedText, "Tredje stycket.")
    }

    @Test
    fun `html text before the first heading is a section with an empty heading path`() {
        val source = source("preamble.html", "<html><body><p>Ingress.</p><h1>Rubrik</h1><p>Text.</p></body></html>")
        val probe = PermitProbeBoundary()

        val units = units(collect(HtmlExtractor(), inputFor(source, probe), probe))

        assertEquals(SourceLocation.HtmlSection(emptyList()), units[0].unit.locator)
        assertContains(units[0].unit.extractedText, "Ingress.")
        assertEquals(SourceLocation.HtmlSection(listOf("Rubrik")), units[1].unit.locator)
        assertContains(units[1].unit.extractedText, "Text.")
    }

    @Test
    fun `html keeps the prose a form and a table wrap`() {
        val source = source(
            "wrapped.html",
            """
            <html><body>
              <h1>Anmalan</h1>
              <form action="/spara"><p>Ansokan galler bilaga 4.</p>
                <input type="text" value="hemligt">
                <textarea>Fritext: overklagandet avslogs.</textarea>
                <button>Skicka</button>
              </form>
              <table><tr><th>Namn</th><th>Belopp</th></tr><tr><td>Anna</td><td>1200</td></tr></table>
            </body></html>
            """.trimIndent(),
        )
        val probe = PermitProbeBoundary()

        val text = units(collect(HtmlExtractor(), inputFor(source, probe), probe))
            .joinToString("\n") { it.unit.extractedText }

        assertContains(text, "Ansokan galler bilaga 4.", message = "a form wrapped real prose")
        assertContains(text, "overklagandet avslogs.", message = "a textarea holds text a person wrote")
        assertContains(text, "Anna", message = "a table wrapped real content")
        assertContains(text, "1200")
    }

    @Test
    fun `html keeps the text of a noscript fallback and of a vector figure`() {
        val source = source(
            "fallback.html",
            "<html><body><noscript>JavaScript ar avstangt; visa den har texten.</noscript>" +
                "<svg><text>Figur 1: karta</text></svg><p>Brotext.</p></body></html>",
        )
        val probe = PermitProbeBoundary()

        val text = units(collect(HtmlExtractor(), inputFor(source, probe), probe))
            .joinToString("\n") { it.unit.extractedText }

        assertContains(text, "JavaScript ar avstangt", message = "a noscript fallback is text the author wrote")
        assertContains(text, "Figur 1: karta", message = "a vector figure's label is text")
        assertContains(text, "Brotext.")
    }

    @Test
    fun `html refuses a document above the memory bound instead of reading it`() {
        val source = source("huge.html", "<html><body><p>${ "a".repeat(2000) }</p></body></html>")
        val probe = PermitProbeBoundary()

        val events = collect(HtmlExtractor(maxDocumentBytes = 1024), inputFor(source, probe), probe)

        val failure = events.single() as ExtractionEvent.UnitFailed
        assertEquals(DOCUMENT_TOO_LARGE_CODE, failure.code)
        assertTrue(units(events).isEmpty(), "a refused document has no unit to cite")
        assertTrue(
            events.none { it is ExtractionEvent.Finished },
            "a refused document must not be reported as finished",
        )
    }

    @Test
    fun `html does not report a refusal that is already committed`() {
        val source = source("huge.html", "<html><body><p>${ "a".repeat(2000) }</p></body></html>")
        val probe = PermitProbeBoundary()

        val events = collect(
            HtmlExtractor(maxDocumentBytes = 1024),
            inputFor(source, probe, committed = setOf(HtmlExtractor.OVERSIZED_KEY)),
            probe,
        )

        assertTrue(events.isEmpty(), "an oversized document was parsed and reported again: $events")
    }

    @Test
    fun `html never lets a script, a style block, or an event handler into a unit`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(HtmlExtractor(), inputFor("sample.html", probe), probe))

        val all = units.joinToString("\n") { it.unit.extractedText + "\n" + it.unit.searchText }
        assertFalse(all.contains("alert"), "script content reached a unit:\n$all")
        assertFalse(all.contains("spara inte"), "script content reached a unit")
        assertFalse(all.contains("color: red"), "style content reached a unit")
        assertFalse(all.contains("steal"), "an event handler reached a unit")
    }

    @Test
    fun `sanitised html is inert and keeps the readable text`() {
        val sanitised = HtmlSanitizer.sanitize(Files.readString(fixture("sample.html")))

        assertTrue(sanitised.select("script, style, noscript, iframe, object, embed").isEmpty())
        assertTrue(
            sanitised.allElements.none { element ->
                element.attributes().any { it.key.startsWith("on") || it.value.contains("javascript:", ignoreCase = true) }
            },
            "the sanitised document still carries an active attribute",
        )
        assertContains(sanitised.text(), "Forsta stycket.")
        assertContains(sanitised.text(), "Andra stycket & mera.")
    }

    @Test
    fun `html decodes entities and joins inline markup into one readable block`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(HtmlExtractor(), inputFor("sample.html", probe), probe))

        assertContains(units[1].unit.extractedText, "Andra stycket & mera.")
        assertContains(units[2].unit.extractedText, "Bilagetext. Fortsattning.")
    }

    @Test
    fun `html reports what it learned about the document`() {
        val probe = PermitProbeBoundary()

        val events = collect(HtmlExtractor(), inputFor("sample.html", probe), probe)
        val finished = events.last() as ExtractionEvent.Finished

        assertEquals("Diariet 1986", finished.metadata["title"])
        assertEquals("sv", finished.metadata["lang"])
        assertEquals(units(events).size, finished.totalUnits)
    }

    @Test
    fun `csv cites rows as a spreadsheet range named after the file`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(CsvExtractor(), inputFor("sample.csv", probe), probe))

        assertEquals(1, units.size)
        assertEquals(
            SourceLocation.SpreadsheetRange(sheet = "sample", startCell = "A2", endCell = "D5"),
            units.single().unit.locator,
        )
    }

    @Test
    fun `csv keeps a quoted field with its delimiter and embedded newline in one cell`() {
        val probe = PermitProbeBoundary()

        val unit = units(collect(CsvExtractor(), inputFor("sample.csv", probe), probe)).single().unit

        assertContains(unit.searchText, "Berg, Lars")
        assertContains(unit.searchText, "rad ett")
        assertContains(unit.searchText, "rad tva")
        assertContains(unit.searchText, "3400")
        assertEquals(
            4,
            unit.searchText.lines().count { it.isNotBlank() },
            "one header line plus four records, not one extra per embedded newline:\n${unit.searchText}",
        )
    }

    @Test
    fun `csv repeats the column headers in every batch and keeps row numbers honest`() {
        val probe = PermitProbeBoundary()

        val units = units(collect(CsvExtractor(rowsPerUnit = 1), inputFor("sample.csv", probe), probe))

        // Record 4 of the file is a blank line. It is a row of the file, so it holds the row numbers
        // after it in place, but it is not evidence, so it is not a unit of its own.
        assertEquals(
            listOf(
                SourceLocation.SpreadsheetRange("sample", "A2", "D2"),
                SourceLocation.SpreadsheetRange("sample", "A3", "D3"),
                SourceLocation.SpreadsheetRange("sample", "A5", "D5"),
            ),
            units.map { it.unit.locator },
        )
        units.forEach { unit ->
            assertTrue(
                unit.unit.searchText.startsWith("id\tname\tamount\tnote"),
                "a batch lost the header it can only be read against:\n${unit.unit.searchText}",
            )
        }
        assertContains(
            units[2].unit.searchText,
            "Dagny",
            message = "the blank line shifted the row numbers",
        )
        assertContains(units[2].unit.extractedText, "4,Dagny,0,-")
    }

    @Test
    fun `csv column letters run past z`() {
        val header = (1..28).joinToString(",") { "c$it" }
        val row = (1..28).joinToString(",") { "$it" }
        val source = source("wide.csv", "$header\n$row\n")
        val probe = PermitProbeBoundary()

        val unit = units(collect(CsvExtractor(), inputFor(source, probe), probe)).single().unit

        assertEquals(
            SourceLocation.SpreadsheetRange("wide", "A2", "AB2"),
            unit.locator,
        )
    }

    @Test
    fun `csv without data rows has no units to cite`() {
        val source = source("empty.csv", "id,name\n")
        val probe = PermitProbeBoundary()

        val events = collect(CsvExtractor(), inputFor(source, probe), probe)

        assertTrue(units(events).isEmpty(), "a header alone is nothing to cite")
        assertEquals(0, (events.last() as ExtractionEvent.Finished).totalUnits)
    }

    @Test
    fun `every extractor emits each event inside a unit permit`() {
        extractorsWithFixtures().forEach { (name, extractor) ->
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
    fun `every extractor produces the same citations twice`() {
        extractorsWithFixtures().forEach { (name, extractor) ->
            val first = units(collect(extractor, inputFor(name, PermitProbeBoundary()), PermitProbeBoundary()))
            val second = units(collect(extractor, inputFor(name, PermitProbeBoundary()), PermitProbeBoundary()))

            assertEquals(first.map { it.key }, second.map { it.key }, "$name produced unstable keys")
            assertEquals(first.map { it.ordinal }, second.map { it.ordinal }, "$name produced unstable ordinals")
            assertEquals(first.map { it.unit.locator }, second.map { it.unit.locator }, "$name produced unstable locators")
        }
    }

    @Test
    fun `every extractor skips committed units on resume and still counts them`() {
        extractorsWithFixtures().forEach { (name, extractor) ->
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
    fun `every extractor numbers its units in document order`() {
        extractorsWithFixtures().forEach { (name, extractor) ->
            val probe = PermitProbeBoundary()

            val units = units(collect(extractor, inputFor(name, probe), probe))

            assertEquals((0 until units.size).toList(), units.map { it.ordinal }, "$name numbered its units out of order")
        }
    }

    @Test
    fun `a csv file reaches the csv extractor through detection`() {
        val path = fixture("sample.csv")
        val probe = PermitProbeBoundary()

        val events = collect(
            ExtractorRegistry.production().select(MediaTypeDetector().detect(path).value),
            inputFor(path, probe),
            probe,
        )

        assertEquals(
            SourceLocation.SpreadsheetRange(sheet = "sample", startCell = "A2", endCell = "D5"),
            units(events).single().unit.locator,
            "a csv read by a text reader would be cited by line range instead of by spreadsheet range",
        )
    }

    @Test
    fun `a markdown file reaches the markdown extractor through detection`() {
        val path = fixture("sample.md")
        val probe = PermitProbeBoundary()

        val events = collect(
            ExtractorRegistry.production().select(MediaTypeDetector().detect(path).value),
            inputFor(path, probe),
            probe,
        )

        assertEquals(listOf(SourceLocation.TextLines(1, 3), SourceLocation.TextLines(4, 6)), units(events).map { it.unit.locator }.take(2))
    }

    @Test
    fun `a tsv file is read as a table with its tab separator`() {
        val source = source("register.tsv", "id\tnamn\tbelopp\r\n1\tAnna\t1200\r\n")
        val probe = PermitProbeBoundary()

        val unit = units(collect(CsvExtractor(), inputFor(source, probe), probe)).single().unit

        assertEquals(
            SourceLocation.SpreadsheetRange(sheet = "register", startCell = "A2", endCell = "C2"),
            unit.locator,
            "a tab-separated row read as comma-separated collapses into one column",
        )
    }

    @Test
    fun `the production registry routes each text format to its own extractor`() {
        val registry = ExtractorRegistry.production()

        assertEquals(PlainTextExtractor::class, registry.select("text/plain")::class)
        assertEquals(MarkdownExtractor::class, registry.select("text/markdown")::class)
        assertEquals(MarkdownExtractor::class, registry.select("text/x-markdown")::class)
        assertEquals(HtmlExtractor::class, registry.select("text/html")::class)
        assertEquals(HtmlExtractor::class, registry.select("application/xhtml+xml")::class)
        assertEquals(CsvExtractor::class, registry.select("text/csv")::class)
        assertEquals(TextualFallbackExtractor::class, registry.select("application/json")::class)
        assertTrue(
            registry.claimedMediaTypes().isNotEmpty(),
            "the production registry claims no media type at all",
        )
    }

    private fun extractorsWithFixtures(): List<Pair<String, DocumentExtractor>> = listOf(
        "sample.txt" to PlainTextExtractor(),
        "sample.md" to MarkdownExtractor(),
        "sample.html" to HtmlExtractor(),
        "sample.csv" to CsvExtractor(),
        // The fixture's own type is irrelevant to the whole-document fallback: what these shared tests
        // check is its boundary discipline, which is the same obligation as every other extractor's.
        "sample.txt" to TextualFallbackExtractor(),
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

    /** Writes a file whose exact bytes matter, for the cases a committed fixture cannot carry. */
    private fun source(name: String, text: String): Path {
        val target = directory.resolve("managed").resolve(name)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
        return target
    }
}

/**
 * A unit boundary that runs its block inline, counts the permits, and records any event the collector
 * sees while no permit is open.
 *
 * A flow is collected inline, so an event the collector observes during a permit is an event the extractor
 * emitted inside the boundary. One observed outside it is the defect this probe exists to catch.
 */
internal class PermitProbeBoundary : UnitBoundary {

    var permits: Int = 0
        private set

    val eventsOutsidePermit: MutableList<ExtractionEvent> = mutableListOf()

    private var open: Int = 0

    /**
     * How many permits are open right now.
     *
     * An injected tool checks this before it does its work: that a page was read is not the property —
     * that it was read while the collector held the unit's permit is, because only then does the artifact
     * it wrote belong to the checkpoint that describes it.
     */
    val openPermits: Int get() = open

    override suspend fun <T> unit(block: suspend () -> T): T {
        permits++
        open++
        try {
            return block()
        } finally {
            open--
        }
    }

    /** Called by the collector for every event it receives. */
    fun observed(event: ExtractionEvent) {
        if (open == 0) {
            eventsOutsidePermit += event
        }
    }
}
