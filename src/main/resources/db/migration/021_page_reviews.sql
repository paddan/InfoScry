-- Durable page reviews: what one comparison of a page's published text with a candidate reading concluded.
--
-- A review is the pilot's unit of work. It has to outlive the process that made it for three reasons: the
-- same comparison may be asked for again (and a paid reviewer call must not be paid for twice), a proposal
-- has to stay pending until a person decides about it, and the decision a page carries has to be attributable
-- to the reviewer, the policy and the texts it was made against.
--
-- The identity of a review is its fingerprint *and* the page image it judged. The fingerprint covers the
-- baseline revision and the hash of its text, the candidate's text hash, the reviewer revision, the review
-- prompt version and the policy version; the image hash is separate because the fingerprint record cannot
-- carry it without changing a digest that shipped before this table existed. A review of other pixels is not
-- this review, so a re-render of the same page with the same texts recomputes rather than reuses.
--
-- Nothing here is page text. A review holds hashes plus bounded reasons and spans, so a stored proposal
-- cannot become a second copy of the page, and no accepted text is ever a merged third reading: the accepted
-- text stays exactly one of the two readings or a person's edit.
--
-- `baseline_revision_id` and `baseline_text_hash` are absent together for a page with no baseline, which is a
-- page whose document publishes no text there. A review against a baseline stays attributable to that
-- revision, so a decision taken before another publication can be told apart from one that still applies.
--
-- `outcome_code` is the safe code of a comparison whose recommendation no reviewer answer stands behind: a
-- review that could not be made (`OCR_TIMEOUT`, `OCR_MALFORMED_RESPONSE`, ...) or one that never needed a
-- reviewer (`IDENTICAL_TEXT`, `NO_BASELINE`, `EMPTY_PAIR`, `UNSUPPORTED_REVIEW`). It is null when the
-- reviewer answered. `confidence` is the reviewer's own number, recorded for the person reading the review
-- and never an input to a decision.
--
-- `searchable` is written down rather than derived, because it is the answer a retrieval path asks: a pending
-- candidate is not searchable, a page with a baseline keeps that baseline's text, and a page with no baseline
-- that nobody has approved has no searchable text at all.

CREATE TABLE IF NOT EXISTS page_reviews (
    review_fingerprint     TEXT    NOT NULL CHECK (length(review_fingerprint) = 64),
    image_sha256           TEXT    NOT NULL CHECK (length(image_sha256) = 64),
    document_id            TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    unit_id                TEXT    NOT NULL CHECK (length(trim(unit_id)) > 0),
    ordinal                INTEGER NOT NULL CHECK (ordinal >= 0),
    baseline_revision_id   TEXT,
    baseline_text_hash     TEXT    CHECK (baseline_text_hash IS NULL OR length(baseline_text_hash) = 64),
    candidate_hash         TEXT    NOT NULL CHECK (length(candidate_hash) = 64),
    recommendation         TEXT    NOT NULL CHECK (
        recommendation IN ('EXISTING_BETTER', 'NEW_BETTER', 'UNCERTAIN')
    ),
    disposition            TEXT    NOT NULL CHECK (disposition IN ('KEEP', 'PROPOSE', 'APPROVE')),
    confidence             REAL    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    reviewer_revision_id   TEXT    NOT NULL CHECK (length(trim(reviewer_revision_id)) > 0),
    reviewer_model_version TEXT,
    review_prompt_version  INTEGER NOT NULL CHECK (review_prompt_version > 0),
    policy_version         INTEGER NOT NULL CHECK (policy_version > 0),
    outcome_code           TEXT    CHECK (outcome_code IS NULL OR length(trim(outcome_code)) > 0),
    searchable             INTEGER NOT NULL CHECK (searchable IN (0, 1)),
    -- The bounded reasons, as JSON: a code, its origin, the spans it is about and, for a reviewer's own
    -- reason, its bounded explanation.
    reasons                TEXT    NOT NULL,
    created_at             TEXT    NOT NULL,
    PRIMARY KEY (review_fingerprint, image_sha256),
    CHECK ((baseline_revision_id IS NULL) = (baseline_text_hash IS NULL))
);

-- What "Needs review · N pages" asks for: one document's pending proposals, in reading order.
CREATE INDEX IF NOT EXISTS page_reviews_pending ON page_reviews (document_id, disposition, ordinal);

-- What a page's reviews are read back by, for the details and history views.
CREATE INDEX IF NOT EXISTS page_reviews_page ON page_reviews (document_id, unit_id, ordinal);
