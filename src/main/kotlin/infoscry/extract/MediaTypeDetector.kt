package infoscry.extract

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata

/** The media type InfoScry read from a document's bytes. */
@JvmInline
value class DetectedMediaType(val value: String) {

    override fun toString(): String = value
}

/**
 * What a file actually is, decided from its content.
 *
 * Imported material arrives with whatever name it was published under, and a name is not evidence: a
 * scan saved as `.pdf`, a `.docx` that is really plain text, a `.txt` that is really a container. Every
 * decision downstream — which extractor runs, whether OCR is needed, whether a document is importable
 * at all — is therefore made from the bytes.
 *
 * The file's name is offered as a hint in exactly two situations, and never as a decision: when the bytes
 * say only "this is text", and when they say only "this is some binary nobody recognises". In both cases the
 * name chooses *between readers of the same kind of thing* and cannot overrule the bytes — a container named
 * `.csv` whose content is a zip is still a zip, and a name never turns a readable format into another one.
 * Everywhere else the bytes decide alone, because a hint would win for exactly the containers whose bytes
 * are ambiguous, which is the case the detection exists to settle.
 */
class MediaTypeDetector(private val detector: DefaultDetector = DefaultDetector()) {

    fun detect(path: Path): DetectedMediaType {
        require(Files.isRegularFile(path)) {
            "media type detection needs a regular file, but $path is not one"
        }
        val filename = path.fileName?.toString().orEmpty()
        TikaInputStream.get(path).use { stream ->
            val detected = detector.detect(stream, Metadata()).toString()
            val container = narrowContainer(detected, path)
            val named = narrowTextContainer(container, filename)
            return DetectedMediaType(narrowUnknownBinary(named, filename))
        }
    }

    /**
     * The precise type of a container the detector could only identify as "some Office file".
     *
     * Tika's OOXML and Word 97 detectors live in its parser modules, which this build deliberately does
     * not carry, so a `.docx` arrives as the generic `application/x-tika-ooxml` and a `.doc` as
     * `application/x-tika-msoffice`. Those generic types name every format of their family, which is not
     * enough to choose a reader — and choosing the wrong one is how a spreadsheet gets cited by paragraph.
     *
     * The answer is already in the container, so it is read from there rather than guessed from a name:
     * the entries a zip holds and the streams a compound file holds are what tell a Word document from a
     * workbook. Nothing is parsed and nothing is extracted; only the container's own index is read.
     */
    private fun narrowContainer(detected: String, path: Path): String = when (detected) {
        OOXML_CONTAINER -> ooxmlType(path) ?: detected
        MSOFFICE_CONTAINER -> compoundFileType(path) ?: detected
        ZIP_CONTAINER -> ebookContainerType(path) ?: detected
        else -> detected
    }

    /**
     * The precise type of a zip that is an e-book rather than an archive.
     *
     * Tika's EPUB detector lives in a parser module this build does not carry, so an EPUB arrives as a
     * plain zip: the type says "container" and nothing about what is in it. The answer is in the container's
     * own index — the `mimetype` entry every EPUB must carry first, the `META-INF/container.xml` that marks
     * an EPUB that got its mimetype wrong, and the `.fb2` entry a zipped FictionBook holds — so it is read
     * from there rather than guessed from the name, for the same reason the Office containers are.
     */
    private fun ebookContainerType(path: Path): String? = try {
        ZipFile(path.toFile()).use { zip ->
            when {
                declaredEbookMimetype(zip) != null -> declaredEbookMimetype(zip)
                zip.getEntry(EPUB_CONTAINER_ENTRY) != null -> EPUB_MEDIA_TYPE
                zip.entries().asSequence().any { entry ->
                    entry.name.endsWith(FB2_ENTRY_SUFFIX, ignoreCase = true)
                } -> FBZ_MEDIA_TYPE
                else -> null
            }
        }
    } catch (failure: Exception) {
        if (failure is InterruptedException) throw failure
        null
    }

    /**
     * The media type an EPUB declares in its own `mimetype` entry, read with a bound.
     *
     * The entry is the format's own answer to "what is this file" and it is why the format requires it to
     * come first and uncompressed. It is read through a small bound because a container can put anything
     * there, and only the two types this build reads are accepted: anything else is left to the container
     * check rather than trusted as a declaration.
     */
    private fun declaredEbookMimetype(zip: ZipFile): String? {
        val entry = zip.getEntry(MIMETYPE_ENTRY) ?: return null
        val declared = zip.getInputStream(entry).use { stream -> stream.readNBytes(MIMETYPE_READ_LIMIT) }
            .toString(Charsets.UTF_8)
            .trim()
        return when (declared) {
            EPUB_MEDIA_TYPE -> EPUB_MEDIA_TYPE
            IBOOKS_MEDIA_TYPE -> IBOOKS_MEDIA_TYPE
            else -> null
        }
    }

