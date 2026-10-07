# 11b: Measured pilot and explicit activation gate

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 11a. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Run the owner-selected pilot, obtain accepted numeric thresholds, evaluate held-out cases and enable only validated scope.

Excluded from this slice: Silently inventing activation thresholds or claims of general superiority. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `docs/evaluations/ocr-pilot.md`
- `src/main/kotlin/infoscry/ocr/OcrDecisionPolicy.kt`
- `src/main/kotlin/infoscry/storage/OcrReviewStore.kt`
- `src/test/kotlin/infoscry/ocr/OcrActivationTest.kt (new)`
- `web/src/lib/CollectionsPanel.svelte`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Select corpus and human references with the owner; no private external experiment without its specific authorization.
- [ ] Report denominators/uncertainty and propose thresholds; wait for explicit acceptance before declaring held-out validation sufficient.
- [ ] No/high-confidence/mismatched validation cannot enable automation; bind transcription and reviewer model/profile/prompt/policy scope completely.
- [ ] Changed scope or missing validation reverts to manual operation; pilot failure leaves the manual workflow usable.

## Execution and verification

- [ ] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [ ] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [ ] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.ocr.OcrActivationTest'
```

- [ ] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [ ] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
