package infoscry.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `infoscry collection glossary` as a real process. */
class GlossaryCommandTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-cli-glossary")
        CliProcess.run(*args("collection", "create", "Default"))
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    private fun args(vararg extra: String): Array<String> = (listOf("--data-dir", dataDir.toString()) + extra).toTypedArray()

    private fun listed(): List<String> =
        CliProcess.run(*args("collection", "glossary", "list", "Default")).stdout.trim().lines().filter { it.isNotEmpty() }

    @Test
    fun `adding the same group twice keeps one group with one id`() {
        val first = CliProcess.run(*args("collection", "glossary", "add", "Default", "Stockholm", "Sthlm"))
        val second = CliProcess.run(*args("collection", "glossary", "add", "Default", "Sthlm", "Stockholm"))

        assertEquals(0, first.exitCode, first.stderr)
        assertEquals(0, second.exitCode, second.stderr)
        assertEquals(first.stdout.trim(), second.stdout.trim())
        val line = listed().single().split('\t')
        assertTrue(first.stdout.trim() == "Saved glossary group ${line[0]}.", first.stdout)
        assertEquals(listOf("Stockholm", "Sthlm"), line.drop(1))
    }

    @Test
    fun `a prefix term is listed with a star`() {
        val added = CliProcess.run(*args("collection", "glossary", "add", "Default", "Stockholm", "Sthlm", "--prefix", "Sthlm"))

        assertEquals(0, added.exitCode, added.stderr)
        assertEquals(listOf("Stockholm", "Sthlm*"), listed().single().split('\t').drop(1))
    }

    @Test
    fun `removing a group twice succeeds both times`() {
        CliProcess.run(*args("collection", "glossary", "add", "Default", "Stockholm", "Sthlm"))
        val id = listed().single().substringBefore('\t')

        val first = CliProcess.run(*args("collection", "glossary", "remove", "Default", id))
        val second = CliProcess.run(*args("collection", "glossary", "remove", "Default", id))

        assertEquals(0, first.exitCode, first.stderr)
        assertEquals(0, second.exitCode, second.stderr)
        assertEquals("Removed glossary group $id.", second.stdout.trim())
        assertTrue(listed().isEmpty())
    }

    @Test
    fun `a term another group holds fails and stores nothing`() {
        CliProcess.run(*args("collection", "glossary", "add", "Default", "Stockholm", "Sthlm"))

        val refused = CliProcess.run(*args("collection", "glossary", "add", "Default", "Huvudstaden", "STHLM"))

        assertNotEquals(0, refused.exitCode)
        assertTrue(refused.stderr.contains("another glossary group already has that term"), refused.stderr)
        assertEquals(1, listed().size)
    }

    @Test
    fun `a prefix that is not one of the terms is refused`() {
        val refused = CliProcess.run(*args("collection", "glossary", "add", "Default", "Stockholm", "Sthlm", "--prefix", "Sth"))

        assertNotEquals(0, refused.exitCode)
        assertTrue(listed().isEmpty())
    }
}
