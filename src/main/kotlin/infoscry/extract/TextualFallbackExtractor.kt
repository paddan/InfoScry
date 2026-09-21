package infoscry.extract

import infoscry.domain.SourceLocation
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The last resort: a container whose content is text, read as text.
 *
 * It exists so that a format nobody has written an extractor for is still searchable when its content is
 * text, which is the common case for XML and JSON. It deliberately claims **only** those types: every
 * format with a real extractor owns its own media type now, and a binary blob is reported as unreadable
 * rather than being turned into mojibake that looks like evidence.
 *
 * The whole document becomes one unit with a line-range locator: there is no structure to cite more
 * precisely, and inventing one would put a wrong citation in front of a reader. Because the unit is the
 * whole document, the file is refused above [MAX_TEXT_DOCUMENT_BYTES] instead of being read into memory: a
 * container this large cannot be split into citable units by a reader that does not understand it, and
 * pretending otherwise would trade an honest failure for an out-of-memory kill.
 */
class TextualFallbackExtractor : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = TEXTUAL_MEDIA_TYPES

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        val key = DOCUMENT_REFUSED_KEY
        if (!input.isCommitted(key) && Files.size(input.managedPath) > MAX_TEXT_DOCUMENT_BYTES) {
            refuseDocument(input, key, DOCUMENT_TOO_LARGE_CODE)
            return@flow
        }
        if (!input.isCommitted(key)) {
            // The read is inside the boundary on purpose: the contract is that one unit's production and
            // its delivery happen under the same permit, so a delete or a rebuild cannot land between the
            // text this unit describes and the checkpoint that describes it.
            input.boundary.unit {
                val text = Files.readString(input.managedPath, Charsets.UTF_8)
                val normalised = TextNormalizer.normalize(text)
                emit(
                    ExtractionEvent.UnitReady(
                        key = key,
                        ordinal = 0,
                        unit = ContentUnitDraft(
                            locator = SourceLocation.TextLines(start = 1, end = lineCount(normalised.extracted)),
                            extractedText = normalised.extracted,
                            searchText = normalised.search,
                        ),
                    ),
                )
            }
        }
        input.boundary.unit {
            emit(ExtractionEvent.Finished(metadata = mapOf(EXTRACTOR_METADATA to "text-fallback"), totalUnits = 1))
        }
    }

    private fun lineCount(text: String): Int = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1

    companion object {

        /** The types a text reader can honestly claim: the containers no format extractor owns. */
        val TEXTUAL_MEDIA_TYPES: Set<String> = setOf(
            "text/xml",
            "application/xml",
            "application/json",
        )
    }
}
