# 07: Rescan jobs, import modes and external approval

**Status:** Partially implemented (2026-10-02). The **rescan path** is verified: a `RESCAN` job type and
operation record (migration 023), preview and admission with request-id idempotency, per-page checkpoints and
bounded cancellation, external-page accounting with distinct pages counted once and calls separately, the
`AWAITING_APPROVAL` state, comparison and review wiring, decisions and publication through ticket 02's
service, operation ownership that holds a document while an operation is unfinished **or awaiting review**
(so a second rescan cannot race the first), and a review-pending outcome for imports (migration 024). My own
`./gradlew check` was green on the repaired tree (106 suites, 1340 tests, 0 failures, 16m27s) and the focused
suites (`RescanJobHandlerTest` 11, `OcrRoutesTest` 13, `ImportJobHandlerTest` 28, `RetryJobHandlerTest` 10)
are green.

Three items were repaired by hand after the implementing run timed out mid-edit: a claim check that called a
helper returning `Unit` (the operation store did not compile), two schema-version literals still at 23 after
migration 024, and a managed-copy test that predated the new ownership rule (an operation with unresolved
reviews holds its document, so the test now reaches its subject — the managed copy — without leaving a review
backlog).

**Outstanding, owned by [ticket 07b](07b-import-execution-and-admission.md)**, and the reason this
ticket is not recorded as done: the import half does not yet execute what it admits (review and job-level
external approval do not reach the import worker, and a new import with unapproved text is not yet
review-pending in execution); admission does not revalidate the whole effective settings snapshot; and resume
or approval can still start a second attempt for one operation. The CONTRACTS vectors `external_limit` and
`same_page_two_stages` are not yet meaningfully exercised, and the route tests do not cover concurrent
requests.
**Blocked by:** 04, 05, 06.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

An existing completed document can be rescanned and a new import can use either mode, with durable external approval and unchanged Retry semantics.

## Files and interfaces

- `src/main/kotlin/infoscry/document/RescanService.kt (new)`
- `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt (new)`
- `src/main/kotlin/infoscry/jobs/ImportJobPayload.kt`
- `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt`
- `src/main/kotlin/infoscry/jobs/DocumentIngest.kt`
- `src/main/kotlin/infoscry/server/OcrRoutes.kt (new)`
- `src/main/kotlin/infoscry/storage/JobStore.kt`
- `src/main/kotlin/infoscry/AppContext.kt`
- `src/main/resources/db/migration/ (new forward migration)`

Consume engine, comparison and publication services. Expose the scoped preview/admission/approval/resume APIs in CONTRACTS.md; issue external dispatch permits only within persisted authorization.

**Tests:** Create src/test/kotlin/infoscry/jobs/RescanJobHandlerTest.kt and src/test/kotlin/infoscry/server/OcrRoutesTest.kt; extend ImportJobHandlerTest, RetryJobHandlerTest and deletion recovery tests.

## Test-first implementation

- [x] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Double submit, collection deletion during OCR and crash after approval do not duplicate work, transmit unapproved pages or resurrect a document.
  - Page allowance covers two files in one import and persists after restart; transcription plus review counts one distinct page and two calls.
  - No network transmission before approval, including review of local Surya output through an external reviewer.
  - Original source moved/deleted: rescan reads managed copy; checksum failure reports safely.
  - Publication failure, absent CoreML and cancellation leave old search/source content intact; a no-text new import remains review-pending.
- [x] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [x] Snapshot job settings, managed hash, baseline revision and idempotency key under shared mutation admission. Validate required engine/tool prerequisites without sending document content. — **Partial:** the rescan path does this; the import worker does not yet execute the snapshot (see 07b).
- [x] Persist external distinct-page accounting across all files and both stages. Above allowance, enter AWAITING_APPROVAL before dispatch. Approval binds profile revisions and scope, never a mutable future endpoint. — **Partial:** implemented on the rescan path; the import job-owned path is 07b.
- [ ] Conservatively preview all potentially external pages; unknown page totals pause before exceeding scope. Count provider calls and retries separately; missing image prices read unavailable. — *Vectors not yet exercised* (`external_limit`, `same_page_two_stages`): 07b.
- [x] Commit OCR and review independently per page; bounded cancellation and restart reuse only verified compatible results. No automatic engine fallback and no duplicated admission. — **Partial:** resume/approval can still start a second attempt for one operation: 07b.
- [x] Complete through revision publication. Distinguish review-pending from engine failure and publication-in-progress. Failed/cancelled rescan leaves baseline active. CLI imports retain ownership and surface approval requirements without silently returning successful enqueue. — **Partial:** the CLI ownership/approval surfacing is 07b.
- [x] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [x] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.jobs.*' --tests 'infoscry.server.OcrRoutesTest' --tests 'infoscry.document.*'
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.

## Continuation ownership (2026-10-02)

The implemented rescan backend is retained. The import baseline is [07b](07b-import-execution-and-admission.md), whose remaining repair gate is split into [07c](07c-durable-import-candidates.md), [07d](07d-retry-ocr-execution.md), [07e](07e-non-image-import-compatibility.md) and [07f](07f-external-review-accounting.md). Execute those leaf tickets rather than repeating this ticket.
