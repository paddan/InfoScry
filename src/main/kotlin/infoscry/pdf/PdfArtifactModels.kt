package infoscry.pdf

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import kotlinx.serialization.Serializable

/**
 * The state a client sees for one revision's selectable PDF.
 *
 * NOT_CREATED is a description of "no artifact yet" and is never a stored job state. Every other state is one:
 * it has a durable row and a key.
 */
@Serializable
enum class PdfArtifactState {
    NOT_CREATED, QUEUED, BUILDING, READY, PARTIAL, FAILED;

    val isDurableJobState: Boolean get() = this != NOT_CREATED
}

/**
 * The wire status of one revision's selectable PDF. Each state's invariants are enforced here so that a status
 * read back from anywhere, the database, a route or a test, is valid or fails loudly.
 *
 * - NOT_CREATED: no key, no counts, no unavailable pages, no reason. [totalPages] may be unmeasured.
 * - QUEUED and BUILDING: a key; no counts yet; [totalPages] may be unknown.
 * - READY: a key and a known [totalPages]; every page is selectable or verified blank, none unavailable.
 * - PARTIAL: a key and a known [totalPages]; at least one unavailable page, each with a reason; every page is
 *   selectable, verified blank or unavailable.
 * - FAILED: a safe [reasonCode] and no counted pages. The [key] is optional so a retry can name its attempt.
 *
 * Validation here does not hold a file path or any source text; [reasonCode] and [key] are codes and digests.
 */
@Serializable
data class PdfArtifactStatus(
    val revisionId: String,
    val key: String?,
    val state: PdfArtifactState,
    val selectablePages: Int,
    val totalPages: Int?,
    val blankPages: Int,
    val unavailablePages: List<PdfPageResult>,
    val reasonCode: String?,
) {
    init {
        require(revisionId.isNotBlank()) { "a selectable PDF status names its revision" }
        require(key == null || PdfModelRules.isSha256(key)) { "an artifact key is a lowercase SHA-256" }
        require(selectablePages >= 0 && blankPages >= 0) { "page counts are non-negative" }
        require(totalPages == null || totalPages >= 0) { "a physical page total is non-negative" }
        require(reasonCode == null || PdfModelRules.isSafeReasonCode(reasonCode)) { "a status reason is a safe reason code" }
        require(unavailablePages.all { it.coverage == PdfPageCoverage.UNAVAILABLE }) {
            "unavailable-page results carry UNAVAILABLE coverage only"
        }
        require(unavailablePages.map { it.ordinal }.toSet().size == unavailablePages.size) {
            "each unavailable page is reported once"
        }
        unavailablePages.forEach { page ->
            require(totalPages == null || page.ordinal < totalPages) { "an unavailable page exists in the document" }
        }
        val noCountedPages = selectablePages == 0 && blankPages == 0 && unavailablePages.isEmpty()
        when (state) {
            PdfArtifactState.NOT_CREATED -> require(key == null && noCountedPages && reasonCode == null) {
                "NOT_CREATED has no key, counts, unavailable pages or reason"
            }
            PdfArtifactState.QUEUED, PdfArtifactState.BUILDING -> require(key != null && noCountedPages && reasonCode == null) {
                "an in-progress artifact has a key and no counts yet"
            }
            PdfArtifactState.READY -> {
                require(key != null && totalPages != null) { "a READY artifact has a key and a known total" }
                require(unavailablePages.isEmpty() && reasonCode == null) { "a READY artifact has no unavailable pages" }
                require(selectablePages + blankPages == totalPages) {
                    "a READY artifact accounts for every physical page exactly once"
                }
            }
            PdfArtifactState.PARTIAL -> {
                require(key != null && totalPages != null) { "a PARTIAL artifact has a key and a known total" }
                require(unavailablePages.isNotEmpty() && reasonCode == null) {
                    "a PARTIAL artifact names its unavailable pages"
                }
                require(selectablePages + blankPages + unavailablePages.size == totalPages) {
                    "a PARTIAL artifact accounts for every physical page exactly once"
                }
            }
            PdfArtifactState.FAILED -> require(reasonCode != null && noCountedPages) {
                "a FAILED artifact has a safe reason and no counted pages"
            }
        }
    }
}

@Serializable
data class CreateSelectablePdfRequest(val revisionId: String, val retry: Boolean = false) {
    init {
        require(revisionId.isNotBlank()) { "a create request names its revision" }
    }
}

/**
 * The artifact key convention, frozen. Version [SELECTABLE_PDF_KEY_VERSION] of the input list is:
 * collection, document, managed-original SHA-256, revision identity, renderer version, the embedded font's
 * SHA-256, and for each physical page its ordinal, mode and the text, reading-image and layout SHA-256 that
 * apply (`-` when absent). Changing any input changes the key. Consumers call [selectablePdfArtifactKey] and
 * invent no other identity.
 */
const val SELECTABLE_PDF_KEY_VERSION: Int = 1

data class SelectablePdfPageKeyInput(
    val ordinal: Int,
    val mode: PdfPageMode,
    val textSha256: String?,
    val imageSha256: String?,
    val layoutSha256: String?,
) {
    init {
        require(ordinal >= 0) { "a key input page has a non-negative ordinal, was $ordinal" }
        require(textSha256 == null || PdfModelRules.isSha256(textSha256)) { "a page text hash is a lowercase SHA-256" }
        require(imageSha256 == null || PdfModelRules.isSha256(imageSha256)) { "a page image hash is a lowercase SHA-256" }
        require(layoutSha256 == null || PdfModelRules.isSha256(layoutSha256)) { "a page layout hash is a lowercase SHA-256" }
    }
}

data class SelectablePdfKeyInput(
    val collectionId: CollectionId,
    val documentId: DocumentId,
    val managedOriginalSha256: String,
    val revisionId: String,
    val rendererVersion: String,
    val fontSha256: String,
    val pages: List<SelectablePdfPageKeyInput>,
) {
    init {
        require(collectionId.value.none { it.isISOControl() } && documentId.value.none { it.isISOControl() }) {
            "a key names its collection and document on one line"
        }
        require(PdfModelRules.isSha256(managedOriginalSha256)) { "a key names the original by lowercase SHA-256" }
        require(revisionId.isNotBlank() && revisionId.none { it.isISOControl() }) { "a key names its revision on one line" }
        require(PdfModelRules.isSafeToken(rendererVersion)) { "a key names a safe renderer version" }
        require(PdfModelRules.isSha256(fontSha256)) { "a key names its font by lowercase SHA-256" }
        require(pages.isNotEmpty()) { "a key covers at least one physical page" }
        pages.forEachIndexed { index, page ->
            require(page.ordinal == index) { "a key lists every physical page once, in order, from zero" }
        }
    }
}

/** The SHA-256 of the canonical, line-delimited form of [input]. Equal inputs always give the same key. */
fun selectablePdfArtifactKey(input: SelectablePdfKeyInput): String {
    val lines = buildList {
        add("key_version=$SELECTABLE_PDF_KEY_VERSION")
        add("collection=${input.collectionId.value}")
        add("document=${input.documentId.value}")
        add("managed_original=${input.managedOriginalSha256}")
        add("revision=${input.revisionId}")
        add("renderer=${input.rendererVersion}")
        add("font=${input.fontSha256}")
        input.pages.forEach { page ->
            add("page=${page.ordinal}:${page.mode.name}:${page.textSha256 ?: "-"}:${page.imageSha256 ?: "-"}:${page.layoutSha256 ?: "-"}")
        }
    }
    return PdfModelRules.sha256Of(lines.joinToString("\n"))
}
