package infoscry.extract

import infoscry.domain.SourceLocation
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * One line of a source file with the terminator that ended it.
 *
 * The terminator is kept rather than assumed, because the extracted text is evidence: a document written
 * with Windows line endings has to read back with Windows line endings, and a file whose last line has no
 * terminator has to stay that way. It is also how this reader tells a trailing newline from a final empty
 * line, which is the difference between citing six lines and citing seven.
 */
internal data class SourceLine(val text: String, val terminator: String)

/**
 * Reads a text file line by line, keeping each line's own terminator.
 *
 * `BufferedReader.readLine` cannot do this: it drops the terminator, so a reader cannot tell `"a\nb"`
 * from `"a\nb\n"`, and it silently duplicates a whole-file read when the file is large. This reads one
 * character at a time from an already buffered reader, which is the same cost as `readLine` without
 * losing the information.
 */
internal class SourceLines(private val reader: Reader) {

    /** The next line, or `null` once the file is exhausted. */
    fun next(): SourceLine? {
        val text = StringBuilder()
        while (true) {
            val read = reader.read()
            if (read < 0) {
                return if (text.isEmpty()) null else SourceLine(text.toString(), terminator = "")
            }
            when (val character = read.toChar()) {
                '\n' -> return SourceLine(text.toString(), terminator = "\n")
                '\r' -> {
                    reader.mark(1)
                    val following = reader.read()
                    if (following == '\n'.code) return SourceLine(text.toString(), terminator = "\r\n")
                    if (following >= 0) reader.reset()
                    return SourceLine(text.toString(), terminator = "\r")
                }

                else -> text.append(character)
            }
        }
    }
}

/**
 * Plain text, cited by line range.
 *
 * A text file has no structure to cite more precisely than its lines, so the unit is a batch of whole
 * lines and the locator counts them the way a reader does. Batches are bounded on purpose: the plan's
 * exclusive side has no timeout, so a unit is what one permit is held for, and a whole book of a file
 * must not be one unit.
 *
 * A single source line longer than the batch's character budget is still read whole — the alternative
 * would be cutting a line in two and citing both halves at a line range that only one of them starts at,
 * which is a worse lie than a large unit. What is bounded here is the unit, not the document.
 */
class PlainTextExtractor(
    private val linesPerUnit: Int = DEFAULT_LINES_PER_UNIT,
    private val maxUnitChars: Int = DEFAULT_MAX_UNIT_CHARS,
) : DocumentExtractor {

    init {
        require(linesPerUnit >= 1) { "a text unit needs at least one line, was $linesPerUnit" }
        require(maxUnitChars >= 1) { "a text unit needs room for at least one character, was $maxUnitChars" }
    }

    override val supportedMediaTypes: Set<String> = setOf(TEXT_PLAIN)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        var position = 0
        var total = 0
        var nextLine = 1
        Files.newBufferedReader(input.managedPath, Charsets.UTF_8).use { reader ->
            val lines = SourceLines(reader)
            // The first line is read before the permit opens so that a document's last batch is not
            // followed by a permit taken only to discover that the file ended: a permit is for work.
            var pending: SourceLine? = lines.next()
            while (true) {
                val first = pending ?: break
                input.boundary.unit {
                    val batch = readBatch(first, lines)
                    pending = batch.next
                    val start = nextLine
                    val end = start + batch.lines.size - 1
                    nextLine = end + 1
                    val key = lineKey(start, end)
                    val ordinal = position
                    position++
                    total++
                    if (input.isCommitted(key)) return@unit
                    val normalised = TextNormalizer.normalize(
                        batch.lines.joinToString("") { it.text + it.terminator },
                    )
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = ordinal,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.TextLines(start = start, end = end),
                                extractedText = normalised.extracted,
                                searchText = normalised.search,
                            ),
                        ),
                    )
                }
            }
        }
        input.boundary.unit {
            emit(ExtractionEvent.Finished(metadata = mapOf(EXTRACTOR_METADATA to "plain-text"), totalUnits = total))
        }
    }

    /**
     * The next batch of lines, and the line that starts the batch after it.
     *
     * Reading a line for a committed batch is allowed — the line's boundaries are what keeps the numbering
     * honest — but nothing else is done for it.
     */
    private fun readBatch(first: SourceLine, lines: SourceLines): LineBatch {
        val batch = mutableListOf(first)
        var characters = first.text.length + first.terminator.length
        while (batch.size < linesPerUnit && characters < maxUnitChars) {
            val line = lines.next() ?: return LineBatch(lines = batch, next = null)
            batch += line
            characters += line.text.length + line.terminator.length
        }
        // The batch is full or has reached its character budget, so the next line — if the file has one —
        // starts the batch after it.
        return LineBatch(lines = batch, next = lines.next())
    }

    private data class LineBatch(val lines: List<SourceLine>, val next: SourceLine?)

    companion object {

        /** How many lines one text unit holds unless a caller asks for another batch size. */
        const val DEFAULT_LINES_PER_UNIT: Int = 200

        /** The character budget a text unit fills before it is closed, measured in source characters. */
        const val DEFAULT_MAX_UNIT_CHARS: Int = 64 * 1024
    }
}

