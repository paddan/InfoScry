-- The InfoScry baseline schema. There is exactly one version and no upgrade path: when the schema
-- changes, this file is edited in place (see SchemaMigrator).
--
-- Naming: every identifier and column is English and lower_snake_case. Instants are ISO-8601 UTC
-- strings written by the persistence boundary. Enum columns carry a CHECK that mirrors the Kotlin
-- enum of the same name, so an unknown value cannot reach the database. A new archive starts with no
-- collection: every import names a collection a person chose, so nothing is seeded.

-- One row per applied schema version. `PRAGMA user_version` carries the same number; this table is
-- the durable audit trail of when it was applied.
CREATE TABLE schema_version (
    version INTEGER NOT NULL PRIMARY KEY,
    applied_at TEXT NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- Collections, documents, jobs, import items, deletion records
-- ---------------------------------------------------------------------------------------------

-- A collection is a manually created logical search boundary, not a mirror of a filesystem
-- directory. `lifecycle` is separate from document status: DELETING rejects new work and reads
-- while the deletion operation removes the collection's files and rows.
--
-- The OCR columns are the collection's default OCR settings. A collection names profiles by id; the
-- referenced revisions survive a profile edit because an edit adds a revision row rather than
-- replacing one, and deleting a profile disables it instead of removing it.
CREATE TABLE collections (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    description TEXT,
    ocr_languages TEXT NOT NULL DEFAULT 'eng' CHECK (length(trim(ocr_languages)) > 0),
    lifecycle TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE', 'DELETING')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    ocr_engine TEXT NOT NULL DEFAULT 'TESSERACT'
        CHECK (ocr_engine IN ('TESSERACT', 'SURYA', 'LLM')),
    ocr_import_mode TEXT NOT NULL DEFAULT 'FILL_MISSING'
        CHECK (ocr_import_mode IN ('FILL_MISSING', 'CHECK_AND_IMPROVE')),
    ocr_transcription_profile_id TEXT REFERENCES ocr_profiles (id),
    ocr_review_profile_id TEXT REFERENCES ocr_profiles (id),
    ocr_external_page_limit INTEGER NOT NULL DEFAULT 0 CHECK (ocr_external_page_limit >= 0)
);

-- Collection names are unique case-insensitively, so "Default" and "default" cannot coexist. The
-- index is the authority; the store translates its violation into a typed error.
CREATE UNIQUE INDEX collections_name_unique ON collections (name COLLATE NOCASE);

-- One immutable imported original. sha256 identifies the bytes; the unique constraint is per
-- collection, so the same bytes may exist in two collections as two documents.
--
-- NEEDS_REVIEW is a lifecycle state of its own: the document's pages wait for a person's decision.
-- It is distinct from COMPLETE (which claims the reading was accepted), COMPLETE_WITH_WARNINGS
-- (units that could not be read) and NEEDS_TOOL (a failure whose remedy is an installation).
CREATE TABLE documents (
    id TEXT NOT NULL PRIMARY KEY,
    collection_id TEXT NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    sha256 TEXT NOT NULL CHECK (length(sha256) > 0),
    media_type TEXT NOT NULL CHECK (length(media_type) > 0),
    original_filename TEXT NOT NULL CHECK (length(original_filename) > 0),
    original_path TEXT NOT NULL CHECK (length(original_path) > 0),
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    status TEXT NOT NULL CHECK (
        status IN (
            'QUEUED', 'COPYING', 'EXTRACTING', 'OCR', 'CHUNKING', 'EMBEDDING', 'INDEXING',
            'COMPLETE', 'COMPLETE_WITH_WARNINGS', 'NEEDS_REVIEW', 'FAILED', 'CANCELLED', 'NEEDS_TOOL'
        )
    ),
    title TEXT,
    author TEXT,
    language TEXT,
    error_code TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (collection_id, sha256)
);

CREATE INDEX documents_collection_status ON documents (collection_id, status);

-- A durable unit of background work. `stage` is free text reported while the job runs; `state` is
-- whether a worker still owns it. `payload` is the job type's own JSON. Cancellation is requested
-- in the database first so a restart cannot lose the request. `collection_id` is nullable because
-- some job types (reindexing) are not owned by one collection.
--
-- RETRY and RESCAN are kinds of their own, not imports: a retry reads an unfinished document again
-- from its managed copy, and a rescan re-reads an already published document from page images and
-- may replace its text through a reviewed revision.
--
-- `current_item` is the file an import is working on right now, named by its own last segment and
-- never by the path it was selected from: the source paths a user picked do not cross the API
-- boundary. A job that is not reading a file leaves it NULL.
CREATE TABLE jobs (
    id TEXT NOT NULL PRIMARY KEY,
    collection_id TEXT REFERENCES collections (id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('IMPORT', 'REINDEX', 'RETRY', 'RESCAN')),
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'COMPLETE', 'FAILED', 'CANCELLED')),
    stage TEXT,
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed >= 0),
    total INTEGER NOT NULL DEFAULT 0 CHECK (total >= 0),
    payload TEXT,
    error_code TEXT,
    error_message TEXT,
    cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK (cancel_requested IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    current_item TEXT
);

CREATE INDEX jobs_state ON jobs (state);
CREATE INDEX jobs_collection_state ON jobs (collection_id, state);

-- One row per selected path inside an import job. `item_key` is deterministic for the job, so
-- re-enumerating a directory never queues the same path twice. `document_id` is written as soon as
-- the managed copy exists, so a restart resumes that document instead of re-importing the path.
--
-- A file whose document was deleted is CANCELLED, and the import handler obeys that row on every
-- later attempt instead of copying the deleted target again.
--
-- `created_document` records whether the file's own copy created the document it names. A duplicate
-- and a fresh copy both leave an item naming a document, and no clock or path comparison can tell
-- them apart; the deletion sweep may only remove documents a cancelled file created, never one it
-- merely found.
CREATE TABLE import_items (
    id TEXT NOT NULL PRIMARY KEY,
    job_id TEXT NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    item_key TEXT NOT NULL CHECK (length(item_key) > 0),
    source_path TEXT NOT NULL CHECK (length(source_path) > 0),
    document_id TEXT REFERENCES documents (id) ON DELETE SET NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('PENDING', 'IMPORTED', 'DUPLICATE', 'FAILED', 'CANCELLED')),
    error_code TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_document INTEGER NOT NULL DEFAULT 0 CHECK (created_document IN (0, 1)),
    UNIQUE (job_id, item_key)
);

