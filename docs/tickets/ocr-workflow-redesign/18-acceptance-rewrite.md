# 18: Acceptance

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** 11, 14, 15, 16, 17 (the whole flow, in web, CLI and backend, with the old flow gone).
**Plan:** [OCR workflow redesign, Task 18](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 11-13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Verification"). `AGENTS.md`: browser acceptance uses the local fake provider, not a real provider or GPU.

## Problem

The existing browser acceptance tests describe the review/approval flow.

## Deliverable

Playwright scenarios for the new flow, passing under `./gradlew externalTest`, and a README
record of what remains unverified.

## Can be built against

The local fake provider used by `InvestigateBrowserAcceptanceTest`. Individual scenarios
may be written as the tickets land; the final run needs all of them.

## Files and interfaces

- Modify `src/test/kotlin/infoscry/server/OcrBrowserAcceptanceTest.kt`, `CollectionsBrowserAcceptanceTest.kt`, `web/e2e/ocr-browser-acceptance.mjs`, `web/e2e/collections-browser-acceptance.mjs`.
- Scenarios: import through the dialog (local and external), Scan again over a failed run, cancel and start again, restart mid-run and start again, history and restore.
- Record in `README.md` what remains unverified (real provider, real CoreML) and the manual checklist.

## Original test-first implementation plan

- Rewrite the scenarios with the local fake provider (list above); each fails against the pre-redesign flow or is new.
- Restart scenario: the server is stopped mid-run, restarted, and Scan again succeeds with no prior action; the old text is intact until the run completes.
- Double-click scenario: confirming twice results in exactly one run.
- Run `./gradlew externalTest`; expect PASS.
- Update `README.md` status and manual checklist; do not describe real-provider or CoreML paths as verified.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew externalTest
./gradlew check
```

## Out of scope

New product behavior; real provider or CoreML runs.
