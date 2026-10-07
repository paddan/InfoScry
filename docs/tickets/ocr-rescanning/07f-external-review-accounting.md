# 07f: Exercise external review admission locally

**Status:** Implemented — 2026-10-06. Red evidence observed by temporary, restored reverts of each pinned behavior (classification, ownership guards, stale-scope filter, call recording, allowance bound, payload-echo), then focused suite green (ImportJobHandlerTest 47, RetryJobHandlerTest 18, ImageLlmClientTest 36 — 101 tests, 0 failures) and the accumulated gate green (`./gradlew check`: backend 107 suites / 1391 tests + web 213 tests, 0 failures, 17m12s; `git diff --check` clean). No real external provider traffic. Record in [STATUS.md](STATUS.md).
**Blocked by:** 07c, 07d. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Prove production non-loopback classification and job-owned permits without sending fixtures to a real external provider.

Excluded from this slice: Paid providers, private documents or a production permit bypass. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/test/kotlin/infoscry/jobs/ImportJobHandlerTest.kt`
- `src/test/kotlin/infoscry/jobs/RetryJobHandlerTest.kt`
- `src/test/kotlin/infoscry/ocr/ImageLlmClientTest.kt`
- `src/main/kotlin/infoscry/ocr/OcrDispatchAuthority.kt (only fixes exposed by tests)`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [x] Use an injected recording transport with a syntactically external HTTPS endpoint; keep production endpoint classification and permit checks enabled.
- [x] Allowance one across two documents: transcription plus review of first page uses one page/two calls; next document pauses before transport.
- [x] Wrong owner/profile/document or stale scope cannot dispatch; approved resume retains prior counters.
- [x] Bounded retries add calls without adding distinct pages; refused dispatch adds no network calls; logs/errors contain no payload or keys.

## Execution and verification

- [x] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [x] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [x] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.ocr.ImageLlmClientTest'
```

- [x] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [x] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