CREATE INDEX import_items_job_outcome ON import_items (job_id, outcome);

-- The durable record of a collection or document deletion. `collection_id` is deliberately NOT a
-- foreign key: this row must survive the cascade that removes the collection, because it is what
-- recovery reads to finish moving files, deleting rows and index entries, and purging the trash
-- directory.
--
-- `error_code` is a small, stable vocabulary a client maps to a remedy; `last_error` keeps the
-- detail, which names absolute paths inside the data directory and must not cross the wire.
CREATE TABLE deletion_operations (
    id TEXT NOT NULL PRIMARY KEY,
    collection_id TEXT NOT NULL CHECK (length(collection_id) > 0),
    collection_name TEXT NOT NULL CHECK (length(collection_name) > 0),
    trash_basename TEXT NOT NULL CHECK (length(trash_basename) > 0),
    managed_originals_existed INTEGER NOT NULL CHECK (managed_originals_existed IN (0, 1)),
    phase TEXT NOT NULL CHECK (
        phase IN ('PREPARED', 'FILES_MOVED', 'DB_DELETED', 'INDEX_DELETED', 'DONE')
    ),
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    error_code TEXT,
    kind TEXT NOT NULL DEFAULT 'COLLECTION' CHECK (kind IN ('COLLECTION', 'DOCUMENT'))
);

CREATE INDEX deletion_operations_phase ON deletion_operations (phase);

-- The explicit targets of a document deletion, persisted before anything destructive happens. The
-- rows are the durable guard a later import resume obeys. `document_id` is deliberately not a
-- foreign key: the target row must outlive the document it names, because recovery reads it to find
-- parked files and a resumed import reads it to refuse copying the document again.
--
-- `managed_existed` lets recovery tell "this document never had a managed directory" from "the
-- managed directory disappeared", which is the difference between finishing a deletion and reporting
-- an unsafe state that must not discard files.
--
-- `source_path` is the path the document was imported from, so a document copied from it by an
-- import that was in flight when the deletion was admitted cannot survive the deletion. It is not a
-- path tombstone: nothing consults it once the operation is DONE.
CREATE TABLE document_deletion_targets (
    operation_id TEXT NOT NULL REFERENCES deletion_operations (id) ON DELETE CASCADE,
    document_id TEXT NOT NULL CHECK (length(document_id) > 0),
    managed_existed INTEGER NOT NULL CHECK (managed_existed IN (0, 1)),
    source_path TEXT,
    PRIMARY KEY (operation_id, document_id)
);

-- The guard is read per document, not per operation: a stage that would write a document asks
-- whether anything is deleting it.
CREATE INDEX document_deletion_targets_document ON document_deletion_targets (document_id);

-- ---------------------------------------------------------------------------------------------
-- Content: the durable text of a document, its chunks, and the per-unit checkpoints
-- ---------------------------------------------------------------------------------------------
--
-- Three things are deliberately kept apart, because each can change without the others:
--
--   * `content_units` is the text and structure of the document. Its identity is (document,
--     ordinal), and an ordinal keeps its id when the unit is read again, so a citation survives a
--     re-import.
--   * `extraction_checkpoints` is what one attempt under one fingerprint already committed. A new
--     fingerprint (changed OCR languages, an upgraded tool) adds rows rather than overwriting.
--   * `document_chunking` records how the chunks were measured. Re-chunking with another tokenizer
--     or budget replaces chunks and this row and touches neither the text nor the checkpoints.

