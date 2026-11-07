package infoscry.jobs

import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.SourceLocation
import infoscry.extract.PdfExtractor
import infoscry.image.ImageExtractionMode
import infoscry.image.ParentUnit
import infoscry.image.ParentUnitContext
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageStructure
import infoscry.ocr.PageImageRenderer
import java.nio.file.Files
import java.nio.file.Path
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils

/**
 * What an import and a rescan share when they hand the image stage its parent units: the context of one text unit,
 * the lazy render of a scanned PDF page, and the scratch directory those renders live in.
 */
internal object ImageParents {

    /** Where a region pass's page rasters live while it runs, inside the document's artifacts. */
    const val PAGES_DIRECTORY: String = ".image-pages"

    /**
     * The context of one text unit.
     *
     * A page's text and structure are read only when a region pass can use them (mode ALL); the raster is rendered
     * only if the stage asks for it.
     */
    fun contextOf(
        document: Document,
        mediaType: String,
        managedPath: Path,
        mode: ImageExtractionMode,
        artifactRoot: Path,
        workDirectory: Path,
        snapshot: OcrSettingsSnapshot,
        unitId: ContentUnitId,
        ordinal: Int,
        locator: SourceLocation,
        text: String,
        structure: () -> PageStructure?,
    ): ParentUnitContext {
        val needsText = mode == ImageExtractionMode.ALL
        return ParentUnitContext(
            parent = ParentUnit(unitId, ordinal, locator),
            text = if (needsText) text else "",
            structure = if (needsText) structure() else null,
            page = { renderPage(mediaType, managedPath, locator, unitId, artifactRoot, workDirectory, snapshot, document) },
        )
    }

    /** Renders the raster of a scanned PDF page for a region pass; null for any parent that is not a PDF page. */
    fun renderPage(
        mediaType: String,
        managedPath: Path,
        locator: SourceLocation,
        unitId: ContentUnitId,
        artifactRoot: Path,
        workDirectory: Path,
        snapshot: OcrSettingsSnapshot,
        document: Document,
    ): PageImage? {
        if (mediaType != PdfExtractor.PDF_MEDIA_TYPE || locator !is SourceLocation.PdfPage) return null
        return Loader.loadPDF(managedPath.toFile(), IOUtils.createTempFileOnlyStreamCache()).use { pdf ->
            PageImageRenderer().render(
                document = pdf,
                page = locator.page,
                requestedDpi = snapshot.renderDpi ?: PdfExtractor.DEFAULT_RENDER_DPI,
                directory = workDirectory.resolve("page-${locator.page}"),
                artifactRoot = artifactRoot,
                documentId = document.id,
                unitId = unitId.value,
            )
        }
    }

    /** Removes [directory] and everything under it; an absent directory is already clear. */
    fun clearDirectory(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
