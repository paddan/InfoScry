# 03: Debounced filename search and inline document details

**Status:** Implemented and independently reviewed 2026-10-09.
**Blocked by:** 02.
**Spec:** [Compact document list](../../specs/2026-09-30-compact-document-list.md).
Read the [shared constraints and gates](STATUS.md) before starting.

## Deliverable and files

Replace Search submission with 300 ms trailing debounce and Enter flush; retain literal filename matching. Move existing detail content to one full-width row after its document.
Prioritize filename/status/live progress in rows; move media type, size and import
date into details. Keep Open document and Details discoverable without duplicate
actions. Keep page comparison in the workspace reader, not inside the table;
retain its current reader entry point until companion ticket 04 lands. Preserve all existing actions and safe loading/error behavior.

- `web/src/lib/CollectionsPanel.svelte`
- `web/src/lib/CollectionsPanel.test.ts`
- `web/src/lib/documentListViewState.ts`

**Tests:** Extend web/src/lib/CollectionsPanel.test.ts with fake timers, deferred promises and actual rendered table-row assertions.

## Test-first steps

- [ ] Add failing tests for these exact observable cases:
  - Rapid typing issues one final request after 300 ms; Enter flushes once; IME composition does not emit intermediate requests.
  - An old request resolving during the debounce interval cannot replace rows; filter/sort change flushes latest text and cancels the scheduled duplicate.
  - Collapse/unmount/collection switch before timer fires cancels requests and preserves pending input for its original collection only.
  - Row metadata remains available in details; Open document/Retry/Delete remain
    keyboard reachable and use existing scoped operations.
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

## Verification record — 2026-10-09

Implemented 300 ms trailing filename search, Enter flush, IME deferral and immediate
old-request invalidation. Status/sort consume current trimmed input and cancel
pending search; hide/collapse/switch/unmount cancel timers and preserve the
originating collection's session input. One full-width inline detail row follows
its document inside the bounded table; metadata/actions remain available. Local
scroll and focus behavior handle close/Escape, nested dialogs and row removal.
Late Retry responses cannot reopen/replace another view, and request-owned busy
state prevents a prior request from blocking or releasing a later document's Retry.

Behavioral RED preceded search/detail/focus implementation and both scoped Retry
fixes. Final focused panel gate: 123 tests. Full frontend: 19 files / 382 tests;
Svelte zero errors/warnings and production build passed. Final `./gradlew check`
succeeded in 4m46s: 130 JVM suites / 1,585 tests, zero failures/errors/skips, plus
382 frontend tests. Independent review and two scoped fix reviews closed all
C03 findings. Browser geometry remains for 04. An existing rescan-component
continuation-after-unmount boundary is recorded as required W06 work before
integrated acceptance; C03 does not claim that component's lifetime is verified.
