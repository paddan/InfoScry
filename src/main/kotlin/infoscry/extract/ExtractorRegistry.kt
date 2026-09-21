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
         * extractor owns.
         *
         * The two e-book readers are one path with two entrances. [EpubExtractor] reads a book's own
         * container, and [CalibreBackedEbookExtractor] converts a Kindle or legacy container into an EPUB
         * and hands it to **the same instance**: one reader for the format, one set of section keys, and one
         * place where a citation is decided. A second EPUB reader behind the converter would be a second
         * answer to what a chapter is, and the two would drift.
         *
         * Both readers that need a raster read it with the same tool: the PDF reader hands a page that has
         * no text of its own to [TesseractOcr], and the image reader hands it the picture itself. One
         * instance serves both, so the languages, the timeout, and the artifact layout are decided in one
         * place. A build without Tesseract installed is not a build that cannot read documents: pages that
         * have their own text are extracted and cited as usual, and a page that needs OCR is reported
         * against the document with [TesseractOcr.NEEDS_TESSERACT_CODE] instead of quietly going missing.
         *
         * [tesseract] and [calibre] are parameters so that a test can prove this wiring by reading a page
         * and converting a book with stand-in tools: a registry that compiled against the real ones but
         * never reached them would pass every other test in the suite while reading nothing a person could
         * cite.
         */
        fun production(
            tesseract: TesseractOcr = TesseractOcr(),
            calibre: CalibreConverter = CalibreConverter(),
        ): ExtractorRegistry {
            val epub = EpubExtractor(tesseract::recognize)
            return ExtractorRegistry(
                listOf(
                    PlainTextExtractor(),
                    MarkdownExtractor(),
                    HtmlExtractor(),
                    CsvExtractor(),
                    WordExtractor(OfficeFormat.OOXML),
                    WordExtractor(OfficeFormat.LEGACY),
                    SpreadsheetExtractor(OfficeFormat.OOXML),
                    SpreadsheetExtractor(OfficeFormat.LEGACY),
                    PresentationExtractor(OfficeFormat.OOXML),
                    PresentationExtractor(OfficeFormat.LEGACY),
                    PdfExtractor(tesseract::recognize),
                    ImageExtractor(tesseract::recognize),
                    epub,
                    Fb2Extractor(),
                    CalibreBackedEbookExtractor(calibre, epub),
                ),
                TextualFallbackExtractor(),
            )
        }
    }
}
