# 03 — Coherent page text, image and provenance API

**Status:** Implemented and independently reviewed 2026-10-09.
**Blocked by:** None.
**Spec:** [Wide workspace and page review](../../specs/2026-10-09-workspace-and-page-review.md).
Read the [plan and shared gates](../../plans/2026-10-09-workspace-and-page-review.md).

## Deliverable and files

Create `src/main/kotlin/infoscry/document/PageReviewService.kt`,
`src/main/kotlin/infoscry/server/PageReviewRoutes.kt`,
`src/test/kotlin/infoscry/server/PageReviewRoutesTest.kt`,
`src/test/kotlin/infoscry/document/PageReviewServiceTest.kt`.
Modify `src/main/kotlin/infoscry/server/Routes.kt`, `web/src/lib/api.ts`;
reuse `SourceRoutes.kt`, `DocumentRevisionStore.kt`, `RescanService.kt` rendering
and provenance patterns without duplicating publication or source text storage.

## Interfaces

Produces the two `/page-review` routes and exact metadata fields from the spec.
Add `getDocumentPageReview(collectionId, documentId, options)` to `api.ts` where
options holds ordinal, optional revision/offset/limit; return typed metadata and
build image URLs from the same documentId/revisionId/ordinal/imageKind tuple.
The service resolves revision identity before either representation is returned.

## Behavioral acceptance vectors

1. Generated three-page PDF: ordinal 1 returns page two's text and image,
   including an empty-text page in physical page totals/navigation.
2. Publish revision B after metadata for A: A's image/text remain paired;
   superseded A is readable, candidate/foreign revision IDs are not.
3. Preserved page retains its own OCR image and method. Direct-text page uses
   labelled original rendering; missing OCR artifact is unavailable.
4. Foreign collection/document, invalid ordinal, missing managed copy, symlink
   escape and oversized raster return curated errors without paths or content.
5. Missing/changed original with intact published text and OCR artifact: text and
   stored image still return 200; unknown physical total is null, exact-revision
   recorded neighbors remain usable. Original rendering alone fails independently.
6. Serving metadata/image twice has the same result and no external/GPU calls;
   bounded text paging preserves Unicode and works without an embedder.
7. A successful image reread with a different engine and identical text/image
   records the new method. Failed rereads and restores retain the baseline image
   and producer; repeated checkpoint/append does not change attribution. Unknown
   producer stays unavailable, and a foreign document producer is never accepted.

Page method provenance requires nullable `producerRevisionId` on revision page
drafts/stored text and `producer_revision_id` in the fresh baseline. Successful
import/rescan pages stamp the candidate; preservation/restoration inherits the
baseline value including null. Update the extraction sink, candidate phases and
rescan success/preservation writes together. Do not use text/image equality to
infer producers or migrate/backfill the owner's existing archive.
Render rescans into an operation/candidate-specific artifact namespace that is
stable on resume. Cover a baseline produced by a prior rescan followed by a failed
reread at different DPI: both its active preserved and historical image bytes
remain available and match their recorded hashes.

## Implementation steps

- [ ] Add failing behavioral tests for each numbered vector above. Record actual
  failure output before implementation; compilation failure alone is not evidence.
- [ ] Run the focused side's command below and confirm the intended failures.
- [ ] Implement the deliverable: Use existing immutable managed-copy/hash checks and recorded SourceImageProvenance.
Cache generated original-page renders by managed hash/ordinal/render settings with
atomic publication, so retry after an interrupted render converges. Check original
integrity before generating a missing render. Empty-text documents retain nullable
unit/revision identity; never invent a citation revision. Add route registration
and typed client together.
- [ ] Run focused tests green, then the accumulated gate; record exact commands,
  counts and any unperformed hardware/manual checks.
- [ ] Review contracts, repeat/recovery behavior and spec coverage; update ticket
  status only from evidence. Preserve unrelated edits. Do not commit/push without
  the owner's Git instruction.

## Commands

```sh
./gradlew test -PskipFrontend --tests 'infoscry.server.PageReviewRoutesTest' --tests 'infoscry.document.PageReviewServiceTest'
./gradlew check
```

## Verification record — 2026-10-09

Generated-PDF/SQLite tests establish exact-revision text/image binding, blank
physical pages, nullable unknown totals and recorded neighbors, independent
missing originals/images, bounded Unicode paging, confinement and raster limits.
Measured repeated reads reuse verified census/render caches and indexed snapshot
queries. Durable per-page producers distinguish identical successful rereads from
baseline preservation and survive restoration and checkpoint retry. Candidate-
scoped render directories retain historical images across failed reads at another
DPI; retries reuse verified committed images, repairing changed raster bytes
without repeating committed OCR.

Meaningful RED failures preceded each implementation/fix, including route absence,
truncated-cache reuse, original-dependent text access, history query hydration,
misattributed identical rereads and overwritten historical images. Final focused
gate passed 46 tests. The prior producer/import integration gate passed 119 tests.
Final `./gradlew check` succeeded in 4m47s: 130 JVM suites / 1,585 tests and 19
frontend files / 337 tests, zero failures, errors or skips. Client tests and Svelte
type checks passed, and the frontend production build passed. Independent initial
review and three scoped fix reviews closed every Important finding.

This is fresh-schema implementation; no owner archive migration/reset occurred.
TIFF display conversion is implemented and tested in 04; browser geometry and
integration are verified in 07 with generated fixtures and fake providers. Real
OCR providers, CoreML and human accessibility remain separate unverified gates.
