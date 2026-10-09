# 08 - Browser acceptance for the page browser and page selection

`AGENTS.md` keeps "browser acceptance for Ask and the source-viewer finish line"
open. Two flows added since have only unit tests.

## Scenarios (Playwright, `web/e2e`, fake provider, `externalTest`)
1. Page browser: open a document with three units from Admin, see "Page 1 of 3",
   step next and previous, disabled at the ends, focus stays in the dialog, a
   citation opened from an old revision shows no pager.
2. Page selection: import or rescan a generated PDF with two clean-text pages and
   one scanned page; the dialog says "1 of 3 pages will be read"; after the run
   only the scanned page reached the fake provider and the document has three
   pages.
3. (Open from before) Ask acceptance: ask, see citations, open one in the viewer.

## Acceptance
`./gradlew externalTest --tests 'infoscry.server.*BrowserAcceptanceTest'` passes
with the new scenarios; the README status line about open browser gates is
updated.
