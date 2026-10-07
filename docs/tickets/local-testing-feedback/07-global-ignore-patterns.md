# 07: A global ignore list for files that are never imported

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 05.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Deliverable

An archive-wide list of ignore patterns, like `.gitignore`, that every import applies before anything else, so
system and temporary files are never attempted. The list is global for the archive, not per collection (owner
decision), editable in Admin and in the CLI, and seeded with sensible defaults.

## Rules

- `.gitignore` glob syntax against the file name and the path relative to the imported folder: `*`, `?`, `**`, a
  trailing `/` for directories (their contents are not walked), `!` to re-include, `#` comments. Invalid patterns
  are refused when saved, not when importing.
- Defaults: `.DS_Store`, `._*`, `Thumbs.db`, `desktop.ini`, `~$*`, `*.tmp`, `.git/`, `node_modules/`.
- Applied before ticket 06's include/exclude filter; an ignored file is skipped exactly like ticket 05.
- An explicitly chosen single file that matches a pattern is skipped too, consistently with folder imports.
- A queued or resumed import uses the list that was saved when it was admitted (snapshot in the payload), so a later
  edit cannot change an import already in progress.

## Files and interfaces

- Storage in `src/main/resources/db/migration/001_baseline.sql` (a table or settings row with the patterns and an
  updated time); a small service for read/update and matching.
- Routes: `GET`/`PUT /api/import/ignore-patterns` (credentials and CSRF as other mutations); CLI:
  `infoscry import-ignore list|set`.
- Web: an "Import settings" section in Admin.

## Test-first implementation

- [ ] Failing tests first: defaults present in a fresh archive; each syntax element (`*`, `?`, `**`, directory `/`,
  `!`, comments); an ignored directory is not walked; invalid patterns refused on save; the payload snapshot
  protects a running import; the order global → include/exclude → unsupported holds.
- [ ] Browser scenario: edit the list in Admin, import a folder containing `.DS_Store` and a matching temp file,
  see only the real documents.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.server.*Import*'
(cd web && npm test -- --run && npm run check)
```
