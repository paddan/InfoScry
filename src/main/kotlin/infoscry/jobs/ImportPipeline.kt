package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.DocumentId
import infoscry.extract.ExtractionSink
import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.PageImageSupport
import infoscry.ocr.CandidateRevisionSink
import infoscry.ocr.OcrImportMode

/**
 * What an import runs with: how a file's type is read, which extractor handles that type, and where the
 * extracted units go.
 *
 * The three travel together because they are one pipeline — a registry whose extractors expect a media
 * type from a different detector, or a sink that cannot store what an extractor produces, is a wiring
 * mistake rather than a configuration. Keeping them in one object also means a test can substitute the
 * whole pipeline (fake extractors, a real detector, a recording sink) without the import path having to
 * grow a seam per collaborator.
 */
class ImportPipeline(
    val detector: MediaTypeDetector,
    val registry: ExtractorRegistry,
    val sink: ExtractionSink,
    /**
     * The sink a check-and-improve attempt stages its pages into, opened for the document it read.
     *
     * It is asked for a document rather than being a sink because a candidate revision is opened for exactly
     * one document, and a pipeline is built before any file has one: which sink a reading is committed
     * through can only be answered once the file is copied and its document exists. Null is a pipeline that
     * stages nothing — every attempt commits through [sink], which is where an attempt committed before this
     * seam existed and what a pipeline wired with a recording sink still does.
     */
    private val candidateSinkFor: ((DocumentId) -> ExtractionSink)? = null,
) {

    /**
     * Where one document's units go, given what its attempt is for and what its format can be read from.
     *
     * A check-and-improve attempt re-reads *page images* a document already has text for, so what it reads is
     * a *proposal* about them rather than the text the document publishes: it is staged as a candidate
     * revision and nothing published changes. That is why the sink follows the selected extractor's
     * [pageImageSupport] and not the mode alone: a format that reports no page images has nothing the mode
     * could read again, so its units commit through the ordinary [sink] — its ordinary searchable extraction —
     * instead of being stranded as a proposal no page image could ever justify. A fill-missing attempt
     * completes the document's published content, which is what every import did before the two were told
     * apart, and it never stages either.
     */
    fun sinkFor(
        documentId: DocumentId,
        mode: OcrImportMode,
        pageImageSupport: PageImageSupport,
    ): ExtractionSink =
        if (mode == OcrImportMode.CHECK_AND_IMPROVE && pageImageSupport is PageImageSupport.Supported) {
            candidateSinkFor?.invoke(documentId) ?: sink
        } else {
            sink
        }

    companion object {

        /**
         * The pipeline the application runs with.
         *
         * It takes the open data directory rather than a path because the sink writes the authoritative
         * database: the units an extractor produces are committed through the same store every reader uses,
         * inside the same permit the extraction boundary holds. A sink built from a path alone could only
         * open a second connection to the archive, which is the one thing the single-writer design forbids.
         */
        fun production(context: AppContext): ImportPipeline {
            val committed = StoredUnitsSink(
                paths = context.paths,
                documents = context.documents,
                content = context.content,
            )
            return ImportPipeline(
                detector = MediaTypeDetector(),
                registry = ExtractorRegistry.production(
                    // The image-model engine of an admitted attempt: it resolves the profile revision the job's
                    // snapshot names, and every page it would send is asked for a permit against the allowance
                    // that job owns, so nothing leaves this machine before the scope was approved.
                    llm = { dispatch ->
                        infoscry.ocr.LlmOcr(
                            revisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
                            permits = dispatch,
                            calls = dispatch?.let { authority -> authority::attemptAboutToBeSent },
                        )
                    },
                ),
                sink = committed,
                // What a check-and-improve attempt reads is held for review instead of replacing the
                // document's text: its pages become a candidate revision of that document, and which document
                // that is is a fact of the file rather than of the pipeline.
                candidateSinkFor = { documentId ->
                    CandidateRevisionSink(
                        revisions = context.revisions,
                        documentId = documentId,
                        provenance = PROVENANCE_CHECK_AND_IMPROVE,
                    )
                },
            )
        }

        /**
         * Why a candidate revision of an import exists: a check-and-improve attempt read the document again.
         *
         * It is a value of its own rather than the import's own publication provenance, because this revision
         * is not what the document publishes and never becomes it without a person's decision.
         */
        const val PROVENANCE_CHECK_AND_IMPROVE = "IMPORT_CHECK_AND_IMPROVE"
    }
}
