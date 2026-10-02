-- OCR profiles and the collection settings that select them.
--
-- Version 17 adds the reusable OCR profiles an image-model transcription or review selects, and the OCR
-- settings a collection carries. Nothing existing changes except the new collection columns, and every
-- existing row fills them with the defaults below, so a legacy archive keeps its language and keeps
-- behaving exactly as it did: Tesseract, fill-missing, no reviewer and no external pages.
--
-- A profile's editable fields live in immutable revision rows: an edit inserts a new revision and
-- repoints the profile at it, so a job that snapshotted revision A keeps reading A after the profile is
-- edited to B. The two tables reference each other — a profile's current revision is a row of the other
-- table — which is why that one foreign key is deferred: the profile row is inserted first, carrying the
-- revision it is about to own, and the constraint is checked when the transaction commits.
--
-- No key value is stored anywhere: a revision holds the NAME of the environment variable a key is read
-- from, and the CHECK mirrors the pattern the store validates, so a key pasted into that column cannot be
-- written around the application's rule.

CREATE TABLE IF NOT EXISTS ocr_profile_revisions (
    revision_id                  TEXT    NOT NULL PRIMARY KEY,
    profile_id                   TEXT    NOT NULL,
    sequence                     INTEGER NOT NULL CHECK (sequence > 0),
    provider                     TEXT    NOT NULL CHECK (provider IN ('OPENAI_COMPATIBLE', 'ANTHROPIC')),
    endpoint                     TEXT,
    model                        TEXT    NOT NULL CHECK (length(trim(model)) > 0),
    -- `[A-Za-z_]*` covers the first character and the negated class rejects every character after it that
    -- the environment-variable rule does not allow, which is the whole `^[A-Za-z_][A-Za-z0-9_]*$` rule.
    api_key_environment_variable TEXT    CHECK (
        api_key_environment_variable IS NULL OR (
            api_key_environment_variable GLOB '[A-Za-z_]*'
            AND NOT api_key_environment_variable GLOB '*[^A-Za-z0-9_]*'
        )
    ),
    context_window               INTEGER NOT NULL CHECK (context_window > 0),
    max_output_tokens            INTEGER NOT NULL CHECK (max_output_tokens > 0),
    input_price_per_million      REAL    NOT NULL CHECK (input_price_per_million >= 0),
    output_price_per_million     REAL    NOT NULL CHECK (output_price_per_million >= 0),
    -- The measured result of a synthetic-image capability check, which the transport provides later. A
    -- declared switch is never stored here, so a claim can never masquerade as a measurement.
    image_capability_measured    INTEGER CHECK (image_capability_measured IN (0, 1)),
    image_capability_checked_at  TEXT,
    created_at                   TEXT    NOT NULL,
    UNIQUE (profile_id, sequence)
);

CREATE TABLE IF NOT EXISTS ocr_profiles (
    id                  TEXT    NOT NULL PRIMARY KEY,
    name                TEXT    NOT NULL UNIQUE COLLATE NOCASE CHECK (length(trim(name)) > 0),
    enabled             INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    current_revision_id TEXT    NOT NULL,
    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL,
    FOREIGN KEY (current_revision_id) REFERENCES ocr_profile_revisions (revision_id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX IF NOT EXISTS ocr_profile_revisions_profile ON ocr_profile_revisions (profile_id);

ALTER TABLE collections ADD COLUMN ocr_engine TEXT NOT NULL DEFAULT 'TESSERACT'
    CHECK (ocr_engine IN ('TESSERACT', 'SURYA', 'LLM'));

ALTER TABLE collections ADD COLUMN ocr_import_mode TEXT NOT NULL DEFAULT 'FILL_MISSING'
    CHECK (ocr_import_mode IN ('FILL_MISSING', 'CHECK_AND_IMPROVE'));

-- A collection names profiles by id; the referenced revisions survive an edit because an edit adds a row
-- rather than replacing one, and a deletion disables the profile instead of removing it.
ALTER TABLE collections ADD COLUMN ocr_transcription_profile_id TEXT REFERENCES ocr_profiles (id);

ALTER TABLE collections ADD COLUMN ocr_review_profile_id TEXT REFERENCES ocr_profiles (id);

ALTER TABLE collections ADD COLUMN ocr_external_page_limit INTEGER NOT NULL DEFAULT 0
    CHECK (ocr_external_page_limit >= 0);
