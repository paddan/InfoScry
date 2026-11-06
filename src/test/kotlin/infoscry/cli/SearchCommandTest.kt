package infoscry.cli

import infoscry.server.ApiJson
import infoscry.server.SearchResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `infoscry search` as a real process asking a running server.
 *
 * The command's contract with a script is one line of valid JSON even when nothing matched, and the
 * collection is mandatory because a search reads one collection at a time.
 */
class SearchCommandTest {

    private lateinit var directory: Path
    private lateinit var dataDir: Path
    private lateinit var cli: CliTestServer

    @BeforeTest
    fun createTemporaryDirectories() {
        directory = Files.createTempDirectory("infoscry-search-cli")
        dataDir = Files.createDirectories(directory.resolve("data"))
        cli = CliTestServer(dataDir)
        // Every search in this class names Default, and a new archive no longer holds one.
        cli.run("collection", "create", "Default")
    }

    @AfterTest
    fun removeTemporaryDirectories() {
        cli.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a search without matches prints one valid json line and exits zero`() {
        val result = cli.run(
            "search",
            "--collection", "Default", "--mode", "keyword", "--json", "zzzz",
        )

        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        val body = result.stdout.lines().last { it.trim().startsWith("{") }
        val response = ApiJson.decodeFromString<SearchResponse>(body)
        assertTrue(response.hits.isEmpty(), "an empty archive produced hits")
        assertEquals(0, response.staleFiltered)
    }

    @Test
    fun `a search refuses without a collection`() {
        val result = cli.run(
            "search", "--mode", "keyword", "--json", "zzzz",
        )

        assertNotEquals(0, result.exitCode, "search without a collection reported success")
        assertFalse(result.stdout.lines().any { it.trim().startsWith("{") }, "a refusal was not valid JSON")
    }

    @Test
    fun `a hybrid search for a query with no foothold is an empty result, not a refusal`() {
        // The default mode is hybrid. The archive holds no word of "zzzz", so the semantic half never
        // runs and the absent model never gets to refuse: the honest answer is that nothing matched.
        val result = cli.run(
            "search", "--collection", "Default", "--json", "zzzz",
        )

        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        val body = result.stdout.lines().last { it.trim().startsWith("{") }
        val response = ApiJson.decodeFromString<SearchResponse>(body)
        assertTrue(response.hits.isEmpty(), "a query with no foothold produced hits")
    }

    @Test
    fun `a semantic search without a model exits nonzero naming the install remedy, not a stack trace`() {
        // Semantic mode embeds every query by design, so a temporary data directory with no model still
        // fails with the remedy that fixes it, in the terminal's own voice — rather than with a
        // nearest-neighbour imitation of a result.
        val result = cli.run(
            "search", "--collection", "Default", "--mode", "semantic", "--json", "zzzz",
        )

        assertNotEquals(0, result.exitCode, "a semantic search without a model reported success")
        assertFalse(result.stderr.contains(" at "), "the CLI printed a stack trace: ${result.stderr}")
        assertContains(result.stdout + result.stderr, "MODEL_NOT_INSTALLED")
        assertContains(result.stdout + result.stderr, "embeddingModel")
    }

    @Test
    fun `a search refuses invalid dates without echoing their value`() {
        val result = cli.run(
            "search", "--collection", "Default",
            "--from", "private-date-value", "--json", "report",
        )

        assertNotEquals(0, result.exitCode)
        assertContains(result.stdout + result.stderr, "from must be an ISO date or ISO instant")
        assertFalse((result.stdout + result.stderr).contains("private-date-value"))
        assertFalse(result.stderr.contains(" at "), "the CLI printed a stack trace: ${result.stderr}")
    }

    @Test
    fun `a search accepts inclusive date-only bounds`() {
        val result = cli.run(
            "search", "--collection", "Default",
            "--from", "2026-03-01", "--until", "2026-03-01", "--mode", "keyword", "--json", "report",
        )

        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        val response = ApiJson.decodeFromString<SearchResponse>(result.stdout.lines().last { it.trim().startsWith("{") })
        assertTrue(response.hits.isEmpty())
    }

    @Test
    fun `a search without a token fails before sending anything and says how to get one`() {
        val result = CliProcess.run("search", "--collection", "Default", "--json", "report")

        assertNotEquals(0, result.exitCode)
        assertContains(result.stderr, "INFOSCRY_TOKEN")
        assertContains(result.stderr, "personal access token")
    }
}