/**
 * Markdown, cited by the lines of each heading section.
 *
 * The sections are what make a Markdown document navigable: a heading opens a section, and the section
 * holds every line up to the next heading of *any* level. Splitting at every heading rather than at every
 * heading of the same or higher level is what keeps a nested section from repeating its parent's text —
 * each line belongs to exactly one unit, so a search hit is one hit and a citation is unambiguous.
 *
 * The locator is a line range rather than a heading path, because a line range is what a reader can verify
 * against the file and what survives an edit to an unrelated heading. Setext headings (`===` underlines)
 * are read as body text: this parser recognises the ATX form.
 */
class MarkdownExtractor : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf(TEXT_MARKDOWN, TEXT_X_MARKDOWN)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        var position = 0
        var total = 0
        Files.newBufferedReader(input.managedPath, Charsets.UTF_8).use { reader ->
            val sections = MarkdownSections(SourceLines(reader))
            var section: MarkdownSections.Section? = sections.next()
            while (section != null) {
                val ready = section
                input.boundary.unit {
                    val key = lineKey(ready.startLine, ready.endLine)
                    val ordinal = position
                    position++
                    total++
                    if (input.isCommitted(key)) return@unit
                    val normalised = TextNormalizer.normalize(ready.text)
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = ordinal,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.TextLines(start = ready.startLine, end = ready.endLine),
                                extractedText = normalised.extracted,
                                searchText = normalised.search,
                            ),
                        ),
                    )
                }
                section = if (ready.hasNext) sections.next() else null
            }
        }
        input.boundary.unit {
            emit(ExtractionEvent.Finished(metadata = mapOf(EXTRACTOR_METADATA to "markdown"), totalUnits = total))
        }
    }
}

/**
 * Splits a Markdown file into sections, one heading at a time.
 *
 * A heading opens a section and the section holds every line up to the next heading of any level. Split
 * that way, each line belongs to exactly one section, so a nested heading does not repeat its parent's
 * text and a search hit names one place rather than two.
 */
private class MarkdownSections(private val lines: SourceLines) {

    /** [hasNext] says whether the file has a section after this one, so no permit is taken to find out. */
    data class Section(val startLine: Int, val endLine: Int, val text: String, val hasNext: Boolean)

    private val buffer = StringBuilder()
    private var startLine = 0
    private var endLine = 0
    private var lineNumber = 0

    /** The next section, or `null` at the end of the file once the last one is returned. */
    fun next(): Section? {
        while (true) {
            val line = lines.next() ?: return close(hasNext = false)
            lineNumber++
            if (!HEADING.matches(line.text.trim())) {
                append(line)
                continue
            }
            // A heading closes the section before it and opens the next one, so the section being
            // returned already knows that another one follows.
            val closed = close(hasNext = true)
            append(line)
            if (closed != null) return closed
        }
    }

