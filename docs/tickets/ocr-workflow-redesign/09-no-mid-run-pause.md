# 09: No mid-run pause; stop at the confirmed pages

**Status:** Not started
**Blocked by:** 01, because deleting `AWAITING_APPROVAL_STAGE` and `OcrOperationStage.AWAITING_APPROVAL` leaves old stored rows that only the startup cleanup converts; the cleanup must exist first (and uses a literal string, so it keeps compiling).
**Plan:** [OCR workflow redesign, Task 9](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 6, 10, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Run and document status").

## Problem

A run that exceeds its external allowance pauses for an approval the person did not
expect, shown as "Complete".

## Deliverable

Exceeding the snapshot's `externalPageLimit` ends the attempt `FAILED` with code
`MORE_PAGES_THAN_CONFIRMED` and sends no extra page. `AttemptAwaitingApproval`,
`AWAITING_APPROVAL_STAGE`, `OcrOperationStage.AWAITING_APPROVAL`, the `ocr_external_approvals`
use, both `approve-external` routes and the `externalApproval` job block are removed.

## Can be built against

Tests set `externalPageLimit` directly on the snapshot with the fake provider; no
preview or start ticket is needed (06 and 08 set the limit in production).

## Files and interfaces

- Modify `src/main/kotlin/infoscry/jobs/AttemptDispatch.kt` (`onExhausted` :84), `src/main/kotlin/infoscry/ocr/OcrDispatchAuthority.kt`, `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt` (:173), `src/main/kotlin/infoscry/jobs/RetryJobHandler.kt` (:113), `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt` (`pauseForApproval` :513, `isWaitingForApproval` :502), `src/main/kotlin/infoscry/storage/JobStore.kt`, `src/main/kotlin/infoscry/server/OcrRoutes.kt` (approve routes, `externalApprovalOf`).
- Stored rows with the old stage are handled by ticket 01; make sure reading an old row (job stage string, operation stage string) does not throw.
- Tests `ImportJobHandlerTest`, `RetryJobHandlerTest`, `RescanJobHandlerTest`, `JobRoutesTest`, `OcrRoutesTest`. Other tickets also edit `RetryJobHandler` (11) and `RescanJobHandler` (10); keep edits to the pause paths.

## Test-first implementation

- [ ] Write failing tests: a run that would need one page more than confirmed ends FAILED with `MORE_PAGES_THAN_CONFIRMED` and sends no extra page (assert the fake provider call count equals the limit); no route `approve-external` exists (404) for jobs or operations; stage never equals `awaiting-approval`.
- [ ] Run; expect FAIL.
- [ ] Idempotence/restart: after the run fails with the code, restarting the process leaves it FAILED (not re-run, not paused); the committed pages stay.
- [ ] Implement and delete the dead paths.
- [ ] Run the five classes plus `infoscry.cli.*`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.jobs.RescanJobHandlerTest' --tests 'infoscry.server.JobRoutesTest' --tests 'infoscry.server.OcrRoutesTest' --tests 'infoscry.cli.*'
./gradlew check
```

## Out of scope

Review/candidate removal (ticket 10, 17); web removals (tickets 12, 14); CLI remedy
printing (ticket 16, which will fail to compile against removed approval types only if it
still references them: remove only the references that stop compiling).
