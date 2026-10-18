package infoscry.ask

import infoscry.chunk.ChunkDraft
import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnit
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.ocr.BlockDraft
import infoscry.ocr.BlockKind
import infoscry.ocr.CellDraft
import infoscry.ocr.DerivedPage
import infoscry.ocr.PageStructureDraft
import infoscry.ocr.PageStructureStore
import infoscry.ocr.StructureDerivation
import infoscry.search.SearchHit
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.DocumentStore
import infoscry.storage.Instants
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredEvidenceSourceTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var content: ContentStore
    private lateinit var documents: DocumentStore
    private var createdCollectionId: CollectionId? = null
    private lateinit var paths: AppPaths

    private val collectionId: CollectionId
        get() = checkNotNull(createdCollectionId) { "the fixture collection has not been created" }

    private val fingerprint = ExtractionFingerprint.of(
        sha256 = "f".repeat(64),
        settings = ExtractionSettings(ocrLanguages = "eng"),
    )

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-structured-source")
        database = Database(dataDir.resolve("infoscry.db"))
        SchemaMigrator(database).migrate()
        content = ContentStore(database)
        documents = DocumentStore(database)
        createdCollectionId = CollectionStore(database).create("Acme").id
        paths = AppPaths.of(dataDir)
    }

    @AfterTest
    fun closeDataDirectory() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun chunksOfAStructuredUnitAreSentAsMarkdown() {
        val page = StructureDerivation.derive(
            PageStructureDraft(
                "test/1",
                listOf(
                    BlockDraft(BlockKind.HEADING, "Report", level = 1),
                    BlockDraft(
                        BlockKind.TABLE, "",
                        rows = listOf(
                            listOf(CellDraft("Name", true), CellDraft("Qty", true)),
                            listOf(CellDraft("apple", false), CellDraft("3", false)),
                            listOf(CellDraft("pear", false), CellDraft("5", false)),
                        ),
                    ),
                ),
            ),
            imageSha256 = "a".repeat(64),
        )
        val document = newDocument()
        val unit = commitStructured(document, page)
        val pear = page.searchText.indexOf("pear")
        // Chunk 0 covers the whole text; chunk 1 starts inside the table, at the pear row.
        storeChunks(unit, page.searchText, listOf(0 to 0, 1 to pear))

        val session = session()
        assertEquals(
            "# Report\n\n| Name | Qty |\n| --- | --- |\n| apple | 3 |\n| pear | 5 |",
            session.textOf(hit(document, unit, 0)),
        )
        assertEquals(
            "| Name | Qty |\n| --- | --- |\n| pear | 5 |",
            session.textOf(hit(document, unit, 1)),
        )
    }

    @Test
    fun aHitWhoseTextIsNotItsChunkIsSentAsItWas() {
        val page = reportPage()
        val document = newDocument()
        val unit = commitStructured(document, page)
        storeChunks(unit, page.searchText, listOf(0 to 0))

        assertEquals("something else entirely", session().textOf(searchHit(document, unit, 0, "something else entirely")))
    }

    @Test
    fun offsetsOutsideTheUnitFallBackToTheHitText() {
        val page = reportPage()
        val document = newDocument()
        val unit = commitStructured(document, page)
        storeDrafts(unit, listOf(chunkDraft(0, "Report", 0, page.searchText.length + 5)))

        assertEquals("Report", session().textOf(searchHit(document, unit, 0, "Report")))
    }

    @Test
    fun offsetsNotAddressingTheChunkTextFallBackToTheHitText() {
        val page = StructureDerivation.derive(
            PageStructureDraft(
                "test/1",
                listOf(
                    BlockDraft(BlockKind.HEADING, "Report", level = 1),
                    BlockDraft(BlockKind.PARAGRAPH, "Body"),
                ),
            ),
            imageSha256 = "a".repeat(64),
        )
        val document = newDocument()
        val unit = commitStructured(document, page)
        // The stored text says "Body" but its offsets address the heading: rendering them would show the wrong words.
        storeDrafts(unit, listOf(chunkDraft(0, "Body", 0, 6)))

        assertEquals("Body", session().textOf(searchHit(document, unit, 0, "Body")))
    }

    @Test
    fun aHitNamingAnotherDocumentFallsBackToItsText() {
        val page = reportPage()
        val document = newDocument()
        val unit = commitStructured(document, page)
        storeChunks(unit, page.searchText, listOf(0 to 0))
        val other = newDocument()

        assertEquals(
            "hit text of another document",
            session().textOf(searchHit(other, unit, 0, "hit text of another document")),
        )
    }

    @Test
    fun duplicateChunkTextsResolveByOrdinal() {
        val page = StructureDerivation.derive(
            PageStructureDraft(
                "test/1",
                listOf(
                    BlockDraft(BlockKind.HEADING, "Note", level = 1),
                    BlockDraft(BlockKind.PARAGRAPH, "Note"),
                ),
            ),
            imageSha256 = "a".repeat(64),
        )
        val document = newDocument()
        val unit = commitStructured(document, page)
        storeDrafts(unit, listOf(chunkDraft(0, "Note", 0, 4), chunkDraft(1, "Note", 5, 9)))

        val session = session()
        assertEquals("# Note", session.textOf(searchHit(document, unit, 0, "Note")))
        assertEquals("Note", session.textOf(searchHit(document, unit, 1, "Note")))
    }

    @Test
    fun aUnitWithoutStructureKeepsItsExtractedText() {
        val document = newDocument()
        val committed = content.commitExtractedUnit(
            document, fingerprint, "page-plain", 0,
            ContentUnitDraft(
                locator = SourceLocation.PdfPage(1),
                extractedText = "plain reading",
                searchText = "plain reading",
                method = ExtractionMethod.DIRECT_TEXT,
            ),
            paths.artifactsDir(collectionId, document),
        )

        val unit = checkNotNull(content.readUnit(committed.unit.id))
        assertEquals("plain reading", session().unitText(unit))
    }

    private fun session() = StructuredEvidenceSource(content, paths).session(collectionId)

    private fun reportPage(): DerivedPage = StructureDerivation.derive(
        PageStructureDraft("test/1", listOf(BlockDraft(BlockKind.HEADING, "Report", level = 1))),
        imageSha256 = "a".repeat(64),
    )

    /** Commits [page] as one unit whose structure is written where the unit's artifact root is. */
    private fun commitStructured(document: DocumentId, page: DerivedPage): ContentUnit {
        val root = paths.artifactsDir(collectionId, document)
        val reference = PageStructureStore.write(root, 0, page.structure)
        return content.commitExtractedUnit(
            document, fingerprint, "page-structured", 0,
            ContentUnitDraft(
                locator = SourceLocation.PdfPage(1),
                extractedText = page.extractedText,
                searchText = page.searchText,
                method = ExtractionMethod.DIRECT_TEXT,
                structureReference = reference,
            ),
            root,
        ).unit
    }

    private fun chunkDraft(ordinal: Int, text: String, start: Int, end: Int) = ChunkDraft(
        ordinal = ordinal,
        text = text,
        startOffset = start,
        endOffset = end,
        tokenCount = 4,
        tokenStart = 0,
        tokenEnd = 3,
    )

    /** Stores chunks as `ordinal to start` pairs, each running to the end of [searchText]. */
    private fun storeChunks(unit: ContentUnit, searchText: String, starts: List<Pair<Int, Int>>) {
        storeDrafts(
            unit,
            starts.map { (ordinal, start) -> chunkDraft(ordinal, searchText.substring(start), start, searchText.length) },
        )
    }

    private fun storeDrafts(unit: ContentUnit, drafts: List<ChunkDraft>) {
        content.replaceUnitChunks(unit.id, drafts, "test", "test", 512, 0)
    }

    /** A hit as search would return it: its text is the stored chunk's text, which is what the source checks. */
    private fun hit(document: DocumentId, unit: ContentUnit, ordinal: Int): SearchHit =
        searchHit(document, unit, ordinal, content.chunksOf(unit.id).single { it.ordinal == ordinal }.text)

    private fun searchHit(document: DocumentId, unit: ContentUnit, ordinal: Int, text: String) = SearchHit(
        collectionId, document, unit.id, ordinal, text, null,
        SourceLocation.PdfPage(1), "page 1", emptySet(),
    )

    private fun newDocument(): DocumentId {
        val document = Document(
            id = DocumentId.new(),
            collectionId = collectionId,
            // A distinct checksum per document: the store refuses two documents with one checksum in a collection.
            sha256 = DocumentId.new().value.replace("-", "").padEnd(64, '0'),
            mediaType = "application/pdf",
            originalFilename = "report.pdf",
            sourcePath = "/tmp/report.pdf",
            sizeBytes = 42,
            status = DocumentStatus.EXTRACTING,
            createdAt = Instants.now(),
            updatedAt = Instants.now(),
        )
        documents.insert(document)
        return document.id
    }
}
