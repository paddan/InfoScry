-- A collection-wide image rescan is a job of its own kind: it admits one Keep current text rescan per document.
-- Rows that exist keep their value; only the set of allowed job types grows.
ALTER TABLE jobs DROP CONSTRAINT jobs_type_check;

ALTER TABLE jobs
    ADD CONSTRAINT jobs_type_check
        CHECK (type IN ('IMPORT', 'REINDEX', 'RETRY', 'RESCAN', 'SELECTABLE_PDF', 'CORRECTIONS', 'IMAGE_RESCAN'));
