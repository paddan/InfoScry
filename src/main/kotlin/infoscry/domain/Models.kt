package infoscry.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import java.nio.file.Path

// Every `createdAt`/`updatedAt` field in this file is an ISO-8601 instant string (for example
// `2026-09-21T07:45:12.345Z`). The persistence boundary parses and formats them, so the domain stays
// free of a clock or a platform date type.

/**
 * Persisted document states, in pipeline order. A document with some failed content units ends as
 * `COMPLETE_WITH_WARNINGS`; an interrupted extraction is never marked complete.
 */
@Serializable
enum class DocumentStatus {
    QUEUED,
    COPYING,
    EXTRACTING,
    OCR,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    COMPLETE,
    COMPLETE_WITH_WARNINGS,

    /**
     * The reading is done and a person still owes it a decision.
     *
     * This is not a failure and not a warning: no page that awaits a decision has searchable text, and a
     * document with no approved text at all would be a lie if it read `COMPLETE`. It is also not
     * `NEEDS_TOOL`, whose remedy is an installation — here the remedy is a decision, and it is the review
     * surface rather than the queue that offers it.
     */
    NEEDS_REVIEW,
    FAILED,
    CANCELLED,
    NEEDS_TOOL,
}

/**
 * How one unit's text was read.
 *
 * The distinction is the extractor's own statement about its work, not something read back off a unit's
 * confidence: a parser that read a page's text layer and a tool that recognised a scanned page are two
 * methods, and a confidence value is neither of them. It is persisted beside the unit so "how much of this
 * document was read by OCR" is answerable without guessing.
 */
@Serializable
enum class ExtractionMethod {
    /** The document's own container had the text: a parser read it. */
    DIRECT_TEXT,

    /** The text was recognised from a raster by the OCR tool. */
    OCR,
}

/**
 * Which directory a page image's reference resolves against.
 *
 * A page image is only ever a file inside the root its producer chose, so a reference without its root
 * names nothing. Both roots sit under the document's own directory.
 */
enum class SourceImageRoot {

    /** The document's `artifacts/` directory: a page this pipeline rendered, or a bounded copy it wrote. */
    ARTIFACTS,

    /** The directory holding the document's managed copy: a picture that *is* the document, read as itself. */
    MANAGED_COPY,
}

/**
 * The image one unit's text was read from.
 *
 * A reading is only honest about its own evidence if it says which pixels produced it, because the pixels
 * are not always the ones a person would assume: a picture whose declared raster is past the bound a tool
 * is handed is read from a *bounded copy* this pipeline wrote, and that copy is a different image from the
 * managed original. [relativePath] therefore travels with [root], the local dimensions and hash are the
 * artifact's own — read back from the file rather than taken from what a producer meant to write — and
 * [renderVersion] names the procedure that produced them, so a reading of a reduced copy is never
 * attributed to the original pixels.
 *
 * An absent provenance is not an empty one: it says no image was read at all, which is the truthful answer
 * for a page whose own text layer a parser read. It is not a claim of a zero-sized image, and it is not a
 * default that may be filled in from whatever image happens to be around later.
 *
 * A reference is confined to the root [root] names: it is relative, it does not climb out of that root
 * with a `..` segment, and it does not name the root itself. The rule is the one `PageImage.resolveInside`
 * applies when an artifact is opened, so a durable record cannot name something a later rebuild of the
 * page image would refuse — an escaping reference is rejected here, before persistence, rather than kept
 * as a claim about pixels no root of the document holds.
 */
