package infoscry.pdf

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PageTextLayoutStoreTest {

    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("infoscry-layout-store")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `write then read returns an equal layout`() {
        val layout = layoutOf()
        val reference = PageTextLayoutStore.write(root, layout)
        val read = PageTextLayoutStore.read(root, reference, CANONICAL_TEXT)
        assertEquals(layout, read)
    }

    @Test
    fun `write is content-addressed and idempotent`() {
        val layout = layoutOf()
        val first = PageTextLayoutStore.write(root, layout)
        val file = root.resolve(first.relativePath)
        val firstWriteTime = Files.getLastModifiedTime(file)
        // A repeat write of the identical layout must not rewrite the file: same bytes, same name, no-op.
        val second = PageTextLayoutStore.write(root, layout)
        assertEquals(first, second)
        assertEquals(firstWriteTime, Files.getLastModifiedTime(file))
    }

    @Test
    fun `two different layouts get two different references`() {
        val a = PageTextLayoutStore.write(root, layoutOf())
        val b = PageTextLayoutStore.write(root, layoutOf(reasonCode = "PDF_TEXT_ALIGNMENT_AMBIGUOUS", complete = false, spans = emptyList()))
        assertTrue(a.relativePath != b.relativePath)
        assertTrue(a.sha256 != b.sha256)
    }

    @Test
    fun `the write is atomic, never leaving a temporary file behind`() {
        PageTextLayoutStore.write(root, layoutOf())
        val leftovers = Files.walk(root).use { stream ->
            stream.filter { path -> path.toString().endsWith(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "no .tmp scratch file should remain after a successful write: $leftovers")
    }

    @Test
    fun `read refuses a layout whose text hash does not match the canonical text handed to it`() {
        val reference = PageTextLayoutStore.write(root, layoutOf())
        assertFailsWith<PageTextLayoutProvenanceException> {
            PageTextLayoutStore.read(root, reference, "this is not the text the layout was made from")
        }
    }

    @Test
    fun `read refuses a sidecar whose bytes were tampered with after writing`() {
        val reference = PageTextLayoutStore.write(root, layoutOf())
        val file = root.resolve(reference.relativePath)
        Files.write(file, "{ not valid json at all".toByteArray())
        assertFailsWith<PageTextLayoutStoreCorruptException> {
            PageTextLayoutStore.read(root, reference, CANONICAL_TEXT)
        }
    }

    @Test
    fun `read refuses a reference whose recorded hash no longer matches the file's actual bytes`() {
        val reference = PageTextLayoutStore.write(root, layoutOf())
        val file = root.resolve(reference.relativePath)
        // Valid JSON, but not the bytes this hash names: a second, different, otherwise-well-formed layout.
        val swapped = PageTextLayoutStore.write(root, layoutOf(reasonCode = "PDF_TEXT_ALIGNMENT_AMBIGUOUS", complete = false, spans = emptyList()))
        Files.write(file, Files.readAllBytes(root.resolve(swapped.relativePath)))
        assertFailsWith<PageTextLayoutStoreCorruptException> {
            PageTextLayoutStore.read(root, reference, CANONICAL_TEXT)
        }
    }

    @Test
    fun `read refuses a missing artifact`() {
        val reference = LayoutReference("layout/${"a".repeat(64)}.json", "a".repeat(64))
        assertFailsWith<PageTextLayoutStoreCorruptException> {
            PageTextLayoutStore.read(root, reference, CANONICAL_TEXT)
        }
    }

    @Test
    fun `read refuses a reference that resolves through a symlink escaping the artifact root`() {
        val outside = Files.createTempDirectory("infoscry-layout-outside")
        try {
            val secretFile = outside.resolve("secret.json")
            Files.write(
                secretFile,
                Json.encodeToString(PageTextLayout.serializer(), layoutOf()).toByteArray(Charsets.UTF_8),
            )
            val layoutDir = root.resolve("layout").createDirectories()
            val symlinkName = "escaped.json"
            Files.createSymbolicLink(layoutDir.resolve(symlinkName), secretFile)
            val reference = LayoutReference("layout/$symlinkName", oracleSha256("irrelevant"))
            val failure = assertFailsWith<IllegalArgumentException> {
                PageTextLayoutStore.read(root, reference, CANONICAL_TEXT)
            }
            assertTrue(failure.message.orEmpty().contains("symlink"))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verifyImageMatch accepts the layout's own recorded image hash`() {
        val layout = layoutOf()
        PageTextLayoutStore.verifyImageMatch(layout, layout.imageSha256)
    }

    @Test
    fun `verifyImageMatch refuses a layout verified against a different image's bytes`() {
        val layout = layoutOf()
        assertFailsWith<PageTextLayoutProvenanceException> {
            PageTextLayoutStore.verifyImageMatch(layout, oracleSha256("a completely different picture"))
        }
    }

    @Test
    fun `write resolves relative to the given root regardless of its own working directory`() {
        val nested = root.resolve("collection-x/document-y")
        val layout = layoutOf()
        val reference = PageTextLayoutStore.write(nested, layout)
        assertTrue(Files.exists(nested.resolve(reference.relativePath)))
        assertEquals(layout, PageTextLayoutStore.read(nested, reference, CANONICAL_TEXT))
    }
}

private val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
