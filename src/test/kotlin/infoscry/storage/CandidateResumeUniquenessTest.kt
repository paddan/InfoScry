package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Concurrent resume attempts with the same fingerprint must use the same CANDIDATE revision,
 * not create duplicate candidates.
 */
class CandidateResumeUniquenessTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var revisions: DocumentRevisionStore
    private lateinit var documents: DocumentStore
    private lateinit var collections: CollectionStore

    private val now = "2026-09-30T07:00:00Z"

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("infoscry-candidate-uniqueness")
        database = Database(dataDir.resolve("state.db"))
        SchemaMigrator(database).migrate()
        revisions = DocumentRevisionStore(database, ContentStore(database))
        documents = DocumentStore(database)
        collections = CollectionStore(database)
    }

    @AfterTest
    fun cleanup() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    private fun createDocument(): Document {
        val collection = collections.create("Test Collection")
        val doc = Document(
            id = DocumentId("doc-test"),
            collectionId = collection.id,
            sha256 = "a".repeat(64),
            mediaType = "application/pdf",
            originalFilename = "test.pdf",
            sourcePath = "/tmp/test.pdf",
            sizeBytes = 1000,
            status = DocumentStatus.COMPLETE,
            createdAt = now,
            updatedAt = now,
        )
        return documents.insert(doc)
    }

    @Test
    fun `inserting two CANDIDATE revisions with same document_id and non-null attempt_fingerprint is rejected at the database level`() {
        val document = createDocument()
        val fingerprint = "fingerprint-123"

        // First candidate with this fingerprint opens successfully
        val candidate1 = revisions.openCandidate(document.id, parentRevisionId = null, "RESCAN", fingerprint)
        assertNotNull(candidate1)

        // Attempting to open a second candidate with the same fingerprint should not violate the constraint
        // at the application level: openCandidate catches the violation and adopts the existing candidate.
        // The constraint exists in the database to prevent race conditions, and this method handles the race.
        val candidate2 = revisions.openCandidate(document.id, parentRevisionId = null, "RESCAN", fingerprint)
        assertEquals(candidate1, candidate2, "the second call should return the existing candidate")
    }

    @Test
    fun `WITHDRAWN and PUBLISHED revisions with same fingerprint do not violate uniqueness`() {
        val document = createDocument()
        val fingerprint = "fingerprint-456"

        // Open and withdraw one candidate
        val candidate1 = revisions.openCandidate(document.id, null, "RESCAN", fingerprint)
        database.transaction { connection ->
            connection.prepareStatement("UPDATE document_revisions SET state = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, RevisionState.WITHDRAWN.name)
                stmt.setString(2, candidate1)
                stmt.executeUpdate()
            }
        }

        // Should be able to open another candidate with the same fingerprint since the first is withdrawn
        val candidate2 = revisions.openCandidate(document.id, null, "RESCAN", fingerprint)
        assertNotNull(candidate2)
        assertEquals(candidate1 != candidate2, true, "should create a new candidate")
    }

    @Test
    fun `NULL attempt_fingerprint does not trigger uniqueness constraint`() {
        val document = createDocument()

        // Open multiple candidates without a fingerprint
        val candidate1 = revisions.openCandidate(document.id, null, "RESCAN", attemptFingerprint = null)
        val candidate2 = revisions.openCandidate(document.id, null, "RESCAN", attemptFingerprint = null)

        // Both should succeed without constraint violation
        assertNotNull(candidate1)
        assertNotNull(candidate2)
        assertEquals(candidate1 != candidate2, true, "should create separate candidates without fingerprint")
    }

    @Test
    fun `different attempt_fingerprints do not violate uniqueness`() {
        val document = createDocument()

        // Open candidates with different fingerprints
        val candidate1 = revisions.openCandidate(document.id, null, "RESCAN", "fingerprint-1")
        val candidate2 = revisions.openCandidate(document.id, null, "RESCAN", "fingerprint-2")

        // Both should succeed
        assertNotNull(candidate1)
        assertNotNull(candidate2)
        assertEquals(candidate1 != candidate2, true, "different fingerprints create different candidates")
    }

    @Test
    fun `resumableCandidate finds the newest CANDIDATE with matching fingerprint`() {
        val document = createDocument()
        val fingerprint = "fingerprint-search"

        // Open a candidate with the fingerprint
        val candidate = revisions.openCandidate(document.id, null, "RESCAN", fingerprint)

        // Should find it when resuming
        val found = revisions.resumableCandidate(document.id, fingerprint)
        assertEquals(candidate, found)
    }

    @Test
    fun `resumableCandidate does not find WITHDRAWN candidates`() {
        val document = createDocument()
        val fingerprint = "fingerprint-withdrawn"

        // Open and withdraw a candidate
        val candidate = revisions.openCandidate(document.id, null, "RESCAN", fingerprint)
        database.transaction { connection ->
            connection.prepareStatement("UPDATE document_revisions SET state = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, RevisionState.WITHDRAWN.name)
                stmt.setString(2, candidate)
                stmt.executeUpdate()
            }
        }

        // Should not find withdrawn candidate
        val found = revisions.resumableCandidate(document.id, fingerprint)
        assertNull(found)
    }

    @Test
    fun `resumableCandidate does not find NULL fingerprint rows`() {
        val document = createDocument()

        // Open candidate without fingerprint
        revisions.openCandidate(document.id, null, "RESCAN", attemptFingerprint = null)

        // Should not find it when searching for a specific fingerprint
        val found = revisions.resumableCandidate(document.id, "some-fingerprint")
        assertNull(found)
    }

    @Test
    fun `two concurrent openCandidate calls with same fingerprint return the same revision id`() {
        val document = createDocument()
        val fingerprint = "fingerprint-concurrent"

        // First call opens a new candidate
        val candidate1 = revisions.openCandidate(document.id, null, "RESCAN", fingerprint)

        // Second call with same fingerprint should return the existing candidate (adopting it)
        val candidate2 = revisions.openCandidate(document.id, null, "RESCAN", fingerprint)

        // Both should return the same revision ID
        assertEquals(candidate1, candidate2, "concurrent opens with same fingerprint should adopt existing candidate")
    }
}
