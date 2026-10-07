# 08: Read a document again with a chosen OCR method, including after a failed import

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 04 (for choosing an image-model profile).
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Problem

Scan again lets a person choose engine, mode and profiles, but it needs a published text, so it is not available
for a document whose import failed; Retry is available there but always uses the collection's current settings.

## Deliverable

Retry for one document (and for a selection) can choose the OCR engine, mode, language and profiles, validated and
admitted like a rescan preview (engine available on this machine, external page allowance and approval, measured
image capability), with the collection's settings as the default. A document that already has a published text
keeps using Scan again, so the review-before-replacing rule is unchanged.

## Files and interfaces

- `src/main/kotlin/infoscry/jobs/RetryJobHandler.kt`, `RetryJobPayload.kt`, the retry route
  (`POST /api/collections/{id}/documents/retry`), and the admission/validation used by `RescanService.preview`.
- Web: the Retry action in document Details and the bulk retry in `web/src/lib/CollectionsPanel.svelte`, reusing the
  engine/mode/profile controls from `DocumentRescan.svelte`.

## Test-first implementation

- [ ] Failing tests first: a failed document retried with another engine is read with that engine and its revision
  records it (ticket 03); an unavailable engine or unmeasured external profile is refused before anything is queued;
  external pages need approval as in a rescan; omitting the choice keeps today's behaviour; a document with a
  published text is directed to Scan again.
- [ ] Browser scenario: a failed document is retried with a chosen method and becomes searchable.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.server.*Retry*'
(cd web && npm test -- --run && npm run check)
```
