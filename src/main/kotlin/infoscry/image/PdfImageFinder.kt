package infoscry.image

import infoscry.domain.NormalizedBox
import infoscry.domain.SourceLocation
import infoscry.extract.ImageExtractor
import infoscry.extract.PdfExtractor
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Path
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine

/**
 * The images PDFBox finds when it runs a page's content: image XObjects (including those inside form
 * XObjects) and inline images, in drawing order.
 *
 * A JPEG keeps its original bytes; everything else is decoded to pixels. A soft mask is part of its base
 * image and is not a second image. An image that covers [ImageFilter.SCANNED_PAGE_COVERAGE] of the page is the
 * scan: the page is marked scanned and the image is not decoded or returned. An image whose rectangle lies
 * wholly outside the visible page, or has no area, is not visible and is not returned; one partly outside is
 * clamped to the page.
 */
class PdfImageFinder(private val maxParentBytes: Long = ImageProbe.MAX_PARENT_BYTES) : ImageFinder {

    override val mediaTypes: Set<String> = setOf(PdfExtractor.PDF_MEDIA_TYPE)

    override fun open(managedPath: Path): OpenedSource {
        val document = Loader.loadPDF(managedPath.toFile(), IOUtils.createTempFileOnlyStreamCache())
        return Opened(document, maxParentBytes)
    }

    private class Opened(private val document: PDDocument, private val maxParentBytes: Long) : OpenedSource {

        @Synchronized
        override fun imagesOf(parent: ParentUnit): ParentImages {
            val locator = parent.locator as? SourceLocation.PdfPage ?: return ParentImages.NONE
            if (locator.page > document.numberOfPages) return ParentImages.NONE
            val page = document.getPage(locator.page - 1)
            val engine = Collector(page, parent, maxParentBytes)
            try {
                engine.processPage(page)
            } catch (unreadable: IOException) {
                engine.failures += ImageFindFailure.PARENT_UNREADABLE
            } catch (unreadable: RuntimeException) {
                // A content stream is untrusted; no failure of walking it may escape.
                engine.failures += ImageFindFailure.PARENT_UNREADABLE
            }
            return ParentImages(engine.images, engine.failures, engine.scanned)
        }

        @Synchronized
        override fun close() {
            document.close()
        }
    }

    private class Collector(
        private val page: PDPage,
        private val parent: ParentUnit,
        private val maxParentBytes: Long,
    ) : PDFGraphicsStreamEngine(page) {

        /** Each XObject decoded once per parent; repeated draws share the candidate's bytes. A null value is a failed decode. */
        private val decoded = java.util.IdentityHashMap<Any, ImageCandidate?>()
        private var retained = 0L
        private var overBudget = false

        val images = mutableListOf<FoundImage>()
        val failures = mutableListOf<String>()
        var scanned = false

        override fun drawImage(pdImage: PDImage) {
            val box = boxOf() ?: return
            if (ImageFilter.isScanned(box)) {
                scanned = true
                return
            }
            if (overBudget) return
            if (images.size + failures.size >= ImageIdentity.MAX_PER_PARENT) {
                if (ImageFindFailure.TOO_MANY_IMAGES !in failures) failures += ImageFindFailure.TOO_MANY_IMAGES
                return
            }
            val key: Any = (pdImage as? PDImageXObject)?.cosObject ?: pdImage
            val candidate = if (decoded.containsKey(key)) {
                // A failure is recorded once per XObject, when it is first decoded.
                decoded[key]?.copy(box = box) ?: return
            } else {
                val fresh = candidateOf(pdImage, box)
                decoded[key] = fresh
                if (fresh != null) retained += ImageProbe.retainedBytes(fresh)
                if (retained > maxParentBytes) {
                    overBudget = true
                    failures += ImageRejectedException.IMAGE_TOO_LARGE
                    return
                }
                fresh
            }
            if (candidate == null) {
                failures += ImageRejectedException.IMAGE_DECODE_FAILED
            } else {
                images += FoundImage(parent, ImageSourceKind.EMBEDDED, candidate, null, null)
            }
        }

        /** The image's unit square under the current transformation, as fractions of the displayed page. */
        private fun boxOf(): NormalizedBox? {
            val crop = page.cropBox
            val width = crop.width.toDouble()
            val height = crop.height.toDouble()
            if (width <= 0.0 || height <= 0.0) return null
            val transform: AffineTransform = graphicsState.currentTransformationMatrix.createAffineTransform()
            val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0).map { (x, y) ->
                val point = transform.transform(Point2D.Double(x, y), null)
                val u = (point.x - crop.lowerLeftX) / width
                val t = 1.0 - (point.y - crop.lowerLeftY) / height
                rotated(u, t)
            }
            return ImageProbe.boxOrNull(
                left = corners.minOf { it.first },
                top = corners.minOf { it.second },
                right = corners.maxOf { it.first },
                bottom = corners.maxOf { it.second },
            )
        }

