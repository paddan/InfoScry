package infoscry.ocr

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PageStructureStoreTest {

    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("infoscry-structure-store")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private val page = StructureDerivation.derive(
        PageStructureDraft(
            "test/1",
            listOf(
                BlockDraft(BlockKind.HEADING, "Title", level = 1),
                BlockDraft(BlockKind.TABLE, "", rows = listOf(listOf(CellDraft("a", true), CellDraft("b", false)))),
            ),
        ),
        "c".repeat(64),
    )

    @Test
    fun `write then read round-trips`() {
        val reference = PageStructureStore.write(root, 3, page.structure)
        assertEquals(page.structure, PageStructureStore.read(root, reference, page.extractedText))
    }

    @Test
    fun `writing twice gives the same path and hash`() {
        val first = PageStructureStore.write(root, 3, page.structure)
        val second = PageStructureStore.write(root, 3, page.structure)
        assertEquals(first, second)
        assertNotNull(PageStructureStore.read(root, second, page.extractedText))
    }

    @Test
    fun `a tampered byte reads as null`() {
        val reference = PageStructureStore.write(root, 3, page.structure)
        val file = root.resolve(reference.relativePath)
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        Files.write(file, bytes)
        assertNull(PageStructureStore.read(root, reference, page.extractedText))
    }

    @Test
    fun `a different extracted text reads as null`() {
        val reference = PageStructureStore.write(root, 3, page.structure)
        assertNull(PageStructureStore.read(root, reference, page.extractedText + "x"))
    }

    @Test
    fun `a path outside the root reads as null`() {
        val reference = PageStructureStore.write(root, 3, page.structure)
        assertNull(PageStructureStore.read(root, StructureReference("../x.gz", reference.sha256), page.extractedText))
        assertNull(PageStructureStore.read(root, StructureReference("/etc/hosts", reference.sha256), page.extractedText))
    }

    @Test
    fun `a missing file reads as null`() {
        val reference = PageStructureStore.write(root, 3, page.structure)
        Files.delete(root.resolve(reference.relativePath))
        assertNull(PageStructureStore.read(root, reference, page.extractedText))
    }

    @Test
    fun `a dangling symlink reads as null`() {
        val reference = PageStructureStore.write(root, 3, page.structure)
        val link = root.resolve("structure/dangling.gz")
        Files.createSymbolicLink(link, root.resolve("nowhere"))
        assertNull(PageStructureStore.read(root, StructureReference("structure/dangling.gz", reference.sha256), page.extractedText))
    }

    @Test
    fun `a symlink pointing outside the root reads as null`() {
        val reference = PageStructureStore.write(root, 3, page.structure)
        val outside = Files.createTempDirectory("infoscry-structure-outside")
        try {
            val copy = outside.resolve("copy.gz")
            Files.copy(root.resolve(reference.relativePath), copy)
            Files.createSymbolicLink(root.resolve("structure/link.gz"), copy)
            assertNull(PageStructureStore.read(root, StructureReference("structure/link.gz", reference.sha256), page.extractedText))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
