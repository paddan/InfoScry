# 07b: Import execution and admission — implemented baseline, open repair gate

**Status:** Implemented baseline with unresolved correctness work; not a completed import/Retry gate as of 2026-10-02.
**Blocked by:** 07's implemented backend.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts:** [CONTRACTS.md](CONTRACTS.md).

## Implemented and historically verified

Job-owned external approval, settings revalidation, rescan attempt claiming, CLI approval remedies, admitted runtime identity and NEEDS_REVIEW status exist. Commit `97d7ce8` adds import comparison against its held direct text and publication of approved initial-import pages. Its recorded normal gate is 106 suites / 1364 tests / zero failures; the 2026-10-02 assessment separately reran 116 focused tests successfully. These runs do not cover every remaining scenario below.

A text-only import baseline has no stored revision/hash today; it comes from immutable managed bytes. External accounting uses the extraction page key so transcription and review count a page once. Initial imports may publish approved pages; a rescan currently refuses publication while any page remains pending. The latter differs from the original spec's mixed retained-baseline publication wording and requires reconciliation before a dependent implementation changes behavior (see CONTRACTS.md).

## Required follow-up slices

- [ ] 07c — Durable candidate binding and staged checkpoints: reuse committed OCR after restart or approval pause.
- [ ] 07d — Retry OCR/review/dispatch integration: Retry currently calls ingestion without review or dispatch wiring.
- [ ] 07e — Non-image import compatibility: candidate-sink selection currently depends only on collection mode.
- [ ] 07f — External review accounting coverage: production external classification tested with a recording transport, without real uploads.

## Remaining downstream work

[08d](08d-review-read-api.md) and [08e](08e-review-decision-publication.md) own reading and deciding pending import proposals, including partially published imports. A pure scanned page with no baseline remains a proposal outside retrieval; do not solve this by silently switching mode or auto-approving. Missing embedding runtime remains an explicit publication prerequisite; staging and decisions must remain durable.

## Closing this gate

07b closes only after 07c–07f have focused regression evidence and accumulated normal gates. Do not repeat completed baseline implementation or mark downstream UI/history/runtime gates complete. Each repair ticket is independently reviewable; see STATUS.md for the execution order. Commit/push requires separate authorization.
