-- A page's source image is named whole, inside its root, or not at all.
--
-- Migration 019 added the six `source_image_*` columns by ALTER TABLE, and SQLite cannot add a table-level
-- CHECK that way, so the two promises its header made were only kept by `SourceImageProvenance`: that a
-- reference cannot climb out of the root it names, and that presence is all-or-nothing. Both matter more
-- now than when they were written, because a reviewer-facing route serves the image a page was read from
-- from this record: a reference that was absolute or escaped its root would be a file read the document
-- never owned, and a row with only some of its columns would read back as "no image" while naming one.
--
-- This migration rebuilds `page_text_revisions` with the rules in the schema itself, so nothing that writes
-- around the store can break them:
--
--   * `source_image_relative_path` is a relative reference: not blank, not absolute (a leading separator
--     or a drive letter), and with no `.` or `..` segment and no empty segment. The same rule is enforced
--     by the record (`SourceImageProvenance`), so the two cannot disagree about what is storable.
--   * The root, the reference, the hash and the rendering version are present together or absent together.
--     The dimensions are a pair of their own: a picture no reader would measure is a legitimate "unmeasured"
--     image, and half a measurement is not a measurement. Dimensions never exist without an image.
--
-- Every existing row is copied as it is. A row written through the application satisfies the new rules
-- (the record refused anything else), so every page of an archive migrated from 018 or 019 stays readable,
-- with the provenance it had or none. A row that something wrote around the application and that breaks a
-- rule fails this migration loudly instead of being rewritten: silently dropping or repairing a reference
-- would be a claim about pixels nobody looked at.
--
-- Nothing references this table, and it owns its rows through `document_revisions`, so deleting a revision,
-- a document or a collection still removes its pages.

CREATE TABLE page_text_revisions_new (
    revision_id                 TEXT    NOT NULL REFERENCES document_revisions (id) ON DELETE CASCADE,
    ordinal                     INTEGER NOT NULL CHECK (ordinal >= 0),
    unit_id                     TEXT    NOT NULL CHECK (length(unit_id) > 0),
    locator                     TEXT    NOT NULL CHECK (length(locator) > 0),
    extracted_text              TEXT    NOT NULL,
    search_text                 TEXT    NOT NULL,
    text_sha256                 TEXT    CHECK (text_sha256 IS NULL OR length(text_sha256) = 64),
    extraction_method           TEXT    CHECK (extraction_method IS NULL OR extraction_method IN ('DIRECT_TEXT', 'OCR')),
    mean_confidence             REAL,
    artifact_relative_path      TEXT,
    artifact_sha256             TEXT,
    approval                    TEXT    NOT NULL CHECK (approval IN ('APPROVED', 'PENDING', 'REJECTED')),
    created_at                  TEXT    NOT NULL,
    source_image_root           TEXT    CHECK (source_image_root IS NULL OR source_image_root IN ('MANAGED_COPY', 'ARTIFACTS')),
    source_image_relative_path  TEXT    CHECK (
        source_image_relative_path IS NULL OR (
            length(trim(source_image_relative_path)) > 0
            AND substr(source_image_relative_path, 1, 1) NOT IN ('/', '\')
            AND source_image_relative_path NOT GLOB '[A-Za-z]:*'
            AND ('/' || replace(source_image_relative_path, '\', '/') || '/') NOT LIKE '%/../%'
            AND ('/' || replace(source_image_relative_path, '\', '/') || '/') NOT LIKE '%/./%'
            AND ('/' || replace(source_image_relative_path, '\', '/') || '/') NOT LIKE '%//%'
        )
    ),
    source_image_sha256         TEXT    CHECK (source_image_sha256 IS NULL OR length(source_image_sha256) = 64),
    source_image_width          INTEGER CHECK (source_image_width IS NULL OR source_image_width > 0),
    source_image_height         INTEGER CHECK (source_image_height IS NULL OR source_image_height > 0),
    source_image_render_version INTEGER CHECK (source_image_render_version IS NULL OR source_image_render_version > 0),
    PRIMARY KEY (revision_id, ordinal),
    CHECK ((artifact_relative_path IS NULL) = (artifact_sha256 IS NULL)),
    CHECK (
        (
            source_image_root IS NULL
            AND source_image_relative_path IS NULL
            AND source_image_sha256 IS NULL
            AND source_image_render_version IS NULL
            AND source_image_width IS NULL
            AND source_image_height IS NULL
        ) OR (
            source_image_root IS NOT NULL
            AND source_image_relative_path IS NOT NULL
            AND source_image_sha256 IS NOT NULL
            AND source_image_render_version IS NOT NULL
            AND (source_image_width IS NULL) = (source_image_height IS NULL)
        )
    )
);

INSERT INTO page_text_revisions_new (
    revision_id, ordinal, unit_id, locator, extracted_text, search_text, text_sha256, extraction_method,
    mean_confidence, artifact_relative_path, artifact_sha256, approval, created_at,
    source_image_root, source_image_relative_path, source_image_sha256, source_image_width,
    source_image_height, source_image_render_version
)
SELECT
    revision_id, ordinal, unit_id, locator, extracted_text, search_text, text_sha256, extraction_method,
    mean_confidence, artifact_relative_path, artifact_sha256, approval, created_at,
    source_image_root, source_image_relative_path, source_image_sha256, source_image_width,
    source_image_height, source_image_render_version
FROM page_text_revisions;

DROP TABLE page_text_revisions;

ALTER TABLE page_text_revisions_new RENAME TO page_text_revisions;
