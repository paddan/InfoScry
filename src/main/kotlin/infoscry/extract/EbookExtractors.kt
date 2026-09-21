package infoscry.extract

import infoscry.domain.SourceLocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.w3c.dom.Node

/** The media type of an EPUB, which is also what a KEPUB is. */
internal const val EPUB_MEDIA_TYPE: String = "application/epub+zip"

/** The media type of an Apple iBooks file: an EPUB container with Apple's own `mimetype`. */
internal const val IBOOKS_MEDIA_TYPE: String = "application/x-ibooks+zip"

/** The media type of a FictionBook, which Tika names from the document's own root element. */
internal const val FB2_MEDIA_TYPE: String = "application/x-fictionbook+xml"

/** The media type of a zipped FictionBook. */
internal const val FBZ_MEDIA_TYPE: String = "application/x-fictionbook+zip"

/** The media type of a Mobipocket-family container: `.mobi`, `.prc`, `.azw`, `.azw3`. */
internal const val MOBIPOCKET_MEDIA_TYPE: String = "application/x-mobipocket-ebook"

/**
 * The media type of an e-book container whose bytes carry no signature this build can name.
 *
 * `.lit`, `.pdb`, `.lrf`, `.rb`, `.snb`, `.tcr` and their neighbours are Palm, Sony, Rocket and
 * plain-archive containers with no magic number Tika recognises: the bytes say only "some binary", and no
 * registered type describes them in this build. Inventing a name here is the honest option — the
 * alternative is either claiming `application/octet-stream`, which would route *every* unknown binary to
 * the converter, or refusing a format the product promises to read. The name says exactly what the type
 * is: a container this reader only knows is an e-book because the file said so.
 */
internal const val LEGACY_EBOOK_MEDIA_TYPE: String = "application/x-infoscry-ebook"

/**
 * The largest e-book container this pipeline will read.
 *
 * A book is small: a thousand chapters of markup is a few megabytes, and its illustrations rarely take it
 * past a few tens. Past this the file is either something else wearing an e-book's name or a container
 * built to be expensive, and either way it is refused rather than read.
 */
internal const val MAX_EBOOK_DOCUMENT_BYTES: Long = 64L * 1024 * 1024

/**
 * How many pictures one picture-only chapter has read.
 *
 * A chapter whose markup holds no text at all is read by OCR, one picture at a time, and each reading is a
 * child process. The bound keeps one chapter from turning into hundreds of tool calls; pictures past it are
 * counted in the document's metadata rather than dropped silently.
 */
internal const val MAX_OCR_IMAGES_PER_CHAPTER: Int = 8

/** How one e-book's sections are named, so a resumed attempt recognises what it already has. */
internal fun ebookSectionKey(spineIndex: Int, sectionIndex: Int): String =
    String.format(Locale.ROOT, "ebook:s%03d:%03d", spineIndex, sectionIndex)

/** The key of the unit a picture-only chapter produces. */
internal fun ebookImageKey(spineIndex: Int): String =
    String.format(Locale.ROOT, "ebook:s%03d:image", spineIndex)

/**
 * EPUB, KEPUB, and iBooks: a zip of markup, cited by the section a reader would open.
 *
 * An e-book is the one format in this pipeline whose structure is *declared* rather than implied: the
 * package document lists the chapters, says what order they are read in, and names them, and the
 * navigation document gives those names the words a person wrote. A citation that ignored that would point
 * at `chapter2.xhtml`, which is a file name rather than a place in a book, so every unit here is an
 * [SourceLocation.EbookSection] carrying the chapter's own title and the heading path inside it.
 *
 * Three properties are worth naming, because they are why this is one class rather than a branch per
 * format:
 *
 * - **The container is a stranger's.** Everything is read through [SafeArchive], which refuses a book whose
 *   entries would be written outside the artifact root or whose expansion is unbounded, and every XML parse
 *   goes through [SafeXml], which refuses a DOCTYPE rather than letting an external entity reach a file.
 * - **A chapter with no text is read by a tool.** A scanned chapter, a comic page, a facsimile — the markup
 *   holds a picture and nothing else, and the only way its content becomes searchable is the OCR path the
 *   rest of this pipeline uses: the same seam, the same languages, the same artifact layout.
 * - **Committed sections are not paid for twice.** Section keys are deterministic, so a resumed attempt
 *   skips the work for what it already has — including the tool call, which is the expensive part.
 */
