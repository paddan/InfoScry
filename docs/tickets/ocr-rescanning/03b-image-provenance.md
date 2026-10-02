# 03b: Image provenance and artifact lifetime

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 03 (the seam is verified; this ticket carries what its reviews left open).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

Every durable page reading that names the image it was read from names a file that exists, inside the
document's own roots, and never records half a provenance.

## Why this is separate from 03

[Ticket 03](03-ocr-engine-contract.md) delivered the engine seam, the import modes and Tesseract
compatibility, and its reviews pushed a further improvement: a revision page now records the source image it
was read from (the rendered page, the managed original, or the bounded copy a large picture was reduced to),
because the comparison slice (06) has to compare that image and the review surface (08) has to show it.
Two findings from that work did not belong to 03's deliverable and are owned here:

- The record and the schema promise a root-confined relative reference, but the persistence boundary
  enforces only that the value is non-blank: a caller can store an absolute or root-escaping reference, and
  a row with only some of the provenance columns reads back as if it had none.
- A fill-missing PDF render is recorded as a page's provenance and then deleted as working material, so a
  staged candidate can point at an image no reviewer can reopen. The provenance test covers the
  check-and-improve mode, where the image is retained.

## Files and interfaces

- `src/main/kotlin/infoscry/domain/Models.kt` (or wherever `SourceImageProvenance` lives) and
  `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt` — validate at the boundary, not only at the writer
- `src/main/resources/db/migration/` (the next number after 019; verify at coding time)
- `src/main/kotlin/infoscry/extract/PdfExtractor.kt`, `ImageExtractor.kt`, `DocumentExtractor.kt` — artifact lifetime
- `src/main/kotlin/infoscry/ocr/{OcrEngine.kt, CandidateRevisionSink.kt}`
- Tests: `ImageExtractorTest`, `PdfExtractorTest`, `OcrEngineContractTest`, `RevisionPublicationTest`,
  `SchemaMigratorTest`

## Test-first implementation

- [ ] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - An absolute path, a `..` reference, or a reference that leaves the root it names is refused where the provenance is written, not only where an image is resolved.
  - A row carrying only part of the provenance (a path without a hash or dimensions, or the reverse) is refused on write and does not read back as "no provenance".
  - A page staged from a fill-missing render references an image that still exists after the attempt finishes; if the render is deliberately not retained, the page records no provenance at all rather than a dead one.
  - An as-is picture's reference resolves inside the managed library root and a reduced copy's inside the artifact root, and neither resolves to an absolute path a reviewer could see.
  - An archive migrated from 018/019 keeps every existing page readable with absent provenance.
- [ ] Decide and record the artifact-lifetime rule: a render a candidate references is retained until that candidate is published, withdrawn or discarded, and the decision is stated where the render is written.
- [ ] Enforce the shape in the schema where SQLite can (relative, no traversal, all-or-none provenance columns) so nothing can be written around the store.
- [ ] Keep the store's existing validation style and do not let a read return a partial provenance silently.
- [ ] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [ ] Review the diff against the spec and update STATUS.md. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.extract.*' --tests 'infoscry.ocr.*' --tests 'infoscry.document.*' --tests 'infoscry.storage.*'
```

A successful fake-provider run does not satisfy a real-tool or hardware gate.
