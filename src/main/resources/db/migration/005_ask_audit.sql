-- Durable Ask audit details. Keep 004 immutable; these columns distinguish supplied-but-uncited
-- evidence from citation ids returned by a model that were never supplied.
ALTER TABLE model_calls ADD COLUMN correction_of TEXT;
ALTER TABLE citations ADD COLUMN evidence_id TEXT;
ALTER TABLE citations ADD COLUMN returned_id TEXT;
ALTER TABLE citations ADD COLUMN supplied INTEGER NOT NULL DEFAULT 1 CHECK (supplied IN (0, 1));
ALTER TABLE citations ADD COLUMN invalid_marker INTEGER NOT NULL DEFAULT 0 CHECK (invalid_marker IN (0, 1));
CREATE INDEX IF NOT EXISTS idx_citations_evidence ON citations (conversation_id, evidence_id);
