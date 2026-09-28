-- Document deletion: the deletion operation learns a kind and explicit targets.
--
-- The deletion machine in 001 was written for one collection at a time; a document deletion is the same
-- shape — park managed files, remove rows, remove index entries, purge — but it removes chosen documents
-- instead of a whole collection, and the work must keep the other files of a running import alive. So the
-- operation gains a `kind`, its targets are persisted before anything destructive happens, and the rows
-- that name those targets are the durable guard a later import resume obeys.
--
-- `kind` has a default so every operation admitted before this migration reads as what it was: a
-- collection deletion. `document_deletion_targets` is deliberately not a foreign key to `documents`:
-- the target row must outlive the document it names, because it is what recovery reads to find parked
-- files and what a resumed import reads to refuse copying the document again.
--
-- `managed_existed` is recorded per target at admission. It is what lets recovery tell "this document
-- never had a managed directory" from "the managed directory disappeared", which is the difference
-- between finishing a deletion and reporting an unsafe state that must not discard files.

ALTER TABLE deletion_operations ADD COLUMN kind TEXT NOT NULL DEFAULT 'COLLECTION'
    CHECK (kind IN ('COLLECTION', 'DOCUMENT'));

CREATE TABLE IF NOT EXISTS document_deletion_targets (
    operation_id TEXT NOT NULL REFERENCES deletion_operations (id) ON DELETE CASCADE,
    document_id TEXT NOT NULL CHECK (length(document_id) > 0),
    managed_existed INTEGER NOT NULL CHECK (managed_existed IN (0, 1)),
    PRIMARY KEY (operation_id, document_id)
);

-- The guard is read per document, not per operation: a stage that would write a document asks whether
-- anything is deleting it.
CREATE INDEX IF NOT EXISTS document_deletion_targets_document ON document_deletion_targets (document_id);

-- Per-file disposition: a file whose document was deleted is `CANCELLED`, and the import handler obeys
-- that row on every later attempt instead of copying the deleted target again.
--
-- SQLite cannot widen a CHECK constraint in place, so `import_items` is rebuilt. Foreign keys are off
-- for this migration's duration (it references `jobs` and `documents`), and the row contents are copied
-- unchanged: the wider constraint admits every value the old one did.

CREATE TABLE import_items_new (
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
    UNIQUE (job_id, item_key)
);

INSERT INTO import_items_new (id, job_id, item_key, source_path, document_id, outcome, error_code,
                              error_message, created_at, updated_at)
    SELECT id, job_id, item_key, source_path, document_id, outcome, error_code, error_message,
           created_at, updated_at
    FROM import_items;

DROP TABLE import_items;

ALTER TABLE import_items_new RENAME TO import_items;

CREATE INDEX IF NOT EXISTS import_items_job_outcome ON import_items (job_id, outcome);
