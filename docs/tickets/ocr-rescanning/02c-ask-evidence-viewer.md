# 02c: Saved Ask excerpt fallback

**Status:** Implemented 2026-10-06 — evidence in the [02c verification record](STATUS.md#ticket-02c-verification-record); unchecked criteria below are requirements, not evidence.
**Blocked by:** 02. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Open saved Ask evidence at its recorded revision; unknown revisions display the saved excerpt with an English revision-unknown label.

Excluded from this slice: Investigate schema or publication machinery. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `web/src/routes/+page.svelte`
- `web/src/lib/api.ts`
- `web/src/routes/page.test.ts`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [x] Unknown revision performs no live-unit request and displays the saved excerpt, including when the unit no longer exists.
- [x] Known revision is passed to readSource; subsequent source pagination keeps that revision.
- [x] HTML-like excerpts render literally; changing collection or source selection ignores late responses.

## Execution and verification

- [x] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [x] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [x] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
(cd web && npm test -- --run && npm run check && npm run build)
```

- [x] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [x] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
