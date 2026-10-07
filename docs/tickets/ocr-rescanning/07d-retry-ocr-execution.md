# 07d: Retry uses admitted OCR and review authority

**Status:** Implemented — 2026-10-05. Red first (RetryJobHandlerTest: review wiring `NoSuchElementException: List is empty` on `ocrReviews.pending(...).single()`, dispatch wiring `expected: <1> but was: <0>` for `extractor.sent`), then focused suite green (RetryJobHandlerTest 16, ImportJobHandlerTest 45, DocumentRetryRoutesTest 17 — 0 failures) and the accumulated gate green (`./gradlew check`: backend 107 suites / 1384 tests + web 213 tests, 0 failures, 17m05s; `git diff --check` clean). Record in [STATUS.md](STATUS.md).
**Blocked by:** 07c. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Retry of failed/cancelled/tool-blocked documents executes the same snapshotted engines, comparisons and external allowance rules as import.

Excluded from this slice: Rescan behavior or new retry eligibility. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/main/kotlin/infoscry/jobs/RetryJobHandler.kt`
- `src/main/kotlin/infoscry/jobs/RetryJobPayload.kt`
- `src/main/kotlin/infoscry/document/RetryService.kt`
- `src/main/kotlin/infoscry/jobs/DocumentIngest.kt`
- `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt (shared attempt wiring only)`
- `src/test/kotlin/infoscry/jobs/RetryJobHandlerTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [x] A differing check-and-improve retry calls the reviewer and leaves pilot proposals pending; identical nonblank text needs no human decision. (RetryJobHandlerTest: `a differing check-and-improve retry calls the reviewer and leaves a proposal pending`, `a check-and-improve retry whose reading matches the page's own text records no review`.)
- [x] External transcription and review both use persisted job-owned scope; missing approval waits before dispatch rather than bypassing or failing without a remedy. (RetryJobHandlerTest: `external transcription and review of one retry page count one distinct page and two calls`, `a retry with no external allowance waits for approval before any dispatch`.)
- [x] Two stages for one page count one distinct page and two calls; resume retains counters and original settings. (Same accounting test, plus `a resumed retry continues its job's counters and keeps the snapshot it was admitted with`.)
- [x] Retry revisits failed units while preserving compatible successful checkpoints and does not broaden eligibility to COMPLETE or NEEDS_REVIEW. (`an explicit retry revisits the unit a crash resume would skip` kept green; `retry eligibility does not broaden to completed or review-pending documents`.)

## Execution and verification

- [x] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [x] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence. (Both failures were behavioral assertions against a compiling suite; see the status line.)
- [x] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.jobs.ImportJobHandlerTest'
```

- [x] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [x] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
