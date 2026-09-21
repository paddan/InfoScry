-- Core schema: collections, documents, jobs, import items, and durable deletion records.
--
-- Naming: every identifier and column is English and lower_snake_case. Instants are ISO-8601 UTC
-- strings written by the persistence boundary. Enum columns carry a CHECK that mirrors the Kotlin
-- enum of the same name, so an unknown value cannot reach the database.

-- One row per applied migration. `PRAGMA user_version` carries the same number; this table is the
-- durable audit trail of when each migration ran.
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER NOT NULL PRIMARY KEY,
    applied_at TEXT NOT NULL
);

-- A collection is a manually created logical search boundary, not a mirror of a filesystem
-- directory. `lifecycle` is separate from document status: DELETING rejects new work and reads
-- while the deletion operation below removes the collection's files and rows.
CREATE TABLE IF NOT EXISTS collections (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    description TEXT,
    ocr_languages TEXT NOT NULL DEFAULT 'eng' CHECK (length(trim(ocr_languages)) > 0),
    lifecycle TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE', 'DELETING')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Collection names are unique case-insensitively, so "Default" and "default" cannot coexist. The
-- index is the authority; the store translates its violation into a typed error.
CREATE UNIQUE INDEX IF NOT EXISTS collections_name_unique ON collections (name COLLATE NOCASE);

-- One immutable imported original. sha256 identifies the bytes; the unique constraint is per
-- collection, so the same bytes may exist in two collections as two documents.
CREATE TABLE IF NOT EXISTS documents (
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
            'COMPLETE', 'COMPLETE_WITH_WARNINGS', 'FAILED', 'CANCELLED', 'NEEDS_TOOL'
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

CREATE INDEX IF NOT EXISTS documents_collection_status ON documents (collection_id, status);

-- A durable unit of background work. `stage` is free text reported while the job runs; `state` is
-- whether a worker still owns it. `payload` is the job type's own JSON. Cancellation is requested
-- in the database first so a restart cannot lose the request. `collection_id` is nullable because
-- some job types (reindexing) are not owned by one collection.
CREATE TABLE IF NOT EXISTS jobs (
    id TEXT NOT NULL PRIMARY KEY,
    collection_id TEXT REFERENCES collections (id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('IMPORT')),
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

CREATE INDEX IF NOT EXISTS jobs_state ON jobs (state);
CREATE INDEX IF NOT EXISTS jobs_collection_state ON jobs (collection_id, state);

-- One row per selected path inside an import job. `item_key` is deterministic for the job, so
-- re-enumerating a directory never queues the same path twice. `document_id` is written as soon as
-- the managed copy exists, so a restart resumes that document instead of re-importing the path.
CREATE TABLE IF NOT EXISTS import_items (
    id TEXT NOT NULL PRIMARY KEY,
    job_id TEXT NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    item_key TEXT NOT NULL CHECK (length(item_key) > 0),
    source_path TEXT NOT NULL CHECK (length(source_path) > 0),
    document_id TEXT REFERENCES documents (id) ON DELETE SET NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('PENDING', 'IMPORTED', 'DUPLICATE', 'FAILED')),
    error_code TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (job_id, item_key)
);

CREATE INDEX IF NOT EXISTS import_items_job_outcome ON import_items (job_id, outcome);

-- The durable record of a collection deletion. `collection_id` is deliberately NOT a foreign key:
-- this row must survive the cascade that removes the collection, because it is what recovery reads
-- to finish moving files, deleting rows and index entries, and purging the trash directory.
CREATE TABLE IF NOT EXISTS deletion_operations (
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
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS deletion_operations_phase ON deletion_operations (phase);

-- The default collection is created once, for users who do not organise their material. It uses a
-- stable identifier so nothing has to look it up by name.
INSERT INTO collections (id, name, description, ocr_languages, lifecycle, created_at, updated_at)
SELECT
    'default',
    'Default',
    'Documents that are not organised into another collection.',
    'eng',
    'ACTIVE',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE NOT EXISTS (SELECT 1 FROM collections);
