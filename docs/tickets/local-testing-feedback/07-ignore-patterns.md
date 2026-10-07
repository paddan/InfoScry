# 07: Per-collection ignore patterns for files that are never imported

**Status:** Implemented on the worktree branch; the browser scenario is not done. Unchecked criteria are requirements, not evidence.
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

## Decision

- **Per collection, with defaults for a new one** (owner decision). Rows live in `collection_ignore_patterns`
  (`collection_id`, `position`, `pattern`) in `001_baseline.sql`, with `ON DELETE CASCADE` from `collections`. Both the
  ordinary deletion and its recovery remove the collection through `CollectionStore.delete`, so the cascade covers
  every recovery phase and no deletion step was added. `CollectionStore.create` seeds the eight defaults in the same
  transaction. An emptied list stays empty (the defaults are never re-applied). A collection that existed before this
  change has no rows, so it has an empty list.
- **Own matcher, no dependency.** `IgnorePatterns` (`src/main/kotlin/infoscry/jobs/IgnorePatterns.kt`) compiles each
  line to a `java.util.regex` pattern. No gitignore library was on the classpath, and none was added. Differences from
  git, deliberate and recorded: matching is **case-insensitive** (macOS and Windows volumes are, and `Thumbs.db`
  should find `thumbs.db`); `[abc]` classes are unsupported and their brackets are literal; trailing spaces are always
  trimmed. A line is refused when it names nothing (`!`, `/`, `!/`), ends in a lone backslash, contains a control
  character, has an empty or `.`/`..` segment, exceeds 500 characters, or the list exceeds 500 lines. Comments are
  stored and read back as written; blank lines are dropped on save.
- **Matched against the path below the imported folder.** A pattern with a `/` at the start or in the middle is
  anchored there; any other pattern matches a name at any depth. A file named directly is matched by its own name only,
  so `node_modules/` does not apply to `node_modules/real.txt` chosen explicitly, while `*.tmp` does apply to a chosen
  `x.tmp`. A path is ignored when any directory above it is, so a file cannot be re-included out of an ignored folder.
- **One decision owner.** `ImportSelection.admits` applies the ignore list first, then 06's extension filter, then
  05's content check. The folder walk moved into `ImportSelection.walk`, which consults the same `IgnorePatterns`
  and returns `SKIP_SUBTREE` for an ignored directory, so `.git/` and `node_modules/` are never entered. The handler
  has no second filter location.
- **Snapshot at admission.** `ImportJobPayload.ignore` (omitted when empty; a payload from before this reads as no
  patterns). `POST /api/imports` and the standalone CLI import read the list when the job is admitted; the handler
  never reads the collection's live list, so a queued, running or resumed import keeps the list it was admitted with.
- **Routes** `GET`/`PUT /api/collections/{id}/ignore-patterns`, body `{"patterns": [...]}`, beside the OCR settings
  route: PUT goes through the CSRF boundary and `MutationCoordinator.withMutation` + `requireMutationsAllowed`, answers
  400 `INVALID_REQUEST` for an invalid line (nothing changes), 404 for an unknown or deleting collection.
- **CLI** `infoscry collection ignore <collection> list [--json]` and `set [PATTERN...] [--file F] [--clear]`; naming
  nothing is refused so a forgotten argument never empties the list. Works in-process or through a running server.
- **Web.** An "Ignored files" textarea in the collection's SETTINGS. The saved list is read when a collection's
  settings open, and Save stays disabled until it has been read; every read and save answer is dropped when another
  collection has been selected since (the same `settingsGeneration` guard as the name and language forms).

## Files and interfaces

- Storage in `src/main/resources/db/migration/001_baseline.sql` (patterns owned by the collection and removed with
  it); a small service for read/update and matching.
- Routes: `GET`/`PUT /api/collections/{id}/ignore-patterns` (credentials and CSRF as other mutations, collection
  scoping as other collection routes); CLI: `infoscry collection ignore <collection> list|set`.
- Web: an "Ignored files" field in the collection's SETTINGS in `web/src/lib/CollectionsPanel.svelte`, saved with
  the same stale-response and conflict handling as the other collection settings.

## Test-first implementation

- [x] Failing tests first: defaults present in a new collection; one collection's list does not affect another;
  the list is deleted with its collection; each syntax element (`*`, `?`, `**`, directory `/`,
  `!`, comments); an ignored directory is not walked; invalid patterns refused on save; the payload snapshot
  protects a running import; the order collection ignore list → include/exclude → unsupported holds.
- [ ] Browser scenario **Not done**: the browser (Playwright) item was excluded from this run: edit the list in the collection's settings, import a folder containing `.DS_Store` and a matching temp file,
  see only the real documents.

Tests: `IgnorePatternsTest` (each syntax element, defaults, validation), `ImportSelectionTest` (order),
`CollectionStoreTest` (defaults, independence, emptied list, cascade), `CollectionDeletionRecoveryTest` (no pattern rows
after any recovery point), `ImportJobHandlerTest` (defaults on a folder, ignored directory not walked, relative paths,
explicit file, snapshot on resume, legacy payload), `IgnorePatternsRoutesTest` (defaults, per-collection save,
invalid 400, credentials, 404, snapshot at admission, the CLI's loopback client), `CollectionCommandTest` (CLI),
and `CollectionsPanel.test.ts` (read, save, refusal, disabled until read, stale answers).

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.server.*Import*'
(cd web && npm test -- --run && npm run check)
```
