-- Immutable document revisions, the page text and chunk revisions they own, the active-revision
-- pointer, and the recoverable publication intents.
--
-- Naming follows 001: English lower_snake_case, instants as ISO-8601 UTC strings, and a CHECK beside
-- every enum-like column so an unknown value cannot reach the database.
--
-- Three separations are load-bearing:
--
--   * `document_revisions` is an immutable statement about how a document's text was read. A row is
--     never edited after it is written; a later reading is a child revision, so any page of any past
--     reading stays readable exactly as it was.
--   * `page_text_revisions` and `revision_chunks` belong to a revision, not to the live document. A
--     candidate is staged here and can therefore not mutate `content_units` or `chunks`: the published
--     text and the published chunks are still the previous revision's until a publication makes this
--     one authoritative.
--   * `document_active_revisions` is the single durable answer to "which revision is published". The
--     search index is derived from it, so a publication protocol can be interrupted between the index
--     and the database without either side guessing which the other settled on.
--
-- `text_sha256` and `embedding` are nullable on purpose. Legacy published content is backfilled below
-- without either: its text was never hashed and its vectors are already in the index, and inventing a
-- hash or re-embedding legacy pages during a migration would be a claim the archive cannot support.

CREATE TABLE IF NOT EXISTS document_revisions (
    id                 TEXT    NOT NULL PRIMARY KEY,
    document_id        TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    parent_revision_id TEXT    REFERENCES document_revisions (id),
    state              TEXT    NOT NULL CHECK (state IN ('CANDIDATE', 'PUBLISHED', 'SUPERSEDED', 'WITHDRAWN')),
    -- Why this revision exists and what produced it, so a reader is never told a provenance nobody
    -- recorded. Free-form on purpose: the values are read by the services that write them.
    provenance         TEXT    NOT NULL CHECK (length(provenance) > 0),
    created_at         TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS document_revisions_document ON document_revisions (document_id, created_at);

-- One page's text as one revision read it. `unit_id` is deliberately not a foreign key to
-- `content_units`: a replacement may add a page, and the unit row is created when the revision is
-- published, not when it is staged. The identity is the stable content-unit id either way.
--
-- `approval` is the review decision of this page in this revision. Publication reads only APPROVED
-- pages, so a candidate whose text has not been reviewed is a pending-review outcome rather than a
-- searchable replacement.
CREATE TABLE IF NOT EXISTS page_text_revisions (
    revision_id          TEXT    NOT NULL REFERENCES document_revisions (id) ON DELETE CASCADE,
    ordinal              INTEGER NOT NULL CHECK (ordinal >= 0),
    unit_id              TEXT    NOT NULL CHECK (length(unit_id) > 0),
    locator              TEXT    NOT NULL CHECK (length(locator) > 0),
    extracted_text       TEXT    NOT NULL,
    search_text          TEXT    NOT NULL,
    text_sha256          TEXT    CHECK (text_sha256 IS NULL OR length(text_sha256) = 64),
    extraction_method    TEXT    CHECK (extraction_method IS NULL OR extraction_method IN ('DIRECT_TEXT', 'OCR')),
    mean_confidence      REAL,
    artifact_relative_path TEXT,
    artifact_sha256      TEXT,
    approval             TEXT    NOT NULL CHECK (approval IN ('APPROVED', 'PENDING', 'REJECTED')),
    created_at           TEXT    NOT NULL,
    PRIMARY KEY (revision_id, ordinal),
    CHECK ((artifact_relative_path IS NULL) = (artifact_sha256 IS NULL))
);

-- One revision's chunks, with the vector each chunk was embedded to.
--
-- The vector is stored rather than recomputed: recovering an interrupted publication must not need the
-- accelerator or the pinned model, and a recovery that re-embedded would either refuse to finish on a
-- machine without CoreML or silently produce different vectors.
CREATE TABLE IF NOT EXISTS revision_chunks (
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
-- revision — a document whose extraction produced no unit is searchable by nothing and has nothing to
-- point at.
CREATE TABLE IF NOT EXISTS document_active_revisions (
    document_id TEXT NOT NULL PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    revision_id TEXT NOT NULL REFERENCES document_revisions (id),
    updated_at  TEXT NOT NULL
);

-- One recoverable publication attempt. `phase` says what the attempt promised; `authoritative_at` is
-- the marker the recovery reads to decide which side of the handoff it died on, and it is written by
-- the same SQLite transaction that makes the target revision authoritative. A row with an
-- `authoritative_at` and no `published_at` is therefore never a failure: it is a publication that has
-- to be finished, which is the one thing recovery must not report as failed.
CREATE TABLE IF NOT EXISTS revision_publications (
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
    -- authoritative attempt means the cleanup still owes the index a repair, which is what the next
    -- startup finishes before anything may read: the removed rows are hidden only by an in-process
    -- snapshot, and that snapshot does not survive a restart.
    cleaned_at         TEXT,
    error_code         TEXT,
    error_message      TEXT,
    CHECK (phase <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS revision_publications_unfinished
    ON revision_publications (prepared_at)
    WHERE published_at IS NULL;

-- Which revision an excerpt was read from when it was saved. NULL means the citation predates revision
-- tracking and its provenance cannot be proven, which is a fact to report rather than a default to
-- attribute to whatever text is published now.
ALTER TABLE citations ADD COLUMN revision_id TEXT;

-- Existing published content becomes an initial published revision, so every document that is
-- searchable today is searchable as a revision from the first moment this schema exists. The revision
-- identifier is derived from the document so the backfill is deterministic.
INSERT INTO document_revisions (id, document_id, parent_revision_id, state, provenance, created_at)
SELECT
    'revision-backfill-' || d.id,
    d.id,
    NULL,
    'PUBLISHED',
    'MIGRATION_BACKFILL',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM documents d
WHERE EXISTS (SELECT 1 FROM content_units u WHERE u.document_id = d.id);

INSERT INTO page_text_revisions (
    revision_id, ordinal, unit_id, locator, extracted_text, search_text,
    text_sha256, extraction_method, mean_confidence, artifact_relative_path, artifact_sha256,
    approval, created_at
)
SELECT
    'revision-backfill-' || u.document_id,
    u.ordinal,
    u.id,
    u.locator,
    u.extracted_text,
    u.search_text,
    NULL,
    NULL,
    u.mean_confidence,
    u.artifact_relative_path,
    u.artifact_sha256,
    'APPROVED',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM content_units u
WHERE EXISTS (
    SELECT 1 FROM document_revisions r WHERE r.id = 'revision-backfill-' || u.document_id
);

INSERT INTO revision_chunks (
    revision_id, unit_ordinal, ordinal, text, start_offset, end_offset,
    token_count, token_start, token_end, embedding, embedding_dimension, created_at
)
SELECT
    'revision-backfill-' || u.document_id,
    u.ordinal,
    c.ordinal,
    c.text,
    c.start_offset,
    c.end_offset,
    c.token_count,
    c.token_start,
    c.token_end,
    NULL,
    NULL,
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM chunks c
JOIN content_units u ON u.id = c.content_unit_id
WHERE EXISTS (
    SELECT 1 FROM document_revisions r WHERE r.id = 'revision-backfill-' || u.document_id
);

INSERT INTO document_active_revisions (document_id, revision_id, updated_at)
SELECT r.document_id, r.id, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM document_revisions r
WHERE r.provenance = 'MIGRATION_BACKFILL';
