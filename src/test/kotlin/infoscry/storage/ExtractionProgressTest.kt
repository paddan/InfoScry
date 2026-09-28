package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What a document's durable progress adds up to, and what it refuses to claim.
 *
 * The numbers a reader sees while a document is being read come from here: what the extractor announced,
 * what the attempt has committed, and how each committed unit was read. Everything asserted below is about
 * the three ways that could lie — a count that advances before the work is durable, a resumed attempt that
 * counts the same unit twice, and a method or a total nobody actually stated.
 */
class ExtractionProgressTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var store: ContentStore
    private lateinit var documents: DocumentStore

    /** How many documents this test has created, so each one holds different bytes. */
    private var documentCount = 0

    /** A value class cannot be `lateinit`, so the created collection sits behind a plain field. */
    private var createdCollectionId: CollectionId? = null

    private val collectionId: CollectionId
        get() = checkNotNull(createdCollectionId) { "the fixture collection has not been created" }

    private val fingerprint = ExtractionFingerprint.of(
        sha256 = "f".repeat(64),
        settings = ExtractionSettings(ocrLanguages = "eng"),
    )

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-progress")
        database = Database(dataDir.resolve("infoscry.db"))
        SchemaMigrator(database).migrate()
        store = ContentStore(database)
        documents = DocumentStore(database)
        createdCollectionId = CollectionStore(database).create("Acme").id
    }

    @AfterTest
    fun closeDataDirectory() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `what an extractor announced and what the attempt committed are both counted`() {
        val document = newDocument()
        store.recordProgress(document, fingerprint, unitKind = UnitKind.SHEET, totalUnits = 7)

        commit(document, ordinal = 0, locator = sheet(0), method = ExtractionMethod.DIRECT_TEXT)
        commit(document, ordinal = 1, locator = sheet(1), method = ExtractionMethod.OCR)
        store.commitFailedUnit(document, fingerprint, key = "row-2", ordinal = 2, code = "OCR_FAILED")

        val progress = progressOf(document)
        assertEquals(UnitKind.SHEET, progress.unitKind)
        assertEquals(7, progress.totalUnits, "the denominator is the total the extractor announced")
        assertEquals(2, progress.processedUnits)
        assertEquals(1, progress.failedUnits)
        assertEquals(1, progress.directTextUnits)
        assertEquals(1, progress.ocrUnits)
        assertEquals(listOf("OCR_FAILED"), progress.failedCodes)
    }

    @Test
    fun `a count with no announced total keeps its denominator unknown`() {
        val document = newDocument()

        commit(document, ordinal = 0, locator = section(0))
        commit(document, ordinal = 1, locator = section(1))

        val progress = progressOf(document)
        assertNull(progress.totalUnits, "no extractor announced a total, so there is nothing to divide by")
        assertEquals(2, progress.processedUnits)
        assertEquals(
            UnitKind.SECTION,
            progress.unitKind,
            "a Word document's units are sections, and its own locators say so",
        )
    }

    @Test
    fun `the method is what the extractor stated, not the confidence it happened to report`() {
        val document = newDocument()

        // A parser's page that happens to carry a confidence, and a tool's page that carries none: the
        // counts have to follow the statements, not the numbers that could be mistaken for them.
        commit(document, ordinal = 0, locator = page(1), method = ExtractionMethod.DIRECT_TEXT, confidence = 0.91)
        commit(document, ordinal = 1, locator = page(2), method = ExtractionMethod.OCR, confidence = null)

        val progress = progressOf(document)
        assertEquals(1, progress.directTextUnits)
        assertEquals(1, progress.ocrUnits)
        assertEquals(ExtractionMethod.DIRECT_TEXT, store.readUnit(unitIdOf(document, 0))?.extractionMethod)
        assertEquals(ExtractionMethod.OCR, store.readUnit(unitIdOf(document, 1))?.extractionMethod)
    }

    @Test
    fun `a resumed attempt that skips committed units does not count them twice`() {
        val document = newDocument()
        store.recordProgress(document, fingerprint, unitKind = UnitKind.PAGE, totalUnits = 40)

        // The first pass commits two pages, whatever killed it happens here, and the next pass commits the
        // same two keys again because that is what a resume does before it can tell they are done.
        repeat(2) { pass ->
            commit(document, ordinal = 0, locator = page(1), key = "page:1")
            commit(document, ordinal = 1, locator = page(2), key = "page:2")
            assertEquals(2, progressOf(document).processedUnits, "after pass $pass the count is still two")
        }
        store.recordProgress(document, fingerprint, unitKind = UnitKind.PAGE, totalUnits = 40)

        val progress = progressOf(document)
        assertEquals(2, progress.processedUnits, "a unit committed twice is still one committed unit")
        assertEquals(40, progress.totalUnits, "re-announcing inside one attempt does not lose the total")
        assertEquals(2, store.listUnits(document, afterOrdinal = -1, limit = 10).size)
    }

    @Test
    fun `progress starts only when the unit and its checkpoint are committed`() {
        val document = newDocument()
        store.recordProgress(document, fingerprint, unitKind = UnitKind.PAGE, totalUnits = 3)

        assertEquals(
            0,
            progressOf(document).processedUnits,
            "an announced total is not a unit read: nothing is committed yet",
        )

        commit(document, ordinal = 0, locator = page(1))
        assertEquals(1, progressOf(document).processedUnits)
    }

    @Test
    fun `a new fingerprint starts the attempt over, keeping the document's own format`() {
        val document = newDocument()
        store.recordProgress(document, fingerprint, unitKind = UnitKind.PAGE, totalUnits = 40)
        commit(document, ordinal = 0, locator = page(1))

        val retried = ExtractionFingerprint.of(
            sha256 = "f".repeat(64),
            settings = ExtractionSettings(ocrLanguages = "swe"),
        )
        commit(document, ordinal = 0, locator = page(1), fingerprint = retried)

        val progress = progressOf(document)
        assertEquals(1, progress.processedUnits, "the new attempt has committed one page, not two")
        assertNull(progress.totalUnits, "the new attempt announced no total yet")
        assertEquals(UnitKind.PAGE, progress.unitKind, "reading a document again does not change what its units are")
    }

    @Test
    fun `a document whose units carry no method reports unknown rather than zero`() {
        val document = newDocument()
        commit(document, ordinal = 0, locator = page(1), method = ExtractionMethod.DIRECT_TEXT)
        commit(document, ordinal = 1, locator = page(2), method = ExtractionMethod.OCR)

        // What an archive written before the method was recorded looks like: the column did not exist, so
        // those units have no value in it.
        database.transaction { connection ->
            connection.prepareStatement("UPDATE content_units SET extraction_method = NULL").use { it.executeUpdate() }
        }

        val progress = progressOf(document)
        assertEquals(2, progress.processedUnits)
        assertNull(progress.directTextUnits, "an unknown method is not zero units of direct text")
        assertNull(progress.ocrUnits, "nor zero units read by OCR")
    }

    @Test
    fun `a document that finished before progress existed reads its total from its summary`() {
        val document = newDocument()
        commit(document, ordinal = 0, locator = line(1))
        commit(document, ordinal = 1, locator = line(2))
        store.finishExtraction(document, fingerprint, metadata = emptyMap(), totalUnits = 12)

        // The finished summary without the attempt row and without stored methods: exactly the state an
        // archive this build did not write is in.
        database.transaction { connection ->
            connection.prepareStatement("UPDATE content_units SET extraction_method = NULL").use { it.executeUpdate() }
            connection.prepareStatement("DELETE FROM document_extraction_progress").use { it.executeUpdate() }
        }

        val progress = progressOf(document)
        assertEquals(12, progress.totalUnits, "the finished summary already knew how many units there were")
        assertEquals(2, progress.processedUnits)
        assertEquals(UnitKind.LINE, progress.unitKind, "the committed units still say what they are")
        assertNull(progress.directTextUnits)
    }

    @Test
    fun `a document with no attempt at all has no progress to report`() {
        val document = newDocument()
        commit(document, ordinal = 0, locator = page(1))
        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM document_extraction_progress").use { it.executeUpdate() }
            connection.prepareStatement("DELETE FROM extraction_checkpoints").use { it.executeUpdate() }
        }

        assertNull(
            store.documentProgress(listOf(document))[document],
            "a queued document has no counts, and a row of zeroes would be a claim it cannot make",
        )
    }

    @Test
    fun `a page of documents is answered in one map, without inventing rows for the rest`() {
        val reported = newDocument()
        val untouched = newDocument()
        commit(reported, ordinal = 0, locator = page(1))

        val progress = store.documentProgress(listOf(reported, untouched))

        assertEquals(setOf(reported), progress.keys)
        assertEquals(1, progress.getValue(reported).processedUnits)
    }

    @Test
    fun `an announced total cannot be negative`() {
        val document = newDocument()

        assertFailsWith<IllegalArgumentException> {
            store.recordProgress(document, fingerprint, unitKind = UnitKind.PAGE, totalUnits = -1)
        }
        assertNull(store.documentProgress(listOf(document))[document])
    }

    private fun progressOf(document: DocumentId): DocumentProgress =
        checkNotNull(store.documentProgress(listOf(document))[document]) { "the document has no progress row" }

    private fun unitIdOf(document: DocumentId, ordinal: Int) = checkNotNull(
        store.listUnits(document, afterOrdinal = -1, limit = 50).firstOrNull { it.ordinal == ordinal },
    ).id

    private fun commit(
        documentId: DocumentId,
        ordinal: Int,
        locator: SourceLocation,
        key: String = "unit-$ordinal",
        method: ExtractionMethod = ExtractionMethod.DIRECT_TEXT,
        confidence: Double? = null,
        fingerprint: ExtractionFingerprint = this.fingerprint,
    ) {
        store.commitExtractedUnit(
            documentId = documentId,
            fingerprint = fingerprint,
            key = key,
            ordinal = ordinal,
            draft = ContentUnitDraft(
                locator = locator,
                extractedText = "text of unit $ordinal",
                searchText = "text of unit $ordinal",
                method = method,
                meanConfidence = confidence,
            ),
            artifactRoot = artifactRoot(documentId),
        )
    }

    private fun page(number: Int) = SourceLocation.PdfPage(number)

    private fun line(number: Int) = SourceLocation.TextLines(number, number)

    private fun section(index: Int) = SourceLocation.WordSection(
        headingPath = listOf("Heading $index"),
        paragraphStart = index * 10,
        paragraphEnd = index * 10 + 5,
    )

    private fun sheet(row: Int) = SourceLocation.SpreadsheetRange(
        sheet = "Sheet1",
        startCell = "A$row",
        endCell = "B$row",
    )

    private fun newDocument(): DocumentId {
        // Each document needs its own bytes: one collection holds one document per content hash.
        val document = Document(
            id = DocumentId.new(),
            collectionId = collectionId,
            sha256 = (++documentCount).toString().padStart(64, '0'),
            mediaType = "application/pdf",
            originalFilename = "minutes.pdf",
            sourcePath = "/tmp/minutes.pdf",
            sizeBytes = 42,
            status = DocumentStatus.EXTRACTING,
            createdAt = Instants.now(),
            updatedAt = Instants.now(),
        )
        documents.insert(document)
        return document.id
    }

    private fun artifactRoot(document: DocumentId): Path =
        dataDir.resolve("library").resolve(collectionId.value).resolve(document.value).resolve("artifacts")
}
