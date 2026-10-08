# 03: Revision history names the OCR method an import used

**Status:** Implemented; browser scenario passes (fake provider).

Design: the import's or retry's frozen OCR snapshot (from its job payload) is stored on the revision it publishes
(`document_revisions.reading_snapshot`, set by `openCandidate` for a check-and-improve candidate and by
`recordPublishedContent` for a fill-missing import). The history reads a rescan's operation first, then that
snapshot, and says "No page needed OCR" when no approved page was read by OCR. Legacy imports with no snapshot
still say "Not recorded".

Verified: `infoscry.jobs.ImportJobHandlerTest` (the four new reading tests pass; the one failing test,
"a file that cannot be copied", is a root-permission case), `infoscry.server.OcrRestoreRoutesTest` (new route test:
engine/mode/language/tool shown, no endpoint, key variable or path in the body), the document and OCR route
classes (two `DocumentDeletionRecoveryTest` permission cases fail under root and are environmental), `RetryJobHandlerTest`, `RescanJobHandlerTest`; `web` `npm test -- --run` (387 passed) and `npm run check`
(0 errors). The new tests were written before the implementation but their red run was not captured.
**Blocked by:** None.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Problem

Text history shows engine, mode, language and model only for revisions a rescan produced. For an import it says
"Not recorded", although the import ran with a known OCR settings snapshot.

## Deliverable

Every revision that an import or retry produced shows the OCR engine, mode, language and tool/model versions it
was read with, or says honestly that a page needed no OCR.

## Files and interfaces

- `src/main/kotlin/infoscry/document/RevisionHistoryService.kt` (`reading` is taken from rescan operations only).
- Where the import's settings live: the import/retry job payloads (`ImportJobPayload`, `RetryJobPayload`), the
  extraction fingerprint and `document_revisions.attempt_fingerprint`. Record the snapshot with the revision if no
  durable link exists (edit `001_baseline.sql`).
- `web/src/lib/OcrHistoryPanel.svelte`.

## Test-first implementation

- [x] Failing tests first: an imported document's history names its engine/mode/language; a retry's revision names
  the retry's settings; a direct-text-only document says no page needed OCR; no endpoint, key variable or path is
  exposed. (Written first; their red run was not captured, see the note above.)
- [x] Implement from a durable record, never by reading today's collection settings.
- [x] Extend the text-history browser scenario: `history-import-reading` in `OcrBrowserAcceptanceTest` (passes against the fake provider; the imported picture names engine, mode, language and tool version, and a text file says "No page needed OCR").

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.document.*' --tests 'infoscry.server.Ocr*'
(cd web && npm test -- --run)
```
