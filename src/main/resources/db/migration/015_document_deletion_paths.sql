-- The source paths a document deletion owns, recorded so a document copied from one of them while the
-- deletion runs cannot survive it.
--
-- A document deletion removes the documents its caller selected. An import that was already in flight
-- can finish copying one of those documents' source paths a moment after the deletion was admitted,
-- which creates a *new* document under a new identifier: the deleted target is not resurrected, but the
-- path it was imported from is, and reading that new document publishes exactly what the deletion
-- decided against. A per-file repair cannot close that window — the file's own attempt may be
-- interrupted, killed, or simply lose the race — so the operation owns the paths instead and sweeps them
-- before it is done, while it still holds exclusive maintenance and nothing else can be writing.
--
-- The path is recorded per target at admission, read from the document row before that row is removed.
-- It is deliberately not a path tombstone: nothing consults it once the operation is DONE, so a later
-- explicit import of the same path is an ordinary new document and is never refused by an old deletion.
ALTER TABLE document_deletion_targets ADD COLUMN source_path TEXT;

-- Whether the file's own copy created the document it is attached to.
--
-- A duplicate and a fresh copy both leave an item naming a document, and no clock or path comparison can
-- tell them apart: a duplicate found at the same path has the same path, and both writes can fall in the
-- same millisecond. The deletion sweep needs that distinction, because it may only remove documents a
-- cancelled file *created* at one of its paths — never a document the file merely found, which belongs to
-- whatever imported it first. Existing rows default to "no": nothing is swept on the strength of a row
-- written before this fact existed.
ALTER TABLE import_items ADD COLUMN created_document INTEGER NOT NULL DEFAULT 0
    CHECK (created_document IN (0, 1));