        /** A point of the unrotated page (`u` right, `t` down) as it is displayed under the page's rotation. */
        private fun rotated(u: Double, t: Double): Pair<Double, Double> = when (Math.floorMod(page.rotation, 360)) {
            90 -> (1.0 - t) to u
            180 -> (1.0 - u) to (1.0 - t)
            270 -> t to (1.0 - u)
            else -> u to t
        }

        /** The image as a candidate, or null when it cannot be decoded. */
        private fun candidateOf(image: PDImage, box: NormalizedBox): ImageCandidate? {
            val isMask = image.isStencil
            // A stencil mask is skipped whatever it holds, so its pixels are never decoded: the declared size and the
            // XObject's flag are all the filter needs, and a placeholder keeps the candidate's shape.
            if (isMask) {
                return ImageCandidate(ImageBytes.Raster(MASK_PLACEHOLDER), image.width, image.height, box, isMask = true)
            }
            try {
                val jpeg = if (isPlainJpeg(image)) originalJpeg(image) else null
                if (jpeg != null) {
                    val probed = ImageProbe.probe(jpeg)
                    if (probed == null || probed.encodedMediaType != "image/jpeg") return null
                    return ImageCandidate(
                        ImageBytes.Encoded(jpeg, "image/jpeg"),
                        probed.widthPx,
                        probed.heightPx,
                        box,
                        isMask = false,
                    )
                }
                val step = ImageProbe.subsampleStep(image.width, image.height)
                val raster: BufferedImage = when {
                    image is PDImageXObject -> image.getOpaqueImage(null, step)
                    else -> image.getImage(null, step)
                }
                return ImageCandidate(ImageBytes.Raster(raster), image.width, image.height, box, isMask = false)
            } catch (unreadable: IOException) {
                return null
            } catch (unreadable: RuntimeException) {
                // A decoder given untrusted bytes can fail with any runtime exception.
                return null
            }
        }

        /** Whether the image is a DCT stream a reader shows as it is: grey or RGB, and not remapped by a Decode array. */
        private fun isPlainJpeg(image: PDImage): Boolean {
            if (image.suffix != "jpg") return false
            val components = image.colorSpace.numberOfComponents
            if (components != 1 && components != 3) return false
            val decode = image.decode ?: return true
            return decode.toFloatArray().withIndex().all { (index, value) ->
                value == (if (index % 2 == 0) 0f else 1f)
            }
        }

        /** The JPEG bytes as the file stores them, bounded to one more than an image may hold. */
        private fun originalJpeg(image: PDImage): ByteArray =
            image.createInputStream(listOf(COSName.DCT_DECODE.name, COSName.DCT_DECODE_ABBREVIATION.name)).use {
                it.readNBytes(Math.toIntExact(ImageExtractor.MAX_IMAGE_BYTES + 1))
            }

        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) = Unit

        override fun clip(windingRule: Int) = Unit

        override fun moveTo(x: Float, y: Float) = Unit

        override fun lineTo(x: Float, y: Float) = Unit

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit

        override fun getCurrentPoint(): Point2D = Point2D.Float()

        override fun closePath() = Unit

        override fun endPath() = Unit

        override fun strokePath() = Unit

        override fun fillPath(windingRule: Int) = Unit

        override fun fillAndStrokePath(windingRule: Int) = Unit

        override fun shadingFill(shadingName: COSName) = Unit
    }
}

/** What a stencil mask is carried as: it is skipped, so its own pixels are never decoded. */
private val MASK_PLACEHOLDER: BufferedImage = BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY)
