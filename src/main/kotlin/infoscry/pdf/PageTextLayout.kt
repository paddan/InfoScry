package infoscry.pdf

import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable

/**
 * A layout's text hash does not match the canonical text it is verified against: corrupt or mismatched
 * provenance, distinct from the [IllegalArgumentException] that structural validation throws.
 */
class PageTextLayoutProvenanceException(message: String) : IllegalStateException(message)

/**
 * The structural rules every selectable-PDF record shares. Internal on purpose: the layout, artifact, writer
 * and manifest records must agree on what a hash, a reason code or an identifier looks like.
 */
internal object PdfModelRules {
    private val sha256Hex = Regex("[0-9a-f]{64}")
    private val safeReasonCode = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val safeToken = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** Positions are in image pixels or points; this tolerates float noise at an exact edge, nothing more. */
    const val EDGE_TOLERANCE: Double = 1e-6

    val QUARTER_TURNS: Set<Int> = setOf(0, 90, 180, 270)

    fun isSha256(value: String?): Boolean = value != null && sha256Hex.matches(value)

    /** A machine code such as `NO_ALIGNMENT`: never free text, which could carry source words or a provider body. */
    fun isSafeReasonCode(value: String?): Boolean = value != null && safeReasonCode.matches(value)

    fun isSafeToken(value: String): Boolean = safeToken.matches(value)

