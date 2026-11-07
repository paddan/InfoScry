package infoscry.image

import infoscry.domain.NormalizedBox
import infoscry.extract.OcrUnavailableException
import infoscry.extract.PdfExtractor
import infoscry.ocr.BlockKind
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngines
import infoscry.ocr.PageStructure
import infoscry.ocr.PixelBox
import infoscry.ocr.StructureBlock
import infoscry.ocr.SuryaOcr
import infoscry.ocr.SuryaWorkerException
import infoscry.storage.CaptionSource
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Surya's layout runtime is missing or cannot start, so no picture regions can be found on scanned pages.
 * That includes a missing llama.cpp or Surya model, because the runtime cannot start without them.
 *
 * It is a document-level answer: every scanned page would fail the same way, so the caller reports it once per
 * document with [code] and carries on with the text and the embedded images.
 */
class SuryaLayoutUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** The code this failure reports, which the job shows once per document. */
    val code: String get() = NEEDS_SURYA_LAYOUT

    companion object {

        /** The Surya layout pass cannot run on this machine. */
        const val NEEDS_SURYA_LAYOUT: String = "NEEDS_SURYA_LAYOUT"
    }
}

/**
 * Cuts the pictures out of a scanned page, using the layout blocks Surya finds on it.
 *
 * Only the layout is kept: a page that is read here is read for its `FIGURE` blocks and their `CAPTION` blocks,
 * and the reading's text is not published. The text is used for one thing, which is the caption text of a
 * caption block, because a caption is a property of the picture and not of the page reading.
 *
 * A page whose structure is already known (Surya read it in this attempt or in its baseline) is not read again:
 * the engine is not called, and the structure is used as it is.
 *
 * The caller renders the page image; this class does not render.
 *
 * @param maxRenderedPixels the most pixels a page raster may hold to be decoded. A larger page is refused as
 *   [ImageRejectedException.IMAGE_TOO_LARGE] before any pixel is read: the crops must keep the raster's own
 *   coordinates, so the raster is never subsampled to fit.
 */
