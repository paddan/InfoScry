# 04 — Layout artifacts and revision provenance

**Status:** Not started — documentation only, 2026-10-10.
**Blocked by:** 03 reviewed; 02 frozen.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Write and retain normalized layout artifacts through the complete existing page
lifecycle. Maps to the persistence portion of plan Task 2.

**Own:** `src/main/kotlin/infoscry/pdf/PageTextLayoutStore.kt`, its test,
`src/main/kotlin/infoscry/ocr/OcrEngine.kt`, `PageImageRenderer.kt`,
`CandidateRevisionPhases.kt`, `CandidateRevisionSink.kt`,
`src/main/kotlin/infoscry/extract/DocumentExtractor.kt`, `PdfExtractor.kt`,
`TesseractOcr.kt`, `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt`,
`src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt`,
`src/main/kotlin/infoscry/document/RevisionRestoreService.kt`,
`src/main/resources/db/migration/001_baseline.sql` for layout columns only.
Inspect all sinks/copying paths; request ownership of additional files centrally.
**Tests:** PageTextLayoutStoreTest and LayoutProvenanceTest in `infoscry.pdf`.
**Consume:** 02 contracts, 03 aligner and existing OCR result/image provenance.

## Acceptance

1. Capture geometry/transform while rendering the original page, using actual
   image dimensions/crop/rotation/UserUnit. Bind layout to canonical normalized
   extractedText, not an earlier pre-normalization provider string.
2. Tesseract can reuse its TSV positions. Surya/LLM positioning uses a separately
   identified local pass with existing subprocess limits; it neither changes
   selected OCR engine/text nor adds an external request or approval pause.
3. Sidecar writes are atomic, bounded, root-confined and hash-verified before
   reference commit. Keep engine/TSV and normalized-layout references separate.
4. Retry/resume reuses committed layouts and text. Repeat append cannot change
   provenance. Unselected/failed pages and Restore inherit matching layout and
   producer; identical bytes do not fabricate an absent layout reference.
5. Interrupt after artifact write/before page commit, and after page commit:
   rerun twice, assert convergence and no repeated successful transcription.
6. Missing local positioning or ambiguous text records incomplete/absent layout
   but cannot fail a successful page reading, publication or the next rescan.
7. Older records without layout remain readable; no backfill, migration or owner
   reset. Report schema/deployment compatibility before any installation.

## Steps and checks

- [ ] Write failing store, normalization, selected-page inheritance and Restore tests.
- [ ] Implement sidecar/reference plumbing through all actual constructor/copy paths.
- [ ] Exercise artifact corruption/symlink escape and both crash boundaries.
- [ ] Run `./gradlew test -PskipFrontend --tests 'infoscry.pdf.PageTextLayoutStoreTest' --tests 'infoscry.pdf.LayoutProvenanceTest' --tests 'infoscry.document.RevisionRestoreTest' --tests 'infoscry.storage.CandidateResumeUniquenessTest'`.
- [ ] Return actual copy-path inventory and proof of zero extra provider calls.

Ticket 06 owns subsequent schema/job wiring edits and starts only after this
agent releases shared files. Coordinator runs the accumulated gate at integration.

## Completion evidence

Not run. Return owned diff, behavioral RED/GREEN commands/results, acceptance
vector outcomes, exact exported interfaces and remaining limits. Checkboxes are
future work, not proof. Coordinator review is required before unlocking dependents.
