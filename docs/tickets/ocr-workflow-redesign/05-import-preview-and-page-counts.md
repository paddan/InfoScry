# 05: Page counts and the import preview

**Status:** Not started
**Blocked by:** None for logic. It uses `ReadingMethod.kt` (CONTRACTS section 1: create verbatim if absent) and `ReadingMethodCatalog` (section 1: build against a fake until ticket 03 lands).
**Plan:** [OCR workflow redesign, Task 5](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 1, 4, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Start dialog", "Preview").

## Problem

The person cannot see how many pages an import will read before it starts, and a
changed file set cannot be detected between confirmation and start.

## Deliverable

`POST /api/imports/preview` with the exact request/response and `previewHash` encoding
of CONTRACTS section 4, backed by `ImportPreviewService.preview` and `.hashOf`, a shared
`PageCounter`, and a shared file enumeration.

## Can be built against

The preview needs the method's availability, destination, external flag and profile
revision. Inject `ReadingMethodCatalog` (interface in CONTRACTS section 1) and test with
a fake. Directory filtering reuses the existing `enumerate`. No other ticket's code is needed.

## Files and interfaces

- Create `src/main/kotlin/infoscry/jobs/ImportPreviewService.kt` (`preview(request): ImportPreview`, `hashOf(request): String`), `src/main/kotlin/infoscry/server/ImportPreviewRoutes.kt`, `src/main/kotlin/infoscry/extract/PageCounter.kt` (CONTRACTS section 4).
- Modify `src/main/kotlin/infoscry/document/RescanService.kt`: extract `RescanPageSource.pageCount` into the shared `PageCounter` (behavior unchanged; this file has uncommitted edits: read and keep them).
- Modify `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt`: expose the file enumeration `enumerate` (~:531) as a function the preview shares; the import job's behavior must not change.
- Register the route in `src/main/kotlin/infoscry/server/Routes.kt`.
- Reuse `costEstimateOf` (`RescanService` ~:1529) for the estimate.
- Test `src/test/kotlin/infoscry/jobs/ImportPreviewServiceTest.kt`.

## Test-first implementation

- [ ] Write failing tests: a PDF counts its pages; an image counts 1; an unreadable PDF has `pages = null`, a `reason` and `atLeast = true`; directory filters apply as in import; `previewHash` is stable across repeated calls and changes when a file's bytes, the method or the profile revision change; cost is null with a basis string when a price is unknown.
- [ ] Run `./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportPreviewServiceTest'`; expect FAIL.
- [ ] Review focus 1: a directory import containing one unreadable file reports `at least N`, lists that file with its reason, and the total covers only the readable files; the unreadable file is reported and is not silently dropped.
- [ ] Idempotence: preview twice gives equal responses and writes nothing; `hashOf(request)` equals `preview(request).previewHash`; the hash does not depend on the order of `paths`.
- [ ] Route test: unknown collection 404, bad method 400, unavailable method 409 `METHOD_UNAVAILABLE`.
- [ ] Implement.
- [ ] Run the class plus `infoscry.jobs.Import*` and the rescan page-count tests; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportPreviewServiceTest' --tests 'infoscry.jobs.Import*' --tests 'infoscry.jobs.Rescan*'
./gradlew check
```

## Out of scope

Starting an import with the hash (ticket 06); the dialog (ticket 13); rescan preview
(ticket 08).
