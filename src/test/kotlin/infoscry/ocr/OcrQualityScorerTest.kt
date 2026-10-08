package infoscry.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrQualityScorerTest {
    @Test
    fun `clean swedish prose scores high`() {
        val score = OcrQualityScorer.score(
            "Polisen har under dagen förhört flera vittnen i utredningen om mordet på statsministern.",
        )
        assertTrue(score != null && score >= 75.0, "clean prose scored $score")
    }

    @Test
    fun `garbled recognition output scores far below clean prose`() {
        val clean = OcrQualityScorer.score("Polisen har under dagen förhört flera vittnen i utredningen.")!!
        val noise = OcrQualityScorer.score("~|¤ x1q ;;; ¶§ b7k ## ¦ ªº ü| j2 ©® «» ]]")!!
        assertTrue(noise < clean - 40.0, "noise $noise vs clean $clean")
    }

    @Test
    fun `blank text has no score rather than a zero`() {
        assertNull(OcrQualityScorer.score(""))
        assertNull(OcrQualityScorer.score("   \n\t "))
    }

    @Test
    fun `score stays within zero to one hundred and is rounded to one decimal`() {
        val score = OcrQualityScorer.score("¤¤¤¤¤¤¤¤¤¤")!!
        assertEquals(0.0, score)
        val mid = OcrQualityScorer.score("Hej där 12ab världen")!!
        assertEquals(Math.round(mid * 10) / 10.0, mid)
    }

    @Test
    fun `document score is the character weighted mean of the scored pages`() {
        val pages = listOf(
            OcrQualityScorer.PageScore(chars = 100, score = 90.0),
            OcrQualityScorer.PageScore(chars = 300, score = 50.0),
        )
        assertEquals(60.0, OcrQualityScorer.documentScore(pages))
        assertNull(OcrQualityScorer.documentScore(emptyList()))
    }

    @Test
    fun `text with no letters is not penalised for lacking vowels`() {
        val digits = OcrQualityScorer.score("1 2 3 4 5 6 7 8")!!
        assertTrue(digits >= 90.0, "digits-only text scored $digits")
        val dots = OcrQualityScorer.score("........")!!
        assertTrue(dots >= 90.0, "punctuation-only text scored $dots")
        assertEquals(0.0, OcrQualityScorer.score("¤¤¤¤"))
    }

    @Test
    fun `only the first sample of a long text is judged`() {
        val long = "Polisen har under dagen förhört flera vittnen i utredningen. ".repeat(2000)
        assertEquals(OcrQualityScorer.score(long.take(OcrQualityScorer.SAMPLE_CHARS)), OcrQualityScorer.score(long))
    }

    @Test
    fun `the sample cut never splits a surrogate pair`() {
        val text = "a".repeat(OcrQualityScorer.SAMPLE_CHARS - 1) + "\uD83D\uDE00" + "b".repeat(100_000)
        assertEquals(
            OcrQualityScorer.score("a".repeat(OcrQualityScorer.SAMPLE_CHARS - 1)),
            OcrQualityScorer.score(text),
        )
    }

    @Test
    fun `document score of texts skips blank units and weights by sampled length`() {
        val prose = "Polisen har under dagen förhört flera vittnen i utredningen."
        assertEquals(
            OcrQualityScorer.documentScoreOfTexts(listOf(prose)),
            OcrQualityScorer.documentScoreOfTexts(listOf("   \n", prose, "")),
        )
        assertEquals(OcrQualityScorer.score(prose), OcrQualityScorer.documentScoreOfTexts(listOf(prose)))
    }

    @Test
    fun `document score of texts is unknown when every unit is blank or there are none`() {
        assertNull(OcrQualityScorer.documentScoreOfTexts(listOf("", "  \n ")))
        assertNull(OcrQualityScorer.documentScoreOfTexts(emptyList()))
    }
}
