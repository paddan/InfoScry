package infoscry.extract

import infoscry.domain.SourceLocation
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Pictures, read by OCR, cited by the picture they are.
 *
 * An imported image has no structure to parse and no text layer to trust: it is one page, and the only way
 * to know what is on it is to read it. It therefore goes through exactly the OCR path a scanned PDF page
 * goes through — the same tool, the same languages, the same word boxes written under the same artifact
 * layout — so a citation on a photograph of a receipt behaves the way a citation on a scanned page does.
 *
 * Two properties are worth naming because they are the reason this is an extractor rather than a branch in
 * the PDF reader:
 *
 * - **The unit is committed before the next attempt could redo it.** A reading this large is the most
 *   expensive thing the pipeline does per unit, so a committed picture is skipped [ExtractionInput.isCommitted]
 *   before the tool is invoked at all, and a resumed attempt pays nothing for what it already has.
 * - **A missing tool is a document-level refusal, not a unit-level failure.** Every page of a scan fails
 *   the same way when Tesseract is not installed, so the whole document is refused once with
 *   [TesseractOcr.NEEDS_TESSERACT_CODE]; a tool that ran and failed on this one image fails only this unit.
 *
 * A picture that holds several pages — a multi-page TIFF — is one unit holding their text in the tool's
 * page order. `SourceLocation.Image` names one picture, and a citation opens the picture; splitting it
 * would have to invent a page number inside an image that no reader counts.
 */
class ImageExtractor(
    private val ocr: suspend (RenderedPage) -> OcrResult,
    private val maxImageBytes: Long = MAX_IMAGE_BYTES,
) : DocumentExtractor {

    init {
        require(maxImageBytes >= 1) {
            "an image memory bound must allow at least one byte, was $maxImageBytes"
        }
    }

    override val supportedMediaTypes: Set<String> = setOf(
        PNG_MEDIA_TYPE,
        JPEG_MEDIA_TYPE,
        TIFF_MEDIA_TYPE,
    )

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val imageBytes = try {
            Files.size(input.managedPath)
        } catch (unreadable: IOException) {
            // The managed copy this job recorded is not there to be read: a refusal about the document,
            // spelled the same way every other extractor spells it.
            refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
            return@flow
        }
        if (imageBytes > maxImageBytes) {
            refuseDocument(input, DOCUMENT_TOO_LARGE_KEY, DOCUMENT_TOO_LARGE_CODE)
            return@flow
        }

        var abortCode: String? = null
        var confidence: Double? = null
        input.boundary.unit {
            if (input.isCommitted(UNIT_KEY)) return@unit
            val reading = try {
                ocr(
                    RenderedPage(
                        documentId = input.documentId,
                        page = FIRST_PAGE,
                        imagePath = input.managedPath,
                        artifactRoot = input.artifactRoot,
                        ocrLanguages = input.settings.ocrLanguages,
                        // The picture is the document, not a rendering of one: this pipeline chose no
                        // resolution for it, and saying it did would be inventing provenance.
                        renderDpi = null,
                        fingerprint = input.fingerprint,
                    ),
                )
            } catch (unavailable: OcrUnavailableException) {
                // Not this image's failure: the tool cannot read any image, so one row in the queue saying
                // so is worth more than every picture failing identically.
                abortCode = unavailable.code
                return@unit
            } catch (failure: IOException) {
                emit(ExtractionEvent.UnitFailed(UNIT_KEY, ORDINAL, OCR_FAILED_CODE))
                return@unit
            }
            confidence = reading.meanConfidence
            val normalised = TextNormalizer.normalize(reading.text)
            emit(
                ExtractionEvent.UnitReady(
                    key = UNIT_KEY,
                    ordinal = ORDINAL,
                    unit = ContentUnitDraft(
                        locator = SourceLocation.Image(input.originalFilename),
                        extractedText = normalised.extracted,
                        searchText = normalised.search,
                        artifactRelativePath = reading.artifactRelativePath,
                        artifactSha256 = reading.artifactSha256,
                        meanConfidence = reading.meanConfidence,
                    ),
                ),
            )
        }

        val code = abortCode
        if (code != null) {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, code)
            return@flow
        }
        input.boundary.unit {
            emit(
                ExtractionEvent.Finished(
                    metadata = metadata(confidence),
                    totalUnits = ONE_UNIT,
                ),
            )
        }
    }

    private fun metadata(confidence: Double?): Map<String, String> = buildMap {
        put(IMAGES_METADATA, ONE_UNIT.toString())
        put(OCR_PAGES_METADATA, ONE_UNIT.toString())
        confidence?.let { value ->
            put(OCR_MEAN_CONFIDENCE_METADATA, String.format(Locale.ROOT, "%.3f", value))
        }
    }

    companion object {

        /** The types a picture arrives as, as a detector reports them. */
        internal const val PNG_MEDIA_TYPE: String = "image/png"
        internal const val JPEG_MEDIA_TYPE: String = "image/jpeg"
        internal const val TIFF_MEDIA_TYPE: String = "image/tiff"

        /**
         * The largest image this extractor will hand to the tool.
         *
         * The file is read by the tool rather than by this process, but a picture past this size is either
         * absurd or hostile, and the time it would take to read is time no other document gets.
         */
        internal const val MAX_IMAGE_BYTES: Long = 256L * 1024 * 1024

        /** A picture has exactly one unit, and its key says which one it is. */
        internal const val UNIT_KEY: String = "image:1"

        internal const val IMAGES_METADATA: String = "images"
        internal const val OCR_PAGES_METADATA: String = "ocr_pages"
        internal const val OCR_MEAN_CONFIDENCE_METADATA: String = "ocr_mean_confidence"

        private const val FIRST_PAGE: Int = 1
        private const val ORDINAL: Int = 0
        private const val ONE_UNIT: Int = 1
    }
}
