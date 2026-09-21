package infoscry.cli

import infoscry.server.ApiJson
import infoscry.server.CollectionResponse
import infoscry.server.CollectionsResponse
import infoscry.storage.CollectionStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `infoscry collection` as a real process.
 *
 * What a script depends on is the exit code and the shape of the output, so both are asserted through
 * the real command line: success is `0`, a refusal is not, and machine-readable output is one parseable
 * line with no diagnostics mixed into it.
 */
class CollectionCommandTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-cli")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `list prints the seeded Default collection as JSON`() {
        val result = CliProcess.run(*args("collection", "list", "--json"))

        assertEquals(0, result.exitCode, result.stderr)
        val listed = ApiJson.decodeFromString<CollectionsResponse>(result.stdout.trim())
        assertTrue(listed.collections.any { it.id == CollectionStore.DEFAULT_ID && it.name == "Default" })
    }

    @Test
    fun `machine-readable output is the only thing on stdout`() {
        val result = CliProcess.run(*args("collection", "list", "--json"))

        assertEquals(1, result.stdout.trim().lines().size, "stdout carried: ${result.stdout}")
        assertTrue(result.stdout.trim().startsWith("{"))
    }

    @Test
    fun `the data directory may be given after the subcommand`() {
        val result = CliProcess.run(
            "collection",
            "list",
            "--json",
            "--data-dir",
            dataDir.toString(),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout, "Default")
    }

    @Test
    fun `create makes a collection that list then shows`() {
        val created = CliProcess.run(*args("collection", "create", "Project Nightfall", "--json"))

        assertEquals(0, created.exitCode, created.stderr)
        val response = ApiJson.decodeFromString<CollectionResponse>(created.stdout.trim())
        assertEquals("Project Nightfall", response.collection.name)

        val listed = CliProcess.run(*args("collection", "list", "--json"))
        assertContains(listed.stdout, "Project Nightfall")
    }

    @Test
    fun `a duplicate name fails with an explanation instead of a stack trace`() {
        CliProcess.run(*args("collection", "create", "Acme"))

        val duplicate = CliProcess.run(*args("collection", "create", "acme"))

        assertNotEquals(0, duplicate.exitCode)
        assertContains(duplicate.stderr, "already exists")
        assertTrue(
            !duplicate.stderr.contains("Exception in thread"),
            "the operator gets the reason, not a stack trace: ${duplicate.stderr}",
        )
    }

    @Test
    fun `the human-readable list names each collection with its id and OCR languages`() {
        CliProcess.run(*args("collection", "create", "Acme"))

        val listed = CliProcess.run(*args("collection", "list"))

        assertEquals(0, listed.exitCode, listed.stderr)
        assertContains(listed.stdout, "Default")
        assertContains(listed.stdout, "Acme")
        assertContains(listed.stdout, "ocr: eng")
    }

    private fun args(vararg extra: String): Array<String> =
        (listOf("--data-dir", dataDir.toString()) + extra).toTypedArray()
}
