-- Adds the REINDEX job type.
--
-- SQLite cannot change a CHECK constraint in place, so the `jobs` table is rebuilt with the wider
-- constraint. `import_items.job_id` references `jobs(id) ON DELETE CASCADE`, and a referenced table
-- cannot be dropped while foreign keys are enforced, so this migration runs with foreign keys
-- disabled for its duration. The migrator turns the pragma off and on again around the file: a
-- `PRAGMA foreign_keys` assignment is a no-op inside a transaction, which is why this cannot be an
-- ordinary migration.
--
-- The row contents are copied unchanged: the new constraint admits every value the old one did, so
-- nothing can be rejected on the way in. The identifier column is preserved, so `import_items`
-- rows keep pointing at the same jobs after the rename.

CREATE TABLE jobs_new (
    id TEXT NOT NULL PRIMARY KEY,
    collection_id TEXT REFERENCES collections (id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('IMPORT', 'REINDEX')),
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
