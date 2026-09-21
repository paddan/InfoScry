package infoscry.extract

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The bounds one archive is read under.
 *
 * Every number here is a refusal threshold rather than a target: a document past one of them is not read
 * slowly, it is not read at all, because a container whose expansion is unbounded can take the whole
 * process — and every other job in it — down with a single import.
 *
 * The defaults are the release's numbers: at most 10,000 entries, no single entry past 100 MiB, no
 * archive expanding past 1 GiB in total, and no path deeper than 32 segments. They are injectable so a
 * test can cross each bound cheaply and deterministically, and the tests assert the production values
 * separately so the shortcut cannot quietly weaken them.
 */
data class ArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Long = 100L * 1024 * 1024,
    val maxTotalBytes: Long = 1024L * 1024 * 1024,
    val maxDepth: Int = 32,
) {

    init {
        require(maxEntries >= 1) { "an archive bound must allow at least one entry, was $maxEntries" }
        require(maxEntryBytes >= 1) { "an entry bound must allow at least one byte, was $maxEntryBytes" }
        require(maxTotalBytes >= 1) { "a total bound must allow at least one byte, was $maxTotalBytes" }
        require(maxDepth >= 1) { "a depth bound must allow at least one segment, was $maxDepth" }
    }

    companion object {

        /** The bounds the release reads documents under. */
        val DEFAULT: ArchiveLimits = ArchiveLimits()
    }
}

/**
 * A container, or an entry in it, that this pipeline refuses to read.
 *
 * [code] is the reason the item's outcome records, and it is one of the shared codes wherever the reason
 * is one the other readers also have: an archive that expands past its bound fails under
 * [DOCUMENT_TOO_LARGE_CODE] exactly as an oversized text container does, and one whose bytes cannot be
 * read at all fails under [DOCUMENT_UNREADABLE_CODE]. A name this process will not write is its own
 * reason, [SafeArchive.ARCHIVE_UNSAFE_CODE], because "this container tried to leave the directory" is not
 * something the operator can fix by making the document smaller.
 */
class ArchiveRefusedException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * One readable entry of a guarded archive: the path it names, normalised, and the size it declares.
 *
 * [path] is the canonical form of the name — no leading separator, no `.` or `..` segment, no empty
 * segment — which is what makes it safe to resolve under a directory and what makes two spellings of one
 * target comparable. [declaredBytes] is what the container's index claims, which is a hint the reader
 * checks and never trusts.
 */
data class ArchiveEntry(val path: String, val declaredBytes: Long)

/**
 * Reads a zip container without letting it decide where this process writes.
 *
 * A zip is a list of names a stranger wrote, and every one of them is a potential instruction: `../../`
 * walks out of the directory the archive is unpacked into, a leading `/` names an absolute path, two
 * spellings of one name overwrite each other so that the entry a person sees is not the one that was
 * checked, and an entry that declares a small size and inflates to a large one turns a small download into
 * a full disk. All of that is settled **before anything is written**: [open] validates every name and
 * declared size and refuses the whole container if one is hostile, and the running expansion is charged
 * against [ArchiveLimits.maxTotalBytes] while the bytes are actually read, because a declared size is
 * exactly what a bomb lies about.
 *
 * Two deliberate choices are worth naming:
 *
 * - **A refused container is refused whole.** One name that walks out of the root is not skipped so the
 *   rest can be read: a container that contains such a name was built by something that is not publishing
 *   books, and reading the rest of it would be trusting the part of it that looks ordinary.
 * - **Nothing is written outside the caller's root.** [extract] resolves the normalised path under the
 *   root it is given and writes there, and a path that could escape is rejected at [open] rather than
 *   being sanitised into something that silently points elsewhere.
 *
 * The XML inside the container is guarded by the same idea in [SafeXml]: the markup is data somebody else
 * wrote, and an external entity in it is a request to read this machine's files.
 */
