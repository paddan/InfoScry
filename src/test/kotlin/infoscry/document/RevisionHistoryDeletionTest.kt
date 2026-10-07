package infoscry.document

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.jobs.seedCollectionWithId
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.storage.PageApproval
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Deleting a document or a collection removes its whole history — and only its own.
 *
 * History is not a soft-delete feature. A document that was rescanned, restored and left with an unresolved
 * proposal owns revisions, page texts, chunks, vectors, publication intents, restore records, operations,
 * previews and reviews; every one of them has to go with it, including the revision a restore created. What
 * must never go is anything the document did not own: the external original it was imported from, and the
 * managed copy and history of another collection that imported the same bytes.
 */
class RevisionHistoryDeletionTest {

    private lateinit var dataDir: Path
    private lateinit var externalDir: Path
    private lateinit var original: Path
    private val embedder = SwitchableEmbedder()
    private val collection = CollectionId("default")
    private val originalBytes = "an external original that is not InfoScry's to touch".toByteArray()

    @BeforeTest
    fun createDirectories() {
        dataDir = Files.createTempDirectory("infoscry-history-deletion")
        // The external original lives outside the data directory, exactly as a user's own file does.
        externalDir = Files.createTempDirectory("infoscry-external-original")
        original = externalDir.resolve("ledger.txt")
        Files.write(original, originalBytes)
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeDirectories() {
        dataDir.toFile().deleteRecursively()
        externalDir.toFile().deleteRecursively()
    }

    private fun open(): AppContext = AppContext.open(dataDir, restoreEmbedder = embedder.provider)

    /** One archive: the same external file imported into two collections, with a full history in the first. */
    private class Archive(
        val doomed: RestoreDocument,
        val bystander: RestoreDocument,
        val doomedFile: Path,
        val bystanderFile: Path,
    )

    private fun seed(context: AppContext): Archive {
        val other = context.collections.create("Other")
        val doomedId = runBlocking { context.library.importFile(collection, original) }.document.id
        val bystanderId = runBlocking { context.library.importFile(other.id, original) }.document.id
        val doomed = RestoreFixtures.publishGenerations(context, dataDir, collection, documentId = doomedId)
        val bystander = RestoreFixtures.publishGenerations(context, dataDir, other.id, documentId = bystanderId)

        // The doomed document's history is as full as a real one gets: a restore (a revision of its own), an
        // unresolved proposal staged against the active reading, an operation, a preview and a review.
        val restore = runBlocking {
            context.revisionRestore.restore(
                collection, doomed.documentId, "restore-1", doomed.revisionIds.last(), doomed.revisionIds.first(),
            )
        }
        RestoreFixtures.stage(
            context, doomed.documentId, restore.newRevisionId, listOf("proposal", "pending"), doomed.unitIds,
            approval = PageApproval.PENDING,
        )
        context.ocrOperations.admit(
            collectionId = collection.value,
            documentId = doomed.documentId,
            baseRevisionId = restore.newRevisionId,
            snapshot = OcrSettingsSnapshot(
                engine = OcrEngine.TESSERACT,
                mode = OcrImportMode.FILL_MISSING,
                language = "eng",
                extractorVersion = "test",
            ),
            requestId = "rescan-1",
            requestHash = "b".repeat(64),
            pageTotal = 2,
            jobId = null,
        )
        insertPreviewAndReview(context, doomed, restore.newRevisionId)

        return Archive(
            doomed = doomed,
            bystander = bystander,
            doomedFile = context.paths.documentDir(collection, doomed.documentId),
            bystanderFile = context.paths.documentDir(other.id, bystander.documentId),
        )
    }

    private fun insertPreviewAndReview(context: AppContext, doomed: RestoreDocument, baseline: String) {
        context.database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO ocr_rescan_previews (preview_id, collection_id, document_id, base_revision_id, " +
                    "managed_sha256, snapshot, snapshot_hash, approval_required, created_at, expires_at) " +
                    "VALUES ('preview-1', ?, ?, ?, ?, '{}', ?, 0, '2026-10-07T00:00:00Z', '2026-10-08T00:00:00Z')",
            ).use { statement ->
                statement.setString(1, collection.value)
                statement.setString(2, doomed.documentId.value)
                statement.setString(3, baseline)
                statement.setString(4, "c".repeat(64))
                statement.setString(5, "d".repeat(64))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO page_reviews (review_fingerprint, image_sha256, document_id, unit_id, ordinal, " +
                    "baseline_revision_id, baseline_text_hash, candidate_hash, recommendation, disposition, " +
                    "reviewer_revision_id, review_prompt_version, policy_version, searchable, reasons, " +
                    "created_at) VALUES (?, ?, ?, ?, 1, ?, ?, ?, 'UNCERTAIN', 'PROPOSE', 'reviewer-1', 1, 1, 0, " +
                    "'[]', '2026-10-07T00:00:00Z')",
            ).use { statement ->
                statement.setString(1, "e".repeat(64))
                statement.setString(2, "f".repeat(64))
                statement.setString(3, doomed.documentId.value)
                statement.setString(4, doomed.unitIds[1].value)
                statement.setString(5, baseline)
                statement.setString(6, "1".repeat(64))
                statement.setString(7, "2".repeat(64))
                statement.executeUpdate()
            }
        }
    }

    private val documentTables = listOf(
        "document_revisions",
        "document_active_revisions",
        "revision_publications",
        "revision_restores",
        "ocr_operations",
        "ocr_rescan_previews",
        "page_reviews",
        "content_units",
    )

    private fun historyRows(context: AppContext, documentId: DocumentId): Map<String, Int> =
        documentTables.associateWith { table -> RestoreFixtures.rows(context, table, documentId) } +
            mapOf(
                "page_text_revisions" to RestoreFixtures.revisionRows(context, "page_text_revisions", documentId),
                "revision_chunks" to RestoreFixtures.revisionRows(context, "revision_chunks", documentId),
            )

    private fun orphanRows(context: AppContext): Int = listOf("page_text_revisions", "revision_chunks").sumOf { table ->
        context.database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM $table WHERE revision_id NOT IN (SELECT id FROM document_revisions)",
                ).use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
    }

