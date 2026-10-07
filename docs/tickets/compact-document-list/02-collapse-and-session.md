# 02: Collapsed overview, bounded table and session state

**Status:** Not started.
**Blocked by:** 01.
**Spec:** [Compact document list](../../specs/2026-09-30-compact-document-list.md).
Read the [shared constraints and gates](STATUS.md) before starting.

## Deliverable and files

Implement the separate disclosure/count/status buttons and bounded table region. Keep settings/import/history outside it. Put the per-collection view map in the parent page, restoring only expansion/query/filter/sort and refetching page one. Integrate summary refresh with the existing visible-panel lifecycle.

- `web/src/lib/CollectionsPanel.svelte`
- `web/src/lib/documentListViewState.ts (new)`
- `web/src/routes/+page.svelte`
- `web/src/lib/CollectionsPanel.test.ts`
- `web/src/routes/page.test.ts`

**Tests:** Extend CollectionsPanel.test.ts and web/src/routes/page.test.ts; add web/src/lib/documentListViewState.test.ts if extracting pure map helpers.

## Test-first steps

- [ ] Add failing tests for these exact observable cases:
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
