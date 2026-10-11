# 05 — PDF writer with selectable text

**Status:** Not started — documentation only, 2026-10-10.
**Blocked by:** 02 frozen; font choice from 01.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Build the file writer independently of SQLite, job scheduling and UI. Maps to
plan Task 3; may run alongside 03 and 08 after 02.

**Own:** `src/main/kotlin/infoscry/pdf/SelectablePdfWriter.kt`,
`PdfFontResolver.kt`, `src/main/resources/fonts/` pinned asset/license,
`src/test/kotlin/infoscry/pdf/SelectablePdfWriterTest.kt` and private writer fixtures.
**Consume:** PdfWriteRequest → PdfWriteResult and manifest records from 02.
Do not modify shared records/fixtures, pipeline or job wiring without coordination.

## Acceptance

1. Mixed PDF retains all physical pages/order/dimensions. Native pages retain
   selectable native text. OCR pages use the exact reading image and one new
   invisible layer, with no copied stale text or previous generated-PDF input.
2. Extract the canonical replacement text once, including ÅÄÖ/åäö. No substituted
   or missing glyphs; unsupported font coverage gives an explicit page reason.
3. Verify TextPosition rectangles within 1 PDF point for rotations 0/90/180/270,
   differing CropBox/MediaBox origins, UserUnit and reduced-DPI images. Compare
   visible rendering at the same scale; no double rotation/crop or altered page.
4. Missing/ambiguous geometry gives UNAVAILABLE/PARTIAL, preserving visible page
   content without retaining stale text. If that is impossible, fail the file;
   never omit the page. Blank pages require verification, not empty OCR alone.
5. Text order and copyable line/word ranges follow the canonical reading, including
   columns, hyphenation and split/merged words. PDF search is not an acceptance test.
6. Embed the pinned licensed font with Unicode mappings and lossless images.
   Use file-backed PDFBox storage and bounded raster processing. Reopen/validate
   the temporary output before returning its actual hash/size/manifest.
7. Record flattened OCR-page annotation/link/form limitations, visible annotation
   appearance and bookmark remapping. Original bytes/hashes stay unchanged.

## Steps and checks

- [ ] Add failing text, spatial, visual, physical-page and unchanged-original assertions.
- [ ] Implement modes and invisible layer using existing PDFBox 3.0.7.
- [ ] Exercise partial coverage, unsupported glyphs and a multi-page size/memory fixture.
- [ ] Run `./gradlew test -PskipFrontend --tests infoscry.pdf.SelectablePdfWriterTest`.
- [ ] Return actual output/manifest fixture paths, comparison evidence and limits.

A TextStripper pass alone does not prove mark/copy usability. Native Preview and
second-reader selection stay in 09's manual gate unless actually performed here.

## Completion evidence

Not run. Return owned diff, behavioral RED/GREEN commands/results, acceptance
vector outcomes, exact exported interfaces and remaining limits. Checkboxes are
future work, not proof. Coordinator review is required before unlocking dependents.
