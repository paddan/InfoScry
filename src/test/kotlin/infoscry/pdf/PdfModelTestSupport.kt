package infoscry.pdf

import java.security.MessageDigest

/**
 * An independent SHA-256 oracle. The model tests never ask production code to hash their own fixtures, so a
 * wrong production digest cannot make a test agree with itself.
 */
internal fun oracleSha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal const val CANONICAL_TEXT = "Åsa betalade 120 kronor."
internal const val IMAGE_WIDTH = 100
internal const val IMAGE_HEIGHT = 50
internal val IMAGE_SHA = oracleSha256("reading image bytes")
internal val OTHER_SHA = oracleSha256("some other artifact")

internal val PAGE_GEOMETRY = PdfPageGeometry(
    mediaX = 0.0,
    mediaY = 0.0,
    mediaWidth = 612.0,
    mediaHeight = 792.0,
    cropX = 0.0,
    cropY = 0.0,
    cropWidth = 612.0,
    cropHeight = 792.0,
    rotation = 0,
    userUnit = 1.0,
)

/** Image pixels to unrotated PDF space for a 100x50 raster covering a 612x792 point page, y flipped. */
internal val PAGE_TRANSFORM = ImageToPdf(a = 6.12, b = 0.0, c = 0.0, d = -15.84, e = 0.0, f = 792.0)

internal fun box(left: Double, top: Double, width: Double, height: Double) =
    TextRectangle(left = left, top = top, width = width, height = height)

internal fun word(start: Int, endExclusive: Int, order: Int, rectangle: TextRectangle) =
    PositionedTextSpan(start, endExclusive, rectangle, SpanGranularity.WORD, order)

/** Four whitespace-free tokens covering [CANONICAL_TEXT] with the whitespace gaps left uncovered. */
internal fun completeWords(): List<PositionedTextSpan> = listOf(
    word(0, 3, 0, box(4.0, 5.0, 20.0, 10.0)),
    word(4, 12, 1, box(28.0, 5.0, 40.0, 10.0)),
    word(13, 16, 2, box(72.0, 5.0, 14.0, 10.0)),
    word(17, 24, 3, box(35.0, 20.0, 30.0, 10.0)),
)

internal fun layoutOf(
    text: String = CANONICAL_TEXT,
    spans: List<PositionedTextSpan> = completeWords(),
    complete: Boolean = true,
    reasonCode: String? = null,
    textSha256: String = oracleSha256(text),
    imageSha256: String = IMAGE_SHA,
    version: Int = 1,
    width: Int = IMAGE_WIDTH,
    height: Int = IMAGE_HEIGHT,
    geometry: PdfPageGeometry = PAGE_GEOMETRY,
    transform: ImageToPdf = PAGE_TRANSFORM,
    positioningVersion: String = "local-anchor-1",
): PageTextLayout = PageTextLayout(
    version = version,
    textSha256 = textSha256,
    imageSha256 = imageSha256,
    width = width,
    height = height,
    geometry = geometry,
    transform = transform,
    spans = spans,
    complete = complete,
    reasonCode = reasonCode,
    positioningVersion = positioningVersion,
)
