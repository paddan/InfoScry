# 06 — Mark pages and confirm targeted reading

**Status:** Implemented and independently reviewed 2026-10-10.
**Blocked by:** 04, 05.
**Spec:** [Wide workspace and page review](../../specs/2026-10-09-workspace-and-page-review.md).
Read the [plan and shared gates](../../plans/2026-10-09-workspace-and-page-review.md).

## Deliverable and files

Modify `web/src/lib/PageReview.svelte`, `web/src/lib/PageReview.test.ts`,
`web/src/lib/StartReadingDialog.svelte`, `web/src/lib/StartReadingDialog.test.ts`,
`web/src/lib/DocumentRescan.svelte`, `web/src/lib/api.ts`,
`web/src/routes/+page.svelte`; the existing CollectionsPanel retry refresh may
preserve its mounted detail so an accepted retry keeps polling ownership.
SourceWorkspace visibility may be corrected for narrow Back/reopen; expose actual
pane visibility so the active progress owner can pause/resync. Retain
its request-owned busy and late-response protections. Create
`web/src/lib/DocumentRescan.test.ts` if component-level coverage is missing.

## Interfaces

PageReview owns a session-only marked ordinal set. Scan this page again emits
[currentOrdinal]; Scan selected pages emits the sorted marked set. Extend the
existing rescan dialog request with optional selection, passed unchanged to
previewRescan. Method changes regenerate a preview for the same set.

## Behavioral acceptance vectors

1. On page seven Scan this page again confirms page 7, one page to read, and
   one chosen available method; another page can be marked while navigating.
2. Changing Surya to a confirmed LLM keeps exact pages and refreshes cost and
   destination. Empty marked selection disables Scan selected pages.
3. Unchecked/unavailable profiles are not selectable. No automatic provider
   fallback occurs; the confirmation lists exact selected page numbers.
4. Uncertain start retains identical ID/body until resolved; a method/page edit
   cannot silently change an uncertain request. Definitive stale preview gets
   fresh confirmation. Cancelling before start sends nothing.
5. Document change/Back/history clears marks; returning restores list view but
   no hidden selection. Publication refreshes the reviewed reading and status.
6. One-page scan keeps the rest of the document and opens updated text beside
   the same page image; failure keeps the published comparison intact.
7. Closing/unmounting the rescan component while admission is pending does not
   resurrect status polling or terminal callbacks when the response arrives.
   Old jobs cannot update a later detail/page view; guard async continuations by
   component lifetime and request generation while preserving server work.

## Implementation steps

- [ ] Add failing behavioral tests for each numbered vector above. Record actual
  failure output before implementation; compilation failure alone is not evidence.
- [ ] Run the focused side's command below and confirm the intended failures.
- [ ] Implement the deliverable: Reuse existing start dialog and operation progress polling. Keep confirmation
outside scrolling content so it stays reachable. Label page numbers as one-based;
API ordinals stay zero-based. Do not add a range-input parser or persist marks.
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

Live review supports current-page scanning and session-only sorted marks. The
shared dialog confirms exact one-based pages, retains the zero-based set through
method changes, and freezes the identical request ID/body during uncertain start.
Unavailable profiles remain disabled. Completion offers an explicit exact-revision
refresh; the active revision is resolved independently of OCR operation history,
including after Restore. Failed/dropped publication lookup is not acknowledged as
delivered, so visible resync can retry.

Back clears marks while retaining page; narrow Back exposes context/reopen.
DocumentRescan guards admission/poll continuations by lifetime, visibility and
request identity. Hidden polling pauses, accepted Retry resumes on return, and a
single visible reader owner presents progress without a duplicate table poller.
No server cancellation/readmission is triggered by hiding.

Behavioral RED covered marks/dialog, deferred admission after unmount, hidden
progress, Back/reopen, same-document stale publication and Restore. Scoped Luna
reviews and their fixes closed the lifecycle/publication findings.
Final frontend tests passed 21 files / 421 tests, with zero Svelte errors/warnings
and successful static build. Final `./gradlew check` passed in 4m32s: 131 JVM
suites / 1,595 tests and 421 frontend tests, zero failures, errors or skips.
Initial Luna review and four scoped fix reviews closed all findings.

Integrated browser/viewport evidence remains in ticket 07; real providers,
CoreML and human accessibility are separate unverified gates.
