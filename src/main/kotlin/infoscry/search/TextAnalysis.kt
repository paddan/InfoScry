package infoscry.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute

/**
 * The index's own analysis outside an index: the tokens a text field would hold for a piece of text. Code that has
 * to agree with search about what a word is (the glossary's keys, for one) asks here rather than approximating it.
 */
object TextAnalysis {

    /** Analyzers are safe to share across threads; this one is never closed, as it lives as long as the process. */
    private val analyzer: Analyzer = LuceneIndex.newAnalyzer()

    /** The analyzed tokens of [text], normalized exactly as the text field stores them. */
    fun tokens(text: String): List<String> =
        analyzer.tokenStream(LuceneSchema.FIELD_TEXT, text).use { stream ->
            val term = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            buildList { while (stream.incrementToken()) add(term.toString()) }.also { stream.end() }
        }
}
