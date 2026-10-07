-- Ensure only one CANDIDATE revision per document+fingerprint pair can exist at a time.
--
-- A resumed check-and-improve import looks up its own candidate by document_id and attempt_fingerprint.
-- Without this constraint, two concurrent resumes could create duplicate candidates. With it, a racing
-- resume that hits the violation will adopt the existing candidate instead of failing.
--
-- The index is partial: it applies only to CANDIDATE rows with a non-null attempt_fingerprint.
-- Published, superseded, and withdrawn revisions do not participate. Candidates without a fingerprint
-- (NULL) are also excluded so that multiple stagings without a fingerprint are allowed — only a
-- resumed reading (one with an attempt_fingerprint) is unique.

CREATE UNIQUE INDEX IF NOT EXISTS document_revisions_resume_uniqueness
    ON document_revisions (document_id, attempt_fingerprint)
    WHERE state = 'CANDIDATE' AND attempt_fingerprint IS NOT NULL;
