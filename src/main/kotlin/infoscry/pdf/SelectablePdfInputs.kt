package infoscry.pdf

import infoscry.config.AppPaths
import infoscry.domain.Document
import infoscry.domain.ExtractionMethod
import infoscry.library.ManagedLibrary
import infoscry.ocr.PageImage
import infoscry.storage.DocumentRevision
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageText
import infoscry.storage.RevisionState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDPage

/** What assembling one revision's selectable-PDF inputs found. */
sealed interface SelectablePdfResolution {

    /** Every physical page was accounted for; [request] and [keyInput] are ready to hand to the writer. */
    data class Ready(val request: PdfWriteRequest, val keyInput: SelectablePdfKeyInput) : SelectablePdfResolution

    /** This revision can never produce a public artifact: a safe reason, reported without a durable row. */
    data class Ineligible(val reasonCode: String, val message: String) : SelectablePdfResolution

    /** A safe, durable failure: the inputs could not be assembled, so the attempt never reaches the writer. */
    data class Failed(val reasonCode: String, val message: String) : SelectablePdfResolution
}

/**
 * Resolves one published revision's committed pages, images and layouts into the frozen writer request
 * and cache key 02 and 05 already define (ticket 06 of the selectable-PDF plan).
 *
 * Nothing here calls an OCR engine, an aligner or a provider: every input is either the immutable
 * original, or already-committed revision text, [infoscry.domain.SourceImageProvenance] and
 * [LayoutReference] rows. A page this resolver cannot account for safely makes the whole attempt
 * [SelectablePdfResolution.Failed] rather than silently omitted, exactly as the spec requires.
 */
object SelectablePdfInputResolver {

    const val PDF_MEDIA_TYPE: String = "application/pdf"

    /**
     * [revisionPages] is `revisions.pages(revisionId)`, read by the caller (this object never reads the
     * database) so recovery and the job handler can resolve the exact same list a status check saw.
     */
    fun resolve(
        paths: AppPaths,
        library: ManagedLibrary,
        document: Document,
        revision: DocumentRevision,
        revisionPages: List<RevisionPageText>,
        output: Path,
    ): SelectablePdfResolution {
        if (document.mediaType != PDF_MEDIA_TYPE) {
            return SelectablePdfResolution.Ineligible(
                "UNSUPPORTED_MEDIA_TYPE",
                "a selectable-text PDF copy can only be built from a PDF original",
            )
        }
        if (revision.state != RevisionState.PUBLISHED && revision.state != RevisionState.SUPERSEDED) {
            return SelectablePdfResolution.Ineligible(
                "REVISION_NOT_PUBLIC",
                "only a revision that was published may have a public selectable-PDF artifact",
            )
        }
        val original = library.managedPathOf(document)
        if (!Files.isRegularFile(original)) {
            return SelectablePdfResolution.Failed("ORIGINAL_MISSING", "the managed original is not on disk")
        }

        val geometries = try {
            readGeometries(original)
        } catch (error: IOException) {
            return SelectablePdfResolution.Failed("ORIGINAL_UNREADABLE", "the managed original could not be opened")
        }

        val byOrdinal = revisionPages.associateBy { it.ordinal }
        val artifactRoot = paths.artifactsDir(document.collectionId, document.id)
        val managedRoot = paths.documentDir(document.collectionId, document.id)

        val pageInputs = ArrayList<PdfPageInput>(geometries.size)
        val keyPages = ArrayList<SelectablePdfPageKeyInput>(geometries.size)
        for (ordinal in geometries.indices) {
            val geometry = geometries[ordinal]
            val page = byOrdinal[ordinal]
            val resolved = when {
                page == null -> unavailable(ordinal, geometry, image = null, layout = null, layoutSha256 = null)
                page.approval != PageApproval.APPROVED -> try {
                    unavailableFromApproval(ordinal, geometry, page, document, artifactRoot, managedRoot)
                } catch (error: IllegalStateException) {
                    return SelectablePdfResolution.Failed(
                        "SOURCE_IMAGE_PROVENANCE",
                        "physical page ${ordinal + 1}'s reading image is not the image its text was read from",
                    )
                }
                page.extractionMethod != ExtractionMethod.OCR -> Resolved.native(ordinal, geometry)
                else -> try {
                    ocrOrUnavailable(ordinal, geometry, page, document, artifactRoot, managedRoot)
                } catch (error: PageTextLayoutProvenanceException) {
                    return SelectablePdfResolution.Failed(
                        "LAYOUT_PROVENANCE_MISMATCH",
                        "physical page ${ordinal + 1}'s positioning artifact does not belong to its committed text or image",
                    )
                } catch (error: PageTextLayoutStoreCorruptException) {
                    return SelectablePdfResolution.Failed(
                        "LAYOUT_CORRUPT",
                        "physical page ${ordinal + 1}'s positioning artifact could not be read back",
                    )
                } catch (error: IllegalStateException) {
                    return SelectablePdfResolution.Failed(
                        "SOURCE_IMAGE_PROVENANCE",
                        "physical page ${ordinal + 1}'s reading image is not the image its text was read from",
                    )
                }
            }
            pageInputs += resolved.input
            keyPages += resolved.keyInput
        }

        val request = PdfWriteRequest(original, revision.id, pageInputs, output)
        val keyInput = SelectablePdfKeyInput(
            collectionId = document.collectionId,
            documentId = document.id,
            managedOriginalSha256 = document.sha256,
            revisionId = revision.id,
            rendererVersion = SelectablePdfWriter.RENDERER_VERSION,
            fontSha256 = PdfFontResolver.sha256(),
            pages = keyPages,
        )
        return SelectablePdfResolution.Ready(request, keyInput)
    }

