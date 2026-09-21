package infoscry.extract

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guard between an imported container's own bytes and this process's filesystem.
 *
 * A zip is a list of names a stranger wrote, and every hostile case here is a real one: a name that walks
 * out of the directory it is unpacked into, a name that is absolute, two names that land on one file, and
 * a container that expands to more than the disk it is unpacked onto. What the tests assert is not only
 * that the guard refuses, but that **nothing was written outside the root** when it does — a refusal that
 * already overwrote something is not a refusal.
 *
 * The limits are injected rather than patched to the production numbers so each bound can be crossed
 * deterministically and cheaply, and the production numbers are asserted separately so the shortcut cannot
 * hide a weakened bound.
 *
 * The XML half is here too, because it is the same job: the markup inside a container is data somebody
 * else wrote, and an external entity in it is a request to read this machine's files.
 */
class SafeArchiveTest {

    private lateinit var directory: Path
    private lateinit var root: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-safe-archive")
        root = directory.resolve("root")
        Files.createDirectories(root)
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    // ---- What a well-formed container gives back ----------------------------------------------------

    @Test
    fun `entries and their text are read in the container's own order`() {
        val container = zip("plain.zip", listOf("one.txt" to "first", "two/three.txt" to "second"))

        SafeArchive.open(container).use { archive ->
            assertEquals(listOf("one.txt", "two/three.txt"), archive.entries().map { it.path })
            assertEquals("first", archive.readText(archive.entries()[0]))
            assertEquals("second", archive.readText(archive.entry("two/three.txt")!!))
            assertContentEquals("first".toByteArray(StandardCharsets.UTF_8), archive.readBytes(archive.entries()[0]))
            assertNull(archive.entry("missing.txt"))
        }
    }

    @Test
    fun `an entry is found when the container spells the name in another case`() {
        val container = zip("case.zip", listOf("Images/Plate.png" to "picture"))

        SafeArchive.open(container).use { archive ->
            assertEquals("Images/Plate.png", archive.entry("images/plate.png")?.path)
            assertEquals("Images/Plate.png", archive.entry("Images/Plate.png")?.path)
        }
    }

    @Test
    fun `extract writes under the root and returns the file it wrote`() {
        val container = zip("extract.zip", listOf("nested/thing.txt" to "payload"))

        SafeArchive.open(container).use { archive ->
            val written = archive.extract(archive.entry("nested/thing.txt")!!, root)

            assertEquals(root.resolve("nested/thing.txt"), written)
            assertEquals("payload", Files.readString(written))
            assertTrue(written.normalize().startsWith(root.normalize()))
        }
    }

    // ---- Names a container is not allowed to have ---------------------------------------------------

    @Test
    fun `an entry that walks out of the root is refused and writes nothing`() {
        val container = zip("escape.zip", listOf("../escape.txt" to "hostile"))

        val failure = assertFailsWith<ArchiveRefusedException> { SafeArchive.open(container) }

        assertEquals(SafeArchive.ARCHIVE_UNSAFE_CODE, failure.code)
        assertContains(failure.message.orEmpty(), "escape.txt")
        assertFalse(Files.exists(directory.resolve("escape.txt")), "an escaping name reached the filesystem")
    }

    @Test
    fun `an absolute entry path is refused and writes nothing`() {
        val outside = directory.resolve("absolute.txt")
        val container = zip("absolute.zip", listOf(outside.toString() to "hostile"))

        val failure = assertFailsWith<ArchiveRefusedException> { SafeArchive.open(container) }

        assertEquals(SafeArchive.ARCHIVE_UNSAFE_CODE, failure.code)
        assertFalse(Files.exists(outside), "an absolute name reached the filesystem")
    }

    @Test
    fun `two names that normalise to one target are refused`() {
        val container = zip("duplicate.zip", listOf("a/b.txt" to "first", "a//b.txt" to "second"))

        val failure = assertFailsWith<ArchiveRefusedException> { SafeArchive.open(container) }

        assertEquals(SafeArchive.ARCHIVE_UNSAFE_CODE, failure.code)
        assertContains(failure.message.orEmpty(), "a/b.txt")
    }

