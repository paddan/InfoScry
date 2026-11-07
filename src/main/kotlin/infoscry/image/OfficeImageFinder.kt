package infoscry.image

import infoscry.domain.NormalizedBox
import infoscry.domain.SourceLocation
import infoscry.extract.DOCX_MEDIA_TYPE
import infoscry.extract.PPTX_MEDIA_TYPE
import infoscry.extract.XLSX_MEDIA_TYPE
import infoscry.extract.ooxmlBlocks
import infoscry.extract.wordUnits
import infoscry.storage.CaptionSource
import java.awt.geom.Rectangle2D
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xssf.usermodel.XSSFPicture
import org.apache.poi.xssf.usermodel.XSSFShape
import org.apache.poi.xssf.usermodel.XSSFShapeGroup
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture

/**
 * Pictures in docx, pptx and xlsx files, read through POI's picture relations.
 *
 * The format is told by the parent's locator, because a path carries no media type: a [SourceLocation.WordSection]
 * opens the file as a Word document, a [SourceLocation.Slide] as a presentation and a
 * [SourceLocation.SpreadsheetRange] as a workbook. The legacy binary formats have no finder.
 *
 * - **docx:** a picture belongs to the section the extractor emitted for the paragraph holding it; a picture-only
 *   paragraph, which is not part of any section's text, belongs to the section before it (the first, when it
 *   comes before them all). Sections are rebuilt with the extractor's own walk, so they match its locators.
 *   A picture has no box.
 * - **pptx:** pictures on a slide, in shape order and through groups, with the box on the slide.
 * - **xlsx:** a picture belongs to the unit range of its sheet that contains the picture's anchor row; one
 *   anchored outside every range is not found, because a range's neighbours are not known here. No box.
 *
 * The caption is the shape's alt text (`descr`). A value that is only a file name, as Word writes for an inserted
 * file, is not a caption. A box with no area on the page is dropped and the picture is kept without one.
 */
class OfficeImageFinder : ImageFinder {

    override val mediaTypes: Set<String> = setOf(DOCX_MEDIA_TYPE, PPTX_MEDIA_TYPE, XLSX_MEDIA_TYPE)

    override fun open(managedPath: Path): OpenedSource = Opened(managedPath)

    /** A picture found but not yet read: [load] fetches its bytes only when its parent is served. */
    private class RawPicture(val load: () -> ByteArray?, val box: NormalizedBox?, val alt: String?)

    private class Opened(private val path: Path) : OpenedSource {

        private var word: Map<SourceLocation.WordSection, List<RawPicture>>? = null
        private var wordDocument: XWPFDocument? = null
        private var show: XMLSlideShow? = null
        private var book: XSSFWorkbook? = null

        @Synchronized
        override fun imagesOf(parent: ParentUnit): ParentImages {
            val raw = try {
                when (val locator = parent.locator) {
                    is SourceLocation.WordSection -> wordPictures()[locator]
                    is SourceLocation.Slide -> slidePictures(locator.number)
                    is SourceLocation.SpreadsheetRange -> sheetPictures(locator)
                    else -> null
                }
            } catch (unreadable: IOException) {
                return ParentImages(emptyList(), listOf(ImageFindFailure.PARENT_UNREADABLE), scanned = false)
            } catch (unreadable: RuntimeException) {
                // A document is untrusted; no failure of walking it may escape.
                return ParentImages(emptyList(), listOf(ImageFindFailure.PARENT_UNREADABLE), scanned = false)
            }
            return if (raw.isNullOrEmpty()) ParentImages.NONE else build(parent, raw)
        }

        @Synchronized
        override fun close() {
            wordDocument?.close()
            show?.close()
            book?.close()
        }

        private fun build(parent: ParentUnit, raw: List<RawPicture>): ParentImages {
            val images = mutableListOf<FoundImage>()
            val failures = mutableListOf<String>()
            for (picture in raw) {
                if (images.size + failures.size >= ImageIdentity.MAX_PER_PARENT) {
                    failures += ImageFindFailure.TOO_MANY_IMAGES
                    break
                }
                val candidate = picture.load()?.let { ImageProbe.candidateOf(it, picture.box) }
                if (candidate == null) {
                    failures += ImageRejectedException.IMAGE_DECODE_FAILED
                    continue
                }
                val caption = captionOf(picture.alt)
                images += FoundImage(
                    parent,
                    ImageSourceKind.EMBEDDED,
                    candidate,
                    caption,
                    if (caption == null) null else CaptionSource.ALT_TEXT,
                )
            }
            return ParentImages(images, failures, scanned = false)
        }

        private fun wordPictures(): Map<SourceLocation.WordSection, List<RawPicture>> {
            word?.let { return it }
            // The document stays open so a picture's bytes are read only when its section is served.
            val document = Files.newInputStream(path).use { XWPFDocument(it) }
            wordDocument = document
            val loaded = wordPicturesOf(document)
            word = loaded
            return loaded
        }

