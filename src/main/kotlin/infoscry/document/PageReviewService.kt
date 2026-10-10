package infoscry.document

import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageRoot
import infoscry.extract.PageCounter
import infoscry.extract.DefaultPageCounter
import infoscry.extract.PdfExtractor
import infoscry.extract.PdfPageRenderer
import infoscry.extract.PngPageRenderer
import infoscry.library.ManagedLibrary
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.PageImage
import infoscry.ocr.PageImageRenderer
import infoscry.ocr.OcrQualityScorer
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.OcrOperationStore
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageText
import infoscry.storage.RevisionState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils

@Serializable
data class PageReviewResponse(
    val documentId: String,
    val unitId: String?,
    val revisionId: String?,
    val ordinal: Int,
    val documentPages: Int?,
    val previousOrdinal: Int?,
    val nextOrdinal: Int?,
    val text: String,
    val offset: Int,
    val totalChars: Int,
    val truncated: Boolean,
    val qualityScore: Double?,
    val readingMethod: String?,
    val imageKind: String,
)

data class PageReviewImage(val path: Path, val mediaType: String)

/** A curated resource failure; neither paths nor parser/provider output cross this boundary. */
class PageReviewUnavailableException(val code: String, val conflict: Boolean = false, message: String) :
    IllegalStateException(message)

