package infoscry.pdf

import kotlinx.serialization.Serializable

/**
 * What the writer did with one physical page. A [PdfPageCoverage.UNAVAILABLE] page must name a safe reason, and
 * no other coverage may carry one, so a missing layer is never reported without its explanation.
 */
@Serializable
data class PdfPageResult(val ordinal: Int, val coverage: PdfPageCoverage, val reasonCode: String?) {
    init {
        require(ordinal >= 0) { "a page result has a non-negative ordinal, was $ordinal" }
        require((coverage == PdfPageCoverage.UNAVAILABLE) == (reasonCode != null)) {
            "only an UNAVAILABLE page carries a reason code"
        }
        require(reasonCode == null || PdfModelRules.isSafeReasonCode(reasonCode)) {
            "a page result reason is a safe reason code"
        }
    }
}

/**
 * The per-page account of one written copy. [pages] lists every physical page, in order, from zero.
 *
 * The counts are kept apart on purpose: a verified BLANK page is complete with no selectable text, and an
 * UNAVAILABLE page is a missing layer. Neither is a failure count.
 */
@Serializable
data class SelectablePdfManifest(
    val revisionId: String,
    val rendererVersion: String,
    val fontHash: String,
    val pages: List<PdfPageResult>,
) {
    init {
        require(revisionId.isNotBlank()) { "a manifest names the revision it renders" }
        require(PdfModelRules.isSafeToken(rendererVersion)) { "a manifest names a safe renderer version" }
        require(PdfModelRules.isSha256(fontHash)) { "a manifest names its font by lowercase SHA-256" }
        require(pages.isNotEmpty()) { "a manifest covers at least one physical page" }
        pages.forEachIndexed { index, page ->
            require(page.ordinal == index) { "a manifest lists every physical page once, in order, from zero" }
        }
    }

    /** Pages whose text can be selected: NATIVE or OCR. */
    val selectablePageCount: Int get() = pages.count { it.coverage == PdfPageCoverage.NATIVE || it.coverage == PdfPageCoverage.OCR }

    /** Verified blank pages: complete, with no selectable text. */
    val blankPageCount: Int get() = pages.count { it.coverage == PdfPageCoverage.BLANK }

    /** Pages kept visible without selectable text, each with its reason. */
    val unavailablePageResults: List<PdfPageResult> get() = pages.filter { it.coverage == PdfPageCoverage.UNAVAILABLE }
}