    private fun append(line: SourceLine) {
        if (buffer.isEmpty()) startLine = lineNumber
        endLine = lineNumber
        buffer.append(line.text).append(line.terminator)
    }

    private fun close(hasNext: Boolean): Section? {
        if (buffer.isEmpty()) return null
        val section = Section(
            startLine = startLine,
            endLine = endLine,
            text = buffer.toString(),
            hasNext = hasNext,
        )
        buffer.setLength(0)
        return section
    }

    companion object {

        /** An ATX heading: one to six `#` followed by the title. Setext underlines are body text. */
        private val HEADING = Regex("^#{1,6}\\s+.*$")
    }
}

/**
 * The HTML that may reach a unit and, later, a source viewer.
 *
 * Sanitising is not a presentation choice here. Imported markup is untrusted input: a script that ran
 * while a section were rendered would be remote code execution against the local API, and an inline
 * handler or a `javascript:` target would be the same hole with a smaller payload. Everything active is
 * removed before a unit exists, so there is no later stage that has to remember to be careful.
 *
 * Removing markup is not the same as removing text. What is deleted whole is only the markup whose
 * content is not document text at all — code, styling, embedded objects, document metadata — because a
 * page that wrapped a paragraph in a `<form>` or a table in a layout element is ordinary, and dropping
 * that subtree would delete evidence the archive exists to keep. Everything else loses its *tags*: the
 * safelist strips the element and its attributes while the text a person wrote stays where it is.
 *
 * The safelist is spelled out rather than taken whole from jsoup: the extractor walks headings and
 * block elements to build its sections, so the structure it depends on has to survive cleaning.
 */
object HtmlSanitizer {

    /**
     * Elements whose content is executable, styling, embedded, or metadata: removed with their subtree.
     *
     * The list is deliberately shorter than it looks like it should be. `<form>`, `<input>`,
     * `<textarea>`, `<button>`, `<select>`, `<noscript>`, `<svg>` and `<math>` all carry markup that
     * must not survive — but they also wrap or contain text somebody wrote, so they are left to the
     * safelist, which strips their tags and their attributes and keeps their text.
     */
    private const val NON_TEXT_CONTENT =
        "script, style, iframe, object, embed, applet, frame, frameset, link, meta, base, template"

    /** Structure the section walk depends on, plus the inline vocabulary worth keeping as text. */
    private val KEPT_TAGS = arrayOf(
        "html", "head", "body", "title", "div", "p", "span", "section", "article", "main", "header",
        "footer", "aside", "nav", "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "dl", "dt", "dd", "table", "caption", "thead", "tbody", "tr", "td", "th",
        "blockquote", "pre", "code", "address", "summary", "details", "fieldset", "legend",
        "em", "strong", "b", "i", "u", "s", "small", "sub", "sup", "br", "hr", "a", "img",
    )

    private val SAFELIST: Safelist = Safelist.relaxed().addTags(*KEPT_TAGS)

    /** The inert document [html] describes. */
    fun sanitize(html: String): Document = sanitize(Jsoup.parse(html))

    /** The inert form of an already parsed document: non-text elements and active attributes removed. */
    fun sanitize(document: Document): Document {
        val source = document.clone()
        source.select(NON_TEXT_CONTENT).remove()
        return Jsoup.parse(Jsoup.clean(source.body().html(), SAFELIST))
    }
}

/**
 * HTML, cited by heading path.
 *
 * A page has no stable line numbers — the same markup reflows differently in every reader — so the
 * location a citation can honestly carry is the section it came from: the chain of headings a reader
 * would click through. Text before the first heading is a section with an empty path, because that is
 * where it sits.
 *
 * jsoup has to parse the whole document to give a DOM, so the container is read once before the first
 * unit. That read writes nothing and produces no unit; every unit and its event still happen inside the
 * boundary. Because nothing about the document is understood until the DOM exists, a container above
 * [MAX_TEXT_DOCUMENT_BYTES] is refused instead of being read: an out-of-memory kill would take every other
 * job in the process with it, and a refusal is at least a reason a reader can act on.
 */
