-- Explicit restoration of a historical revision.
--
-- A restore never rewrites history and never reads a page again: it stages a NEW candidate revision whose
-- pages are copies of an earlier revision's immutable page texts, and publishes it through the same
-- recoverable protocol every other publication uses (`revision_publications`). This table is the durable
-- record of the request that did so, and it exists for three reasons the revision rows alone cannot carry:
--
--   * Idempotency. `request_id`/`request_hash` are the pair the other operation stores use: the same id and
--     the same body is the same restore, and the same id with another body is a conflict rather than a
--     second restore.
--   * Provenance. `restored_from_revision_id` is what the history view reads to say that a revision is a
--     restore of another one, so the statement is recorded rather than inferred from a free-form string.
--   * Recovery. `phase` says whether the attempt is still in flight (STAGED), finished (PUBLISHED) or gave up
--     (FAILED). A STAGED row that survives a restart is resolved from the publication protocol's own answer
--     about its new revision: the database either already serves it (PUBLISHED) or never moved authority
--     (FAILED, the previous reading wins).
--
-- A restore belongs to its document. Deleting the document or its collection removes the row with the
-- revisions it names; history is not a soft-delete feature.
CREATE TABLE IF NOT EXISTS revision_restores (
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

CREATE INDEX IF NOT EXISTS revision_restores_document ON revision_restores (document_id, created_at);
