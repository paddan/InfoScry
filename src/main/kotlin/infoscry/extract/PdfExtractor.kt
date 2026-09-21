package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper

/**
 * What one page of OCR needs: the image to read and where the reading belongs.
 *
 * The seam carries the document and page this image came from, the artifact root the reading may write
 * its word boxes into, and the language codes the collection asked for. Passing them here rather than
 * reading them from an ambient service keeps the OCR implementation a plain function of its input: the
 * extractor already knows all four, and a tool that had to look them up would need the extractor's state.
 */
data class RenderedPage(
    val documentId: DocumentId,
    val page: Int,
    val imagePath: Path,
    val artifactRoot: Path,
    val ocrLanguages: String,
    val renderDpi: Int,
)

/** What OCR read from one page. */
data class OcrResult(
    val text: String,
    val meanConfidence: Double? = null,
)

/**
 * The OCR tool cannot read this document at all, so the whole document fails.
 *
 * The distinction matters: a page OCR could not read is one failed unit among many and the rest of the
 * document still delivers, while a missing or unusable tool means every page would fail the same way.
 * Reporting the second as a page failure would fill a document with identical failures and bury the one
 * thing the operator has to act on, which is why this exception carries a code the pipeline reports
 * against the document instead.
 */
class OcrUnavailableException(val code: String, message: String) : IOException(message)

/**
 * One page's own text layer, and the decision that follows from it.
 *
 * The threshold is deliberately crude and deliberately fixed: OCR is expensive, so it only runs where the
 * page cannot speak for itself. A page that carries a usable text layer is never rendered, and a page that
 * is a picture, a form, or a scan with a stray header is handed over rather than indexed as though its few
 * characters were the page.
 */
data class PdfPageCandidate(val page: Int, val text: String) {

    /** Whether this page has to be read by OCR instead of taken at its word. */
    val needsOcr: Boolean get() = needsOcr(text)

    companion object {

        /** Fewer alphanumeric characters than this on a page means the page has no usable text layer. */
        const val MIN_ALPHANUMERIC_CHARACTERS: Int = 40

        /** A page where less than this share of its non-whitespace characters is text has no text layer. */
        const val MIN_ALPHANUMERIC_RATIO: Double = 0.5

        /**
         * Whether a page's own text has to be replaced by OCR.
         *
         * Whitespace is removed before either measure is taken, because layout whitespace is not evidence
         * either way: a page of spaces is empty, and a page with generous margins is not less readable for
         * them.
         */
        fun needsOcr(text: String): Boolean {
            val visible = text.filterNot { character -> character.isWhitespace() }
            val alphanumeric = visible.count { character -> character.isLetterOrDigit() }
            return alphanumeric < MIN_ALPHANUMERIC_CHARACTERS ||
                alphanumericRatio(text) < MIN_ALPHANUMERIC_RATIO
        }

        /**
         * The share of a page's non-whitespace characters that are letters or digits.
         *
         * A page with nothing visible has a ratio of zero, which is the truthful answer: there is no text
         * on it to trust.
         */
        fun alphanumericRatio(text: String): Double {
            val visible = text.filterNot { character -> character.isWhitespace() }
            if (visible.isEmpty()) return 0.0
            return visible.count { character -> character.isLetterOrDigit() }.toDouble() / visible.length
        }
    }
}

/**
 * PDF pages, cited by page number, with OCR only where the page has no text of its own.
 *
 * A PDF is read one page at a time and each page is decided on its own: a page whose text layer is usable
 * becomes a unit without anything being rendered, and a page that is a scan is rendered and handed to the
 * OCR seam. That per-page split is what makes the common case — a digital PDF, or a mostly digital one
 * with a signed attachment at the end — cost nothing, and what keeps a long scan from stalling the
 * mutation gate for the whole document: the permit is taken per page, and the expensive rendering happens
 * inside that page's permit.
 *
 * A page that cannot be produced is one failed unit, never a failed document. Damaged content is the case
 * PDFBox is deliberately forgiving about — it warns and yields whatever text it did find — so the page that
 * genuinely stops a reader is one whose raster cannot exist: a page whose declared size cannot be rendered
 * within [maxRenderedPixels] at any legible resolution fails as a unit while every other page still
 * delivers. A refusal that covers the whole document — a container that cannot be opened, a protected
 * document, and an OCR tool that is not usable — is reported once against the document, and the flow then
 * ends without a `Finished` event, because the document did not get read.
 *
 * Rendering writes to a private temporary directory of this attempt's own and deletes it when the attempt
 * ends, including when it is cancelled or fails. Rendered pages are working material, not evidence: the
 * artifact root holds what a citation points at, and a half-finished render left in it would later be
 * treated as though it belonged to a committed unit.
 *
 * The resolution is the job's own ([ExtractionSettings.renderDpi]) when it carries one, because the
 * fingerprint is computed over exactly those settings and an extraction rendered at another resolution
 * would be reused for output it would not produce again. A page whose raster would exceed
 * [maxRenderedPixels] at that resolution is rendered at the highest resolution that fits, never below
 * [MIN_RENDER_DPI], and is refused when even that does not fit.
 */
