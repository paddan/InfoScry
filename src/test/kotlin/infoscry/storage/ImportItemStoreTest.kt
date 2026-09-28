package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobId
import infoscry.domain.JobType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two per-item writes that name a document, and what a document deletion does to them.
 *
 * A deletion can land between an attempt's last document write and its per-item result: its durable
 * disposition marks the item cancelled and its row phase removes the document. Neither write may undo
 * either of those — a foreign key would turn one file's race into the whole import's failure, and an
 * `IMPORTED` written over a cancellation would resurrect work the deletion already decided against.
 */
class ImportItemStoreTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var collections: CollectionStore
    private lateinit var documents: DocumentStore
    private lateinit var jobs: JobStore
    private lateinit var items: ImportItemStore

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-import-items")
        database = Database(dataDir.resolve("infoscry.db"))
        SchemaMigrator(database).migrate()
        collections = CollectionStore(database)
        documents = DocumentStore(database)
        jobs = JobStore(database)
        items = ImportItemStore(database)
    }

    @AfterTest
    fun closeDataDirectory() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a late result does not overwrite the cancellation disposition`() {
        val (jobId, documentId) = stagedItem()
        // What the deletion's admission writes, followed by what its row phase does.
        items.cancelTargeting(jobId, documentId)
        assertTrue(documents.delete(documentId))

        val recorded = items.record(jobId, ITEM_KEY, ImportItemOutcome.IMPORTED, documentId)

        assertEquals(
            ImportItemOutcome.CANCELLED,
            recorded.outcome,
            "the item keeps the disposition the deletion recorded",
        )
        assertNull(recorded.documentId, "the removed document's id is not written back")
        assertEquals(
            ImportItemOutcome.CANCELLED,
            items.attachDocument(jobId, ITEM_KEY, documentId).outcome,
            "attaching obeys the same disposition",
        )
    }

    @Test
    fun `an item write never names a document whose row is gone`() {
        val (jobId, documentId) = stagedItem()
        assertTrue(documents.delete(documentId))

        val recorded = items.record(jobId, ITEM_KEY, ImportItemOutcome.IMPORTED, documentId)

        assertEquals(ImportItemOutcome.IMPORTED, recorded.outcome)
        assertNull(recorded.documentId, "a document id with no row would fail the item's foreign key")
        assertNull(items.attachDocument(jobId, ITEM_KEY, documentId).documentId)
    }

    /** A queued job with one item already attached to a document, the way an attempt leaves it. */
    private fun stagedItem(): Pair<JobId, DocumentId> {
        val collection = collections.create("Acme")
        val document = documents.insert(document(collection.id))
        val job = jobs.enqueue(JobType.IMPORT, collection.id, payload = "{}", total = 1)
        items.queue(job.id, ITEM_KEY, "/private/evidence/one.pdf")
        items.attachDocument(job.id, ITEM_KEY, document.id)
        assertEquals(document.id, items.find(job.id, ITEM_KEY)?.documentId)
        return job.id to document.id
    }

    private fun document(collectionId: CollectionId): Document {
        val now = Instants.now()
        return Document(
            id = DocumentId.new(),
            collectionId = collectionId,
            sha256 = "sha256-of-one",
            mediaType = "application/pdf",
            originalFilename = "one.pdf",
            sourcePath = "/private/evidence/one.pdf",
            sizeBytes = 1024,
            status = DocumentStatus.COPYING,
            createdAt = now,
            updatedAt = now,
        )
    }

    private companion object {
        const val ITEM_KEY = "/private/evidence/one.pdf"
    }
}
