# 01: Idempotent startup cleanup of stale OCR state

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** None.
**Plan:** [OCR workflow redesign, Task 1](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 6, 10, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Always restartable").

## Problem

A database written by the shipped flow can hold a job stored as `COMPLETE` with stage
`awaiting-approval`, an operation left in a working stage by a killed process, or a
`COMPLETE` operation with a pending candidate revision. These produce "waits for
approval" or "pending review" refusals when starting again.

## Deliverable

On every application start, before `jobs.resetInterrupted`, an idempotent routine
cancels those jobs, fails those operations with code `INTERRUPTED` (releasing their
document), and withdraws pending candidates. Running it a second time changes nothing.

## Can be built against

Nothing else; uses only the existing stores. No other ticket is needed. It uses the
literal `"awaiting-approval"` (CONTRACTS section 10) so ticket 09 can later delete the
`JobStore` constant freely.

## Files and interfaces

- Create `src/main/kotlin/infoscry/ocr/StaleOcrStateCleanup.kt`:
  `class StaleOcrStateCleanup(jobs: JobStore, operations: OcrOperationStore, revisions: DocumentRevisionStore) { fun run(): CleanupReport }`,
  `data class CleanupReport(val jobsCancelled: Int, val operationsFailed: Int, val candidatesWithdrawn: Int)`.
- Modify `src/main/kotlin/infoscry/AppContext.kt` (~line 583-610): call it before `jobs.resetInterrupted`.
- Modify `src/main/kotlin/infoscry/storage/JobStore.kt` and `src/main/kotlin/infoscry/storage/OcrOperationStore.kt` only to add the conditional-update helpers needed.
- Test `src/test/kotlin/infoscry/ocr/StaleOcrStateCleanupTest.kt`.

Behavior:
- Jobs with state `COMPLETE` and stage `awaiting-approval` -> `JobStore.cancel`.
- Operations whose stage `holdsDocument` and that have no live attempt (`OcrOperationStore.liveAttempt`) -> `finish(stage = FAILED, errorCode = "INTERRUPTED")`.
- `COMPLETE` operations with `pendingReviewCount > 0` -> `revisions.withdrawCandidate` and pending count 0. Reuse `RescanService.discardPending`'s logic by extracting it into a shared function (keep `discardPending` behavior unchanged).
- Every step is a conditional update (`WHERE` the old state still holds) so a repeat finds nothing.

## Original test-first implementation plan

- Write failing tests in `StaleOcrStateCleanupTest`, building states with the stores directly: `a paused import job becomes cancelled`, `an operation left in a working stage becomes failed with code INTERRUPTED and releases its document`, `a complete operation with a pending candidate has the candidate withdrawn and no longer holds the document`, `running it twice reports zero changes the second time`, `a live running job is untouched`.
- Run `./gradlew test -PskipFrontend --tests 'infoscry.ocr.StaleOcrStateCleanupTest'`; expect FAIL because the class is missing.
- Idempotence/restart tests: run the cleanup, then simulate a crash after each of the three steps (call only steps 1..k, then the full `run()`); the final state equals one uninterrupted run, and a database with all three stale states is cleaned by one `run()`, after which the document accepts a new rescan admission (use the existing admission test helper).
- The document's published text is unchanged by the cleanup.
- Implement `run()`, extract the shared discard function, wire into `AppContext`.
- Run the test class plus `JobRunnerRecoveryTest`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.ocr.StaleOcrStateCleanupTest' --tests 'infoscry.jobs.JobRunnerRecoveryTest' --tests 'infoscry.jobs.RescanJobHandlerTest'
./gradlew check
```

## Out of scope

Removing the old stages, the approve routes or review code (tickets 09, 10, 17);
replacing a stopped rescan on admission (ticket 02); any schema migration script.