    @Test
    fun deletingADocumentRemovesItsHistoryProposalsAndRestoresButNeverAnOriginalOrAnotherCollectionsCopy() {
        open().use { context ->
            val archive = seed(context)
            val seeded = historyRows(context, archive.doomed.documentId)
            assertTrue(seeded.all { (_, rows) -> rows > 0 }, "the fixture is not as full as it should be: $seeded")
            val bystanderBefore = historyRows(context, archive.bystander.documentId)
            val bystanderBytes = Files.readAllBytes(managedCopy(archive.bystanderFile))
            assertTrue(Files.exists(archive.doomedFile))

            runBlocking { context.documentService.deleteConfirmed(collection, listOf(archive.doomed.documentId)) }

            // Everything the document owned is gone: its revisions, every revision's pages, chunks and vectors,
            // the restore's own revision and record, the proposal, the operation, the preview and the review.
            assertEquals(
                seeded.keys.associateWith { 0 },
                historyRows(context, archive.doomed.documentId),
            )
            assertEquals(0, orphanRows(context), "a revision's pages or chunks outlived the revision")
            assertEquals(false, Files.exists(archive.doomedFile), "the document's managed copy and artifacts remain")
            assertEquals(emptyList(), RestoreFixtures.searchTexts(context, "harbour", collection))

            // Nothing that was not the document's was touched: the external original is byte for byte what it
            // was, and the other collection's copy, history and searchable text are exactly as they were.
            assertContentEquals(originalBytes, Files.readAllBytes(original))
            assertEquals(bystanderBefore, historyRows(context, archive.bystander.documentId))
            assertContentEquals(bystanderBytes, Files.readAllBytes(managedCopy(archive.bystanderFile)))
            val otherCollection = assertNotNull(context.documents.get(archive.bystander.documentId)).collectionId
            assertEquals(
                listOf("market third"),
                RestoreFixtures.searchTexts(context, "market", otherCollection),
            )
        }
    }

    @Test
    fun deletingACollectionRemovesEveryRevisionRowAndLeavesTheOtherCollectionAndTheOriginalAlone() {
        open().use { context ->
            val archive = seed(context)
            val bystanderBefore = historyRows(context, archive.bystander.documentId)
            val bystanderBytes = Files.readAllBytes(managedCopy(archive.bystanderFile))

            runBlocking { context.collectionService.deleteConfirmed(collection, "Default") }

            assertEquals(
                historyRows(context, archive.doomed.documentId).keys.associateWith { 0 },
                historyRows(context, archive.doomed.documentId),
            )
            assertEquals(0, orphanRows(context))
            assertEquals(false, Files.exists(archive.doomedFile))

            assertContentEquals(originalBytes, Files.readAllBytes(original))
            assertEquals(bystanderBefore, historyRows(context, archive.bystander.documentId))
            assertContentEquals(bystanderBytes, Files.readAllBytes(managedCopy(archive.bystanderFile)))
            val otherCollection = assertNotNull(context.documents.get(archive.bystander.documentId)).collectionId
            assertEquals(
                listOf("market third"),
                RestoreFixtures.searchTexts(context, "market", otherCollection),
            )
        }
    }

    /** The one managed file of a document's directory: the immutable copy of its original. */
    private fun managedCopy(directory: Path): Path = Files.walk(directory).use { entries ->
        entries.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("original") }.findFirst().get()
    }
}
