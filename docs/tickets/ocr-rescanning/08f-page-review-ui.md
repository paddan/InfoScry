# 08f: Manual page review in Admin

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 08c, 08e, 02c, 02d. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Review image, baseline, candidate and reasons, then keep/use/edit and follow durable publication.

Excluded from this slice: History/restore or quality evaluation. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `web/src/lib/OcrReviewPanel.svelte (new)`
- `web/src/lib/OcrReviewPanel.test.ts (new)`
- `web/src/lib/CollectionsPanel.svelte`
- `web/src/lib/api.ts`
- `web/src/routes/+page.svelte`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Show Needs review with accurate pending count for import and rescan, including image-only pages excluded from search.
- [ ] Page navigation is bounded; image/text/reasons render safely; keyboard focus returns correctly after actions.
- [ ] Stale hash/revision displays conflict and preserves unsaved edit; duplicate submit persists once.
- [ ] Save decisions then publish, distinguish saved/publishing/published, reload the actual server state and update pending counts.
- [ ] Explicit bulk scope does not imply every hidden page was individually examined; collection change cannot display late prior-document material.

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
