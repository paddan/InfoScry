package infoscry.extract

import infoscry.domain.SourceLocation
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The last resort: a document whose type is text, read as text.
 *
 * It exists so that a format nobody has written an extractor for is still searchable when its content is
 * text, which is the common case for anything Tika identifies as a text type and for the minority of
 * documents that are JSON, XML, or CSV under an unknown container. It deliberately does **not** claim
 * containers: a media type outside [supportedMediaTypes] is refused by the registry, so a binary blob is
 * reported as unreadable instead of being turned into mojibake that looks like evidence.
 *
 * The whole document becomes one unit with a line-range locator: there is no structure to cite more
 * precisely, and inventing one would put a wrong citation in front of a reader. Format-specific
 * extractors replace this one for their media types as they are added.
 */
class TikaFallbackExtractor : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = TEXTUAL_MEDIA_TYPES

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val key = WHOLE_DOCUMENT_KEY
        if (!input.isCommitted(key)) {
            // The read is inside the boundary on purpose: the contract is that one unit's production and
            // its delivery happen under the same permit, so a delete or a rebuild cannot land between the
            // text this unit describes and the checkpoint that describes it.
            input.boundary.unit {
                val text = Files.readString(input.managedPath, Charsets.UTF_8).removePrefix(BYTE_ORDER_MARK)
                emit(
                    ExtractionEvent.UnitReady(
                        key = key,
                        ordinal = 0,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.TextLines(start = 1, end = lineCount(text)),
                            extractedText = text,
                            // No normalizer runs yet: the text and its searchable form are the same until
                            // the text extractors' task supplies one.
                            searchText = text,
                        ),
                    ),
                )
            }
        }
        input.boundary.unit {
            emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1))
        }
    }

    private fun lineCount(text: String): Int = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1

    companion object {

        /** The one unit a whole-document reader produces, and the key a resume matches on. */
        const val WHOLE_DOCUMENT_KEY = "document"

        /** The types a text reader can honestly claim. */
        val TEXTUAL_MEDIA_TYPES: Set<String> = setOf(
            "text/plain",
            "text/csv",
            "text/markdown",
            "text/x-markdown",
            "text/xml",
            "application/json",
            "application/xml",
        )

        private const val BYTE_ORDER_MARK = "\uFEFF"
    }
}
