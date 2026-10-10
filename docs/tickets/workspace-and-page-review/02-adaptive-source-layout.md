# 02 — Adaptive source and history layout

**Status:** Implemented and independently reviewed 2026-10-10.
**Blocked by:** 01.
**Spec:** [Wide workspace and page review](../../specs/2026-10-09-workspace-and-page-review.md).
Read the [plan and shared gates](../../plans/2026-10-09-workspace-and-page-review.md).

## Deliverable and files

Modify `web/src/routes/+page.svelte`, `web/src/lib/HistoryColumn.svelte` and
`web/src/routes/page.test.ts`. Create `web/src/lib/SourceWorkspace.svelte` and
`web/src/lib/SourceWorkspace.test.ts` for pane layout and focus only.
The existing `CollectionsPanel.svelte` Open document callback and its tests may
forward the actual action button as the Admin focus opener; keep source behavior
unchanged and provide an active-view fallback if that opener disappears.

## Interfaces

Produces `SourceWorkspace` with `mode: 'split' | 'review'`, source content
snippet, `onClose: () => void` and `onBack: () => void`. The page owns open source,
query/conversation and history visibility. Ticket 04 supplies review content.

## Behavioral acceptance vectors

1. Search/Ask/Investigate preserve their context beside an open source when
   width permits; Ask/Investigate history is closed by default in the left
   sidebar and reserves no separate workspace column (amended 2026-10-10).
2. Narrow mode has a labelled Back action and preserves current source/page.
3. Open/close and mode switching preserve query/conversation and return focus
   to a valid opener; deletion of an opener falls back to the active view heading.
4. Existing citation revision and saved-excerpt behavior remains unchanged.

## Implementation steps

- [ ] Add failing behavioral tests for each numbered vector above. Record actual
  failure output before implementation; compilation failure alone is not evidence.
- [ ] Run the focused side's command below and confirm the intended failures.
- [ ] Implement the deliverable: Replace fixed source-sheet/backdrop layout with the adaptive workspace. Check
actual container width; do not retain modal aria semantics or focus trapping for
nonmodal split panes. Keep narrow single-pane accessibility explicit.
- [ ] Run focused tests green, then the accumulated gate; record exact commands,
  counts and any unperformed hardware/manual checks.
- [ ] Review contracts, repeat/recovery behavior and spec coverage; update ticket
  status only from evidence. Preserve unrelated edits. Do not commit/push without
  the owner's Git instruction.

## Commands

```sh
(cd web && npm test -- --run && npm run check && npm run build)
./gradlew check
```

## Verification record — 2026-10-10

Actual container width drives nonmodal split/single-pane layout. Ask/Investigate
history was subsequently moved to a closed-by-default sidebar disclosure, with
New conversation always visible; source width no longer controls history.
Narrow Back retains source/page and context; close returns focus to the actual opener, or to the visible active
view heading when that opener disappears, including Admin.

Behavioral RED cases covered the previous modal and missing Admin focus targets.
The initial two failures were observed but their raw log was not retained;
subsequent focus-fix RED/GREEN logs were retained. Frontend tests passed 20 files /
389 tests, with zero type errors/warnings and a successful production build.
Independent Luna review and two scoped fix reviews closed the focus findings.
Final `./gradlew check` passed in 4m55s: 130 JVM suites / 1,585 tests and 389
frontend tests, zero failures, errors or skips. Rendered geometry and human
accessibility remain assigned to ticket 07 and its separate manual gate.