class PdfExtractor(
    private val ocr: suspend (RenderedPage) -> OcrResult,
    private val maxDocumentBytes: Long = MAX_DOCUMENT_BYTES,
    private val maxRenderedPixels: Long = MAX_RENDERED_PIXELS,
) : DocumentExtractor {

    init {
        require(maxDocumentBytes >= 1) {
            "a PDF memory bound must allow at least one byte, was $maxDocumentBytes"
        }
        require(maxRenderedPixels >= 1) {
            "a rendered-page bound must allow at least one pixel, was $maxRenderedPixels"
        }
    }

    override val supportedMediaTypes: Set<String> = setOf(PDF_MEDIA_TYPE)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val containerBytes = Files.size(input.managedPath)
        if (containerBytes > maxDocumentBytes) {
            refuseDocument(input, DOCUMENT_TOO_LARGE_KEY, DOCUMENT_TOO_LARGE_CODE)
            return@flow
        }
        val document = try {
            // A temp-file cache keeps the reader's memory bounded by the page it is working on rather than
            // by the size of the file: a scan of five hundred pages must not have to fit in memory for its
            // first page to be cited.
            Loader.loadPDF(input.managedPath.toFile(), IOUtils.createTempFileOnlyStreamCache())
        } catch (protected: InvalidPasswordException) {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, ENCRYPTED_DOCUMENT_CODE)
            return@flow
        } catch (unreadable: IOException) {
            refuseDocument(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
            return@flow
        }
        try {
            if (document.isEncrypted) {
                // A document that opens without a password but is still encrypted is protected material
                // too: its text is readable only because the tool decrypted it, and this pipeline never
                // decrypts anything. It is refused rather than cited as though it were an ordinary file.
                refuseDocument(input, DOCUMENT_REFUSED_KEY, ENCRYPTED_DOCUMENT_CODE)
                return@flow
            }
            val run = emitPages(input, document)
            val code = run.abortCode
            if (code == null) {
                input.boundary.unit {
                    emit(
                        ExtractionEvent.Finished(
                            metadata = metadata(document, run),
                            totalUnits = run.total,
                        ),
                    )
                }
            } else {
                refuseDocument(input, DOCUMENT_REFUSED_KEY, code)
            }
        } finally {
            document.close()
        }
    }

    /**
     * Emits one unit per page, in page order, taking the permit per page.
     *
     * A page already committed under this fingerprint is counted and then skipped before anything about it
     * is read. That is the whole point of the checkpoints, and doing it here rather than after the text read
     * is what keeps a resumed attempt from paying for the pages it already has.
     */
    private suspend fun FlowCollector<ExtractionEvent>.emitPages(
        input: ExtractionInput,
        document: PDDocument,
    ): PageRun {
        val renderer = PDFRenderer(document)
        val run = PageRun()
        var workDirectory: Path? = null
        try {
            for (page in 1..document.numberOfPages) {
                if (run.abortCode != null) break
                input.boundary.unit {
                    run.total++
                    val key = pageKey(page)
                    if (input.isCommitted(key)) return@unit

                    val candidate = try {
                        PdfPageCandidate(page, pageText(document, page))
                    } catch (failure: Exception) {
                        rethrowUnlessAPageFailure(failure)
                        emit(ExtractionEvent.UnitFailed(key, page - 1, PAGE_UNREADABLE_CODE))
                        return@unit
                    }
                    if (!candidate.needsOcr) {
                        emitTextUnit(key, page, candidate.text)
                        return@unit
                    }

                    val requested = input.settings.renderDpi ?: DEFAULT_RENDER_DPI
                    val dpi = resolutionFor(document.getPage(page - 1), requested)
                    if (dpi == null) {
                        emit(ExtractionEvent.UnitFailed(key, page - 1, PAGE_RENDER_REFUSED_CODE))
                        return@unit
                    }
                    val directory = workDirectory ?: createWorkDirectory().also { workDirectory = it }
                    val image = try {
                        render(renderer, page, dpi, directory)
                    } catch (failure: Exception) {
                        rethrowUnlessAPageFailure(failure)
                        emit(ExtractionEvent.UnitFailed(key, page - 1, PAGE_RENDER_REFUSED_CODE))
                        return@unit
                    }
                    val result = try {
                        ocr(
                            RenderedPage(
                                documentId = input.documentId,
                                page = page,
                                imagePath = image,
                                artifactRoot = input.artifactRoot,
                                ocrLanguages = input.settings.ocrLanguages,
                                renderDpi = dpi,
                            ),
                        )
                    } catch (unavailable: OcrUnavailableException) {
                        // Not a page failure: the tool cannot read any page, so the document cannot be
                        // read, and one failure per page would only bury the fact that matters.
                        run.abortCode = unavailable.code
                        return@unit
                    } catch (failure: Exception) {
                        rethrowUnlessAPageFailure(failure)
                        emit(ExtractionEvent.UnitFailed(key, page - 1, OCR_FAILED_CODE))
                        return@unit
                    }
                    run.ocrPages++
                    run.confidences += result.meanConfidence
                    emitTextUnit(key, page, result.text)
                    // The collector has committed the unit by the time `emit` returns, so the working image
                    // has done its job and is removed inside the same permit that produced it.
                    Files.deleteIfExists(image)
                }
            }
        } finally {
            workDirectory?.let { directory -> directory.toFile().deleteRecursively() }
        }
        return run
    }

    /** The page's own text layer, read on its own so a damaged page is that page's problem. */
    private fun pageText(document: PDDocument, page: Int): String {
        val stripper = PDFTextStripper()
        stripper.startPage = page
        stripper.endPage = page
        return stripper.getText(document)
    }

    /**
     * The resolution a page is rendered at, or `null` when no legible resolution fits the pixel bound.
     *
     * The requested resolution is the first candidate and the legibility floor the last, so a page is
     * rendered as well as it can be within the bound instead of at a fixed compromise.
     */
    private fun resolutionFor(page: PDPage, requested: Int): Int? {
        val box = page.mediaBox
        val wanted = requested.coerceIn(MIN_RENDER_DPI, MAX_RENDER_DPI)
        for (dpi in wanted downTo MIN_RENDER_DPI) {
            if (pixels(box.width, box.height, dpi) <= maxRenderedPixels) return dpi
        }
        return null
    }

    private fun pixels(width: Float, height: Float, dpi: Int): Long {
        val columns = ceil(width.toDouble() * dpi / POINTS_PER_INCH)
        val rows = ceil(height.toDouble() * dpi / POINTS_PER_INCH)
        return (columns * rows).toLong()
    }

    /** Renders one page to a PNG inside this attempt's working directory and returns the file. */
    private fun render(renderer: PDFRenderer, page: Int, dpi: Int, directory: Path): Path {
        val image = renderer.renderImageWithDPI(page - 1, dpi.toFloat(), ImageType.RGB)
        val target = directory.resolve(String.format(Locale.ROOT, IMAGE_NAME_FORMAT, page))
        try {
            ImageIO.write(image, "png", target.toFile())
        } finally {
            image.flush()
        }
        return target
    }

    private fun createWorkDirectory(): Path = Files.createTempDirectory(WORK_DIRECTORY_PREFIX)

    /** Emits one page's text as a unit, in both of the forms a unit carries. */
    private suspend fun FlowCollector<ExtractionEvent>.emitTextUnit(key: String, page: Int, text: String) {
        val normalised = TextNormalizer.normalize(text)
        emit(
            ExtractionEvent.UnitReady(
                key = key,
                ordinal = page - 1,
                unit = ContentUnitDraft(
                    locator = SourceLocation.PdfPage(page),
                    extractedText = normalised.extracted,
                    searchText = normalised.search,
                ),
            ),
        )
    }

    private fun metadata(document: PDDocument, run: PageRun): Map<String, String> = buildMap {
        put("pages", document.numberOfPages.toString())
        if (run.ocrPages > 0) {
            put("ocr_pages", run.ocrPages.toString())
            val confidences = run.confidences.filterNotNull()
            if (confidences.isNotEmpty()) {
                put("ocr_mean_confidence", String.format(Locale.ROOT, "%.3f", confidences.average()))
            }
        }
    }

    companion object {

        /** The media type this extractor claims, which is what its own bytes report. */
        internal const val PDF_MEDIA_TYPE: String = "application/pdf"

        /** The code a container that cannot be opened as a PDF at all fails under. */
        internal const val DOCUMENT_UNREADABLE_CODE: String = "DOCUMENT_UNREADABLE"

        /** The code a page whose raster cannot exist fails under. */
        internal const val PAGE_RENDER_REFUSED_CODE: String = "PAGE_RENDER_REFUSED"

        /** The code a page whose OCR call failed fails under. */
        internal const val OCR_FAILED_CODE: String = "OCR_FAILED"

        /** The code a page whose own text layer cannot be read fails under. */
        internal const val PAGE_UNREADABLE_CODE: String = "PAGE_UNREADABLE"

        /**
         * The code a build with no OCR implementation reports for a page that needs one.
         *
         * The page decision, the rendering, and the permit discipline are this task's. The tool that reads
         * a rendered page belongs to the Tesseract task, which replaces the seam the production registry
         * wires; until then a page that has to be read by OCR is reported rather than silently left out of
         * the document.
         */
        internal const val OCR_UNAVAILABLE_CODE: String = "OCR_UNAVAILABLE"

        /** The resolution a page is rendered at when the job does not ask for another one. */
        internal const val DEFAULT_RENDER_DPI: Int = 300

        /** The lowest resolution a page may be rendered at and still be worth reading. */
        internal const val MIN_RENDER_DPI: Int = 100

        /** The highest resolution a job may ask for, so one setting cannot ask for an impossible raster. */
        internal const val MAX_RENDER_DPI: Int = 600

        /** The most pixels one rendered page may hold. */
        internal const val MAX_RENDERED_PIXELS: Long = 16_777_216L

        /** The largest PDF container this extractor will open. */
        internal const val MAX_DOCUMENT_BYTES: Long = 512L * 1024 * 1024

        /** The key a PDF refused before its first unit is recorded under. */
        internal const val DOCUMENT_KEY: String = DOCUMENT_REFUSED_KEY
    }
}