class HtmlExtractor(
    private val maxDocumentBytes: Long = MAX_TEXT_DOCUMENT_BYTES,
) : DocumentExtractor {

    init {
        require(maxDocumentBytes >= 1) { "an HTML memory bound must allow at least one byte, was $maxDocumentBytes" }
    }

    override val supportedMediaTypes: Set<String> = setOf(TEXT_HTML, APPLICATION_XHTML)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        if (Files.size(input.managedPath) > maxDocumentBytes) {
            // A DOM has to exist before a heading section can be named, so there is no unit to fail here:
            // the document is refused, and a refusal is not a completion. The file is never parsed, and a
            // refusal that is already durable is not reported a second time.
            if (!input.isCommitted(OVERSIZED_KEY)) {
                input.boundary.unit {
                    emit(ExtractionEvent.UnitFailed(key = OVERSIZED_KEY, ordinal = 0, code = DOCUMENT_TOO_LARGE_CODE))
                }
            }
            return@flow
        }
        val document = Jsoup.parse(Files.readString(input.managedPath, Charsets.UTF_8))
        val metadata = documentMetadata(document)
        val sections = HtmlSections.sectionsOf(HtmlSanitizer.sanitize(document))
        var position = 0
        var total = 0
        sections.forEach { section ->
            input.boundary.unit {
                val key = headingKey(position, section.headingPath)
                val ordinal = position
                position++
                total++
                if (input.isCommitted(key)) return@unit
                val normalised = TextNormalizer.normalize(section.text)
                emit(
                    ExtractionEvent.UnitReady(
                        key = key,
                        ordinal = ordinal,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.HtmlSection(section.headingPath),
                            extractedText = normalised.extracted,
                            searchText = normalised.search,
                        ),
                    ),
                )
            }
        }
        input.boundary.unit { emit(ExtractionEvent.Finished(metadata = metadata, totalUnits = total)) }
    }

    private fun documentMetadata(document: Document): Map<String, String> = buildMap {
        put(EXTRACTOR_METADATA, "html")
        val title = document.title().trim()
        if (title.isNotEmpty()) put("title", title)
        val language = document.selectFirst("html")?.attr("lang")?.trim().orEmpty()
        if (language.isNotEmpty()) put("lang", language)
    }

    companion object {

        /**
         * The key a refused document fails under.
         *
         * A key names a unit, and a document refused before its first section has none, so the failure
         * names the document instead: [DOCUMENT_TOO_LARGE_KEY], the one spelling every extractor that can
         * refuse a document uses. Nothing cites it; it exists so the reason survives a resume rather than
         * being re-derived from an oversized file on every attempt.
         */
        const val OVERSIZED_KEY: String = DOCUMENT_TOO_LARGE_KEY
    }
}

/**
 * The sections of a sanitised page, in document order.
 *
 * The walk has to avoid two ways of reporting the same text twice: a block element reports the text of
 * everything inside it, so a nested block must not report it again, and a wrapper that only exists to hold
 * headings must not report the headings' text on top of the heading sections. The first is handled by
 * remembering which elements were claimed, the second by treating such a wrapper as transparent.
 */
private object HtmlSections {

    data class Section(val headingPath: List<String>, val text: String)