data class SourceImageProvenance(
    val root: SourceImageRoot,
    /** The artifact's reference inside [root], which is the managed copy's own area for `MANAGED_COPY`. */
    val relativePath: String,
    val sha256: String,
    /** The artifact's measured pixel width, absent when no reader would state one. */
    val width: Int?,
    /** The artifact's measured pixel height, absent when no reader would state one. */
    val height: Int?,
    /** The version of the rendering or reduction that produced these pixels. */
    val renderVersion: Int,
) {

    init {
        require(relativePath.isNotBlank()) { "a source image reference must not be blank" }
        // The reference is what a reviewer-facing route serves an image from, so it is confined where it is
        // *written*: an absolute path, a `..` or `.` segment, or an empty one is refused here rather than
        // only where an image is later resolved. The schema enforces the same rule.
        require(isRootConfined(relativePath)) {
            "a source image reference is relative to its root and stays inside it, " +
                "was '${relativePath.take(MAX_REPORTED_REFERENCE_CHARACTERS)}'"
        }
        requireStaysInsideNamedRoot(relativePath)
        require(sha256.length == SHA256_HEX_LENGTH && sha256.all { character -> character.isHexCharacter() }) {
            "a source image names its artifact's SHA-256, was '${sha256.take(MAX_REPORTED_HASH_CHARACTERS)}'"
        }
        // Both dimensions or neither, exactly as a page image declares them: a measured artifact is
        // measured in both axes, and half a measurement is not a measurement.
        require((width == null) == (height == null)) { "a source image either has both dimensions or neither" }
        require(width == null || width > 0) { "a measured source image has a positive width, was $width" }
        require(height == null || height > 0) { "a measured source image has a positive height, was $height" }
        require(renderVersion > 0) { "a source image names the rendering that produced it, was $renderVersion" }
    }

    private companion object {
        const val SHA256_HEX_LENGTH: Int = 64
        const val MAX_REPORTED_HASH_CHARACTERS: Int = 16
        const val MAX_REPORTED_REFERENCE_CHARACTERS: Int = 80

        /**
         * Whether [reference] names something strictly inside whatever root it is resolved against.
         *
         * Either separator is treated as one, so a reference that is only a path on another platform is
         * refused too: a record that moves between machines must mean the same file everywhere.
         */
        fun isRootConfined(reference: String): Boolean {
            if ('\u0000' in reference) return false
            val first = reference.first()
            if (first == '/' || first == '\\') return false
            if (reference.length >= 2 && reference[1] == ':' && (first in 'a'..'z' || first in 'A'..'Z')) return false
            return reference.split('/', '\\').none { segment ->
                segment.isEmpty() || segment == "." || segment == ".."
            }
        }

        /** Hex as `HexFormat` writes it — the only form a digest in this pipeline has. */
        fun Char.isHexCharacter(): Boolean = this in '0'..'9' || this in 'a'..'f'

        /**
         * The rule a source image reference must satisfy, applied wherever one is built.
         *
         * The pair of root and reference is what confines an image — the root decides which directory the
         * reference resolves against — so a reference that is absolute, that climbs out with `..`, or that
         * names the root itself is refused. This is the same lexical rule `PageImage.resolveInside`
         * applies when an artifact is opened (it resolves and normalises against the root without touching
         * the filesystem), so a record and a rebuild of its page image agree on what the record names.
         */
        fun requireStaysInsideNamedRoot(reference: String) {
            val relative = Path.of(reference)
            require(!relative.isAbsolute) {
                "a source image reference is relative to the root it names, was '$reference'"
            }
            val normalized = relative.normalize()
            val climbsOut = normalized.any { segment -> segment.toString() == ".." }
            require(!climbsOut && normalized.toString().isNotEmpty()) {
                "a source image reference stays inside the root it names, was '$reference'"
            }
        }
    }
}

/**
 * What one unit of a document is, in the words a reader counts it in.
 *
 * It exists so progress can be honest about its own scope: a page count is a count of pages, a Word
 * document's units are sections, a workbook's are sheets. The label a reader sees is chosen by the UI from
 * this name, so the product copy stays in one place.
 */
@Serializable
enum class UnitKind {
    PAGE,
    SECTION,
    SLIDE,
    SHEET,
    LINE,
    IMAGE,
}

/** A collection is usable while `ACTIVE`; `DELETING` rejects new work and reads. */
@Serializable
enum class CollectionLifecycle {
    ACTIVE,
    DELETING,
}

/** The work kinds a persistent job can represent. Importing is the only kind in the first release. */
@Serializable
enum class JobType {
    IMPORT,

    /** Rebuilds the search index from the persisted text, so citations and vectors agree again. */
    REINDEX,

    /**
     * Reads existing managed documents again from their stored bytes, retaining their identities.
     *
     * It is a kind of its own rather than an import because a retry addresses document identifiers that
     * already exist and must never be classified as a duplicate of itself — see `RetryJobHandler`.
     */
    RETRY,

    /**
     * Reads pages of an already published document again and may replace its text through a reviewed
     * revision.
     *
     * It is a kind of its own for the same reason a retry is: retry eligibility is not broadened, and a
     * rescan of a successful document is not a retry of a failed one. The operation it drives lives in
     * `ocr_operations`, because a rescan outlives the attempt that started it.
     */
    RESCAN,
}

