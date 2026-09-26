# InfoScry implementation status

**Updated:** 2026-09-25, branch `codex-task21-ask`.

The backend and CLI implement local import/extraction/OCR, collections and jobs,
CoreML embeddings, hybrid search, recoverable Lucene reindexing, LLM profiles,
cited Ask, and bounded Investigate. The reader frontend now includes collection
search, bounded extracted-source pages and managed originals, Ask, and an
Investigate conversation panel with persisted history, and an Admin view for
configuring LLM profiles. A focused static-asset
route fix lets the built JavaScript load in the browser.

The remaining scope is in the [active local-use plan](superpowers/plans/2026-09-23-infoscry-next-work.md):
exercise Ask and Investigate through the browser with a disposable fake LLM,
decide whether the foundational source pane is sufficient or the design's
format-specific previews are still required, and write a short tested run guide.
Import, jobs, and logs stay in the CLI; LLM profiles are now manageable in the
web Admin view as well. There
is no CI, package, or release milestone.

## Current code state

The optional HTTP profile-probe refactor was removed with the user's approval.
The existing CLI `llm test` command remains. Reader endpoints and the static
asset fix are in `75771c0`; the reader UI and Investigate history/duplicate-turn
guard are in `21ac4b0`. These are local branch commits, not a release. The
browser/provider acceptance described below is still open.

## Verified so far

- The last accepted backend slices cover safe job/item API responses, collection
  OCR settings and job paging, and LLM profile CRUD/defaults. Their focused
  tests and accumulated `./gradlew check` passed; independent review found no
  outstanding major issue.
- Focused source-route tests passed, including cross-collection 404, original
  byte ranges, and an emoji at the source-page boundary. The focused
  Investigate-route suite passed all 11 tests, including collection-scoped
  history. Frontend Vitest passed 22 tests, `npm run check` had zero diagnostics,
  and `npm run build` succeeded. After the Investigate duplicate-turn guard,
  the accumulated `./gradlew check` passed in 7m46s.
- `./gradlew externalTest` passed against local Tesseract, and
  `./gradlew gpuIntegrationTest` passed against the installed E5 model on this
  Mac. The latest GPU profile recorded 38 CoreML kernel events and 99.92% of
  measured kernel time in CoreML; see `docs/gpu-validation.md`.
- In a disposable data directory, CLI import completed for
  `src/test/resources/fixtures/sample.txt`. The built browser UI found it via
  keyword, semantic, and hybrid modes, opened its extracted source at lines
  1–6, and the original endpoint returned HTTP 206 for a byte range. The
  fixture's SHA-256 remained unchanged. This is not a browser proof of Ask or
  Investigate against a provider; their normal tests use fakes.

No branch integration or local acceptance is implied by this status.

## Dark reader interface

The reader now uses a dark sidebar layout inspired by Palmemordsarkivet, with
separate Search, Ask, Investigate, and Admin views. Switching views preserves mounted
panels and their current state. The Search sidebar exposes the existing API's
mode, file type, path, metadata, import-date, document-status, and OCR filters.
Ask and Investigate have separate configured-profile selectors; Search filters
do not apply to those modes. Sources open beside the active view on desktop and
below it on narrow screens, with a close action.

The frontend suite passed 24 tests, Svelte/TypeScript checks passed with zero
diagnostics, and the static build passed. A local browser check at 1440px and
390px verified dark rendering even with a light OS preference, filter request
parameters, profile selection, cost retention, keyboard tabs, retained answers,
and source links for Ask and Investigate. These browser checks used intercepted
fixture API responses, not a running backend/provider; the end-to-end acceptance
and format-specific source-viewer gates above remain open.