class EpubExtractor(
    private val ocr: suspend (RenderedPage) -> OcrResult,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    private val maxDocumentBytes: Long = MAX_EBOOK_DOCUMENT_BYTES,
    private val maxOcrImagesPerChapter: Int = MAX_OCR_IMAGES_PER_CHAPTER,
) : DocumentExtractor {

    init {
        require(maxDocumentBytes >= 1) {
            "an e-book memory bound must allow at least one byte, was $maxDocumentBytes"
        }
        require(maxOcrImagesPerChapter >= 0) {
            "a picture bound cannot be negative, was $maxOcrImagesPerChapter"
        }
    }

    override val supportedMediaTypes: Set<String> = setOf(EPUB_MEDIA_TYPE, IBOOKS_MEDIA_TYPE)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val size = managedSize(input) ?: run {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
            return@flow
        }
        if (size > maxDocumentBytes) {
            refuseDocument(input, DOCUMENT_TOO_LARGE_KEY, DOCUMENT_TOO_LARGE_CODE)
            return@flow
        }

        val archive = try {
            SafeArchive.open(input.managedPath, limits)
        } catch (refused: ArchiveRefusedException) {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, refused.code)
            return@flow
        }

        // One private directory per attempt, for the pictures a chapter hands to the tool. It is deleted
        // when the attempt ends: a picture inside a book is not a citable artifact, and publishing one
        // under the artifact root would make it look like evidence that can be reopened.
        var workDirectory: Path? = null
        try {
            archive.use { opened ->
                val book = try {
                    EpubPackage.read(opened)
                } catch (refused: ArchiveRefusedException) {
                    refuseDocument(input, DOCUMENT_REFUSED_KEY, refused.code)
                    return@flow
                }
                val directory = Files.createTempDirectory(WORK_DIRECTORY_PREFIX)
                workDirectory = directory
                emitBook(input, opened, book, directory)
            }
        } finally {
            workDirectory?.toFile()?.deleteRecursively()
        }
    }

    private suspend fun FlowCollector<ExtractionEvent>.emitBook(
        input: ExtractionInput,
        archive: SafeArchive,
        book: EpubPackage.Book,
        workDirectory: Path,
    ) {
        var ordinal = 0
        var total = 0
        var pictures = 0
        var picturesNotRead = 0
        var footnotes = 0
        var ocrPages = 0
        val confidences = mutableListOf<Double>()
        var abortCode: String? = null

        for ((spineIndex, item) in book.spine.withIndex()) {
            if (abortCode != null) break
            val chapterPath = item.path
            val entry = archive.entry(chapterPath) ?: run {
                // The package names a chapter the container does not hold: the book is not readable as the
                // book it claims to be, and reporting every later chapter as missing would bury the one
                // fact that explains it.
                refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
                return
            }
            val markup = try {
                archive.readText(entry)
            } catch (refused: ArchiveRefusedException) {
                refuseDocument(input, DOCUMENT_REFUSED_KEY, refused.code)
                return
            } catch (failure: IOException) {
                refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
                return
            }
            val chapter = Jsoup.parse(markup)
            val chapterTitle = book.titles[chapterPath]
            footnotes += footnoteCount(chapter)
            val images = imageHrefs(chapter)
            pictures += images.size

            val sections = HtmlSections.sectionsOf(HtmlSanitizer.sanitize(chapter))
                .filter { section -> section.text.isNotBlank() }

            for ((sectionIndex, section) in sections.withIndex()) {
                val key = ebookSectionKey(spineIndex, sectionIndex)
                val unitOrdinal = ordinal
                ordinal++
                total++
                if (input.isCommitted(key)) continue
                input.boundary.unit {
                    val normalised = TextNormalizer.normalize(section.text)
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = unitOrdinal,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.EbookSection(
                                    chapter = chapterTitle,
                                    headingPath = section.headingPath,
                                    spineIndex = spineIndex,
                                ),
                                extractedText = normalised.extracted,
                                searchText = normalised.search,
                            ),
                        ),
                    )
                }
            }

            if (sections.isNotEmpty()) {
                // A chapter the reader reads as text is never handed to a tool, so its plates have not been
                // read as pictures either. Leaving them out of the total made "images: 12,
                // pictures_not_read: 0" read as though twelve pictures had been read.
                picturesNotRead += images.size
                continue
            }

            // A chapter with no text of its own: the pages of a scan, a facsimile, a comic page. Its
            // content exists only as a picture, so a tool has to read it — and the unit is still the
            // chapter, because that is the place in the book a reader would open.
            if (images.isEmpty()) continue
            val key = ebookImageKey(spineIndex)
            val unitOrdinal = ordinal
            ordinal++
            total++
            if (input.isCommitted(key)) continue

            // The pictures are written out here rather than while the chapter was being examined: a
            // chapter with text is not read by a tool, and materialising its plates would write files
            // nothing looks at. They land in this attempt's own directory, which is removed when the
            // attempt ends — a picture inside a book is not a citable artifact.
            val written = images.take(maxOcrImagesPerChapter)
                .mapNotNull { href -> resolveImage(archive, chapterPath, href, workDirectory) }
            picturesNotRead += images.size - written.size
            // The page number is the picture's place in the *book*, not the number of readings this attempt
            // has made: a resumed attempt skips the chapters it already has, and an artifact named after the
            // reading's position would be written under a different name the second time.
            val firstPicture = pictures - images.size
            input.boundary.unit {
                val texts = mutableListOf<String>()
                var confidence: Double? = null
                var artifactPath: String? = null
                var artifactSha: String? = null
                for ((imageIndex, image) in written.withIndex()) {
                    val reading = try {
                        ocr(
                            RenderedPage(
                                documentId = input.documentId,
                                page = firstPicture + imageIndex + 1,
                                imagePath = image,
                                artifactRoot = input.artifactRoot,
                                ocrLanguages = input.settings.ocrLanguages,
                                // The picture is the source, not a rendering of one: this pipeline chose
                                // no resolution for it, and saying it did would be inventing provenance.
                                renderDpi = null,
                                fingerprint = input.fingerprint,
                            ),
                        )
                    } catch (unavailable: OcrUnavailableException) {
                        abortCode = unavailable.code
                        emitDocumentRefusal(input, DOCUMENT_REFUSED_KEY, unavailable.code)
                        return@unit
                    } catch (failure: IOException) {
                        emit(
                            ExtractionEvent.UnitFailed(
                                key = key,
                                ordinal = unitOrdinal,
                                code = OCR_FAILED_CODE,
                            ),
                        )
                        return@unit
                    }
                    if (reading.text.isNotBlank()) texts += reading.text
                    confidence = reading.meanConfidence
                    reading.meanConfidence?.let { value -> confidences += value }
                    reading.artifactRelativePath?.let { path -> artifactPath = path }
                    reading.artifactSha256?.let { sha -> artifactSha = sha }
                    ocrPages++
                }
                if (texts.isEmpty()) {
                    // The tool looked at every picture and read nothing. That is a failure of this unit
                    // rather than a silent empty section: a chapter that reaches a reader as "no content"
                    // when a scan was never read is the kind of absence that gets mistaken for evidence.
                    emit(ExtractionEvent.UnitFailed(key = key, ordinal = unitOrdinal, code = OCR_FAILED_CODE))
                    return@unit
                }
                val normalised = TextNormalizer.normalize(texts.joinToString("\n"))
                emit(
                    ExtractionEvent.UnitReady(
                        key = key,
                        ordinal = unitOrdinal,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.EbookSection(
                                chapter = chapterTitle,
                                headingPath = emptyList(),
                                spineIndex = spineIndex,
                            ),
                            extractedText = normalised.extracted,
                            searchText = normalised.search,
                            artifactRelativePath = artifactPath,
                            artifactSha256 = artifactSha,
                            meanConfidence = confidence,
                        ),
                    ),
                )
            }
        }

        if (abortCode != null) {
            // The refusal was emitted inside the unit's permit above; a refused document is not a finished
            // one, and the sections that were delivered before the refusal stay delivered.
            return
        }
        input.boundary.unit {
            emit(
                ExtractionEvent.Finished(
                    metadata = bookMetadata(
                        book = book,
                        pictures = pictures,
                        picturesNotRead = picturesNotRead,
                        footnotes = footnotes,
                        ocrPages = ocrPages,
                        confidences = confidences,
                    ),
                    totalUnits = total,
                ),
            )
        }
    }

    private fun managedSize(input: ExtractionInput): Long? = try {
        Files.size(input.managedPath)
    } catch (unreadable: IOException) {
        null
    }

    private fun bookMetadata(
        book: EpubPackage.Book,
        pictures: Int,
        picturesNotRead: Int,
        footnotes: Int,
        ocrPages: Int,
        confidences: List<Double>,
    ): Map<String, String> = buildMap {
        put(EXTRACTOR_METADATA, EXTRACTOR_NAME)
        putAll(book.metadata)
        put(SPINE_ITEMS_METADATA, book.spine.size.toString())
        put(IMAGES_METADATA, pictures.toString())
        put(FOOTNOTES_METADATA, footnotes.toString())
        if (picturesNotRead > 0) put(PICTURES_NOT_READ_METADATA, picturesNotRead.toString())
        if (ocrPages > 0) {
            put(ImageExtractor.OCR_PAGES_METADATA, ocrPages.toString())
            confidences.takeIf { it.isNotEmpty() }?.let { values ->
                put(
                    ImageExtractor.OCR_MEAN_CONFIDENCE_METADATA,
                    String.format(Locale.ROOT, "%.3f", values.average()),
                )
            }
        }
    }

    /**
     * How many footnotes the chapter carries.
     *
     * The test is on the element's own attributes rather than on a selector, because the attribute that
     * marks a note is namespaced (`epub:type`) and a selector for it depends on how the parser kept the
     * namespace. A note is an `<aside>` — or a `<div>` — whose type or role says so; the note's *text* stays
     * in the section that carries it either way, which is what makes it citable.
     */
    private fun footnoteCount(chapter: Document): Int = chapter.select("aside, div").count { element ->
        val declared = listOf(element.attr(EPUB_TYPE_ATTRIBUTE), element.attr(ROLE_ATTRIBUTE))
            .flatMap { value -> value.split(' ', '\t') }
            .filter { value -> value.isNotEmpty() }
        declared.any { value -> value in FOOTNOTE_TYPES } || element.hasClass(FOOTNOTE_CLASS)
    }

    /** The picture references a chapter carries, in document order. */
    private fun imageHrefs(chapter: Document): List<String> = chapter.select("img[src]")
        .mapNotNull { image -> image.attr("src").trim().takeIf { it.isNotEmpty() } }

    /**
     * Writes one picture out of the container and returns the file, or `null` when it cannot be read.
     *
     * A picture the container does not hold is not a reason to refuse the book: the markup and the manifest
     * disagree in real books, and the chapter's text is still the chapter's text. What must not happen is a
     * picture whose bytes are never materialised, because the tool can only read a file.
     */
    private fun resolveImage(
        archive: SafeArchive,
        chapterPath: String,
        href: String,
        workDirectory: Path,
    ): Path? {
        val path = SafeArchive.resolveHref(chapterPath, href) ?: return null
        val entry = archive.entry(path) ?: return null
        return try {
            archive.extract(entry, workDirectory)
        } catch (refused: ArchiveRefusedException) {
            null
        } catch (failure: IOException) {
            null
        }
    }

    companion object {

        /** The name this extractor is known by in a document's metadata. */
        internal const val EXTRACTOR_NAME: String = "epub"

        /** How many chapters the spine lists. */
        const val SPINE_ITEMS_METADATA: String = "spine_items"

        /** How many pictures the book references, read or not. */
        const val IMAGES_METADATA: String = "images"

        /** How many footnotes the book carries. */
        const val FOOTNOTES_METADATA: String = "footnotes"

        /**
         * How many pictures no reading looked at.
         *
         * That is the plates of a chapter that was read as text, plus the pictures past the per-chapter
         * bound in a chapter that had to be handed to a tool: the number that says [IMAGES_METADATA] is a
         * count of references, not of readings.
         */
        const val PICTURES_NOT_READ_METADATA: String = "pictures_not_read"

        /** The attribute that declares what an element is in an EPUB. */
        private const val EPUB_TYPE_ATTRIBUTE: String = "epub:type"

        /** The attribute that declares what an element is in ARIA, which some books use instead. */
        private const val ROLE_ATTRIBUTE: String = "role"

        /** The values that mark an aside as a note rather than as body text. */
        private val FOOTNOTE_TYPES: Set<String> = setOf(
            "footnote",
            "endnote",
            "rearnote",
            "doc-footnote",
            "doc-endnote",
        )

        /** The class convention some books use instead of a type. */
        private const val FOOTNOTE_CLASS: String = "footnote"

        /** The prefix of the private directory one attempt works in. */
        internal const val WORK_DIRECTORY_PREFIX: String = "infoscry-ebook-pages-"

        /** The suffix a picture without a recognisable extension is written under. */
        internal const val DEFAULT_PICTURE_EXTENSION: String = "bin"
    }
}

