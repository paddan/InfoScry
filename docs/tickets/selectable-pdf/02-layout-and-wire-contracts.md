# 02 — Layout and wire contract freeze

**Status:** Not started — documentation only, 2026-10-10.
**Blocked by:** 01 reviewed.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Implement the validated immutable records and contract validation before parallel
consumers start. Maps to the interface portions of plan Tasks 2–5.

**Own:** `src/main/kotlin/infoscry/pdf/PageTextLayout.kt`,
`PdfArtifactModels.kt`, `SelectablePdfManifest.kt`, `PdfWriteModels.kt`,
`GeometryAlignmentInput.kt`; matching model tests in `src/test/kotlin/infoscry/pdf/`.
**Do not edit:** schema, extraction, jobs, writer, web files or shared fixtures.

## Acceptance

1. Types and field names match CONTRACTS.md exactly, including full page geometry,
   transform convention, positioning version, nullable NOT_CREATED key/totals,
   blank-page counts and unavailable-page reasons.
2. Validate finite matrices/geometry, positive dimensions/UserUnit, quarter-turn
   rotation, valid hashes and confined artifact references. A complete layout
   validates canonical UTF-16 ranges without splitting surrogate pairs.
3. WORD/LINE granularity is explicit. Duplicate order, overlapping ranges,
   zero-size/out-of-bounds rectangles and uncovered non-whitespace characters
   cannot be declared complete. Empty/verified-blank cases are distinguished.
4. DTO serialization round-trips every state, including omitted nullable fields
   as the existing ApiJson serializer emits them. READY/PARTIAL invariants reject
   a missing artifact key/unknown totals; NOT_CREATED is not a durable job state.
5. Freeze the artifact key input list/version convention with 01's measured font
   and renderer assumptions; no consumer invents another cache identity.

## Steps and checks

- [ ] Add failing validation/serialization tests for the numbered cases.
- [ ] Implement the records and pure validators, with canonical-text validation
  taking the text explicitly (a hash alone cannot validate UTF-16 ranges).
- [ ] Run `./gradlew test -PskipFrontend --tests 'infoscry.pdf.PageTextLayoutTest' --tests 'infoscry.pdf.PdfArtifactModelsTest'`.
- [ ] Return exact Kotlin declarations and sample serialized statuses. Coordinator
  reconciles CONTRACTS.md/plan/spec before unlocking 03, 05 and 08.

No storage or routes are implemented here. A necessary signature change must be
settled centrally before parallel implementation, not discovered after merging.

## Completion evidence

Not run. Return owned diff, behavioral RED/GREEN commands/results, acceptance
vector outcomes, exact exported interfaces and remaining limits. Checkboxes are
future work, not proof. Coordinator review is required before unlocking dependents.
