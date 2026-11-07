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
 * The data directory holds imported originals and their generated artifacts, so it must not be
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
 * Everything the product writes lives below [root], except the database (Postgres, see
 * [infoscry.storage.DatabaseConfig]) and the source files a user selects for import, which InfoScry
 * reads and never modifies. The layout is deliberately shallow
 * and named, because `doctor`, backup instructions, and deletion recovery all refer to these paths:
 *
 * ```
 * <root>/infoscry.lock    single-writer process lock
 * <library>/<collection>/<document>/original.<ext>   immutable imported copy
 * <library>/<collection>/<document>/artifacts/       generated artifacts for that document
 * <library>/.import-tmp/  scratch for a copy on its way into the library
 * <root>/models/          pinned embedding model files
 * <root>/logs/            rolling structured logs
 * <root>/tmp/             scratch space for copies and child tools
 * ```
 *
 * `<library>` is `<root>/library` unless `INFOSCRY_LIBRARY_DIR` names another directory on the server, such as
 * a larger disk; each collection keeps its documents in a directory of its own there either way.
 */
class AppPaths private constructor(val root: Path, library: Path? = null) {

    val lockFile: Path = root.resolve("infoscry.lock")

    /** Where imported documents are kept: one directory per collection, named by its id. */
    val libraryDir: Path = library ?: root.resolve(LIBRARY_DIRECTORY)

    /**
     * Scratch for a managed copy being written. It is inside the library so moving the finished copy into place
     * stays an atomic rename when the library is on a disk of its own; the leading dot keeps it from being
     * mistaken for a collection.
     */
    val libraryScratchDir: Path get() = libraryDir.resolve(".import-tmp")
    val modelsDir: Path = root.resolve("models")
    val logsDir: Path = root.resolve(LOGS_DIRECTORY)
    val tempDir: Path = root.resolve("tmp")

    /** Files uploaded for import, one area per collection, removed a week after upload. */
    val uploadsDir: Path = root.resolve("uploads")

    /**
     * Where the accelerator records its one verification profile, under the temp root.
     *
     * A profile is a diagnostic artifact, not archive state: it is scratch the way `tmp` is scratch, and
     * it has to be outside `library` because nothing here is imported or evidencing a document.
     */
    val embeddingProfileDir: Path get() = tempDir.resolve("gpu-profile")

    fun collectionDir(collectionId: CollectionId): Path = libraryDir.resolve(collectionId.value)

    fun documentDir(collectionId: CollectionId, documentId: DocumentId): Path =
        collectionDir(collectionId).resolve(documentId.value)

    fun artifactsDir(collectionId: CollectionId, documentId: DocumentId): Path =
        documentDir(collectionId, documentId).resolve(ARTIFACTS_DIRECTORY)

    /**
     * Where one revision's selectable-PDF copy lives: the content-addressed key is its own directory,
     * so two attempts that resolve the same inputs ([infoscry.pdf.selectablePdfArtifactKey]) share one
     * directory and a rewrite is a safe overwrite rather than a second file. It lives inside the
     * document's own `artifacts/` directory, so document and collection deletion park and purge it with
     * everything else the document owns, with no extra step of their own.
     */
    fun selectablePdfDir(collectionId: CollectionId, documentId: DocumentId, revisionId: String, key: String): Path =
        artifactsDir(collectionId, documentId).resolve(SELECTABLE_PDF_DIRECTORY).resolve(revisionId).resolve(key)

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
        listOf(libraryDir, libraryScratchDir, modelsDir, logsDir, tempDir, uploadsDir).forEach(::createPrivateDirectory)
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
        const val SELECTABLE_PDF_DIRECTORY = "selectable-pdf"

        /**
         * The layout of a data directory, naming paths without creating or touching anything.
         *
         * Reading logs or running diagnostics must not change the state an operator asked about, so
         * these callers need the layout without the setup [from] performs.
         */
        fun of(dataDir: Path): AppPaths = layout(dataDir)

        /** The data directory of a running InfoScry, created if it does not exist yet. */
        fun from(dataDir: Path): AppPaths = layout(dataDir).ensureDirectories()

        private fun layout(dataDir: Path): AppPaths {
            val root = dataDir.toAbsolutePath().normalize()
            val library = configuredLibrary()
            // Collections beside logs/ and models/ would mix documents with the server's own files.
            check(library == null || library != root) {
                "$LIBRARY_VARIABLE names the data directory itself; leave it unset for <data>/library or point it at another folder"
            }
            return AppPaths(root, library)
        }

        const val DATA_VARIABLE = "INFOSCRY_DATA_DIR"

        /** Where `INFOSCRY_DATA_DIR` puts the data directory; tests replace it. */
        @Volatile
        var dataDirFromEnvironment: () -> String? = { System.getenv(DATA_VARIABLE) }

        /** The data directory when no `--data-dir` is given: `INFOSCRY_DATA_DIR`, else `~/.infoscry`. */
        fun defaultDataDir(): Path {
            val raw = dataDirFromEnvironment()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return Path.of(System.getProperty("user.home"), ".infoscry")
            val path = Path.of(raw)
            check(path.isAbsolute) { "$DATA_VARIABLE must be an absolute path, was '$raw'" }
            return path.normalize()
        }

        const val LIBRARY_VARIABLE = "INFOSCRY_LIBRARY_DIR"

        /** Where `INFOSCRY_LIBRARY_DIR` puts the library; tests replace it, as they replace the database. */
        @Volatile
        var libraryFromEnvironment: () -> String? = { System.getenv(LIBRARY_VARIABLE) }

        /** The library `INFOSCRY_LIBRARY_DIR` names, or `null` for `<data directory>/library`. */
        internal fun configuredLibrary(): Path? {
            val raw = libraryFromEnvironment()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val path = Path.of(raw)
            check(path.isAbsolute) { "$LIBRARY_VARIABLE must be an absolute path, was '$raw'" }
            // A library on a disk that is not connected must not quietly become an empty folder somewhere else.
            check(path.normalize().parent?.let(Files::isDirectory) ?: true) {
                "$LIBRARY_VARIABLE is $raw, but the folder that holds it does not exist; connect the disk or create the folder"
            }
            return path.normalize()
        }
    }
}