        private fun wordPicturesOf(document: XWPFDocument): Map<SourceLocation.WordSection, List<RawPicture>> {
            val sections = wordUnits(ooxmlBlocks(document)).map { it.locator }.filterIsInstance<SourceLocation.WordSection>()
            if (sections.isEmpty()) return emptyMap()
            val bySection = sections.associateWith { mutableListOf<RawPicture>() }
            fun sectionAt(paragraph: Int) = sections.lastOrNull { it.paragraphStart <= paragraph } ?: sections.first()
            fun add(paragraph: Int, owner: XWPFParagraph) {
                owner.runs.flatMap { run -> run.embeddedPictures }.forEach { picture ->
                    bySection.getValue(sectionAt(paragraph)) +=
                        RawPicture({ picture.pictureData?.data }, box = null, alt = picture.description)
                }
            }
            // The numbering is the extractor's: one per body paragraph, and a table row counts the paragraphs of its cells.
            var number = 0
            document.bodyElements.forEach { element ->
                when (element) {
                    is XWPFParagraph -> {
                        number++
                        add(number, element)
                    }

                    is XWPFTable -> element.rows.forEach { row ->
                        val start = number + 1
                        number += row.tableCells.sumOf { it.paragraphs.size }.coerceAtLeast(1)
                        row.tableCells.forEach { cell -> cell.paragraphs.forEach { add(start, it) } }
                    }

                    else -> Unit
                }
            }
            return bySection
        }

        private fun slidePictures(number: Int): List<RawPicture>? {
            val presentation = show ?: Files.newInputStream(path).use { XMLSlideShow(it) }.also { show = it }
            val slide = presentation.slides.getOrNull(number - 1) ?: return null
            val page = presentation.pageSize
            val found = mutableListOf<RawPicture>()
            fun walk(shapes: List<XSLFShape>, place: (Rectangle2D) -> Rectangle2D) {
                shapes.forEach { shape ->
                    when (shape) {
                        is XSLFGroupShape -> {
                            val outer = shape.anchor
                            val inner = shape.interiorAnchor
                            walk(shape.shapes) { child ->
                                place(
                                    if (inner.width <= 0.0 || inner.height <= 0.0) {
                                        child
                                    } else {
                                        Rectangle2D.Double(
                                            outer.x + (child.x - inner.x) * outer.width / inner.width,
                                            outer.y + (child.y - inner.y) * outer.height / inner.height,
                                            child.width * outer.width / inner.width,
                                            child.height * outer.height / inner.height,
                                        )
                                    },
                                )
                            }
                        }

                        is XSLFPictureShape -> {
                            val rect = place(shape.anchor)
                            found += RawPicture(
                                load = { shape.pictureData?.data },
                                box = ImageProbe.boxOrNull(
                                    rect.minX / page.width,
                                    rect.minY / page.height,
                                    rect.maxX / page.width,
                                    rect.maxY / page.height,
                                ),
                                alt = (shape.xmlObject as? CTPicture)?.nvPicPr?.cNvPr?.descr,
                            )
                        }

                        else -> Unit
                    }
                }
            }
            walk(slide.shapes) { it }
            return found
        }

        private fun sheetPictures(range: SourceLocation.SpreadsheetRange): List<RawPicture>? {
            val workbook = book ?: Files.newInputStream(path).use { XSSFWorkbook(it) }.also { book = it }
            val sheet = workbook.getSheet(range.sheet) ?: return null
            val drawing = sheet.drawingPatriarch ?: return null
            val first = rowOf(range.startCell) ?: return null
            val last = rowOf(range.endCell) ?: return null
            val found = mutableListOf<RawPicture>()
            fun walk(shapes: Iterable<XSSFShape>, groupRow: Int?) {
                shapes.forEach { shape ->
                    when (shape) {
                        is XSSFShapeGroup -> walk(shape, groupRow ?: shape.anchor?.let { rowOfAnchor(it) })
                        is XSSFPicture -> {
                            val row = groupRow ?: rowOfAnchor(shape.clientAnchor) ?: return@forEach
                            if (row in first..last) {
                                found += RawPicture({ shape.pictureData?.data }, box = null, alt = shape.ctPicture.nvPicPr.cNvPr.descr)
                            }
                        }

                        else -> Unit
                    }
                }
            }
            walk(drawing.shapes, null)
            return found
        }

        /** The one-based row of an A1 cell reference. */
        private fun rowOf(cell: String): Int? = cell.trimStart { it.isLetter() }.toIntOrNull()

        /** The one-based row an anchor starts on. */
        private fun rowOfAnchor(anchor: org.apache.poi.xssf.usermodel.XSSFAnchor?): Int? =
            (anchor as? org.apache.poi.xssf.usermodel.XSSFClientAnchor)?.let { it.row1 + 1 }
    }

    private companion object {

        private val FILE_NAME = Regex("^\\S+\\.(png|jpe?g|gif|bmp|tiff?|emf|wmf|svg|webp)$", RegexOption.IGNORE_CASE)

        /** The alt text as a caption, or null when it is blank or only a file name. */
        fun captionOf(alt: String?): String? {
            val text = alt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (FILE_NAME.matches(text.lowercase(Locale.ROOT))) null else text
        }
    }
}