/**
 * The package document and the navigation document, read into what a chapter walk needs.
 *
 * Both documents are optional in the ways the format allows and mandatory in the ways it does not: a
 * container without a `META-INF/container.xml` is not an EPUB, and a package document without a spine is
 * not a book. Everything here is read through [SafeXml], so a package document that declares an entity
 * cannot pull a file into the parse, and every href is resolved through [SafeArchive.resolveHref], so a
 * chapter cannot be named outside the container.
 */
internal object EpubPackage {

    /** One manifest entry: what the package says a chapter is and where it lives. */
    data class Item(val id: String, val path: String)

    /** What a book says about itself and how it is read. */
    data class Book(
        val metadata: Map<String, String>,
        val spine: List<Item>,
        val titles: Map<String, String>,
    )

    /** Reads the package document of [archive], or refuses the book. */
    fun read(archive: SafeArchive): Book {
        refuseProtectedBook(archive)
        val opfPath = containerRootFile(archive)
        val opfEntry = archive.entry(opfPath) ?: throw unreadable("the package document is not in the book")
        val opf = SafeXml.parse(readBytes(archive, opfEntry))
        val manifest = manifest(opf, opfPath)
        val spine = spine(opf, manifest)
        if (spine.isEmpty()) throw unreadable("the package document names no chapters")
        return Book(
            metadata = metadata(opf),
            spine = spine,
            titles = titles(archive, opf, opfPath, manifest),
        )
    }

