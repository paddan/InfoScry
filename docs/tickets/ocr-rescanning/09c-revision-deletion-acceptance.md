# 09c: Deletion removes revision-owned work safely

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 03c, 07c, 08e, 09a. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Prove document/collection deletion cleans history, proposals and managed artifacts without resurrection or external-source changes.

Excluded from this slice: General retention policy or garbage collection. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/main/kotlin/infoscry/document/DocumentService.kt`
- `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt`
- `src/main/kotlin/infoscry/storage/OcrOperationStore.kt`
- `src/test/kotlin/infoscry/document/DocumentServiceTest.kt`
- `src/test/kotlin/infoscry/storage/SchemaMigratorTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Delete a document with pending import, rescan proposal and restored history; remove owned rows/artifacts and retain unrelated collection copy and external original.
- [ ] Interrupt each persisted deletion phase and resume cleanup without orphan candidate binding or evidence misattribution.
- [ ] Race decision/restore against deletion: refuse or finish ordered ownership, never resurrect the document.
- [ ] Assert current foreign-key cascades before changing schema; change only demonstrated omissions.

## Execution and verification

- [ ] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [ ] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [ ] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.document.*' --tests 'infoscry.storage.SchemaMigratorTest'
```

- [ ] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [ ] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
