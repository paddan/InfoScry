package infoscry.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The smallest source location a search result or citation can point at.
 *
 * The hierarchy is closed: every extractor must express its unit as one of these variants, so a
 * citation can always be rendered against the original document. Serial names are explicit because
 * these values are persisted as JSON in SQLite and must survive package renames.
 *
 * Positions are one-based where they address a human-visible page, slide, or text line, matching
 * how a reader counts them.
 */
@Serializable
sealed interface SourceLocation {

    @Serializable
    @SerialName("pdf_page")
    data class PdfPage(val page: Int) : SourceLocation {
        init {
            require(page >= 1) { "PdfPage.page is one-based, was $page" }
        }
    }

    @Serializable
    @SerialName("image")
    data class Image(val name: String) : SourceLocation {
        init {
            require(name.isNotBlank()) { "Image.name must not be blank" }
        }
    }

    @Serializable
    @SerialName("word_section")
    data class WordSection(
        val headingPath: List<String>,
        val paragraphStart: Int,
        val paragraphEnd: Int,
    ) : SourceLocation {
        init {
            require(headingPath.none { it.isBlank() }) {
                "WordSection.headingPath entries must not be blank"
            }
            require(paragraphStart >= 0) {
                "WordSection.paragraphStart must not be negative, was $paragraphStart"
            }
            require(paragraphEnd >= paragraphStart) {
                "WordSection.paragraphEnd ($paragraphEnd) must not precede paragraphStart ($paragraphStart)"
            }
        }
    }

    @Serializable
    @SerialName("spreadsheet_range")
    data class SpreadsheetRange(
        val sheet: String,
        val startCell: String,
        val endCell: String,
    ) : SourceLocation {
        init {
            require(sheet.isNotBlank()) { "SpreadsheetRange.sheet must not be blank" }
            require(startCell.isNotBlank()) { "SpreadsheetRange.startCell must not be blank" }
            require(endCell.isNotBlank()) { "SpreadsheetRange.endCell must not be blank" }
        }
    }

    @Serializable
    @SerialName("slide")
    data class Slide(val number: Int) : SourceLocation {
        init {
            require(number >= 1) { "Slide.number is one-based, was $number" }
        }
    }

    @Serializable
    @SerialName("text_lines")
    data class TextLines(val start: Int, val end: Int) : SourceLocation {
        init {
            require(start >= 1) { "TextLines.start is one-based, was $start" }
            require(end >= start) {
                "TextLines.end ($end) must not precede start ($start)"
            }
        }
    }

    @Serializable
    @SerialName("html_section")
    data class HtmlSection(val headingPath: List<String>) : SourceLocation {
        init {
            require(headingPath.none { it.isBlank() }) {
                "HtmlSection.headingPath entries must not be blank"
            }
        }
    }

    @Serializable
    @SerialName("ebook_section")
    data class EbookSection(
        val chapter: String?,
        val headingPath: List<String>,
        val spineIndex: Int,
    ) : SourceLocation {
        init {
            require(chapter == null || chapter.isNotBlank()) {
                "EbookSection.chapter must be null or non-blank"
            }
            require(headingPath.none { it.isBlank() }) {
                "EbookSection.headingPath entries must not be blank"
            }
            require(spineIndex >= 0) {
                "EbookSection.spineIndex is an index into the spine, was $spineIndex"
            }
        }
    }

    /**
     * The human-readable label of this location, as a search result or citation shows it.
     *
     * One pointer per variant, in English: page numbers and slides are one-based, spreadsheet addresses
     * keep the A1 spelling a reader recognises, and headings are joined into their ancestor chain so the
     * label says where the section sits, not just what its last heading is.
     */
    fun describe(): String = when (this) {
        is PdfPage -> "page $page"
        is Image -> "image $name"
        is Slide -> "slide $number"
        is TextLines -> "lines $start" + if (end == start) "" else "–$end"
        is SpreadsheetRange -> "$sheet!$startCell:$endCell"
        is HtmlSection -> headingPath.joinToString(" › ")
        is EbookSection -> {
            val labels = listOfNotNull(chapter) + headingPath
            labels.joinToString(" › ").ifEmpty { "section ${spineIndex + 1}" }
        }
        is WordSection -> headingPath.joinToString(" › ").ifEmpty { "document" } +
            " paragraphs $paragraphStart" + if (paragraphEnd == paragraphStart) "" else "–$paragraphEnd"
    }
}