    /**
     * Refuses a book whose content is protected, and lets a book through whose only encrypted entries are
     * obfuscated fonts.
     *
     * The distinction is not pedantry: `META-INF/encryption.xml` is how an EPUB declares an encrypted
     * resource, and a great many unprotected books use it for nothing but the standard font-obfuscation
     * algorithm, whose whole purpose is to satisfy a font licence rather than to keep a reader out. A
     * reader that refused every book with that file would refuse books whose text is right there, so what is
     * refused is a declared encryption whose algorithm is anything other than the font one — which is what
     * DRM looks like from here.
     */
    private fun refuseProtectedBook(archive: SafeArchive) {
        val entry = archive.entry(ENCRYPTION_PATH) ?: return
        val document = try {
            SafeXml.parse(readBytes(archive, entry))
        } catch (refused: ArchiveRefusedException) {
            // A declaration this reader cannot read is a declaration it cannot judge, and refusing an
            // unreadable encryption declaration fails closed rather than assuming the book is open.
            throw ArchiveRefusedException(
                ENCRYPTED_DOCUMENT_CODE,
                "the book declares an encryption this reader cannot read",
            )
        }
        val algorithms = document.elementsNamed(ENCRYPTION_METHOD_NAME)
            .map { element -> element.getAttribute(ALGORITHM_ATTRIBUTE).trim() }
            .filter { it.isNotEmpty() }
        if (algorithms.isEmpty()) return
        if (algorithms.all { algorithm -> algorithm in BENIGN_ALGORITHMS }) return
        throw ArchiveRefusedException(
            ENCRYPTED_DOCUMENT_CODE,
            "the book's content is encrypted, and nothing here decrypts a book",
        )
    }

    /** The package document's path, from the container's own rootfile declaration. */
    private fun containerRootFile(archive: SafeArchive): String {
        val entry = archive.entry(CONTAINER_PATH)
            ?: throw unreadable("the container does not hold $CONTAINER_PATH")
        val document = SafeXml.parse(readBytes(archive, entry))
        val rootfile = document.elementsNamed(ROOTFILE_NAME).firstOrNull()
            ?: throw unreadable("the container names no rootfile")
        val fullPath = rootfile.getAttribute(FULL_PATH_ATTRIBUTE).trim()
        if (fullPath.isEmpty()) throw unreadable("the container's rootfile names no path")
        return SafeArchive.resolveHref("", fullPath)
            ?: throw unreadable("the container's rootfile names a path outside the book")
    }

    private fun manifest(opf: org.w3c.dom.Document, opfPath: String): Map<String, Item> = buildMap {
        opf.elementsNamed(ITEM_NAME).forEach { item ->
            val id = item.getAttribute("id").trim()
            val href = item.getAttribute("href").trim()
            if (id.isEmpty() || href.isEmpty()) return@forEach
            val path = SafeArchive.resolveHref(opfPath, href) ?: return@forEach
            put(id, Item(id = id, path = path))
        }
    }

    private fun spine(opf: org.w3c.dom.Document, manifest: Map<String, Item>): List<Item> =
        opf.elementsNamed(ITEMREF_NAME).mapNotNull { itemref ->
            manifest[itemref.getAttribute("idref").trim()]
        }

    /**
     * What the package says the book is.
     *
     * Only the facts a collection can filter on and a citation can show are kept, and each is taken from
     * the first element that carries it: a book with two titles has one that is the title, and choosing the
     * last would be as arbitrary as choosing the first.
     */
    private fun metadata(opf: org.w3c.dom.Document): Map<String, String> = buildMap {
        fun first(name: String): String? = opf.elementsNamed(name)
            .firstNotNullOfOrNull { element -> element.textContent?.trim()?.takeIf { it.isNotEmpty() } }

        first(TITLE_NAME)?.let { put("title", it) }
        first(CREATOR_NAME)?.let { put("author", it) }
        first(PUBLISHER_NAME)?.let { put("publisher", it) }
        first(LANGUAGE_NAME)?.let { put("language", it) }
        first(IDENTIFIER_NAME)?.let { put("identifier", it) }
    }

    /**
     * The chapter titles a reader would click, keyed by the chapter they name.
     *
     * The navigation document is the book's own answer to "what is this chapter called", and it is optional
     * in both directions: an EPUB 3 book has a navigation document, an EPUB 2 book has an NCX, and a book
     * may have neither. A chapter without a title is cited by its heading path alone rather than by an
     * invented name.
     */
    private fun titles(
        archive: SafeArchive,
        opf: org.w3c.dom.Document,
        opfPath: String,
        manifest: Map<String, Item>,
    ): Map<String, String> {
        val navPath = navigationPath(opf, manifest)
        if (navPath != null) {
            val entry = archive.entry(navPath) ?: return emptyMap()
            val nav = try {
                Jsoup.parse(readBytes(archive, entry).toString(Charsets.UTF_8))
            } catch (failure: IOException) {
                return emptyMap()
            }
            val titles = navTitles(nav, navPath)
            if (titles.isNotEmpty()) return titles
        }
        val ncxPath = ncxPath(opf, opfPath, manifest) ?: return emptyMap()
        val entry = archive.entry(ncxPath) ?: return emptyMap()
        return try {
            ncxTitles(SafeXml.parse(readBytes(archive, entry)), ncxPath)
        } catch (refused: ArchiveRefusedException) {
            // A navigation document that cannot be read costs the book its chapter titles and nothing else:
            // the chapters, their headings and their text are in the package and the markup.
            emptyMap()
        }
    }

    private fun navigationPath(opf: org.w3c.dom.Document, manifest: Map<String, Item>): String? =
        opf.elementsNamed(ITEM_NAME)
            .firstOrNull { item ->
                item.getAttribute(PROPERTIES_ATTRIBUTE).split(WHITESPACE)
                    .any { property -> property == NAV_PROPERTY }
            }
            ?.let { item -> manifest[item.getAttribute("id").trim()]?.path }

    private fun ncxPath(opf: org.w3c.dom.Document, opfPath: String, manifest: Map<String, Item>): String? {
        val tocId = opf.elementsNamed(SPINE_NAME).firstOrNull()
            ?.getAttribute(TOC_ATTRIBUTE)
            ?.trim()
            .orEmpty()
        val fromSpine = manifest[tocId]?.path
        if (fromSpine != null) return fromSpine
        // A package that never named its NCX still usually has one, and a book whose chapters are cited by
        // file name only because of a missing attribute would be worse than one guessed from a media type.
        return manifest.values.firstOrNull { item -> item.path.endsWith(NCX_EXTENSION, ignoreCase = true) }?.path
    }

    /** EPUB 3 navigation: the anchors inside the table of contents. */
    private fun navTitles(nav: Document, navPath: String): Map<String, String> = buildMap {
        nav.select("nav a[href]").forEach { anchor ->
            val title = anchor.text().trim()
            if (title.isEmpty()) return@forEach
            val path = SafeArchive.resolveHref(navPath, anchor.attr("href")) ?: return@forEach
            putIfAbsent(path, title)
        }
    }

