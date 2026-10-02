# 09: Revision history and explicit restoration

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 08.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

Users can inspect all published text versions and restore one without rerunning OCR or rewriting the original PDF.

## Files and interfaces

- `src/main/kotlin/infoscry/document/RevisionPublicationService.kt`
- `src/main/kotlin/infoscry/server/OcrRoutes.kt`
- `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt`
- `web/src/lib/OcrHistoryPanel.svelte (new)`
- `web/src/lib/CollectionsPanel.svelte`
- `src/main/kotlin/infoscry/document/DocumentService.kt`

Restore(documentId, expectedRevisionId, restoreRevisionId) creates a new publication referencing historical page texts; it shares the ticket 02 consistency boundary.

**Tests:** Create src/test/kotlin/infoscry/document/RevisionRestoreTest.kt and web/src/lib/OcrHistoryPanel.test.ts; extend deletion and source-route coverage.

## Test-first implementation

- [ ] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Three publications followed by restore retain all history and stable page IDs; OCR call count stays zero.
  - Restore from stale tab or during deletion refuses safely; embedding failure leaves current revision active.
  - Old evidence opens its actual revision and new search returns only the restored active content.
  - Deleting a document removes proposals and history but never external originals or another collection copy.
- [ ] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [ ] Show engine/model, timestamps, manual/automatic page changes and restore action. Reuse immutable page images instead of copying them per version.
- [ ] Restore requires current-version match and no conflicting mutation. Rebuild chunks/embeddings if required by current indexing contract; never rerun OCR.
- [ ] Ensure collection/document deletion includes all revision/candidate/review rows and managed artifacts, and that restore cannot race deletion into resurrection.
- [ ] Expose revision-aware source links and clearly identify historical/unknown legacy evidence. New search remains on the active revision only.
- [ ] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [ ] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.document.*' --tests 'infoscry.server.*'
(cd web && npm test -- --run && npm run check && npm run build)
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
