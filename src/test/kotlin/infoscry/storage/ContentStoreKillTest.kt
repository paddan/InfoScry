package infoscry.storage

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a force-terminated process leaves behind.
 *
 * The content store's promise is that a unit commits with its checkpoint in one transaction, so an
 * interrupted import keeps exactly the units that were finished and never a half-written one. That promise is
 * about a process being killed, which only a real child process can demonstrate: a test that closes its
 * connection politely exercises the clean path, not the one the write-ahead log exists for.
 *
 * The child parks in a named state and waits for the parent to release it, so the parent kills in exactly the
 * window it asked for. The three windows are the three instants that matter: after a checkpoint commit, with
 * an artifact renamed into place and no commit yet, and between two commits. The fourth point the task names —
 * a kill during chunking — belongs to the end-to-end pipeline kills in Task 27.
 */
class ContentStoreKillTest {

    @Test
    fun `a killed process keeps every committed unit and no partial one`() {
        KILL_WINDOWS.forEach { window -> killAt(window) }
    }

    /** Runs one child, kills it in [window], and checks what survived. */
    private fun killAt(window: String) {
        val dataDir = Files.createTempDirectory("infoscry-kill")
        val progress = dataDir.resolve("progress")
        val killed = startChild(dataDir, units = UNITS, progress = progress)
        val parked = try {
            parkAt(progress, window)
        } finally {
            killed.destroyForcibly()
            check(killed.waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "the child did not die within ${KILL_TIMEOUT_SECONDS}s"
            }
        }

        // The window the test asked for and the state the child parked in have to be the same kind: a window
        // that quietly stopped exercising the artifact branch would keep passing while testing less.
        assertEquals(
            window.startsWith(ContentCommitHarness.ARTIFACT_PREFIX),
            parkedArtifact(parked) != null,
            "$parked: the window and the state it parked in disagree about what is being tested",
        )

        Database(dataDir.resolve("infoscry.db")).use { database ->
            val content = ContentStore(database)
            val documentId = DocumentId(ContentCommitHarness.DOCUMENT_ID)
            val document = DocumentStore(database).get(documentId)
                ?: throw AssertionError("the child stored its document before it committed any unit")
            val fingerprint = ContentCommitHarness.fingerprintOf(document)
            val artifactRoot = dataDir.resolve(ARTIFACT_DIRECTORY)

            val units = content.listUnits(documentId, afterOrdinal = -1, limit = 50)
            val byOrdinal = units.associateBy { it.ordinal }

            // Every stored unit is whole — a commit that landed half written would not match the text the
            // child wrote — and the stored set is exactly the prefix the child reported committing, which is
            // what rules out a lost commit, a duplicate ordinal and a unit nobody wrote.
            units.forEach { unit ->
                assertEquals(
                    ContentCommitHarness.unitText(unit.ordinal),
                    unit.extractedText,
                    "$parked: unit ${unit.ordinal} was stored half written",
                )
            }
            assertEquals(
                committedThrough(parked)?.let { (0..it).toList() } ?: emptyList(),
                units.map { it.ordinal },
                "$parked: the stored units are not the prefix the child reported committing",
            )
            assertEquals(
                units.map { it.ordinal }.toSet(),
                content.loadCheckpoints(documentId, fingerprint).filter { it.succeeded }.map { it.ordinal }.toSet(),
                "$parked: a stored unit and a successful checkpoint are the same fact",
            )
            assertTrue(
                content.extractionMarker(documentId) == null,
                "$parked: the child never reported a finished pass, so the document cannot look extracted",
            )

            // The parked artifact is on disk and is not evidence. Nothing may skip its unit, because a skip
            // without a checkpoint would reuse a citation whose word boxes were never verified — and the page
            // is read again, which means writing that same path with new bytes must still commit.
            parkedArtifact(parked)?.let { index ->
                val artifact = artifactRoot.resolve(ContentCommitHarness.artifactRelativePath(index))
                assertTrue(
                    Files.isRegularFile(artifact),
                    "$parked: this window is about an artifact on disk, but unit $index has none",
                )
                assertNull(
                    byOrdinal[index],
                    "$parked: a unit was stored although its commit had not started",
                )
                assertFalse(
                    content.reusableCheckpoints(documentId, fingerprint, artifactRoot)
                        .skipKeys.contains("unit-$index"),
                    "$parked: an artifact without a checkpoint was treated as committed evidence",
                )

                val rewritten = ContentCommitHarness.artifactBytes(index) + "rewritten\n"
                Files.writeString(artifact, rewritten)
                val recommitted = content.commitExtractedUnit(
                    documentId = documentId,
                    fingerprint = fingerprint,
                    key = "unit-$index",
                    ordinal = index,
                    draft = ContentUnitDraft(
                        locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                        extractedText = ContentCommitHarness.unitText(index),
                        searchText = ContentCommitHarness.unitText(index),
                        artifactRelativePath = ContentCommitHarness.artifactRelativePath(index),
                        artifactSha256 = sha256Of(artifact),
                    ),
                    artifactRoot = artifactRoot,
                )
                assertNull(recommitted.artifactIssue, "$parked: replacing an uncommitted artifact was refused")
                assertEquals(
                    sha256Of(artifact),
                    recommitted.unit.artifactSha256,
                    "$parked: the unit kept the checksum of the artifact it replaced",
                )
            }

            // The next process picks the document up where the killed one left it, without redoing anything.
            val before = content.listUnits(documentId, afterOrdinal = -1, limit = 50).size
            content.commitExtractedUnit(
                documentId = documentId,
                fingerprint = fingerprint,
                key = "unit-$before",
                ordinal = before,
                draft = ContentUnitDraft(
                    locator = SourceLocation.TextLines(start = before + 1, end = before + 1),
                    extractedText = ContentCommitHarness.unitText(before),
                    searchText = ContentCommitHarness.unitText(before),
                ),
                artifactRoot = artifactRoot,
            )
            assertEquals(
                before + 1,
                content.listUnits(documentId, afterOrdinal = -1, limit = 50).size,
                "$parked: a resume did not continue",
            )
        }
        dataDir.toFile().deleteRecursively()
    }

