package infoscry.storage

import infoscry.chunk.ChunkDraft
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The durable content of one document: its units, the chunks built from them, and what an attempt already
 * committed.
 *
 * Everything here is about what survives a killed process. A unit commits with its artifact in one
 * transaction, a checkpoint is what a resume skips, a committed artifact that no longer verifies is
 * invalidated per unit rather than wholesale, and the marker of a complete pass is kept apart from the pages
 * so that only a genuine finish can produce it.
 */
class ContentStoreTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var store: ContentStore
    private lateinit var documents: DocumentStore

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
        dataDir = Files.createTempDirectory("infoscry-content")
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
    fun `a committed unit round-trips with its locator and both text forms`() {
        val document = newDocument()

        val commit = store.commitExtractedUnit(
            documentId = document,
            fingerprint = fingerprint,
            key = "page-1",
            ordinal = 0,
            draft = draftOf(
                locator = SourceLocation.PdfPage(1),
                extracted = "Faktura 1986\n",
                search = "Faktura 1986\n",
            ),
            artifactRoot = artifactRoot(document),
        )

        val stored = store.readUnit(commit.unit.id)
        assertNotNull(stored)
        assertEquals(SourceLocation.PdfPage(1), stored.locator)
        assertEquals("Faktura 1986\n", stored.extractedText)
        assertEquals("Faktura 1986\n", stored.searchText)
        assertEquals(document, stored.documentId)
        assertEquals(0, stored.ordinal)
    }

    @Test
    fun `committing the same ordinal again keeps the unit's identity and replaces its text`() {
        val document = newDocument()
        val first = store.commitExtractedUnit(
            document, fingerprint, "page-1", 0, draftOf(extracted = "first", search = "first"), artifactRoot(document),
        )

        val second = store.commitExtractedUnit(
            document, fingerprint, "page-1", 0, draftOf(extracted = "second", search = "second"), artifactRoot(document),
        )

        assertEquals(first.unit.id, second.unit.id, "a re-read unit must keep its identity for its citations")
        assertEquals("second", store.readUnit(first.unit.id)?.extractedText)
        assertEquals(1, store.listUnits(document, afterOrdinal = -1, limit = 10).size)
    }

    @Test
    fun `a failed unit is checkpointed and is not retried on a later attempt`() {
        val document = newDocument()

        store.commitFailedUnit(document, fingerprint, "page-7", 6, "OCR_FAILED")

        val checkpoints = store.loadCheckpoints(document, fingerprint)
        assertEquals(1, checkpoints.size)
        val checkpoint = checkpoints.single()
        assertEquals("page-7", checkpoint.key)
        assertFalse(checkpoint.succeeded)
        assertEquals("OCR_FAILED", checkpoint.errorCode)
        // A known failure is a committed result: a resume skips it rather than deriving the same failure from
        // the same page again.
        assertEquals(setOf("page-7"), store.reusableCheckpoints(document, fingerprint, artifactRoot(document)).skipKeys)
    }

    @Test
    fun `a complete pass writes a marker with its unit and failure counts`() {
        val document = newDocument()
        store.commitExtractedUnit(document, fingerprint, "page-1", 0, draftOf(), artifactRoot(document))
        store.commitFailedUnit(document, fingerprint, "page-2", 1, "OCR_FAILED")

        assertNull(store.extractionMarker(document), "a marker exists only after a pass reports it finished")

        val marker = store.finishExtraction(
            documentId = document,
            fingerprint = fingerprint,
            metadata = mapOf("title" to "Minutes"),
            totalUnits = 2,
        )

        assertEquals(2, marker.totalUnits)
        assertEquals(1, marker.failedUnits)
        assertEquals(mapOf("title" to "Minutes"), marker.metadata)
        assertEquals(marker, store.extractionMarker(document))
    }

    @Test
    fun `a checkpoint whose artifact no longer verifies is invalidated per unit and reported`() {
        val document = newDocument()
        val root = artifactRoot(document)
        val artifact = writeArtifact(root, "ocr/page-000001.tsv.gz", "boxes")
        val ok = store.commitExtractedUnit(
            document, fingerprint, "page-1", 0,
            draftOf().copy(artifactRelativePath = "ocr/page-000001.tsv.gz", artifactSha256 = sha256Of(artifact)),
            artifactRoot = root,
        )
        store.commitExtractedUnit(document, fingerprint, "page-2", 1, draftOf(), artifactRoot = root)

        // The artifact is damaged after it was committed.
        Files.writeString(artifact, "corrupted")

        val reuse = store.reusableCheckpoints(document, fingerprint, root)

        assertEquals(listOf("page-1"), reuse.repaired)
        assertEquals(setOf("page-2"), reuse.skipKeys, "a unit whose evidence is gone must be read again")
        // The text stays and the unusable artifact reference goes with the checkpoint: a citation must not
        // open evidence that is not there.
        assertEquals(ok.unit.id, store.readUnit(ok.unit.id)?.id)
        assertNull(store.readUnit(ok.unit.id)?.artifactRelativePath)
        assertEquals(setOf("page-2"), store.reusableCheckpoints(document, fingerprint, root).skipKeys)
        assertTrue(store.reusableCheckpoints(document, fingerprint, root).repaired.isEmpty(), "repairs are reported once")
    }

    @Test
    fun `a unit naming an artifact that is not there is stored without the reference and marked failed`() {
        val document = newDocument()

        val commit = store.commitExtractedUnit(
            document, fingerprint, "page-1", 0,
            draftOf().copy(artifactRelativePath = "ocr/missing.tsv.gz", artifactSha256 = "0".repeat(64)),
            artifactRoot = artifactRoot(document),
        )

        assertNotNull(commit.artifactIssue)
        assertNull(commit.unit.artifactRelativePath, "an unverifiable artifact must not be cited")
        assertNull(commit.unit.artifactSha256)
        assertEquals("still readable", commit.unit.extractedText, "the text is evidence even when its artifact is gone")
        val checkpoint = store.loadCheckpoints(document, fingerprint).single()
        assertFalse(checkpoint.succeeded)
        assertEquals(ContentStore.ARTIFACT_UNVERIFIED_CODE, checkpoint.errorCode)
    }

    @Test
    fun `an artifact that verifies is stored with the unit`() {
        val document = newDocument()
        val root = artifactRoot(document)
        val artifact = writeArtifact(root, "ocr/page-000001.tsv.gz", "boxes")

        val commit = store.commitExtractedUnit(
            document, fingerprint, "page-1", 0,
            draftOf().copy(
                artifactRelativePath = "ocr/page-000001.tsv.gz",
                artifactSha256 = sha256Of(artifact),
                meanConfidence = 0.91,
            ),
            artifactRoot = root,
        )

        assertNull(commit.artifactIssue)
        assertEquals("ocr/page-000001.tsv.gz", commit.unit.artifactRelativePath)
        assertEquals(sha256Of(artifact), commit.unit.artifactSha256)
        assertEquals(0.91, commit.unit.meanConfidence)
        assertTrue(store.loadCheckpoints(document, fingerprint).single().succeeded)
    }

    @Test
    fun `chunks replace per unit and their spans and token offsets round-trip`() {
        val document = newDocument()
        val unit = store.commitExtractedUnit(
            document, fingerprint, "page-1", 0, draftOf(search = "alpha beta gamma"), artifactRoot(document),
        ).unit

        val count = store.replaceUnitChunks(
            unitId = unit.id,
            drafts = listOf(
                ChunkDraft(ordinal = 0, text = "alpha beta", startOffset = 0, endOffset = 10, tokenCount = 5, tokenStart = 3, tokenEnd = 4),
                ChunkDraft(ordinal = 1, text = "beta gamma", startOffset = 6, endOffset = 16, tokenCount = 5, tokenStart = 3, tokenEnd = 4),
            ),
            chunkerVersion = "1",
            tokenizerId = "test-3",
            maxSequenceTokens = 512,
            overlapTokens = 1,
        )
        assertEquals(2, count)

        val replaced = store.replaceUnitChunks(
            unitId = unit.id,
            drafts = listOf(
                ChunkDraft(ordinal = 0, text = "alpha beta gamma", startOffset = 0, endOffset = 16, tokenCount = 6, tokenStart = 3, tokenEnd = 5),
            ),
            chunkerVersion = "1",
            tokenizerId = "test-3",
            maxSequenceTokens = 512,
            overlapTokens = 1,
        )

        assertEquals(1, replaced, "replacing a unit's chunks leaves exactly the new ones")
        val chunks = store.chunksOf(unit.id)
        assertEquals(1, chunks.size)
        val chunk = chunks.single()
        assertEquals(unit.id, chunk.contentUnitId)
        assertEquals(0, chunk.ordinal)
        assertEquals(0, chunk.startOffset)
        assertEquals(16, chunk.endOffset)
        assertEquals(6, chunk.tokenCount)
        assertEquals(3, chunk.tokenStart)
        assertEquals(5, chunk.tokenEnd)
        assertEquals(1, store.chunkCount(document))
    }

    @Test
    fun `re-chunking is skipped only when the chunker and its tokenizer are unchanged`() {
        val document = newDocument()
        val unit = store.commitExtractedUnit(document, fingerprint, "page-1", 0, draftOf(), artifactRoot(document)).unit
        assertTrue(store.needsChunking(document, "1", "test-3", 512, 100), "a document with no chunks needs them")

        store.replaceUnitChunks(
            unitId = unit.id,
            drafts = listOf(ChunkDraft(0, "body", 0, 4, 4, 1, 2)),
            chunkerVersion = "1",
            tokenizerId = "test-3",
            maxSequenceTokens = 512,
            overlapTokens = 100,
        )
        // Chunks alone do not make a document chunked: the pass has to say it walked every unit.
        assertTrue(store.needsChunking(document, "1", "test-3", 512, 100))
        store.finishChunking(document, "1", "test-3", 512, 100, unitCount = 1)

        assertFalse(store.needsChunking(document, "1", "test-3", 512, 100))
        assertTrue(store.needsChunking(document, "1", "e5-768", 512, 100), "another tokenizer measures other chunks")
        assertTrue(store.needsChunking(document, "2", "test-3", 512, 100), "another chunker builds other chunks")
        assertTrue(store.needsChunking(document, "1", "test-3", 512, 50), "another budget builds other chunks")
        assertTrue(store.needsChunking(document, "1", "test-3", 512, 100) == false)
        // A pass that chunked a prefix of the document must not pass for the whole one: the marker counts the
        // units it walked, so a unit read afterwards puts the document back in the queue for chunking.
        store.commitExtractedUnit(document, fingerprint, "page-2", 1, draftOf(), artifactRoot(document))
        assertTrue(store.needsChunking(document, "1", "test-3", 512, 100), "a unit the pass never walked has no chunks")
    }

    @Test
    fun `committing a unit with new text drops its stale chunks`() {
        val document = newDocument()
        val unit = store.commitExtractedUnit(
            document, fingerprint, "page-1", 0, draftOf(search = "first reading"), artifactRoot(document),
        ).unit
        store.replaceUnitChunks(
            unitId = unit.id,
            drafts = listOf(ChunkDraft(0, "first reading", 0, 13, 5, 3, 4)),
            chunkerVersion = "1",
            tokenizerId = "test-3",
            maxSequenceTokens = 512,
            overlapTokens = 100,
        )
        store.finishChunking(document, "1", "test-3", 512, 100, unitCount = 1)
        assertFalse(store.needsChunking(document, "1", "test-3", 512, 100))

        store.commitExtractedUnit(
            document, fingerprint, "page-1", 0, draftOf(search = "second reading"), artifactRoot(document),
        )

        assertEquals(0, store.chunksOf(unit.id).size, "chunks of the replaced text must not stay indexable")
        assertTrue(store.needsChunking(document, "1", "test-3", 512, 100))
    }

    @Test
    fun `units of another fingerprint are not reused`() {
        val document = newDocument()
        val swedish = ExtractionFingerprint.of("f".repeat(64), ExtractionSettings(ocrLanguages = "swe+eng"))
        store.commitExtractedUnit(document, fingerprint, "page-1", 0, draftOf(), artifactRoot(document))

        assertTrue(store.reusableCheckpoints(document, swedish, artifactRoot(document)).skipKeys.isEmpty())
        assertTrue(store.loadCheckpoints(document, swedish).isEmpty(), "another fingerprint's units are not this one's")
    }

    @Test
    fun `a document's units are listed incrementally in ordinal order`() {
        val document = newDocument()
        repeat(5) { index ->
            store.commitExtractedUnit(
                document, fingerprint, "page-${index + 1}", index, draftOf(), artifactRoot(document),
            )
        }

        val first = store.listUnits(document, afterOrdinal = -1, limit = 2)
        val second = store.listUnits(document, afterOrdinal = first.last().ordinal, limit = 2)

        assertEquals(listOf(0, 1), first.map { it.ordinal })
        assertEquals(listOf(2, 3), second.map { it.ordinal })
    }

    @Test
    fun `structure reports identities and locators without the text`() {
        val document = newDocument()
        store.commitExtractedUnit(
            document, fingerprint, "sheet-1", 0,
            draftOf(locator = SourceLocation.SpreadsheetRange("Sheet1", "A1", "B9")),
            artifactRoot(document),
        )

        val structure = store.listStructure(document)

        assertEquals(1, structure.size)
        val entry = structure.single()
        assertEquals(0, entry.ordinal)
        assertEquals(SourceLocation.SpreadsheetRange("Sheet1", "A1", "B9"), entry.locator)
        assertEquals(
            store.listUnits(document, -1, 10).single().id,
            entry.id,
            "structure and the unit reader must name the same unit",
        )
    }

    @Test
    fun `deleting a document removes its content`() {
        val document = newDocument()
        val unit = store.commitExtractedUnit(document, fingerprint, "page-1", 0, draftOf(), artifactRoot(document)).unit
        store.replaceUnitChunks(unit.id, listOf(ChunkDraft(0, "body", 0, 4, 4, 1, 2)), "1", "test-3", 512, 100)
        store.finishExtraction(document, fingerprint, emptyMap(), 1)

        documents.delete(document)

        assertEquals(0, store.listUnits(document, -1, 10).size)
        assertEquals(0, store.chunksOf(unit.id).size)
        assertEquals(0, store.loadCheckpoints(document, fingerprint).size)
        assertNull(store.extractionMarker(document))
        assertEquals(0, store.chunkCount(document))
    }

    @Test
    fun `an artifact path outside the artifact root is refused`() {
        val document = newDocument()
        val outside = Files.writeString(dataDir.resolve("outside.txt"), "not an artifact")

        val commit = store.commitExtractedUnit(
            document, fingerprint, "page-1", 0,
            draftOf().copy(artifactRelativePath = "../outside.txt", artifactSha256 = sha256Of(outside)),
            artifactRoot = artifactRoot(document),
        )

        assertNotNull(commit.artifactIssue, "a relative path that escapes the root names no artifact of this unit")
        assertNull(commit.unit.artifactRelativePath)
    }

    private fun newDocument(): DocumentId {
        val document = Document(
            id = DocumentId.new(),
            collectionId = collectionId,
            sha256 = "f".repeat(64),
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

    private fun draftOf(
        locator: SourceLocation = SourceLocation.TextLines(start = 1, end = 1),
        extracted: String = "still readable",
        search: String = extracted,
    ): ContentUnitDraft = ContentUnitDraft(locator = locator, extractedText = extracted, searchText = search)

    private fun artifactRoot(document: DocumentId): Path =
        dataDir.resolve("library").resolve(collectionId.value).resolve(document.value).resolve("artifacts")

    private fun writeArtifact(root: Path, relativePath: String, content: String): Path {
        val artifact = root.resolve(relativePath)
        Files.createDirectories(artifact.parent)
        Files.writeString(artifact, content)
        return artifact
    }

    private fun sha256Of(path: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
}
