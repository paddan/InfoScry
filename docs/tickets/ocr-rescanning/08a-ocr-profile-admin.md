# 08a: OCR profile administration

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 01, 05. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Manage separate OCR profiles and synthetic image probes in Admin.

Excluded from this slice: Collection settings, rescan or review panels. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `web/src/lib/OcrProfilesPanel.svelte (new)`
- `web/src/lib/OcrProfilesPanel.test.ts (new)`
- `web/src/lib/api.ts`
- `web/src/lib/api.test.ts`
- `web/src/routes/+page.svelte`
- `src/main/kotlin/infoscry/server/OcrProfileRoutes.kt (contract fixes only)`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Create/edit/disable and reload a profile using existing routes; edits produce immutable revisions.
- [ ] Show capability measurement and key-presence only; never accept or display a key value.
- [ ] Probe explicitly uses the synthetic fixture; stale responses cannot overwrite another selected profile.
- [ ] Keyboard operation, labelled errors and safe model/endpoint text; disabling preserves history references.

## Execution and verification

- [ ] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [ ] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [ ] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
(cd web && npm test -- --run && npm run check && npm run build)
```

- [ ] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [ ] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
