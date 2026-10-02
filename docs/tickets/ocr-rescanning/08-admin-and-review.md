# 08: Admin profiles, collection controls and page review

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 07.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

Users can configure OCR, start a reviewed rescan and resolve uncertain pages entirely in Admin.

## Files and interfaces

- `web/src/lib/OcrProfilesPanel.svelte (new)`
- `web/src/lib/OcrReviewPanel.svelte (new)`
- `web/src/lib/CollectionsPanel.svelte`
- `web/src/lib/api.ts`
- `web/src/routes/+page.svelte`
- `src/main/kotlin/infoscry/server/OcrRoutes.kt`

Use CONTRACTS.md DTOs, revision guards and job APIs; integrate existing source-image serving with opaque collection/document/page IDs, never arbitrary filesystem paths.

**Tests:** Create web/src/lib/OcrProfilesPanel.test.ts and OcrReviewPanel.test.ts; extend CollectionsPanel.test.ts and server review-route tests.

## Test-first implementation

- [ ] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Switch collection during settings save, preview or review fetch; late results never overwrite the new selection.
  - Reload waiting-for-approval/review and resume from persisted state; duplicate clicks dispatch once.
  - Concurrent tab edits return a conflict; HTML/script-like OCR is displayed literally.
  - Keyboard-only review, focus return, narrow layout, large-document page navigation and manual edits preserve decisions without loading every image at once.
- [ ] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [ ] Add reusable OCR profile management, image capability status and key-presence indicators, independent of Ask/Investigate profiles.
- [ ] Collection controls show engine, transcription/review profiles, import mode and external allowance. Preserve defaults while changing collection and reject stale asynchronous responses.
- [ ] Scan again previews pages, overrides, destinations and available cost estimates. Show durable awaiting-approval, resume, cancel and processing states after reload.
- [ ] Review shows image, baseline, candidate, plain-text differences and reason. Implement Keep existing, Use new, Edit text, and explicit whole-document scope actions; show revision conflicts and preserve unsaved input.
- [ ] Manual decisions form a publication batch; only after successful chunk/embed/index does searchable text change. Flag image-only uncertain pages and explain why they are not in search.
- [ ] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [ ] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.server.OcrRoutesTest'
(cd web && npm test -- --run && npm run check && npm run build)
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
