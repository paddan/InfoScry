-- Version 4: images as content. Adds the metadata of the pictures a document yields, the checkpoints that let an
-- interrupted extraction resume, and the collection's default for how much imagery an import extracts. Every
-- existing row is kept; existing documents gain images on their next rescan with images on.

-- Immutable metadata of one image unit. A revision's images are not listed here: they are the revision's
-- `image_region` page rows in `page_text_revisions`, joined on (document_id, unit_id, artifact_sha256) through
-- `source_image_sha256`, so history and Restore work unchanged. The box is four normalized coordinates, all
-- present or all absent.
CREATE TABLE document_images (
    document_id                TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    unit_id                    TEXT    NOT NULL CHECK (length(unit_id) > 0),
    artifact_sha256            TEXT    NOT NULL CHECK (length(artifact_sha256) = 64),
    parent_unit_id             TEXT    NOT NULL CHECK (length(parent_unit_id) > 0),
    source_kind                TEXT    NOT NULL CHECK (source_kind IN ('EMBEDDED', 'PAGE_REGION', 'STANDALONE')),
    artifact_relative_path     TEXT    NOT NULL CHECK (length(trim(artifact_relative_path)) > 0),
    thumbnail_relative_path    TEXT    NOT NULL CHECK (length(trim(thumbnail_relative_path)) > 0),
    media_type                 TEXT    NOT NULL CHECK (length(media_type) > 0),
    width_px                   BIGINT  NOT NULL CHECK (width_px > 0),
    height_px                  BIGINT  NOT NULL CHECK (height_px > 0),
    box_left                   DOUBLE PRECISION,
    box_top                    DOUBLE PRECISION,
    box_right                  DOUBLE PRECISION,
    box_bottom                 DOUBLE PRECISION,
    caption                    TEXT,
    caption_source             TEXT    CHECK (caption_source IS NULL OR caption_source IN ('ALT_TEXT', 'FIGCAPTION', 'LAYOUT_CAPTION')),
    created_at                 TEXT    NOT NULL,
    PRIMARY KEY (document_id, unit_id, artifact_sha256),
    CHECK (
        (box_left IS NULL AND box_top IS NULL AND box_right IS NULL AND box_bottom IS NULL)
        OR (box_left IS NOT NULL AND box_top IS NOT NULL AND box_right IS NOT NULL AND box_bottom IS NOT NULL)
    )
);

-- One row per target, parent unit and mode: the outcome of looking for images in one parent unit, so a resumed
-- extraction skips what it already did. `target` is the text fingerprint for an import and the candidate
-- revision for a rescan.
CREATE TABLE image_extraction_checkpoints (
    document_id        TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    target             TEXT    NOT NULL CHECK (length(target) > 0),
    parent_unit_id     TEXT    NOT NULL CHECK (length(parent_unit_id) > 0),
    mode               TEXT    NOT NULL CHECK (mode IN ('EMBEDDED', 'ALL')),
    extractor_version  TEXT    NOT NULL CHECK (length(extractor_version) > 0),
    outcome            TEXT    NOT NULL CHECK (outcome IN ('DONE', 'FAILED')),
    image_count        BIGINT  NOT NULL CHECK (image_count >= 0),
    warning            TEXT,
    committed_at       TEXT    NOT NULL,
    PRIMARY KEY (document_id, target, parent_unit_id, mode)
);

ALTER TABLE collections
    ADD COLUMN default_image_extraction TEXT NOT NULL DEFAULT 'OFF'
        CHECK (default_image_extraction IN ('OFF', 'EMBEDDED', 'ALL'));
