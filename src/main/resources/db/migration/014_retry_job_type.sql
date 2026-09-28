-- Adds the RETRY job type.
--
-- Retrying a document is deliberately not an import: it addresses existing document identifiers and
-- their immutable managed copies, and it must bypass the import path's duplicate detection. That makes
-- it a distinct kind of durable work, so the queue records it as one — an import history that listed a
-- retry as an import would describe work the archive never did.
--
-- SQLite cannot change a CHECK constraint in place, so the `jobs` table is rebuilt with the wider
-- constraint, exactly as migration 003 did when REINDEX was added. `import_items.job_id` references
-- `jobs(id) ON DELETE CASCADE`, and a referenced table cannot be dropped while foreign keys are
-- enforced, so this migration runs with foreign keys disabled for its duration (see SchemaMigrator).
--
-- The row contents are copied unchanged: the wider constraint admits every value the old one did, so
-- nothing is rejected on the way in and existing rows keep pointing at the same jobs.

CREATE TABLE jobs_new (
    id TEXT NOT NULL PRIMARY KEY,
    collection_id TEXT REFERENCES collections (id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('IMPORT', 'REINDEX', 'RETRY')),
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'COMPLETE', 'FAILED', 'CANCELLED')),
    stage TEXT,
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed >= 0),
    total INTEGER NOT NULL DEFAULT 0 CHECK (total >= 0),
    payload TEXT,
    error_code TEXT,
    error_message TEXT,
    cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK (cancel_requested IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

INSERT INTO jobs_new (id, collection_id, type, state, stage, completed, total, payload, error_code,
                      error_message, cancel_requested, created_at, updated_at)
    SELECT id, collection_id, type, state, stage, completed, total, payload, error_code,
           error_message, cancel_requested, created_at, updated_at
    FROM jobs;

DROP TABLE jobs;

ALTER TABLE jobs_new RENAME TO jobs;

CREATE INDEX IF NOT EXISTS jobs_state ON jobs (state);
CREATE INDEX IF NOT EXISTS jobs_collection_state ON jobs (collection_id, state);