    @Test
    fun `two names that differ only by case are refused`() {
        val container = zip("case-collision.zip", listOf("Readme.md" to "first", "readme.md" to "second"))

        val failure = assertFailsWith<ArchiveRefusedException> { SafeArchive.open(container) }

        assertEquals(SafeArchive.ARCHIVE_UNSAFE_CODE, failure.code)
    }

    @Test
    fun `an entry nested deeper than the limit is refused`() {
        val container = zip("deep.zip", listOf("a/b/c/d.txt" to "deep"))

        val failure = assertFailsWith<ArchiveRefusedException> {
            SafeArchive.open(container, ArchiveLimits(maxDepth = 3))
        }

        assertEquals(SafeArchive.ARCHIVE_UNSAFE_CODE, failure.code)
    }

    // ---- Containers that expand past what this process will read -------------------------------------

    @Test
    fun `more entries than the limit are refused`() {
        val names = (1..11).map { index -> "entry-$index.txt" to "x" }
        val container = zip("many.zip", names)

        val failure = assertFailsWith<ArchiveRefusedException> {
            SafeArchive.open(container, ArchiveLimits(maxEntries = 10))
        }

        assertEquals(SafeArchive.ENTRY_COUNT_CODE, failure.code)
    }

    @Test
    fun `an entry whose declared size is past the bound is refused before it is written`() {
        val container = zip("entry-bomb.zip", listOf("big.txt" to "0".repeat(4096)))

        val failure = assertFailsWith<ArchiveRefusedException> {
            SafeArchive.open(container, ArchiveLimits(maxEntryBytes = 1024))
        }

        assertEquals(DOCUMENT_TOO_LARGE_CODE, failure.code)
        assertFalse(Files.exists(root.resolve("big.txt")), "an oversized entry was written")
    }

    @Test
    fun `an archive that expands past the bound is stopped while it is being read`() {
        // Two entries, each well under the per-entry bound, whose total is over it. This is the case a
        // declared-size check cannot catch: each file is small and the container is still a bomb.
        val container = zip("total-bomb.zip", listOf("one.txt" to "0".repeat(2048), "two.txt" to "0".repeat(2048)))

        SafeArchive.open(container, ArchiveLimits(maxEntryBytes = 1024 * 1024, maxTotalBytes = 3000)).use { archive ->
            assertEquals("0".repeat(2048), archive.readText(archive.entries()[0]))
            val failure = assertFailsWith<ArchiveRefusedException> { archive.readText(archive.entries()[1]) }
            assertEquals(DOCUMENT_TOO_LARGE_CODE, failure.code)
        }
    }

    @Test
    fun `the production limits are the numbers the design fixed`() {
        val limits = ArchiveLimits.DEFAULT

        assertEquals(10_000, limits.maxEntries)
        assertEquals(100L * 1024 * 1024, limits.maxEntryBytes)
        assertEquals(1024L * 1024 * 1024, limits.maxTotalBytes)
        assertEquals(32, limits.maxDepth)
    }

    // ---- Containers and entries that cannot be read --------------------------------------------------

    @Test
    fun `an entry whose bytes are not the stream its header promises is refused as unreadable`() {
        // The shape an individually encrypted entry has for this reader: the container's index promises a
        // deflate stream and what follows is not one. Nothing is written and the reason reaches the queue.
        val container = zip("encrypted-entry.zip", listOf("chapter.xhtml" to "lorem ipsum ".repeat(200)))
        corruptCompressedPayload(container)

        SafeArchive.open(container).use { archive ->
            val failure = assertFailsWith<ArchiveRefusedException> { archive.readText(archive.entries()[0]) }
            assertEquals(DOCUMENT_UNREADABLE_CODE, failure.code)
        }
    }

