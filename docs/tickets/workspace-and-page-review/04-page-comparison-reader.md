# 04 — Side-by-side page review

**Status:** Implemented and independently reviewed 2026-10-10.
**Blocked by:** 02, 03.
**Spec:** [Wide workspace and page review](../../specs/2026-10-09-workspace-and-page-review.md).
Read the [plan and shared gates](../../plans/2026-10-09-workspace-and-page-review.md).

## Deliverable and files

Create `web/src/lib/PageReview.svelte`, `web/src/lib/PageReview.test.ts`.
Modify `web/src/routes/+page.svelte`, `web/src/lib/api.ts` and
`web/src/routes/page.test.ts`. Keep image/text fetch state in PageReview, not
CollectionsPanel; SourceWorkspace owns the outer layout.

## Interfaces

Consumes page-review metadata/images and SourceWorkspace review mode. Produces
`PageReview` props collectionId, documentId, initialOrdinal, optional revisionId,
`onBack` and `onScanPages: (ordinals: number[]) => void`. Historical mode cannot
emit a scan request. Ticket 06 connects the scan callback.

## Behavioral acceptance vectors

1. Collections Open document uses full review; page two shows image and text
   for that same exact revision. Previous/Next include blank/failed pages.
2. Missing original does not hide intact text/stored OCR image. For unknown
   physical total, display Page N with total unavailable and use recorded neighbor
   ordinals; never pretend stored text pages establish the whole physical count.
3. Slow page-one responses after page two are ignored independently for text
   and image; resource failure never leaves a misleading previous image visible.
4. Divider works with pointer and arrow keys, zoom has fit/reset, narrow tabs
   retain page/revision/zoom; image/text have independent scrolling.
5. Truncated text supports Load more; one resource failure has its own Retry.
6. Historical citation stays historical and cannot scan; unknown-revision excerpt
   stays excerpt-only. Unsupported formats retain the existing text source view.
7. Publication offers refresh; accepting it loads both new resources together.

## Implementation steps

- [ ] Add failing behavioral tests for each numbered vector above. Record actual
  failure output before implementation; compilation failure alone is not evidence.
- [ ] Run the focused side's command below and confirm the intended failures.
- [ ] Implement the deliverable: Render text as text, not raw HTML. Announce page/loading/errors accessibly and
keep navigation controls visible. Do not fabricate synchronized line scrolling.
Implement marked-page state in ticket 06; this ticket exposes the scan callback
without making provider requests.
- [ ] Run focused tests green, then the accumulated gate; record exact commands,
  counts and any unperformed hardware/manual checks.
- [ ] Review contracts, repeat/recovery behavior and spec coverage; update ticket
  status only from evidence. Preserve unrelated edits. Do not commit/push without
  the owner's Git instruction.

## Commands

```sh
(cd web && npm test -- --run && npm run check && npm run build)
./gradlew check
```

## Verification record — 2026-10-10

Supported PDF/image documents open side-by-side review, including documents with
no extracted content unit. Exact-revision page text/image binding, nullable total
and neighbors, Unicode paging, independent resource retry, bounded zoom and divider,
narrow tabs, and explicit publication refresh have component coverage. Known-revision
PDF page citations open pinned historical review; unknown revisions remain saved
excerpts. Consecutive citations replace the exact target while unrelated updates
retain reader state. Late image events cannot alter the current page.

TIFF display routes return a bounded PNG with tested MIME, dimensions and decoded
pixels, retaining original bytes/provenance. Ten focused route tests passed.
Behavioral RED preceded the component implementation and both review fixes; the
initial component RED output was observed but not saved, while TIFF and fix logs
were retained. Independent Luna review and two scoped fix reviews closed all
Important findings. Final frontend tests passed 21 files / 402 tests, type checks
had zero errors/warnings and the production build passed.

Final `./gradlew check` passed in 4m47s: 130 JVM suites / 1,586 tests and
402 frontend tests, zero failures, errors or skips.

Actual viewport/pointer/independent long-page scroll geometry is verified in ticket 07.
Human accessibility, real OCR providers and CoreML are separate unverified gates.
No owner archive migration/reset or provider calls occurred.
