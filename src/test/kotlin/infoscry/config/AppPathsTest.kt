package infoscry.config

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppPathsTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-paths")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `from creates every directory the storage layout names`() {
        val paths = AppPaths.from(dataDir)

        assertEquals(dataDir.toAbsolutePath().normalize(), paths.root)
        assertEquals(listOf("index", "library", "logs", "models", "tmp"), childDirectoryNames(paths))
        assertEquals(paths.root.resolve("infoscry.db"), paths.databaseFile)
        assertEquals(paths.root.resolve("infoscry.lock"), paths.lockFile)
        assertEquals(paths.root.resolve("runtime.json"), paths.runtimeFile)
    }

    @Test
    fun `data directories are private on posix filesystems`() {
        val paths = AppPaths.from(dataDir)

        assertTrue(posixSupported(), "this test asserts POSIX modes and needs a POSIX filesystem")
        val privateDirectory = PosixFilePermissions.fromString("rwx------")
        listOf(paths.root, paths.libraryDir, paths.indexDir, paths.modelsDir, paths.logsDir, paths.tempDir)
            .forEach { directory ->
                assertEquals(
                    privateDirectory,
                    Files.getPosixFilePermissions(directory),
                    "$directory must not be readable by other users",
                )
            }
    }

    @Test
    fun `ensureDirectories is idempotent and hardens an existing directory`() {
        val wideOpen = Files.createDirectory(dataDir.resolve("library"))
        Files.setPosixFilePermissions(wideOpen, PosixFilePermissions.fromString("rwxrwxrwx"))

        val paths = AppPaths.from(dataDir)
        paths.ensureDirectories()

        assertTrue(Files.isDirectory(wideOpen))
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(wideOpen),
        )
        assertEquals(listOf("index", "library", "logs", "models", "tmp"), childDirectoryNames(paths))
    }

    @Test
    fun `document paths are derived from the collection and document identifiers`() {
        val paths = AppPaths.from(dataDir)
        val collectionId = CollectionId("nightfall")
        val documentId = DocumentId("report")

        assertEquals(paths.libraryDir.resolve("nightfall"), paths.collectionDir(collectionId))
        assertEquals(
            paths.libraryDir.resolve("nightfall").resolve("report"),
            paths.documentDir(collectionId, documentId),
        )
        assertEquals(
            paths.documentDir(collectionId, documentId).resolve("artifacts"),
            paths.artifactsDir(collectionId, documentId),
        )
        assertEquals(
            paths.documentDir(collectionId, documentId).resolve("original.pdf"),
            paths.originalFile(collectionId, documentId, "pdf"),
        )
    }

    @Test
    fun `createDocumentDirectories creates the document and artifacts directories privately`() {
        val paths = AppPaths.from(dataDir)
        val collectionId = CollectionId("nightfall")
        val documentId = DocumentId("report")

        val documentDir = paths.createDocumentDirectories(collectionId, documentId)

        assertEquals(paths.documentDir(collectionId, documentId), documentDir)
        assertTrue(Files.isDirectory(paths.collectionDir(collectionId)))
        assertTrue(Files.isDirectory(paths.artifactsDir(collectionId, documentId)))
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(documentDir),
        )
    }

    private fun childDirectoryNames(paths: AppPaths): List<String> =
        Files.list(paths.root).use { entries ->
            entries.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }

    private fun posixSupported(): Boolean =
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
}
