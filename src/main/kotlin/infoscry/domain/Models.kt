package infoscry.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    FAILED,
    CANCELLED,
    NEEDS_TOOL,
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
) {
    init {
        require(name.isNotBlank()) { "Collection.name must not be blank" }
        require(ocrLanguages.isNotBlank()) { "Collection.ocrLanguages must not be blank" }
    }
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
