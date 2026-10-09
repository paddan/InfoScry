package infoscry.storage

import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.OcrSettingsSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OcrOperationReplacementTest {
    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var collections: CollectionStore
    private lateinit var documents: DocumentStore
    private lateinit var operations: OcrOperationStore

    @BeforeTest
    fun setUp() {
        dataDir = Files.createTempDirectory("infoscry-ocr-replacement")
        database = Database(dataDir.resolve("state.db"))
        SchemaMigrator(database).migrate()
        collections = CollectionStore(database)
        documents = DocumentStore(database)
        operations = OcrOperationStore(database)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a new admission cancels an active operation atomically and is repeatable`() {
        val collection = collections.create("OCR replacement")
        val documentId = DocumentId("document-replacement")
        documents.insert(
            Document(
                id = documentId,
                collectionId = collection.id,
                sha256 = "a".repeat(64),
                mediaType = "text/plain",
                originalFilename = "source.txt",
                sourcePath = "/tmp/source.txt",
                sizeBytes = 8,
                status = DocumentStatus.COMPLETE,
                createdAt = "2026-10-08T10:00:00Z",
                updatedAt = "2026-10-08T10:00:00Z",
            ),
        )
        val snapshot = OcrSettingsSnapshot(
            engine = OcrEngine.TESSERACT,
            mode = OcrImportMode.READ_ALL,
            language = "eng",
            extractorVersion = "test",
        )
        val previous = operations.admit(
            collectionId = collection.id.value,
            documentId = documentId,
            baseRevisionId = null,
            snapshot = snapshot,
            requestId = "first",
            requestHash = "1".repeat(64),
            pageTotal = 1,
            jobId = null,
        )
        operations.advance(previous.operationId, OcrOperationStage.OCR)

        val replacement = operations.admit(
            collectionId = collection.id.value,
            documentId = documentId,
            baseRevisionId = null,
            snapshot = snapshot,
            requestId = "second",
            requestHash = "2".repeat(64),
            pageTotal = 1,
            jobId = null,
        )

        assertEquals(OcrOperationStage.CANCELLED, operations.operation(previous.operationId)?.stage)
        assertEquals(replacement.operationId, operations.activeOperation(documentId)?.operationId)
        assertNotEquals(previous.operationId, replacement.operationId)
        assertEquals(
            replacement.operationId,
            operations.admit(
                collectionId = collection.id.value,
                documentId = documentId,
                baseRevisionId = null,
                snapshot = snapshot,
                requestId = "second",
                requestHash = "2".repeat(64),
                pageTotal = 1,
                jobId = null,
            ).operationId,
        )
        assertEquals(2, operations.operations(documentId).size)
    }
}
