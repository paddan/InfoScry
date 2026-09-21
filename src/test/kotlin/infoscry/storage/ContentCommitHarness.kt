package infoscry.storage

import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * A process that commits units until it is killed.
 *
 * What a killed process leaves behind only exists for a real process: a JVM that is force-terminated
 * mid-transaction is the one case SQLite's write-ahead log is there for, and a test that closed a connection
 * politely could never exercise it. The child reports how far it got through a file the parent watches, so
 * the parent can decide exactly when to pull the plug.
 *
 * Arguments: `<data directory> <units> <progress file>`.
 */
object ContentCommitHarness {

    /** One unit's exact text, so the parent can tell a truncated commit from a complete one. */
    fun unitText(index: Int): String = "unit $index: " + "content ".repeat(40).trim()

    @JvmStatic
    fun main(args: Array<String>) {
        val dataDir = Path.of(args[0])
        val units = args[1].toInt()
        val progress = Path.of(args[2])

        val database = Database(dataDir.resolve("infoscry.db"))
        SchemaMigrator(database).migrate()
        val collections = CollectionStore(database)
        val documents = DocumentStore(database)
        val content = ContentStore(database)

        val collection = collections.list().firstOrNull { it.name == COLLECTION }
            ?: collections.create(COLLECTION)
        val document = documents.get(DocumentId(DOCUMENT_ID)) ?: documents.insert(
            Document(
                id = DocumentId(DOCUMENT_ID),
                collectionId = collection.id,
                sha256 = "a".repeat(64),
                mediaType = "application/pdf",
                originalFilename = "kill.pdf",
                sourcePath = "/tmp/kill.pdf",
                sizeBytes = 42,
                status = DocumentStatus.EXTRACTING,
                createdAt = Instants.now(),
                updatedAt = Instants.now(),
            ),
        )
        val fingerprint = ExtractionFingerprint.of(
            document.sha256,
            ExtractionSettings(ocrLanguages = "eng"),
        )
        val artifactRoot = dataDir.resolve("artifacts")

        repeat(units) { index ->
            // Reported before the write, so a unit named here and missing afterwards is the case this exists
            // for: the parent killed the process while that commit was in flight. The pause leaves room for
            // the parent to kill in a named window rather than whenever it happens to look.
            Files.writeString(progress, "about:$index")
            Thread.sleep(PREPARE_MILLIS)
            content.commitExtractedUnit(
                documentId = document.id,
                fingerprint = fingerprint,
                key = "unit-$index",
                ordinal = index,
                draft = ContentUnitDraft(
                    locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                    extractedText = unitText(index),
                    searchText = unitText(index),
                ),
                artifactRoot = artifactRoot,
            )
            Files.writeString(progress, "done:$index")
            Thread.sleep(STEP_MILLIS)
        }
        // Only the parent ends this process.
        Thread.sleep(WAIT_FOREVER_MILLIS)
    }

    /** The collection and document the parent reads back; fixed so both sides agree without a handshake. */
    const val COLLECTION: String = "Kill"

    const val DOCUMENT_ID: String = "killed-document"

    /** How the parent finds the same fingerprint the child used. */
    fun fingerprintOf(document: Document): ExtractionFingerprint =
        ExtractionFingerprint.of(document.sha256, ExtractionSettings(ocrLanguages = "eng"))

    private const val WAIT_FOREVER_MILLIS = 600_000L

    /** How long the child waits after announcing a unit, so the parent can kill in that window. */
    private const val PREPARE_MILLIS = 15L

    /** How long the child waits after committing one, so the next window is a separate instant. */
    private const val STEP_MILLIS = 15L
}
