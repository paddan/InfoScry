-- Root-confined, all-or-nothing source-image provenance, enforced by the schema itself.
--
-- Migration 019 added the six provenance columns one ALTER TABLE at a time, and SQLite cannot attach a
-- table-level CHECK to an existing table that way. The all-or-nothing presence rule therefore lived only
-- in `SourceImageProvenance`, which every store write goes through — but a write that never touched the
-- store (raw SQL, a hand-edited archive, a past bug) could still land a row naming a root with no
-- reference, a hash beside no reference, a width with no height, or a reference that is absolute or
-- climbs out of the root it names. Each of those is a claim about pixels no root of the document holds,
-- and a read that silently reported them as "no image was observed" would turn corruption into a fact.
--
-- The audit runs before the constraints, on the rows an archive already holds. A row whose provenance is
-- partially present, half-measured, or present but unusable (an absolute or escaping reference, a digest
-- that is not a SHA-256) has its six provenance columns cleared to NULL — the page's text, chunks and
-- identity are never touched, and no revision is deleted and no hash is guessed: a reference that names
-- no file is not evidence, and clearing it records the one honest answer, that no usable image was
-- observed for that page. An entirely absent provenance is valid and stays absent; a complete and
-- confined one is valid and survives byte for byte.
--
-- The rebuild then makes the rules part of the table. SQLite widens a CHECK only by rebuilding, and
-- `page_text_revisions` is referenced by no other table (its own reference to `document_revisions` is
-- recreated unchanged), so the rebuild copies every row and every other column as it is. After this
-- migration no connection — the store or raw SQL — can write a half-present provenance, a half
-- measurement, or a reference that leaves its named root.

-- Audit 1: partially present core provenance, or a measurement present in only one axis.
UPDATE page_text_revisions
SET source_image_root = NULL,
    source_image_relative_path = NULL,
    source_image_sha256 = NULL,
    source_image_width = NULL,
    source_image_height = NULL,
    source_image_render_version = NULL
WHERE NOT (source_image_root IS NULL AND source_image_relative_path IS NULL AND source_image_sha256 IS NULL
           AND source_image_width IS NULL AND source_image_height IS NULL
           AND source_image_render_version IS NULL)
  AND (source_image_root IS NULL OR source_image_relative_path IS NULL OR source_image_sha256 IS NULL
       OR source_image_render_version IS NULL
       OR (source_image_width IS NULL) <> (source_image_height IS NULL));

-- Audit 2: present but unusable — a digest that is not a SHA-256, or a reference that is absolute,
-- climbs out of its named root with `..`, or names the root itself rather than a file inside it.
UPDATE page_text_revisions
SET source_image_root = NULL,
    source_image_relative_path = NULL,
    source_image_sha256 = NULL,
    source_image_width = NULL,
    source_image_height = NULL,
    source_image_render_version = NULL
WHERE source_image_relative_path IS NOT NULL
  AND (length(source_image_sha256) <> 64
       OR source_image_relative_path LIKE '/%'
       OR source_image_relative_path = '.'
       OR source_image_relative_path = './'
       OR source_image_relative_path = '..'
       OR source_image_relative_path LIKE '../%'
       OR source_image_relative_path LIKE '%/../%'
       OR source_image_relative_path LIKE '%/..');

CREATE TABLE page_text_revisions_new (
    revision_id          TEXT    NOT NULL REFERENCES document_revisions (id) ON DELETE CASCADE,
    ordinal              INTEGER NOT NULL CHECK (ordinal >= 0),
    unit_id              TEXT    NOT NULL CHECK (length(unit_id) > 0),
    locator              TEXT    NOT NULL CHECK (length(locator) > 0),
    extracted_text       TEXT    NOT NULL,
    search_text          TEXT    NOT NULL,
    text_sha256          TEXT    CHECK (text_sha256 IS NULL OR length(text_sha256) = 64),
    extraction_method    TEXT    CHECK (extraction_method IS NULL OR extraction_method IN ('DIRECT_TEXT', 'OCR')),
    mean_confidence      REAL,
    artifact_relative_path TEXT,
    artifact_sha256      TEXT,
    source_image_root TEXT
        CHECK (source_image_root IS NULL OR source_image_root IN ('MANAGED_COPY', 'ARTIFACTS')),
    -- A reference is confined to the root it names: relative, never climbing out with `..`, and never
    -- naming the root itself. This is the same lexical rule the domain record and `PageImage` apply, so
    -- what raw SQL can store is what a rebuild of the page image can open.
    source_image_relative_path TEXT
        CHECK (source_image_relative_path IS NULL OR (
            length(source_image_relative_path) > 0
            AND source_image_relative_path NOT LIKE '/%'
            AND source_image_relative_path <> '.'
            AND source_image_relative_path <> './'
            AND source_image_relative_path <> '..'
            AND source_image_relative_path NOT LIKE '../%'
            AND source_image_relative_path NOT LIKE '%/../%'
            AND source_image_relative_path NOT LIKE '%/..'
        )),
    source_image_sha256 TEXT
        CHECK (source_image_sha256 IS NULL OR length(source_image_sha256) = 64),
    source_image_width INTEGER
        CHECK (source_image_width IS NULL OR source_image_width > 0),
    source_image_height INTEGER
        CHECK (source_image_height IS NULL OR source_image_height > 0),
    source_image_render_version INTEGER
        CHECK (source_image_render_version IS NULL OR source_image_render_version > 0),
    approval             TEXT    NOT NULL CHECK (approval IN ('APPROVED', 'PENDING', 'REJECTED')),
    created_at           TEXT    NOT NULL,
    unit_key             TEXT,
    PRIMARY KEY (revision_id, ordinal),
    CHECK ((artifact_relative_path IS NULL) = (artifact_sha256 IS NULL)),
    -- Presence is all-or-nothing: either all six source-image columns are absent — no image was observed
    -- for the page, which is the truthful answer for a text-layer reading or a pre-019 row — or the core
    -- four name an artifact completely and the dimensions are both present (positive, per their own
    -- CHECKs) or both absent. Half a provenance is not a provenance, and half a measurement is not a
    -- measurement.
    CHECK (
        (source_image_root IS NULL AND source_image_relative_path IS NULL AND source_image_sha256 IS NULL
         AND source_image_width IS NULL AND source_image_height IS NULL
         AND source_image_render_version IS NULL)
        OR
        (source_image_root IS NOT NULL AND source_image_relative_path IS NOT NULL
         AND source_image_sha256 IS NOT NULL AND source_image_render_version IS NOT NULL
         AND ((source_image_width IS NULL AND source_image_height IS NULL)
              OR (source_image_width IS NOT NULL AND source_image_height IS NOT NULL)))
    )
);

INSERT INTO page_text_revisions_new (
    revision_id, ordinal, unit_id, locator, extracted_text, search_text, text_sha256,
    extraction_method, mean_confidence, artifact_relative_path, artifact_sha256,
    source_image_root, source_image_relative_path, source_image_sha256, source_image_width,
    source_image_height, source_image_render_version, approval, created_at, unit_key
)
SELECT revision_id, ordinal, unit_id, locator, extracted_text, search_text, text_sha256,
       extraction_method, mean_confidence, artifact_relative_path, artifact_sha256,
       source_image_root, source_image_relative_path, source_image_sha256, source_image_width,
       source_image_height, source_image_render_version, approval, created_at, unit_key
FROM page_text_revisions;

DROP TABLE page_text_revisions;

ALTER TABLE page_text_revisions_new RENAME TO page_text_revisions;
