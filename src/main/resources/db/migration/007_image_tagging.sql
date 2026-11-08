-- Version 7: what an image shows. One stored analysis per image and set of inputs, the edits people made on top
-- of every analysis, the collection's analysis defaults and the categories a picture row is filtered by. Every
-- existing row is kept; a text unit's category list is empty, and the tables go with their document.

CREATE TABLE image_analyses (
    -- Insertion order: analyses that share a timestamp are read back in the order they were written.
    recorded_seq        BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    document_id         TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    unit_id             TEXT    NOT NULL CHECK (length(unit_id) > 0),
    image_sha256        TEXT    NOT NULL CHECK (length(image_sha256) = 64),
    profile_revision_id TEXT    NOT NULL CHECK (length(trim(profile_revision_id)) > 0),
    prompt_version      BIGINT  NOT NULL CHECK (prompt_version > 0),
    language            TEXT    NOT NULL CHECK (length(trim(language)) > 0),
    identify_appearance BIGINT  NOT NULL CHECK (identify_appearance IN (0, 1)),
    outcome             TEXT    NOT NULL CHECK (outcome IN ('DONE', 'FAILED')),
    description         TEXT,
    tags                JSONB   NOT NULL DEFAULT '[]'::jsonb,
    error_code          TEXT,
    created_at          TEXT    NOT NULL,
    PRIMARY KEY (document_id, unit_id, image_sha256, profile_revision_id, prompt_version, language,
                 identify_appearance),
    CHECK (
        (outcome = 'DONE' AND description IS NOT NULL AND error_code IS NULL)
        OR (outcome = 'FAILED' AND error_code IS NOT NULL)
    )
);

CREATE INDEX image_analyses_image ON image_analyses (document_id, unit_id, image_sha256, outcome);

CREATE TABLE image_tag_edits (
    -- Insertion order is creation order: edits apply to an analysis in the order people made them.
    edit_seq    BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    document_id TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    unit_id     TEXT    NOT NULL CHECK (length(unit_id) > 0),
    edit_id     TEXT    NOT NULL CHECK (length(trim(edit_id)) > 0),
    action      TEXT    NOT NULL CHECK (action IN ('ADD', 'REMOVE', 'CONFIRM')),
    tag         JSONB   NOT NULL,
    created_by  TEXT    REFERENCES users (id),
    created_at  TEXT    NOT NULL,
    PRIMARY KEY (document_id, unit_id, edit_id)
);

CREATE INDEX image_tag_edits_unit ON image_tag_edits (document_id, unit_id);

ALTER TABLE collections
    ADD COLUMN default_image_profile_id   TEXT CHECK (
        default_image_profile_id IS NULL OR length(trim(default_image_profile_id)) > 0
    ),
    ADD COLUMN default_identify_appearance BIGINT NOT NULL DEFAULT 0
        CHECK (default_identify_appearance IN (0, 1));

-- What category of thing a picture's unit is searchable by. A text unit has no picture category, which is
-- the empty list, so a category filter never matches one.
ALTER TABLE search_chunks
    ADD COLUMN image_categories TEXT[] NOT NULL DEFAULT '{}';

-- The analysis stage is another external dispatch a permit is counted for.
ALTER TABLE ocr_external_calls DROP CONSTRAINT ocr_external_calls_stage_check;

ALTER TABLE ocr_external_calls
    ADD CONSTRAINT ocr_external_calls_stage_check
        CHECK (stage IN ('TRANSCRIPTION', 'REVIEW', 'IMAGE_ANALYSIS'));
