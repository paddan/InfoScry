# 03: Debounced filename search and inline document details

**Status:** Not started.
**Blocked by:** 02.
**Spec:** [Compact document list](../../specs/2026-09-30-compact-document-list.md).
Read the [shared constraints and gates](STATUS.md) before starting.

## Deliverable and files

Replace Search submission with 300 ms trailing debounce and Enter flush; retain literal filename matching. Move existing detail content to one full-width row after its document. Preserve all existing actions and safe loading/error behavior.

- `web/src/lib/CollectionsPanel.svelte`
- `web/src/lib/CollectionsPanel.test.ts`
- `web/src/lib/documentListViewState.ts`

**Tests:** Extend web/src/lib/CollectionsPanel.test.ts with fake timers, deferred promises and actual rendered table-row assertions.

## Test-first steps

- [ ] Add failing tests for these exact observable cases:
  - Rapid typing issues one final request after 300 ms; Enter flushes once; IME composition does not emit intermediate requests.
  - An old request resolving during the debounce interval cannot replace rows; filter/sort change flushes latest text and cancels the scheduled duplicate.
  - Collapse/unmount/collection switch before timer fires cancels requests and preserves pending input for its original collection only.
  - Only one detail row is open, directly below the selected document; changing filters/page clears it; Open document uses the existing source reader.
  - Keyboard collapse/close preserves sensible focus; document removal while details are open closes the obsolete detail; no raw HTML rendering of filenames/errors.
- [ ] Run the focused command and record a meaningful behavioral failure before implementation.
- [ ] Implement the deliverable against the spec's shared endpoint/state contracts, preserving unrelated work and pinned dependencies.
- [ ] Run focused verification green, then `./gradlew check`; frontend work also runs web `npm run check` and `npm run build`.
- [ ] Review spec coverage, update this ticket and STATUS.md with actual evidence and remaining gates. Do not commit or push without the user's Git authorization.

## Focused command

```sh
(cd web && npm test -- --run && npm run check && npm run build)
```
