package infoscry.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `.gitignore` subset a collection's ignore list speaks, one syntax element at a time. */
class IgnorePatternsTest {

    private fun of(vararg lines: String) = IgnorePatterns.of(lines.toList())

    private fun IgnorePatterns.file(path: String) = ignores(path, isDirectory = false)

    private fun IgnorePatterns.dir(path: String) = ignores(path, isDirectory = true)

    @Test
    fun `a bare name matches that name at any depth`() {
        val ignore = of(".DS_Store")

        assertTrue(ignore.file(".DS_Store"))
        assertTrue(ignore.file("a/b/.DS_Store"))
        assertFalse(ignore.file("a/.DS_Store.txt"))
        assertFalse(ignore.file("x.DS_Store"))
    }

    @Test
    fun `a star matches within one name and never across a slash`() {
        val ignore = of("*.tmp", "~$*")

        assertTrue(ignore.file("a.tmp"))
        assertTrue(ignore.file("deep/er/a.tmp"))
        assertTrue(ignore.file("~\$Report.docx"))
        assertFalse(ignore.file("a.tmp.pdf"))
        assertFalse(ignore.file("report.docx"))
        assertTrue(of("doc/*.md").file("doc/a.md"))
        assertFalse(of("doc/*.md").file("doc/sub/a.md"), "a star must not cross a directory separator")
    }

    @Test
    fun `a question mark matches exactly one character`() {
        val ignore = of("file?.txt")

        assertTrue(ignore.file("file1.txt"))
        assertFalse(ignore.file("file.txt"))
        assertFalse(ignore.file("file12.txt"))
        assertFalse(of("a?b").file("a/b"), "a question mark must not match a separator")
    }

    @Test
    fun `a dot is literal and never a wildcard`() {
        assertFalse(of("a.b").file("axb"))
        assertTrue(of("._*").file("._thing"))
        assertFalse(of("._*").file("xthing"))
    }

    @Test
    fun `a leading double star matches in any directory including none`() {
        val ignore = of("**/cache.db")

        assertTrue(ignore.file("cache.db"))
        assertTrue(ignore.file("a/cache.db"))
        assertTrue(ignore.file("a/b/cache.db"))
        assertFalse(ignore.file("a/mycache.db"))
    }

    @Test
    fun `a middle double star matches zero or more directories`() {
        val ignore = of("a/**/z.txt")

        assertTrue(ignore.file("a/z.txt"))
        assertTrue(ignore.file("a/b/z.txt"))
        assertTrue(ignore.file("a/b/c/z.txt"))
        assertFalse(ignore.file("b/a/z.txt"), "a pattern with a slash is anchored to the imported folder")
    }

    @Test
    fun `a trailing double star matches everything inside`() {
        val ignore = of("build/**")

        assertTrue(ignore.file("build/out.txt"))
        assertTrue(ignore.file("build/x/y.txt"))
        assertFalse(ignore.file("src/build/out.txt"))
    }

    @Test
    fun `a trailing slash matches directories only and their contents`() {
        val ignore = of(".git/", "node_modules/")

        assertTrue(ignore.dir(".git"))
        assertTrue(ignore.dir("pkg/node_modules"))
        assertFalse(ignore.file(".git"), "a file named like an ignored directory is not that directory")
        assertTrue(ignore.file(".git/config"), "the contents of an ignored directory are ignored")
        assertTrue(ignore.file("pkg/node_modules/x/readme.md"))
        assertFalse(ignore.file("pkg/modules/readme.md"))
    }

    @Test
    fun `a slash inside or at the start anchors the pattern to the imported folder`() {
        assertTrue(of("/top.txt").file("top.txt"))
        assertFalse(of("/top.txt").file("sub/top.txt"))
        assertTrue(of("docs/draft.txt").file("docs/draft.txt"))
        assertFalse(of("docs/draft.txt").file("x/docs/draft.txt"))
    }

    @Test
    fun `a bang re-includes and the last matching pattern wins`() {
        val ignore = of("*.tmp", "!keep.tmp")

        assertTrue(ignore.file("a.tmp"))
        assertFalse(ignore.file("keep.tmp"))
        assertFalse(ignore.file("sub/keep.tmp"))
        // Order matters: the same two lines the other way round ignore everything.
        assertTrue(of("!keep.tmp", "*.tmp").file("keep.tmp"))
    }

    @Test
    fun `a file cannot be re-included once its directory is ignored`() {
        val ignore = of("logs/", "!logs/keep.txt")

        assertTrue(ignore.file("logs/keep.txt"))
    }

    @Test
    fun `comments and blank lines mean nothing and an escaped hash is a name`() {
        val ignore = of("# a comment", "", "   ", "\\#notes.txt")

        assertTrue(ignore.file("#notes.txt"))
        assertFalse(ignore.file("a comment"))
        assertFalse(ignore.file("notes.txt"))
    }

    @Test
    fun `an escaped bang is a name and not a negation`() {
        assertTrue(of("\\!important.txt").file("!important.txt"))
    }

    @Test
    fun `matching ignores letter case so a Windows or macOS name is found either way`() {
        val ignore = of("Thumbs.db", "*.TMP")

        assertTrue(ignore.file("thumbs.DB"))
        assertTrue(ignore.file("x.tmp"))
    }

    @Test
    fun `the new-collection defaults ignore system and temporary files but not documents`() {
        val ignore = of(*IgnorePatterns.DEFAULTS.toTypedArray())

        listOf(".DS_Store", "._resource", "Thumbs.db", "desktop.ini", "~\$lock.docx", "x.tmp", "a/b/.DS_Store").forEach {
            assertTrue(ignore.file(it), "$it should be ignored by default")
        }
        assertTrue(ignore.dir(".git"))
        assertTrue(ignore.dir("a/node_modules"))
        assertTrue(ignore.file(".git/HEAD"))
        listOf("report.pdf", "notes.txt", "a/b/c.docx", ".gitignore", "temp.txt").forEach {
            assertFalse(ignore.file(it), "$it must not be ignored by default")
        }
        assertEquals(
            listOf(".DS_Store", "._*", "Thumbs.db", "desktop.ini", "~\$*", "*.tmp", ".git/", "node_modules/"),
            IgnorePatterns.DEFAULTS,
        )
    }

    @Test
    fun `no patterns ignore nothing`() {
        assertFalse(IgnorePatterns.NONE.file("anything"))
        assertFalse(IgnorePatterns.NONE.dir("anything"))
    }

    @Test
    fun `invalid patterns are refused when the list is built`() {
        listOf(
            "!", "/", "!/", "\\", "abc\\", "a\u0000b", "a\tb\nc", "../up", "a/../b", "x".repeat(IgnorePatterns.MAX_PATTERN_LENGTH + 1),
        ).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("accepted ${bad.take(20)}") { IgnorePatterns.of(listOf(bad)) }
        }
        assertFailsWith<IllegalArgumentException> {
            IgnorePatterns.of(List(IgnorePatterns.MAX_PATTERNS + 1) { "p$it" })
        }
    }

    @Test
    fun `lines are trimmed and blank lines are dropped when the list is built`() {
        assertEquals(listOf("*.tmp", "# keep"), IgnorePatterns.of(listOf("  *.tmp  ", "", "   ", "# keep")).patterns)
    }
}
