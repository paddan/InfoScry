package infoscry.pdf

import java.security.MessageDigest
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.io.RandomAccessReadBuffer

/**
 * The one font every selectable text layer is written with (ticket 05).
 *
 * The font is the pinned, redistributable Noto Sans Regular 2.015 (Noto Project Authors, SIL Open Font License 1.1),
 * committed as `src/main/resources/fonts/NotoSans-Regular.ttf`, 825628 bytes, SHA-256 [SHA256]. Its license text is
 * `src/main/resources/fonts/OFL-1.1-NotoSans.txt`. A machine-installed font is never used: the resource is verified
 * against [SHA256] before any use, and a mismatch is a packaging error.
 *
 * Unicode coverage is read from the font's own cmap. A character the font cannot draw is reported by
 * [unsupportedCodePoints]; the writer turns that into an explicit page reason and never substitutes a glyph.
 */
internal object PdfFontResolver {

    const val RESOURCE: String = "/fonts/NotoSans-Regular.ttf"
    const val LICENSE_RESOURCE: String = "/fonts/OFL-1.1-NotoSans.txt"
    const val FONT_NAME: String = "Noto Sans Regular 2.015"
    const val SHA256: String = "f5f552c8c5edb61fe6efb824baf4d4de47b1a8689ab4925ff43f7bd6a4ebece5"
    const val BYTE_COUNT: Int = 825628

    private val fontBytes: ByteArray by lazy {
        val bytes = PdfFontResolver::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: error("the pinned font resource $RESOURCE is missing from the build")
        check(bytes.size == BYTE_COUNT && sha256Hex(bytes) == SHA256) {
            "the pinned font resource is not the verified $FONT_NAME file"
        }
        bytes
    }

    private val parsed: TrueTypeFont by lazy { TTFParser().parse(RandomAccessReadBuffer(fontBytes)) }

    /** The verified font file. Callers load it into their own document; this object never mutates it. */
    fun bytes(): ByteArray = fontBytes

    /** The SHA-256 of [bytes], which the writer records as the manifest's font identity. */
    fun sha256(): String = SHA256

    /** The code points of [text] that this font has no glyph for, in order of first appearance. */
    fun unsupportedCodePoints(text: String): Set<Int> {
        val cmap = parsed.unicodeCmapLookup
        return text.codePoints().toArray().filter { codePoint -> cmap.getGlyphId(codePoint) == 0 }.toCollection(LinkedHashSet())
    }

    /** The font's ascent above the baseline, in em, from its horizontal header. */
    val ascentEm: Double by lazy { parsed.horizontalHeader.ascender.toDouble() / parsed.header.unitsPerEm }

    /** The font's descent below the baseline, in em, as a positive number. */
    val descentEm: Double by lazy { -parsed.horizontalHeader.descender.toDouble() / parsed.header.unitsPerEm }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
