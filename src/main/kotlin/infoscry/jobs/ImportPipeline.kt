package infoscry.jobs

import infoscry.AppContext
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
         * It takes the open data directory rather than a path because the sink writes the authoritative
         * database: the units an extractor produces are committed through the same store every reader uses,
         * inside the same permit the extraction boundary holds. A sink built from a path alone could only
         * open a second connection to the archive, which is the one thing the single-writer design forbids.
         */
        fun production(context: AppContext): ImportPipeline = ImportPipeline(
            detector = MediaTypeDetector(),
            registry = ExtractorRegistry.production(),
            sink = StoredUnitsSink(
                paths = context.paths,
                documents = context.documents,
                content = context.content,
            ),
        )
    }
}