class SafeArchive private constructor(
    private val zip: ZipFile,
    private val limits: ArchiveLimits,
    private val entries: List<ArchiveEntry>,
    private val storedNames: Map<String, String>,
) : AutoCloseable {

    /** How many bytes this archive has expanded so far, across every read. */
    private var expandedBytes: Long = 0

    /** The readable entries, in the order the container lists them. */
    fun entries(): List<ArchiveEntry> = entries

    /**
     * The entry [path] names, or `null`.
     *
     * The lookup is exact first and case-insensitive second. A package document may spell an href with a
     * different case than the container's entry, which is a real defect in real books; because two entries
     * differing only by case are refused at [open], the case-insensitive step can never be ambiguous — at
     * most one entry can match.
     */
    fun entry(path: String): ArchiveEntry? {
        val canonical = canonicalOrNull(path) ?: return null
        storedNames[canonical]?.let { return entries.firstOrNull { entry -> entry.path == canonical } }
        val folded = canonical.lowercase()
        val match = entries.firstOrNull { entry -> entry.path.lowercase() == folded } ?: return null
        return match
    }

    /** The bytes of [entry], charged against this archive's expansion bound. */
    fun readBytes(entry: ArchiveEntry): ByteArray = open(entry).use { stream -> stream.readBytes() }

    /** The text of [entry], read as UTF-8. */
    fun readText(entry: ArchiveEntry): String = readBytes(entry).toString(Charsets.UTF_8)

    /**
     * Writes [entry] under [into] and returns the file.
     *
     * The write lands under a temporary name first and is moved into place, so a container that stops
     * mid-entry leaves no half-file that the next attempt would mistake for evidence.
     */
    fun extract(entry: ArchiveEntry, into: Path): Path {
        val base = into.toAbsolutePath().normalize()
        val target = base.resolve(entry.path).normalize()
        if (!target.startsWith(base)) {
            throw ArchiveRefusedException(
                ARCHIVE_UNSAFE_CODE,
                "the entry '${entry.path}' would be written outside $base",
            )
        }
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.part")
        try {
            open(entry).use { stream ->
                Files.newOutputStream(partial).use { file -> stream.copyTo(file) }
            }
            moveIntoPlace(partial, target)
        } finally {
            Files.deleteIfExists(partial)
        }
        return target
    }

    override fun close() {
        zip.close()
    }

    /**
     * The entry's stream, charged as it is read.
     *
     * Both bounds are checked here rather than at [open] alone, because a declared size is a claim: an
     * entry may declare a kilobyte and inflate to a gigabyte. The per-entry bound is what keeps one
     * `readBytes` from allocating without limit, and the running total is what keeps a container of many
     * such entries from filling the disk.
     */
    private fun open(entry: ArchiveEntry): InputStream {
        val stored = storedNames[entry.path] ?: throw ArchiveRefusedException(
            DOCUMENT_UNREADABLE_CODE,
            "the container no longer lists '${entry.path}'",
        )
        val zipEntry: ZipEntry = zip.getEntry(stored) ?: throw ArchiveRefusedException(
            DOCUMENT_UNREADABLE_CODE,
            "the container does not hold an entry named '$stored'",
        )
        val stream = try {
            zip.getInputStream(zipEntry)
        } catch (failure: ZipException) {
            // The index promised a stream and the bytes are not it: what a truncated download, a corrupt
            // file, and an entry this reader cannot decrypt all look like from here.
            throw ArchiveRefusedException(
                DOCUMENT_UNREADABLE_CODE,
                "the entry '${entry.path}' could not be read",
                failure,
            )
        } catch (failure: IOException) {
            throw ArchiveRefusedException(
                DOCUMENT_UNREADABLE_CODE,
                "the entry '${entry.path}' could not be read",
                failure,
            )
        }
        return GuardedStream(stream, entry.path)
    }

    private fun moveIntoPlace(partial: Path, target: Path) {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * A stream that refuses to produce more than the container is allowed to.
     *
     * Reading is charged rather than estimated, and the accounting deliberately includes a second read of
     * one entry: what the bound protects is the work this archive has produced, and reading the same
     * megabyte twice is two megabytes of work.
     *
     * This is also where a stream that cannot be inflated is translated. The container's index can promise
     * a deflate stream and the bytes can be something else — a truncated download, a corrupt file, an entry
     * this reader cannot decrypt — and the failure surfaces *while reading*, not when the entry is opened.
     * A caller that only caught the open would see a raw `ZipException` escape from a pipeline whose whole
     * vocabulary is its own refusal codes.
     */
    private inner class GuardedStream(
        private val delegate: InputStream,
        private val path: String,
    ) : InputStream() {

        private var entryBytes: Long = 0

        override fun read(): Int {
            val value = guard { delegate.read() }
            if (value >= 0) charge(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = guard { delegate.read(buffer, offset, length) }
            if (read > 0) charge(read.toLong())
            return read
        }

        override fun close() {
            delegate.close()
        }

        private fun <T> guard(block: () -> T): T = try {
            block()
        } catch (failure: ZipException) {
            throw ArchiveRefusedException(
                DOCUMENT_UNREADABLE_CODE,
                "the entry '$path' could not be read",
                failure,
            )
        }

        private fun charge(bytes: Long) {
            entryBytes += bytes
            if (entryBytes > limits.maxEntryBytes) {
                throw ArchiveRefusedException(
                    DOCUMENT_TOO_LARGE_CODE,
                    "the entry '$path' expands past ${limits.maxEntryBytes} bytes and was not read",
                )
            }
            expandedBytes += bytes
            if (expandedBytes > limits.maxTotalBytes) {
                throw ArchiveRefusedException(
                    DOCUMENT_TOO_LARGE_CODE,
                    "the container expands past ${limits.maxTotalBytes} bytes and was not read further",
                )
            }
        }
    }

    /** A path in the form this class compares and resolves, or `null` when it is not a safe one. */
    private fun canonicalOrNull(name: String): String? = try {
        canonicalize(name, limits)
    } catch (refused: ArchiveRefusedException) {
        null
    }

    companion object {

        /**
         * The code an entry name this process will not write fails under.
         *
         * It is its own code rather than a size or readability one, because the person importing the file
         * has to be told which of the three happened: the container is too big to read, it cannot be read
         * at all, or it tried to write somewhere it was not given.
         */
        const val ARCHIVE_UNSAFE_CODE: String = "ARCHIVE_UNSAFE"

        /** The code a container with more entries than the bound allows fails under. */
        const val ENTRY_COUNT_CODE: String = "ARCHIVE_TOO_MANY_ENTRIES"

        /**
         * Opens [container] for guarded reading, or refuses it.
         *
         * Every name and declared size is validated here, before the caller can write anything, and the
         * container's own index is the only thing read: this method never inflates an entry.
         */
        fun open(container: Path, limits: ArchiveLimits = ArchiveLimits.DEFAULT): SafeArchive {
            val zip = try {
                ZipFile(container.toFile())
            } catch (failure: ZipException) {
                throw ArchiveRefusedException(
                    DOCUMENT_UNREADABLE_CODE,
                    "the container could not be opened",
                    failure,
                )
            } catch (failure: IOException) {
                throw ArchiveRefusedException(
                    DOCUMENT_UNREADABLE_CODE,
                    "the container could not be opened",
                    failure,
                )
            }
            try {
                val entries = mutableListOf<ArchiveEntry>()
                val stored = mutableMapOf<String, String>()
                val folded = mutableMapOf<String, String>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    // A directory carries no bytes and no citable text, so it is not counted against the
                    // entry bound: padding a container with directories would otherwise refuse a book whose
                    // content is one chapter.
                    if (entry.isDirectory) continue
                    if (entries.size >= limits.maxEntries) {
                        throw ArchiveRefusedException(
                            ENTRY_COUNT_CODE,
                            "the container holds more than ${limits.maxEntries} entries",
                        )
                    }
                    val path = canonicalize(entry.name, limits)
                    val previous = stored.putIfAbsent(path, entry.name)
                    if (previous != null) {
                        throw ArchiveRefusedException(
                            ARCHIVE_UNSAFE_CODE,
                            "the container holds two entries named '$path': '$previous' and '${entry.name}'",
                        )
                    }
                    val foldedPrevious = folded.putIfAbsent(path.lowercase(), path)
                    if (foldedPrevious != null) {
                        throw ArchiveRefusedException(
                            ARCHIVE_UNSAFE_CODE,
                            "the container holds '$foldedPrevious' and '$path', which are one file on a " +
                                "case-insensitive filesystem",
                        )
                    }
                    if (entry.size > limits.maxEntryBytes) {
                        throw ArchiveRefusedException(
                            DOCUMENT_TOO_LARGE_CODE,
                            "the entry '$path' declares ${entry.size} bytes, past ${limits.maxEntryBytes}",
                        )
                    }
                    entries += ArchiveEntry(path = path, declaredBytes = entry.size)
                }
                return SafeArchive(zip, limits, entries, stored)
            } catch (failure: Throwable) {
                zip.close()
                throw failure
            }
        }

        /**
         * The canonical form of one entry name, or a refusal.
         *
         * The name is split into segments and each one is checked: an empty segment is the `//` a name can
         * carry, a `.` segment means nothing in a path but hides the real one, `..` is the walk out, and a
         * backslash is a separator on a platform this build does not support and a character that no
         * EPUB href is allowed to contain — refusing it costs nothing a real book needs. A leading
         * separator or a drive letter is an absolute path in another spelling.
         */
        internal fun canonicalize(name: String, limits: ArchiveLimits): String {
            if (name.isBlank()) {
                throw ArchiveRefusedException(ARCHIVE_UNSAFE_CODE, "the container holds an unnamed entry")
            }
            if (name.indexOf('\u0000') >= 0) {
                throw ArchiveRefusedException(ARCHIVE_UNSAFE_CODE, "the entry '$name' carries a NUL byte")
            }
            if (name.startsWith('/') || name.startsWith('\\') || DRIVE_LETTER.containsMatchIn(name)) {
                throw ArchiveRefusedException(
                    ARCHIVE_UNSAFE_CODE,
                    "the entry '$name' is an absolute path",
                )
            }
            val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
            segments.forEach { segment ->
                if (segment == "..") {
                    throw ArchiveRefusedException(
                        ARCHIVE_UNSAFE_CODE,
                        "the entry '$name' walks out of the directory it would be written to",
                    )
                }
                if (segment.contains('\\')) {
                    throw ArchiveRefusedException(
                        ARCHIVE_UNSAFE_CODE,
                        "the entry '$name' contains a backslash separator",
                    )
                }
            }
            if (segments.isEmpty()) {
                throw ArchiveRefusedException(ARCHIVE_UNSAFE_CODE, "the entry '$name' names no file")
            }
            if (segments.size > limits.maxDepth) {
                throw ArchiveRefusedException(
                    ARCHIVE_UNSAFE_CODE,
                    "the entry '$name' is nested deeper than ${limits.maxDepth} segments",
                )
            }
            return segments.joinToString("/")
        }

        /** A Windows drive prefix, which is an absolute path even without a leading separator. */
        private val DRIVE_LETTER = Regex("^[A-Za-z]:")

        /**
         * Joins one href onto the directory that holds the document it appeared in.
         *
         * A package document names its chapters relative to itself, and some books spell that with `..`;
         * the result is the same canonical form the container's entries are checked into, so an href that
         * tries to leave the book is refused with the entries rather than silently resolved.
         */
        internal fun resolveHref(base: String, href: String): String? {
            val withoutFragment = href.substringBefore('#')
            if (withoutFragment.isEmpty()) return null
            val decoded = try {
                java.net.URLDecoder.decode(withoutFragment, Charsets.UTF_8)
            } catch (malformed: IllegalArgumentException) {
                withoutFragment
            }
            if (decoded.startsWith('/')) {
                return runCatching { canonicalize(decoded.removePrefix("/"), ArchiveLimits.DEFAULT) }.getOrNull()
            }
            val prefix = base.substringBeforeLast('/', "")
            val combined = if (prefix.isEmpty()) decoded else "$prefix/$decoded"
            return runCatching { canonicalize(combined, ArchiveLimits.DEFAULT) }.getOrNull()
        }
    }
}

/**
 * Parses the XML inside a container without letting it reach the filesystem or the network.
 *
 * The markup in an imported book is data a stranger wrote, and an XML parser's default behaviour is to do
 * what that markup asks: an `<!ENTITY xxe SYSTEM "file:///...">` is a request to read a local file and put
 * its contents in the document, and an external DTD is a request to fetch something over the network. Both
 * are refused here rather than filtered afterwards, because there is no safe way to look at the resolved
 * content of an entity that should never have been resolved.
 *
 * A DOCTYPE declaration is refused whole. No format this pipeline reads needs one, an internal entity
 * subset is how expansion bombs are written, and a document refused for it is a document whose reason a
 * person can read rather than a parse that quietly pulls in a file.
 */
internal object SafeXml {

    /** Parses [bytes] into a DOM, or refuses the document as unreadable. */
    fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature(DISALLOW_DOCTYPE, true)
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false)
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
        } catch (unsupported: Exception) {
            // A parser without these features cannot be made safe to hand a stranger's markup, so it is not
            // used at all. This is a build failure rather than a document failure, and saying so is more
            // useful than parsing anyway.
            throw IllegalStateException("this XML parser cannot be hardened against external entities", unsupported)
        }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (failure: Exception) {
            throw ArchiveRefusedException(
                DOCUMENT_UNREADABLE_CODE,
                "the document's markup could not be read",
                failure,
            )
        }
    }

    private const val DISALLOW_DOCTYPE: String = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES: String =
        "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES: String =
        "http://xml.org/sax/features/external-parameter-entities"
}