class LayoutRegions(
    private val engines: PageOcrEngines,
    private val maxRenderedPixels: Long = PdfExtractor.MAX_RENDERED_PIXELS,
) {

    init {
        require(maxRenderedPixels >= 1) { "a page raster bound must allow at least one pixel, was $maxRenderedPixels" }
    }

    /**
     * The picture regions of one scanned page, in reading order.
     *
     * @param structure the page's structure when it is already known, or null to have Surya read the page here.
     * @param pageText the text [structure] was derived into. Required with a [structure]; ignored otherwise,
     *   because a page read here carries its own text.
     * @param settings the attempt's settings, which the engine reads the page with.
     * @return the regions with their captions, or an empty result with a failure code when the page cannot be
     *   read; a region is not filtered by size here.
     * @throws SuryaLayoutUnavailableException when no Surya engine is configured, or its runtime cannot start.
     */
    suspend fun regionsOf(
        parent: ParentUnit,
        page: PageImage,
        structure: PageStructure?,
        pageText: String?,
        settings: OcrSettingsSnapshot,
    ): ParentImages {
        require(structure == null || pageText != null) { "a known page structure needs the text it was derived into" }
        // The header is read once, before any engine call, so a page that is refused is never read by Surya.
        return when (val opened = openPage(page)) {
            is OpenedPage.Refused -> failedWith(opened.code)
            is OpenedPage.Open -> opened.reader.use { reader ->
                regionsFrom(parent, page, reader, structure, pageText, settings)
            }
        }
    }

    private suspend fun regionsFrom(
        parent: ParentUnit,
        page: PageImage,
        reader: PageReader,
        structure: PageStructure?,
        pageText: String?,
        settings: OcrSettingsSnapshot,
    ): ParentImages {
        if (structure != null) return regionsIn(parent, reader, structure, requireNotNull(pageText))

        val surya = engines.engineFor(OcrEngine.SURYA) ?: throw SuryaLayoutUnavailableException(
            "no Surya engine is configured in this build, so picture regions cannot be found on scanned pages. " +
                SuryaOcr.installRemedy(),
        )
        val reading = try {
            surya.transcribe(page, settings)
        } catch (unavailable: OcrUnavailableException) {
            throw SuryaLayoutUnavailableException(
                "picture regions cannot be found on scanned pages: ${unavailable.message}",
                unavailable,
            )
        } catch (failed: SuryaWorkerException) {
            return failedWith(failed.code)
        }
        val found = reading.structure ?: return readingFailure(reading)
        return regionsIn(parent, reader, found, reading.text)
    }

    /** A reading that has no structure: a failure when it names one, and nothing when the page was empty. */
    private fun readingFailure(reading: OcrPageResult): ParentImages {
        val code = reading.errorCode
        return if (code == null || code == OcrPageResult.EMPTY_READING_CODE) ParentImages.NONE else failedWith(code)
    }

    private fun regionsIn(parent: ParentUnit, reader: PageReader, structure: PageStructure, text: String): ParentImages {
        val figures = structure.blocks
            .filter { it.kind == BlockKind.FIGURE && it.bbox != null }
            .sortedBy { it.order }
        if (figures.isEmpty()) return ParentImages.NONE

        val raster = decode(reader) ?: return failedWith(ImageRejectedException.IMAGE_DECODE_FAILED)
        val captions = captionsByFigure(figures, structure.blocks, text, raster.height)
        val images = figures.mapNotNull { figure ->
            regionOf(parent, raster, figure.bbox!!, captions[figure.order])
        }
        return ParentImages(images, failures = emptyList(), scanned = false)
    }

    /** The caption of each figure, by the figure's block order. A caption is claimed by one figure at most. */
    private fun captionsByFigure(
        figures: List<StructureBlock>,
        blocks: List<StructureBlock>,
        text: String,
        pageHeight: Int,
    ): Map<Int, String> {
        val captions = blocks
            .filter { it.kind == BlockKind.CAPTION && it.bbox != null }
            .sortedBy { it.order }
            .mapNotNull { block -> textOf(block, text)?.let { label -> block.order to Pair(block.bbox!!, label) } }
            .toMap()
        val claimed = mutableSetOf<Int>()
        val result = mutableMapOf<Int, String>()
        for (figure in figures) {
            val figureBox = figure.bbox!!
            val nearest = captions.entries
                .filter { it.key !in claimed }
                .mapNotNull { (order, caption) -> gapBetween(figureBox, caption.first, pageHeight)?.let { order to it } }
                .minByOrNull { it.second } ?: continue
            claimed += nearest.first
            result[figure.order] = captions.getValue(nearest.first).second
        }
        return result
    }

    /** The caption text of [block], trimmed, or null when the block is empty or its range is not in [text]. */
    private fun textOf(block: StructureBlock, text: String): String? {
        val range = block.extracted
        if (range.start < 0 || range.endExclusive > text.length) return null
        return text.substring(range.start, range.endExclusive).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The vertical gap between [figure] and [caption] when the caption sits directly below or above it, or null
     * when it does not. Adjacent means the gap is at most [CAPTION_GAP_SHARE] of the page height and the caption
     * overlaps at least half the figure's width.
     */
    private fun gapBetween(figure: PixelBox, caption: PixelBox, pageHeight: Int): Int? {
        val gap = when {
            caption.top >= figure.bottom -> caption.top - figure.bottom
            figure.top >= caption.bottom -> figure.top - caption.bottom
            else -> return null
        }
        if (gap.toDouble() > CAPTION_GAP_SHARE * pageHeight) return null
        val overlap = min(figure.right, caption.right) - max(figure.left, caption.left)
        if (overlap * 2 < figure.right - figure.left) return null
        return gap
    }

    /** Crops [box] out of [raster] and describes it, or null when the box has no area left on the page. */
    private fun regionOf(
        parent: ParentUnit,
        raster: BufferedImage,
        box: PixelBox,
        caption: String?,
    ): FoundImage? {
        val left = box.left.coerceIn(0, raster.width)
        val right = box.right.coerceIn(0, raster.width)
        val top = box.top.coerceIn(0, raster.height)
        val bottom = box.bottom.coerceIn(0, raster.height)
        if (right <= left || bottom <= top) return null

        val region = BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_RGB)
        val graphics = region.createGraphics()
        try {
            graphics.drawImage(raster.getSubimage(left, top, right - left, bottom - top), 0, 0, null)
        } finally {
            graphics.dispose()
        }
        val candidate = ImageCandidate(
            bytes = ImageBytes.Raster(region),
            widthPx = region.width,
            heightPx = region.height,
            box = NormalizedBox(
                left = left.toDouble() / raster.width,
                top = top.toDouble() / raster.height,
                right = right.toDouble() / raster.width,
                bottom = bottom.toDouble() / raster.height,
            ),
            isMask = false,
        )
        return FoundImage(
            parent = parent,
            kind = ImageSourceKind.PAGE_REGION,
            candidate = candidate,
            caption = caption,
            captionSource = if (caption != null) CaptionSource.LAYOUT_CAPTION else null,
        )
    }

    /**
     * Reads the page's header once and keeps its reader open, so a page over [maxRenderedPixels] is refused
     * without allocating its pixels, and a page that passes is decoded by the same reader.
     * The raster is never subsampled: the structure's boxes are in its own coordinates.
     */
    private fun openPage(page: PageImage): OpenedPage {
        val decodeFailed = OpenedPage.Refused(ImageRejectedException.IMAGE_DECODE_FAILED)
        val reader: PageReader = try {
            ImageIO.createImageInputStream(page.imagePath.toFile())?.let { stream ->
                val imageReader = ImageIO.getImageReaders(stream).takeIf { it.hasNext() }?.next()
                if (imageReader == null) {
                    stream.close()
                    null
                } else {
                    PageReader(stream, imageReader)
                }
            }
        } catch (unreadable: IOException) {
            null
        } catch (unreadable: RuntimeException) {
            // A decoder given untrusted bytes can fail with any runtime exception; none of them may escape.
            null
        } ?: return decodeFailed

        val pixels = try {
            reader.reader.input = reader.stream
            reader.reader.getWidth(0).toLong() * reader.reader.getHeight(0)
        } catch (unreadable: IOException) {
            reader.close()
            return decodeFailed
        } catch (unreadable: RuntimeException) {
            reader.close()
            return decodeFailed
        }
        if (pixels > maxRenderedPixels) {
            reader.close()
            return OpenedPage.Refused(ImageRejectedException.IMAGE_TOO_LARGE)
        }
        return OpenedPage.Open(reader)
    }

    /** The page raster, decoded by the reader [openPage] opened, or null when it cannot be decoded. */
    private fun decode(reader: PageReader): BufferedImage? = try {
        reader.reader.read(0)
    } catch (unreadable: IOException) {
        null
    } catch (unreadable: RuntimeException) {
        null
    }

    /** An open page image: its stream and the reader that has read its header. Close it when done. */
    private class PageReader(val stream: ImageInputStream, val reader: ImageReader) : AutoCloseable {
        override fun close() {
            try {
                reader.dispose()
            } finally {
                stream.close()
            }
        }
    }

    /** The outcome of opening a page image: the open reader, or the code that refused it. */
    private sealed interface OpenedPage {
        class Open(val reader: PageReader) : OpenedPage
        class Refused(val code: String) : OpenedPage
    }

    private fun failedWith(code: String): ParentImages =
        ParentImages(images = emptyList(), failures = listOf(code), scanned = false)

    private companion object {

        /** How far a caption may sit from its figure, as a share of the page height. */
        const val CAPTION_GAP_SHARE: Double = 0.03
    }
}
