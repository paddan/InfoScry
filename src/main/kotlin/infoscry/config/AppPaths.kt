package infoscry.config

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

/**
 * The private file mode InfoScry gives its own data.
 *
 * The data directory holds imported originals, extracted text, and index files, so it must not be
 * readable by other users of the machine. Where the filesystem has no POSIX modes (for example a
 * mounted FAT volume) there is nothing to apply and nothing to claim: the mode is simply absent,
 * rather than reported as applied.
 */
internal object PrivatePermissions {

    val posixSupported: Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private val directoryMode = PosixFilePermissions.fromString("rwx------")
    private val fileMode = PosixFilePermissions.fromString("rw-------")

    /** Attributes for [Files.createDirectory] and friends; empty where POSIX modes do not exist. */
    fun directoryAttributes(): Array<FileAttribute<*>> =
        if (posixSupported) arrayOf(PosixFilePermissions.asFileAttribute(directoryMode)) else emptyArray()

    /** Attributes for a newly created file, so a moved-in original keeps the private mode. */
    fun fileAttributes(): Array<FileAttribute<*>> =
        if (posixSupported) arrayOf(PosixFilePermissions.asFileAttribute(fileMode)) else emptyArray()

    /**
     * Re-applies the private mode to something that already exists. Creation attributes only cover
     * paths created in that call, so an existing directory has to be hardened explicitly.
     */
    fun hardenDirectory(directory: Path) {
        if (posixSupported) Files.setPosixFilePermissions(directory, directoryMode)
    }

    /** Re-applies the private mode to an existing file, for the same reason as [hardenDirectory]. */
    fun hardenFile(file: Path) {
        if (posixSupported) Files.setPosixFilePermissions(file, fileMode)
    }
}

/**
 * The layout of one InfoScry data directory.
 *
 * Everything the product writes lives below [root]; the only exception is the source files a user
 * selects for import, which InfoScry reads and never modifies. The layout is deliberately shallow
 * and named, because `doctor`, backup instructions, and deletion recovery all refer to these paths:
 *
 * ```
 * <root>/infoscry.db      authoritative SQLite database
 * <root>/infoscry.lock    single-writer process lock
 * <root>/runtime.json     PID, port and bearer token, present only while the server runs
 * <root>/library/<collection>/<document>/original.<ext>   immutable imported copy
 * <root>/library/<collection>/<document>/artifacts/       generated artifacts for that document
 * <root>/index/           Lucene generations
 * <root>/models/          pinned embedding model files
 * <root>/logs/            rolling structured logs
 * <root>/tmp/             scratch space for copies and child tools
 * ```
 */
class AppPaths private constructor(val root: Path) {

    val databaseFile: Path = root.resolve("infoscry.db")
    val lockFile: Path = root.resolve("infoscry.lock")
    val runtimeFile: Path = root.resolve("runtime.json")
    val libraryDir: Path = root.resolve(LIBRARY_DIRECTORY)
    val indexDir: Path = root.resolve("index")
    val modelsDir: Path = root.resolve("models")
    val logsDir: Path = root.resolve(LOGS_DIRECTORY)
    val tempDir: Path = root.resolve("tmp")

    fun collectionDir(collectionId: CollectionId): Path = libraryDir.resolve(collectionId.value)

    fun documentDir(collectionId: CollectionId, documentId: DocumentId): Path =
        collectionDir(collectionId).resolve(documentId.value)

    fun artifactsDir(collectionId: CollectionId, documentId: DocumentId): Path =
        documentDir(collectionId, documentId).resolve(ARTIFACTS_DIRECTORY)

    /**
     * Where a collection's managed directory is parked while its deletion is in flight.
     *
     * The trash lives inside `library/` on purpose: parking it has to be an atomic rename, which needs
     * the same filesystem, and the durable deletion record stores only the basename because the parent
     * is derivable. The leading dot keeps a parked directory from being mistaken for a collection.
     */
    fun trashDirectory(trashBasename: String): Path = libraryDir.resolve(trashBasename)

    /** The immutable managed copy of an import, named after the source's extension. */
    fun originalFile(collectionId: CollectionId, documentId: DocumentId, extension: String): Path =
        documentDir(collectionId, documentId).resolve("original.$extension")

    /**
     * Creates the document directory, its artifacts directory, and the collection directory they
     * live in, each with the private mode. Returns the document directory.
     */
    fun createDocumentDirectories(collectionId: CollectionId, documentId: DocumentId): Path {
        createPrivateDirectory(collectionDir(collectionId))
        createPrivateDirectory(documentDir(collectionId, documentId))
        createPrivateDirectory(artifactsDir(collectionId, documentId))
        return documentDir(collectionId, documentId)
    }

    /** Creates every directory in the layout. Safe to call repeatedly, and re-hardens each one. */
    fun ensureDirectories(): AppPaths {
        createPrivateDirectory(root)
        listOf(libraryDir, indexDir, modelsDir, logsDir, tempDir).forEach(::createPrivateDirectory)
        return this
    }

    private fun createPrivateDirectory(directory: Path) {
        if (Files.isDirectory(directory)) {
            PrivatePermissions.hardenDirectory(directory)
            return
        }
        Files.createDirectories(directory, *PrivatePermissions.directoryAttributes())
        PrivatePermissions.hardenDirectory(directory)
    }

    companion object {
        const val ARTIFACTS_DIRECTORY = "artifacts"
        const val LIBRARY_DIRECTORY = "library"
        const val LOGS_DIRECTORY = "logs"

        /**
         * The layout of a data directory, naming paths without creating or touching anything.
         *
         * Reading logs or running diagnostics must not change the state an operator asked about, so
         * these callers need the layout without the setup [from] performs.
         */
        fun of(dataDir: Path): AppPaths = AppPaths(dataDir.toAbsolutePath().normalize())

        /** The data directory of a running InfoScry, created if it does not exist yet. */
        fun from(dataDir: Path): AppPaths =
            AppPaths(dataDir.toAbsolutePath().normalize()).ensureDirectories()
    }
}
