# 06: Include or exclude file extensions when importing a folder

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 05.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Deliverable

When importing a folder, a person can either list extensions to include (only those are imported) or list
extensions to exclude (everything else is imported), in Admin → Collections → Add documents and in the CLI.

## Rules

- Include and exclude are mutually exclusive in one request; both together is a 400 before any side effect.
- Extensions are matched case-insensitively, with or without the leading dot; an empty list means no filter.
- A file removed by the filter is skipped exactly like ticket 05 (not shown, not counted). The collection's ignore list
  (07) applies before this filter.

## Files and interfaces

- The import request and payload (`ImportJobPayload`, the import route, `CollectionService`), the file-selection
  step from 05.
- CLI: `infoscry import --include=pdf,docx` / `--exclude=tmp,log` (`src/main/kotlin/infoscry/cli/`).
- Web: the Add documents flow in `web/src/lib/CollectionsPanel.svelte` (and `ImportPanel.svelte` if it owns it).

## Test-first implementation

- [ ] Failing tests first: include imports only the listed types; exclude imports everything else; both is refused
  with nothing enqueued; case and leading dots are normalised; filters apply recursively; the queued payload keeps
  the filter so a resumed import applies the same one.
- [ ] Browser scenario: a folder imported with include and with exclude shows only the expected documents.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.cli.*Import*'
(cd web && npm test -- --run && npm run check)
```
