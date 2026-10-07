# 02e: Publication seal failure and recovery

**Status:** Implemented 2026-10-07; acceptance evidence is recorded in [STATUS.md](STATUS.md). Unchecked criteria would be requirements, not evidence.
**Blocked by:** 02. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Prove that a failed snapshot switch cannot serve mixed search and source revisions.

Excluded from this slice: Publication redesign or general concurrency refactoring. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/test/kotlin/infoscry/document/RevisionPublicationRecoveryTest.kt`
- `src/test/kotlin/infoscry/server/SearchRoutesTest.kt`
- `src/main/kotlin/infoscry/document/RevisionPublicationService.kt (only if regression fails)`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [x] Inject failure at AUTHORITATIVE and again at the second STAGED_COMMITTED using a deterministic counter hook.
- [x] Read while recovery cannot switch: search/source refusal is explicit rather than old index with new text.
- [x] Restart or complete recovery, then prove reads resume coherently on the authoritative revision.

## Execution and verification

- [x] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [x] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [x] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.document.RevisionPublicationRecoveryTest' --tests 'infoscry.server.SearchRoutesTest'
```

- [x] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [x] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

> Verification note (2026-10-07): the accumulated `./gradlew check` was run and its only two failures
> are pre-existing baseline failures of ticket 02d's migration-028 tests, proven on the pristine
> baseline; see the exact commands, counts and attribution in the
> [02e verification record](STATUS.md#ticket-02e-verification-record).

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
