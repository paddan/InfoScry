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

## Implementation and verification (2026-10-09)

`DocumentBrowserAcceptanceTest` and its Playwright script cover all three
scenarios against the built reader and local server. The PDF fixture uses the
production page selector; only page 3 reaches the fake reading engine, progress
is one committed page of one selected page, and publication preserves all three
unit IDs. The Ask scenario supersedes the cited revision before opening its
persisted citation. Admin navigation checks both end boundaries and keyboard
focus wrapping.

The accumulated five-class browser gate passed 36 tests: Collections 14,
OCR 10, Investigate 8, Search 1 and Document 3. Existing fixtures now accept
imports without image reading and count the Admin model-list GET separately
from completion POSTs. JavaScript syntax and whitespace checks passed. These
tests use fake providers/engines and embeddings; real OCR and CoreML workflow
gates remain open. See [implementation status](../../implementation-status.md).
