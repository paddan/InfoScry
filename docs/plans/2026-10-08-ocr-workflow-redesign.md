# OCR workflow redesign — implementation plan

> **Tickets:** this plan is split into self-contained tickets in [docs/tickets/ocr-workflow-redesign/](../tickets/ocr-workflow-redesign/STATUS.md) (shared interfaces in [CONTRACTS.md](../tickets/ocr-workflow-redesign/CONTRACTS.md)). Implement from the tickets.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One start dialog for import and rescan that confirms the reading method and the pages to read, reads every page, publishes on completion, and can always be started again.

**Architecture:** Phase 1 makes every stopped run restartable and cleans stale state (useful alone). Phase 2 replaces the engine/profile/mode/reviewer/allowance settings with one reading method chosen at start, whose preview fixes the page set that is confirmed. Phase 3 rebuilds the web flow around one dialog and a per-document status. Phase 4 deletes the old review and approval code. Each task leaves `./gradlew check` green.

**Tech Stack:** Kotlin/JVM 25, Ktor, SQLite; SvelteKit/TypeScript, Vitest, Playwright (`externalTest`).

**Spec:** [docs/specs/2026-10-08-ocr-workflow-redesign.md](../specs/2026-10-08-ocr-workflow-redesign.md) (approved 2026-10-08). Read it and `AGENTS.md` first.

## Global Constraints

- Idempotence everywhere: every new route, job step, migration and recovery step is run twice and interrupted after each durable step in its tests; the end state equals one uninterrupted run.
- Starting a run never depends on the state a previous run left; no "waits for approval", "pending review" or "cannot start yet" may be returned to a start.
- Single-user: no refusals or waits that protect other readers. Keep checkpoints per page, immutable originals/managed copies, stable unit ids, atomic index publication, publication/deletion recovery, shared mutation admission.
- Bind to `127.0.0.1`; bearer/CSRF as today. API keys only from environment variables; keys, questions, document text and authorization headers stay out of logs and errors.
- Product copy in English, UI restrained and accessible.
- Normal tests: redistributable fixtures, temp data dirs, fake providers. Run backend `./gradlew test -PskipFrontend --tests <class>` and frontend `cd web && npm test -- --run && npm run check` while iterating; `./gradlew check` before each task's commit.
- Engineering defaults (not in the spec; change them here if you disagree): no schema migration script — removed collection columns stay in `001_baseline.sql` unread and unwritten, and stale-state cleanup is an idempotent startup routine; a run's confirmed page total becomes the snapshot's `externalPageLimit`, so no new allowance concept is added.

## Review Focus

1. A directory import where a file's page count cannot be read: dialog says `at least N`, approval covers the listed files only, the unreadable file is reported not silently read.
2. Process killed mid-run at each stage (reading, chunking, embedding, publishing): restart succeeds with no prior action and the old text is intact.
3. Double-click on the confirm button and a repeated `POST` with the same request id: exactly one run.
4. A database written by the shipped flow (job `COMPLETE`+`awaiting-approval`, operation with pending candidate, collection with engine/profile pair set): opens, is cleaned, and starting works.
5. Files or profile changed between preview and start: refused with "preview again", never a different page set than confirmed.

---

## Phase 1 — Always restartable

### Task 1: Idempotent startup cleanup of stale OCR state

**Files:**
- Create: `src/main/kotlin/infoscry/ocr/StaleOcrStateCleanup.kt`
- Modify: `src/main/kotlin/infoscry/AppContext.kt` (~line 583–610, run before `jobs.resetInterrupted`)
- Modify: `src/main/kotlin/infoscry/storage/JobStore.kt`, `src/main/kotlin/infoscry/storage/OcrOperationStore.kt`
- Test: `src/test/kotlin/infoscry/ocr/StaleOcrStateCleanupTest.kt`