    fun sectionsOf(document: Document): List<Section> {
        val sections = mutableListOf<Section>()
        val stack = ArrayDeque<Pair<Int, String>>()
        var path: List<String> = emptyList()
        val texts = mutableListOf<String>()

        fun flush() {
            if (texts.isEmpty()) return
            sections += Section(headingPath = path, text = texts.joinToString("\n"))
            texts.clear()
        }

        entriesOf(document).forEach { entry ->
            when (entry) {
                is Entry.Heading -> {
                    flush()
                    if (entry.title.isNotEmpty()) {
                        while (stack.isNotEmpty() && stack.last().first >= entry.level) stack.removeLast()
                        stack.addLast(entry.level to entry.title)
                        path = stack.map { it.second }
                        texts += entry.title
                    }
                }

                is Entry.Text -> texts += entry.text
            }
        }
        flush()
        return sections
    }

    private fun entriesOf(document: Document): List<Entry> {
        val body = document.body()
        val entries = mutableListOf<Entry>()
        val claimed = mutableSetOf<Element>()
        body.getAllElements().forEach { element ->
            val level = headingLevel(element.normalName())
            if (level != null) {
                // `element` is itself Iterable, so `claimed += element` would add its children instead.
                claimed.add(element)
                entries += Entry.Heading(level = level, title = element.text().trim())
                return@forEach
            }
            if (element.normalName() !in BLOCK_TAGS) return@forEach
            if (hasClaimedAncestor(element, claimed)) return@forEach
            if (element.select(HEADINGS).isNotEmpty()) {
                // A wrapper around headings: it reports only the text that is not inside one of them, so
                // the headings can open their own sections without the wrapper repeating them.
                val own = element.ownText().trim()
                if (own.isNotEmpty()) entries += Entry.Text(own)
                return@forEach
            }
            claimed.add(element)
            val text = element.text().trim()
            if (text.isNotEmpty()) entries += Entry.Text(text)
        }
        return entries
    }

    private fun hasClaimedAncestor(element: Element, claimed: Set<Element>): Boolean =
        element.parents().any { it in claimed }

    private fun headingLevel(tag: String): Int? = when (tag) {
        "h1" -> 1
        "h2" -> 2
        "h3" -> 3
        "h4" -> 4
        "h5" -> 5
        "h6" -> 6
        else -> null
    }

    private val HEADINGS = "h1, h2, h3, h4, h5, h6"

    /**
     * Elements whose text is a block of its own. A table reports its whole content once rather than one
     * row at a time, because a cited cell without its header row is not evidence of anything.
     */
    private val BLOCK_TAGS: Set<String> = setOf(
        "body", "div", "p", "section", "article", "main", "header", "footer", "aside", "nav",
        "figure", "figcaption", "ul", "ol", "li", "dl", "dt", "dd", "table", "caption", "thead",
        "tbody", "tr", "td", "th", "blockquote", "pre", "address", "summary", "details", "fieldset",
        "legend", "form",
    )

    private sealed interface Entry {

        data class Heading(val level: Int, val title: String) : Entry

        data class Text(val text: String) : Entry
    }
}

/**
 * CSV, cited as a spreadsheet range.
 *
 * A CSV file is one unnamed table, so the range names it after the file: the citation then reads
 * `sample.csv — sample!A2:D5` and there is nothing about it a reader cannot check. Sheets are the
 * coordinate system of every table a reader has met, and the viewer the plan calls for highlights exactly
 * such a range.
 *
 * Two decisions here exist to keep the row numbers honest. Blank rows are counted but are not units: they
 * are part of the file's row numbering, and a unit whose only text would be the repeated header is not
 * evidence of anything. And the empty record a trailing line terminator produces is dropped, for the same
 * reason a trailing newline is not an extra line of text.
 *
 * The search form of a unit carries the header labels above its rows, because a row of values with no
 * column names is unsearchable; the evidence form is the rows themselves as the parser printed them.
 */
class CsvExtractor(private val rowsPerUnit: Int = DEFAULT_ROWS_PER_UNIT) : DocumentExtractor {

    init {
        require(rowsPerUnit >= 1) { "a CSV unit needs at least one row, was $rowsPerUnit" }
    }

