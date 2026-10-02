-- Rescan jobs, their durable operations, previews, and external-page admission.
--
-- A rescan is deliberately not a retry: a retry reads an unfinished document again from its managed copy,
-- while a rescan re-reads a document that is already published, from page images, and may replace its text
-- through a reviewed revision. The queue therefore records it as its own kind of work, exactly as RETRY
-- was added. SQLite cannot change a CHECK constraint in place, so the `jobs` table is rebuilt with the wider
-- constraint; `import_items.job_id` references `jobs(id) ON DELETE CASCADE` and a referenced table cannot be
-- dropped while foreign keys are enforced, so this migration runs with foreign keys disabled for its
-- duration (see SchemaMigrator). The rows are copied unchanged, and the wider constraint admits every value
-- the old one did.

CREATE TABLE jobs_new (
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

INSERT INTO jobs_new (id, collection_id, type, state, stage, completed, total, payload, error_code,
                      error_message, cancel_requested, created_at, updated_at, current_item)
    SELECT id, collection_id, type, state, stage, completed, total, payload, error_code,
           error_message, cancel_requested, created_at, updated_at, current_item
    FROM jobs;

DROP TABLE jobs;

ALTER TABLE jobs_new RENAME TO jobs;

CREATE INDEX IF NOT EXISTS jobs_state ON jobs (state);
CREATE INDEX IF NOT EXISTS jobs_collection_state ON jobs (collection_id, state);

-- One preview: what a person was shown before a rescan was admitted.
--
-- The preview is durable because admission revalidates against it: the document's managed hash, the
-- revision that would be the baseline, and the *snapshot* whose approval scope an external dispatch is
-- bound to. A preview that lived only in a browser's memory could not be re-checked a moment later, and a
-- rescan admitted against settings the person never saw is not the operation they agreed to. The snapshot
-- and its hash are both stored: the hash is what an approval names, so "approved for this scope" survives
-- without re-encoding the snapshot.
CREATE TABLE IF NOT EXISTS ocr_rescan_previews (
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
    expires_at                TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS ocr_rescan_previews_document ON ocr_rescan_previews (document_id, created_at);

-- One durable rescan operation: the reading attempt a document is under.
--
-- It is separate from the job because a job is one *attempt* and an operation outlives it: an attempt that
-- paused for external approval, failed on a missing tool, or was cancelled is resumed by queuing another
-- attempt, and every one of them continues the same operation with the same immutable snapshot and the same
-- counters. `current_job_id` names the attempt that owns it right now, so a cancellation request and a
-- progress read have something to point at.
--
-- The snapshot is the whole of what the attempt is: editing a collection default afterwards changes future
-- operations only, and a resume may not substitute a different engine, model or prompt version. `stage` is
-- the durable lifecycle, and it is deliberately not the review backlog: an operation can be COMPLETE while
-- pages are still awaiting a person's decision, which is what the review counts are for.
--
-- `request_id`/`request_hash` are the idempotency pair. Repeating a rescan with the same request id and the
-- same body is the same operation; the same id with a different body is a conflict rather than a second
-- reading, because "admit this again" must never mean two attempts racing one document.
CREATE TABLE IF NOT EXISTS ocr_operations (
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

-- What "is this document already being rescanned" asks, and what the detail view reads.
CREATE INDEX IF NOT EXISTS ocr_operations_document ON ocr_operations (document_id, created_at);
CREATE INDEX IF NOT EXISTS ocr_operations_job ON ocr_operations (current_job_id);

-- The distinct document/page identities that were sent to an external provider, once per identity.
--
-- This is the page allowance's unit and nothing else: a page read by transcription and then judged by an
-- external reviewer was sent twice but is one page here, because the allowance is "how many of my pages
-- leave this Mac", not "how many requests were paid for". `owner_kind`/`owner_id` name what the allowance
-- belongs to: one document's rescan operation, or one import job — an import of twenty files has one
-- allowance covering the pages of all of them.
CREATE TABLE IF NOT EXISTS ocr_external_pages (
    owner_kind    TEXT    NOT NULL CHECK (owner_kind IN ('OPERATION', 'JOB')),
    owner_id      TEXT    NOT NULL,
    document_id   TEXT    NOT NULL,
    unit_id       TEXT    NOT NULL CHECK (length(trim(unit_id)) > 0),
    ordinal       INTEGER NOT NULL CHECK (ordinal >= 0),
    first_sent_at TEXT    NOT NULL,
    PRIMARY KEY (owner_kind, owner_id, document_id, unit_id)
);

CREATE INDEX IF NOT EXISTS ocr_external_pages_owner ON ocr_external_pages (owner_kind, owner_id);

-- Provider calls, counted per page and stage, retries included.
--
-- Separate from the page table on purpose: a page limit is not a currency cap, and a retried request is a
-- second call. The two numbers are read together, and the UI explains them — one distinct page, two calls
-- for a page that was both transcribed and reviewed.
CREATE TABLE IF NOT EXISTS ocr_external_calls (
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

-- One approval of an external page scope, granted by a person, bound to the scope it was granted for.
--
-- The approval names the snapshot hash it was granted against, so a resumed attempt that would dispatch
-- under another engine, endpoint, model, prompt version or policy cannot inherit it: a changed scope is a
-- new approval. `authorized_distinct_pages` is the *maximum* distinct pages approved, which is why the
-- latest approval for an owner is the one that counts and why the count itself is never reset by one.
CREATE TABLE IF NOT EXISTS ocr_external_approvals (
    approval_id              TEXT    NOT NULL PRIMARY KEY,
    owner_kind               TEXT    NOT NULL CHECK (owner_kind IN ('OPERATION', 'JOB')),
    owner_id                 TEXT    NOT NULL,
    snapshot_hash            TEXT    NOT NULL CHECK (length(snapshot_hash) = 64),
    authorized_distinct_pages INTEGER NOT NULL CHECK (authorized_distinct_pages >= 0),
    created_at               TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS ocr_external_approvals_owner
    ON ocr_external_approvals (owner_kind, owner_id, created_at);