/**
 * The lifecycle of a job record. Stages inside a running job are reported separately as free text;
 * this enum only answers whether the job is still owned by a worker.
 */
@Serializable
enum class JobState {
    QUEUED,
    RUNNING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

/**
 * A collection is a manually created logical search boundary, not a mirror of a filesystem
 * directory. Each document belongs to exactly one collection.
 */
@Serializable
data class Collection(
    val id: CollectionId,
    val name: String,
    val ocrLanguages: String,
    val createdAt: String,
    val updatedAt: String,
    val description: String? = null,
    val lifecycle: CollectionLifecycle = CollectionLifecycle.ACTIVE,
    /**
     * The OCR settings future import and rescan attempts on this collection snapshot.
     *
     * Every one of them defaults to the behavior a pre-OCR information archive already had: Tesseract reading
     * the pages that have no text, the collection's existing languages, no image-model profile and no external
     * pages. That is what makes an unchanged collection, and a client that predates these fields, behave
     * exactly as they did. See [ocrSettings].
     */
    val ocrEngine: OcrEngine = OcrEngine.TESSERACT,
    val ocrImportMode: OcrImportMode = OcrImportMode.FILL_MISSING,
    val ocrTranscriptionProfileId: String? = null,
    val ocrReviewProfileId: String? = null,
    val ocrExternalPageLimit: Int = 0,
    /**
     * How many documents the collection holds. A derived listing value, counted from the document
     * rows when a collection is listed for display; it is never persisted on the collection and is
     * `0` for a collection that was not counted (for example one read back by id).
     */
    val documentCount: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "Collection.name must not be blank" }
        require(ocrLanguages.isNotBlank()) { "Collection.ocrLanguages must not be blank" }
    }

    /**
     * The collection's effective OCR settings as one value.
     *
     * Built rather than stored so there is only one definition of a collection's OCR settings, and so
     * constructing them validates the engine/profile agreement the store and the API also enforce.
     */
    fun ocrSettings(): CollectionOcrSettings = CollectionOcrSettings(
        language = ocrLanguages,
        engine = ocrEngine,
        importMode = ocrImportMode,
        transcriptionProfileId = ocrTranscriptionProfileId,
        reviewProfileId = ocrReviewProfileId,
        externalPageLimit = ocrExternalPageLimit,
    )
}

/**
 * One immutable imported original. `sha256` identifies the bytes; the unique constraint is
 * `(collectionId, sha256)`, so the same bytes may exist in two collections as two documents.
 */
@Serializable
data class Document(
    val id: DocumentId,
    val collectionId: CollectionId,
    val sha256: String,
    val mediaType: String,
    val originalFilename: String,
    @SerialName("original_path")
    val sourcePath: String,
    val sizeBytes: Long,
    val status: DocumentStatus,
    val createdAt: String,
    val updatedAt: String,
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    init {
        require(sha256.isNotBlank()) { "Document.sha256 must not be blank" }
        require(mediaType.isNotBlank()) { "Document.mediaType must not be blank" }
        require(originalFilename.isNotBlank()) { "Document.originalFilename must not be blank" }
        require(sourcePath.isNotBlank()) { "Document.sourcePath must not be blank" }
        require(sizeBytes >= 0) { "Document.sizeBytes must not be negative, was $sizeBytes" }
    }
}

/**
 * The smallest independently readable and citable part of a document. Both text forms are kept:
 * [extractedText] is the extractor's output, [searchText] is the normalized form used for indexing.
 *
 * [artifactRelativePath] names a file the extractor wrote for this unit under the document's artifact root —
 * the OCR word boxes of a scanned page, a converted book's normalized EPUB — and [artifactSha256] is what
 * makes the reference verifiable: an artifact that no longer matches is not the evidence the unit was
 * committed with, so the unit is read again rather than cited against it. [meanConfidence] is how sure an OCR
 * tool was about this unit and is absent for text a parser read rather than a tool recognised.
 */
