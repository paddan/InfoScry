# 02: Import history shows the final stage of a finished import

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** None.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Problem

Admin → Collections → Import history shows a finished import as State `Complete` with Stage `Queued`.

## Deliverable

A finished import shows no stale stage: either its final stage or no stage column value at all, consistently in the
history table, the job API and the CLI.

## Files and interfaces

- `src/main/kotlin/infoscry/storage/JobStore.kt` (stage bookkeeping), `src/main/kotlin/infoscry/jobs/JobRunner.kt`,
  `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt`, `src/main/kotlin/infoscry/server/ImportHistoryRoutes.kt`.
- `web/src/lib/CollectionsPanel.svelte`, `web/src/lib/importProgress.ts` (`stageLabel`).

## Test-first implementation

- [ ] Reproduce first: find why a finished import keeps `QUEUED` (never advanced, or reset at completion), with a
  failing test at the store/handler level and one at the history route.
- [ ] Decide and record which value a finished job carries (final stage, or none) and apply it to every terminal
  state (complete, complete with failures, cancelled, failed).
- [ ] The history table renders a terminal job without a running stage label.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.server.ImportHistoryRoutesTest' --tests 'infoscry.jobs.*'
(cd web && npm test -- --run)
```
