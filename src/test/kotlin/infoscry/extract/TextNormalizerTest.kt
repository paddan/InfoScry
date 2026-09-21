package infoscry.extract

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two text forms one unit carries, and the line the normalizer must not cross.
 *
 * `extracted` is evidence: it is what the parser produced, and a reader comparing a citation against the
 * original file has to see the same characters. `search` is the indexable form, where a byte-order mark,
 * a stray carriage return, or a control character left over from a scan would otherwise become a token
 * nobody can type. The normalizer produces the second from the first and must never write it back over
 * the first, because a corrected "evidence" text is no longer evidence.
 */
class TextNormalizerTest {

    @Test
    fun `extracted text keeps what the parser produced, search text is normalised`() {
        val raw = "F\u00F6rsta raden.\r\nAndra raden.\u0007\r\n"

        val normalised = TextNormalizer.normalize(raw)

        assertEquals(raw, normalised.extracted, "the evidence text must be the parser's output verbatim")
        assertFalse(normalised.search.contains("\r"), "search text kept a carriage return")
        assertFalse(normalised.search.contains("\u0007"), "search text kept a control character")
        assertEquals("F\u00F6rsta raden.\nAndra raden.\n", normalised.search)
    }

    @Test
    fun `search text is composed the way a keyboard composes it`() {
        val decomposed = "Arendet A\u030Aterremitteras."

        val normalised = TextNormalizer.normalize(decomposed)

        assertTrue(
            normalised.search.contains("\u00C5terremitteras"),
            "search text is not NFC: ${normalised.search}",
        )
        assertEquals(decomposed, normalised.extracted, "NFC was applied to the evidence text")
    }

    @Test
    fun `search text uses one line ending`() {
        val raw = "rad ett\r\nrad tva\rrad tre\nrad fyra"

        val normalised = TextNormalizer.normalize(raw)

        assertEquals("rad ett\nrad tva\nrad tre\nrad fyra", normalised.search)
    }

    @Test
    fun `search text keeps tabs and newlines while dropping other control characters`() {
        val raw = "Kolumn1\tKolumn2\n\u0000\u0008\u001B[31mFargad\u001B[0m\n"

        val normalised = TextNormalizer.normalize(raw)

        assertTrue(normalised.search.contains("Kolumn1\tKolumn2"), "a tab is layout, not noise")
        assertFalse(normalised.search.any { it.code < 32 && it != '\n' && it != '\t' })
        assertTrue(normalised.search.endsWith("\n"), "the final newline is kept")
    }

    @Test
    fun `a leading byte-order mark is an encoding artefact, not document text`() {
        val raw = "\uFEFFProtokoll"

        val normalised = TextNormalizer.normalize(raw)

        assertEquals("Protokoll", normalised.extracted)
        assertEquals("Protokoll", normalised.search)
    }

    @Test
    fun `a byte-order mark in the middle of the text is content`() {
        val raw = "a\uFEFFb"

        val normalised = TextNormalizer.normalize(raw)

        assertEquals(raw, normalised.extracted)
        assertTrue(normalised.search.contains('\uFEFF'), "only the leading mark is an artefact")
    }

    @Test
    fun `normalising is idempotent`() {
        val raw = "\uFEFFA\u030Angermanland\r\n\u0007Kolumn\tvarde\r\n"

        val once = TextNormalizer.normalize(raw)
        val twice = TextNormalizer.normalize(once.search)

        assertEquals(once.search, twice.search)
    }

    @Test
    fun `normalising empty text stays empty`() {
        val normalised = TextNormalizer.normalize("")

        assertEquals("", normalised.extracted)
        assertEquals("", normalised.search)
    }

    @Test
    fun `the search form keeps every word the evidence text had`() {
        val raw = "Beslut: arendet A\u030Aterremitteras.\r\nAnmalan\u0007 registrerades.\r\n"

        val normalised = TextNormalizer.normalize(raw)

        // A count alone would pass if normalisation swapped one word for another of the same length, so
        // the two lists are compared as words. Composition and the control character are folded out
        // first: those are exactly what the normalizer is allowed to change, and nothing else.
        val words = { text: String -> text.split(Regex("\\s+")).filter { it.isNotBlank() } }
        val folded = { text: String -> words(text).map(::withoutMarksOrControls) }
        assertEquals(
            folded(raw),
            folded(normalised.search),
            "normalisation changed the words: ${normalised.search}",
        )
        assertTrue(normalised.search.contains("Beslut:"))
        assertTrue(normalised.search.contains("Anmalan"))
        assertTrue(normalised.search.endsWith("registrerades.\n"))
    }

    /** A word with its accents decomposed, and with the control characters the normalizer strips gone. */
    private fun withoutMarksOrControls(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD).filter { character ->
            Character.getType(character) != Character.NON_SPACING_MARK.toInt() &&
                Character.getType(character) != Character.CONTROL.toInt()
        }
}
