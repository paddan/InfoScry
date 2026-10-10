# 02: Collapsed overview, bounded table and session state

**Status:** Implemented and independently reviewed 2026-10-09.
**Blocked by:** 01 and [wide workspace 01](../workspace-and-page-review/01-wide-workspace.md).
**Spec:** [Compact document list](../../specs/2026-09-30-compact-document-list.md).
Read the [shared constraints and gates](STATUS.md) before starting.

## Deliverable and files

Implement the separate disclosure/count/status buttons and bounded full-width
table region. Put overview, active import progress and documents before secondary
settings/history disclosures; these remain outside the document collapse.
Keep Add documents visible and Delete collection a labelled settings action.
Include exact ongoing-stage count shortcuts as well as attention counts. Put the per-collection view map in the parent page, restoring only expansion/query/filter/sort and refetching page one. Integrate summary refresh with the existing visible-panel lifecycle.

- `web/src/lib/CollectionsPanel.svelte`
- `web/src/lib/documentListViewState.ts (new)`
- `web/src/routes/+page.svelte`
- `web/src/lib/CollectionsPanel.test.ts`
- `web/src/routes/page.test.ts`
- `web/src/lib/ImportPanel.svelte` and its tests: optional import-change callback
  after admission/terminal outcome, so an idle collapsed overview refreshes without
  perpetual polling.

**Tests:** Extend CollectionsPanel.test.ts and web/src/routes/page.test.ts; add web/src/lib/documentListViewState.test.ts if extracting pure map helpers.

## Test-first steps

- [ ] Add failing tests for these exact observable cases:
  - Active progress/errors stay visible when documents/settings/history collapse;
    opening settings/history does not change list state. Each ongoing-stage count
    applies its exact status filter; no fabricated review status exists.
  - First visit and browser reload are collapsed; Admin exit/re-entry and collection A/B switching restore separate state.
  - Total click clears query/status; failed click clears query and sets FAILED; disclosure toggles without changing criteria.
  - Collapse clears selection/details and cancels list/detail requests, but summary stays refreshable. Hidden panel stops polling and reopening fetches fresh data.
  - Summary loading/error never displays zero; a late A response cannot replace B. Rename retains session state and deletion removes it.
  - Filters/pagination keep existing page-scoped selection and mutation safeguards; no hidden selected IDs survive a collapse.
- [ ] Run the focused command and record a meaningful behavioral failure before implementation.
- [ ] Implement the deliverable against the spec's shared endpoint/state contracts, preserving unrelated work and pinned dependencies.
- [ ] Run focused verification green, then `./gradlew check`; frontend work also runs web `npm run check` and `npm run build`.
- [ ] Review spec coverage, update this ticket and STATUS.md with actual evidence and remaining gates. Do not commit or push without the user's Git authorization.

## Focused command

```sh
(cd web && npm test -- --run && npm run check && npm run build)
```

## Verification record — 2026-10-09

Collapsed aggregate overview, exact status shortcuts, bounded full-width table,
secondary settings/history disclosures and parent-owned session view map are
implemented. Selection/details are cleared on collapse and never restored.
Import progress/errors remain visible; optional import-change notification wakes
idle summary refresh. Parent and child polling pause when hidden and refresh on
return while retaining active job identity. Completed hidden deletions reconcile
before reloading unfinished operations.

Behavioral RED covered missing total shortcut, idle import not refreshing summary,
hidden completed deletion being lost and child import polling continuing while
hidden. All regressions passed after implementation. Final focused frontend gate:
185 tests; full frontend: 19 files / 358 tests. Svelte check reported zero errors
and warnings; production build passed. Final `./gradlew check` succeeded in
4m50s: 130 JVM suites / 1,585 tests, zero failures/errors/skips, plus 358 frontend
tests. Independent review and one scoped visibility fix review closed all findings.

Browser geometry, keyboard/screen-reader and human checks remain for 04. Live
search and inline details remain ticket 03 scope; no real-provider/GPU claim.
