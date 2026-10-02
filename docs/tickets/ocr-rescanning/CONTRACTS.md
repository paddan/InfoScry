# Shared implementation contracts

Read the [spec](../../specs/2026-09-30-ocr-rescanning.md) and
[dependency/status index](STATUS.md). These are proposed new interfaces, not
claims about existing code. Implement them in ticket 01 unless an owner is named.
Keep HTTP adapters thin, use existing domain ID wrappers at service boundaries,
and serialize IDs as opaque strings. Reconcile concrete signatures with existing
transaction/coroutine conventions while preserving these semantics across consumers.

## Shared records

| Record | Required fields and semantics |
|---|---|
| `OcrSettingsSnapshot` | engine: TESSERACT/SURYA/LLM; mode: FILL_MISSING/CHECK_AND_IMPROVE; language; transcriptionProfileRevisionId nullable for local engines; reviewProfileRevisionId nullable; extractor/tool/model versions; renderDpi; transcriptionPromptVersion; reviewPromptVersion; policyVersion; autoValidationId nullable; externalPageLimit. Snapshot provider endpoint/model/limits or retain an immutable referenced profile revision. |
| `PageImage` | documentId, stable unitId, ordinal, image artifact reference/hash, dimensions, render version. The filesystem path is internal and validated within the managed artifact root. |
| `OcrPageResult` | text, optional boxes/confidence, unreadable spans, verified-blank indicator, engine/model provenance, artifact hashes, safe status/error code. Absence of confidence is not zero confidence; empty output alone cannot establish blankness. |
| `PageComparisonInput` | baseline revision/text nullable, candidate revision/text, PageImage, review profile revision and policy version. |
| `PageReview` | recommendation EXISTING_BETTER/NEW_BETTER/UNCERTAIN; structured bounded reasons and validated differing spans; publication disposition KEEP/PROPOSE/APPROVE; reviewer and policy fingerprints. Auto APPROVE requires an accepted validation record. |
| `DocumentRevision` | revisionId, documentId, parentRevisionId nullable, immutable selected page-text revision IDs, provenance, creation time. Existing unit IDs survive all versions. |
| `RescanPreview` | previewId, documentId, baseRevisionId, managed hash, settings snapshot, page total nullable, external-page upper bound nullable, named destinations, cost estimate nullable with basis, approvalRequired, expiry. |
| `OcrOperation` | operationId, jobId, documentId, baseRevisionId, candidateRevisionId nullable, snapshot, stage, committed page counts, pendingReviewCount, external distinct-page/call counters, safe error code. No original paths or raw provider errors. |

An external dispatch permit is an internal capability bound to operation,
profile revision and document/page identity, minted only by ticket 07's persisted
admission service. Ticket 05 accepts an injected permit validator; before 07 is
integrated, production external dispatch is unavailable. Capability probes send
only the explicit synthetic image. Fake tests cannot become a production bypass.

## Services and ownership

- Ticket 03: `OcrEngine.transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult`
  is suspendable/cancellable. The engine receives validated dispatch context from
  its configured client; it cannot pick a new endpoint or engine by itself.
- Ticket 05: `ImageLlmClient` owns image serialization, budgeting, bounded provider
  I/O and response validation for transcription and review. It does not publish.
- Ticket 06: `OcrComparisonService.compare(input: PageComparisonInput): PageReview`
  persists no changes to active page text; caller commits its result/checkpoint.
- Ticket 02: `RevisionPublicationService.publish(documentId, baseRevisionId,
  candidateRevisionId)` admits a durable operation and returns its opaque ID.
  Progress/errors are read from persisted state, not an HTTP connection lifetime.
- Ticket 09: `restore(documentId, expectedRevisionId, restoreRevisionId)` uses
  the same publisher, retaining provenance and creating a new revision.

Use opaque ID aliases in the shared models to keep signatures consistent.
Page-review saves check the expected active document revision and candidate hash
inside the transaction. The first manual decision may reference the pre-rescan
baseline; if initial mixed-result publication has occurred, rebase only when that
page's text hash is unchanged. Otherwise return a conflict for explicit review.
Do not leave every unresolved proposal unusable merely because another page was
published in the same document.

## Proposed API surface

All routes require existing credentials/CSRF rules. Resolve every nested ID under
its collection/document; cross-collection IDs return 404 without metadata leaks.
Validate before side effects. Invalid shape is 400; current-revision/lifecycle
conflict is 409; queued durable operations return 202. Repeated admission with
the same request ID and same body returns the same operation; different body with
the same ID conflicts. Bodies never accept arbitrary filesystem paths or keys.

