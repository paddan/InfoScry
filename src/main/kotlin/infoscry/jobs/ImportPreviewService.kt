package infoscry.jobs

import infoscry.document.costEstimateOf
import infoscry.domain.CollectionId
import infoscry.extract.EXTRACTOR_SCHEMA_VERSION
import infoscry.extract.MediaTypeDetector
import infoscry.extract.PageCounter
import infoscry.image.ImageCount
import infoscry.image.ImageCounter
import infoscry.image.ImageExtractionMode
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.ImageAnalysisAdmission
import infoscry.ocr.ImageAnalysisRequest
import infoscry.ocr.OcrProfileService
import infoscry.ocr.ReadingMethodUnavailableException
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.ReadingMethod
import infoscry.ocr.ReadingMethodCatalog
import infoscry.storage.CollectionStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable

@Serializable
data class ImportPreviewRequest(
    val collection: CollectionId,
    val paths: List<String>,
    val recursive: Boolean = false,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val method: ReadingMethod,
    /** How much imagery the run extracts; null takes the collection's default. */
    val imageExtraction: ImageExtractionMode? = null,
    /** How the run describes its pictures; null describes none. */
    val imageAnalysis: ImageAnalysisRequest? = null,
)

@Serializable
data class ImportPreviewFile(
    val path: String,
    /** Pages that will be read, which is what the cost and the total are computed from. */
    val pages: Int?,
    val reason: String?,
    /** Every page of the document, so a preview can show how many of them are read. */
    val documentPages: Int? = null,
)

@Serializable
data class ImportPreview(
    val files: List<ImportPreviewFile>,
    val totalPages: Int,
    val atLeast: Boolean,
    val destination: String,
    val external: Boolean,
    val estimatedCostUsd: Double?,
    val costBasis: String?,
    val previewHash: String,
    /** How many pictures the analysis would send, when the selected files make that countable; null otherwise. */
    val imageCount: Int? = null,
    /** Whether the chosen image profile sends pictures off this machine. */
    val imageExternal: Boolean = false,
    /** True when [imageCount] is a lower bound because scanned pages can add regions no preview can count. */
    val imageAtLeast: Boolean = false,
    val imageEstimatedCostUsd: Double? = null,
    val imageCostBasis: String? = null,
    /** The confirmed picture allowance an external profile would run under; zero for a local one. */
    val imageAllowance: Int = 0,
)

internal data class ConfirmedImportSourceSet(val previewHash: String, val sources: List<ConfirmedImportSource>)

