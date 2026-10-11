package infoscry.pdf

import java.nio.file.Path
import kotlinx.serialization.Serializable

/**
 * How the writer builds one physical page. The caller resolves the mode from revision provenance, never from
 * whether the text happens to be empty.
 *
 * - NATIVE copies the original page objects and text. It takes no reading image and no layout.
 * - OCR rebuilds the page from its reading image and adds one invisible layer from a complete layout.
 * - BLANK is a page whose blankness was verified. It carries no visible text.
 * - UNAVAILABLE keeps the page visible with no selectable text, because its layout is missing or incomplete.
 */
enum class PdfPageMode { NATIVE, OCR, BLANK, UNAVAILABLE }

@Serializable
enum class PdfPageCoverage { NATIVE, OCR, BLANK, UNAVAILABLE }

/**
 * One physical page as the writer receives it. [ordinal] is zero-based and counts every physical page, even one
 * with no extracted unit. When both [canonicalText] and [layout] are present, the layout must belong to that text.
 */
data class PdfPageInput(
    val ordinal: Int,
    val canonicalText: String?,
    val readingImage: Path?,
    val layout: PageTextLayout?,
    val geometry: PdfPageGeometry,
    val mode: PdfPageMode,
) {
    init {
        require(ordinal >= 0) { "a physical page has a non-negative ordinal, was $ordinal" }
        if (canonicalText != null && layout != null) layout.verifiedAgainst(canonicalText)
        when (mode) {
            PdfPageMode.NATIVE -> require(layout == null && readingImage == null) {
                "a NATIVE page copies the original and takes neither a reading image nor a layout"
            }
            PdfPageMode.OCR -> {
                require(canonicalText != null && readingImage != null && layout != null) {
                    "an OCR page needs its canonical text, reading image and layout"
                }
                require(layout.complete) { "an OCR page needs a complete layout; an incomplete one is UNAVAILABLE" }
                require(layout.geometry == geometry) { "an OCR page's layout belongs to its page geometry" }
            }
            PdfPageMode.BLANK -> {
                require(canonicalText == null || canonicalText.codePoints().allMatch { PdfModelRules.isBlankCodePoint(it) }) {
                    "a BLANK page has no visible text"
                }
                require(layout == null || (canonicalText != null && layout.verifiedBlank)) {
                    "a BLANK page's layout, if given, places no text and is verified against its text"
                }
            }
            PdfPageMode.UNAVAILABLE -> require(layout == null || !layout.complete) {
                "an UNAVAILABLE page never carries a complete layout; a complete one is OCR or BLANK"
            }
        }
    }
}

/** The complete physical-page list for one published revision. [pages] must be every page, in order, from zero. */
data class PdfWriteRequest(
    val original: Path,
    val revisionId: String,
    val pages: List<PdfPageInput>,
    val output: Path,
) {
    init {
        require(revisionId.isNotBlank()) { "a write request names the revision it renders" }
        require(pages.isNotEmpty()) { "a write request covers at least one physical page" }
        pages.forEachIndexed { index, page ->
            require(page.ordinal == index) { "a write request lists every physical page once, in order, from zero" }
        }
        require(output.toAbsolutePath().normalize() != original.toAbsolutePath().normalize()) {
            "a write request never overwrites the original file"
        }
    }
}

/** A written copy: its manifest, the SHA-256 of the file bytes and their size. */
data class PdfWriteResult(val manifest: SelectablePdfManifest, val sha256: String, val bytes: Long) {
    init {
        require(PdfModelRules.isSha256(sha256)) { "a written PDF is named by lowercase SHA-256" }
        require(bytes > 0L) { "a written PDF has a positive size, was $bytes bytes" }
    }
}
