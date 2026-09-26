-- A short title written from the opening question, so a reader recognises a stored conversation by
-- what it was about instead of its first 180 characters. Nullable: existing rows stay unset (no
-- backfill, no re-titling) and the list endpoints fall back to the truncated opening question.
ALTER TABLE conversations ADD COLUMN title TEXT;