    @Test
    fun `a truncated container is refused as unreadable`() {
        val container = zip("truncated.zip", listOf("one.txt" to "bytes"))
        val bytes = Files.readAllBytes(container)
        Files.write(container, bytes.copyOf(60))

        val failure = assertFailsWith<ArchiveRefusedException> { SafeArchive.open(container) }

        assertEquals(DOCUMENT_UNREADABLE_CODE, failure.code)
    }

    @Test
    fun `a file that is not a container at all is refused as unreadable`() {
        val notAZip = directory.resolve("plain.txt")
        Files.writeString(notAZip, "this is not a zip")

        val failure = assertFailsWith<ArchiveRefusedException> { SafeArchive.open(notAZip) }

        assertEquals(DOCUMENT_UNREADABLE_CODE, failure.code)
    }

    // ---- The markup inside the container -------------------------------------------------------------

    @Test
    fun `an external entity does not read the file it names`() {
        val secret = directory.resolve("secret.txt")
        Files.writeString(secret, "TOP-SECRET-VALUE")
        val markup = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE root [ <!ENTITY xxe SYSTEM "file://$secret"> ]>
            <root>&xxe;</root>
        """.trimIndent()

        val outcome = runCatching { SafeXml.parse(markup.toByteArray(StandardCharsets.UTF_8)) }

        val text = outcome.getOrNull()?.documentElement?.textContent.orEmpty()
        assertFalse(
            text.contains("TOP-SECRET-VALUE"),
            "an external entity pulled a file into the parse: $outcome",
        )
        assertTrue(outcome.isFailure, "a document with a doctype was accepted")
    }

    @Test
    fun `a document without a doctype is parsed`() {
        val markup = """<?xml version="1.0" encoding="UTF-8"?><root><child>text</child></root>"""

        val document = SafeXml.parse(markup.toByteArray(StandardCharsets.UTF_8))

        assertEquals("text", document.documentElement.firstChild.textContent)
    }

    @Test
    fun `markup that is not well formed is refused as unreadable`() {
        val failure = assertFailsWith<ArchiveRefusedException> {
            SafeXml.parse("<root><unclosed>".toByteArray(StandardCharsets.UTF_8))
        }

        assertEquals(DOCUMENT_UNREADABLE_CODE, failure.code)
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    /** A zip with the given entries, in the order given. */
    private fun zip(name: String, entries: List<Pair<String, String>>): Path {
        val target = directory.resolve(name)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }

    /**
     * Overwrites the start of a container's first compressed payload.
     *
     * The structure stays readable — the central directory still lists the entry — while the stream it
     * promises cannot be inflated. That is what a name-by-name reader sees for an entry it cannot decrypt:
     * a container that opens and an entry that fails. The payload's offset is read from the local header
     * (its name and extra-field lengths) rather than assumed, and its end is bounded by the central
     * directory, because the local header's size fields are zero when the writer used a data descriptor.
     */
    private fun corruptCompressedPayload(container: Path) {
        val bytes = Files.readAllBytes(container)
        val nameLength = littleEndianShort(bytes, 26)
        val extraLength = littleEndianShort(bytes, 28)
        val payloadStart = 30 + nameLength + extraLength
        val centralDirectory = centralDirectoryOffset(bytes, payloadStart)
        for (index in payloadStart until minOf(payloadStart + CORRUPTED_BYTES, centralDirectory)) {
            bytes[index] = (bytes[index].toInt() xor 0xFF).toByte()
        }
        Files.write(container, bytes)
    }

    /** The offset of the central directory's first header, searched from [from]. */
    private fun centralDirectoryOffset(bytes: ByteArray, from: Int): Int {
        for (index in from until bytes.size - 4) {
            if (littleEndianInt(bytes, index) == CENTRAL_DIRECTORY_SIGNATURE) return index
        }
        return bytes.size
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or (littleEndianShort(bytes, offset + 2) shl 16)

    private companion object {

        /** How many bytes of the compressed stream are overwritten, enough to break every inflate path. */
        const val CORRUPTED_BYTES: Int = 16

        /** The signature every central directory header starts with. */
        const val CENTRAL_DIRECTORY_SIGNATURE: Int = 0x02014b50
    }
}
