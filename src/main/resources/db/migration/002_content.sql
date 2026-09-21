-- Content schema: the durable text of a document, the chunks built from it, and the per-unit checkpoints a
-- resumed attempt reuses.
--
-- Naming follows 001: English lower_snake_case, instants as ISO-8601 UTC strings written by the persistence
-- boundary, and a CHECK beside every enum-like column so an unknown value cannot reach the database.
--
-- Three things are deliberately kept apart, because each of them can change without the others changing:
--
--   * `content_units` is the text and structure of the document. Its identity is (document, ordinal), and an
--     ordinal keeps its id when the unit is read again, so a citation into it survives a re-import.
--   * `extraction_checkpoints` is what one attempt under one fingerprint already committed. A new fingerprint
--     (changed OCR languages, an upgraded tool) adds rows rather than overwriting evidence.
--   * `document_chunking` records how the chunks were measured. Re-chunking with another tokenizer or another
--     budget replaces chunks and this row and touches neither the text nor the checkpoints.

-- One citable unit of a document: its locator, both text forms, and the artifact it references.
CREATE TABLE IF NOT EXISTS content_units (
    id TEXT NOT NULL PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    locator_type TEXT NOT NULL CHECK (length(locator_type) > 0),
    locator TEXT NOT NULL CHECK (length(locator) > 0),
    extracted_text TEXT NOT NULL,
    search_text TEXT NOT NULL,
    artifact_relative_path TEXT,
    artifact_sha256 TEXT,
    mean_confidence REAL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (document_id, ordinal),
    -- An artifact reference is complete or absent: a path without a checksum could not be verified, and a
    -- checksum without a path names nothing to check.
    CHECK ((artifact_relative_path IS NULL) = (artifact_sha256 IS NULL))
);

-- One embeddable passage of a unit. The span is what the chunk covers of the unit's search text; the token
-- columns are where that text sits inside the encoded passage, so both the coverage and the budget spent on
-- the encoder's own tokens are readable from the row.
CREATE TABLE IF NOT EXISTS chunks (
    id TEXT NOT NULL PRIMARY KEY,
    content_unit_id TEXT NOT NULL REFERENCES content_units (id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    text TEXT NOT NULL CHECK (length(text) > 0),
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
    token_count INTEGER NOT NULL CHECK (token_count > 0),
    token_start INTEGER NOT NULL CHECK (token_start >= 0),
    token_end INTEGER NOT NULL CHECK (token_end >= token_start),
    created_at TEXT NOT NULL,
    UNIQUE (content_unit_id, ordinal)
);

-- What one extraction attempt committed for one unit under one fingerprint.
--
-- `outcome` is EXTRACTED or FAILED. A failed row is durable on purpose: an attempt that resumes does not
-- retry a unit whose failure is already known, so the failure is reported once rather than derived again from
-- the same bytes. The fingerprint is part of the identity, so the same unit read under other settings is a
-- different row.
CREATE TABLE IF NOT EXISTS extraction_checkpoints (
    id TEXT NOT NULL PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    fingerprint TEXT NOT NULL CHECK (length(fingerprint) > 0),
    unit_key TEXT NOT NULL CHECK (length(unit_key) > 0),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    outcome TEXT NOT NULL CHECK (outcome IN ('EXTRACTED', 'FAILED')),
    error_code TEXT,
    artifact_relative_path TEXT,
    artifact_sha256 TEXT,
    completed_at TEXT NOT NULL,
    UNIQUE (document_id, fingerprint, unit_key),
    CHECK ((artifact_relative_path IS NULL) = (artifact_sha256 IS NULL))
);

CREATE INDEX IF NOT EXISTS extraction_checkpoints_document ON extraction_checkpoints (document_id, fingerprint);

-- The terminal marker of a complete extraction pass: one row per document, replaced when the pass is repeated.
--
-- It is separate from the page checkpoints because it answers a different question. A checkpoint says a unit
-- is done; this says the extractor reached the end of its document and how many of its units failed, which is
-- what decides whether a document is complete or complete with warnings once it is indexed.
CREATE TABLE IF NOT EXISTS document_extractions (
    document_id TEXT NOT NULL PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    fingerprint TEXT NOT NULL CHECK (length(fingerprint) > 0),
    total_units INTEGER NOT NULL CHECK (total_units >= 0),
    failed_units INTEGER NOT NULL CHECK (failed_units >= 0),
    metadata TEXT NOT NULL,
    completed_at TEXT NOT NULL
);

-- How a document's chunks were built: the chunker's version, the tokenizer that measured them, and the budget
-- they were fitted to. Kept apart from extraction on purpose — re-chunking with another tokenizer replaces
-- chunks and this row and leaves the extracted text and the OCR checkpoints untouched.
CREATE TABLE IF NOT EXISTS document_chunking (
    document_id TEXT NOT NULL PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    chunker_version TEXT NOT NULL CHECK (length(chunker_version) > 0),
    tokenizer_id TEXT NOT NULL CHECK (length(tokenizer_id) > 0),
    max_sequence_tokens INTEGER NOT NULL CHECK (max_sequence_tokens > 0),
    overlap_tokens INTEGER NOT NULL CHECK (overlap_tokens >= 0),
    chunk_count INTEGER NOT NULL CHECK (chunk_count >= 0),
    -- How many units the pass that wrote this row walked. A pass that stopped halfway has chunks for a
    -- prefix of the document, and without this count the prefix would pass for the whole.
    unit_count INTEGER NOT NULL CHECK (unit_count >= 0),
    chunked_at TEXT NOT NULL
);