-- One citable unit of a document: its locator, both text forms, and the artifact it references.
--
-- `extraction_method` is stored by the extractor that produced the unit, never inferred from mean
-- confidence: a document is commonly read both ways (one page has a text layer, the next is a scan).
-- `page_approval` is the page's review decision, stored beside the unit because it is a fact about
-- the reading of that page rather than about the document's attempt. NULL means no review was
-- required (the page's own text layer, a text-only format) and is not a pending decision.
CREATE TABLE content_units (
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
    extraction_method TEXT CHECK (extraction_method IS NULL OR extraction_method IN ('DIRECT_TEXT', 'OCR')),
    page_approval TEXT CHECK (page_approval IS NULL OR page_approval IN ('APPROVED', 'PENDING')),
    UNIQUE (document_id, ordinal),
    -- An artifact reference is complete or absent: a path without a checksum could not be verified,
    -- and a checksum without a path names nothing to check.
    CHECK ((artifact_relative_path IS NULL) = (artifact_sha256 IS NULL))
);

-- One embeddable passage of a unit. The span is what the chunk covers of the unit's search text; the
-- token columns are where that text sits inside the encoded passage, so both the coverage and the
-- budget spent on the encoder's own tokens are readable from the row.
CREATE TABLE chunks (
    id TEXT NOT NULL PRIMARY KEY,
    content_unit_id TEXT NOT NULL REFERENCES content_units (id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    text TEXT NOT NULL CHECK (length(text) > 0),
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
    -- The bounds match the domain's own requires rather than tightening them: `Chunk` already
    -- refuses a passage with no room for a token, so a zero-length payload fails there with a
    -- message about the passage instead of here with a driver-level constraint error.
    token_count INTEGER NOT NULL CHECK (token_count >= 0),
    token_start INTEGER NOT NULL CHECK (token_start >= 0),
    token_end INTEGER NOT NULL CHECK (token_end >= token_start),
    created_at TEXT NOT NULL,
    UNIQUE (content_unit_id, ordinal)
);

-- What one extraction attempt committed for one unit under one fingerprint. `outcome` is EXTRACTED
-- or FAILED. A failed row is durable on purpose: a resumed attempt does not retry a unit whose
-- failure is already known. The fingerprint is part of the identity, so the same unit read under
-- other settings is a different row.
CREATE TABLE extraction_checkpoints (
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

CREATE INDEX extraction_checkpoints_document ON extraction_checkpoints (document_id, fingerprint);

-- The terminal marker of a complete extraction pass: one row per document, replaced when the pass is
-- repeated. It answers a different question than a checkpoint: that the extractor reached the end of
-- its document and how many units failed, which decides complete versus complete with warnings.
CREATE TABLE document_extractions (
    document_id TEXT NOT NULL PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    fingerprint TEXT NOT NULL CHECK (length(fingerprint) > 0),
    total_units INTEGER NOT NULL CHECK (total_units >= 0),
    failed_units INTEGER NOT NULL CHECK (failed_units >= 0),
    metadata TEXT NOT NULL,
    completed_at TEXT NOT NULL
);

-- What a document's current attempt is reading and how far it has got, written by the attempt
-- itself. `total_units` stays NULL when the extractor had no total to announce: an unknown total
-- shown as a number would be an invented denominator.
--
-- The completed and failed counters are deliberately not columns. A stored counter can go wrong, and
-- the rows that make it up are the same checkpoints a resume skips, so counting those rows makes
-- "progress advances only with a committed unit" true by construction.
CREATE TABLE document_extraction_progress (
    document_id TEXT NOT NULL PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    fingerprint TEXT NOT NULL CHECK (length(fingerprint) > 0),
    unit_kind TEXT CHECK (unit_kind IS NULL OR unit_kind IN ('PAGE', 'SECTION', 'SLIDE', 'SHEET', 'LINE', 'IMAGE')),
    total_units INTEGER CHECK (total_units IS NULL OR total_units >= 0),
    started_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    -- A total belongs to something: a count with no unit kind to count is a number nobody can read.
    CHECK ((total_units IS NULL) OR (unit_kind IS NOT NULL))
);

-- How a document's chunks were built: the chunker's version, the tokenizer that measured them, and
-- the budget they were fitted to.
CREATE TABLE document_chunking (
    document_id TEXT NOT NULL PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    chunker_version TEXT NOT NULL CHECK (length(chunker_version) > 0),
    tokenizer_id TEXT NOT NULL CHECK (length(tokenizer_id) > 0),
    max_sequence_tokens INTEGER NOT NULL CHECK (max_sequence_tokens > 0),
    overlap_tokens INTEGER NOT NULL CHECK (overlap_tokens >= 0),
    chunk_count INTEGER NOT NULL CHECK (chunk_count >= 0),
    -- How many units the pass that wrote this row walked. A pass that stopped halfway has chunks for
    -- a prefix of the document, and without this count the prefix would pass for the whole.
    unit_count INTEGER NOT NULL CHECK (unit_count >= 0),
    chunked_at TEXT NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- LLM configuration and conversations
-- ---------------------------------------------------------------------------------------------
--
-- A profile stores the NAME of the environment variable holding its API key, never a value. The same
-- rule holds for every table here: conversations, messages and model-call snapshots record
-- provider, endpoint, model, profile name, prompt version and retrieval settings, but no secret.

CREATE TABLE llm_profiles (
    id                               TEXT    NOT NULL PRIMARY KEY,
    name                             TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    provider                         TEXT    NOT NULL CHECK (provider IN ('OPENAI_COMPATIBLE', 'ANTHROPIC')),
    endpoint                         TEXT,
    model                            TEXT    NOT NULL,
    api_key_environment_variable     TEXT,
    context_window                   INTEGER NOT NULL CHECK (context_window > 0),
    max_output_tokens                INTEGER NOT NULL CHECK (max_output_tokens > 0),
    supports_tool_calling            INTEGER NOT NULL CHECK (supports_tool_calling IN (0, 1)),
    input_price_per_million          REAL    NOT NULL CHECK (input_price_per_million >= 0),
    output_price_per_million         REAL    NOT NULL CHECK (output_price_per_million >= 0),
    cache_read_price_per_million     REAL    NOT NULL CHECK (cache_read_price_per_million >= 0),
    enabled                          INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    -- The measured result of the last capability test.
    tool_calling_measured            INTEGER,
    capability_checked_at            TEXT,
    created_at                       TEXT    NOT NULL,
    updated_at                       TEXT    NOT NULL
);

-- Which profile answers Ask and which answers Investigate. A row holds the role, so future roles only
-- add rows, and the absence of a row means "no default selected yet".
CREATE TABLE app_defaults (
    id           TEXT    NOT NULL PRIMARY KEY,
    role         TEXT    NOT NULL UNIQUE CHECK (role IN ('ASK', 'INVESTIGATE')),
    profile_id   TEXT    NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES llm_profiles (id) ON DELETE CASCADE
);

-- The editable, versioned Ask/Investigate prompt bodies. The immutable core rules live in
-- code/resources and cannot be replaced from here; this table only holds the user-editable bodies.
CREATE TABLE prompt_overrides (
    role             TEXT    NOT NULL PRIMARY KEY CHECK (role IN ('ASK', 'INVESTIGATE')),
    body             TEXT    NOT NULL,
    version          INTEGER NOT NULL,
    updated_at       TEXT    NOT NULL
);

-- Conversations are fixed to one collection and one profile snapshot when created. The snapshot is
-- non-secret and a later profile edit never rewrites history. `title` is a short name written from the
-- opening question; it is nullable and the list endpoints fall back to the truncated question.
--
-- `profile_endpoint_repaired` records that the snapshot's address once carried a credential that was
-- removed. The read path treats such a snapshot as a switched-off profile, so a conversation cannot
-- resume dispatching to a repaired address until a person has reviewed the live profile.
CREATE TABLE conversations (
    id                          TEXT    NOT NULL PRIMARY KEY,
    collection_id               TEXT    NOT NULL,
    mode                        TEXT    NOT NULL CHECK (mode IN ('ASK', 'INVESTIGATE')),
    profile_provider            TEXT    NOT NULL,
    profile_endpoint            TEXT,
    profile_model               TEXT    NOT NULL,
    profile_name                TEXT    NOT NULL,
    prompt_version              INTEGER NOT NULL,
    retrieval_snapshot          TEXT    NOT NULL DEFAULT '{}',
    created_at                  TEXT    NOT NULL,
    title                       TEXT,
    profile_endpoint_repaired   INTEGER NOT NULL DEFAULT 0 CHECK (profile_endpoint_repaired IN (0, 1)),
    FOREIGN KEY (collection_id) REFERENCES collections (id) ON DELETE CASCADE
);

-- `tool_calls_json` and `tool_call_id` preserve the provider-neutral tool exchange when an
-- Investigate turn is continued. `superseded` marks a streamed draft answer replaced by a corrected
-- one: only one answer is the adopted conversation answer, and the superseded draft stays as audit
-- data, excluded from the reader and provider views.
CREATE TABLE messages (
    id                TEXT    NOT NULL PRIMARY KEY,
    conversation_id   TEXT    NOT NULL,
    seq               INTEGER NOT NULL,
    role              TEXT    NOT NULL CHECK (role IN ('user', 'assistant', 'tool', 'system')),
    content           TEXT    NOT NULL,
    created_at        TEXT    NOT NULL,
    tool_calls_json   TEXT,
    tool_call_id      TEXT,
    superseded        INTEGER NOT NULL DEFAULT 0 CHECK (superseded IN (0, 1)),
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

CREATE TABLE model_calls (
    id                 TEXT    NOT NULL PRIMARY KEY,
    conversation_id    TEXT    NOT NULL,
    provider           TEXT    NOT NULL,
    endpoint           TEXT,
    model              TEXT    NOT NULL,
    profile_name       TEXT    NOT NULL,
    prompt_version     INTEGER NOT NULL,
    requested_at       TEXT    NOT NULL,
    response_at        TEXT    NOT NULL,
    status             TEXT    NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    input_tokens       INTEGER,
    output_tokens      INTEGER,
    cache_read_tokens  INTEGER,
    cost_usd           REAL    NOT NULL DEFAULT 0,
    error_code         TEXT,
    correction_of      TEXT,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Ask audit details: `supplied` and `invalid_marker` distinguish supplied-but-uncited evidence from
-- citation ids a model returned that were never supplied. `revision_id` is the revision an excerpt
-- was read from when it was saved; NULL means its provenance cannot be proven, which is a fact to
-- report rather than a default to attribute to whatever text is published now.
CREATE TABLE citations (
    id               TEXT    NOT NULL PRIMARY KEY,
    model_call_id    TEXT    NOT NULL,
    conversation_id  TEXT    NOT NULL,
    source_unit_id   TEXT    NOT NULL,
    locator_json     TEXT    NOT NULL,
    snippet          TEXT    NOT NULL,
    validated        INTEGER NOT NULL CHECK (validated IN (0, 1)),
    evidence_id      TEXT,
    returned_id      TEXT,
    supplied         INTEGER NOT NULL DEFAULT 1 CHECK (supplied IN (0, 1)),
    invalid_marker   INTEGER NOT NULL DEFAULT 0 CHECK (invalid_marker IN (0, 1)),
    revision_id      TEXT,
    FOREIGN KEY (model_call_id) REFERENCES model_calls (id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Accumulated usage per profile, so the cost/usage counters have one home.
CREATE TABLE usage_totals (
    profile_id          TEXT    NOT NULL PRIMARY KEY,
    calls               INTEGER NOT NULL DEFAULT 0,
    input_tokens        INTEGER NOT NULL DEFAULT 0,
    output_tokens       INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens   INTEGER NOT NULL DEFAULT 0,
    cost_usd            REAL    NOT NULL DEFAULT 0,
    FOREIGN KEY (profile_id) REFERENCES llm_profiles (id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation ON messages (conversation_id, seq);
CREATE INDEX idx_citations_model_call ON citations (model_call_id);
CREATE INDEX idx_citations_evidence ON citations (conversation_id, evidence_id);
CREATE INDEX idx_model_calls_conversation ON model_calls (conversation_id);

-- ---------------------------------------------------------------------------------------------
-- Investigate activity: tool calls, the evidence ledger, request audit, limit events
-- ---------------------------------------------------------------------------------------------

-- One tool call the service executed on the model's behalf: which conversation and which assistant
-- model call requested it, the tool name and arguments, the typed result code, and its duration.
CREATE TABLE tool_calls (
    id                TEXT    NOT NULL PRIMARY KEY,
    conversation_id   TEXT    NOT NULL,
    model_call_id     TEXT    NOT NULL,
    tool_name         TEXT    NOT NULL,
    arguments_json    TEXT    NOT NULL,
    result_code       TEXT    NOT NULL,
    duration_ms       INTEGER NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    FOREIGN KEY (model_call_id) REFERENCES model_calls (id) ON DELETE CASCADE
);

-- The evidence ledger: monotonic S1, S2, ... per conversation, never reused or renumbered after
-- eviction. Each row records the source unit, locator and the excerpt that was supplied, so every
-- cited source can be reopened after restart. `message_seq` is the `messages.seq` of the assistant
-- tool-call exchange that introduced the entry: a continued conversation uses it to associate
-- retained evidence with the history group that actually carried it into a provider request, so
-- ledger membership alone never makes evidence eligible. NULL entries stay ineligible for citation.
CREATE TABLE evidence_ledger (
    conversation_id  TEXT NOT NULL,
    evidence_id      TEXT NOT NULL,
    source_unit_id   TEXT NOT NULL,
    locator_json     TEXT NOT NULL,
    excerpt          TEXT NOT NULL,
    message_seq      INTEGER,
    PRIMARY KEY (conversation_id, evidence_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Which evidence ids were supplied to a particular model request.
CREATE TABLE request_eligibility (
    model_call_id    TEXT NOT NULL,
    conversation_id  TEXT NOT NULL,
    evidence_id      TEXT NOT NULL,
    PRIMARY KEY (model_call_id, evidence_id),
    FOREIGN KEY (model_call_id) REFERENCES model_calls (id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Which history groups were omitted from a model request's outbound view, so the full local history
-- stays recoverable while the reduced view is recorded.
CREATE TABLE request_omissions (
    model_call_id    TEXT NOT NULL,
    conversation_id  TEXT NOT NULL,
    group_label      TEXT NOT NULL,
    PRIMARY KEY (model_call_id, group_label),
    FOREIGN KEY (model_call_id) REFERENCES model_calls (id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Typed limit, cancellation and budget-overflow events, durable so operators can see why a
-- conversation stopped.
CREATE TABLE limit_events (
    id                TEXT NOT NULL PRIMARY KEY,
    conversation_id   TEXT NOT NULL,
    event_type        TEXT NOT NULL,
    message           TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

CREATE INDEX idx_tool_calls_conversation ON tool_calls (conversation_id);
CREATE INDEX idx_evidence_ledger_conversation ON evidence_ledger (conversation_id);
CREATE INDEX idx_limit_events_conversation ON limit_events (conversation_id);

-- ---------------------------------------------------------------------------------------------
-- OCR profiles
-- ---------------------------------------------------------------------------------------------
--
-- A profile's editable fields live in immutable revision rows: an edit inserts a new revision and
-- repoints the profile at it, so a job that snapshotted revision A keeps reading A after the profile
-- is edited to B. The two tables reference each other, which is why the profile's foreign key is
-- deferred: the profile row is inserted first, carrying the revision it is about to own, and the
-- constraint is checked when the transaction commits.
--
-- No key value is stored anywhere: a revision holds the NAME of the environment variable a key is
-- read from, and the CHECK mirrors the pattern the store validates.

CREATE TABLE ocr_profile_revisions (
    revision_id                  TEXT    NOT NULL PRIMARY KEY,
    profile_id                   TEXT    NOT NULL,
    sequence                     INTEGER NOT NULL CHECK (sequence > 0),
    provider                     TEXT    NOT NULL CHECK (provider IN ('OPENAI_COMPATIBLE', 'ANTHROPIC')),
    endpoint                     TEXT,
    model                        TEXT    NOT NULL CHECK (length(trim(model)) > 0),
    -- `[A-Za-z_]*` covers the first character and the negated class rejects every character after it
    -- that the environment-variable rule does not allow, which is the whole
    -- `^[A-Za-z_][A-Za-z0-9_]*$` rule.
    api_key_environment_variable TEXT    CHECK (
        api_key_environment_variable IS NULL OR (
            api_key_environment_variable GLOB '[A-Za-z_]*'
            AND NOT api_key_environment_variable GLOB '*[^A-Za-z0-9_]*'
        )
    ),
    context_window               INTEGER NOT NULL CHECK (context_window > 0),
    max_output_tokens            INTEGER NOT NULL CHECK (max_output_tokens > 0),
    input_price_per_million      REAL    NOT NULL CHECK (input_price_per_million >= 0),
    output_price_per_million     REAL    NOT NULL CHECK (output_price_per_million >= 0),
    -- The measured result of a synthetic-image capability check. A declared switch is never stored
    -- here, so a claim can never masquerade as a measurement.
    image_capability_measured    INTEGER CHECK (image_capability_measured IN (0, 1)),
    image_capability_checked_at  TEXT,
    created_at                   TEXT    NOT NULL,
    UNIQUE (profile_id, sequence)
);

CREATE TABLE ocr_profiles (
    id                  TEXT    NOT NULL PRIMARY KEY,
    name                TEXT    NOT NULL UNIQUE COLLATE NOCASE CHECK (length(trim(name)) > 0),
    enabled             INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    current_revision_id TEXT    NOT NULL,
    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL,
    FOREIGN KEY (current_revision_id) REFERENCES ocr_profile_revisions (revision_id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ocr_profile_revisions_profile ON ocr_profile_revisions (profile_id);

-- ---------------------------------------------------------------------------------------------
-- Document revisions and recoverable publication
-- ---------------------------------------------------------------------------------------------
--
-- Three separations are load-bearing:
--
--   * `document_revisions` is an immutable statement about how a document's text was read. A row is
--     never edited after it is written; a later reading is a child revision, so any page of any past
--     reading stays readable exactly as it was.
--   * `page_text_revisions` and `revision_chunks` belong to a revision, not to the live document. A
--     candidate is staged here and therefore cannot mutate `content_units` or `chunks`: the published
--     text and chunks stay the previous revision's until a publication makes this one authoritative.
--   * `document_active_revisions` is the single durable answer to "which revision is published". The
--     search index is derived from it, so a publication can be interrupted between the index and the
--     database without either side guessing which the other settled on.

CREATE TABLE document_revisions (
    id                 TEXT    NOT NULL PRIMARY KEY,
    document_id        TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    parent_revision_id TEXT    REFERENCES document_revisions (id),
    state              TEXT    NOT NULL CHECK (state IN ('CANDIDATE', 'PUBLISHED', 'SUPERSEDED', 'WITHDRAWN')),
    -- Why this revision exists and what produced it, so a reader is never told a provenance nobody
    -- recorded. Free-form on purpose: the values are read by the services that write them.
    provenance         TEXT    NOT NULL CHECK (length(provenance) > 0),
    created_at         TEXT    NOT NULL
);

CREATE INDEX document_revisions_document ON document_revisions (document_id, created_at);

-- One page's text as one revision read it. `unit_id` is deliberately not a foreign key to
-- `content_units`: a replacement may add a page, and the unit row is created when the revision is
-- published, not when it is staged. The identity is the stable content-unit id either way.
--
-- `approval` is the review decision of this page in this revision. Publication reads only APPROVED
-- pages, so a candidate whose text has not been reviewed is a pending-review outcome rather than a
-- searchable replacement. `text_sha256` is nullable: a text that was never hashed has no hash to
-- invent.
--
-- The `source_image_*` columns name the pixels this page's reading was actually made from. A picture
-- whose declared raster is past the bound a tool is handed is read from a bounded copy this pipeline
-- wrote, and that copy's pixels are not the managed original's, so a reviewer comparing a rescan with
-- the document has to know which of the two a reading was made from.
--
--   * `source_image_root` says which directory the relative path resolves against: the document's own
--     `artifacts/` directory (a rendered PDF page, or a bounded copy written for one attempt) or the
--     directory holding its managed copy (a picture that is the document, read as itself). The schema
--     never holds an absolute path.
--   * `source_image_render_version` names the procedure that produced the pixels: 1 for a page this
--     pipeline rendered or a picture read as itself, 2 for the bounded copy of a picture.
--   * The relative path cannot climb out of its root: it is not blank, not absolute (a leading
--     separator or a drive letter), and has no `.`, `..` or empty segment. A reviewer-facing route
--     serves the image from this record, so an escaping reference would be a file read the document
--     never owned. `SourceImageProvenance` enforces the same rule, so the two cannot disagree.
--   * The root, path, hash and render version are present together or absent together. The
--     dimensions are a pair of their own (a picture nobody measured is a legitimate "unmeasured"
--     image; half a measurement is not a measurement) and never exist without an image. Absent means
--     "no image": zero, an empty reference or a guessed hash would each be a claim about pixels
--     nobody looked at.
CREATE TABLE page_text_revisions (
    revision_id                 TEXT    NOT NULL REFERENCES document_revisions (id) ON DELETE CASCADE,
    ordinal                     INTEGER NOT NULL CHECK (ordinal >= 0),
    unit_id                     TEXT    NOT NULL CHECK (length(unit_id) > 0),
    locator                     TEXT    NOT NULL CHECK (length(locator) > 0),
    extracted_text              TEXT    NOT NULL,
    search_text                 TEXT    NOT NULL,
    text_sha256                 TEXT    CHECK (text_sha256 IS NULL OR length(text_sha256) = 64),
    extraction_method           TEXT    CHECK (extraction_method IS NULL OR extraction_method IN ('DIRECT_TEXT', 'OCR')),
    mean_confidence             REAL,
    artifact_relative_path      TEXT,
    artifact_sha256             TEXT,
    approval                    TEXT    NOT NULL CHECK (approval IN ('APPROVED', 'PENDING', 'REJECTED')),
    created_at                  TEXT    NOT NULL,
    source_image_root           TEXT    CHECK (source_image_root IS NULL OR source_image_root IN ('MANAGED_COPY', 'ARTIFACTS')),
    source_image_relative_path  TEXT    CHECK (
        source_image_relative_path IS NULL OR (
            length(trim(source_image_relative_path)) > 0
            AND substr(source_image_relative_path, 1, 1) NOT IN ('/', '\')
            AND source_image_relative_path NOT GLOB '[A-Za-z]:*'
            AND ('/' || replace(source_image_relative_path, '\', '/') || '/') NOT LIKE '%/../%'
            AND ('/' || replace(source_image_relative_path, '\', '/') || '/') NOT LIKE '%/./%'
            AND ('/' || replace(source_image_relative_path, '\', '/') || '/') NOT LIKE '%//%'
        )
    ),
    source_image_sha256         TEXT    CHECK (source_image_sha256 IS NULL OR length(source_image_sha256) = 64),
    source_image_width          INTEGER CHECK (source_image_width IS NULL OR source_image_width > 0),
    source_image_height         INTEGER CHECK (source_image_height IS NULL OR source_image_height > 0),
    source_image_render_version INTEGER CHECK (source_image_render_version IS NULL OR source_image_render_version > 0),
    PRIMARY KEY (revision_id, ordinal),
    CHECK ((artifact_relative_path IS NULL) = (artifact_sha256 IS NULL)),
    CHECK (
        (
            source_image_root IS NULL
            AND source_image_relative_path IS NULL
            AND source_image_sha256 IS NULL
            AND source_image_render_version IS NULL
            AND source_image_width IS NULL
            AND source_image_height IS NULL
        ) OR (
            source_image_root IS NOT NULL
            AND source_image_relative_path IS NOT NULL
            AND source_image_sha256 IS NOT NULL
            AND source_image_render_version IS NOT NULL
            AND (source_image_width IS NULL) = (source_image_height IS NULL)
        )
    )
);

-- One revision's chunks, with the vector each chunk was embedded to. The vector is stored rather than
-- recomputed: recovering an interrupted publication must not need the accelerator or the pinned
-- model, and a recovery that re-embedded would either refuse to finish on a machine without CoreML or
-- silently produce different vectors. `embedding` is nullable for a chunk that has no vector.
CREATE TABLE revision_chunks (
    revision_id         TEXT    NOT NULL REFERENCES document_revisions (id) ON DELETE CASCADE,
    unit_ordinal        INTEGER NOT NULL CHECK (unit_ordinal >= 0),
    ordinal             INTEGER NOT NULL CHECK (ordinal >= 0),
    text                TEXT    NOT NULL CHECK (length(text) > 0),
    start_offset        INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset          INTEGER NOT NULL CHECK (end_offset > start_offset),
    token_count         INTEGER NOT NULL CHECK (token_count >= 0),
    token_start         INTEGER NOT NULL CHECK (token_start >= 0),
    token_end           INTEGER NOT NULL CHECK (token_end >= token_start),
    embedding           BLOB,
    embedding_dimension INTEGER CHECK (embedding_dimension IS NULL OR embedding_dimension > 0),
    created_at          TEXT    NOT NULL,
    PRIMARY KEY (revision_id, unit_ordinal, ordinal),
    CHECK ((embedding IS NULL) = (embedding_dimension IS NULL))
);

-- Which revision of one document is published. Its absence means the document has no published
-- revision: a document whose extraction produced no unit is searchable by nothing.
CREATE TABLE document_active_revisions (
    document_id TEXT NOT NULL PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    revision_id TEXT NOT NULL REFERENCES document_revisions (id),
    updated_at  TEXT NOT NULL
);

-- One recoverable publication attempt. `phase` says what the attempt promised; `authoritative_at` is
-- the marker recovery reads to decide which side of the handoff it died on, and it is written by the
-- same SQLite transaction that makes the target revision authoritative. A row with an
-- `authoritative_at` and no `published_at` is therefore never a failure: it is a publication that has
-- to be finished.
CREATE TABLE revision_publications (
    id                 TEXT    NOT NULL PRIMARY KEY,
    document_id        TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    collection_id      TEXT    NOT NULL,
    base_revision_id   TEXT,
    target_revision_id TEXT    NOT NULL REFERENCES document_revisions (id),
    phase              TEXT    NOT NULL CHECK (phase IN ('PREPARED', 'PUBLISHED', 'ABANDONED', 'REFUSED')),
    authoritative_at   TEXT,
    prepared_at        TEXT    NOT NULL,
    published_at       TEXT,
    -- When the rows of the reading this publication replaced were removed from the index. Null on an
    -- authoritative attempt means the cleanup still owes the index a repair, which the next startup
    -- finishes before anything may read: the removed rows are hidden only by an in-process snapshot,
    -- and that snapshot does not survive a restart.
    cleaned_at         TEXT,
    error_code         TEXT,
    error_message      TEXT,
    CHECK (phase <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX revision_publications_unfinished
    ON revision_publications (prepared_at)
    WHERE published_at IS NULL;

-- Explicit restoration of a historical revision. A restore never rewrites history and never reads a
-- page again: it stages a NEW candidate revision whose pages are copies of an earlier revision's
-- immutable page texts, and publishes it through the same recoverable protocol as every other
-- publication. This table is the durable record of that request:
--
--   * Idempotency. `request_id`/`request_hash`: the same id and body is the same restore, and the same
--     id with another body is a conflict rather than a second restore.
--   * Provenance. `restored_from_revision_id` is what the history view reads to say that a revision is
--     a restore of another one, so the statement is recorded rather than inferred.
--   * Recovery. `phase` says whether the attempt is in flight (STAGED), finished (PUBLISHED) or gave up
--     (FAILED). A STAGED row that survives a restart is resolved from the publication protocol's own
--     answer about its new revision.
--
-- A restore belongs to its document: deleting the document or its collection removes the row with the
-- revisions it names.
CREATE TABLE revision_restores (
    restore_id                TEXT    NOT NULL PRIMARY KEY,
    collection_id             TEXT    NOT NULL,
    document_id               TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    request_id                TEXT    NOT NULL CHECK (length(trim(request_id)) > 0),
    request_hash              TEXT    NOT NULL CHECK (length(request_hash) = 64),
    expected_revision_id      TEXT    NOT NULL,
    restored_from_revision_id TEXT    NOT NULL,
    new_revision_id           TEXT    NOT NULL REFERENCES document_revisions (id),
    phase                     TEXT    NOT NULL CHECK (phase IN ('STAGED', 'PUBLISHED', 'FAILED')),
    error_code                TEXT,
    error_message             TEXT,
    created_at                TEXT    NOT NULL,
    updated_at                TEXT    NOT NULL,
    UNIQUE (collection_id, document_id, request_id),
    UNIQUE (new_revision_id)
);

CREATE INDEX revision_restores_document ON revision_restores (document_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- OCR page reviews and validation records
-- ---------------------------------------------------------------------------------------------

-- What one comparison of a page's published text with a candidate reading concluded. A review has to
-- outlive the process that made it: the same comparison may be asked for again (a paid reviewer call
-- must not be paid for twice), a proposal stays pending until a person decides, and the decision a page
-- carries has to be attributable to the reviewer, the policy and the texts it was made against.
--
-- The identity of a review is its fingerprint and the page image it judged. The fingerprint covers the
-- baseline revision and its text hash, the candidate's text hash, the reviewer revision, the review
-- prompt version and the policy version; the image hash is separate because the fingerprint record
-- cannot carry it without changing a digest that already exists. A review of other pixels is not this
-- review, so a re-render of the same page with the same texts recomputes rather than reuses.
--
-- Nothing here is page text. A review holds hashes plus bounded reasons and spans, so a stored proposal
-- cannot become a second copy of the page, and no accepted text is ever a merged third reading.
--
-- `baseline_revision_id` and `baseline_text_hash` are absent together for a page with no baseline.
-- `outcome_code` is the safe code of a comparison whose recommendation no reviewer answer stands behind
-- (a review that could not be made, or one that never needed a reviewer); it is null when the reviewer
-- answered. `confidence` is the reviewer's own number, never an input to a decision. `searchable` is
-- written down rather than derived, because it is the answer a retrieval path asks.
CREATE TABLE page_reviews (
    review_fingerprint     TEXT    NOT NULL CHECK (length(review_fingerprint) = 64),
    image_sha256           TEXT    NOT NULL CHECK (length(image_sha256) = 64),
    document_id            TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    unit_id                TEXT    NOT NULL CHECK (length(trim(unit_id)) > 0),
    ordinal                INTEGER NOT NULL CHECK (ordinal >= 0),
    baseline_revision_id   TEXT,
    baseline_text_hash     TEXT    CHECK (baseline_text_hash IS NULL OR length(baseline_text_hash) = 64),
    candidate_hash         TEXT    NOT NULL CHECK (length(candidate_hash) = 64),
    recommendation         TEXT    NOT NULL CHECK (
        recommendation IN ('EXISTING_BETTER', 'NEW_BETTER', 'UNCERTAIN')
    ),
    disposition            TEXT    NOT NULL CHECK (disposition IN ('KEEP', 'PROPOSE', 'APPROVE')),
    confidence             REAL    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    reviewer_revision_id   TEXT    NOT NULL CHECK (length(trim(reviewer_revision_id)) > 0),
    reviewer_model_version TEXT,
    review_prompt_version  INTEGER NOT NULL CHECK (review_prompt_version > 0),
    policy_version         INTEGER NOT NULL CHECK (policy_version > 0),
    outcome_code           TEXT    CHECK (outcome_code IS NULL OR length(trim(outcome_code)) > 0),
    searchable             INTEGER NOT NULL CHECK (searchable IN (0, 1)),
    -- The bounded reasons, as JSON: a code, its origin, the spans it is about and, for a reviewer's own
    -- reason, its bounded explanation.
    reasons                TEXT    NOT NULL,
    created_at             TEXT    NOT NULL,
    PRIMARY KEY (review_fingerprint, image_sha256),
    CHECK ((baseline_revision_id IS NULL) = (baseline_text_hash IS NULL))
);

-- What "Needs review" asks for: one document's pending proposals, in reading order.
CREATE INDEX page_reviews_pending ON page_reviews (document_id, disposition, ordinal);

-- What a page's reviews are read back by, for the details and history views.
CREATE INDEX page_reviews_page ON page_reviews (document_id, unit_id, ordinal);

-- Accepted validation records: the measured evidence that one policy/reviewer/prompt combination was
-- judged good enough, by a person, to replace text without one. This table is the whole of the
-- automatic-replacement gate: pilot mode replaces nothing without a manual decision, and the only
-- thing that may allow more is a record read from here, keyed by the combination it was accepted for.
-- A changed model, prompt or policy falls back to manual approval rather than inheriting an old
-- acceptance.
--
-- `review_profile_revision_id` is deliberately not a foreign key: the record is evidence about an
-- immutable revision rather than a pointer to whatever profile names it now, and a comparison that
-- resolves an unknown reviewer revision refuses it, so a record whose revision no longer exists
-- approves nothing. There is deliberately no document, collection, unit or page column: acceptance is
-- about the combination, never about one page's text.
CREATE TABLE ocr_validation_records (
    policy_version            INTEGER NOT NULL CHECK (policy_version > 0),
    review_profile_revision_id TEXT   NOT NULL CHECK (length(trim(review_profile_revision_id)) > 0),
    review_prompt_version     INTEGER NOT NULL CHECK (review_prompt_version > 0),
    validation_id             TEXT    NOT NULL CHECK (length(trim(validation_id)) > 0),
    accepted_at               TEXT    NOT NULL,
    PRIMARY KEY (policy_version, review_profile_revision_id, review_prompt_version)
);

-- ---------------------------------------------------------------------------------------------
-- OCR rescans: previews, operations and external-page admission
-- ---------------------------------------------------------------------------------------------

-- What a person was shown before a rescan was admitted. It is durable because admission revalidates
-- against it: the document's managed hash, the revision that would be the baseline, and the snapshot
-- whose approval scope an external dispatch is bound to. Both the snapshot and its hash are stored: the
-- hash is what an approval names. `overrides` are the overrides the preview was taken with, so
-- admission re-resolves exactly the settings it showed; without them an override would look like drift.
CREATE TABLE ocr_rescan_previews (
    preview_id                TEXT    NOT NULL PRIMARY KEY,
    collection_id             TEXT    NOT NULL,
    document_id               TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    base_revision_id          TEXT,
    managed_sha256            TEXT    NOT NULL CHECK (length(managed_sha256) = 64),
    snapshot                  TEXT    NOT NULL,
    snapshot_hash             TEXT    NOT NULL CHECK (length(snapshot_hash) = 64),
    page_total                INTEGER CHECK (page_total IS NULL OR page_total > 0),
    external_page_upper_bound INTEGER CHECK (external_page_upper_bound IS NULL OR external_page_upper_bound >= 0),
    approval_required         INTEGER NOT NULL CHECK (approval_required IN (0, 1)),
    cost_estimate             TEXT,
    created_at                TEXT    NOT NULL,
    expires_at                TEXT    NOT NULL,
    overrides                 TEXT
);

CREATE INDEX ocr_rescan_previews_document ON ocr_rescan_previews (document_id, created_at);

-- One durable rescan operation: the reading attempt a document is under. It is separate from the job
-- because a job is one attempt and an operation outlives it: an attempt that paused for external
-- approval, failed on a missing tool, or was cancelled is resumed by queuing another attempt, and every
-- one of them continues the same operation with the same immutable snapshot and counters.
-- `current_job_id` names the attempt that owns it right now. The snapshot is the whole of what the
-- attempt is: a resume may not substitute a different engine, model or prompt version. `stage` is the
-- durable lifecycle and deliberately not the review backlog: an operation can be COMPLETE while pages
-- still await a person's decision, which is what `pending_review_count` is for.
--
-- `request_id`/`request_hash` are the idempotency pair: the same id and body is the same operation; the
-- same id with a different body is a conflict, because "admit this again" must never mean two attempts
-- racing one document.
CREATE TABLE ocr_operations (
    operation_id        TEXT    NOT NULL PRIMARY KEY,
    collection_id       TEXT    NOT NULL,
    document_id         TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    current_job_id      TEXT    REFERENCES jobs (id) ON DELETE SET NULL,
    base_revision_id    TEXT,
    candidate_revision_id TEXT,
    snapshot            TEXT    NOT NULL,
    snapshot_hash       TEXT    NOT NULL CHECK (length(snapshot_hash) = 64),
    stage               TEXT    NOT NULL CHECK (
        stage IN ('PREFLIGHT', 'AWAITING_APPROVAL', 'OCR', 'REVIEW', 'CHUNKING', 'EMBEDDING', 'INDEXING',
                  'COMPLETE', 'FAILED', 'CANCELLED', 'NEEDS_TOOL')
    ),
    page_total          INTEGER CHECK (page_total IS NULL OR page_total > 0),
    pages_committed     INTEGER NOT NULL DEFAULT 0 CHECK (pages_committed >= 0),
    pages_failed        INTEGER NOT NULL DEFAULT 0 CHECK (pages_failed >= 0),
    pending_review_count INTEGER NOT NULL DEFAULT 0 CHECK (pending_review_count >= 0),
    error_code          TEXT,
    error_message       TEXT,
    request_id          TEXT    NOT NULL CHECK (length(trim(request_id)) > 0),
    request_hash        TEXT    NOT NULL CHECK (length(request_hash) = 64),
    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL,
    UNIQUE (collection_id, document_id, request_id)
);

CREATE INDEX ocr_operations_document ON ocr_operations (document_id, created_at);
CREATE INDEX ocr_operations_job ON ocr_operations (current_job_id);

-- One document, one reading: at most one operation per document that still owns its reading, including
-- one waiting for an external page scope and a COMPLETE one whose pages still await decisions. The
-- index is the invariant itself; a service's check-then-insert is two transactions and could not be.
CREATE UNIQUE INDEX ocr_operations_active_document ON ocr_operations (document_id)
    WHERE stage IN ('PREFLIGHT', 'AWAITING_APPROVAL', 'OCR', 'REVIEW', 'CHUNKING', 'EMBEDDING', 'INDEXING')
       OR (stage = 'COMPLETE' AND pending_review_count > 0);

-- The distinct document/page identities that were sent to an external provider, once per identity.
-- This is the page allowance's unit and nothing else: a page read by transcription and then judged by
-- an external reviewer was sent twice but is one page here, because the allowance is "how many of my
-- pages leave this Mac", not "how many requests were paid for". `owner_kind`/`owner_id` name what the
-- allowance belongs to: one document's rescan operation, or one import job.
CREATE TABLE ocr_external_pages (
    owner_kind    TEXT    NOT NULL CHECK (owner_kind IN ('OPERATION', 'JOB')),
    owner_id      TEXT    NOT NULL,
    document_id   TEXT    NOT NULL,
    unit_id       TEXT    NOT NULL CHECK (length(trim(unit_id)) > 0),
    ordinal       INTEGER NOT NULL CHECK (ordinal >= 0),
    first_sent_at TEXT    NOT NULL,
    PRIMARY KEY (owner_kind, owner_id, document_id, unit_id)
);

CREATE INDEX ocr_external_pages_owner ON ocr_external_pages (owner_kind, owner_id);

-- Provider calls, counted per page and stage, retries included. Separate from the page table on
-- purpose: a page limit is not a currency cap, and a retried request is a second call.
CREATE TABLE ocr_external_calls (
    owner_kind  TEXT    NOT NULL CHECK (owner_kind IN ('OPERATION', 'JOB')),
    owner_id    TEXT    NOT NULL,
    document_id TEXT    NOT NULL,
    unit_id     TEXT    NOT NULL CHECK (length(trim(unit_id)) > 0),
    ordinal     INTEGER NOT NULL CHECK (ordinal >= 0),
    stage       TEXT    NOT NULL CHECK (stage IN ('TRANSCRIPTION', 'REVIEW')),
    calls       INTEGER NOT NULL CHECK (calls >= 1),
    updated_at  TEXT    NOT NULL,
    PRIMARY KEY (owner_kind, owner_id, document_id, unit_id, stage)
);

-- One approval of an external page scope, granted by a person and bound to the scope it was granted
-- for. The approval names the snapshot hash it was granted against, so a resumed attempt that would
-- dispatch under another engine, endpoint, model, prompt version or policy cannot inherit it.
-- `authorized_distinct_pages` is the maximum distinct pages approved, which is why the latest approval
-- for an owner is the one that counts.
CREATE TABLE ocr_external_approvals (
    approval_id              TEXT    NOT NULL PRIMARY KEY,
    owner_kind               TEXT    NOT NULL CHECK (owner_kind IN ('OPERATION', 'JOB')),
    owner_id                 TEXT    NOT NULL,
    snapshot_hash            TEXT    NOT NULL CHECK (length(snapshot_hash) = 64),
    authorized_distinct_pages INTEGER NOT NULL CHECK (authorized_distinct_pages >= 0),
    created_at               TEXT    NOT NULL
);

CREATE INDEX ocr_external_approvals_owner
    ON ocr_external_approvals (owner_kind, owner_id, created_at);