    override val supportedMediaTypes: Set<String> = setOf(TEXT_CSV)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        var position = 0
        var total = 0
        Files.newBufferedReader(input.managedPath, Charsets.UTF_8).use { reader ->
            val table = CsvTable(reader, delimiterFor(input.managedPath))
            val header = table.header
            val name = tableName(input.managedPath)
            while (table.hasMore()) {
                var produced = false
                input.boundary.unit {
                    val batch = table.nextBatch(rowsPerUnit)
                    if (batch.isEmpty()) return@unit
                    produced = true
                    val first = batch.first().number
                    val last = batch.last().number
                    val key = rowKey(first, last)
                    val ordinal = position
                    position++
                    total++
                    if (input.isCommitted(key)) return@unit
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = ordinal,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.SpreadsheetRange(
                                    sheet = name,
                                    startCell = "A$first",
                                    endCell = "${columnLetter(maxColumns(header, batch))}$last",
                                ),
                                extractedText = TextNormalizer.normalize(printRows(batch)).extracted,
                                searchText = TextNormalizer.normalize(searchableRows(header, batch)).search,
                            ),
                        ),
                    )
                }
                // hasMore() and nextBatch() agree by construction, so this cannot happen; stopping is
                // still better than looping forever if one of them ever changes alone.
                if (!produced) break
            }
        }
        input.boundary.unit {
            emit(
                ExtractionEvent.Finished(
                    metadata = mapOf(EXTRACTOR_METADATA to "csv"),
                    totalUnits = total,
                ),
            )
        }
    }

    private fun maxColumns(header: List<String>, batch: List<Row>): Int =
        maxOf(header.size, batch.maxOf { it.cells.size }, 1)

    /** The rows as CSV, which is what the parser read back out of the file. */
    private fun printRows(batch: List<Row>): String = csvText(batch.map { it.cells })

    /** The header labels and the rows as one searchable text; a value's own newline is not a row break. */
    private fun searchableRows(header: List<String>, batch: List<Row>): String =
        tableSearchText(header, batch.map { it.cells })

    private fun tableName(path: Path): String {
        val filename = path.fileName?.toString().orEmpty()
        val stem = filename.substringBeforeLast('.')
        return stem.ifBlank { filename.ifBlank { DEFAULT_TABLE_NAME } }
    }

    /**
     * The separator between the columns of the table.
     *
     * TSV is the same table with tabs between the columns, and within the text family the name is the
     * only signal for which one a file is: two files that differ only in their separator have the same
     * bytes as far as any detector can tell. Reading a TSV as comma-separated would put a whole row in
     * one cell and cite a range one column wide, so the name is consulted for the separator and for
     * nothing else — the file's content is what decided that it is a table at all.
     */
    private fun delimiterFor(path: Path): Char =
        if (path.fileName?.toString()?.lowercase(Locale.ROOT)?.endsWith(".tsv") == true) '\t' else ','

    companion object {

        /** How many records one CSV unit holds unless a caller asks for another batch size. */
        const val DEFAULT_ROWS_PER_UNIT: Int = 200

        /** The name a table gets when the file's own name yields none. */
        const val DEFAULT_TABLE_NAME: String = "table"
    }
}

/** One record of a CSV file, with the spreadsheet row number a reader counts it at. */
internal data class Row(val number: Int, val cells: List<String>)

/**
 * The records of a CSV table, with a header and spreadsheet row numbers.
 *
 * The parser is driven one record at a time. Commons CSV hands out an iterator, and the only reason this
 * wrapper looks one record ahead is to recognise the empty record a trailing line terminator produces:
 * dropping it needs to know that no record follows, and reading the whole table into a list to find out
 * would undo the streaming.
 */
internal class CsvTable(reader: Reader, delimiter: Char) {

    private val records: Iterator<CSVRecord> = CSVFormat.DEFAULT.builder()
        .setDelimiter(delimiter)
        .setIgnoreEmptyLines(false)
        .get()
        .parse(reader)
        .iterator()

