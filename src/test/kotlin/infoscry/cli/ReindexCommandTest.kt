package infoscry.cli

import infoscry.server.ApiJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `infoscry reindex --wait --json` as a real process, on both sides of the ownership question.
 *
 * A rebuild is exclusive maintenance for its whole duration, so the same ownership rule an import
 * follows decides where it runs: a server that owns the data directory runs it, and a directory nobody
 * owns is rebuilt here, in this process, which holds the lock until the rebuild is done. `--wait` waits
 * for a terminal state and the `--json` shape reports it. An empty archive is enough to exercise both
 * halves: nothing needs embedding, so no model or harness seam is involved.
 */
class ReindexCommandTest {

    private lateinit var directory: Path
    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDirectories() {
        directory = Files.createTempDirectory("infoscry-reindex-cli")
        dataDir = Files.createDirectories(directory.resolve("data"))
    }

    @AfterTest
    fun removeTemporaryDirectories() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a foreground reindex waits for a terminal job result and releases the directory`() {
        val result = CliProcess.run(
            "reindex", "--data-dir", dataDir.toString(), "--wait", "--json",
        )

        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        val body = result.stdout.lines().last { it.trim().startsWith("{") }
        assertTrue(body.contains("\"state\":\"COMPLETE\""), "the rebuild did not end complete: $body")
        assertTrue(body.contains("\"documents\":0"), "the rebuild reported documents: $body")

        // The lock is released, which is what a later command needs.
        val list = CliProcess.run("--data-dir", dataDir.toString(), "collection", "list", "--json")
        assertEquals(0, list.exitCode, "a later command could not open the data directory: ${list.stderr}")
        assertTrue(list.stdout.contains("Default"))
    }

    @Test
    fun `a server runs the rebuild and keeps running after the command returns`() {
        val server = CliProcess.startRunning("serve", "--data-dir", dataDir.toString(), "--port", "0", "--json")
        try {
            server.awaitStdoutLine("{")

            val result = CliProcess.run(
                "reindex", "--data-dir", dataDir.toString(), "--wait", "--json",
            )

            assertEquals(0, result.exitCode, "stderr=${result.stderr}")
            val body = result.stdout.lines().last { it.trim().startsWith("{") }
            assertTrue(body.contains("\"state\":\"COMPLETE\""), "the rebuild did not end complete: $body")
            // The server is still serving: a later read that needs it succeeds.
            val probe = CliProcess.run("--data-dir", dataDir.toString(), "jobs", "--json")
            assertEquals(0, probe.exitCode, "the server no longer answers: ${probe.stderr}")
        } finally {
            server.terminate()
        }
    }
}