/** Reads exact immutable revision text independently of the page image's availability or rendering. */
class PageReviewService(
    private val paths: AppPaths,
    private val documents: DocumentStore,
    private val revisions: DocumentRevisionStore,
    private val library: ManagedLibrary,
    private val operations: OcrOperationStore,
    private val profileRevisionOf: (String) -> OcrProfileRevision?,
    private val draw: PdfPageRenderer = PngPageRenderer,
    private val maxRenderedPixels: Long = PdfExtractor.MAX_RENDERED_PIXELS,
    private val pageCounter: PageCounter = DefaultPageCounter(),
    private val originalSha256Of: (Path) -> String = ::pageReviewSha256,
) {
    private val verifiedCounts = ConcurrentHashMap<String, Int>()
    private data class OriginalIdentity(val size: Long, val modified: FileTime, val fileKey: Any?)
    private val unavailableCounts = ConcurrentHashMap<Path, OriginalIdentity>()
    fun review(
        collectionId: CollectionId,
        documentId: DocumentId,
        ordinal: Int,
        revisionId: String? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_TEXT_LIMIT,
    ): PageReviewResponse {
        require(offset >= 0) { "offset must be a whole number, zero or greater" }
        require(limit in 1..MAX_TEXT_LIMIT) { "limit must be from 1 to $MAX_TEXT_LIMIT" }
        val resolved = resolve(collectionId, documentId, ordinal, revisionId)
        val text = resolved.page?.extractedText.orEmpty()
        require(offset <= text.length) { "offset cannot exceed the page text length" }
        val start = if (splitsPair(text, offset)) offset - 1 else offset
        val requestedEnd = minOf(text.length.toLong(), start.toLong() + limit).toInt()
        val end = if (splitsPair(text, requestedEnd)) {
            if (requestedEnd - start <= 1) requestedEnd + 1 else requestedEnd - 1
        } else requestedEnd
        return PageReviewResponse(
            documentId = documentId.value,
            unitId = resolved.page?.unitId?.value,
            revisionId = resolved.revisionId,
            ordinal = ordinal,
            documentPages = resolved.pageCount,
            previousOrdinal = if (resolved.pageCount != null) (ordinal - 1).takeIf { it >= 0 }
                else resolved.revisionId?.let { revisions.neighbourOrdinal(it, ordinal, previous = true) },
            nextOrdinal = if (resolved.pageCount != null) (ordinal + 1).takeIf { it < resolved.pageCount }
                else resolved.revisionId?.let { revisions.neighbourOrdinal(it, ordinal, previous = false) },
            text = text.substring(start, end),
            offset = start,
            totalChars = text.length,
            truncated = end < text.length,
            qualityScore = OcrQualityScorer.score(text),
            readingMethod = resolved.page?.let { readingMethod(resolved.document, it) },
            imageKind = when {
                resolved.page?.sourceImage != null -> "ocr"
                resolved.page == null || resolved.page.extractionMethod == ExtractionMethod.DIRECT_TEXT -> "original"
                else -> "none"
            },
        )
    }

    /** A published document's image always needs the revision metadata resolved before this request. */
    fun image(
        collectionId: CollectionId,
        documentId: DocumentId,
        ordinal: Int,
        revisionId: String?,
        kind: String,
    ): PageReviewImage {
        require(kind == "ocr" || kind == "original") { "image kind must be ocr or original" }
        val resolved = resolve(collectionId, documentId, ordinal, revisionId, discoverPhysicalCount = false)
        require(revisionId != null || resolved.revisionId == null) { "a published page image needs its exact revision" }
        return if (kind == "ocr") ocrImage(resolved) else originalImage(resolved, ordinal)
    }

    private data class Resolved(
        val document: Document,
        val revisionId: String?,
        val page: RevisionPageText?,
        val original: Path,
        val pageCount: Int?,
    )

    private fun resolve(
        collection: CollectionId, documentId: DocumentId, ordinal: Int, requested: String?,
        discoverPhysicalCount: Boolean = true,
    ): Resolved {
        require(ordinal >= 0) { "ordinal must be a whole number, zero or greater" }
        val document = documents.get(documentId)?.takeIf { it.collectionId == collection } ?: missing()
        if (document.mediaType !in RescanPageSource.SUPPORTED_MEDIA_TYPES) {
            throw PageReviewUnavailableException("PAGE_IMAGES_UNSUPPORTED", true, "this document format has no page review")
        }
        val revisionId = requested ?: revisions.activeRevisionId(documentId)
        revisionId?.let { id ->
            val revision = revisions.revision(id)?.takeIf {
                it.documentId == documentId && (it.state == RevisionState.PUBLISHED || it.state == RevisionState.SUPERSEDED)
            } ?: missing()
            check(revision.documentId == documentId)
        }
        val original = library.managedPathOf(document)
        val page = revisionId?.let { revisions.page(it, ordinal) }?.takeIf { it.approval == PageApproval.APPROVED }
        val count = cachedCount(document) ?: if (discoverPhysicalCount) discoverCount(document) else null
        if (count != null) require(ordinal < count) { "ordinal is outside the document's physical pages" }
        else if (page == null && discoverPhysicalCount) missing()
        return Resolved(document, revisionId, page, original, count)
    }

    private fun verifiedOriginal(document: Document): Path {
        val path = library.managedPathOf(document)
        if (!confined(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) unavailableOriginal()
        val hash = try { originalSha256Of(path) } catch (_: IOException) { unavailableOriginal() }
        if (hash != document.sha256) {
            throw PageReviewUnavailableException("PAGE_REVIEW_MANAGED_COPY_CHANGED", true,
                "the managed original no longer matches the imported document; add the file again to restore it")
        }
        return path
    }

    private fun cacheDirectory(document: Document): Path = paths.artifactsDir(document.collectionId, document.id)
        .resolve("page-review/${document.sha256}-v${PageImage.RENDER_VERSION}-dpi$ORIGINAL_DPI")

    /** A count names verified immutable bytes, never the number of extracted text units. */
    private fun cachedCount(document: Document): Int? {
        verifiedCounts[document.sha256]?.let { return it }
        val file = cacheDirectory(document).resolve("physical-pages.txt")
        if (!confined(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        val count = try {
            if (Files.size(file) > 12) return null
            Files.readString(file).trim().toIntOrNull()?.takeIf { it > 0 }
        } catch (_: IOException) { null }
        if (count != null) verifiedCounts[document.sha256] = count
        return count
    }

    private fun discoverCount(document: Document): Int? {
        val path = library.managedPathOf(document)
        if (!confined(path)) return null
        val identity = try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) return null
            OriginalIdentity(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        } catch (_: IOException) { return null }
        // Failed source validation must not turn every bounded SQLite text slice into a full-file read.
        // This is only an unavailable-count hint: original image generation always verifies its source anew.
        if (unavailableCounts[path] == identity) return null
        val count = try {
            val original = verifiedOriginal(document)
            pageCounter.pageCount(original, document.mediaType)?.takeIf { it > 0 }
        } catch (_: PageReviewUnavailableException) { null }
        catch (_: IOException) { null }
        if (count == null) unavailableCounts[path] = identity else {
            unavailableCounts.remove(path)
            rememberCount(document, count)
        }
        return count
    }

    private fun rememberCount(document: Document, count: Int) {
        verifiedCounts[document.sha256] = count
        val directory = cacheDirectory(document)
        if (!confined(directory)) return
        // The cache is rebuildable. Failure to persist it must not make intact SQLite text unavailable.
        try {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".count-", ".part")
            try {
                Files.writeString(temporary, count.toString())
                Files.move(temporary, directory.resolve("physical-pages.txt"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        } catch (_: IOException) { /* The verified in-process count remains usable. */ }
    }

    private fun ocrImage(resolved: Resolved): PageReviewImage {
        val page = resolved.page ?: missingImage()
        val provenance = page.sourceImage ?: missingImage()
        val artifactRoot = paths.artifactsDir(resolved.document.collectionId, resolved.document.id)
        val managedRoot = paths.documentDir(resolved.document.collectionId, resolved.document.id)
        val root = if (provenance.root == SourceImageRoot.ARTIFACTS) artifactRoot else managedRoot
        val path = root.resolve(provenance.relativePath).normalize()
        if (!path.startsWith(root) || !confined(path)) missingImage()
        val image = try {
            PageImage.ofProvenance(resolved.document.id, page.unitId.value, page.ordinal, provenance, artifactRoot, managedRoot)
        } catch (_: IOException) { missingImage() } catch (_: IllegalArgumentException) { missingImage() }
        catch (_: IllegalStateException) { missingImage() }
        validateRaster(image)
        if (imageMediaType(image.imagePath) == "image/tiff") {
            return PageReviewImage(tiffDisplay(image.imagePath, resolved.document, provenance.sha256, image), "image/png")
        }
        return PageReviewImage(image.imagePath, imageMediaType(image.imagePath))
    }

    private fun originalImage(resolved: Resolved, ordinal: Int): PageReviewImage {
        if (resolved.document.mediaType != RescanPageSource.PDF_MEDIA_TYPE) {
            require(ordinal == 0) { "ordinal is outside the document's physical pages" }
            val original = verifiedOriginal(resolved.document)
            val image = measured(original, resolved.document, ordinal) ?: missingImage()
            validateRaster(image)
            if (imageMediaType(original) == "image/tiff") {
                return PageReviewImage(tiffDisplay(original, resolved.document, resolved.document.sha256, image), "image/png")
            }
            return PageReviewImage(resolved.original, imageMediaType(resolved.original))
        }
        val artifactRoot = paths.artifactsDir(resolved.document.collectionId, resolved.document.id)
        // All rendering inputs are immutable and in the key; interrupted work never occupies the final name.
        val cache = cacheDirectory(resolved.document)
        if (!confined(cache)) missingImage()
        Files.createDirectories(cache)
        val target = cache.resolve("page-$ordinal.png")
        if (!confined(target)) missingImage()
        measured(target, resolved.document, ordinal)?.let { cached ->
            validateRaster(cached)
            if (completeCachedRaster(target, cached)) return PageReviewImage(target, "image/png")
        }
        val original = verifiedOriginal(resolved.document)
        val temporary = Files.createTempDirectory(cache, ".render-")
        try {
            val rendered = try {
                Loader.loadPDF(original.toFile(), IOUtils.createTempFileOnlyStreamCache()).use { pdf ->
                    require(ordinal < pdf.numberOfPages) { "ordinal is outside the document's physical pages" }
                    rememberCount(resolved.document, pdf.numberOfPages)
                    PageImageRenderer(draw, maxRenderedPixels).render(pdf, ordinal + 1, ORIGINAL_DPI,
                        temporary, artifactRoot, resolved.document.id, resolved.page?.unitId?.value ?: "page:$ordinal")
                }
            } catch (_: IOException) { missingImage() }
            if (rendered == null) rasterUnbounded()
            validateRaster(rendered)
            Files.move(rendered.imagePath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return PageReviewImage(target, "image/png")
        } finally {
            Files.list(temporary).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(temporary)
        }
    }

    private fun measured(path: Path, document: Document, ordinal: Int): PageImage? {
        if (!confined(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            PageImage.ofFile(document.id, "page:$ordinal", ordinal, path.parent, path.fileName.toString(),
                paths.artifactsDir(document.collectionId, document.id), null, 0).takeIf { it.width != null }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
    }

    /** Header-only/partially written caches are rebuildable; check completeness only after the raster bound. */
    private fun completeCachedRaster(path: Path, image: PageImage): Boolean = try {
        ImageIO.read(path.toFile())?.let { raster ->
            try { raster.width == image.width && raster.height == image.height } finally { raster.flush() }
        } == true
    } catch (_: IOException) { false }

    /** Recorded TIFF provenance stays untouched; this bounded, rebuildable PNG is only for browser display. */
    private fun tiffDisplay(source: Path, document: Document, sourceHash: String, image: PageImage): Path {
        val directory = cacheDirectory(document)
        val target = directory.resolve("display-$sourceHash.png")
        if (!confined(target)) missingImage()
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && completeCachedRaster(target, image)) return target
        try {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".tiff-display-", ".part")
            try {
                val decoded = ImageIO.read(source.toFile()) ?: missingImage()
                try {
                    if (decoded.width != image.width || decoded.height != image.height) missingImage()
                    if (decoded.width.toLong() * decoded.height > maxRenderedPixels) rasterUnbounded()
                    if (!ImageIO.write(decoded, "png", temporary.toFile())) missingImage()
                } finally { decoded.flush() }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        } catch (failure: PageReviewUnavailableException) { throw failure }
        catch (_: IOException) { missingImage() }
        if (!confined(target) || !completeCachedRaster(target, image)) missingImage()
        return target
    }

    private fun validateRaster(image: PageImage) {
        val width = image.width ?: missingImage()
        val height = image.height ?: missingImage()
        if (width.toLong() * height > maxRenderedPixels) rasterUnbounded()
    }

    /** Producer identity is recorded when reading succeeds and inherited verbatim when text is preserved. */
    private fun readingMethod(document: Document, page: RevisionPageText): String? {
        if (page.extractionMethod == ExtractionMethod.DIRECT_TEXT) return "PDF text layer"
        if (page.extractionMethod != ExtractionMethod.OCR || page.sourceImage == null) return null
        val producerId = page.producerRevisionId ?: return null
        val producer = revisions.revision(producerId)?.takeIf { it.documentId == document.id &&
            (it.state == RevisionState.PUBLISHED || it.state == RevisionState.SUPERSEDED) } ?: return null
        val source = if (producer.id == page.revisionId) page else revisions.page(producer.id, page.ordinal) ?: return null
        if (source.approval != PageApproval.APPROVED || source.unitId != page.unitId || source.sourceImage != page.sourceImage ||
            source.extractedText != page.extractedText || source.extractionMethod != page.extractionMethod) return null
        val snapshot = operations.readingSnapshotForRevision(document.id, producer.id)
            ?: revisions.readingSnapshot(producer.id) ?: return null
        return when (snapshot.engine) {
            OcrEngine.TESSERACT -> "Tesseract"
            OcrEngine.SURYA -> "Surya"
            OcrEngine.LLM -> snapshot.transcriptionProfileRevisionId?.let(profileRevisionOf)?.let { "LLM · ${it.model}" }
        }
    }

    /** Refuse symbolic links in every document-owned component, including the artifact's ancestors. */
    private fun confined(path: Path): Boolean {
        val root = paths.libraryDir.normalize()
        val normalized = path.toAbsolutePath().normalize()
        if (normalized == root || !normalized.startsWith(root) || Files.isSymbolicLink(root)) return false
        var part = root
        for (segment in root.relativize(normalized)) {
            part = part.resolve(segment)
            if (Files.isSymbolicLink(part)) return false
        }
        return true
    }

    private fun imageMediaType(path: Path): String = try {
        ImageIO.createImageInputStream(path.toFile())?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) missingImage()
            val reader = readers.next()
            try {
                when (reader.formatName.lowercase()) {
                    "png" -> "image/png"
                    "jpeg", "jpg" -> "image/jpeg"
                    "tif", "tiff" -> "image/tiff"
                    else -> missingImage()
                }
            } finally { reader.dispose() }
        } ?: missingImage()
    } catch (_: IOException) { missingImage() }

    private fun splitsPair(text: String, offset: Int): Boolean = offset in 1 until text.length &&
        Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])

    private fun missing(): Nothing = throw NoSuchElementException("no page review exists in this collection")
    private fun unavailableOriginal(): Nothing = throw PageReviewUnavailableException("PAGE_REVIEW_ORIGINAL_UNAVAILABLE",
        message = "the managed original is unavailable; add the file again to restore it")
    private fun missingImage(): Nothing = throw PageReviewUnavailableException("PAGE_REVIEW_IMAGE_UNAVAILABLE",
        message = "the recorded page image is unavailable")
    private fun rasterUnbounded(): Nothing = throw PageReviewUnavailableException("PAGE_RASTER_UNBOUNDED", true,
        "this page exceeds the supported image size")

    companion object {
        const val DEFAULT_TEXT_LIMIT: Int = 16_384
        const val MAX_TEXT_LIMIT: Int = 65_536
        private const val ORIGINAL_DPI: Int = 150
    }
}

private fun pageReviewSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return java.util.HexFormat.of().formatHex(digest.digest())
}
