# 03 — Local canonical-text positioning

**Status:** Not started — documentation only, 2026-10-10.
**Blocked by:** 02 frozen.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Implement the pure bounded alignment of the published reading onto local spatial
anchors. Maps to the algorithm portion of plan Task 2.

**Own:** `src/main/kotlin/infoscry/pdf/OcrGeometryAligner.kt`,
`src/test/kotlin/infoscry/pdf/OcrGeometryAlignerTest.kt` and private test fixtures
under `src/test/resources/pdf/alignment/`.
**Consume:** GeometryAlignmentInput → PageTextLayout from 02.
**Do not edit:** OCR adapters, worker script, schema or revision stores; 04 obtains
local anchors and persists the result.

## Acceptance

1. Swedish canonical text survives normalization/matching byte-for-byte. Match
   normalization has a reversible map to canonical UTF-16 offsets.
2. Corrected spelling/number, ligatures, whitespace, hyphenation and split/merged
   words map to measured word/line anchors in reading order. Assertions compare
   independent expected rectangles, not merely recovered strings.
3. Repeated anchors, absent lines, insertions without a visual counterpart and
   reordered columns cannot be accepted on edit-distance score alone. Return
   incomplete layout with PDF_TEXT_ALIGNMENT_AMBIGUOUS; retain all canonical text.
4. Surya multi-line blocks require real line/word anchors. The aligner never
   relabels a block as LINE or spreads the entire page text across one box.
5. Bound work/memory explicitly and test the boundary. Exceeding it gives a safe
   incomplete result; no text truncation, network, process or store writes.
6. Same immutable input/version produces the same mapping and hashes on repeat.

## Steps and checks

- [ ] Add failing independent fixture assertions for every numbered vector.
- [ ] Implement the strategy accepted after 01; keep canonical text immutable.
- [ ] Test complete coverage, UTF-16 offsets, ambiguous cases and resource limits.
- [ ] Run `./gradlew test -PskipFrontend --tests infoscry.pdf.OcrGeometryAlignerTest`.
- [ ] Return supported matching cases, measured limit and any ambiguity examples
  as safe fixture IDs. No real OCR/provider quality claim from synthetic anchors.

If the probe's positioning strategy proves inadequate, report the failing case;
do not replace canonical text or invent geometry to finish this ticket.

## Completion evidence

Not run. Return owned diff, behavioral RED/GREEN commands/results, acceptance
vector outcomes, exact exported interfaces and remaining limits. Checkboxes are
future work, not proof. Coordinator review is required before unlocking dependents.
