package infoscry.storage

import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.RescanPreview
import infoscry.ocr.RescanSelection
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OcrOperationStorePreviewTest {
    private lateinit var dataDir: java.nio.file.Path
    private lateinit var database: Database
    private lateinit var collectionId: String
    private var documentId = DocumentId("document-preview")
    private lateinit var operations: OcrOperationStore

    @BeforeTest
    fun setUp() {
        dataDir = Files.createTempDirectory("infoscry-ocr-preview")
        database = Database(dataDir.resolve("state.db"))
        SchemaMigrator(database).migrate()
        val collection = CollectionStore(database).create("OCR preview")
        collectionId = collection.id.value
        DocumentStore(database).insert(
            Document(
                id = documentId,
                collectionId = collection.id,
                sha256 = "a".repeat(64),
                mediaType = "application/pdf",
                originalFilename = "source.pdf",
                sourcePath = "/tmp/source.pdf",
                sizeBytes = 8,
                status = DocumentStatus.COMPLETE,
                createdAt = "2026-10-09T10:00:00Z",
                updatedAt = "2026-10-09T10:00:00Z",
            ),
        )
        operations = OcrOperationStore(database)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `stored preview retains the document page count`() {
        val preview = preview(documentPages = 12)

        operations.recordPreview(collectionId, documentId, preview)

        assertEquals(12, operations.preview(preview.previewId)?.documentPages)
    }

    @Test
    fun `preview without a stored document page count reads as null`() {
        val preview = preview(documentPages = null)

        operations.recordPreview(collectionId, documentId, preview)

        assertEquals(null, operations.preview(preview.previewId)?.documentPages)
    }

    @Test
    fun `canonical selection survives closing and reopening the archive`() {
        val preview = preview(documentPages = 12).copy(
            selection = RescanSelection.explicit(listOf(8, 2, 8)).canonicalized(12),
        )
        operations.recordPreview(collectionId, documentId, preview)

        database.close()
        database = Database(dataDir.resolve("state.db"))
        SchemaMigrator(database).migrate()
        operations = OcrOperationStore(database)

        assertEquals(RescanSelection.explicit(listOf(2, 8)), operations.preview(preview.previewId)?.selection)
    }

    private fun preview(documentPages: Int?) = RescanPreview(
        previewId = "preview-${documentPages ?: "unknown"}",
        documentId = documentId,
        baseRevisionId = null,
        managedHash = "a".repeat(64),
        snapshot = OcrSettingsSnapshot(
            engine = OcrEngine.TESSERACT,
            mode = OcrImportMode.READ_ALL,
            language = "eng",
            extractorVersion = "test",
        ),
        snapshotHash = "b".repeat(64),
        pageTotal = 4,
        externalPageUpperBound = 4,
        expiresAt = "2026-10-09T11:00:00Z",
        documentPages = documentPages,
    )
}
