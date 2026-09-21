package infoscry.extract

import java.text.Normalizer

/**
 * The two forms of one piece of extracted text.
 *
 * [extracted] is the evidence: what the parser produced, kept as it produced it, because a reader
 * checking a citation against the original file has to find the same characters there. [search] is the
 * form the index holds, where the artefacts of encoding and of a scan are removed so that a query can
 * match what a person would actually type.
 */
data class NormalizedText(val extracted: String, val search: String)

/**
 * Turns one parser's output into the two forms a unit carries.
 *
 * The normalizer is deliberately narrow. It fixes the things that are wrong for reasons that have nothing
 * to do with the document — a byte-order mark left by whatever wrote the file, a carriage return from a
 * Windows line ending, a control character from a scan — and it does nothing else. It does not
 * spell-correct, does not repair OCR, does not reflow, and does not touch the words: a text that has been
 * "improved" is no longer evidence, and a citation that points at a sentence somebody silently rewrote is
 * worse than no citation at all.
 *
 * The search form keeps tabs and newlines. A tab is layout a CSV or a table depends on and a newline
 * separates the lines a [`SourceLocation.TextLines`] range is counted in; everything else in the control
 * category is noise from the source or from the tool that read it.
 */
object TextNormalizer {

    /** The byte-order mark a leading character may carry: an encoding artefact, never document text. */
    private const val BYTE_ORDER_MARK = '\uFEFF'

    /**
     * The evidence text as the parser produced it, and the searchable form derived from it.
     *
     * A single leading byte-order mark is dropped from both forms: it is an artefact of how the file was
     * encoded, and leaving it in would put an invisible character at the start of every document. A mark
     * anywhere else is content and stays where it is.
     */
    fun normalize(extractedText: String): NormalizedText {
        val extracted = extractedText.removePrefix(BYTE_ORDER_MARK.toString())
        return NormalizedText(extracted = extracted, search = searchForm(extracted))
    }

    private fun searchForm(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        val composed = Normalizer.normalize(unified, Normalizer.Form.NFC)
        return composed.filter { character ->
            character == '\n' || character == '\t' || Character.getType(character) != CONTROL
        }
    }

    private val CONTROL: Int = Character.CONTROL.toInt()
}
