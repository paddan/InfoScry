package infoscry.storage

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a force-terminated process leaves behind.
 *
 * The content store's promise is that a unit commits with its checkpoint in one transaction, so an
 * interrupted import keeps exactly the units that were finished and never a half-written one. That promise is
 * about a process being killed, which only a real child process can demonstrate: a test that closes its
 * connection politely exercises the clean path, not the one the write-ahead log exists for.
 *
 * The child announces the unit it is about to commit, pauses, commits it, and announces that it did, so the
 * parent can kill in three different windows — before a unit, after one, and between two — and check the same
 * invariants in each.
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
        val killed = startChild(dataDir, units = 6, progress = progress)
        try {
            awaitProgress(progress, window)
        } finally {
            killed.destroyForcibly()
            check(killed.waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "the child did not die within ${KILL_TIMEOUT_SECONDS}s"
            }
        }
        val reported = Files.readString(progress).trim()

        Database(dataDir.resolve("infoscry.db")).use { database ->
            val content = ContentStore(database)
            val documentId = DocumentId(ContentCommitHarness.DOCUMENT_ID)
            val document = DocumentStore(database).get(documentId)
                ?: throw AssertionError("the child stored its document before it committed any unit")
            val fingerprint = ContentCommitHarness.fingerprintOf(document)

            val units = content.listUnits(documentId, afterOrdinal = -1, limit = 50)
            val byOrdinal = units.associateBy { it.ordinal }

            // Everything the child reported as committed is there, whole. A unit whose text came back
            // truncated would be the failure this test exists to rule out.
            lastCommitted(reported)?.let { last ->
                (0..last).forEach { ordinal ->
                    val unit = byOrdinal[ordinal]
                        ?: throw AssertionError("$window: unit $ordinal was reported committed and is gone")
                    assertEquals(ContentCommitHarness.unitText(ordinal), unit.extractedText, "$window: unit $ordinal")
                }
            }
            // The unit that was in flight is either absent or complete — a transaction is all or nothing.
            announced(reported)?.let { announcedIndex ->
                byOrdinal[announcedIndex]?.let { inFlight ->
                    assertEquals(
                        ContentCommitHarness.unitText(announcedIndex),
                        inFlight.extractedText,
                        "$window: an in-flight unit was stored half written",
                    )
                }
            }
            assertEquals(
                units.map { it.ordinal }.distinct(),
                units.map { it.ordinal },
                "$window: an ordinal was stored twice",
            )
            assertTrue(
                units.none { it.ordinal > MAX_UNITS },
                "$window: a unit past the last the child could reach exists",
            )
            assertEquals(
                units.map { it.ordinal }.toSet(),
                content.loadCheckpoints(documentId, fingerprint).filter { it.succeeded }.map { it.ordinal }.toSet(),
                "$window: a stored unit and a successful checkpoint are the same fact",
            )
            assertTrue(
                content.extractionMarker(documentId) == null,
                "$window: the child never reported a finished pass, so the document cannot look extracted",
            )

            // The next process picks the document up where the killed one left it, without redoing anything.
            val resumed = units.size
            content.commitExtractedUnit(
                documentId = documentId,
                fingerprint = fingerprint,
                key = "unit-$resumed",
                ordinal = resumed,
                draft = ContentUnitDraft(
                    locator = SourceLocation.TextLines(start = resumed + 1, end = resumed + 1),
                    extractedText = ContentCommitHarness.unitText(resumed),
                    searchText = ContentCommitHarness.unitText(resumed),
                ),
                artifactRoot = dataDir.resolve("artifacts"),
            )
            assertEquals(resumed + 1, content.listUnits(documentId, -1, 50).size, "$window: a resume did not continue")
        }
        dataDir.toFile().deleteRecursively()
    }

    /** The last unit index the child said it had committed, or `null` if it died before one. */
    private fun lastCommitted(reported: String): Int? =
        reported.removePrefix("done:").toIntOrNull()?.takeIf { reported.startsWith("done:") }

    /** The unit index the child announced before committing, or `null` if it died after finishing one. */
    private fun announced(reported: String): Int? =
        reported.removePrefix("about:").toIntOrNull()?.takeIf { reported.startsWith("about:") }

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

    private fun awaitProgress(progress: Path, expected: String) {
        val deadline = System.nanoTime() + PROGRESS_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val seen = runCatching { Files.readString(progress).trim() }.getOrDefault("")
            if (seen == expected) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "the child never reported '$expected'; the progress file last said " +
                runCatching { Files.readString(progress).trim() }.getOrDefault("<none>"),
        )
    }

    private companion object {
        /** One kill before any commit, one after a commit, and one between two of them. */
        val KILL_WINDOWS = listOf("done:0", "about:2", "done:3")

        const val MAX_UNITS = 5
        const val PROGRESS_TIMEOUT_NANOS = 60_000_000_000L
        const val KILL_TIMEOUT_SECONDS = 30L
        const val POLL_MILLIS = 2L
    }
}
