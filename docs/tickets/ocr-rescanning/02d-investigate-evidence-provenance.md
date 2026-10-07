# 02d: Investigate evidence survives replacement

**Status:** Implemented — verified 2026-10-07; unchecked criteria are requirements, not evidence.
**Blocked by:** 02, 02c. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Persist revision provenance alongside the already stored excerpt and return saved evidence without requiring its live unit.

Excluded from this slice: OCR review or history UI. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/main/kotlin/infoscry/storage/LlmStore.kt`
- `src/main/kotlin/infoscry/server/InvestigationRoutes.kt`
- `src/main/kotlin/infoscry/investigate/InvestigationService.kt`
- `src/main/resources/db/migration/ (new forward migration)`
- `web/src/routes/+page.svelte`
- `src/test/kotlin/infoscry/server/InvestigationRoutesTest.kt`
- `src/test/kotlin/infoscry/investigate/InvestigationServiceTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [x] The existing evidence_ledger.excerpt is preserved; add revision provenance rather than another excerpt column.
- [x] Replace or remove a live unit, reopen the conversation and retain its evidence ID, excerpt and source identity.
- [x] New evidence records the revision actually supplied to the model; unknown legacy provenance uses the excerpt fallback from 02c.
- [x] Migration preserves conversations and evidence counts; collection scoping and deletion remain enforced.

## Execution and verification

- [x] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [x] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [x] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.server.InvestigationRoutesTest' --tests 'infoscry.investigate.InvestigationServiceTest'
```

- [x] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [x] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
