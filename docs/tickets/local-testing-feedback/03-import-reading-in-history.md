# 03: Revision history names the OCR method an import used

**Status:** Not started. Unchecked criteria are requirements, not evidence.
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

- [ ] Failing tests first: an imported document's history names its engine/mode/language; a retry's revision names
  the retry's settings; a direct-text-only document says no page needed OCR; no endpoint, key variable or path is
  exposed.
- [ ] Implement from a durable record, never by reading today's collection settings.
- [ ] Extend the text-history browser scenario.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.document.*' --tests 'infoscry.server.Ocr*'
(cd web && npm test -- --run)
```