    private var lookahead: CSVRecord? = if (records.hasNext()) records.next() else null

    private var rowNumber: Int = 0

    /** The first record, which is the row that names the columns. */
    val header: List<String> = run {
        val first = lookahead?.cells().orEmpty()
        lookahead = if (records.hasNext()) records.next() else null
        rowNumber = 1
        first
    }

    /**
     * Whether a record other than the trailing artefact remains.
     *
     * This is what lets a caller take a permit only when there is a batch to produce: asking "is there
     * more?" must not cost a critical section.
     */
    fun hasMore(): Boolean = lookahead != null

    /**
     * The next batch of non-blank records.
     *
     * Blank rows advance [rowNumber] without being returned, so the rows after them keep the number a
     * reader sees in the file.
     */
    fun nextBatch(size: Int): List<Row> {
        val batch = mutableListOf<Row>()
        while (batch.size < size && lookahead != null) {
            val record = lookahead ?: break
            lookahead = if (records.hasNext()) records.next() else null
            val isLast = lookahead == null
            rowNumber++
            val cells = record.cells()
            val blank = cells.all { it.isBlank() }
            if (isLast && blank) break
            if (!blank) batch += Row(number = rowNumber, cells = cells)
        }
        return batch
    }

    private fun CSVRecord.cells(): List<String> = iterator().asSequence().toList()
}

/**
 * A table's rows as CSV, which is the evidence form for both a CSV file and a spreadsheet range.
 *
 * CSV is what the source actually held and what a reader can check the citation against, so both table
 * formats print their rows the same way rather than each inventing a layout.
 */
internal fun csvText(rows: List<List<String>>): String {
    val printed = StringBuilder()
    CSVPrinter(printed, CSVFormat.DEFAULT.builder().setRecordSeparator("\n").get()).use { printer ->
        rows.forEach { printer.printRecord(it) }
    }
    return printed.toString()
}

/**
 * A table's searchable form: the column labels above every batch of rows.
 *
 * A value with no column name above it is unsearchable, so the header travels with each batch, and a line
 * break inside a value is flattened because it would otherwise read as the start of a new record.
 */
internal fun tableSearchText(header: List<String>, rows: List<List<String>>): String = buildString {
    append(searchableLine(header)).append('\n')
    rows.forEach { append(searchableLine(it)).append('\n') }
}

private fun searchableLine(cells: List<String>): String =
    cells.joinToString("\t") { it.replace(CELL_BREAK, " ") }

/** A line break inside a value, which must not read as the end of a record. */
private val CELL_BREAK = Regex("[\\r\\n]+")

/** `A`, `Z`, `AA`, `AB`: the column labels a spreadsheet range is written with. */
internal fun columnLetter(column: Int): String {
    require(column >= 1) { "a column number is one-based, was $column" }
    var remaining = column
    val letters = StringBuilder()
    while (remaining > 0) {
        val digit = (remaining - 1) % 26
        letters.append(('A'.code + digit).toChar())
        remaining = (remaining - 1) / 26
    }
    return letters.reverse().toString()
}

private fun lineKey(start: Int, end: Int): String = "lines:$start-$end"

private fun rowKey(start: Int, end: Int): String = "rows:$start-$end"

private fun headingKey(ordinal: Int, headingPath: List<String>): String =
    if (headingPath.isEmpty()) "heading:$ordinal" else "heading:$ordinal:${headingPath.joinToString(">")}"

/** The metadata key every extractor uses to say which one produced a document. */
internal const val EXTRACTOR_METADATA = "extractor"

internal const val TEXT_PLAIN = "text/plain"

internal const val TEXT_MARKDOWN = "text/markdown"

internal const val TEXT_X_MARKDOWN = "text/x-markdown"

internal const val TEXT_HTML = "text/html"

internal const val APPLICATION_XHTML = "application/xhtml+xml"

internal const val TEXT_CSV = "text/csv"