    private class Resolved(val input: PdfPageInput, val keyInput: SelectablePdfPageKeyInput) {
        companion object {
            fun native(ordinal: Int, geometry: PdfPageGeometry) = Resolved(
                PdfPageInput(ordinal, null, null, null, geometry, PdfPageMode.NATIVE),
                SelectablePdfPageKeyInput(ordinal, PdfPageMode.NATIVE, null, null, null),
            )
        }
    }

    private fun unavailable(
        ordinal: Int,
        geometry: PdfPageGeometry,
        image: PageImage?,
        layout: PageTextLayout?,
        layoutSha256: String?,
    ): Resolved {
        // The writer reports its own safe reason for this mode from the layout's own reasonCode (or
        // its generic default when there is no layout at all); nothing here repeats that decision.
        return Resolved(
            PdfPageInput(ordinal, null, image?.imagePath, layout, geometry, PdfPageMode.UNAVAILABLE),
            SelectablePdfPageKeyInput(ordinal, PdfPageMode.UNAVAILABLE, null, image?.sha256, layoutSha256),
        )
    }

    /** A page nobody has approved yet: shown with its image when one exists, with no text, regardless of layout. */
    private fun unavailableFromApproval(
        ordinal: Int,
        geometry: PdfPageGeometry,
        page: RevisionPageText,
        document: Document,
        artifactRoot: Path,
        managedRoot: Path,
    ): Resolved {
        val image = resolveImage(page, document, artifactRoot, managedRoot)
        return Resolved(
            PdfPageInput(ordinal, null, image?.imagePath, null, geometry, PdfPageMode.UNAVAILABLE),
            SelectablePdfPageKeyInput(ordinal, PdfPageMode.UNAVAILABLE, page.textSha256, image?.sha256, null),
        )
    }

    /** An OCR page: OCR when its layout is complete, UNAVAILABLE with its image otherwise. */
    private fun ocrOrUnavailable(
        ordinal: Int,
        geometry: PdfPageGeometry,
        page: RevisionPageText,
        document: Document,
        artifactRoot: Path,
        managedRoot: Path,
    ): Resolved {
        val image = resolveImage(page, document, artifactRoot, managedRoot)
            ?: return unavailable(ordinal, geometry, null, null, null)
        val reference = page.layoutReference
            ?: return unavailable(ordinal, geometry, image, null, null)
        val canonicalText = page.extractedText
        val layout = PageTextLayoutStore.read(artifactRoot, reference, canonicalText)
        PageTextLayoutStore.verifyImageMatch(layout, image.sha256)
        if (!layout.complete) return unavailable(ordinal, geometry, image, layout, reference.sha256)
        return Resolved(
            PdfPageInput(ordinal, canonicalText, image.imagePath, layout, geometry, PdfPageMode.OCR),
            SelectablePdfPageKeyInput(ordinal, PdfPageMode.OCR, page.textSha256, image.sha256, reference.sha256),
        )
    }

    /** The page's reading image, hash-verified against its own provenance; `null` when none was recorded. */
    private fun resolveImage(page: RevisionPageText, document: Document, artifactRoot: Path, managedRoot: Path): PageImage? {
        page.sourceImage ?: return null
        return PageImage.ofProvenance(
            documentId = document.id,
            unitId = page.unitId.value,
            ordinal = page.ordinal,
            provenance = page.sourceImage,
            artifactRoot = artifactRoot,
            managedCopyRoot = managedRoot,
        )
    }

    /** This original's physical page geometry, in order, read while the file is open once. */
    private fun readGeometries(original: Path): List<PdfPageGeometry> =
        Loader.loadPDF(original.toFile(), MemoryUsageSetting.setupTempFileOnly().streamCache).use { document ->
            (0 until document.numberOfPages).map { index -> geometryOf(document.getPage(index)) }
        }

    private fun geometryOf(page: PDPage): PdfPageGeometry {
        val media = page.mediaBox
        val crop = page.cropBox
        return PdfPageGeometry(
            mediaX = media.lowerLeftX.toDouble(),
            mediaY = media.lowerLeftY.toDouble(),
            mediaWidth = media.width.toDouble(),
            mediaHeight = media.height.toDouble(),
            cropX = crop.lowerLeftX.toDouble(),
            cropY = crop.lowerLeftY.toDouble(),
            cropWidth = crop.width.toDouble(),
            cropHeight = crop.height.toDouble(),
            rotation = ((page.rotation % 360) + 360) % 360,
            userUnit = page.userUnit.toDouble(),
        )
    }
}
