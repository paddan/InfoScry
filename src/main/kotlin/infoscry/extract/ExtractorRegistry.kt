package infoscry.extract

import kotlinx.coroutines.flow.Flow

/**
 * No registered extractor can read this kind of document.
 *
 * An item that fails this way is not a broken file — it is a format the pipeline does not handle yet —
 * and [code] is what the item's outcome records so the reason is visible in the queue and the CLI rather
 * than being reported as a generic failure.
 */
class UnsupportedMediaTypeException(val mediaType: String) : IllegalArgumentException(
    "no extractor handles media type '$mediaType'",
) {

    val code: String = "UNSUPPORTED_MEDIA_TYPE"
}

/**
 * Which extractor a detected media type goes to.
 *
 * There is exactly one owner per media type: two extractors claiming the same one is a wiring mistake
 * that would otherwise surface as whichever happened to be registered last. [fallback] is the single last
 * resort for types no extractor claims — it is consulted only after the exact lookup misses, and only for
 * the types it says it can read.
 */
class ExtractorRegistry(
    extractors: List<DocumentExtractor>,
    private val fallback: DocumentExtractor? = null,
) {

    private val exact: Map<String, DocumentExtractor> = buildMap {
        extractors.forEach { extractor ->
            extractor.supportedMediaTypes.forEach { mediaType ->
                val previous = put(mediaType, extractor)
                require(previous == null) {
                    "two extractors claim media type '$mediaType': " +
                        "${previous?.javaClass?.simpleName} and ${extractor.javaClass.simpleName}"
                }
            }
        }
    }

    /** Every media type this registry can read, for diagnostics and for the doctor command. */
    fun claimedMediaTypes(): Set<String> = exact.keys + (fallback?.supportedMediaTypes ?: emptySet())

    /** The extractor for [mediaType], or a refusal that names the type nobody handles. */
    fun select(mediaType: String): DocumentExtractor =
        exact[mediaType]
            ?: fallback?.takeIf { mediaType in it.supportedMediaTypes }
            ?: throw UnsupportedMediaTypeException(mediaType)

    /** Streams one document's units from whichever extractor owns [mediaType]. */
    fun extract(input: ExtractionInput, mediaType: String): Flow<ExtractionEvent> =
        select(mediaType).extract(input)

    companion object {

        /**
         * The registry the first release ships with.
         *
         * Each extractor claims its own media types, so a document goes to the reader that understands
         * its structure, and [TextualFallbackExtractor] takes only the text containers no format
         * extractor owns. The Office, PDF, OCR, and e-book extractors are added by the tasks that own
         * them and register their own types here.
         */
        fun production(): ExtractorRegistry = ExtractorRegistry(
            listOf(
                PlainTextExtractor(),
                MarkdownExtractor(),
                HtmlExtractor(),
                CsvExtractor(),
            ),
            TextualFallbackExtractor(),
        )
    }
}