/**
 * Every element under this document whose local name is [name], in document order.
 *
 * The local part is what is compared, because the same element is spelled `dc:title` in one package
 * document and `title` in another, and a reader that insisted on the prefix would refuse books that are
 * correct. The walk is a flat scan of the parsed tree rather than an XPath query, because it has to work
 * for a namespace-prefixed document and a plain one alike without the caller knowing which it has.
 */
internal fun Document.elementsNamed(name: String): List<Element> {
    val all = getElementsByTagName("*")
    return (0 until all.length)
        .mapNotNull { index -> all.item(index) as? Element }
        .filter { element -> element.localName() == name }
}

/** Every element under this element whose local name is [name], including this element's descendants. */
internal fun Element.elementsNamed(name: String): List<Element> {
    val all = getElementsByTagName("*")
    return (0 until all.length)
        .mapNotNull { index -> all.item(index) as? Element }
        .filter { element -> element.localName() == name }
}

/** The element's own child elements, in order, without its text nodes. */
internal fun Element.childElements(): List<Element> {
    val children = childNodes
    return (0 until children.length).mapNotNull { index ->
        children.item(index).takeIf { node -> node.nodeType == Node.ELEMENT_NODE } as? Element
    }
}

/**
 * The element's name without its namespace prefix.
 *
 * A document parsed without namespace awareness keeps the prefix in `nodeName` and reports no local name,
 * so the local part is taken from `nodeName` and the two spellings of one element are comparable.
 */
internal fun Element.localName(): String = nodeName.substringAfterLast(':')
