# 10c: Reconcile docs and manual completion evidence

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 10a, 10b. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Declare manual OCR readiness only after API/UI, recovery and required real-runtime gates have evidence.

Excluded from this slice: New product behavior or activation. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `README.md`
- `docs/usage.md`
- `docs/cli.md`
- `docs/installation.md`
- `docs/technical-reference.md`
- `docs/implementation-status.md`
- `docs/tickets/ocr-rescanning/STATUS.md`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Document actual Admin controls, approval remedies, retry limits, pending-page semantics, revision history and tested install/asdf commands.
- [ ] Run accumulated check, externalTest and gpuIntegrationTest; retain command/date/outcome for every applicable gate.
- [ ] Manual keyboard/accessibility and real-image checks have explicit evidence or remain open; unrelated Ask/source-reader gates remain independently stated.
- [ ] No feature claim relies on checkbox counts; automatic replacement stays disabled and ticket 11 remains outside manual completion.

## Execution and verification

- [ ] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [ ] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [ ] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew check
./gradlew externalTest
./gradlew gpuIntegrationTest
```

- [ ] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [ ] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
