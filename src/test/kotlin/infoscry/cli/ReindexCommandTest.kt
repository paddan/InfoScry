package infoscry.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `infoscry reindex --wait --json` as a real process asking a running server, which runs the rebuild as a
 * job under exclusive maintenance. An empty archive is enough: nothing needs embedding.
 */
class ReindexCommandTest {

    private lateinit var directory: Path
    private lateinit var cli: CliTestServer

    @BeforeTest
    fun createTemporaryDirectories() {
        directory = Files.createTempDirectory("infoscry-reindex-cli")
        cli = CliTestServer(Files.createDirectories(directory.resolve("data")))
    }

    @AfterTest
    fun removeTemporaryDirectories() {
        cli.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the server runs the rebuild, --wait reports its end, and the server keeps serving`() {
        cli.run("collection", "create", "Default")

        val result = cli.run("reindex", "--wait", "--json")

        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        val body = result.stdout.lines().last { it.trim().startsWith("{") }
        assertTrue(body.contains("\"state\":\"COMPLETE\""), "the rebuild did not end complete: $body")
        assertTrue(body.contains("\"documents\":0"), "the rebuild reported documents: $body")
        val list = cli.run("collection", "list", "--json")
        assertEquals(0, list.exitCode, "the server no longer answers: ${list.stderr}")
        assertTrue(list.stdout.contains("Default"))
    }

    @Test
    fun `without --wait the command answers with the accepted job`() {
        val result = cli.run("reindex", "--json")

        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        assertTrue(result.stdout.contains("\"accepted\":true"), result.stdout)
    }
}
