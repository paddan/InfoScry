# 07c: Resume the same staged import candidate

**Status:** Implemented — accumulated gate green (`./gradlew check`: backend 1378 tests + web 213 tests, 0 failures); focused suite green (168 tests, 0 failures).
**Blocked by:** 07b implementation. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Bind each staged import document attempt to its candidate and durable page checkpoints so restart reuses completed OCR.

Excluded from this slice: Retry wiring, UI or external real-provider experiments. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt`
- `src/main/kotlin/infoscry/jobs/ImportPipeline.kt`
- `src/main/kotlin/infoscry/jobs/DocumentIngest.kt`
- `src/main/kotlin/infoscry/ocr/CandidateRevisionSink.kt`
- `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt`
- `src/main/resources/db/migration/ (only if existing durable owner cannot carry the binding)`
- `src/test/kotlin/infoscry/jobs/ImportJobHandlerTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [x] Kill after page 1 is committed, reopen the archive and assert the same candidate ID and stable page ID are reused; page 1 OCR call count remains one.
- [x] Pause for external approval after a committed page, resume and process only remaining pages without resetting page/call counters.
- [x] Inject interruption after page staging but before approval recording: resume completes that page decision without losing the checkpoint or silently treating it approved.
- [x] A changed snapshot creates a distinct explicit attempt; incompatible checkpoints are never reused.
- [x] Cancellation/failure leaves a recoverable or explicitly withdrawn candidate with no active-content mutation; no orphan duplicate candidate is silently created.

## Execution and verification

- [x] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [x] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [x] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.ocr.OcrEngineContractTest'
```

- [x] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [x] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
