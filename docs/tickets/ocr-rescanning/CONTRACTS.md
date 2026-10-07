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
| `PageComparisonInput` | baseline revision/text nullable, candidate revision/text, PageImage, review profile revision and policy version. A baseline is one of three shapes: absent; a published revision with its text and hash; or a **text-only reading the caller holds** (an import's own page text, which the archive has not published) — the third has no revision and no hash, so such a review is identified by candidate, image, reviewer and policy. |
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

## Continuation boundaries (2026-10-02)

Use the leaf execution tickets in STATUS.md. Preserve implemented code and repair demonstrable gaps; umbrella tickets are coordination records. Each leaf handoff records final DTO/service signatures here before dependent work consumes them. Existing review/decision and revision-list routes are a starting point, not proof that imports and restoration are supported.

- 07c owns durable `(job, document, immutable attempt settings) → candidate` binding and successful page checkpoints. Resume must load them from SQLite, including a staged page whose approval write was interrupted; an in-memory set is insufficient. Transcription checkpoints remain independent of review and embedding.
- 07d owns retry dispatch/reviewer wiring using the same durable ownership and allowance semantics. 07e limits candidate staging to supported page-image extractors; non-image imports retain ordinary extraction.
- 08d owns review-read DTOs: candidate/owner identity, document/ordinal/stable-unit mapping, baseline and candidate text/hashes, bounded reasons and opaque image links. A proposal without an LLM review row still appears. Keep existing for an import must have an actual recoverable direct-text baseline; missing is not empty or candidate text.
- 08e owns manual-decision/publication DTOs for an initial import with no active revision and for an already partially published import. Decide against immutable candidate hash and expected active revision (including explicitly absent); later decisions create a new revision rather than editing a published one. Preserve request-id idempotency and page-hash-aware rebase.
- 03b permits entirely absent provenance and optional dimensions as a pair; partially present core provenance is invalid. 03c retains images referenced by candidates/history; no new garbage collector is needed to close the lifetime bug.
- 02d reuses the existing `evidence_ledger.excerpt` column. Add revision provenance and remove dependence on the continued existence of a live unit when returning saved history.

### 07c: Durable staged-import candidate resumption

**Migrations:** 025_candidate_resume_binding.sql adds nullable `document_revisions.attempt_fingerprint` and `page_text_revisions.unit_key` (old rows NULL, never resumed). Migration 026_candidate_resume_uniqueness.sql adds partial unique index `document_revisions_resume_uniqueness` on (document_id, attempt_fingerprint) WHERE state='CANDIDATE' AND attempt_fingerprint IS NOT NULL. SchemaMigrator SUPPORTED_VERSION is now 26.

**Interfaces:**
- `DocumentRevisionStore.openCandidate(..., attemptFingerprint)`: adopts the existing candidate on unique violation; new `resumableCandidate(documentId, fingerprint)` loads newest CANDIDATE row for that fingerprint; new `stagedKeys(revisionId)` lists unit keys staged in that candidate.
- `RevisionPageDraft` has new field `unitKey`.
- `CandidateRevisionSink.committedKeys`: adopts resumable candidate and returns its staged keys (so staged pages skip repeat OCR); never reuses WITHDRAWN candidate or one from different snapshot/fingerprint.
- `ExtractionSink` default method `deliver(documentId, fingerprint, event, approval: PageApproval?)`: writes page and its approval decision in ONE transaction (closes crash window between staging and decision recording).

**Test coverage:** ImportJobHandlerTest covers five scenarios: kill/resume reuses candidate/page (no repeat OCR); pause/resume without resetting counters; interruption after staging completes page decision on resume; changed snapshot creates distinct attempt; cancellation/failure leaves recoverable CANDIDATE, publishes nothing. Unique-index adoption under true concurrency tested at store level in CandidateResumeUniquenessTest.

### 07d: Retry uses admitted OCR and review authority

**Migrations:** None required — the retry's snapshot travels in the existing `jobs.payload` JSON (`RetryJobPayload` is serialized there), and no schema, counter or approval surface changes; the persisted job-owned external scope (tickets 07/07b) is reused as is. SchemaMigrator SUPPORTED_VERSION stays 26.

**Interfaces:**
- `RetryJobPayload.ocr: OcrSettingsSnapshot? = null`: the snapshotted OCR selection the retry was admitted with, mirroring `ImportJobPayload.ocr` (engines, profile revisions, external-page allowance). Null is a legacy payload: Tesseract, fill-missing, no external pages, no reviewer.
- `RetryPrerequisites.ocr: OcrSettingsSnapshot? = null`: `RetryPrerequisites.probe` now captures the resolved snapshot instead of folding it into the settings and discarding it, and `RetryService.admit` writes `ocr = reconciled.ocr` into the payload (reconciliation inside the mutation permit still decides which snapshot is enqueued).
- `AttemptDispatch` (new internal helper, `src/main/kotlin/infoscry/jobs/AttemptDispatch.kt`), built once in `ImportJobHandler.attachTo` and shared by the import and retry handlers: `authorityFor(job, document, snapshot, dispatchStage)` builds the job-owned `OcrDispatchAuthority` for one stage (external revisions only; local dispatches count nothing), and `stagedPageReviewOf(job, document, collection, snapshot)` builds the `StagedPageReview` with the review-stage authority and the snapshot's prompt/policy versions. The import's former private `dispatchAuthorityFor`/`stagedPageReviewOf` are deleted; its constructor's `operations`, `profileRevisionOf`, `paths` and `reviewerFor` fields are replaced by this one `attemptDispatch` field (`jobs` stays).
- `AttemptAwaitingApproval(jobId)` replaces and absorbs `ImportAwaitingApproval` (same package, same control-flow role; `DocumentIngest.ingest`'s catch clause follows the rename). Its `onExhausted` is what both handlers' waiting state routes through.
- `RetryJobHandler` gains `attemptDispatch`; `retryOne(job, collection, settings, snapshot, document, stage)` passes `dispatch = attemptDispatch.authorityFor(... TRANSCRIPTION)` and `review = attemptDispatch.stagedPageReviewOf(...)` to `DocumentIngest.ingest` (alongside the unchanged `revisitFailedUnits = true`/`onFailure`), and `handle` catches `AttemptAwaitingApproval` to write `JobStore.AWAITING_APPROVAL_STAGE` durably and return, mirroring `ImportJobHandler.handle`. Retry and import therefore share one allowance per job and one reviewer wiring; no production call site passes a fake.

**Test coverage:** RetryJobHandlerTest adds six tests pinning the ticket's four scenarios: a differing check-and-improve retry calls the scripted reviewer and leaves a `PROPOSE` (pilot) pending review (identical nonblank text records no review and sends nothing); external transcription and review of one page count one distinct page and two calls against the job's scope (external reviewer profile, not loopback); allowance 0 waits at `AWAITING_APPROVAL_STAGE` before any dispatch rather than failing; resume after approval continues the same account's counters and decodes the original `ocr`/`settings` from the payload; and `RetryService.ELIGIBLE_STATUSES` still excludes `COMPLETE`, `COMPLETE_WITH_WARNINGS` and `NEEDS_REVIEW`, with `an explicit retry revisits the unit a crash resume would skip` kept green. Red first: both wiring tests failed behaviorally (no review row; `extractor.sent == 0`) against a compiling suite before the implementation.

### 07f: Injected recording transport for external dispatch

**Migrations:** None — no schema, counter, approval or DTO change; SchemaMigrator SUPPORTED_VERSION stays 26.

**Interfaces:**
- `ImageLlmClient(profile, lookup, permits, calls, timeout, maxImageBytes, maxResponseBytes, retryPolicy, engine: HttpClientEngine? = null)`: the optional `engine` is the transport seam. Null (production) keeps the client's own CIO engine, owned and closed by the client; a non-null engine is wrapped in the `HttpClient` this client builds itself with `followRedirects = false` and `expectSuccess = false`, so an injected transport cannot turn redirects on or make a status pass as success. Permit, credential, budgeting, retry and call-counting behavior is identical for any transport, and the client never closes an injected engine (one recording engine serves every per-page client a run builds).
- Pass-through `clientEngine: HttpClientEngine? = null` on `LlmOcr`, `OcrComparisonService`, `ocrReviewerFactory(...)`, `rescanEngineFactory(...)` and `ImportJobHandler.attachTo`/pipeline construction: each defaults to null, so every production call site is unchanged in behavior; a test injects once at `attachTo` and both transcription and review dispatch through it.
- `OcrDispatchAuthority` and `OcrOperationStore` (`allowanceFor`, `authorizePage`, `recordCall`) are unchanged; 07f's tests confirmed the existing guards with revert proofs rather than requiring a fix.
- Test-only (`src/test`, `internal`): `RecordingImageLlmEngine(script)` — a scripted `HttpClientEngineBase` that records `RecordedImageRequest(method, url, headers, body)` instead of opening a socket; `RecordedImageResponse(statusCode, body, headers)` where the last script entry repeats. It cannot weaken the client's rules because the client owns the `HttpClient` around it.

**Test coverage:** ImageLlmClientTest pins a permitted external dispatch arriving at the classified endpoint on the recorder (and a refused one never reaching it), bounded retries as one permit plus one counted call per attempt, and no endpoint/key/page text in any refusal error; ImportJobHandlerTest pins transcription + review = 1 distinct page / 2 calls with the next document pausing before the transport, wrong profile/document/stale-scope refusal under a live approval, and resume continuing counters; RetryJobHandlerTest pins retry's 1 page / 2 calls, allowance-0 wait with zero recorded requests, resumed counters, and a 429 retry as a third call on the same page. All four scenarios were proven to fail under temporary reverts (see the 07f record in STATUS.md).

### 07e: Sink selection follows page-image support

**Migrations:** None — no schema, counter, approval or DTO change; SchemaMigrator SUPPORTED_VERSION stays 26.

**Interfaces:**
- `ImportPipeline.sinkFor(documentId, mode, pageImageSupport)`: the third parameter is the *selected*
  extractor's `PageImageSupport` (DocumentIngest passes `extractor.pageImageSupport` for the document's own
  media type). The candidate sink is opened only when the mode is `CHECK_AND_IMPROVE` **and** the support is
  `PageImageSupport.Supported`; every other combination returns the ordinary `sink`. A format without page
  images therefore keeps its ordinary searchable extraction (units, chunks, index rows, `COMPLETE`) under
  Check and improve, and no page-image rebuild and no image-provider request can be made for it, because the
  staged-page review only exists for a sink that stages.
- `DocumentExtractor.pageImageSupport` is unchanged as a contract (default `Unsupported(PAGE_IMAGES_UNSUPPORTED)`;
  only `PdfExtractor` and `ImageExtractor` report `Supported`) and its KDoc now names the import consumer.
  The explicit rescan refusal (`RescanRefusalException.PAGE_IMAGES_UNSUPPORTED` / `RescanJobHandler`'s
  preflight) is untouched and still the answer a rescan of an unsupported format gets.

**Test coverage:** ImportJobHandlerTest pins the three behavioral modes: plain text and the committed
`sample.docx` fixture under an admitted check-and-improve snapshot publish ordinary text/chunks/index rows
with the candidate sink never opened (red first: `expected: <COMPLETE> but was: <NEEDS_REVIEW>`); an
extractor that reports no page images is routed to the ordinary sink even when its draft carries a baseline
and an image reference (red first: `expected: <0> but was: <1>` reviewer requests); and a supported page with
no direct baseline still stages `PENDING`, publishes nothing and writes no review row. ExtractorRegistryTest
classifies every format the production registry claims (Supported exactly for PDF/pictures). RescanJobHandlerTest
pins the existing refusal for a `text/plain` document. Red evidence and green counts are in the 07e record in STATUS.md.

### 03c: Retained image references remain usable

**Migrations:** None — no schema, counter, approval or DTO change; SchemaMigrator SUPPORTED_VERSION stays 027.

**Interfaces and lifetime rule:**
- `ContentUnitDraft.sourceImage` (PdfExtractor) is non-null exactly when the named file is kept for the
  record's life: a check-and-improve page render under `{fingerprint}/pages`, a managed original, or a
  bounded derived copy under the attempt's artifacts. A fill-missing working render — deleted when its
  reading commits and whose directory is deleted when the attempt ends, including on cancellation or
  failure — records no reference at all; the unit's durable evidence there is its word-box artifact
  (`artifactRelativePath`/`artifactSha256`), which is written beside the attempt's artifacts and survives.
- Images referenced by staged candidates and by published history are retained on disk until normal
  document/collection deletion owns cleanup; no artifact garbage collector exists or was added, and no
  deletion path changed. A restart adopts the staged candidate through `CandidateRevisionSink`
  `committedKeys` and skips its pages before rendering, so nothing rewrites what those pages name.
- `ImageExtractor` and `CandidateRevisionSink` needed no change (byte-identical); the managed-original and
  bounded-derived roots are unchanged and pinned by `ImageExtractorTest` plus the existing contract tests.

**Test coverage:** PdfExtractorTest pins scenario 1 red-first (dangling working reference, then the
deliberate absence of one); OcrEngineContractTest pins cancellation/restart/competing-attempt retention;
ImageExtractorTest pins the distinct managed/artifact roots. Red messages and gate counts are in the 03c
record in STATUS.md. Residual flagged there by inspection: the rescan path's shared
`documentArtifactRoot/rescan/pages` directory (08d/09c question, untouched here).

### 02c: Saved Ask excerpt fallback

**Migrations:** None — no schema, DTO, HTTP or backend change; SchemaMigrator SUPPORTED_VERSION stays 027.

**Interfaces (client-side, `web/`):**
- `openAskEvidence(evidence: AskEvidence)` in `web/src/routes/+page.svelte` replaces the AskPanel
  inline `onOpenSource` lambda. It builds the hit once, then routes: a recorded revision keeps the
  existing `readSource(..., revisionId)` path; a citation with no revision **and** a saved excerpt
  goes to `openRevisionUnknownExcerpt(hit, excerpt)`, which performs no request at all and shows the
  excerpt under the source sheet's English label `Revision unknown — showing the excerpt saved with
  this answer, not the document's current text.`; a live citation (no saved excerpt) keeps reading
  the live unit, because that unit is the reading it just came from.
- `prepareSourceSheet(hit, opener)` is the shared preamble of both paths: it bumps the source
  generation, adopts the hit and clears the previous view (including any fallback excerpt), so a
  late response for a previously opened source — or a pending read after the reader picks another
  citation or collection — is dropped rather than shown.
- No new wire fields or signatures: `AskEvidence.revisionId`/`.excerpt` are consumed as already served
  by `loadAskEvidence` (`citations.snippet` is `NOT NULL`, and a recorded-but-deleted revision is
  already reported as absent via the history route's LEFT JOIN), and `readSource` is unchanged.
  `openInvestigationSource` is untouched — Investigate provenance is ticket 02d's slice.

**Test coverage:** `web/src/routes/page.test.ts` pins the ticket's three scenarios (red first: four
failures recorded in the 02c record in STATUS.md) plus the modal focus contract for the fallback path
(a fifth test, red first in its own cycle): unknown revision → no `/sources/` request, excerpt
and label shown even when the source route answers 404; known revision passed to `readSource` and kept
across `Load more` (green pin before the fix); excerpt rendered literally; a first citation's late
response dropped after the reader opens a second citation; and focus entering the sheet when the
excerpt opens it. UI gates: `npm test -- --run` (219 passed), `npm run check`, `npm run build`, plus
the final-tree `./gradlew check` — counts in STATUS.md.

### 02d: Investigate evidence revision provenance

- Migration 028: `evidence_ledger` gains one nullable `revision_id` column beside `excerpt` (legacy rows stay NULL — the honest “revision unknown”). `SchemaMigrator.SUPPORTED_VERSION` = 28.
- `LlmStore.persistEvidenceLedgerEntry(conversationId, evidenceId, sourceUnitId, locatorJson, excerpt, messageSeq: Int? = null, revisionId: String? = null)`; `loadHistory` returns `EvidenceLedgerSnapshot.revisionId: String? = null`.
- `ToolEvidence.revisionId: String? = null` — search evidence carries `hit.revisionId`, read evidence the document's active revision at read time via the new `fun interface InvestigationRevisions`; `InvestigationTools(collectionId, search, content, documents, revisions, maxResultBytes)`.
- Reopened-conversation read serves each entry from its own row (`excerpt`, `revision_id`) and places the document via `COALESCE(live unit, recorded revision, the revision that once held the unit)` with an INNER JOIN on `documents` (deletion) and `d.collection_id` scoping. History `EvidenceWire` now returns `excerpt` + `revisionId`; the `done` wire returns `revisionId`.
- Web: `openInvestigationSource` routes like saved Ask citations — recorded revision opens that reading, unknown provenance shows the saved excerpt under the revision-unknown label without any request.
### 02e: Publication seal failure and recovery

**Migrations:** None — no schema, DTO or counter change; SchemaMigrator SUPPORTED_VERSION stays 027.

**Interfaces:**
- `RevisionSnapshotGate.requireUnsealed()` (`src/main/kotlin/infoscry/search/RevisionSnapshotGate.kt`):
  throws the existing `RevisionSnapshotUnavailableException` while the seal is raised, for reads that do
  not take a lease. `acquire()` already refuses search (and any lease-taking reader) under the seal;
  this is the same refusal for a reader of live SQLite text.
- The live source read in `SourceRoutes` (`GET /api/collections/{id}/sources/{sourceId}` with no
  `revision` query parameter) calls `requireUnsealed()` before answering, so it maps to the existing
  503 `REVISION_SNAPSHOT_UNAVAILABLE` ("the archive is finishing a publication; retry once it has
  completed") through `ApplicationCall.handle`. A source read that names a revision is unchanged: its
  text is that revision's immutable text, which the spec lets finish against its own revision, and it
  is served even while the seal is raised.
- No change to `RevisionPublicationService.publish(..., observe: (PublicationStep) -> Unit)`: the
  ticket's deterministic counter hook is the existing `observe` seam counted per step — the first
  `STAGED_COMMITTED` is the publication's, the second belongs to the inline roll-forward that runs
  because the authority moved.

**Test coverage:** RevisionPublicationRecoveryTest pins the three scenarios with that counter hook
(authority-commit failure plus roll-forward staged-commit failure observed exactly once each, leaving a
PREPARED intent with durable authority; lease and search refusal while SQLite already serves the
target's text under a scope that still hides it; a restart completing the publication before serving
and search/source resuming on the authoritative revision). SearchRoutesTest pins the wire boundary:
503 `REVISION_SNAPSHOT_UNAVAILABLE` for both search and the live source while the publication cannot
switch, the named-revision read still answering, and both reopening after `recoverUnfinished()`. Red
evidence and gate counts are in the 02e record in STATUS.md.

### Known publication wording conflict

The spec, “Publication and history”, says a whole rescan revision may combine approved new text with retained baseline text on uncertain pages. The implementation (`RevisionPublicationService.refusalFor`, `CandidateRevisionPhases.publishCandidate`) and the recorded owner decision in 07b instead refuse a rescan with any pending page. Initial imports may publish approved pages while others remain pending. This plan preserves current behavior and does not silently reinterpret either contract. Before changing the rescan publication rule, reconcile that exact spec/recorded-decision conflict with the owner and update spec, publisher, tests and consumers together. It does not block checkpoint/provenance repairs, UI profile management or proof of the existing publication rule.

### Gate discipline

Run one focused regression cycle per independent behavior, then the applicable accumulated gate once on the final slice tree. Do not repeatedly run a passing full gate without new changes, failures or unresolved concerns. Every code slice still requires `./gradlew check`; browser and hardware tests remain separate. Documentation-only planning uses link/fence/dependency/diff checks. Use actual commands/results and dates; preserve historical evidence without copying it as current proof.