**Interfaces:**
- Produces: `class StaleOcrStateCleanup(jobs: JobStore, operations: OcrOperationStore, revisions: DocumentRevisionStore) { fun run(): CleanupReport }`; `data class CleanupReport(val jobsCancelled: Int, val operationsFailed: Int, val candidatesWithdrawn: Int)`.

- [ ] **Step 1: Write failing tests** in `StaleOcrStateCleanupTest`: `a paused import job becomes cancelled`, `an operation left in a working stage becomes failed with code INTERRUPTED and releases its document`, `a complete operation with a pending candidate has the candidate withdrawn and no longer holds the document`, `running it twice reports zero changes the second time`, `a live running job is untouched`. Build states with the stores directly.
- [ ] **Step 2:** Run `./gradlew test -PskipFrontend --tests 'infoscry.ocr.StaleOcrStateCleanupTest'`; expect FAIL (class missing).
- [ ] **Step 3: Implement** `StaleOcrStateCleanup.run()`: jobs with state `COMPLETE` and stage `JobStore.AWAITING_APPROVAL_STAGE` → `JobStore.cancel`; operations whose stage `holdsDocument` and have no live attempt (`OcrOperationStore.liveAttempt`) → `finish(stage = FAILED, errorCode = "INTERRUPTED")`; `COMPLETE` operations with `pendingReviewCount > 0` → `revisions.withdrawCandidate` and pending count 0 (reuse `RescanService.discardPending`'s logic by extracting it into a shared function). Each step is a conditional update so a repeat finds nothing.
- [ ] **Step 4:** Wire into `AppContext` before `jobs.resetInterrupted`. Run the test class plus `JobRunnerRecoveryTest`; expect PASS.
- [ ] **Step 5: Commit** (`Clean stale OCR state at startup`).

### Task 2: A new rescan replaces a stopped one

**Files:**
- Modify: `src/main/kotlin/infoscry/document/RescanService.kt` (`admitRescan` ~:566, `resume` ~:685), `src/main/kotlin/infoscry/storage/OcrOperationStore.kt` (`admit`, `activeOperation`)
- Test: `src/test/kotlin/infoscry/jobs/RescanJobHandlerTest.kt`

**Interfaces:**
- Produces: `RescanService.admitRescan(...)` no longer throws `OcrOperationConflictException` for a stopped, failed, interrupted or waiting operation; it ends that operation as `CANCELLED` and admits the new one in one transaction. If the existing operation has a live attempt it is cancelled through `cancel()` first.

- [ ] **Step 1: Write failing tests**: `a new rescan is admitted over a failed operation`, `...over an operation left in OCR by a killed process`, `...over a running operation, which is cancelled`, `repeating admit with the same request id returns the operation that exists`, `the document's published text is unchanged by the replaced operation`.
- [ ] **Step 2:** Run the new tests; expect FAIL with the conflict exception.
- [ ] **Step 3: Implement** the replacement in `OcrOperationStore.admit` (cancel active row, then insert). Keep the partial unique index.
- [ ] **Step 4:** Run `infoscry.jobs.*`, `infoscry.document.*`, `infoscry.server.Ocr*`; expect PASS.
- [ ] **Step 5: Commit.**

---

## Phase 2 — One reading method, confirmed at start

### Task 3: `ReadingMethod` and the list of available methods

**Files:**
- Create: `src/main/kotlin/infoscry/ocr/ReadingMethod.kt`, `src/main/kotlin/infoscry/server/ReadingMethodRoutes.kt`
- Modify: `src/main/kotlin/infoscry/server/Routes.kt` (register route)
- Test: `src/test/kotlin/infoscry/ocr/ReadingMethodTest.kt`, `src/test/kotlin/infoscry/server/ReadingMethodRoutesTest.kt`

**Interfaces:**
- Produces: `@Serializable sealed interface ReadingMethod { data object Tesseract; data object Surya; data class Llm(val profileId: String) }` with `val id: String` (`tesseract`, `surya`, `llm:<profileId>`) and `ReadingMethod.parse(id): ReadingMethod`; `data class MethodAvailability(val method: ReadingMethod, val label: String, val destination: String, val available: Boolean, val unavailableReason: String?, val external: Boolean)`; `GET /api/collections/{id}/reading-methods` → `{ methods: List<MethodAvailability>, default: String? }`.

- [ ] **Step 1: Write failing tests**: parse/round-trip of all three ids and a bad id (400); the route lists Tesseract and Surya with reasons when the tool/model is missing, an LLM profile with `external = true` and `destination` = host, a disabled profile or unset key variable as unavailable with reason; an LLM profile that never passed its image check is unavailable with that reason.
- [ ] **Step 2:** Run; expect FAIL.
- [ ] **Step 3: Implement** using `OcrProfileService` (`keyAvailable`, capability record) and the engine availability already used by `RescanService.snapshotFor`.
- [ ] **Step 4:** Run both classes; expect PASS. **Step 5: Commit.**

### Task 4: Collection stores language and a default method only

**Files:**
- Modify: `src/main/kotlin/infoscry/ocr/OcrModels.kt` (`CollectionOcrSettings`, `OcrSettingsSnapshot.of`), `src/main/kotlin/infoscry/storage/CollectionStore.kt` (:138, :163, :286–300), `src/main/kotlin/infoscry/server/Routes.kt` (`UpdateOcrLanguagesRequest` :101, route :345)
- Test: `src/test/kotlin/infoscry/storage/CollectionStoreTest.kt`, `src/test/kotlin/infoscry/ocr/OcrSettingsTest.kt`

**Interfaces:**
- Produces: `data class CollectionOcrSettings(val language: String, val defaultMethod: ReadingMethod)`; the old engine/profile fields are derived from `defaultMethod` into the existing `ocr_engine`/`ocr_transcription_profile_id` columns, so engine/profile cannot disagree; `ocr_import_mode`, `ocr_review_profile_id`, `ocr_external_page_limit` are no longer read or written (defaults remain in the baseline).

- [ ] **Step 1: Write failing tests**: an old row with engine=LLM and no profile loads as the engine's local default method (no exception); saving a method writes engine and profile together; the PATCH route accepts `{ language, defaultMethod }` and rejects an unknown method with 400; saving twice is a no-op.
- [ ] **Step 2:** Run; expect FAIL. **Step 3: Implement** the model change and update all call sites the compiler reports (use `ReadingMethod` instead of `engine`+`transcriptionProfileId`). **Step 4:** Run `infoscry.ocr.*`, `infoscry.storage.*`, `infoscry.server.Collection*`; expect PASS. **Step 5: Commit.**

### Task 5: Page counts and the import preview

**Files:**
- Create: `src/main/kotlin/infoscry/jobs/ImportPreviewService.kt`, `src/main/kotlin/infoscry/server/ImportPreviewRoutes.kt`
- Modify: `src/main/kotlin/infoscry/document/RescanService.kt` (extract `RescanPageSource.pageCount` into a shared `PageCounter`), `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt` (expose the file enumeration `enumerate` ~:531 as a function the preview shares)
- Test: `src/test/kotlin/infoscry/jobs/ImportPreviewServiceTest.kt`

**Interfaces:**
- Produces: `POST /api/imports/preview` body `{ collection, paths, recursive, include, exclude, method }` → `{ files: List<{path, pages: Int?, reason: String?}>, totalPages: Int, atLeast: Boolean, destination: String, external: Boolean, estimatedCostUsd: Double?, costBasis: String?, previewHash: String }`. `previewHash` = SHA-256 over the sorted file list (path, size, sha256), the method id, the profile revision id and the language. `ImportPreviewService.preview(request): ImportPreview` and `ImportPreviewService.hashOf(request): String` (same inputs, same hash).

- [ ] **Step 1: Write failing tests**: a PDF counts its pages, an image counts 1, an unreadable PDF has `pages = null` and `atLeast = true`; directory filters apply as in import; `previewHash` is stable across repeated calls and changes when a file's bytes, the method or the profile revision change; cost is null with a basis string when a price is unknown.
- [ ] **Step 2:** Run; expect FAIL. **Step 3: Implement**, reusing `costEstimateOf` (RescanService ~:1529) for the estimate. **Step 4:** Run; PASS. **Step 5: Commit.**

### Task 6: Start an import with a method and a confirmed preview

**Files:**
- Modify: `src/main/kotlin/infoscry/server/Routes.kt` (`ImportRequest` :188, `POST /api/imports` :357–402), `src/main/kotlin/infoscry/jobs/ImportJobPayload.kt`, `src/main/kotlin/infoscry/cli/ImportCommand.kt`
- Test: `src/test/kotlin/infoscry/server/ImportRoutesTest.kt` (create if absent, else the existing import route test)

**Interfaces:**
- Consumes: `ImportPreviewService.hashOf`, `ReadingMethod.parse`.
- Produces: `ImportRequest` gains `method: String`, `previewHash: String`, `requestId: String`; the snapshot is built from the method with `externalPageLimit = previewed totalPages` (0 for local methods); a mismatched `previewHash` → 409 `PREVIEW_STALE`; the same `requestId` + body returns the existing job, a different body with the same id → 409.

- [ ] **Step 1: Write failing tests** for: start with a matching hash enqueues one job whose payload snapshot has the method and the page total; a changed file → 409; the same request id twice → one job; double-submit race → one job.
- [ ] **Step 2:** Run; expect FAIL. **Step 3: Implement**; the idempotency record can reuse the `ocr_operations` request-hash pattern or a small `import_requests` table in the baseline (decide in the task; keep the table creation idempotent). **Step 4:** Run; PASS. **Step 5: Commit.**

### Task 7: Read every page; no import mode

**Files:**
- Modify: `src/main/kotlin/infoscry/ocr/OcrModels.kt` (`OcrSettingsSnapshot.mode`), `src/main/kotlin/infoscry/jobs/ImportPipeline.kt` (`sinkFor` :51), `src/main/kotlin/infoscry/extract/DocumentExtractor.kt`, `src/main/kotlin/infoscry/extract/PdfExtractor.kt`
- Test: `src/test/kotlin/infoscry/extract/PdfExtractorTest.kt`, `src/test/kotlin/infoscry/jobs/ImportJobHandlerTest.kt`

**Interfaces:**
- Produces: `OcrImportMode` gains a single behavior, `READ_ALL`: every page is rendered and read with the snapshot's method even when a text layer exists; the snapshot fingerprint includes it so checkpoints cannot be reused across methods.

- [ ] **Step 1: Write failing tests**: a PDF with a text layer is still read by the (fake) engine on every page; the extraction fingerprint differs between two methods; pages already committed under the same fingerprint are not read again after a restart.
- [ ] **Step 2:** Run; expect FAIL. **Step 3: Implement** by mapping the old modes to `READ_ALL` for new runs and keeping the old enum values readable for stored snapshots. **Step 4:** Run `infoscry.extract.*`, `infoscry.jobs.Import*`; PASS. **Step 5: Commit.**

### Task 8: Rescan preview and admission take a method

**Files:**
- Modify: `src/main/kotlin/infoscry/document/RescanService.kt` (`preview` :436, `admitRescan`, `withOverrides` ~:1700, `requirePreviewStillCurrent`), `src/main/kotlin/infoscry/server/OcrRoutes.kt` (`RescanPreviewRequest` :35)
- Test: `src/test/kotlin/infoscry/jobs/RescanJobHandlerTest.kt`

**Interfaces:**
- Produces: `RescanService.preview(collectionId, documentId, method: ReadingMethod)`; `RescanPreviewRequest(method: String)`; the preview's `externalPageUpperBound` is the page total and `approvalRequired` is removed; the snapshot's `externalPageLimit` = page total. The field-by-field overrides merge (`withOverrides`, `RescanOverrides`) is deleted, which removes the engine/profile mismatch bug.

- [ ] **Step 1: Write failing tests**: previewing with each method yields a consistent snapshot; an LLM method without an available profile is refused with the reason from Task 3; admission is idempotent per request id; a changed profile revision between preview and admit → `StaleRescanPreviewException`.
- [ ] **Step 2:** Run; expect FAIL. **Step 3: Implement**; delete the override merging and its helpers. **Step 4:** Run `infoscry.jobs.Rescan*`, `infoscry.document.*`, `infoscry.server.Ocr*`; PASS. **Step 5: Commit.**

### Task 9: No mid-run pause; stop at the confirmed pages

**Files:**
- Modify: `src/main/kotlin/infoscry/jobs/AttemptDispatch.kt` (`onExhausted` :84), `src/main/kotlin/infoscry/ocr/OcrDispatchAuthority.kt`, `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt` (:173), `src/main/kotlin/infoscry/jobs/RetryJobHandler.kt` (:113), `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt` (`pauseForApproval` :513, `isWaitingForApproval` :502), `src/main/kotlin/infoscry/storage/JobStore.kt`, `src/main/kotlin/infoscry/server/OcrRoutes.kt` (approve routes, `externalApprovalOf`)
- Test: `ImportJobHandlerTest`, `RetryJobHandlerTest`, `RescanJobHandlerTest`, `JobRoutesTest`, `OcrRoutesTest`

**Interfaces:**
- Produces: exceeding the snapshot's `externalPageLimit` ends the attempt `FAILED` with code `MORE_PAGES_THAN_CONFIRMED`; `AttemptAwaitingApproval`, `AWAITING_APPROVAL_STAGE`, `OcrOperationStage.AWAITING_APPROVAL`, `ocr_external_approvals` use and both `approve-external` routes are removed; the `externalApproval` job block is removed.

- [ ] **Step 1: Write failing tests**: a run that would need one page more than confirmed ends Failed with that code and sends no extra page; no route `approve-external` exists (404); stage never equals `awaiting-approval`.
- [ ] **Step 2:** Run; expect FAIL. **Step 3: Implement** and delete the dead paths; stored rows with the old stage are handled by Task 1. **Step 4:** Run the five classes plus `infoscry.cli.*`; PASS. **Step 5: Commit.**

### Task 10: Publish on completion; no review stage

**Files:**
- Modify: `src/main/kotlin/infoscry/ocr/CandidateRevisionPhases.kt` (`acceptedPageOf` :86, `publishCandidate` :323), `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt` (review phase), `src/main/kotlin/infoscry/jobs/AttemptDispatch.kt` (`stagedPageReviewOf` :62), `src/main/kotlin/infoscry/document/RevisionPublicationService.kt` (`refusalFor` :443), `src/main/kotlin/infoscry/jobs/DocumentIngest.kt`
- Test: `RescanJobHandlerTest`, `ImportJobHandlerTest`, `RevisionPublicationTest`

**Interfaces:**
- Produces: every staged page is `APPROVED`; the run publishes once when all pages are read (`RevisionPublicationService.publish(documentId, baseRevisionId, candidateRevisionId)`), is a no-op when already published, and a cancelled/failed run publishes nothing. `OcrOperationStage.REVIEW` and `PageApproval.PENDING` are no longer produced.

- [ ] **Step 1: Write failing tests**: a completed rescan replaces the text and the previous revision stays restorable; a run killed after the last page but before publication, then restarted, publishes exactly once; a cancelled run leaves the text unchanged; publishing twice is a no-op.
- [ ] **Step 2:** Run; expect FAIL. **Step 3: Implement.** **Step 4:** Run `infoscry.jobs.*`, `infoscry.document.*`; PASS. **Step 5: Commit.**

### Task 11: Retry uses a method and resumes committed pages

**Files:**
- Modify: `src/main/kotlin/infoscry/jobs/RetryJobHandler.kt`, `src/main/kotlin/infoscry/document/RetryService.kt`, `src/main/kotlin/infoscry/server/DocumentRoutes.kt` (:309)
- Test: `RetryJobHandlerTest`, `DocumentRetryRoutesTest`

**Interfaces:**
- Produces: the retry request takes `method` (optional; omitted = the failed run's method) and `requestId`; retrying a document with committed pages for the same fingerprint reads only the remaining pages; repeating the request returns the same job.

- [ ] **Step 1: Write failing tests** (resume without re-reading committed pages, repeat returns the same job, retry after a failure that left a stale operation). **Step 2:** Run; FAIL. **Step 3:** Implement. **Step 4:** Run; PASS. **Step 5:** Commit.

---

## Phase 3 — One dialog in the web

### Task 12: API client and status model

**Files:**
- Modify: `web/src/lib/api.ts` (add `listReadingMethods`, `previewImport`, `startImport`, `previewRescan(method)`, `startRescan`, types `ReadingMethodOption`, `ImportPreview`; remove approval, review and discard clients)
- Create: `web/src/lib/documentStatus.ts`
- Test: `web/src/lib/api.test.ts`, `web/src/lib/documentStatus.test.ts`

**Interfaces:**
- Produces: `documentStatus(doc, job?): { kind: 'imported'|'reading'|'done'|'failed'|'cancelled'; label: string; progress?: {done: number; total: number}; nextAction: 'scan'|'retry'|'cancel'|null }`.

- [ ] **Step 1: Write failing tests** for URL/body of each client and for every status mapping, including a failed run with a cause label. **Step 2:** Run `cd web && npm test -- --run`; FAIL. **Step 3:** Implement. **Step 4:** PASS and `npm run check`. **Step 5:** Commit.

### Task 13: `StartReadingDialog.svelte`

**Files:**
- Create: `web/src/lib/StartReadingDialog.svelte`, `web/src/lib/StartReadingDialog.test.ts`

**Interfaces:**
- Produces: props `{ collectionId, request: { kind: 'import'; paths; recursive; include; exclude } | { kind: 'rescan'; documentId }, onstarted(run), oncancel() }`. It loads methods and the preview, pre-selects the collection default, shows pages, destination, cost and the confirm label (`Read 48 pages with Surya`, `Send 48 pages to <host> with <profile>`, `Read at least 48 pages …` when `atLeast`), disables unavailable methods with their reasons, re-previews on method change, and submits once (button disabled while submitting; a new `requestId` per open).

- [ ] **Step 1: Write failing tests**: default pre-selected; unavailable method shows its reason and cannot be chosen; changing the method re-previews; confirm label for local, external and `atLeast`; double click submits once; a stale-preview response triggers a re-preview with the message "The files changed; review the summary again."
- [ ] **Step 2:** Run; FAIL. **Step 3:** Implement. **Step 4:** PASS and `npm run check`. **Step 5:** Commit.

### Task 14: Use the dialog for import and Scan again; per-document status

**Files:**
- Modify: `web/src/lib/ImportPanel.svelte`, `web/src/lib/CollectionsPanel.svelte`, `web/src/lib/DocumentRescan.svelte`
- Delete: `web/src/lib/JobApproval.svelte` (+test), `web/src/lib/OcrReviewPanel.svelte` (+test), the discard and approval UI in `DocumentRescan.svelte`
- Test: `ImportPanel.test.ts`, `CollectionsPanel.test.ts`, `DocumentRescan.test.ts`

**Interfaces:**
- Consumes: `StartReadingDialog`, `documentStatus`.
- Produces: Import and Scan again open the dialog; each document row shows its status and one next action; a failed or cancelled document offers Scan again/Retry immediately with no extra step; an in-progress run shows `Reading n of N` and "Cancel and start over" in the dialog.

- [ ] **Step 1: Write failing tests**: starting an import goes through the dialog and sends the method and hash; Scan again after a failed run needs no prior action; no text anywhere mentions approval or review; cancel updates the row at once.
- [ ] **Step 2:** Run; FAIL. **Step 3:** Implement and delete. **Step 4:** PASS and `npm run check`. **Step 5:** Commit.

### Task 15: Collection settings: language and default method

**Files:**
- Modify: `web/src/lib/CollectionOcrSettings.svelte`, `web/src/lib/OcrChoiceFields.svelte` (delete or reduce to the method list), `web/src/lib/ocrRescan.ts`
- Test: `CollectionOcrSettings.test.ts`

- [ ] **Step 1: Write failing tests**: only language and the default-method list are shown; saving sends `{ language, defaultMethod }`; unavailable methods are disabled with reasons. **Step 2:** FAIL. **Step 3:** Implement; drop the allowance hint added earlier. **Step 4:** PASS. **Step 5:** Commit.

### Task 16: CLI import with a method

**Files:**
- Modify: `src/main/kotlin/infoscry/cli/ImportCommand.kt` (add `--method`, `--yes`; remove approval remedy printing :328, :398–473)
- Test: `src/test/kotlin/infoscry/cli/ImportCommandProcessTest.kt`

- [ ] **Step 1: Write failing tests**: without `--method` the collection default is used and the summary (pages, destination) is printed; an external method without `--yes` prompts and aborts on no answer; repeating the command does not create a second job. **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit.

---

## Phase 4 — Remove the old flow

### Task 17: Delete review and approval code

**Files:**
- Delete or reduce: `storage/OcrReviewStore.kt`, `ocr/StagedPageReview.kt`, `ocr/OcrComparisonService.kt`, `ocr/OcrDecisionPolicy.kt`, review routes in `server/OcrRoutes.kt`, `RescanService.{pendingReviews,decideReviews,publishDecisions,discardPending}`, and their tests (`OcrReviewRoutesTest`, `OcrReviewDecisionPublicationTest`, `OcrDecisionPolicyTest`)
- Modify: `docs/tickets/ocr-rescanning/STATUS.md` and tickets 08d–08f, 11a, 11b (mark retired), `docs/specs/2026-09-30-ocr-rescanning.md` (note superseded sections), `README.md`, `docs/usage.md`, `docs/cli.md`

- [ ] **Step 1:** Run `./gradlew check` and note the baseline. **Step 2:** Delete the code and tests in the order the compiler allows; no behavior change is expected, so no new tests besides keeping `StaleOcrStateCleanupTest` (which still handles old rows through plain SQL if the stores are gone). **Step 3:** Update docs and ticket status. **Step 4:** `./gradlew check` PASS. **Step 5:** Commit.

### Task 18: Acceptance

**Files:**
- Modify: `src/test/kotlin/infoscry/server/OcrBrowserAcceptanceTest.kt`, `CollectionsBrowserAcceptanceTest.kt`, `web/e2e/ocr-browser-acceptance.mjs`, `web/e2e/collections-browser-acceptance.mjs`

- [ ] **Step 1:** Rewrite the scenarios with the local fake provider: import through the dialog (local and external), Scan again over a failed run, cancel and start again, restart mid-run and start again, history and restore. **Step 2:** Run `./gradlew externalTest`; PASS. **Step 3:** Record in `README.md` what remains unverified (real provider, real CoreML) and the manual checklist. **Step 4:** Commit.

## Spec coverage

Decisions 1–8 → Tasks 12–14 (document status, one dialog), 3–4 (method list, default), 7 (all pages), 8–9 (approval at start, no pause), 10 (no reviewer), 4 (language), 1–2, 11 (restartable). "Always restartable" → Tasks 1, 2, 10, 11. Preview/hash → Tasks 5–6, 8. Collection settings → Tasks 4, 15. Superseded contracts → Tasks 9, 10, 17. Verification section → test steps in each task and Task 18.
