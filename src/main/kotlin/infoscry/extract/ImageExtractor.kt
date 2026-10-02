package infoscry.extract

import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceLocation
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrPageResult
import infoscry.ocr.PageImage
import infoscry.ocr.PageImageRenderer
import infoscry.ocr.PageOcrEngines
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
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
 *
 * A picture is held to two bounds, because it is two different things at once. Its *encoded* size is what a
 * file may cost this process ([maxImageBytes]); its *declared raster* is what a tool would allocate while
 * reading it ([maxRenderedPixels]) — and the second is a number a small, highly compressed file chooses
 * for itself, so it is read from the header and, when it is past the bound, reduced to a bounded copy
 * before any tool sees it ([pageImageToRead]).
 *
 * The import mode changes nothing here: a picture has no text layer to fill in or to check, so both modes
 * read it, and both read it with the engine the attempt selected.
 */
class ImageExtractor(
    private val ocr: PageOcrEngines,
    private val maxImageBytes: Long = MAX_IMAGE_BYTES,
    private val maxRenderedPixels: Long = PdfExtractor.MAX_RENDERED_PIXELS,
) : DocumentExtractor {

    init {
        require(maxImageBytes >= 1) {
            "an image memory bound must allow at least one byte, was $maxImageBytes"
        }
        require(maxRenderedPixels >= 1) {
            "a picture raster bound must allow at least one pixel, was $maxRenderedPixels"
        }
    }

    override val supportedMediaTypes: Set<String> = setOf(
        PNG_MEDIA_TYPE,
        JPEG_MEDIA_TYPE,
        TIFF_MEDIA_TYPE,
    )

    /** A picture *is* a page image, so a picture can be read again from one. */
    override val pageImageSupport: PageImageSupport = PageImageSupport.Supported

    /**
     * What the picture's engine would read it with, discovered without reading anything.
     *
     * A picture is one page and the engines are this reader's, so it is this reader that can say what they
     * are: the identity reaches the attempt's fingerprint before the page is handed over, for the attempt
     * that was admitted without one ([PageOcrEngines.engineFor] answers nothing for an engine this build
     * does not have, and the engine answers nothing when its runtime cannot be described).
     */
    override suspend fun runtimeIdentity(kind: OcrEngine): String? = ocr.engineFor(kind)?.runtimeIdentity()

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
            val image = try {
                pageImageToRead(input)
            } catch (unreadable: IOException) {
                // The bytes are there and cannot be read as an image at all. Nothing about this document
                // can be produced, so it is refused once rather than failing as a picture nobody could
                // open.
                abortCode = DOCUMENT_UNREADABLE_CODE
                emitDocumentRefusal(input, DOCUMENT_REFUSED_KEY, DOCUMENT_UNREADABLE_CODE)
                return@unit
            }
            if (image == null) {
                // The picture could not be shown to fit the raster bound this process hands to a tool: no
                // reader claims it, its header declares no raster this process can read, or the bounded copy
                // it would have to be read from could not be produced. Dispatching it anyway is exactly the
                // blow-up the bound exists to prevent — a declared raster is a number the *file* chooses —
                // so this picture's page is refused with the reason rather than handed over unbounded.
                emit(ExtractionEvent.UnitFailed(UNIT_KEY, ORDINAL, PAGE_RASTER_UNBOUNDED_CODE))
                return@unit
            }
            val reading = try {
                val engine = input.settings.readingEngine()
                // The build's engines plus the one this attempt builds for its dispatch authority: a picture
                // read by an external engine is one of this attempt's pages leaving the machine.
                ocr.forAttempt(input.dispatch)
                    .transcribe(engine, image, input.settings.pageOcrSettings(null))
            } catch (unavailable: OcrUnavailableException) {
                // Not this image's failure: the engine cannot read any image, so one row in the queue saying
                // so is worth more than every picture failing identically. The refusal is emitted inside
                // this unit's permit rather than under a second one, because the permit already covers
                // this step of work.
                abortCode = unavailable.code
                emitDocumentRefusal(input, DOCUMENT_REFUSED_KEY, unavailable.code)
                return@unit
            } catch (failure: IOException) {
                emit(ExtractionEvent.UnitFailed(UNIT_KEY, ORDINAL, OCR_FAILED_CODE))
                return@unit
            }
            // The picture is the page, so whether it is blank paper is a question about the artifact
            // itself, and it is asked whenever the reading came back with no text. Empty output is not a
            // successful extraction — the engine answers an empty reading under
            // [OcrPageResult.EMPTY_READING_CODE] rather than as a reading — so an empty reading of white
            // paper is a blank page, and an empty reading of a picture with ink on it fails this unit under
            // that code instead of being committed as a picture that says nothing.
            val verified = reading.verifiedAgainst(image)
            if (verified.text.isBlank() && verified.verifiedBlank) {
                emit(ExtractionEvent.UnitFailed(UNIT_KEY, ORDINAL, PAGE_BLANK_CODE))
                return@unit
            }
            if (verified.text.isBlank() || verified.errorCode != null) {
                // A reading the engine named a failure is that failure; one that says nothing without naming
                // a code is still not a reading, and neither is a page of whitespace.
                emit(
                    ExtractionEvent.UnitFailed(
                        UNIT_KEY,
                        ORDINAL,
                        verified.errorCode ?: OcrPageResult.EMPTY_READING_CODE,
                    ),
                )
                return@unit
            }
            confidence = verified.meanConfidence
            val normalised = TextNormalizer.normalize(verified.text)
            emit(
                ExtractionEvent.UnitReady(
                    key = UNIT_KEY,
                    ordinal = ORDINAL,
                    unit = ContentUnitDraft(
                        locator = SourceLocation.Image(input.originalFilename),
                        extractedText = normalised.extracted,
                        searchText = normalised.search,
                        // The picture is the document and the engine read it; there is no text layer here.
                        method = ExtractionMethod.OCR,
                        // The engine's word boxes are named inside the attempt's own artifacts, and the
                        // unit names them from the document's artifact root.
                        artifactRelativePath = verified.artifactRelativePath
                            ?.let { relative -> "${input.fingerprint.value}/$relative" },
                        artifactSha256 = verified.artifactSha256,
                        meanConfidence = verified.meanConfidence,
                        // Which file the tool was actually handed: the managed copy, or the bounded copy this
                        // attempt wrote for it. The two are different images, and a reading of one may never
                        // be attributed to the other.
                        sourceImage = provenance(image, input),
                    ),
                ),
            )
        }

        if (abortCode != null) {
            // The refusal was emitted inside the unit's permit above; nothing is left to report, and a
            // document that was refused is not reported as finished either.
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

    /**
     * The page image the engine reads: the managed copy, or a bounded copy of it.
     *
     * A picture is handed to a tool as a *path*, so the tool decodes it in its own process. The dimensions
     * the file declares are therefore read from its header first — two numbers, with no raster allocated to
     * learn them — and a picture past [maxRenderedPixels] is reduced here, with integer source subsampling,
     * into a copy that is written under the attempt's own artifacts and read from there. Nothing about the
     * managed copy changes: it is immutable, and the copy is a second file in the attempt's directory.
     *
     * Every frame is asked, because a container may hold several pictures — a multi-page TIFF is one unit —
     * and the tool decodes each of them. A picture whose frames all fit the bound is read as the managed
     * copy it is, with the hash and dimensions of that file.
     *
     * @return the page image, or `null` when the picture could not be shown to fit the bound.
     */
    private fun pageImageToRead(input: ExtractionInput): PageImage? {
        val managed = input.managedPath.toAbsolutePath().normalize()
        val stream = ImageIO.createImageInputStream(managed.toFile()) ?: return null
        try {
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = stream
                val frames = frameCount(reader) ?: return null
                if ((0 until frames).none { frame -> declaredPixels(reader, frame) > maxRenderedPixels }) {
                    return managedPageImage(input)
                }
                return reducedPageImage(input, reader, frames)
            } finally {
                reader.dispose()
            }
        } finally {
            stream.close()
        }
    }

    /**
     * Which file the reading was made from, as a durable record names it.
     *
     * A picture read from the managed copy names the managed copy: the reference resolves against the
     * directory the copy lives in, which is the document's own area and immutable, so it stays valid.
     * Anything else this extractor hands over it wrote itself under the attempt's artifacts, and its
     * reference resolves against the document's artifact root like the word boxes beside it — the attempt's
     * own directory is working material, while what it produced is the document's evidence.
     */
    private fun provenance(image: PageImage, input: ExtractionInput): SourceImageProvenance =
        if (image.imageRoot == input.managedPath.toAbsolutePath().normalize().parent) {
            image.managedCopyProvenance()
        } else {
            image.artifactProvenance(input.artifactRoot)
        }

    /**
     * The managed copy of the picture as a page image.
     *
     * A picture is not rendered by this pipeline, so its page image is the managed copy itself: the
     * reference can only name that one file inside the directory the copy lives in, and the recorded hash is
     * the hash of its bytes — the same digest the document was imported under. The engine's own reading is
     * written under the attempt's directory instead, because the managed copy is immutable and nothing this
     * pipeline writes may sit beside it.
     */
    private fun managedPageImage(input: ExtractionInput): PageImage {
        val managed = input.managedPath.toAbsolutePath().normalize()
        val root = requireNotNull(managed.parent) { "a managed copy lives in a directory" }
        return PageImage.ofFile(
            documentId = input.documentId,
            unitId = UNIT_KEY,
            ordinal = ORDINAL,
            imageRoot = root,
            imageReference = managed.fileName.toString(),
            artifactRoot = input.artifactRoot.resolve(input.fingerprint.value),
            // The picture is the document, not a rendering of one: this pipeline chose no resolution for it,
            // and saying it did would be inventing provenance.
            renderDpi = null,
            // Nothing in an imported picture declares a rotation of its own; the pixels are what they are.
            rotationDegrees = NO_DECLARED_ROTATION,
        )
    }

    /**
     * The picture as a bounded copy of itself, written under the attempt's artifacts and read from there.
     *
     * Each frame is decoded with the smallest whole subsampling step that brings *that* frame within the
     * bound, and the frames are written as one file, in the format a container of several pictures has to
     * keep for a tool to read all of them. The copy is named after the picture the way every other page
     * image of this pipeline is, so a citation can find the pixels a reading was made from, and it is
     * deliberately *not* written beside the managed copy: that file is immutable, and what this pipeline
     * produces belongs under its own attempt's artifacts.
     *
     * The recorded dimensions and hash are read back from the copy, so the page image says what was
     * actually written rather than what was intended, and its render version says the reading came from a
     * reduction rather than from the original pixels ([PageImage.REDUCED_RENDER_VERSION]).
     *
     * @return the bounded page image, or `null` when the copy could not be produced.
     */
    private fun reducedPageImage(input: ExtractionInput, reader: ImageReader, frames: Int): PageImage? {
        val artifactRoot = input.artifactRoot.resolve(input.fingerprint.value)
        val directory = artifactRoot.resolve(PageImageRenderer.PAGES_DIRECTORY)
        val target = directory.resolve(REDUCED_PICTURE_NAME)
        return try {
            Files.createDirectories(directory)
            writeBoundedCopy(reader, frames, target)
            PageImage.ofFile(
                documentId = input.documentId,
                unitId = UNIT_KEY,
                ordinal = ORDINAL,
                imageRoot = directory,
                imageReference = target.fileName.toString(),
                artifactRoot = artifactRoot,
                renderDpi = null,
                rotationDegrees = NO_DECLARED_ROTATION,
                renderVersion = PageImage.REDUCED_RENDER_VERSION,
            )
        } catch (unreadable: IOException) {
            // Whatever went wrong — the reader would not subsample the frame, the reader produced more than
            // it was asked for, the writer refused the copy — the reduction did not happen, and a picture
            // whose bounded copy does not exist is one this process must not hand to a tool.
            null
        }
    }

    /**
     * Writes [frames] of the picture, each subsampled into the bound, as the one file a tool reads.
     *
     * A frame is read and written one at a time and then dropped, so what this holds is one bounded raster
     * rather than the whole container — a hundred-page scan must not have to fit in memory for its first
     * page to be read. The write is not atomic: what reads this copy is a tool inside this attempt, which
     * never sees it if the attempt did not get this far, and the next attempt at the same name replaces it.
     */
    private fun writeBoundedCopy(reader: ImageReader, frames: Int, target: Path) {
        val writers = ImageIO.getImageWritersByFormatName(REDUCED_PICTURE_FORMAT)
        if (!writers.hasNext()) throw IOException("no $REDUCED_PICTURE_FORMAT writer is available")
        val writer = writers.next()
        try {
            if (!writer.canWriteSequence()) {
                throw IOException(
                    "the $REDUCED_PICTURE_FORMAT writer cannot hold several pictures in one file, so a " +
                        "bounded copy of this container cannot be written",
                )
            }
            val parameter = writer.defaultWriteParam
            val output = ImageIO.createImageOutputStream(target.toFile())
                ?: throw IOException("the bounded copy could not be opened for writing")
            output.use {
                writer.output = output
                writer.prepareWriteSequence(null)
                for (frame in 0 until frames) {
                    val raster = subsampledFrame(reader, frame)
                    try {
                        writer.writeToSequence(IIOImage(raster, null, null), parameter)
                    } finally {
                        raster.flush()
                    }
                }
                writer.endWriteSequence()
            }
        } finally {
            writer.dispose()
        }
    }

    /**
     * One frame of the picture, decoded with the smallest whole subsampling step that brings it within the
     * bound.
     *
     * The step is arithmetic rather than a guess: a reader produces `ceil(columns / step)` columns, so the
     * smallest step whose destination fits the bound is the step asked for. What comes back is checked
     * against the bound all the same, because the reader is asked for a step and not told one — a reader
     * that honoured it loosely would hand an unbounded raster to the tool, which is the one outcome this
     * whole step exists to prevent.
     */
    private fun subsampledFrame(reader: ImageReader, frame: Int): BufferedImage {
        val width = reader.getWidth(frame)
        val height = reader.getHeight(frame)
        val parameter = reader.defaultReadParam
            ?: throw IOException("the reader for this picture takes no read parameters, so it cannot subsample")
        val step = subsampleStep(width, height)
        if (step > 1) parameter.setSourceSubsampling(step, step, 0, 0)
        val raster = reader.read(frame, parameter)
        if (raster.width.toLong() * raster.height.toLong() > maxRenderedPixels) {
            raster.flush()
            throw IOException(
                "the reader produced a ${raster.width}x${raster.height} raster for a ${width}x$height " +
                    "frame subsampled by $step, which is past the $maxRenderedPixels pixel bound",
            )
        }
        return raster
    }

    /** The smallest whole subsampling step that brings a [width] by [height] frame within the bound. */
    internal fun subsampleStep(width: Int, height: Int): Int {
        var step = 1
        while (subsampledPixels(width, height, step) > maxRenderedPixels) step++
        return step
    }

    /**
     * What a reader produces for a frame subsampled by [step]: `ceil(columns / step)` per axis.
     *
     * The ceiling division is done in `Long` on purpose. In `Int` arithmetic `width + step - 1` overflows for
     * a width near `Int.MAX_VALUE`, and the overflowing result is negative — which the step search reads as
     * "this step already fits", so it would stop early and ask the reader for a raster of over a billion
     * pixels before the bound was ever consulted. This is the arithmetic that stands between a declared size
     * and an allocation, so it must not be able to under-report one.
     */
    internal fun subsampledPixels(width: Int, height: Int, step: Int): Long {
        val columns = (width.toLong() + step - 1) / step
        val rows = (height.toLong() + step - 1) / step
        return columns * rows
    }

    /** How many pictures the artifact holds, or `null` when its reader will not say. */
    private fun frameCount(reader: ImageReader): Int? = try {
        reader.getNumImages(true).takeIf { frames -> frames >= 1 }
    } catch (unreadable: IOException) {
        null
    } catch (unsupported: UnsupportedOperationException) {
        null
    }

    /** The pixels one frame declares, read from its header without decoding it. */
    private fun declaredPixels(reader: ImageReader, frame: Int): Long =
        reader.getWidth(frame).toLong() * reader.getHeight(frame).toLong()

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

        /**
         * The code a picture this process cannot bound fails under.
         *
         * A page image is handed to a tool as a path, and what the tool allocates is the raster the file's
         * own header declares. A picture whose raster cannot be read, cannot be brought within the bound,
         * or cannot be shown to fit it at all is therefore refused with this code instead of being handed
         * over — the alternative is a declared raster nobody in this process has limited.
         */
        internal const val PAGE_RASTER_UNBOUNDED_CODE: String = "PAGE_RASTER_UNBOUNDED"

        /**
         * The name a bounded copy of a picture gets, under the attempt's page images.
         *
         * The number is the page's ordinal plus one, the name every page image and every word-box artifact
         * of this pipeline has, so the pixels a reading was made from are findable by page number.
         */
        internal const val REDUCED_PICTURE_NAME: String = "page-000001.tiff"

        /**
         * The format a bounded copy is written in.
         *
         * A picture that holds several frames has to stay one file for the tool to read all of them, and
         * TIFF is the format this build's writers can hold several pictures in.
         */
        internal const val REDUCED_PICTURE_FORMAT: String = "tiff"

        /** A picture has exactly one unit, and its key says which one it is. */
        internal const val UNIT_KEY: String = "image:1"

        internal const val IMAGES_METADATA: String = "images"
        internal const val OCR_PAGES_METADATA: String = "ocr_pages"
        internal const val OCR_MEAN_CONFIDENCE_METADATA: String = "ocr_mean_confidence"

        private const val ORDINAL: Int = 0
        private const val ONE_UNIT: Int = 1

        /** What an image declares about its own rotation: nothing, the pixels are the page. */
        private const val NO_DECLARED_ROTATION: Int = 0
    }
}
