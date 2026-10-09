package infoscry.document

import infoscry.ocr.OcrCostEstimate
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.OcrProfileRevision

internal const val ASSUMED_TOKENS_PER_PAGE: Int = 2_000

/** Shared page-image estimate used by rescan and import previews. */
internal fun costEstimateOf(
    snapshot: OcrSettingsSnapshot,
    pageTotal: Int?,
    external: Boolean,
    profileRevisionOf: (String) -> OcrProfileRevision?,
): Pair<OcrCostEstimate?, String?> {
    if (!external) return null to "nothing is sent off this machine, so there is no external cost"
    if (pageTotal == null) return null to "the page count is unknown, so no estimate can be made"
    val revisions = listOfNotNull(snapshot.transcriptionProfileRevisionId)
        .mapNotNull(profileRevisionOf)
        .filter { it.scope == OcrEndpointScope.EXTERNAL }
    if (revisions.isEmpty()) return null to "no external profile is selected"
    val unpriced = revisions.firstOrNull { it.inputPricePerMillion <= 0.0 || it.outputPricePerMillion <= 0.0 }
    if (unpriced != null) {
        return null to "profile revision ${unpriced.revisionId} names no input or output price, so the cost of " +
            "sending $pageTotal pages is unavailable"
    }
    val callsPerPage = 1
    val perMillion = revisions.sumOf { it.inputPricePerMillion + it.outputPricePerMillion }
    val amount = pageTotal.toDouble() * callsPerPage * ASSUMED_TOKENS_PER_PAGE * perMillion / 1_000_000.0
    return OcrCostEstimate(
        amountUsd = amount,
        basis = "$pageTotal pages × $callsPerPage call(s) per page at an assumed " +
            "$ASSUMED_TOKENS_PER_PAGE tokens each, priced at $perMillion per million tokens",
    ) to null
}
