# 08: Read a document again with a chosen OCR method, including after a failed import

**Status:** Implemented and verified by the focused backend and frontend tests below; the browser scenario is not done. Unchecked criteria are requirements, not evidence.
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

## Decision: the choice is a rescan override, resolved by the rescan admission and frozen in the payload

`POST /api/collections/{id}/documents/retry` takes an optional `ocr` object (`engine`, `importMode`,
`transcriptionProfileId`, `reviewProfileId`, `language`); absent or empty means today's behaviour exactly (the
collection's settings, resolved as before). A non-empty choice is
resolved by `RescanService.resolveChosenReading`, the same snapshot resolution and `requireReadingUsable` checks
`preview` runs (engine in this build, embedder, profile exists and enabled, external profile measured as image
capable, key present), so the codes are the rescan ones (409 `RESCAN_ENGINE_UNAVAILABLE`,
`RESCAN_PROFILE_UNMEASURED`, ...) and nothing is queued on a refusal. A chosen Tesseract additionally needs the tool,
which `preview` does not check. The resulting snapshot and the extraction settings derived from it (with the chosen
language) are frozen into `RetryJobPayload`, so the handler, the fingerprint, the external allowance (the collection's,
0 by default) and the revision's reading snapshot (ticket 03) follow the existing path unchanged. External pages wait
for approval as in an import: `POST /api/jobs/{id}/approve-external` now also accepts a RETRY job (it decoded only
import payloads). Per document, a document that already has a published text is refused with
`RETRY_USE_SCAN_AGAIN` and a format without page images with `PAGE_IMAGES_UNSUPPORTED` (the `rejected` entries gained an
optional `code`); other documents of the request are still queued. `language` is the one addition to the rescan
overrides (`RescanOverrides.language`, never sent by a preview). The profile selects offer OCR profiles (which include
copies of LLM profiles made in ticket 04), as Scan again does. The controls are the shared
`OcrChoiceFields.svelte`, used by `DocumentRescan.svelte`, the document Retry and Retry all eligible documents.

## Test-first implementation

- [x] Failing tests first (7 of the 9 new backend tests failed before the implementation; the two that pin unchanged behaviour passed): a failed document retried with another engine is read with that engine and its revision
  records it (ticket 03); an unavailable engine or unmeasured external profile is refused before anything is queued;
  external pages need approval as in a rescan; omitting the choice keeps today's behaviour; a document with a
  published text is directed to Scan again.
- [ ] Browser scenario (not done): a failed document is retried with a chosen method and becomes searchable.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.server.*Retry*'
(cd web && npm test -- --run && npm run check)
```
