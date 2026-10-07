# 08b: Collection OCR defaults

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 08a, 07e. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Expose engine, mode, transcription/reviewer profiles, language and external-page allowance in Collections.

Excluded from this slice: Document rescan/review actions. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `web/src/lib/CollectionsPanel.svelte`
- `web/src/lib/CollectionsPanel.test.ts`
- `web/src/lib/api.ts`
- `src/test/kotlin/infoscry/server/CollectionRoutesTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Save/reload each setting and preserve legacy defaults; switching collection during save cannot overwrite its selection.
- [ ] LLM requires eligible transcription profile; separate reviewer selection can be absent; same model reviewer shows lack of independence.
- [ ] Show named destinations, outbound image/text disclosure, allowance distinct-page semantics and separate call counts.
- [ ] Setting changes affect future attempts only; no automatic replacement control is offered in pilot mode.

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
