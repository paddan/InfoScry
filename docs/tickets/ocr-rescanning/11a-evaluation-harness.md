# 11a: Local pilot manifest and scoring runner

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 10c. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Build deterministic local scoring from redistributable ground truth and a document-level tuning/held-out manifest.

Excluded from this slice: Production activation or selecting private pilot documents for the user. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `scripts/ocr/evaluate.py (new)`
- `scripts/ocr/test_evaluate.py (new)`
- `docs/evaluations/ocr-pilot.md (new)`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Fixture tests pin CER/WER denominators, Unicode normalization, omissions and invented/critical name-number errors separately.
- [ ] Zero examples cannot report perfect accuracy; report review load, false approvals, missed improvements, latency and usage separately.
- [ ] Manifest records hashes, versions and document-family split; related pages cannot leak across tuning/held-out sets.
- [ ] Runner defaults local and never uploads a private corpus; human reference preparation and corpus selection remain explicit.

## Execution and verification

- [ ] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [ ] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [ ] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
python3 -m unittest discover -s scripts/ocr -p 'test_evaluate.py'
```

- [ ] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [ ] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
