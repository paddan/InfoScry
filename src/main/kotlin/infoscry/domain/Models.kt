package infoscry.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 */
@Serializable
data class ContentUnit(
    val id: ContentUnitId,
    val documentId: DocumentId,
    val ordinal: Int,
    val locator: SourceLocation,
    val extractedText: String,
    val searchText: String,
) {
    init {
        require(ordinal >= 0) { "ContentUnit.ordinal must not be negative, was $ordinal" }
    }
}

/**
 * A retrieval excerpt of one content unit. A chunk never crosses a unit boundary, so its citation
 * is always the unit's locator.
 *
 * [startOffset] and [endOffset] are character offsets into the unit's search text; [tokenCount] is
 * the encoded passage length measured with the embedding tokenizer, including prefix and special
 * tokens.
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
) {
    init {
        require(ordinal >= 0) { "Chunk.ordinal must not be negative, was $ordinal" }
        require(startOffset >= 0) { "Chunk.startOffset must not be negative, was $startOffset" }
        require(endOffset >= startOffset) {
            "Chunk.endOffset ($endOffset) must not precede startOffset ($startOffset)"
        }
        require(tokenCount >= 0) { "Chunk.tokenCount must not be negative, was $tokenCount" }
    }
}

/**
 * A durable unit of background work. [stage] is the human-readable stage inside a running job;
 * [completed] and [total] count its items. Errors are stored as a code plus an actionable message.
 *
 * [collectionId] and [payload] are what a worker needs to resume the job — the collection it belongs to
 * and the request it was enqueued with — and they mirror the `jobs` table's columns so a record round-
 * trips without a second row type.
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
    val payload: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val cancelRequested: Boolean = false,
) {
    init {
        require(completed >= 0) { "Job.completed must not be negative, was $completed" }
        require(total >= 0) { "Job.total must not be negative, was $total" }
    }
}
