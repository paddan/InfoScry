# 01 — Positioning feasibility and fixtures

**Status:** Not started — documentation only, 2026-10-10.
**Blocked by:** None.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Prove the smallest useful selection/copy round-trip and establish honest geometry
limits before the remaining implementation. Maps to plan Task 1.

**Own:** `src/test/kotlin/infoscry/pdf/PdfTextPlacementProbeTest.kt`,
`SelectablePdfFixtures.kt` in the same directory, and `src/test/resources/pdf/`
probe fixtures/font license if necessary. Only this agent edits shared fixtures;
later owners request fixture additions from the coordinator.
**Read:** `OcrEngine.kt`, `TesseractOcr.kt`, `SuryaOcr.kt`, `LlmOcr.kt`,
`PageImageRenderer.kt`, `scripts/ocr/surya_worker.py`. No production changes.

## Acceptance

1. Generated fixture has independent known word positions, two columns, Swedish
   letters, repeated anchors, corrected spelling/number, split/merged words and
   a line missing from local recognition. Include stale existing invisible text.
2. A minimal PDFBox probe writes invisible Swedish text; extraction returns the
   canonical text and TextPosition rectangles match the independent fixture
   within 1 PDF point. Compare visible raster before/after.
3. Tesseract word boxes, Surya multi-line blocks and coordinate-free LLM output
   are separate cases. Show which can be positioned and which are ambiguous.
   A block rectangle stretched around arbitrary text does not pass.
4. Record the font's exact asset/version/hash/license and local anchor strategy.
   No owner data, external model call, package install or dependency update.
5. Report synthetic versus real-tool/native-reader evidence separately. Failure
   to establish a general Surya/LLM method does not become invented support;
   return an explicit proposal and stop dependent tickets for review.

## Steps and checks

- [ ] Draw fixtures and pin independent expected words/rectangles/canonical text.
- [ ] Add a failing placement/copy assertion and record its behavioral failure.
- [ ] Add a minimal test-only probe; retain reusable regression fixtures.
- [ ] Run `./gradlew test -PskipFrontend --tests infoscry.pdf.PdfTextPlacementProbeTest`.
- [ ] Return screenshots/raster comparison, rectangle evidence, font identity,
  engine-specific complete/partial findings and unresolved manual gates.

Do not edit the plan/spec while another worker uses them. The coordinator records
probe results and approves the 02 interface freeze. No application feature is
considered implemented by this probe.

## Completion evidence

Run 2026-10-10. Owned diff: `src/test/kotlin/infoscry/pdf/PdfTextPlacementProbeTest.kt`,
`SelectablePdfFixtures.kt`, and fixtures under `src/test/resources/pdf/`
(real Tesseract 5.5.3 TSV, simulated Surya JSON, rendered probe PNG, a copy
of the committed Caveat font). No `src/main` change; no git operations.
RED: a stub probe returning the stale input unchanged failed on canonical
text and on a leftover stale word. GREEN: `./gradlew test -PskipFrontend
--tests infoscry.pdf.PdfTextPlacementProbeTest`, 8/8 passing, independently
re-run by the coordinator and confirmed. Acceptance vectors 1–5: native
round-trip passed at 0.0001pt; Tesseract positioned 101/144 glyphs but missed
the 1pt bound (ink boxes, not glyph-geometric); Surya is block-only (real
worker code inspected, not installed locally); LLM text alone leaves most
words unplaced; font identity/license recorded. Three contract decisions this
raised (anchor tolerance, span granularity, Surya/LLM honesty) are resolved
in the plan's feasibility-evidence section and CONTRACTS.md. Coordinator
reviewed and approved; ticket 02 is unblocked.
