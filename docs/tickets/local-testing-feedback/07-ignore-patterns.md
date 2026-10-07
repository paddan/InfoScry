# 07: Per-collection ignore patterns for files that are never imported

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 05.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Deliverable

Each collection has a list of ignore patterns, like `.gitignore`, that every import into it applies before
anything else, so system and temporary files are never attempted (owner decision: per collection, not
archive-wide). It is edited with the collection's settings in Admin and in the CLI, and a new collection starts
with sensible defaults.

## Rules

- `.gitignore` glob syntax against the file name and the path relative to the imported folder: `*`, `?`, `**`, a
  trailing `/` for directories (their contents are not walked), `!` to re-include, `#` comments. Invalid patterns
  are refused when saved, not when importing.
- Defaults for a new collection: `.DS_Store`, `._*`, `Thumbs.db`, `desktop.ini`, `~$*`, `*.tmp`, `.git/`, `node_modules/`.
- Applied before ticket 06's include/exclude filter; an ignored file is skipped exactly like ticket 05.
- An explicitly chosen single file that matches a pattern is skipped too, consistently with folder imports.
- A queued or resumed import uses the list that was saved when it was admitted (snapshot in the payload), so a later
  edit cannot change an import already in progress.

## Files and interfaces

- Storage in `src/main/resources/db/migration/001_baseline.sql` (patterns owned by the collection and removed with
  it); a small service for read/update and matching.
- Routes: `GET`/`PUT /api/collections/{id}/ignore-patterns` (credentials and CSRF as other mutations, collection
  scoping as other collection routes); CLI: `infoscry collection ignore <collection> list|set`.
- Web: an "Ignored files" field in the collection's SETTINGS in `web/src/lib/CollectionsPanel.svelte`, saved with
  the same stale-response and conflict handling as the other collection settings.

## Test-first implementation

- [ ] Failing tests first: defaults present in a new collection; one collection's list does not affect another;
  the list is deleted with its collection; each syntax element (`*`, `?`, `**`, directory `/`,
  `!`, comments); an ignored directory is not walked; invalid patterns refused on save; the payload snapshot
  protects a running import; the order collection ignore list → include/exclude → unsupported holds.
- [ ] Browser scenario: edit the list in the collection's settings, import a folder containing `.DS_Store` and a matching temp file,
  see only the real documents.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.server.*Import*'
(cd web && npm test -- --run && npm run check)
```