    /**
     * The type of a binary the bytes could not name, decided by the name it was published under.
     *
     * `.lit`, `.pdb`, `.lrf` and their neighbours have no signature Tika recognises, so they all arrive as
     * `application/octet-stream` — a type that says "some binary" and would send every unknown file to the
     * e-book converter if it were claimed. The names below are the containers a converter is the only
     * reader for, and the rule is deliberately closed: a name that is not on the list leaves the type
     * exactly as the bytes described it.
     */
    private fun narrowUnknownBinary(detected: String, filename: String): String {
        if (detected != OCTET_STREAM) return detected
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in CALIBRE_BOOK_EXTENSIONS) LEGACY_EBOOK_MEDIA_TYPE else detected
    }

    private fun ooxmlType(path: Path): String? = try {
        ZipFile(path.toFile()).use { zip ->
            when {
                zip.getEntry(WORD_DOCUMENT_ENTRY) != null -> DOCX_MEDIA_TYPE
                zip.getEntry(WORKBOOK_ENTRY) != null -> XLSX_MEDIA_TYPE

                zip.getEntry(PRESENTATION_ENTRY) != null -> PPTX_MEDIA_TYPE
                else -> null
            }
        }
    } catch (failure: Exception) {
        // A container that cannot be opened is not a container this detector can name; the caller sees the
        // generic type and refuses the document by name rather than being told a type nobody read.
        if (failure is InterruptedException) throw failure
        null
    }

    private fun compoundFileType(path: Path): String? = try {
        POIFSFileSystem(path.toFile()).use { filesystem ->
            val root = filesystem.root
            when {
                root.hasEntry(WORD_STREAM) -> DOC_MEDIA_TYPE
                root.hasEntry(WORKBOOK_STREAM) || root.hasEntry(WORKBOOK_OLD_STREAM) -> XLS_MEDIA_TYPE
                root.hasEntry(PRESENTATION_STREAM) -> PPT_MEDIA_TYPE
                else -> null
            }
        }
    } catch (failure: Exception) {
        if (failure is InterruptedException) throw failure
        null
    }

    /**
     * The media type of a text container, narrowed by the name the file was published under.
     *
     * Every text container without a signature of its own — CSV, TSV, Markdown — is reported as
     * `text/plain`, which is true and useless: it says the file is text but not which reader understands
     * its structure, so a `.csv` would be read as prose and cited by line range instead of by spreadsheet
     * range, and the viewer for its rows would never be reached. Content cannot settle that question —
     * bytes that differ only in their separator are the same bytes — so the name is consulted, and only
     * here: after the bytes have already said the file is text, and only to choose between readers that
     * all read text.
     *
     * The rule stays subordinate to the bytes. A container named `.csv` whose content is a zip is still a
     * zip, and content that is not text is never claimed to be.
     */
    private fun narrowTextContainer(detected: String, filename: String): String {
        if (detected.substringBefore(';').trim() != TEXT_PLAIN) return detected
        return when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "csv", "tsv" -> TEXT_CSV
            "md", "markdown" -> TEXT_MARKDOWN
            else -> detected
        }
    }
}

/**
 * The extension of a Kindle or legacy e-book container.
 *
 * These are the formats the design lists as readable through an external converter. The name is a fallback
 * that is consulted only after the bytes have been read: a file whose content says no more than "some
 * binary" is routed to the converter when, and only when, it was published under one of these extensions.
 * A `.mobi`, `.azw` or `.azw4` carries a Mobipocket signature, so its bytes normally settle the type on
 * their own and this list merely agrees with them; the rest have no signature this build can name, and the
 * extension is the only thing that says a converter is what reads them. The list is closed: an extension
 * outside it never routes a file to Calibre.
 */
private val CALIBRE_BOOK_EXTENSIONS: Set<String> = setOf(
    "mobi",
    "prc",
    "azw",
    "azw3",
    "azw4",
    "lit",
    "pdb",
    "lrf",
    "rb",
    "snb",
    "tcr",
)

/** The entry every EPUB carries first, which is the format's own declaration of what the file is. */
private const val MIMETYPE_ENTRY = "mimetype"

/** The entry whose presence marks an EPUB whose mimetype entry is missing or nonstandard. */
private const val EPUB_CONTAINER_ENTRY = "META-INF/container.xml"

/** The suffix of the document a zipped FictionBook wraps. */
private const val FB2_ENTRY_SUFFIX = ".fb2"

/** How much of a `mimetype` entry is read: the longest type this build accepts, with room to spare. */
private const val MIMETYPE_READ_LIMIT: Int = 256

/** The generic type Tika reports for a zip it has no more specific detector for. */
private const val ZIP_CONTAINER = "application/zip"

/** The generic type Tika reports for a binary nothing recognises. */
private const val OCTET_STREAM = "application/octet-stream"

/** The generic type Tika reports for every OOXML container when its parser modules are absent. */
private const val OOXML_CONTAINER = "application/x-tika-ooxml"

/** The generic type Tika reports for every Word 97-2003 compound file. */
private const val MSOFFICE_CONTAINER = "application/x-tika-msoffice"

/** The zip entries that tell one OOXML document family from another. */
private const val WORD_DOCUMENT_ENTRY = "word/document.xml"

/**
 * The zip entry that marks an OOXML workbook.
 *
 * `xl/workbook.bin` is deliberately absent. That entry marks a *binary* workbook (`.xlsb`), which is a
 * different format: `XSSFWorkbook` cannot read it, so naming it here would hand such a file to a reader
 * that throws a parse error at the user instead of refusing the type.
 */
private const val WORKBOOK_ENTRY = "xl/workbook.xml"

private const val PRESENTATION_ENTRY = "ppt/presentation.xml"

/** The streams that tell one compound-file format from another. */
private const val WORD_STREAM = "WordDocument"
private const val WORKBOOK_STREAM = "Workbook"
private const val WORKBOOK_OLD_STREAM = "Book"
private const val PRESENTATION_STREAM = "PowerPoint Document"
