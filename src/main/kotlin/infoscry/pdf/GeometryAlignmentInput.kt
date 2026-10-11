package infoscry.pdf

import infoscry.ocr.OcrWordBox
import infoscry.ocr.PageImage

/**
 * Everything the local aligner needs to place one page's canonical text, and nothing more.
 *
 * The raster must be measured: positions are meaningless against an image of unknown size. [anchors] are word
 * boxes from a local positioning pass, never recognizer blocks relabelled as words. [canonicalText] is the text
 * that will be placed, and it is never replaced by anchor text.
 */
data class GeometryAlignmentInput(
    val canonicalText: String,
    val image: PageImage,
    val geometry: PdfPageGeometry,
    val transform: ImageToPdf,
    val anchors: List<OcrWordBox>,
    val positioningVersion: String,
) {
    init {
        require(image.width != null && image.height != null) { "alignment needs a measured raster with known dimensions" }
        require(PdfModelRules.isSafeToken(positioningVersion)) { "alignment names a safe positioning version" }
    }
}
