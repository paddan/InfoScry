package infoscry.jobs

import infoscry.extract.ExtractionSink
import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector

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
) {

    companion object {

        /**
         * The pipeline the application runs with.
         *
         * The sink is [ExtractionSink.NONE] until the durable unit store exists: an import copies and
         * detects, and its documents stay in `EXTRACTING` instead of being reported as searchable. Running
         * extractors into a store that cannot keep their output would spend OCR time and leave a document
         * looking extracted when nothing was stored.
         */
        fun production(): ImportPipeline = ImportPipeline(
            detector = MediaTypeDetector(),
            registry = ExtractorRegistry.production(),
            sink = ExtractionSink.NONE,
        )
    }
}
