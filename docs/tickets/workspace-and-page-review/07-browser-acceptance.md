# 07 — Integrated browser acceptance and documentation

**Status:** Implemented and independently task-reviewed 2026-10-10.
**Blocked by:** 06; compact-list 04.
**Spec:** [Wide workspace and page review](../../specs/2026-10-09-workspace-and-page-review.md).
Read the [plan and shared gates](../../plans/2026-10-09-workspace-and-page-review.md).

## Deliverable and files

Modify `web/e2e/document-browser-acceptance.mjs`,
`web/e2e/collections-browser-acceptance.mjs`,
`web/e2e/investigate-browser-acceptance.mjs`,
`web/e2e/search-browser-acceptance.mjs`, `web/e2e/ocr-browser-acceptance.mjs`,
`src/test/kotlin/infoscry/server/DocumentBrowserAcceptanceTest.kt`,
`src/test/kotlin/infoscry/server/CollectionsBrowserAcceptanceTest.kt`,
`src/test/kotlin/infoscry/server/InvestigateBrowserAcceptanceTest.kt`,
the shared Investigate evidence assembly and its focused backend tests (to retain
authoritative document identity for page/image citations),
`web/src/lib/DocumentRescan.svelte`, its component tests and the API client (to
resume stopped explicit scans with their frozen selection/checkpoints),
the existing resume service and focused tests (repeat admission must return the
current operation after an uncertain response, without duplicate work),
`docs/usage.md`, `docs/implementation-status.md`, `README.md` and both ticket
status files. Extend existing fake fixtures; do not introduce a parallel harness.

## Interfaces

Consumes all preceding deliverables and compact-list tickets 02–04. Produces
recorded whole-flow evidence with real built UI/server, generated PDFs and fake
providers, plus explicit separate manual gates.

## Behavioral acceptance vectors

1. At 390/1024/1440/1920px measure geometry: no page overflow, wide table grows,
   readable answers, usable split panes/history, visible focus and narrow Back.
2. Collections count/filter/search → inline details → full page review → Back
   retains the collection view without restoring destructive selection.
3. Clean-text page explicitly rescanned with fake Surya/LLM: exactly selected
   images dispatched, selected progress count, unselected text/IDs/provenance
   preserved, new text beside correct image and previous revision in History.
4. Stop/restart a partial explicit run; recover exact set/checkpoints. Restore
   old text; historical Ask/Investigate citations stay paired to old revision.
5. Slow page responses, missing image, unsupported format and failed provider
   show independent curated errors without stale page pairings or secret text.
6. Preserve existing Collections/Search/Ask/Investigate/OCR browser scenarios,
   catalog/name suggestions, unknown-revision excerpts and automatic rescans.

## Implementation steps

- [x] Add failing behavioral tests for each numbered vector above. Record actual
  failure output before implementation; compilation failure alone is not evidence.
- [x] Run the focused side's command below and confirm the intended failures.
- [x] Implement the deliverable: Add failing browser scenarios before integration changes, then rerun every
existing affected class. Use DOM geometry, not screenshots alone, for width;
inspect screenshots of wide/narrow review as visual QA. Record human keyboard/
screen-reader testing or leave it open. Real Surya/LLM, PDF corpus and full
CoreML workflow remain separate and must not be marked complete from fakes.
- [x] Run focused tests green, then the accumulated gate; record exact commands,
  counts and any unperformed hardware/manual checks.
- [x] Review contracts, repeat/recovery behavior and spec coverage; update ticket
  status only from evidence. Preserve unrelated edits. Do not commit/push without
  the owner's Git instruction.

## Commands

```sh
./gradlew externalTest --tests 'infoscry.server.*BrowserAcceptanceTest'
./gradlew check
(cd web && npm run check && npm run build)
```

The independent Luna task review and scoped crash-marker harness rereview found
no product defects. Its minor report wording correction is integrated. The browser
gate passed 42 tests, followed by 7 affected Document scenarios after the final
guard/oracle changes. Focused backend tests passed 57 and frontend tests passed
424 with type/build checks green. The replacement final `./gradlew check` passed
in 4m25s: 131 JVM suites / 1,596 tests and 424 frontend tests, with zero failures,
errors or skips. The initial crash-marker handshake race was fixed with atomic
publication while retaining all five actual process-kill boundaries. Whole-plan review then found a nested Escape issue and stale spec status; both
were fixed and the scoped rereview passed. The final `./gradlew check` passed
in 4m23s with 1,596 JVM / 425 frontend tests, zero failures/errors/skips. Real
provider/CoreML and human accessibility gates remain open.
