-- Accepted validation records: the measured evidence that one policy/reviewer/prompt combination was
-- judged good enough, by a person, to replace text without one.
--
-- This table is the whole of the automatic-replacement gate. Pilot mode replaces nothing without a manual
-- decision, and the only thing that may allow more is a record read from here, keyed by the combination it
-- was accepted for. An approval is therefore impossible without a persisted, accepted row: no policy object,
-- no argument and no settings edit can carry a record that was never written, and the store that reads this
-- table is the only producer of the record value a policy can hold.
--
-- The combination is the key because acceptance is evidence about *specific* work: a recorded threshold that
-- was accepted for one reviewer revision, prompt version and policy version says nothing about another
-- combination, and a changed model, prompt or policy must fall back to manual/pilot approval rather than
-- inherit an old acceptance.
--
-- `validation_id` is the acceptance flow's own name for the record, for the report and the audit trail.
-- Nothing in this build writes a row: the measurement and acceptance flow (its evaluation runner, its
-- explicit user acceptance and its held-out evidence) owns that write together with the additional keys it
-- needs. What ships here is the read path and the columns the decision path checks.
--
-- `review_profile_revision_id` is the immutable reviewer revision the acceptance was measured with. It is
-- deliberately not a foreign key: the record is evidence about a revision rather than a pointer to whatever
-- profile names it now, and a comparison resolves its own reviewer revision and refuses an unknown one — so a
-- record whose revision no longer exists approves nothing. Ticket 11 adds the transcription/model keys and
-- the corpus manifest its measurement binds, and may then reference the rows it measures against.
--
-- Deliberately absent: no document, collection, unit or page column. Acceptance is about the combination
-- above, never about one page — a record that named a page would be an approval of that page's text, and a
-- decision about a page's text is a review decision, not an activation.

CREATE TABLE IF NOT EXISTS ocr_validation_records (
    policy_version            INTEGER NOT NULL CHECK (policy_version > 0),
    review_profile_revision_id TEXT   NOT NULL CHECK (length(trim(review_profile_revision_id)) > 0),
    review_prompt_version     INTEGER NOT NULL CHECK (review_prompt_version > 0),
    validation_id             TEXT    NOT NULL CHECK (length(trim(validation_id)) > 0),
    accepted_at               TEXT    NOT NULL,
    PRIMARY KEY (policy_version, review_profile_revision_id, review_prompt_version)
);
