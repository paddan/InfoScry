-- The pixels one revision page's reading was actually made from.
--
-- A durable reading that does not name the image it came from cannot be told apart from a reading of
-- another image, and here the difference is real evidence rather than bookkeeping: a picture whose
-- declared raster is past the bound a tool is handed is read from a *bounded copy* this pipeline wrote,
-- and that copy's pixels are not the managed original's. A reviewer comparing a rescan against what the
-- document says, and a comparison that has to show the image the text came from, both need to know which
-- of the two a page's reading was made from — and a reading made from one is not a reading of the other.
--
-- `source_image_root` says which directory `source_image_relative_path` resolves against, because a page
-- image is only ever a file inside the root its producer chose: either the document's own `artifacts/`
-- directory (a rendered PDF page, or a bounded copy written for one attempt) or the directory holding the
-- document's managed copy (a picture that *is* the document, read as itself). Both roots sit under the
-- document's own directory, so a root and a relative reference name one file without the schema ever
-- holding an absolute path, and the reference cannot climb out of the root it names.
--
-- The hash and the dimensions are read back from the artifact rather than taken from what a producer
-- intended to write, and `source_image_render_version` names the procedure that produced those pixels: 1
-- for a page this pipeline rendered or a picture read as itself, 2 for the bounded copy of a picture.
--
-- Every column is nullable and every row that has no image leaves all of them absent: a text-layer page
-- was never read from a raster, and a revision staged before this migration has no image anybody
-- observed. Absent means "no image", and zero, an empty reference or a guessed hash would each be a claim
-- about pixels nobody looked at. Presence is all-or-nothing, which SQLite cannot express as a table-level
-- CHECK on an ALTER TABLE; `SourceImageProvenance` is what enforces it, and it is the only way a write
-- reaches these columns.
ALTER TABLE page_text_revisions ADD COLUMN source_image_root TEXT
    CHECK (source_image_root IS NULL OR source_image_root IN ('MANAGED_COPY', 'ARTIFACTS'));

ALTER TABLE page_text_revisions ADD COLUMN source_image_relative_path TEXT
    CHECK (source_image_relative_path IS NULL OR length(source_image_relative_path) > 0);

ALTER TABLE page_text_revisions ADD COLUMN source_image_sha256 TEXT
    CHECK (source_image_sha256 IS NULL OR length(source_image_sha256) = 64);

ALTER TABLE page_text_revisions ADD COLUMN source_image_width INTEGER
    CHECK (source_image_width IS NULL OR source_image_width > 0);

ALTER TABLE page_text_revisions ADD COLUMN source_image_height INTEGER
    CHECK (source_image_height IS NULL OR source_image_height > 0);

ALTER TABLE page_text_revisions ADD COLUMN source_image_render_version INTEGER
    CHECK (source_image_render_version IS NULL OR source_image_render_version > 0);
