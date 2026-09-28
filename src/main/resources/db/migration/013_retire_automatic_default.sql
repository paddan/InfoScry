-- Retire the automatic Default collection.
--
-- 001 inserted one `default` row so a user had somewhere to import without organising first. A new
-- archive now starts empty and every import names a collection a person chose, so that seed is removed
-- here — but only while it is still exactly the seed: its identifier is `default`, it is still named
-- `Default`, it is ACTIVE, and nothing was ever stored in it.
--
-- The identifier is what is keyed on, never the name: a collection a person created and named `Default`
-- has a generated id and is not this row, and a legacy `default` row that was renamed is one a person
-- meant to keep. Any owned state — a document, a job, a conversation or an unfinished deletion — means
-- the collection was used, and used material belongs to the person to rename or delete through Admin
-- rather than to a migration. Every predicate is a read of current state, so running this statement
-- again removes nothing more, and reopening an archive never revisits it.
DELETE FROM collections
WHERE id = 'default'
  AND name = 'Default'
  AND lifecycle = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM documents WHERE collection_id = 'default')
  AND NOT EXISTS (SELECT 1 FROM jobs WHERE collection_id = 'default')
  AND NOT EXISTS (SELECT 1 FROM conversations WHERE collection_id = 'default')
  AND NOT EXISTS (
      SELECT 1 FROM deletion_operations WHERE collection_id = 'default' AND phase <> 'DONE'
  );
