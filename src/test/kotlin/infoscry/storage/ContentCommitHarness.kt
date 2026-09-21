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
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * A process that commits units until it is killed.
 *
 * What a killed process leaves behind only exists for a real process: a JVM that is force-terminated
 * mid-transaction is the one case SQLite's write-ahead log is there for, and a test that closed a connection
 * politely could never exercise it. The child reports where it is through a file the parent watches, and then
 * **waits for the parent to release it** before going on. The kill therefore lands in a named window instead
 * of wherever the process happened to be when the parent looked: the windows are a property of the test
 * rather than a race against the machine's scheduler.
 *
 * One unit passes through two parked states, in order:
 *
 * - `artifact:<i>` — the unit's artifact has been renamed into place and its commit has not started. This is
 *   the window the content store's promise is really about: an artifact on disk with no checkpoint beside it.
 * - `done:<i>` — the commit returned, so the unit is durable.
 *
 * Arguments: `<data directory> <units> <progress file>`.
 */
object ContentCommitHarness {

    /** One unit's exact text, so the parent can tell a truncated commit from a complete one. */
    fun unitText(index: Int): String = "unit $index: " + "content ".repeat(40).trim()

    /** Where one unit's artifact lives, relative to the document's artifact root. */
    fun artifactRelativePath(index: Int): String = "ocr/page-%06d.tsv.gz".format(index + 1)

    /** One unit's artifact bytes. The parent rewrites these to prove an uncommitted artifact is replaceable. */
    fun artifactBytes(index: Int): String = "word boxes for unit $index\n"

    /** The state a unit is parked in once its artifact is in place and before its commit starts. */
    fun artifactState(index: Int): String = ARTIFACT_PREFIX + index

    /** The state a unit is parked in once its commit has returned. */
    fun doneState(index: Int): String = DONE_PREFIX + index

    const val ARTIFACT_PREFIX: String = "artifact:"

    const val DONE_PREFIX: String = "done:"

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
            // The artifact is renamed into place first, exactly as an extractor does it, and the unit is only
            // announced after that: a unit named in this state and absent from the database afterwards is the
            // case the window exists for.
            val relative = artifactRelativePath(index)
            val artifact = artifactRoot.resolve(relative)
            Files.createDirectories(artifact.parent)
            Files.writeString(artifact, artifactBytes(index))

            announce(progress, artifactState(index))
            awaitRelease(progress, artifactState(index))
            content.commitExtractedUnit(
                documentId = document.id,
                fingerprint = fingerprint,
                key = "unit-$index",
                ordinal = index,
                draft = ContentUnitDraft(
                    locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                    extractedText = unitText(index),
                    searchText = unitText(index),
                    artifactRelativePath = relative,
                    artifactSha256 = sha256Of(artifact),
                ),
                artifactRoot = artifactRoot,
            )
            announce(progress, doneState(index))
            awaitRelease(progress, doneState(index))
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

    /**
     * Publishes the state the parent watches for, through a rename so the parent can never read a torn file.
     */
    private fun announce(progress: Path, state: String) {
        val temporary = progress.resolveSibling("${progress.fileName}.tmp")
        Files.writeString(temporary, state)
        Files.move(
            temporary,
            progress,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /**
     * Waits until the parent releases [state], so the next step happens only when the parent says so.
     *
     * The wait is bounded: a parent that dies without releasing the child fails it rather than leaving a
     * process behind for the rest of the suite.
     */
    private fun awaitRelease(progress: Path, state: String) {
        val release = progress.resolveSibling("${progress.fileName}.ack")
        val deadline = System.nanoTime() + RELEASE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (runCatching { Files.readString(release).trim() }.getOrNull() == state) return
            Thread.sleep(RELEASE_POLL_MILLIS)
        }
        error("the parent never released '$state'")
    }

    private fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private const val WAIT_FOREVER_MILLIS = 600_000L

    private const val RELEASE_TIMEOUT_NANOS = 120_000_000_000L

    private const val RELEASE_POLL_MILLIS = 2L

    private const val DIGEST_BUFFER_BYTES = 64 * 1024
}