    /** Lowercase hex SHA-256 over UTF-8 bytes: the one digest form every fingerprint in this package uses. */
    fun sha256Of(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    /** Whitespace for coverage purposes: a gap that a span does not need to cover. */
    fun isBlankCodePoint(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    fun isLineBreakCodePoint(codePoint: Int): Boolean =
        codePoint == '\n'.code || codePoint == '\r'.code || codePoint == 0x0B || codePoint == 0x0C ||
            codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029

    /** True when [index] falls between the two halves of one surrogate pair, which no range may do. */
    fun splitsSurrogatePair(text: String, index: Int): Boolean =
        index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
}

/** A rectangle in image pixels (top-left origin) or, where used by a writer, in PDF points. */
@Serializable
data class TextRectangle(val left: Double, val top: Double, val width: Double, val height: Double) {
    init {
        require(left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite()) {
            "a text rectangle has finite coordinates"
        }
        require(width > 0.0 && height > 0.0) { "a text rectangle has a positive size, was ${width}x$height" }
    }
}

@Serializable
enum class SpanGranularity { WORD, LINE }

/**
 * One positioned piece of the canonical reading. [start] and [endExclusive] are UTF-16 offsets into the
 * canonical `extractedText`; [order] is the reading order and is unique within a layout.
 *
 * Spans are token-level only, never per-character: [SpanGranularity.WORD] is one whitespace-free token and
 * [SpanGranularity.LINE] is one visual line. A multi-line recognizer block is not a LINE span. The
 * text-dependent part of those rules (no whitespace in a WORD, no line break in a LINE, no split surrogate
 * pair) is checked by [PageTextLayout.verifiedAgainst], because only the text can prove it.
 */
@Serializable
data class PositionedTextSpan(
    val start: Int,
    val endExclusive: Int,
    val rectangle: TextRectangle,
    val granularity: SpanGranularity,
    val order: Int,
) {
    init {
        require(start >= 0) { "a span starts at a non-negative offset, was $start" }
        require(endExclusive > start) { "a span covers at least one character, was $start until $endExclusive" }
        require(order >= 0) { "a span has a non-negative reading order, was $order" }
    }
}

/**
 * Maps top-left image pixels into unrotated PDF user space: `x' = a*x + c*y + e`, `y' = b*x + d*y + f`.
 * PDF page rotation is applied once by the viewer; a writer that normalizes output rotation composes it.
 */
@Serializable
data class ImageToPdf(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    init {
        require(listOf(a, b, c, d, e, f).all { it.isFinite() }) { "an image transform has finite entries" }
        val determinant = a * d - b * c
        require(determinant.isFinite() && determinant != 0.0) {
            "an image transform is invertible, so no page area collapses onto a line"
        }
    }
}

/** The page's crop and media geometry as a PDF reader sees it, with the rotation the reader applies. */
@Serializable
data class PdfPageGeometry(
    val mediaX: Double,
    val mediaY: Double,
    val mediaWidth: Double,
    val mediaHeight: Double,
    val cropX: Double,
    val cropY: Double,
    val cropWidth: Double,
    val cropHeight: Double,
    val rotation: Int,
    val userUnit: Double,
) {
    init {
        require(
            listOf(mediaX, mediaY, mediaWidth, mediaHeight, cropX, cropY, cropWidth, cropHeight, userUnit)
                .all { it.isFinite() },
        ) { "page geometry has finite values" }
        require(mediaWidth > 0.0 && mediaHeight > 0.0) { "a media box has a positive size" }
        require(cropWidth > 0.0 && cropHeight > 0.0) { "a crop box has a positive size" }
        val tolerance = PdfModelRules.EDGE_TOLERANCE
        require(
            cropX >= mediaX - tolerance &&
                cropY >= mediaY - tolerance &&
                cropX + cropWidth <= mediaX + mediaWidth + tolerance &&
                cropY + cropHeight <= mediaY + mediaHeight + tolerance,
        ) { "a crop box lies inside its media box" }
        require(userUnit > 0.0) { "a page's user unit is positive, was $userUnit" }
        require(rotation in PdfModelRules.QUARTER_TURNS) {
            "a page rotation is 0, 90, 180 or 270 degrees, was $rotation"
        }
    }
}

/**
 * Positioned text for one page's reading image.
 *
 * A layout is `complete` only when [verifiedAgainst] proves every non-whitespace character of the canonical
 * text is covered by exactly one span. An incomplete layout is a normal, valid record: it carries a safe
 * [reasonCode] and may hold partial spans or none. Callers map it to an unavailable page; they never repair
 * it by changing the text. A complete layout with no spans is a verified blank only once the text is proven
 * blank, which [verifiedAgainst] does; [verifiedBlank] reports the shape.
 *
 * Construction checks everything that does not need the text. [verifiedAgainst] checks the rest, and every
 * boundary that reads a layout back must call it with the canonical text it belongs to.
 */
@Serializable
data class PageTextLayout(
    val version: Int,
    val textSha256: String,
    val imageSha256: String,
    val width: Int,
    val height: Int,
    val geometry: PdfPageGeometry,
    val transform: ImageToPdf,
    val spans: List<PositionedTextSpan>,
    val complete: Boolean,
    val reasonCode: String?,
    val positioningVersion: String,
) {
    init {
        require(version >= 1) { "a layout names a positive record version, was $version" }
        require(PdfModelRules.isSha256(textSha256)) { "a layout names its canonical text by lowercase SHA-256" }
        require(PdfModelRules.isSha256(imageSha256)) { "a layout names its reading image by lowercase SHA-256" }
        require(width > 0 && height > 0) { "a layout's image has a positive size, was ${width}x$height" }
        require(PdfModelRules.isSafeToken(positioningVersion)) { "a layout names a safe positioning version" }
        require(!complete || reasonCode == null) { "a complete layout has no reason code" }
        require(complete || PdfModelRules.isSafeReasonCode(reasonCode)) {
            "an incomplete layout has a safe reason code"
        }
        requireWellFormedSpans()
    }

    /** True for a complete layout with no spans. Only [verifiedAgainst] proves the text it refers to is blank. */
    val verifiedBlank: Boolean get() = complete && spans.isEmpty()

    /**
     * Proves this layout belongs to [canonicalText] and, for a complete layout, that spans cover every
     * non-whitespace character. Returns this layout so callers can chain it.
     *
     * A text hash that does not match [canonicalText] throws [PageTextLayoutProvenanceException]; every other
     * failure is an [IllegalArgumentException].
     */
    fun verifiedAgainst(canonicalText: String): PageTextLayout {
        if (textSha256 != PdfModelRules.sha256Of(canonicalText)) {
            throw PageTextLayoutProvenanceException("a layout is verified against the canonical text it was made from")
        }
        spans.forEach { span -> requireSpanMatchesText(span, canonicalText) }
        if (complete) requireEveryVisibleCharacterCovered(canonicalText)
        return this
    }

    private fun requireWellFormedSpans() {
        require(spans.map { it.order }.toSet().size == spans.size) { "spans have unique reading orders" }
        spans.forEach { span ->
            val rectangle = span.rectangle
            require(
                rectangle.left >= -PdfModelRules.EDGE_TOLERANCE &&
                    rectangle.top >= -PdfModelRules.EDGE_TOLERANCE &&
                    rectangle.left + rectangle.width <= width + PdfModelRules.EDGE_TOLERANCE &&
                    rectangle.top + rectangle.height <= height + PdfModelRules.EDGE_TOLERANCE,
            ) { "a span rectangle lies inside the image" }
        }
        val inReadingOrder = spans.sortedBy { it.order }
        for (index in 1 until inReadingOrder.size) {
            require(inReadingOrder[index].start >= inReadingOrder[index - 1].endExclusive) {
                "spans follow reading order without overlapping"
            }
        }
    }

    private fun requireSpanMatchesText(span: PositionedTextSpan, text: String) {
        require(span.endExclusive <= text.length) { "a span ends inside the canonical text" }
        require(
            !PdfModelRules.splitsSurrogatePair(text, span.start) &&
                !PdfModelRules.splitsSurrogatePair(text, span.endExclusive),
        ) { "a span never splits a surrogate pair" }
        val codePoints = text.substring(span.start, span.endExclusive).codePoints().toArray()
        require(codePoints.any { !PdfModelRules.isBlankCodePoint(it) }) { "a span carries visible text" }
        when (span.granularity) {
            SpanGranularity.WORD -> require(codePoints.none { PdfModelRules.isBlankCodePoint(it) }) {
                "a WORD span is one whitespace-free token"
            }
            SpanGranularity.LINE -> require(codePoints.none { PdfModelRules.isLineBreakCodePoint(it) }) {
                "a LINE span is one visual line"
            }
        }
    }

    private fun requireEveryVisibleCharacterCovered(text: String) {
        val covered = BooleanArray(text.length)
        spans.forEach { span -> for (index in span.start until span.endExclusive) covered[index] = true }
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!PdfModelRules.isBlankCodePoint(codePoint)) {
                require(covered[index]) { "every non-whitespace character is covered by exactly one span" }
            }
            index += Character.charCount(codePoint)
        }
    }
}

/**
 * A path inside the managed artifact root, plus the SHA-256 of the file it names. A reference is confined by
 * construction: it is relative, has no empty, `.` or `..` segment, and contains no backslash or control
 * character, so it cannot name a file outside the directory it is resolved against.
 */
@Serializable
data class LayoutReference(val relativePath: String, val sha256: String) {
    init {
        require(relativePath.isNotEmpty() && !relativePath.startsWith("/")) { "a layout reference is a relative path" }
        require(relativePath.none { it == '\\' || it.isISOControl() }) { "a layout reference has no backslash or control character" }
        require(relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "a layout reference stays inside its artifact root"
        }
        require(PdfModelRules.isSha256(sha256)) { "a layout reference names its file by lowercase SHA-256" }
    }
}