    /** EPUB 2 navigation: the label of each `navPoint`, against the document it points at. */
    private fun ncxTitles(ncx: org.w3c.dom.Document, ncxPath: String): Map<String, String> = buildMap {
        ncx.elementsNamed(NAVPOINT_NAME).forEach { navPoint ->
            val label = navPoint.elementsNamed(TEXT_NAME).firstOrNull()?.textContent?.trim()
            val src = navPoint.elementsNamed(CONTENT_NAME).firstOrNull()
                ?.getAttribute(SRC_ATTRIBUTE)
                ?.trim()
                .orEmpty()
            if (label.isNullOrEmpty() || src.isEmpty()) return@forEach
            val path = SafeArchive.resolveHref(ncxPath, src) ?: return@forEach
            putIfAbsent(path, label)
        }
    }

    private fun readBytes(archive: SafeArchive, entry: ArchiveEntry): ByteArray = try {
        archive.readBytes(entry)
    } catch (refused: ArchiveRefusedException) {
        throw refused
    } catch (failure: IOException) {
        throw unreadable("an entry of the book could not be read")
    }

    private fun unreadable(message: String): ArchiveRefusedException =
        ArchiveRefusedException(DOCUMENT_UNREADABLE_CODE, message)

    private const val CONTAINER_PATH: String = "META-INF/container.xml"
    private const val ENCRYPTION_PATH: String = "META-INF/encryption.xml"
    private const val ENCRYPTION_METHOD_NAME: String = "EncryptionMethod"
    private const val ALGORITHM_ATTRIBUTE: String = "Algorithm"

    /**
     * The algorithms that are not DRM.
     *
     * The first is the identifier the EPUB specification gives for font obfuscation, and it is the one real
     * books actually carry; the second is the older spelling some tools wrote. Everything else — Adobe's
     * ADEPT identifiers, a publisher's own scheme — is treated as protection.
     */
    private val BENIGN_ALGORITHMS: Set<String> = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
    )
    private const val ROOTFILE_NAME: String = "rootfile"
    private const val FULL_PATH_ATTRIBUTE: String = "full-path"
    private const val ITEM_NAME: String = "item"
    private const val ITEMREF_NAME: String = "itemref"
    private const val SPINE_NAME: String = "spine"
    private const val NAVPOINT_NAME: String = "navPoint"
    private const val CONTENT_NAME: String = "content"
    private const val TEXT_NAME: String = "text"
    private const val SRC_ATTRIBUTE: String = "src"
    private const val TOC_ATTRIBUTE: String = "toc"
    private const val PROPERTIES_ATTRIBUTE: String = "properties"
    private const val NAV_PROPERTY: String = "nav"
    private const val NCX_EXTENSION: String = ".ncx"
    private const val TITLE_NAME: String = "title"
    private const val CREATOR_NAME: String = "creator"
    private const val PUBLISHER_NAME: String = "publisher"
    private const val LANGUAGE_NAME: String = "language"
    private const val IDENTIFIER_NAME: String = "identifier"
    private const val WHITESPACE: String = "\\s+"

}

/**
 * FictionBook, read as nested sections.
 *
 * FB2 is the one e-book format here whose structure is the markup itself: no package document, no manifest,
 * no spine. A `<body>` holds `<section>` elements that nest, each naming itself with a `<title>`, so the
 * heading path is not derived from markup that happens to look like a heading — it *is* the structure, and a
 * citation can point at exactly the section a reader would open.
 *
 * A body with a `name` (the usual spelling of a footnote or commentary body) is read as its own part of the
 * book rather than skipped: its text is text somebody wrote, and dropping it would make the notes
 * unsearchable and uncitable.
 */