    /**
     * How many units the child had committed when it was killed in [state], or `null` before the first.
     *
     * A unit parked with its artifact in place has not committed yet, so its index is one past the last
     * commit; a parked commit is the last one.
     */
    private fun committedThrough(state: String): Int? {
        val index = when {
            state.startsWith(ContentCommitHarness.DONE_PREFIX) ->
                state.removePrefix(ContentCommitHarness.DONE_PREFIX).toInt()

            state.startsWith(ContentCommitHarness.ARTIFACT_PREFIX) ->
                state.removePrefix(ContentCommitHarness.ARTIFACT_PREFIX).toInt() - 1

            else -> return null
        }
        return index.takeIf { it >= 0 }
    }

    /** The unit parked with its artifact on disk and no commit, or `null` in the other windows. */
    private fun parkedArtifact(state: String): Int? = state
        .takeIf { it.startsWith(ContentCommitHarness.ARTIFACT_PREFIX) }
        ?.removePrefix(ContentCommitHarness.ARTIFACT_PREFIX)
        ?.toInt()

    private fun startChild(dataDir: Path, units: Int, progress: Path): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        System.getProperty("java.class.path"),
        "infoscry.storage.ContentCommitHarness",
        dataDir.toAbsolutePath().toString(),
        units.toString(),
        progress.toAbsolutePath().toString(),
    ).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(dataDir.resolve("child.log").toFile()))
        .start()

    /**
     * Waits until the child parks at [window], releasing it past every state before that one.
     *
     * The child never advances on its own, so an intermediate state has to be released for a later window to
     * be reachable at all, and the window itself is left unreleased: the parent's kill is what ends the run.
     */
    private fun parkAt(progress: Path, window: String): String {
        val deadline = System.nanoTime() + PROGRESS_TIMEOUT_NANOS
        var last = "<none>"
        while (System.nanoTime() < deadline) {
            val seen = readState(progress)
            if (seen != null) {
                last = seen
                if (seen == window) return seen
                release(progress, seen)
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("the child never parked at '$window'; the last state was '$last'")
    }

    /** The state the child is parked in, or `null` while it has not published one yet. */
    private fun readState(progress: Path): String? = runCatching { Files.readString(progress).trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

    private fun release(progress: Path, state: String) {
        runCatching { Files.writeString(ackFile(progress), state) }
    }

    private fun ackFile(progress: Path): Path = progress.resolveSibling("${progress.fileName}.ack")

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

    private companion object {
        /**
         * One kill after a commit, one with an artifact renamed into place and no commit, and one between two
         * commits.
         */
        val KILL_WINDOWS = listOf(
            ContentCommitHarness.doneState(0),
            ContentCommitHarness.artifactState(2),
            ContentCommitHarness.doneState(3),
        )

        const val UNITS = 6
        const val ARTIFACT_DIRECTORY = "artifacts"
        const val PROGRESS_TIMEOUT_NANOS = 60_000_000_000L
        const val KILL_TIMEOUT_SECONDS = 30L
        const val POLL_MILLIS = 2L
        const val DIGEST_BUFFER_BYTES = 64 * 1024
    }
}
