package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The collection-wide status aggregate at the storage layer: what the grouped read counts, what it
 * deliberately does not count, and that a caller can derive the total from the same snapshot.
 */
class DocumentStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var collections: CollectionStore
    private lateinit var store: DocumentStore

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-document-summary")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        collections = CollectionStore(database)
        store = DocumentStore(database)
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `every status is counted, including the ones no document has`() {
        val collection = collections.create("Nightfall")
        store.insert(document("doc-a", collection.id, status = DocumentStatus.COMPLETE))
        store.insert(document("doc-b", collection.id, status = DocumentStatus.FAILED))
        store.insert(document("doc-c", collection.id, status = DocumentStatus.FAILED))

        val counts = store.statusCountsByCollection(collection.id)

        assertEquals(DocumentStatus.entries.toSet(), counts.keys, "every current status is present")
        assertEquals(2, counts[DocumentStatus.FAILED])
        assertEquals(1, counts[DocumentStatus.COMPLETE])
        assertEquals(0, counts[DocumentStatus.NEEDS_TOOL], "an absent status is zero, not unknown")
        assertEquals(0, counts[DocumentStatus.NEEDS_REVIEW])
        assertEquals(3, counts.values.sum(), "the total is derivable from the same snapshot")
    }

    @Test
    fun `the counts are one collection's own and ignore file-only import items`() {
        val collection = collections.create("Nightfall")
        val other = collections.create("Elsewhere")
        store.insert(document("mine-1", collection.id, status = DocumentStatus.FAILED))
        store.insert(document("theirs-1", other.id, status = DocumentStatus.FAILED))
        store.insert(document("theirs-2", other.id, status = DocumentStatus.FAILED))

        // A file the import queued that has not become a document: file-only, so it is not part of the
        // managed-document eligibility the grouped read shares with the listing.
        val jobs = JobStore(database)
        val importItems = ImportItemStore(database)
        val job = jobs.enqueue(type = JobType.IMPORT, collectionId = collection.id, total = 1)
        importItems.queue(job.id, "pending-1", "/private/evidence/not-published-yet.txt")

        val counts = store.statusCountsByCollection(collection.id)

        assertEquals(1, counts.values.sum(), "neither another collection's documents nor a file-only item count")
        assertEquals(1, counts[DocumentStatus.FAILED])
        assertEquals(0, counts[DocumentStatus.COMPLETE])
        assertEquals(
            setOf(DocumentStatus.FAILED),
            counts.filterValues { it > 0 }.keys,
            "only this collection's own documents are in the nonzero counts",
        )
    }

    @Test
    fun `the counts follow a status change and a delete as authoritative state`() {
        val collection = collections.create("Nightfall")
        store.insert(document("doc-1", collection.id, status = DocumentStatus.FAILED))
        store.insert(document("doc-2", collection.id, status = DocumentStatus.QUEUED))
        assertEquals(1, store.statusCountsByCollection(collection.id)[DocumentStatus.FAILED])

        store.updateStatus(DocumentId("doc-1"), DocumentStatus.QUEUED)
        val afterRetry = store.statusCountsByCollection(collection.id)
        assertEquals(0, afterRetry[DocumentStatus.FAILED], "the retry left FAILED behind")
        assertEquals(2, afterRetry[DocumentStatus.QUEUED])
        assertEquals(2, afterRetry.values.sum())

        assertEquals(true, store.delete(DocumentId("doc-2")))
        val afterDeletion = store.statusCountsByCollection(collection.id)
        assertEquals(1, afterDeletion.values.sum(), "a deleted row is out of the next snapshot")
        assertEquals(1, afterDeletion[DocumentStatus.QUEUED])
        assertEquals(0, afterDeletion[DocumentStatus.FAILED])
    }

    private fun document(id: String, collectionId: CollectionId, status: DocumentStatus): Document = Document(
        id = DocumentId(id),
        collectionId = collectionId,
        sha256 = "sha256-of-$id",
        mediaType = "application/pdf",
        originalFilename = "$id.pdf",
        sourcePath = "/private/evidence/$id.pdf",
        sizeBytes = 1024L,
        status = status,
        createdAt = "2026-01-01T00:00:00.000Z",
        updatedAt = "2026-01-01T00:00:00.000Z",
    )
}