class Fb2Extractor(
    private val maxDocumentBytes: Long = MAX_EBOOK_DOCUMENT_BYTES,
) : DocumentExtractor {

    init {
        require(maxDocumentBytes >= 1) {
            "an e-book memory bound must allow at least one byte, was $maxDocumentBytes"
        }
    }

    override val supportedMediaTypes: Set<String> = setOf(FB2_MEDIA_TYPE, FBZ_MEDIA_TYPE)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val size = try {
            Files.size(input.managedPath)
        } catch (unreadable: IOException) {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
            return@flow
        }
        if (size > maxDocumentBytes) {
            refuseDocument(input, DOCUMENT_TOO_LARGE_KEY, DOCUMENT_TOO_LARGE_CODE)
            return@flow
        }

        // A zipped FictionBook is the same document in a container, so the two are told apart by the
        // container's own signature rather than by the name the file was published under: an `.fbz` whose
        // bytes are already markup is markup, and an `.fb2` that arrived zipped is still a book.
        val markup = if (isZipContainer(input.managedPath)) {
            try {
                readZipped(input)
            } catch (refused: ArchiveRefusedException) {
                refuseDocument(input, DOCUMENT_REFUSED_KEY, refused.code)
                return@flow
            }
        } else {
            String(Files.readAllBytes(input.managedPath), Charsets.UTF_8)
        } ?: run {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
            return@flow
        }

        val document = try {
            SafeXml.parse(markup.toByteArray(Charsets.UTF_8))
        } catch (refused: ArchiveRefusedException) {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, refused.code)
            return@flow
        }

        val bodies = document.elementsNamed(BODY_NAME)
        if (bodies.isEmpty()) {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
            return@flow
        }

        var ordinal = 0
        var total = 0
        bodies.forEachIndexed { spineIndex, body ->
            val chapter = body.getAttribute(NAME_ATTRIBUTE).trim().takeIf { it.isNotEmpty() }
            Fb2Sections.sectionsOf(body).forEachIndexed { sectionIndex, section ->
                val key = ebookSectionKey(spineIndex, sectionIndex)
                val unitOrdinal = ordinal
                ordinal++
                total++
                if (input.isCommitted(key)) return@forEachIndexed
                input.boundary.unit {
                    val normalised = TextNormalizer.normalize(section.text)
                    emit(
                        ExtractionEvent.UnitReady(
                            key = key,
                            ordinal = unitOrdinal,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.EbookSection(
                                    chapter = chapter,
                                    headingPath = section.headingPath,
                                    spineIndex = spineIndex,
                                ),
                                extractedText = normalised.extracted,
                                searchText = normalised.search,
                            ),
                        ),
                    )
                }
            }
        }

        input.boundary.unit {
            emit(
                ExtractionEvent.Finished(
                    metadata = metadataOf(document, bodies.size, total),
                    totalUnits = total,
                ),
            )
        }
    }

    /**
     * The markup inside an `.fbz`, which is a zip whose single entry is the book.
     *
     * The container is read through [SafeArchive] like every other zip this pipeline touches: an `.fbz`
     * whose entries walk out of the directory they would be extracted into is refused with the rest.
     */
    private fun readZipped(input: ExtractionInput): String? {
        val archive = SafeArchive.open(input.managedPath)
        return archive.use { opened ->
            val entry = opened.entries().firstOrNull { it.path.endsWith(FB2_MEDIA_TYPE_EXTENSION, true) }
                ?: opened.entries().firstOrNull()
                ?: return null
            opened.readText(entry)
        }
    }

    /** Whether the file begins with a zip's local-header signature. */
    private fun isZipContainer(path: Path): Boolean = try {
        Files.newInputStream(path).use { stream ->
            val signature = stream.readNBytes(ZIP_SIGNATURE.size)
            signature.size == ZIP_SIGNATURE.size && signature.indices.all { index ->
                signature[index] == ZIP_SIGNATURE[index]
            }
        }
    } catch (unreadable: IOException) {
        false
    }

    private fun metadataOf(document: org.w3c.dom.Document, bodies: Int, sections: Int): Map<String, String> =
        buildMap {
            put(EXTRACTOR_METADATA, EXTRACTOR_NAME)
            put(BODIES_METADATA, bodies.toString())
            put(SECTIONS_METADATA, sections.toString())
            fun first(name: String): String? = document.elementsNamed(name)
                .firstNotNullOfOrNull { element -> element.textContent?.trim()?.takeIf { it.isNotEmpty() } }

            first(BOOK_TITLE_NAME)?.let { put("title", it) }
            first(LANG_NAME)?.let { put("language", it) }
            authorOf(document)?.let { put("author", it) }
            first(ID_NAME)?.let { put("identifier", it) }
        }

    /** The author's name, joined from the name parts FB2 splits it into. */
    private fun authorOf(document: org.w3c.dom.Document): String? = document.elementsNamed(AUTHOR_NAME)
        .firstNotNullOfOrNull { author ->
            val parts = listOf(FIRST_NAME_NAME, MIDDLE_NAME_NAME, LAST_NAME_NAME)
                .flatMap { name -> author.elementsNamed(name) }
                .map { element -> element.textContent.trim() }
                .filter { it.isNotEmpty() }
            parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

    companion object {

        /** The name this extractor is known by in a document's metadata. */
        internal const val EXTRACTOR_NAME: String = "fb2"

        /** How many bodies the book holds: the main text and any named part such as the notes. */
        const val BODIES_METADATA: String = "bodies"

        /** How many sections became citable units. */
        const val SECTIONS_METADATA: String = "sections"

        private const val BODY_NAME: String = "body"
        private const val NAME_ATTRIBUTE: String = "name"
        private const val BOOK_TITLE_NAME: String = "book-title"
        private const val LANG_NAME: String = "lang"
        private const val ID_NAME: String = "id"
        private const val AUTHOR_NAME: String = "author"
        private const val FIRST_NAME_NAME: String = "first-name"
        private const val MIDDLE_NAME_NAME: String = "middle-name"
        private const val LAST_NAME_NAME: String = "last-name"
        private const val FB2_MEDIA_TYPE_EXTENSION: String = ".fb2"

        /** The bytes every zip starts with, which is what tells a zipped book from a markup one. */
        private val ZIP_SIGNATURE: ByteArray = byteArrayOf(0x50, 0x4B)
    }
}

/** The sections of one FB2 body, in document order, with the heading path each one sits under. */
private object Fb2Sections {

    data class Section(val headingPath: List<String>, val text: String)

    fun sectionsOf(body: org.w3c.dom.Element): List<Section> {
        val sections = mutableListOf<Section>()
        collect(body, emptyList(), sections, isBody = true)
        return sections
    }

    /**
     * Walks the body's sections.
     *
     * A section's own text is what it holds outside the sections nested inside it, and its heading path is
     * the titles of the sections above it plus its own. That is the same shape a reader sees: a subsection
     * is cited as "kapitel › avsnitt", and the chapter's opening paragraphs belong to the chapter.
     */
    private fun collect(
        element: org.w3c.dom.Element,
        path: List<String>,
        sections: MutableList<Section>,
        isBody: Boolean,
    ) {
        val children = childElements(element)
        val sectionChildren = children.filter { it.nodeName.substringAfterLast(':') == SECTION_NAME }
        if (isBody) {
            sectionChildren.forEach { section -> collect(section, path, sections, isBody = false) }
            return
        }
        val title = titleOf(element)
        val headingPath = if (title == null) path else path + title
        val ownText = bodyText(element)
        if (ownText.isNotEmpty() || title != null) {
            val text = listOfNotNull(title, ownText.takeIf { it.isNotEmpty() }).joinToString("\n")
            sections += Section(headingPath = headingPath, text = text)
        }
        sectionChildren.forEach { section -> collect(section, headingPath, sections, isBody = false) }
    }

    /** A section's title text: its own `<title>` elements, which may hold several paragraphs. */
    private fun titleOf(section: org.w3c.dom.Element): String? = childElements(section)
        .filter { it.nodeName.substringAfterLast(':') == TITLE_NAME }
        .flatMap { title -> childElements(title) }
        .map { paragraph -> paragraph.textContent.trim() }
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")

