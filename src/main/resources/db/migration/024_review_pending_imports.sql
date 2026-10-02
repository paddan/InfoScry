-- Review-pending documents, one operation per document, and what a preview was taken with.
--
-- Three invariants ticket 07 promised and this migration makes hold at the schema rather than only in a
-- service:
--
--   * A document whose pages are waiting for a person is not "done". An import reads page images with a
--     model, and a model's reading of a page that has no text of its own is a proposal: it is retained,
--     it is shown, and it has no searchable text until somebody decides. The document's status therefore
--     says so, which is why `NEEDS_REVIEW` joins the status list — it is a lifecycle state of its own,
--     distinct from COMPLETE (which claims the reading was accepted), from COMPLETE_WITH_WARNINGS (which
--     is about units that could not be read) and from NEEDS_TOOL (which is a failure whose remedy is an
--     installation).
--   * One document is under one reading at a time. `ocr_operations` held only a uniqueness constraint on
--     request ids, and the service's check-then-insert was two transactions, so two admissions could both
--     see "no active operation" and both insert. The partial unique index below is the invariant itself:
--     at most one row per document that is still working — including one waiting for an external page
--     scope, and including a COMPLETE row whose pages are still awaiting decisions, because such a row
--     still owns the document's reading.
--   * An approval binds to what a person was shown. A preview stores the settings snapshot it resolved,
--     and admission re-resolves the collection's settings *with the overrides the preview was taken with*
--     to decide whether anything the person saw has changed. The overrides therefore travel with the
--     preview: without them, "the collection's engine" and "the engine this preview showed" are two
--     different questions and an override would look like drift.
--
-- A unit's page approval is stored beside the unit because it is a fact about the reading of that page
-- rather than about the document's attempt: a resumed import re-reads only the pages it has not committed,
-- so the pages a person still owes a decision on have to be readable from the archive rather than from the
-- memory of the attempt that read them. NULL means "no review was required of this page" — the page's own
-- text layer, a text-only format, or an import admitted before this feature existed — and is therefore not
-- a pending decision.

CREATE TABLE documents_new (
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

INSERT INTO documents_new (id, collection_id, sha256, media_type, original_filename, original_path,
                           size_bytes, status, title, author, language, error_code, error_message,
                           created_at, updated_at)
    SELECT id, collection_id, sha256, media_type, original_filename, original_path,
           size_bytes, status, title, author, language, error_code, error_message,
           created_at, updated_at
    FROM documents;

DROP TABLE documents;

ALTER TABLE documents_new RENAME TO documents;

CREATE INDEX IF NOT EXISTS documents_collection_status ON documents (collection_id, status);

-- Whether the reading of this unit awaits a person's decision. NULL is "this page owes nobody anything".
ALTER TABLE content_units ADD COLUMN page_approval TEXT
    CHECK (page_approval IS NULL OR page_approval IN ('APPROVED', 'PENDING'));

-- The overrides a preview was taken with, so admission can re-resolve exactly the settings it showed.
ALTER TABLE ocr_rescan_previews ADD COLUMN overrides TEXT;

-- One document, one reading: the operations that still hold a document, as an invariant of the schema.
CREATE UNIQUE INDEX IF NOT EXISTS ocr_operations_active_document ON ocr_operations (document_id)
    WHERE stage IN ('PREFLIGHT', 'AWAITING_APPROVAL', 'OCR', 'REVIEW', 'CHUNKING', 'EMBEDDING', 'INDEXING')
       OR (stage = 'COMPLETE' AND pending_review_count > 0);
