# 01 — Common wide workspace

**Status:** Implemented and reviewed 2026-10-09; rendered geometry verified in 07.
**Blocked by:** None.
**Spec:** [Wide workspace and page review](../../specs/2026-10-09-workspace-and-page-review.md).
Read the [plan and shared gates](../../plans/2026-10-09-workspace-and-page-review.md).

## Deliverable and files

Modify `web/src/routes/+page.svelte`, `web/src/lib/CollectionsPanel.svelte`,
`web/src/lib/LlmAdminPanel.svelte`; test `web/src/routes/page.test.ts`,
`web/src/lib/CollectionsPanel.test.ts`, `web/src/lib/LlmAdminPanel.test.ts`.
Create `web/src/lib/workspace.css` for shared gutters and readable prose rules;
import it from the page. Keep existing application navigation and data behavior.

## Interfaces

Produces the full-width workspace and readable prose/form measures. No API
change. Later tickets reuse these rules rather than adding new centering caps.

## Behavioral acceptance vectors

1. At 1920px, work areas grow beyond their former 78/52/44rem limits; answer
   paragraphs remain around 65–80ch, short form fields remain usable.
2. At 390px, controls wrap and no document-level horizontal scrollbar appears.
3. New-profile name suggestions, probe errors and Cancel → New still work.

## Implementation steps

- [ ] Add failing behavioral tests for each numbered vector above. Record actual
  failure output before implementation; compilation failure alone is not evidence.
- [ ] Run the focused side's command below and confirm the intended failures.
- [ ] Implement the deliverable: Add layout hooks to component tests and browser geometry assertions in ticket
07; DOM-only tests cannot establish rendered width. Adjust shared container CSS,
then LLM form grouping. Do not modify provider catalog/probe behavior.
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

## Verification record — 2026-10-09

Behavioral RED: three new workspace hook assertions and an accessible LLM group
assertion failed before implementation. Shared CSS now uses 16–24px gutters and
readable 75ch prose; former workspace/Collections/LLM panel width caps are removed.
Related LLM fields use accessible groups and responsive columns. Existing profile
name/probe behavior is preserved. A Vite client type reference supports the CSS
side-effect import; no dependencies changed.

Focused frontend: 189 tests passed. Full frontend: 18 files / 335 tests passed;
`npm run check` reported zero errors/warnings, and `npm run build` passed.
`./gradlew check` completed successfully in 5m25s: 1,557 JVM tests, zero failures,
errors or skips, plus 335 frontend tests. Independent task review found no issues.

These tests establish DOM structure and behavior. Actual 390/1024/1440/1920px
geometry, overflow and human accessibility remain open for ticket 07; no real
provider or GPU capability is claimed.
