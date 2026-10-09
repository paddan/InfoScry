# 07: Read every page; no import mode

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** None.
**Plan:** [OCR workflow redesign, Task 7](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 9, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) (decision 3, "Run and document status").

## Problem

`FILL_MISSING` and `CHECK_AND_IMPROVE` import modes skip pages that already have a text
layer, so "all pages are read with the chosen method" is not true.

## Deliverable

`OcrImportMode.READ_ALL`: every page is rendered and read with the snapshot's method even
when a text layer exists. New runs use it; stored snapshots with the old values still load.
The snapshot fingerprint includes it, so checkpoints are never reused across methods.

## Can be built against

Nothing else. Tests use the fake engine. Tickets 04 and 08 also edit `OcrModels.kt`
elsewhere; keep edits to the mode enum and the snapshot's `mode`/fingerprint.

## Files and interfaces

- Modify `src/main/kotlin/infoscry/ocr/OcrModels.kt` (`OcrSettingsSnapshot.mode`), `src/main/kotlin/infoscry/jobs/ImportPipeline.kt` (`sinkFor` :51), `src/main/kotlin/infoscry/extract/DocumentExtractor.kt`, `src/main/kotlin/infoscry/extract/PdfExtractor.kt`.
- Map the old modes to `READ_ALL` for new runs; keep the old enum values readable for stored snapshots.
- Tests `src/test/kotlin/infoscry/extract/PdfExtractorTest.kt`, `src/test/kotlin/infoscry/jobs/ImportJobHandlerTest.kt`.

## Original test-first implementation plan

- Write failing tests: a PDF with a text layer is still read by the (fake) engine on every page; the extraction fingerprint differs between two methods; pages already committed under the same fingerprint are not read again after a restart.
- Run; expect FAIL.
- Idempotence/restart: kill (simulate) after page k is committed, restart; only pages after k are read and the committed page text is unchanged; running the whole import twice reads zero pages the second time.
- A snapshot stored with `FILL_MISSING` or `CHECK_AND_IMPROVE` deserializes without error.
- Implement.
- Run `infoscry.extract.*`, `infoscry.jobs.Import*`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.extract.*' --tests 'infoscry.jobs.Import*'
./gradlew check
```

## Out of scope

Removing the mode from collection settings or the web (tickets 04, 15); page-limit
enforcement (ticket 09); publishing (ticket 10).
