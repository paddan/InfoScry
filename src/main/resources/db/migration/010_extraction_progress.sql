-- Durable extraction progress: what a document's current attempt is reading, and how far it has got.
--
-- Two facts a finished summary cannot express are the ones a reader needs *while* a document is still being
-- read: what one unit of it is called (pages, sections, sheets, slides, lines, images) and how many units
-- the extractor announced it has. Both are written by the attempt itself. `total_units` stays NULL when the
-- extractor had no total to announce, because an unknown total shown as a number would be an invented
-- denominator, and a count without one is the honest thing to display.
--
-- The method of a unit is a column of the unit rather than a counter on this row, because a document is
-- commonly read both ways: one page has its own text layer and the next is a scan. It is stored as
-- DIRECT_TEXT or OCR by the extractor that produced the unit, never inferred from a unit's mean confidence —
-- a parser's text and a tool's text are two methods, not two confidence values.

ALTER TABLE content_units ADD COLUMN extraction_method TEXT
    CHECK (extraction_method IS NULL OR extraction_method IN ('DIRECT_TEXT', 'OCR'));

-- One row per document: the attempt being worked on, and what it announced before it finished.
--
-- The completed and failed counters are deliberately *not* columns here. A stored counter is a number that
-- can go wrong, and the rows that make it up are the same checkpoints a resume skips, so counting those rows
-- is what makes "progress advances only with a committed unit" true by construction rather than by care.
CREATE TABLE IF NOT EXISTS document_extraction_progress (
    document_id TEXT NOT NULL PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    fingerprint TEXT NOT NULL CHECK (length(fingerprint) > 0),
    unit_kind TEXT CHECK (unit_kind IS NULL OR unit_kind IN ('PAGE', 'SECTION', 'SLIDE', 'SHEET', 'LINE', 'IMAGE')),
    total_units INTEGER CHECK (total_units IS NULL OR total_units >= 0),
    started_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    -- A total belongs to something: a count with no unit kind to count is a number nobody can read.
    CHECK ((total_units IS NULL) OR (unit_kind IS NOT NULL))
);
