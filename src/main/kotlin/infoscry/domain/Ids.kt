package infoscry.domain

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Typed identifiers. Each type rejects a blank value so a missing identifier cannot travel
 * as an empty string, and the distinct types keep a document identifier from being passed
 * where a content-unit identifier is expected.
 */
@JvmInline
@Serializable
value class CollectionId(val value: String) {
    init {
        require(value.isNotBlank()) { "CollectionId must not be blank" }
    }

    companion object {
        fun new(): CollectionId = CollectionId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId must not be blank" }
    }

    companion object {
        fun new(): DocumentId = DocumentId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class ContentUnitId(val value: String) {
    init {
        require(value.isNotBlank()) { "ContentUnitId must not be blank" }
    }

    companion object {
        fun new(): ContentUnitId = ContentUnitId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class ChunkId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChunkId must not be blank" }
    }

    companion object {
        fun new(): ChunkId = ChunkId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class JobId(val value: String) {
    init {
        require(value.isNotBlank()) { "JobId must not be blank" }
    }

    companion object {
        fun new(): JobId = JobId(UUID.randomUUID().toString())
    }
}