    /**
     * The text a section holds outside the sections nested in it.
     *
     * Only paragraphs and the few inline containers a book puts prose in are read, so a `<title>` is not
     * counted twice and an `<image>` contributes nothing to a text unit.
     */
    private fun bodyText(section: org.w3c.dom.Element): String = childElements(section)
        .filter { element ->
            element.nodeName.substringAfterLast(':') == PARAGRAPH_NAME ||
                element.nodeName.substringAfterLast(':') == SUBTITLE_NAME
        }
        .map { paragraph -> paragraph.textContent.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    private fun childElements(element: org.w3c.dom.Element): List<org.w3c.dom.Element> {
        val children = element.childNodes
        return (0 until children.length).mapNotNull { index ->
            children.item(index).takeIf { node -> node.nodeType == Node.ELEMENT_NODE } as? org.w3c.dom.Element
        }
    }

    private const val SECTION_NAME: String = "section"
    private const val TITLE_NAME: String = "title"
    private const val PARAGRAPH_NAME: String = "p"
    private const val SUBTITLE_NAME: String = "subtitle"
}

/**
 * Converts an e-book only an external tool can read into an EPUB this pipeline can.
 *
 * Calibre is optional, and that shapes everything here: a machine without it must be able to import every
 * other format, so a missing converter is a document-level refusal with the code that names the thing to
 * install rather than a crash or a stack trace in a job.
 *
 * The converted book is written under the **fingerprint** directory of the artifact root, exactly as the
 * OCR artifacts are, which is what makes it reusable without being wrong: two extractions of the same bytes
 * under the same settings cannot see each other's conversion, and a conversion whose tool version changed
 * lands in a new directory because the fingerprint covers that version. A conversion that is already there
 * is therefore the answer to this attempt too, and re-running it would only spend a child process on a
 * question that has been answered.
 *
 * Only a *finished* conversion is ever placed there. The converter is handed a path inside this attempt's
 * private directory and its output is moved into the artifact root afterwards, so a run that is stopped on
 * timeout, cancelled, or that dies mid-write leaves nothing the next attempt could mistake for an answer —
 * the same reason the conversion log is written through a `.part` file. A partial book at the target path
 * would be worse than a missing one: the reuse check would accept it as the answer, the reader would refuse
 * it, and the document would stay unimportable after whatever caused the truncation was fixed.
 *
 * The original is the input and is never touched: the converted file is a derived artifact, and the citation
 * still points at the book the person imported.
 */
class CalibreConverter(
    private val executable: String = DEFAULT_EXECUTABLE,
    private val timeout: Duration = BOOK_TIMEOUT,
    private val maxCapturedOutputBytes: Int = MAX_CONVERSION_LOG_BYTES,
) {

    init {
        require(maxCapturedOutputBytes >= 1) {
            "a conversion log bound must allow at least one byte, was $maxCapturedOutputBytes"
        }
    }

    /**
     * The EPUB for [input], converting it if this fingerprint does not have one yet.
     *
     * Called from inside a unit boundary, because it writes into the document's artifact root: the file it
     * produces is an artifact of this attempt, and it belongs to the same permit as the unit that will
     * describe the reading of it.
     */
    suspend fun convert(input: ExtractionInput): Path {
        val target = input.artifactRoot.resolve(relativeArtifactPath(input.fingerprint))
        if (isReusable(target)) return target

        // The attempt's private directory is created inside the artifact root, so the book is published with
        // a same-filesystem rename. A work directory under the system temp directory can sit on another
        // filesystem, where the publish degrades into a copy and a process killed mid-copy leaves a
        // truncated book at exactly the path [isReusable] accepts.
        val workDirectory = Files.createTempDirectory(input.artifactRoot, WORK_DIRECTORY_PREFIX)
        try {
            // The converter writes into this attempt's own directory rather than at the target path. The
            // target path is what a later attempt looks at for an answer, so a process that is stopped on
            // timeout or dies mid-write must not be able to leave a truncated book there; only a conversion
            // that succeeded and produced bytes is moved in.
            val converted = workDirectory.resolve(ARTIFACT_NAME)
            val outcome = try {
                ExternalProcess.run(
                    command = listOf(executable, input.managedPath.toString(), converted.toString()),
                    timeout = timeout,
                    cwd = workDirectory,
                    maxCapturedStdoutBytes = maxCapturedOutputBytes,
                )
            } catch (missing: ExternalToolMissingException) {
                throw ConversionRefusedException(
                    NEEDS_CALIBRE_CODE,
                    "'$executable' is not installed or cannot be run, so this e-book cannot be read: " +
                        installRemedy(),
                )
            }

            writeLog(input, target, outcome.stdout, outcome.stderr)

            if (!outcome.succeeded) throw failure(outcome)
            if (!isReusable(converted)) {
                // A tool that reports success and writes nothing has not converted anything, and handing the
                // missing file to the EPUB reader would report the failure as an unreadable book.
                throw ConversionRefusedException(
                    DOCUMENT_UNREADABLE_CODE,
                    "the converter reported success without writing a book",
                )
            }
            publish(converted, target)
            return target
        } finally {
            workDirectory.toFile().deleteRecursively()
        }
    }

    /**
     * Whether [path] holds a book this attempt can use as the answer.
     *
     * A path that cannot be examined is not an answer either: the file can be removed between the check and
     * the read, and reporting that as "no conversion yet" leaves the attempt free to convert again.
     */
    private fun isReusable(path: Path): Boolean = try {
        Files.isRegularFile(path) && Files.size(path) > 0
    } catch (unreadable: IOException) {
        false
    }

    /**
     * Publishes a finished conversion at the path a later attempt reads as its answer.
     *
     * The move is required to be atomic. A copy-based fallback would leave a partial book at exactly the
     * path [isReusable] accepts if the process dies mid-copy, and every later attempt would then derive its
     * failure from that stub instead of converting again; refusing and clearing the destination keeps the
     * answer either absent or whole.
     */
    private fun publish(converted: Path, target: Path) {
        try {
            Files.createDirectories(target.parent)
            Files.move(converted, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
            discard(target)
            throw ConversionRefusedException(
                DOCUMENT_UNREADABLE_CODE,
                "the artifact directory could not publish the converted book in place ($failure); " +
                    "check that the data directory's filesystem supports atomic renames",
            )
        }
    }

    /**
     * Removes a destination that must not be mistaken for a finished conversion.
     *
     * A destination that cannot be removed is not reusable either, because [isReusable] examines it rather
     * than trusting it, so a failed attempt at cleanup cannot turn a stub into an answer.
     */
    private fun discard(target: Path) {
        try {
            Files.deleteIfExists(target)
        } catch (undeletable: IOException) {
            // Left for [isReusable], which treats an unexaminable path as no answer at all.
        }
    }

    /**
     * What a failed conversion means.
     *
     * A converter's own words are the only signal there is for protected material: the format's encryption
     * is not something this pipeline reads, so the tool's message is what says whether the book needs
     * unlocking or is simply broken. The markers are deliberately few and lower-cased, and anything that
     * matches none of them is reported as a book that could not be read rather than guessed at.
     */
    private fun failure(outcome: ProcessOutcome): ConversionRefusedException {
        val words = "${outcome.stdout}\n${outcome.stderr}".lowercase(Locale.ROOT)
        val protectedWords = listOf("drm", "digital editions", "encrypted", "restricted", "protected")
        if (protectedWords.any { marker -> words.contains(marker) }) {
            return ConversionRefusedException(
                ENCRYPTED_DOCUMENT_CODE,
                "the converter reports this book as protected, and nothing here decrypts one",
            )
        }
        return ConversionRefusedException(
            DOCUMENT_UNREADABLE_CODE,
            "the converter could not read this book",
        )
    }

    /** Writes the tool's own output beside the converted book, bounded. */
    private fun writeLog(input: ExtractionInput, converted: Path, stdout: String, stderr: String) {
        val log = converted.resolveSibling(CONVERSION_LOG_NAME)
        val text = buildString {
            if (stdout.isNotBlank()) appendLine(stdout.trimEnd())
            if (stderr.isNotBlank()) appendLine(stderr.trimEnd())
        }
        val bounded = text.toByteArray(Charsets.UTF_8)
            .take(maxCapturedOutputBytes)
            .toByteArray()
            .toString(Charsets.UTF_8)
        val partial = log.resolveSibling("${log.fileName}.part")
        try {
            // The log belongs beside the converted book, so its directory is created here — a conversion
            // that got as far as a tool call has something to say and keeps saying it even when it failed.
            Files.createDirectories(log.parent)
            Files.write(partial, bounded.toByteArray(Charsets.UTF_8))
            try {
                Files.move(partial, log, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(partial, log, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    companion object {

        /** The tool's name as it is found on the PATH. */
        const val DEFAULT_EXECUTABLE: String = "ebook-convert"

        /**
         * One book is given this long before the converter is stopped.
         *
         * Converting a book is not reading a page: a large one with many pictures is minutes of work, and
         * the bound exists to stop a hung process rather than to rush a slow one.
         */
        val BOOK_TIMEOUT: Duration = Duration.ofMinutes(15)

        /**
         * The code a missing converter fails under.
         *
         * It is the word the design uses for a tool this pipeline can work without: the document is not
         * broken and the collection is not broken, one thing has to be installed for this format to be
         * read.
         */
        const val NEEDS_CALIBRE_CODE: String = "NEEDS_TOOL"

        /** Where the converted book lives under a fingerprint. */
        internal const val ARTIFACT_DIRECTORY: String = "ebook"

        /** What the converted book is called. */
        internal const val ARTIFACT_NAME: String = "normalized.epub"

        /** What the converter's own output is called beside it. */
        internal const val CONVERSION_LOG_NAME: String = "calibre-conversion.log"

        /**
         * How much of the converter's output is kept.
         *
         * A converter narrates its work and a broken one can repeat itself forever; the log is diagnostics
         * rather than evidence, so it is kept small on purpose.
         */
        const val MAX_CONVERSION_LOG_BYTES: Int = 1024 * 1024

        /** The prefix of the private directory a conversion runs in. */
        internal const val WORK_DIRECTORY_PREFIX: String = "infoscry-calibre-"

        /** The converted book's path, relative to the document's artifact root. */
        internal fun relativeArtifactPath(fingerprint: ExtractionFingerprint): String = listOf(
            fingerprint.value,
            ARTIFACT_DIRECTORY,
            ARTIFACT_NAME,
        ).joinToString("/")

        /**
         * What to do about a missing converter, in the words of the platform's own package manager.
         *
         * A person who sees `NEEDS_TOOL` has to be told which tool and how to get it, or the code is a
         * puzzle rather than an instruction.
         */
        fun installRemedy(): String {
            val platform = System.getProperty("os.name").lowercase(Locale.ROOT)
            return when {
                platform.contains("mac") -> "install it with 'brew install --cask calibre'"
                platform.contains("linux") ->
                    "install it with your package manager, for example 'apt-get install calibre'"
                else -> "install calibre so its ebook-convert command is on the PATH"
            }
        }
    }
}

/**
 * A format the converter cannot read, refused with the reason it gave.
 *
 * [code] is one of the shared codes: protected material is [ENCRYPTED_DOCUMENT_CODE] and anything else the
 * converter refuses is [DOCUMENT_UNREADABLE_CODE], so the queue and the CLI say the same words they say for
 * the same conditions in every other reader.
 */
class ConversionRefusedException(val code: String, message: String) : IOException(message)

/**
 * A Kindle or legacy e-book, converted on the way in and then read as the EPUB it became.
 *
 * The conversion is not a format of its own: what a Calibre-backed file becomes is an EPUB, and the reader
 * for an EPUB already knows how to cite a section, find its footnote, and hand a picture-only chapter to
 * OCR. Delegating keeps that one reader, one set of keys, and one set of locators rather than a second
 * implementation that would drift from it.
 *
 * Two things the delegation must preserve, and does:
 *
 * - **The fingerprint.** The converted book is read under the outer attempt's fingerprint, so its pictures'
 *   OCR artifacts land beside the artifacts of every other extraction of the same bytes rather than in a
 *   directory keyed by the conversion.
 * - **The permit.** The flow is collected inline, so the sections the EPUB reader emits are emitted inside
 *   the permits it takes from this attempt's boundary — the conversion itself is the only extra permit,
 *   taken because it writes the converted file.
 */
class CalibreBackedEbookExtractor(
    private val converter: CalibreConverter,
    private val epub: EpubExtractor,
    private val maxDocumentBytes: Long = MAX_EBOOK_DOCUMENT_BYTES,
) : DocumentExtractor {

    init {
        require(maxDocumentBytes >= 1) {
            "an e-book memory bound must allow at least one byte, was $maxDocumentBytes"
        }
    }

    override val supportedMediaTypes: Set<String> = setOf(
        MOBIPOCKET_MEDIA_TYPE,
        LEGACY_EBOOK_MEDIA_TYPE,
    )

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val size = try {
            Files.size(input.managedPath)
        } catch (unreadable: IOException) {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
            return@flow
        }
        if (size > maxDocumentBytes) {
            refuseDocument(input, DOCUMENT_TOO_LARGE_KEY, DOCUMENT_TOO_LARGE_CODE)
            return@flow
        }

        // A refusal is emitted inside the unit's permit, and a converted book is what the absence of one
        // looks like: the two cannot both happen, so the null return is the whole signal — a refused
        // document is not a finished one, and nothing is read from a book that was never produced.
        var converted: Path? = null
        input.boundary.unit {
            converted = try {
                converter.convert(input)
            } catch (refused: ConversionRefusedException) {
                emitDocumentRefusal(input, DOCUMENT_REFUSED_KEY, refused.code)
                null
            } catch (failure: IOException) {
                emitDocumentRefusal(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
                null
            }
        }
        val book = converted ?: return@flow

        emitAll(epub.extract(input.copy(managedPath = book)))
    }
}
