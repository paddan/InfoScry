-- What a resumed check-and-improve import needs to find its own candidate again.
--
-- A candidate revision is staged page by page, and a process that dies after page 1 must continue into the
-- same revision and skip page 1 rather than open a second candidate and read it again. Neither fact was
-- recorded: the revision did not say which reading (extraction fingerprint) opened it, and a staged page did
-- not say which extractor key it was read for. Both are nullable because a revision or page written before
-- this migration, and every published or rescan revision, has no such binding and is never resumed by key.
ALTER TABLE document_revisions ADD COLUMN attempt_fingerprint TEXT;
ALTER TABLE page_text_revisions ADD COLUMN unit_key TEXT;