| Method/path | Body or response contract |
|---|---|
| GET/POST `/api/ocr/profiles` | List/create separate OCR profiles with safe key-presence status. |
| PATCH/DELETE `/api/ocr/profiles/{profileId}` | Create a new immutable revision or disable new use; preserve revisions referenced by jobs/history. |
| POST `/api/ocr/profiles/{profileId}/probe` | Explicit synthetic-image capability check; never uses a user's document. |
| Existing collection settings PATCH | Add validated snapshot source fields from above; old clients keep Tesseract/language behavior. |
| POST `/api/collections/{id}/documents/{documentId}/ocr/preview` | Optional engine/profile overrides; return RescanPreview. No document payload is transmitted. |
| POST `/api/collections/{id}/documents/{documentId}/ocr/rescan` | previewId, requestId; revalidate baseline/settings/hash and admit OcrOperation or request renewed preview. |
| GET `/api/collections/{id}/documents/{documentId}/ocr/operations/{operationId}` | Durable stage/counters/approval/review state. |
| POST `.../ocr/operations/{operationId}/approve-external` | expected snapshot hash and maximum authorized distinct pages; reject stale scope. |
| POST `.../ocr/operations/{operationId}/cancel` | Durable cancellation request; publication authority commit is the point beyond which cancellation cannot undo. |
| POST `.../ocr/operations/{operationId}/resume` | Same immutable snapshot and checkpoints; incompatible engine change requires a new preview/attempt. |
| GET `.../ocr/reviews` | Bounded page of proposals, baseline/candidate revision and hashes, safe reasons and opaque image URLs. |
| POST `.../ocr/review-decisions` | requestId, expectedRevisionId, explicit page choices with candidate hashes; KEEP/USE_NEW/EDIT, text only for EDIT. Persist a bounded batch; explicit document-wide choices resolve the current server-side pending set and guard its revision. |
| POST `.../ocr/publish-decisions` | expectedRevisionId and decision batch ID; admit chunk/embed/index publication. |
| GET `.../ocr/revisions` | Paginated published history with provenance and active revision ID. |
| POST `.../ocr/restore` | requestId, expectedRevisionId, restoreRevisionId; return publication operation. |

The shortened `...` always denotes the same collection/document prefix, never
an unscoped global document route. Reuse the existing import job control surface
for import-level external approval/cancel/resume with the same snapshot/page
allowance semantics. Single rescan endpoints must not become the only way to
approve a multi-file import. Define corresponding CLI remedies with the API in
07; standalone CLI ownership remains in place while work is active.

## Job and publication states

Use a distinct RESCAN job type rather than broadening RETRY. Operation stages
include PREFLIGHT, AWAITING_APPROVAL, OCR, REVIEW, CHUNKING, EMBEDDING, INDEXING,
COMPLETE, FAILED, CANCELLED and NEEDS_TOOL. Review backlog is orthogonal to
operation completion: COMPLETE with pending pages is displayed `Needs review`.
A published document's existing COMPLETE status is not erased while rescanning;
management joins the active operation and review counts. Existing readers keep
using the active revision. An initial document with no approved text must expose
pending-review availability rather than COMPLETE/searchable. Update DTO enums,
storage constraints, status filters and UI together wherever a new state is needed.

PREPARED and PUBLISHED are durable publication phases, separate from the job's
processing label. An authority-committed operation must finish recovery, not
report failure and falsely imply that the old revision remains authoritative.
Prevent network/embedding work while holding an exclusive publication boundary.

## Test vectors required in addition to focused ticket scenarios

These declarative cases are acceptance inputs for tests, not production code:

```json
[
  {"case":"pilot_new_better","baseline":"name 123","candidate":"name 128","recommendation":"NEW_BETTER","validated":false,"expected":"PROPOSE"},
  {"case":"no_baseline_uncertain","baseline":null,"candidate":"name?","recommendation":"UNCERTAIN","expectedSearchable":false},
  {"case":"review_timeout","baseline":"existing","candidate":"new","reviewError":"TIMEOUT","expectedText":"existing"},
  {"case":"external_limit","limit":1,"sentPages":["docA:1"],"nextPage":"docB:1","expected":"AWAITING_APPROVAL"},
  {"case":"same_page_two_stages","ocrPage":"docA:1","reviewPage":"docA:1","expectedDistinctPages":1,"expectedCalls":2},
  {"case":"stale_manual_edit","expectedRevision":"r1","activeRevision":"r2","expectedHttpStatus":409}
]
```

Ticket 06 owns the first three cases, 07 owns page accounting and 08 owns stale
edit conflicts. Ticket 02's recovery harness must inject faults on both sides of
every DB/index/reader handoff, using a real temporary SQLite/Lucene instance.

## Accumulated gates and stop conditions

Every implementation slice adds its focused failing test first, observes the
behavioral failure, implements only that slice, then runs its focused tests green
and the accumulated normal gate:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew check
```

UI changes additionally run these from `web/`:

```sh
npm test -- --run
npm run check
npm run build
```

Run relevant `externalTest` browser/tool acceptance as each affected scenario
lands. Ticket 10 runs all applicable browser/external and real CoreML gates.
Do not classify a still-running timed fixture as a hang before its configured
timeout. A dependency/runtime/test infrastructure failure is not evidence of
application correctness. Keep those checks explicitly unverified until runnable.

Stop dependent implementation when ticket 02 cannot prove coherent publication,
when Surya's real Mac runtime fails, or when image transport cannot pass its
capability gate. Preserve manual Tesseract behavior; never hide a missing gate
behind fallback. Ticket 11 cannot enable auto mode without accepted measured
thresholds and held-out evidence. No private documents go to external evaluation
without explicit permission for that experiment.

Documentation-only changes require local Markdown link/anchor checks, fence
balance, spec-to-ticket consistency and `git diff --check`, not app suites.
