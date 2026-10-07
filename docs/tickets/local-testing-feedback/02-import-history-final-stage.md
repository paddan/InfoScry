# 02: Import history shows the final stage of a finished import

**Status:** Implemented on this branch; focused backend and web tests pass (the one POSIX-permission failure in
`ImportJobHandlerTest` is the known root-container case). The `externalTest` browser scenario for import history is
not extended here. Unchecked criteria are requirements, not evidence.

## Decision

A finished job carries no stage. `JobStore` clears the stage an attempt last entered when the job completes, fails or
is cancelled. The one stage kept on a `COMPLETE` job is `awaiting-approval`, the wait for a person's approval that
ended the attempt. The same rule is applied on read (`JobStore.visibleStage`), so rows written before this change do
not show a stale stage in the history, the job API or the CLI. The web history and progress text use the same rule
(`stageForReader`), so a finished import shows no stage except that wait.

Root cause: `ImportJobHandler` and its helpers report each durable step as a stage (`queue`, `copy`, `record`, ...)
through `JobStage.run`, which writes the stage before the step. The last step of a file is often a `queue` step, and
`JobStore.complete` moved only the state, so the stage the attempt last entered (`queue`, labelled "Queued") stayed on
the finished row.
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

- [x] Reproduce first: find why a finished import keeps `QUEUED` (never advanced, or reset at completion), with a
  failing test at the store/handler level and one at the history route.
- [x] Decide and record which value a finished job carries (final stage, or none) and apply it to every terminal
  state (complete, complete with failures, cancelled, failed).
- [x] The history table renders a terminal job without a running stage label.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.server.ImportHistoryRoutesTest' --tests 'infoscry.jobs.*'
(cd web && npm test -- --run)
```