/** What one walk over a document's pages produced. */
private class PageRun(
    var total: Int = 0,
    var ocrPages: Int = 0,
    var abortCode: String? = null,
    val confidences: MutableList<Double?> = mutableListOf(),
)

/** The key of one page's unit: the page is the only stable position a PDF has. */
private fun pageKey(page: Int): String = "page:$page"

/**
 * Reports a document-level refusal once, inside a permit, and never twice for the same attempt.
 *
 * A refusal already committed under this fingerprint is the answer to this attempt too: the next attempt
 * recognises it instead of deriving it again from the same bytes.
 */
private suspend fun FlowCollector<ExtractionEvent>.refuseDocument(
    input: ExtractionInput,
    key: String,
    code: String,
) {
    if (input.isCommitted(key)) return
    input.boundary.unit {
        emit(ExtractionEvent.UnitFailed(key = key, ordinal = 0, code = code))
    }
}

/**
 * Lets cancellation and interruption out of a page's failure handling.
 *
 * A page that fails is a unit that failed; a run that was cancelled and a thread that was interrupted are
 * not, and turning either into a page failure would report a stopped attempt as a readable document.
 */
private fun rethrowUnlessAPageFailure(failure: Exception) {
    if (failure is CancellationException || failure is InterruptedException) throw failure
}

/** Points per inch: a PDF's own unit, and what a rendered resolution is relative to. */
private const val POINTS_PER_INCH: Double = 72.0

/** The name a rendered page gets, so a page's image is findable by its number. */
private const val IMAGE_NAME_FORMAT: String = "page-%06d.png"

/** The prefix of this attempt's private working directory. */
private const val WORK_DIRECTORY_PREFIX: String = "infoscry-pdf-pages-"
