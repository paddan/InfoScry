package infoscry.library

import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DocumentStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedLibraryTest {

    private lateinit var dataDir: Path
    private lateinit var sourceDir: Path
    private lateinit var paths: AppPaths
    private lateinit var database: Database
    private lateinit var library: ManagedLibrary
    private lateinit var documents: DocumentStore
    private var nightfall: CollectionId = CollectionId("nightfall")
    private var acme: CollectionId = CollectionId("acme")

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-library")
        sourceDir = Files.createTempDirectory("infoscry-sources")
        paths = AppPaths.from(dataDir)
        database = Database(paths.databaseFile)
        SchemaMigrator(database).migrate()
        documents = DocumentStore(database)
        library = ManagedLibrary(paths, documents)
        val collections = CollectionStore(database)
        nightfall = collections.create("Nightfall").id
        acme = collections.create("Acme").id
    }

    @AfterTest
    fun closeDataDirectory() {
        database.close()
        dataDir.toFile().deleteRecursively()
        sourceDir.toFile().deleteRecursively()
    }

    @Test
    fun `the same bytes twice in one collection are a duplicate`() {
        val source = sourceFile("report.pdf", "nightfall report")
        val sha256 = sha256Of(source)

        val first = library.importFile(nightfall, source)
        val second = library.importFile(nightfall, source)

        assertEquals(ManagedImportOutcome.CREATED, first.outcome)
        assertEquals(ManagedImportOutcome.DUPLICATE, second.outcome)
        assertEquals(first.document.id, second.document.id)
        assertEquals(first.managedPath, second.managedPath)
        assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(second.managedPath))
        assertEquals(1, documents.countByCollection(nightfall))
        assertEquals(listOf(first.document.id), managedDocumentIds(nightfall))
        assertEquals(emptyList(), tempFiles())
    }

    @Test
    fun `the same bytes in another collection are a separate document`() {
        val source = sourceFile("report.pdf", "nightfall report")

        val inNightfall = library.importFile(nightfall, source)
        val inAcme = library.importFile(acme, source)

        assertEquals(ManagedImportOutcome.CREATED, inAcme.outcome)
        assertNotEquals(inNightfall.document.id, inAcme.document.id)
        assertNotEquals(inNightfall.managedPath, inAcme.managedPath)
        assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(inAcme.managedPath))
        assertEquals(1, documents.countByCollection(nightfall))
        assertEquals(1, documents.countByCollection(acme))
    }

    @Test
    fun `duplicate detection uses the bytes and not the filename`() {
        val source = sourceFile("report.pdf", "nightfall report")
        val sameBytesOtherName = sourceFile("copy-of-report.pdf", "nightfall report")
        val otherBytesSameName = Files.createDirectories(sourceDir.resolve("nested"))
            .resolve("report.pdf")
            .also { Files.writeString(it, "acme report") }

        val first = library.importFile(nightfall, source)

        assertEquals(
            ManagedImportOutcome.DUPLICATE,
            library.importFile(nightfall, sameBytesOtherName).outcome,
        )
        assertEquals(
            ManagedImportOutcome.CREATED,
            library.importFile(nightfall, otherBytesSameName).outcome,
        )
        assertEquals(sha256Of(source), first.document.sha256)
        assertEquals(2, documents.countByCollection(nightfall))
    }

    @Test
    fun `managed copy survives deleting the source`() {
        val source = sourceFile("report.pdf", "nightfall report")
        val payload = Files.readAllBytes(source)

        val imported = library.importFile(nightfall, source)
        Files.delete(source)

        assertContentEquals(payload, Files.readAllBytes(imported.managedPath))
    }

    @Test
    fun `managed copy survives moving the source`() {
        val source = sourceFile("report.pdf", "nightfall report")
        val payload = Files.readAllBytes(source)
        val imported = library.importFile(nightfall, source)

        val moved = Files.move(source, sourceDir.resolve("renamed.pdf"))

        assertTrue(Files.exists(moved))
        assertContentEquals(payload, Files.readAllBytes(imported.managedPath))
        assertEquals(source.toAbsolutePath().toString(), imported.document.sourcePath)
    }

    @Test
    fun `a failed copy leaves no final file and no document row`() {
        val source = sourceFile("report.pdf", "nightfall report")
        val sha256 = sha256Of(source)

        // Block the copy step itself: the temporary directory the copy streams into is replaced by a
        // regular file, so creating the temporary file fails before any document directory exists.
        Files.delete(paths.tempDir)
        Files.createFile(paths.tempDir)

        assertFailsWith<java.io.IOException> { library.importFile(nightfall, source) }

        assertNull(documents.findBySha256(nightfall, sha256))
        assertEquals(0, documents.countByCollection(nightfall))
        assertTrue(managedDocumentIds(nightfall).isEmpty())
        assertTrue(Files.exists(source))
        assertContentEquals("nightfall report".toByteArray(), Files.readAllBytes(source))
    }

    @Test
    fun `a copy that cannot start leaves the data directory usable`() {
        val source = sourceFile("report.pdf", "nightfall report")
        Files.delete(paths.tempDir)
        Files.createFile(paths.tempDir)
        assertFailsWith<java.io.IOException> { library.importFile(nightfall, source) }

        Files.delete(paths.tempDir)
        paths.ensureDirectories()

        val imported = library.importFile(nightfall, source)

        assertEquals(ManagedImportOutcome.CREATED, imported.outcome)
        assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(imported.managedPath))
    }

    @Test
    fun `import rejects a missing or non-regular source`() {
        val directory = Files.createDirectory(sourceDir.resolve("folder"))

        assertFailsWith<java.nio.file.NoSuchFileException> {
            library.importFile(nightfall, sourceDir.resolve("absent.pdf"))
        }
        assertFailsWith<IllegalArgumentException> { library.importFile(nightfall, directory) }

        assertEquals(0, documents.countByCollection(nightfall))
        assertTrue(managedDocumentIds(nightfall).isEmpty())
    }

    @Test
    fun `import rejects an unknown collection without leaving a file`() {
        val source = sourceFile("report.pdf", "nightfall report")

        assertFailsWith<NoSuchElementException> {
            library.importFile(CollectionId("missing"), source)
        }

        assertEquals(0, Files.list(paths.libraryDir).use { it.count() }.toInt())
        assertEquals(emptyList(), tempFiles())
    }

    @Test
    fun `a managed document keeps its original metadata and an artifacts directory`() {
        val source = sourceFile("Quarterly Report.PDF", "nightfall report")

        val imported = library.importFile(nightfall, source)
        val document = imported.document

        assertEquals(nightfall, document.collectionId)
        assertEquals("Quarterly Report.PDF", document.originalFilename)
        assertEquals(source.toAbsolutePath().toString(), document.sourcePath)
        assertEquals(Files.size(source), document.sizeBytes)
        assertEquals(sha256Of(source), document.sha256)
        assertTrue(document.mediaType.isNotBlank())
        assertEquals(DocumentStatus.COPYING, document.status)
        assertEquals(
            paths.artifactsDir(nightfall, document.id),
            imported.managedPath.parent.resolve("artifacts"),
        )
        assertTrue(Files.isDirectory(paths.artifactsDir(nightfall, document.id)))
        assertEquals("original.pdf", imported.managedPath.fileName.toString())
    }

    private fun sourceFile(name: String, content: String): Path =
        Files.writeString(sourceDir.resolve(name), content)

    /** Every document directory that exists on disk for [collectionId], ordered for stable asserts. */
    private fun managedDocumentIds(collectionId: CollectionId): List<DocumentId> {
        val collectionDir = paths.collectionDir(collectionId)
        if (!Files.isDirectory(collectionDir)) return emptyList()
        return Files.newDirectoryStream(collectionDir).use { entries ->
            entries.filter { Files.isDirectory(it) }
                .map { DocumentId(it.fileName.toString()) }
                .sortedBy { it.value }
        }
    }

    private fun tempFiles(): List<String> =
        Files.newDirectoryStream(paths.tempDir).use { entries ->
            entries.map { it.fileName.toString() }.sorted()
        }

    private fun sha256Of(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}
