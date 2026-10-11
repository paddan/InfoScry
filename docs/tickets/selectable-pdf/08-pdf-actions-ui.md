# 08 — Compact mark/copy PDF actions

**Status:** Not started — documentation only, 2026-10-10.
**Blocked by:** 02 wire contract frozen; 07 for integrated verification.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Build the compact shared controls against the frozen wire contract using fakes;
integrate the real routes after 07. Maps to frontend plan Task 5.

**Own:** `web/src/lib/SelectablePdfActions.svelte`, its test,
`web/src/lib/api.ts`, `api.test.ts`, `PageReview.svelte`, `PageReview.test.ts`,
`web/src/routes/+page.svelte` and corresponding focused page tests only where
cited sources bypass PageReview. Preserve prior uncommitted UI changes.
**Consume:** exact DTOs/routes/client signatures in CONTRACTS.md.
No backend or shared e2e fixture edits. 09 owns browser harness modifications.

## Acceptance

1. Shared controls beside Open original in Collections and cited Ask/Investigate
   views. Original remains reachable in every state; no new column, PDF search
   control or change to Lucene/search behavior.
2. NOT_CREATED offers Create; QUEUED/BUILDING report progress; READY opens/downloads;
   PARTIAL shows selectable/blank/unavailable counts and page reasons before
   download; FAILED offers Retry with a safe reason. English copy per contracts.
3. POST creates/retries, encoded file GET opens/downloads. Rendering/opening itself
   cannot dispatch generation or a provider call. Blank READY 0/N is explained.
4. Identity is collection/document/revision; historic citations retain their own
   revision. Null/unknown revision cannot dispatch. Ignore delayed A after B;
   cancel timers/requests on identity change, unmount or hidden view.
5. Poll only QUEUED/BUILDING, stopping on terminal state. Repeated clicks converge
   through backend idempotence; errors remain retryable. Keep disabled/focus state
   understandable and do not lose the source opener or page selection.
6. API decoding handles omitted nullable key/total/reason fields and statuses
   without undefined-driven UI bugs. Keyboard labels/focus and narrow/wide wrapping
   are covered; no hidden long page-reason overflow.

## Steps and checks

- [ ] Write failing API decoder, action-state and delayed identity tests first.
- [ ] Implement typed client functions and a single shared component with fake routes.
- [ ] Integrate into live/historical PageReview and any bypass source path.
- [ ] Run `cd web && npm test -- --run && npm run check`.
- [ ] After 07 passes, verify actual response shapes and return integration evidence;
  contract-only unit success does not complete this ticket before real route wiring.

Coordinator schedules ./gradlew check after real integration. Do not overwrite
07's routes or 06's DTO ownership to make a fake client pass.

## Completion evidence

Not run. Return owned diff, behavioral RED/GREEN commands/results, acceptance
vector outcomes, exact exported interfaces and remaining limits. Checkboxes are
future work, not proof. Coordinator review is required before unlocking dependents.
