package infoscry.search

/**
 * The field names and identity constants of the search index, in one place so indexing, searching and
 * a rebuild that changes the layout all agree on the same spelling.
 *
 * One Lucene document per searchable chunk: analyzing offsets against a whole document would make a
 * result cite a document when the question matched a single excerpt, and the plan's citations are exact
 * to a page, section, cell range or chapter — which is a chunk, not a document.
 */
object LuceneSchema {

    /** The layout version of this schema. Bump it when a field name or a field's meaning changes. */
    const val SCHEMA_VERSION: Int = 1

    // Filter fields: exact, unanalyzed, and stored so a search hit can name its identity without a
    // database round-trip.
    const val FIELD_DOCUMENT_ID: String = "document_id"
    const val FIELD_COLLECTION_ID: String = "collection_id"
    const val FIELD_CONTENT_UNIT_ID: String = "content_unit_id"

    // Analyzed text: what keyword search indexes and what a result snippet is read back from.
    const val FIELD_TEXT: String = "text"
    const val FIELD_SNIPPET: String = "snippet"

    // Stored citation data: the exact source location the chunk came from, plus a human label, so a
    // result can jump to the right page or cell without another query.
    const val FIELD_LOCATOR: String = "locator"
    const val FIELD_LOCATOR_LABEL: String = "locator_label"

    // Range data: the chunk's ordinal within its unit, stored so a result can order excerpts.
    const val FIELD_ORDINAL: String = "chunk_ordinal"

    // The embedding vector. The dimension is not a constant here because it is part of the index's
    // identity — an index built with a different model than the one serving queries must refuse to
    // answer rather than compare 512-dimension and 768-dimension vectors.
    const val FIELD_VECTOR: String = "vector"

    /** The values stored in the index's commit user-data map, namespaced to avoid collisions. */
    const val KEY_SCHEMA_VERSION: String = "infoscry.schema_version"
    const val KEY_MODEL: String = "infoscry.model"
    const val KEY_MODEL_REVISION: String = "infoscry.model_revision"
    const val KEY_MODEL_FINGERPRINT: String = "infoscry.model_fingerprint"
    const val KEY_DIMENSION: String = "infoscry.dimension"
}