/** Computes the exact file set and page estimate a confirmed import start is bound to. */
class ImportPreviewService internal constructor(
    private val collections: CollectionStore,
    private val catalog: ReadingMethodCatalog,
    private val profiles: OcrProfileService,
    private val pageCounter: PageCounter,
    private val selection: ImportSelection,
    private val enumerate: (List<String>, Boolean, IgnorePatterns) -> List<ImportSource>,
    private val ignorePatterns: (CollectionId) -> IgnorePatterns,
    /** Refuses paths outside the import roots and the collection's upload area; the default allows every path. */
    private val allowPaths: (CollectionId, List<String>) -> Unit = { _, _ -> },
    /** How a selected file's media type is read, so its pictures can be counted before extraction. */
    private val detector: MediaTypeDetector = MediaTypeDetector(),
    /** How many pictures a file holds, as far as that is knowable before extraction. */
    private val imageCounter: ImageCounter = ImageCounter(),
) {
    fun preview(request: ImportPreviewRequest): ImportPreview {
        val collection = requireCollection(request.collection)
        val method = catalog.require(request.collection, request.method)
        val selected = selectedFiles(request, ignorePatterns(request.collection))
        val filePreviews = selected.map { source ->
            // No OCR reads no page: every file's pages keep their own text, so none is counted as read.
            val readablePages = if (request.method == ReadingMethod.None) {
                0
            } else {
                runCatching { pageCounter.readablePageCount(source.path) }.getOrNull()
            }
            val documentPages = runCatching { pageCounter.pageCount(source.path) }.getOrNull()
            ImportPreviewFile(
                path = source.path.toString(),
                pages = readablePages,
                reason = if (readablePages == null) "page count could not be read" else null,
                documentPages = documentPages,
            )
        }
        val totalPages = filePreviews.sumOf { it.pages ?: 0 }
        val pageTotalForCost = totalPages.takeIf { filePreviews.all { file -> file.pages != null } }
        val images = request.imageExtraction ?: collection.defaultImageExtraction
        val imageCount = imageCountOf(selected, filePreviews, images)
        val admittedImage = ImageAnalysisAdmission.admit(
            request.imageAnalysis, profiles::get, imageCount?.upperBound,
        )
        if (admittedImage != null && images == ImageExtractionMode.OFF) {
            throw ReadingMethodUnavailableException(
                "describing images needs images on; choose how much imagery to extract, or describe none",
            )
        }
        val imageRevision = admittedImage?.choice?.profileRevisionId?.let(profiles::findRevision)
        val imageEstimate = ImageAnalysisAdmission.costEstimateOf(imageCount?.upperBound, imageRevision)
        val snapshot = snapshotFor(collection.ocrLanguages, request.method)
        val estimate = if (method.external) {
            // A known-page subtotal cannot price an approval that also includes files whose counts are
            // unknown. Keep the named files in the confirmation scope, but do not present a partial subtotal
            // as the cost of reading all of them.
            costEstimateOf(snapshot, pageTotalForCost, true) { revisionId -> profiles.findRevision(revisionId) }
        } else {
            null to null
        }
        return ImportPreview(
            files = filePreviews,
            totalPages = totalPages,
            atLeast = filePreviews.any { it.pages == null },
            destination = method.destination,
            external = method.external,
            estimatedCostUsd = estimate.first?.amountUsd,
            costBasis = estimate.first?.basis ?: estimate.second,
            previewHash = hashOfSelected(collection.ocrLanguages, request.method, selected, images, admittedImage?.choice?.profileRevisionId),
            imageCount = imageCount?.known,
            imageExternal = imageRevision?.scope == infoscry.ocr.OcrEndpointScope.EXTERNAL,
            imageAtLeast = imageCount?.atLeast ?: false,
            imageEstimatedCostUsd = imageEstimate?.amountUsd,
            imageCostBasis = imageEstimate?.basis
                ?: if (admittedImage != null && imageRevision?.scope == infoscry.ocr.OcrEndpointScope.EXTERNAL) {
                    "the number of pictures is unknown, so the cost of sending them is unavailable"
                } else {
                    null
                },
            imageAllowance = admittedImage?.externalAllowance ?: 0,
        )
    }

    /** Computes the preview binding without opening page containers or rendering pages. */
    fun hashOf(request: ImportPreviewRequest): String {
        val collection = requireCollection(request.collection)
        catalog.require(request.collection, request.method)
        val selected = selectedFiles(request, ignorePatterns(request.collection))
        val images = request.imageExtraction ?: collection.defaultImageExtraction
        val revisionId = request.imageAnalysis
            ?.takeIf { it.profileId.isNotBlank() && it.profileId != ImageAnalysisAdmission.NONE }
            ?.let { profiles.get(it.profileId)?.revision?.revisionId }
        return hashOfSelected(collection.ocrLanguages, request.method, selected, images, revisionId)
    }

    /** Captures the exact file identities used by a start request, with a hash matching the preview contract. */
    internal fun confirmedSourceSet(request: ImportPreviewRequest): ConfirmedImportSourceSet {
        val collection = requireCollection(request.collection)
        catalog.require(request.collection, request.method)
        val sources = selectedFiles(request, ignorePatterns(request.collection)).map { file ->
            confirmedSourceOf(file.path)
        }
        val images = request.imageExtraction ?: collection.defaultImageExtraction
        val revisionId = request.imageAnalysis
            ?.takeIf { it.profileId.isNotBlank() && it.profileId != ImageAnalysisAdmission.NONE }
            ?.let { profiles.get(it.profileId)?.revision?.revisionId }
        return ConfirmedImportSourceSet(
            previewHash = hashOfConfirmed(collection.ocrLanguages, request.method, sources, images, revisionId),
            sources = sources,
        )
    }

    private fun selectedFiles(request: ImportPreviewRequest, ignore: IgnorePatterns): List<ImportSource> {
        require(request.paths.isNotEmpty()) { "an import preview needs at least one file or directory" }
        allowPaths(request.collection, request.paths)
        val extensions = ExtensionFilter.of(request.include, request.exclude)
        return enumerate(request.paths, request.recursive, ignore)
            .filter { source -> selection.admits(source.path, source.relativePath, source.exists, ignore, extensions) }
            // By file name first: an upload's path starts with its random id, so path order alone would shuffle
            // a chosen folder. The full path breaks ties, so the order stays the same for the same files.
            .sortedWith { left, right ->
                compareUtf8(left.path.fileName?.toString().orEmpty(), right.path.fileName?.toString().orEmpty())
                    .takeIf { it != 0 } ?: compareUtf8(left.path.toString(), right.path.toString())
            }
    }

    private fun hashOfSelected(
        language: String,
        method: ReadingMethod,
        files: List<ImportSource>,
        images: ImageExtractionMode,
        imageProfileRevisionId: String?,
    ): String {
        val sources = files.map { file -> confirmedSourceOf(file.path) }
        return hashOfConfirmed(language, method, sources, images, imageProfileRevisionId)
    }

    private fun hashOfConfirmed(
        language: String,
        method: ReadingMethod,
        sources: List<ConfirmedImportSource>,
        images: ImageExtractionMode,
        imageProfileRevisionId: String?,
    ): String {
        val profileRevision = when (method) {
            ReadingMethod.Tesseract, ReadingMethod.Surya, ReadingMethod.None, ReadingMethod.KeepText -> "-"
            is ReadingMethod.Llm -> profiles.get(method.profileId)?.revision?.revisionId
                ?: throw IllegalArgumentException("the selected OCR profile is unavailable")
        }
        val canonical = buildString {
            append("method=").append(method.id).append('\n')
            append("profileRevision=").append(profileRevision).append('\n')
            append("language=").append(language).append('\n')
            append("images=").append(images.id).append('\n')
            append("imageProfileRevision=").append(imageProfileRevisionId ?: "-").append('\n')
            sources.forEach { file ->
                append("file\t").append(file.path).append('\t').append(file.sizeBytes).append('\t')
                    .append(file.sha256).append('\n')
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    /**
     * How many pictures the selected files hold, when that is knowable before extraction.
     *
     * A standalone picture is one picture; a PDF's embedded pictures are counted from its page resources, and a
     * region pass on a scanned page adds pictures no preview can count, so the count is then a lower bound and
     * the confirmed allowance is one figure per scanned page above it. A format whose pictures are attached to
     * units only the extractor names is not counted, and the admission then refuses an external profile rather
     * than guessing what it would send.
     */
    private fun imageCountOf(
        files: List<ImportSource>,
        previews: List<ImportPreviewFile>,
        mode: infoscry.image.ImageExtractionMode,
    ): ImageCount? {
        if (files.isEmpty()) return null
        var known = 0
        var upperBound = 0
        var atLeast = false
        for ((source, preview) in files.zip(previews)) {
            val mediaType = try {
                detector.detect(source.path).value
            } catch (_: RuntimeException) {
                return null
            }
            val count = imageCounter.count(
                path = source.path,
                mediaType = mediaType,
                pages = preview.documentPages ?: preview.pages,
                withRegions = mode == infoscry.image.ImageExtractionMode.ALL,
            ) ?: return null
            known += count.known
            upperBound += count.upperBound
            atLeast = atLeast || count.atLeast
        }
        return ImageCount(known, upperBound, atLeast)
    }

    private fun confirmedSourceOf(path: Path): ConfirmedImportSource = ConfirmedImportSource(
        path = path.toString(),
        sizeBytes = Files.size(path),
        sha256 = sha256Of(path),
    )

    private fun snapshotFor(language: String, method: ReadingMethod): OcrSettingsSnapshot {
        val selected = CollectionOcrSettings(language, method)
        val profileId = (method as? ReadingMethod.Llm)?.profileId
        val revisionId = profileId?.let { profiles.require(it).revision.revisionId }
        return OcrSettingsSnapshot.of(
            settings = selected,
            extractorVersion = EXTRACTOR_SCHEMA_VERSION,
            transcriptionProfileRevisionId = revisionId,
        )
    }

    private fun requireCollection(id: CollectionId) = collections.get(id)
        ?.takeIf { it.lifecycle == infoscry.domain.CollectionLifecycle.ACTIVE }
        ?: throw NoSuchElementException("no active collection with id ${id.value}")

    private fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun compareUtf8(left: String, right: String): Int {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(a.size, b.size)) {
            val comparison = (a[index].toInt() and 0xff).compareTo(b[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return a.size.compareTo(b.size)
    }
}