@Serializable
data class ContentUnit(
    val id: ContentUnitId,
    val documentId: DocumentId,
    val ordinal: Int,
    val locator: SourceLocation,
    val extractedText: String,
    val searchText: String,
    val artifactRelativePath: String? = null,
    val artifactSha256: String? = null,
    val meanConfidence: Double? = null,
    /**
     * How this unit's text was read, or `null` for a unit committed before the pipeline recorded it.
     *
     * Absent is not "direct text": a legacy unit's method is unknown, and a count that showed it as zero
     * (or folded it into direct text) would invent a history the archive does not have.
     */
    val extractionMethod: ExtractionMethod? = null,
) {
    init {
        require(ordinal >= 0) { "ContentUnit.ordinal must not be negative, was $ordinal" }
        require((artifactRelativePath == null) == (artifactSha256 == null)) {
            "a unit's artifact reference is either complete or absent"
        }
    }
}

/**
 * A retrieval excerpt of one content unit. A chunk never crosses a unit boundary, so its citation
 * is always the unit's locator.
 *
 * [startOffset] and [endOffset] are character offsets into the unit's search text, and [text] is what the
 * embedder and the index receive — the span itself, preceded by the unit's repeated header when it has one.
 * [tokenCount] is the encoded passage length measured with the embedding tokenizer, including prefix and
 * special tokens, and [tokenStart] and [tokenEnd] are the first and last token indices of [text] inside that
 * passage. Tokens before [tokenStart] or after [tokenEnd] are the ones the encoder added, so the stored
 * offsets say both what the chunk covers and what its budget was spent on.
 *
 * The search text is the single offset space, and that is a ruled decision rather than an accident: chunk
 * offsets, chunk text and every rendered citation all address it, `ContentUnit` keeps the extracted text
 * beside it as evidence, and no mapping between the two is stored. The extracted text is the byte-faithful
 * reading and the managed original is what a reader opens, so nothing needs offsets into it. The invariant
 * that carries the decision is that slicing the unit's search text with [startOffset] and [endOffset] yields
 * [text] — for a unit that repeats a header, [text] minus that header. It is asserted in `ContentStoreTest`;
 * a chunk whose offsets address another space would cite the wrong characters.
 */
@Serializable
data class Chunk(
    val id: ChunkId,
    val contentUnitId: ContentUnitId,
    val ordinal: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val tokenCount: Int,
    val tokenStart: Int,
    val tokenEnd: Int,
) {
    init {
        require(ordinal >= 0) { "Chunk.ordinal must not be negative, was $ordinal" }
        require(startOffset >= 0) { "Chunk.startOffset must not be negative, was $startOffset" }
        require(endOffset >= startOffset) {
            "Chunk.endOffset ($endOffset) must not precede startOffset ($startOffset)"
        }
        require(tokenCount >= 0) { "Chunk.tokenCount must not be negative, was $tokenCount" }
        require(tokenStart in 0..<tokenCount) {
            "Chunk.tokenStart ($tokenStart) is outside a passage of $tokenCount tokens"
        }
        require(tokenEnd in tokenStart..<tokenCount) {
            "Chunk.tokenEnd ($tokenEnd) must follow tokenStart ($tokenStart) inside the passage"
        }
    }
}

/**
 * A durable unit of background work. [stage] is the human-readable stage inside a running job;
 * [completed] and [total] count its items. Errors are stored as a code plus an actionable message.
 *
 * [collectionId] and [payload] are what a worker needs to resume the job — the collection it belongs to
 * and the request it was enqueued with — and they mirror the `jobs` table's columns so a record round-
 * trips without a second row type. The payload is worker-internal: it will hold the paths the user
 * selected for an import, and it never leaves the process, so it is excluded from every wire format.
 *
 * [cancelRequested] is the durable cancellation request, which is deliberately separate from the state:
 * a stage may still be inside a platform call when the request arrives, so the state stays `RUNNING`
 * until the worker can honestly say the job stopped.
 */
@Serializable
data class Job(
    val id: JobId,
    val type: JobType,
    val state: JobState,
    val createdAt: String,
    val updatedAt: String,
    val collectionId: CollectionId? = null,
    val stage: String? = null,
    /**
     * The file the attempt is working on now, as its own name — never the path it was selected from.
     * A job that reads one file at a time reports the one it holds; the others leave it `null`.
     */
    val currentItem: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
    @Transient val payload: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val cancelRequested: Boolean = false,
) {
    init {
        require(completed >= 0) { "Job.completed must not be negative, was $completed" }
        require(total >= 0) { "Job.total must not be negative, was $total" }
    }
}
