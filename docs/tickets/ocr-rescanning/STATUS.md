# OCR and rescanning implementation plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation or superpowers:subagent-driven-development if the user selects delegated execution. Steps use unchecked criteria; these are requirements, not completion evidence.

**Goal:** Select OCR engines, compare rescans with published text, review differences and publish reversible document revisions safely.

**Architecture:** Persist OCR profiles and immutable attempt snapshots in SQLite. Stage text revisions independently, run pluggable page-image OCR and review, and publish coherent text/index revisions through a recoverable boundary. Keep normal imports and Retry compatible.

**Tech Stack:** Kotlin/JVM 25, SQLite, Lucene, TypeScript/SvelteKit; bounded local Surya child process and image-capable LLM endpoints.

**Spec:** [2026-09-30 OCR design](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts:** [Shared interfaces, constraints and test gates](CONTRACTS.md).

Product scope was authorized on 2026-09-30. This plan was decomposed on 2026-10-02 after a targeted assessment through `97d7ce8`. Tickets 01–06 and the 07/07b backend provide an implemented baseline with historical passing tests. The manual product is unfinished, and 07b's repair gate is open. No unchecked criterion or old full-suite count establishes current end-to-end readiness. No implementation, installation, model download, private-data experiment, commit or push is authorized by this planning document.

## Global constraints

- Kotlin/JVM 25; one application process and one Gradle backend module; TypeScript/SvelteKit static frontend.
- SQLite authority, rebuildable Lucene; immutable originals and stable content-unit IDs.
- GPU embeddings through CoreML on macOS arm64; no CPU embedding fallback.
- Bind only to 127.0.0.1; existing bearer/CSRF, mutation admission and deletion guards apply.
- Secrets only from environment variables; no source data or credentials in logs/errors.
- Preserve pinned dependencies and unrelated changes; English restrained accessible UI.
- Pilot mode first. Automatic replacement stays disabled until measured thresholds are explicitly accepted and met.

## Execution units and dependency order

Use one leaf ticket as the implementation/review unit. Umbrellas 02b, 08, 09, 10 and 11 coordinate completion; do not implement them as large batches. Dependencies are correctness gates, not an instruction to delegate. Files overlap; serialize migrations and integration edits even where behavior is independent.

| Baseline | Current status |
|---|---|
| [01](01-profiles-and-settings.md) | Implemented; historical focused/full gates recorded below |
| [02](02-revision-publication.md) | Publication implemented; 02b evidence/seal follow-ups open |
| [03](03-ocr-engine-contract.md) | OCR seam implemented; 03b/03c provenance gates open |
| [04](04-surya.md) | Adapter and historical real Mac run recorded; final runtime gate remains 10b |
| [05](05-image-llm.md) | Image transport/profile capability implemented; final local image-model gate remains 10b |
| [06](06-comparison.md) | Comparison/pilot decisions implemented; review material/decision surface remains 08d–08f |
| [07](07-jobs-and-admission.md) | Rescan backend implemented; import repair gate is 07b plus 07c–07f |
| [07b](07b-import-execution-and-admission.md) | Implemented baseline with unresolved correctness work; open gate |

| Execution ticket | Blocked by | State |
|---|---|---|
| [03b — Provenance persistence](03b-image-provenance.md) | 03 | Implemented |
| [02c — Saved Ask excerpt fallback](02c-ask-evidence-viewer.md) | 02 | Implemented |
| [02d — Investigate evidence survives replacement](02d-investigate-evidence-provenance.md) | 02, 02c | Not started |
| [02e — Publication seal failure and recovery](02e-publication-seal-acceptance.md) | 02 | Implemented |
| [03c — Retained image references remain usable](03c-image-artifact-lifetime.md) | 03b | Implemented |
| [07c — Resume the same staged import candidate](07c-durable-import-candidates.md) | 07b implementation | Implemented |
| [07d — Retry uses admitted OCR and review authority](07d-retry-ocr-execution.md) | 07c | Implemented |
| [07e — Keep ordinary extraction for non-image formats](07e-non-image-import-compatibility.md) | 07b implementation | Implemented |
| [07f — Exercise external review admission locally](07f-external-review-accounting.md) | 07c, 07d | Implemented |
| [08a — OCR profile administration](08a-ocr-profile-admin.md) | 01, 05 | Not started |
| [08b — Collection OCR defaults](08b-collection-ocr-controls.md) | 08a, 07e | Not started |
| [08c — Preview and control one rescan](08c-rescan-controls.md) | 08b, 07c, 07d, 07f | Not started |
| [08d — Read review material for imports and rescans](08d-review-read-api.md) | 03c, 07c, 07e | Not started |
| [08e — Persist and publish manual page decisions](08e-review-decision-publication.md) | 08d, 02e | Not started |
| [08f — Manual page review in Admin](08f-page-review-ui.md) | 08c, 08e, 02c, 02d | Not started |
| [09a — Revision restoration API](09a-restore-service.md) | 08e, 02d | Not started |
| [09b — Published history and restore controls](09b-history-ui.md) | 09a, 08f | Not started |
| [09c — Deletion removes revision-owned work safely](09c-revision-deletion-acceptance.md) | 03c, 07c, 08e, 09a | Not started |
| [10a — Complete manual OCR browser acceptance](10a-browser-acceptance.md) | 08f, 09b, 09c, 07f, 02d, 02e | Not started |
| [10b — Separate real OCR and CoreML evidence](10b-real-runtime-gates.md) | 03c, 07d, 08e | Not started |
| [10c — Reconcile docs and manual completion evidence](10c-manual-release-checkpoint.md) | 10a, 10b | Not started |
| [11a — Local pilot manifest and scoring runner](11a-evaluation-harness.md) | 10c | Not started |
| [11b — Measured pilot and explicit activation gate](11b-pilot-and-activation.md) | 11a | Not started |

### Recommended sequence

1. Correctness foundation: 07c → 07d → 07f; 07e is independent of that chain. Complete 03b → 03c and 02c → 02d, plus independent 02e, before opening a user-facing rescan/review flow.
2. Bounded UI/API slices: 08a → 08b; 08d → 08e can proceed once their repair dependencies pass. Then 08c and 08f integrate the verified controls and decision contracts.
3. History: 09a → 09b; 09c verifies deletion/recovery after backend decision/restore behavior exists.
4. Manual finish line: 10a browser acceptance and 10b real runtime evidence are separate gates; 10c reconciles all required evidence and user documentation.
5. Later, separately accepted quality work: 11a → 11b. Automatic replacement stays disabled while any activation prerequisite is missing.

Per-ticket dependencies in the table are the minimum implementation dependencies. User-facing rescan/review release additionally requires 02c–02e, 03b–03c and 07c–07f, even when a profile/settings panel can be built earlier. "Independent" means separately testable, not automatically safe to edit shared files concurrently.

## 2026-10-02 assessment and planning record

Scope: spec, tickets and central implementation paths through `97d7ce8`; targeted review rather than every line of the 147-file change. A new run passed 116 tests across ImportJobHandlerTest (39), RetryJobHandlerTest (10), RescanJobHandlerTest (15), OcrComparisonTest (26), RevisionPublicationTest (19) and RevisionPublicationRecoveryTest (7). Full `check`, browser, real OCR and CoreML gates were not rerun in that assessment. No application code changed.

The plan assigns staged-import restart to 07c, Retry wiring to 07d, non-image compatibility to 07e, external-review tests to 07f, saved evidence to 02c/02d, seal integration to 02e, provenance validation to 03b and lifetime to 03c. Investigate already stores excerpts: older text claiming an absent excerpt column was incorrect; 02d adds revision provenance and repairs the live-unit join.

## Historical verification records

The records below describe prior slices and runs, not the current completion state. Current ownership and blockers are the tables above.

## Review focus

- Publication crash or concurrent read exposing mixed text/index versions — ticket 02.
- A readable-looking PDF text layer hiding incorrect/missing text — ticket 03.
- External review accidentally sending local OCR output without allowance — ticket 07.
- Model fluency masking invented names, numbers or omitted handwriting — tickets 06 and 11.
- Stale review/restore overwriting current text or changing saved citation meaning — tickets 02, 08 and 09.

## Verification record

Ticket 01 only. `./gradlew check` succeeded on 2026-09-30 (13m45s, all backend tests plus the frontend
tests and build) and the focused suite above is green. Migration 017 adds the two OCR profile tables and
the collection OCR settings columns; a legacy archive keeps its language, Tesseract, fill-missing, no
reviewer and allowance 0, and a legacy queued payload still decodes. Two pinned digests prove the legacy
extraction fingerprint is byte-identical to the previous build's, so existing checkpoints keep matching.
An independent review pass found three gaps that were fixed before these gates: a snapshot resolved its
two profile revisions in two separate transactions, endpoint validation accepted non-http schemes and
hostless URLs, and an unknown body field (the shape a pasted key would take) was silently dropped instead
of refused.

At the ticket-01/02 checkpoint, later implementation and runtime gates were still open. Subsequent historical records below supersede that checkpoint; the current execution table above is authoritative. Existing unrelated Ask/source-viewer gates remain independent.

## Ticket 02 verification record

Migration 018 adds immutable document and page-text revisions, revision-owned chunks and vectors,
durable publication intents and `citations.revision_id`; existing content is backfilled into an initial
published revision with its stable unit IDs. Publication runs PREPARED → staged index commit → SQLite
authority → reader-snapshot switch → PUBLISHED, with recovery that finishes an authoritative publication
and discards one that never moved authority, both before anyone is served. `./gradlew check` was green on
the final tree (97 suites, 1133 tests, 0 failures, 14 minutes) and the focused suites were re-run
afterwards.

Three independent adversarial reviews shaped this ticket, and each of their findings was fixed and
re-verified with a test whose failure was reproduced by temporarily reverting the fix: untagged import rows
served beside a replacement (superseded rows are removed before the snapshot switch); a check-then-increment
race in reader acquisition and non-exclusive handoffs (atomic acquire under one monitor, serialized
handoffs, base recheck inside the handoff); a failed publication leaving unowned candidate rows searchable
(the candidate is hidden before its rows can exist); cleanup writing to the index outside mutation
admission; a failure after the authority commit reported as failure; a candidate draft readable through the
source route (only PUBLISHED and SUPERSEDED revisions are readable); and a recovery window that could serve
the old search reading beside the new source text (reads are sealed before a roll-forward whose switch has
not happened, and unsealed when the completion switches).

Left open deliberately and carried by [ticket 02b](02b-revision-aware-evidence.md): the Ask viewer's
revision-unknown excerpt fallback, the Investigate evidence ledger's missing revision provenance (its excerpt column already exists), and
an end-to-end test for the pre-switch seal window. Also reported, not resolved: a legacy citation that
neither its live unit nor any revision that once held it can place is dropped from history rather than
misattributed, and that resolver is an unindexed per-row lookup.

## Ticket 03 verification record

The engine seam, the renderer, the Tesseract adapter and the mode branch are in the tree, with FILL_MISSING
keeping the previous heuristics and thresholds and the legacy extraction fingerprints byte-identical. My own
final run of `./gradlew check` was green (98 suites, 1176 tests, 0 failures, 14m13s) and the focused suites
were re-run after the last fix.

Four adversarial reviews shaped this ticket, each finding fixed and proven by a test that fails when the fix
is reverted: the blank scan decoded a whole raster before bounding it (it now reads declared dimensions
first); an over-cap imported picture was dispatched to the engine unbounded (it is reduced to a bounded
derived copy with its own hash and render version, or refused with `PAGE_RASTER_UNBOUNDED`); the bound
arithmetic overflowed `Int` for an extreme declared width (now `Long`, with a regression test, after the
parent traced the overflow by hand); multipage artifacts are called blank only when every frame is white;
candidate keys are scoped by document and fingerprint; and a line break in a fingerprint field is refused
instead of composing two readings into one digest. Subsampled decoding was measured bounded on the pinned
JDK for JPEG, PNG and TIFF (step 3 under a 128 MB heap where the full raster cannot fit) rather than assumed.

Handed to [ticket 03b](03b-image-provenance.md): root-confined provenance enforced at the store and schema,
no half-populated provenance rows, and renders that staged candidates reference kept alive. Reported
residuals: a reader that ignores source subsampling can still allocate before the post-check (measured for
three formats, not proven for every one); PDFBox's internal allocations sit outside the raster bound; and
the real-Tesseract, browser and GPU/CoreML gates belong to ticket 10.

## Ticket 04 verification record

The local Surya engine and its worker are in the tree, with the real runtime proven on this Mac rather than
through a fake: surya-ocr 0.22.1 (Apache-2.0), torch 2.14.1, transformers 5.18.0, llama.cpp 0.5.0
(build 11146) serving `datalab-to/surya-ocr-2` weights (1 266 400 864 + 204 986 688 bytes) through
llama.cpp with Metal offload. The committed typed and handwriting-*style* fixtures transcribed exactly; the
adapter reads a page in ~1.8 s warm and ~5.2 s cold including model load; the identity probe costs 1.3 s and
starts no model. My own `./gradlew check --rerun-tasks` was green (99 suites, 1197 tests, 0 failures,
14m43s), `externalTest --tests 'infoscry.ocr.SuryaRealToolTest'` was green (1 test, 0 failures, 0 skipped),
and the worker's stdlib-only self-check is green.

Three adversarial reviews shaped this ticket. They found, and the fixes proven by revert evidence closed:
a teardown that waited only for the Python process (so a helper ignoring SIGTERM could outlive the engine);
an empty reading committed as a successful extraction (engines now report `OCR_EMPTY` and both consumers
fail the unit unless the raster is verified blank, which deliberately flipped two ticket-03 expectations);
boxes accepted outside the page; an unconfigured engine that named no remedy; worker output caps checked
after building the output; and a runtime identity that never reached the extraction fingerprint, so a model
change would have reused checkpoints — the probe now runs before the fingerprint and the committed-key
lookup, with absence never equal to a discovered value. The parent also fixed a `close()`/`transcribe()`
race in which a worker could start after `close()` returned.

One further round closed a class of collision the reviews found by sweeping beyond the reported symptom: a
fingerprint field that encodes an absent value as a literal word can be composed identically by a *real*
value equal to that word — a runtime that truthfully reports `none`, or a tool whose first output line is
`none`, would reuse checkpoints committed while nothing was known (including a refusal recorded because no
tool ran). Absent values are now tagged (`fingerprintPresence`: `absent` versus `present:<value>`) in the
transcription and review fingerprints and in the extraction attempt block, and the two legacy extraction
fields — whose absent form is written into already-committed digests and therefore cannot change — escape a
present value that would read as absence (`legacyFingerprintField`). Every realistic value composes exactly
as before, which the two pinned digests still prove.

Residuals recorded, not resolved: `OcrSettingsSnapshot` has no `runtimeIdentity`, so ticket 07's admission
path must carry the probe value into the snapshot it persists; invalidation is proven with stand-in
identities plus a real probe rather than a real model upgrade; the probe's 10 s hang bound is untested; a
process re-parented before teardown is invisible to `ProcessHandle.descendants()`, which is why the
documented guarantee names the handles captured while the worker was alive; the handwriting fixture is a
typeface render, so human handwriting quality is measured only in ticket 11's pilot; and roughly 11 GB of
leftover `infoscry*` test temp directories from earlier runs await cleanup.

## Ticket 05 verification record

The image-LLM client and engine are in the tree: `ImageLlmClient` speaks both image protocols from the page's
own bytes (hash-checked against the page record, format-sniffed, budgeted against the profile's window and
reserved output, response bytes capped while reading, one timeout that includes the body read, retries only
on 429/5xx, redirects never followed, resolved model versions recorded) and refuses a non-local destination
without ticket 07's injected permit validator; `LlmOcr` resolves only the snapshotted revision and maps blank
text to `OCR_EMPTY` rather than claiming blankness; `prompts/ocr-transcription.txt` ships with the
versioned transcription schema; `POST /api/ocr/profiles/{id}/probe` sends only the synthetic image and
records the measurement. My own `./gradlew check` was green on the final tree (102 suites, 1268 tests, 0
failures, 15m55s).

Review rounds drove, each with a guard-revert proof: a truncated answer becoming a reading (the finish reason
is now required to mean complete), an external destination dispatched without a permit, credentialed
endpoints accepted and echoed (refused now at every write, with migration 020 removing what an archive
already stored, marking repaired conversation snapshots so the read path can still tell, and switching the
repaired profiles off), a switched-off profile still dispatching from `/api/ask`, Investigate and the
best-effort title refresh (the rule is now enforced where each call is made, not only at the route), and a
probe body check that could buffer an unbounded body or wait forever for one that never arrived. The parent
found and fixed the migration's delimiter bug by hand: it took the first delimiter *type* present rather than
the earliest delimiter, which would have rewritten `https://host?email=a@b/c` into `https://b/c` — a
different host — and both regression cases now fail against the old expression.

Residuals: no real provider gate (ticket 10); `LlmOcr` is not wired into the serving path (ticket 07's
admission); a conversation links to its profile only by name, so a repaired snapshot is stopped by the read
path rather than the migration; a hand-edited unparsable endpoint is still refused by the record rather than
repaired; and `LlmStore.persistInvestigateModelCall` takes a raw endpoint string that no HTTP caller reaches
today.

## Ticket 06 verification record

The comparison, the decision policy, the durable review store and the review call are in the tree, with the
review prompt at v2. My own `./gradlew check` was green on the final tree (104 suites, 1313 tests, 0 failures,
15m53s) and the focused suites were re-run with forced execution.

Five review rounds shaped it, each finding closed with a revert proof. The approval gate took four of them to
become structural: a caller-implementable predicate, then an `internal`-constructor record (module-wide and
therefore forgeable), then a record resolved per decision, and finally a policy that holds the review store —
the seam is deleted, the record's constructor is private to that store and its only producer is the store's
own read, so an acceptance can only be resolved from a live row and cannot outlive it. Baselines are loaded
from the revision store and must match the input's unit, ordinal, text and (where the revision recorded one)
hash before the identical-text shortcut or any reuse. Cached reviews re-decide their disposition with the
current policy; a review is only reused while the page image still matches the hash it was judged from; a
claimed span must be absent exactly when that side has no finding span; prompt rendering is single-pass so
source text cannot substitute a placeholder; and the A/B wording no longer discloses which side is the new
reading.

Residuals: nothing wires a store-backed policy in production yet, so approval stays unreachable until ticket
07 passes one and ticket 11 writes an accepted-validation row; `OcrReviewStore.pending()` reads stored rows, so
a stored `APPROVE` whose acceptance has vanished is not offered for manual decision (ticket 08's surface); the
baseline hash is checked only where a revision recorded one, because pages copied by `recordPublishedContent`
carry a NULL `text_sha256`; a compared `PageImage` must be named with the baseline revision's own unit id
rather than an extraction key; `OCR_REVIEW_PROMPT_VERSION = 2` means a v1 attempt is refused as
`OCR_REVIEW_PROMPT_MISMATCH`; and writing `ocr_validation_records` directly is the deliberate trust boundary,
because that is the archive accepting a combination.

## Ticket 07 verification record

The rescan path is verified on the repaired tree: my own `./gradlew check` was green (106 suites, 1340 tests, 0
failures, 16m27s) and the focused suites are green (`RescanJobHandlerTest` 11, `OcrRoutesTest` 13,
`ImportJobHandlerTest` 28, `RetryJobHandlerTest` 10). An independent review then found four blocking issues, of
which operation ownership was implemented (an operation holds its document while unfinished **or awaiting
review**, which the schema's partial unique index states too) and three were not:

- the import half executes none of what it admits — the worker forwards only `payload.settings`, the
  production extractor registry has no image-capable engine, `CHECK_AND_IMPROVE` OCRs pages without comparing
  or reviewing them, an import finishes `COMPLETE` rather than review-pending, the import approval route
  records an approval without resuming the job, and the remote CLI path reports success without checking
  admission;
- `requireSnapshotStillResolvable` revalidates profile revisions and an LLM engine only, not the current
  mode, language, reviewer selection or allowance;
- resume and approval can enqueue a second attempt for one operation: neither claims the attempt through the
  operation store's `startAttempt` guard, so two workers can run against one candidate and one set of counters.

The two implementing runs timed out on this ticket's combined scope (~62 KB service, ~49 KB handler, routes,
import path, tests), and the second left the tree mid-edit: the parent repaired a claim check that called a
helper returning `Unit` (the store did not compile), two schema-version literals still at 23 after migration
024, and a managed-copy test that predated the ownership rule. Everything above is carried by
[07b](07b-import-execution-and-admission.md), including the CONTRACTS vectors (`external_limit`,
`same_page_two_stages`) and the concurrency tests the review named as missing.

Residuals also recorded: the review's remaining P2s — the import admission snapshot carries no runtime
identity (it is re-probed in `DocumentIngest`, so a restart could resume under a newly discovered runtime),
and `OcrRoutes` already exposes review-decision/publication and revision endpoints that belong to tickets 08
and 09.

### Ticket 07b's finished sequence (baby steps)

The import half was finished in five small steps, each verified by a forced focused run of my own rather than by
its report alone: candidate phases extracted from the rescan handler into `CandidateRevisionPhases.kt` (986 →
709 lines, bodies moved not copied); `PageImage.ofProvenance` rebuilding a staged page's image and refusing
changed bytes; per-document sink selection so a check-and-improve import stages a candidate while `FILL_MISSING`
keeps the stored-units path; the comparison and review recorded while the draft is in hand (the reviewer factory
moved to `StagedPageReview.kt`); and publication of an initial import's approved pages through the shared
phases, with the rescan rule untouched. The product rule behind the last step came from the owner: an initial
import publishes its approved pages because there is no earlier reading to protect, while a rescan stays
all-or-nothing because its base revision is already serving readers.

## Ticket 07b verification record

Verified by my own `./gradlew check` (106 suites, 1353 tests, 0 failures, 16m39s) and by inspection of the three
mechanisms it adds. The admitted runtime identity is now recorded at admission — import admission, the
standalone CLI import and the retry prerequisites all probe once and carry it in the payload — and
`DocumentIngest` asks the reader for an identity only when the settings record none, which is what kept
`SuryaOcrTest`'s property intact: a recorded identity is never replaced, while an attempt with no identity is
still keyed by what the reader reports, so a changed runtime forces a re-read rather than a silent reuse.
`NEEDS_REVIEW` is reachable through `ExtractionSink.awaitingDecision` (`ContentStore.unitsAwaitingDecision`
counts pages whose `page_approval` is PENDING), and an import whose reading still owes a person a decision ends
in that state instead of `COMPLETE`. Job-owned external approval, the two-file allowance, settings
revalidation, attempt claiming and the CLI's approval surfacing were already verified and remain green.

One run of `./gradlew check` failed on a single pre-existing test (`SuryaOcrTest > a page committed under one
runtime is read again when the probe reports another`) before the identity rule was reconciled as described;
the test was not touched and passes on the final tree.

Residual, closed with the slice that followed: a `NEEDS_REVIEW` document is now a finished status for import
purposes (`ImportJobHandler.FINISHED_STATUSES`), so a byte-identical re-import records `DUPLICATE` instead of
re-reading a document that is waiting for a decision. The remaining work — the import's compare/review and
publication — is measured in the ticket, together with the publication question it is gated on.

## Ticket 07c verification record

Durable staged-import candidate resumption is implemented: migrations 025 and 026 add `attempt_fingerprint` and `page_text_revisions.unit_key`, with a partial unique index `document_revisions_resume_uniqueness` on (document_id, attempt_fingerprint); `DocumentRevisionStore.openCandidate(..., attemptFingerprint)` adopts the existing candidate on collision, `resumableCandidate(documentId, fingerprint)` loads the newest CANDIDATE for resume, and `CandidateRevisionSink.committedKeys` adopts resumable candidates and returns their staged keys to skip repeat OCR. `ExtractionSink.deliver(documentId, fingerprint, event, approval)` writes a page and its approval in one transaction to close crash windows. Focused gate: `./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.ocr.OcrEngineContractTest' --tests 'infoscry.storage.*'` = 168 tests, 0 failures. Five scenarios verified by ImportJobHandlerTest: kill/resume reuse, pause/resume without reset, interruption-after-staging completion, changed-snapshot distinction, and cancel/fail recoverability. SchemaMigrator SUPPORTED_VERSION now 26. The accumulated `./gradlew check` is green: backend 1378 tests and web 213 tests, 0 failures (`git diff --check` clean).

## Ticket 07d verification record

Retry now executes the OCR selection it was admitted with: `RetryJobPayload.ocr` carries the `OcrSettingsSnapshot` (`RetryPrerequisites.probe` captures it and `RetryService.admit` enqueues it), and the new shared `AttemptDispatch` helper — built once in `ImportJobHandler.attachTo` and passed to both handlers — supplies the job-owned `OcrDispatchAuthority` and the snapshotted `StagedPageReview` that `RetryJobHandler.retryOne` now passes to `DocumentIngest.ingest`, so retry uses the same dispatch allowance, comparisons and reviewer wiring as import. A spent allowance throws the renamed `AttemptAwaitingApproval`, which the retry's `handle` records durably as `JobStore.AWAITING_APPROVAL_STAGE` (mirroring the import) instead of failing. No new migration.

Red first: with only the tests and payload field in place, `a differing check-and-improve retry calls the reviewer and leaves a proposal pending` failed with `NoSuchElementException: List is empty` on `ocrReviews.pending(...).single()` (the retry recorded no review) and `external transcription and review of one retry page count one distinct page and two calls` failed with `expected: <1> but was: <0>` for `extractor.sent` (the retry built no dispatch authority) — both behavioral, against a compiling suite. Focused gate green on 2026-10-05: `./gradlew test --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.document.RetryService*'` plus `DocumentRetryRoutesTest` (no `RetryService*` test class exists; the routes suite exercises admission) = RetryJobHandlerTest 16, ImportJobHandlerTest 45, DocumentRetryRoutesTest 17, 0 failures. Six new tests pin the ticket's four scenarios: differing/identical check-and-improve retry review, one distinct page and two calls across both stages through an external (non-loopback) reviewer profile, allowance-0 wait before any dispatch, resume continuing the same counters under the unchanged decoded snapshot/settings, and `RetryService.ELIGIBLE_STATUSES` still excluding `COMPLETE`, `COMPLETE_WITH_WARNINGS` and `NEEDS_REVIEW` (the failed-unit-revisit test kept green). Accumulated gate green: `./gradlew check` = 107 suites / 1384 backend tests + web 213 tests, 0 failures (17m05s); `git diff --check` clean. Residual: the external reviewer profile in the accounting tests names `HOME` as its key variable so the client's credential gate passes; its endpoint is `https://example.invalid/v1` (DNS fails in ~24 ms, nothing is delivered anywhere), so the test pins dispatch accounting, not a live provider answer — a real external provider gate remains ticket 10b.

Review fix (P1, 2026-10-06): a resumed retry no longer re-processes documents an earlier run of the same job already finished. `RetryJobHandler.handle` skips a document whose status is in `ImportJobHandler.FINISHED_STATUSES` (now `internal`, reused by the retry handler for the same reason) only when the job's durable `completed` counter — read *before* the attempt's own `reportProgress(0, …)` reset — shows a previous run of this job did work: a status-only skip would silently swallow a fresh explicit retry of a `COMPLETE`/`COMPLETE_WITH_WARNINGS` document (production admission never queues a finished one, but `SuryaOcrTest`'s runtime-forced re-read and the failed-unit revisit pin exactly that), which the one-shot exact variant was observed breaking (`an explicit retry revisits the unit a crash resume would skip` = `expected: <[unit-failed-0]> but was: <[]>`; `SuryaOcrTest > a page committed under one runtime is read again…` = `expected: <2> but was: <1>`) — neither test was touched. The `AttemptAwaitingApproval` wait log also records `waiting.jobId.value` under a new `JOB_ID_FIELD` (`job_id`) instead of `document_id` (P2). New regression test `a resumed retry does not re-process the document the paused run already finished`: two failed documents, a one-page external allowance pauses the job on the second document's page, approval (`maxDistinctPages = 2`) and resume dispatch each document's page exactly once and leaves both `COMPLETE` with `distinctPages == 2` / `calls == 2`. Red before the fix — `expected: <1> but was: <2>` on the first document's dispatch count (the finished document was read again), RetryJobHandlerTest 17 tests / 1 failed — then green. Focused gate: `./gradlew test --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.server.DocumentRetryRoutesTest'` = 79 tests, 0 failures (RetryJobHandlerTest now 17). Accumulated gate green: `./gradlew check` = 107 suites / 1385 backend tests + web 213 tests, 0 failures (17m05s); `git diff --check` clean.

## Ticket 07f verification record

External review admission is exercised locally: the production path dispatches through the injected
`RecordingImageLlmEngine` (a scripted Ktor `HttpClientEngine` in `src/test`) at syntactically external
`https://…example.invalid/v1` endpoints, while production's `endpointScope` classification, the permit ask,
the credential gate and the job-owned counters all stay enabled. The seam is `ImageLlmClient`'s new optional
`engine` parameter plus `clientEngine` pass-throughs on `LlmOcr`, `OcrComparisonService`,
`ocrReviewerFactory`, `rescanEngineFactory` and `ImportJobHandler.attachTo` (all default null = production
CIO; the client still builds its own `HttpClient` with redirects off around whatever engine is injected).
The three ticket-owned suites pin the four scenarios: transport-at-classified-endpoint / permit-first /
no-payload-in-errors (ImageLlmClientTest), one page and two calls across transcription plus review with the
document past allowance pausing before the transport, wrong owner/profile/document and stale-scope refusal,
and resume continuing counters (ImportJobHandlerTest), and bounded retries adding calls but not pages,
allowance-0 wait and resumed-retry counters (RetryJobHandlerTest). `OcrDispatchAuthority.kt` needed no fix
(it is byte-identical to HEAD); no migration was added (SchemaMigrator SUPPORTED_VERSION stays 26).

Red evidence, 2026-10-06 — each proof was a temporary one-line revert of the pinned behavior, run once,
then restored byte-identical (`git diff` empty on the touched file, no `TEMPORARY REVERT PROOF` marker left
in `src/`):

- **Classification disabled** (`endpointScope` → LOCAL):
  `./gradlew test --tests 'infoscry.ocr.ImageLlmClientTest'` = 36 tests, 5 failed —
  `expected: <EXTERNAL> but was: <LOCAL>`, `the permit is asked about the page, not about each attempt
  => expected: <1> but was: <0>`, `expected: <OCR_EXTERNAL_DISPATCH_NOT_PERMITTED> but was:
  <OCR_PROVIDER_UNAVAILABLE>`.
- **Ownership guards disabled** (`OcrDispatchAuthority.isPermitted` profile/document checks): the
  pre-existing wrong-profile/document assertions passed vacuously at allowance 0, so the test was
  strengthened to re-assert them after owner A's approval; under the revert the strengthened assertion
  failed: `an approved allowance does not make another profile's revision dispatchable here`
  (`./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest'` = 47 tests, 1 failed).
- **Stale-scope filter removed** (`OcrOperationStore.allowanceFor` snapshot-hash `takeIf`): same command,
  1 failed: `a page under a stale scope cannot dispatch`.
- **Call recording disabled** (`attemptAboutToBeSent` body unreachable):
  `./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.jobs.RetryJobHandlerTest'`
  = 65 tests, 8 failed — `both stages called out against the job's one scope => expected: <2> but was:
  <0>`, `every network attempt counts as a call => expected: <3> but was: <0>`. (An earlier malformed
  revert that swallowed only the profile guard produced `BUILD SUCCESSFUL`; it was discarded and redone.)
- **Allowance bound removed** (`authorizePage` bound check bypassed): same ImportJobHandlerTest command,
  47 tests, 5 failed — `a page left this machine before its scope was approved => expected: <0> but was:
  <1>`, `the page past the allowance was sent => expected: <1> but was: <2>`, `the page past the allowance
  waits instead of being sent => expected: <awaiting-approval> but was: <queue>`.
- **Payload echoed into an error** (permit-refusal message interpolated the endpoint):
  `./gradlew test --tests 'infoscry.ocr.ImageLlmClientTest'` = 36 tests, 1 failed: `the endpoint is never
  echoed: '… its image was not sent to https://…'`.

Excluded from red evidence as infrastructure, per the ticket: the first focused run's
`infoscry.config.ProcessLockUnavailable at ImportJobHandlerTest.kt:731` — the wrong-owner test read
`harness.externalAccountOf` (which opens its own `AppContext`) inside an already-open context; repaired
side-in by reading the account through the open context's `ocrOperations`, with no assertion weakened. One
intermediate run also failed with the Kotlin daemon's `Not enough memory to run compilation` (environment);
an unchanged retry succeeded.

Green gates, 2026-10-06:

- Focused (ticket's exact command):
  `export JAVA_HOME="$(asdf where java)" && ./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest'
  --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.ocr.ImageLlmClientTest'`
  = BUILD SUCCESSFUL; ImportJobHandlerTest 47, RetryJobHandlerTest 18, ImageLlmClientTest 36 —
  101 tests, 0 failures, 0 skipped.
- Accumulated: `export JAVA_HOME="$(asdf where java)" && ./gradlew check` = BUILD SUCCESSFUL in 17m12s;
  backend 107 suites / 1391 tests / 0 failures / 0 skipped, frontend `vitest --run` 10 files / 213 tests
  passed.
- `git diff --check` clean.

Residuals: no real external provider traffic was sent or needed — endpoints are RFC 2606 `example.invalid`
names answered by the injected engine, and the one client-level test without an injected engine (during the
classification proof) attempted only a DNS lookup of `vision.example.invalid`, which resolves nowhere.
Dispatch accounting is pinned, not a live provider answer; the reviewer profiles still resolve their key
variable `HOME` from the real environment (production `ocrReviewerFactory` default lookup) while the
transcription tests use the injected `RECORDED_TRANSPORT_KEY` lookup — the same credential-gate residual as
ticket 07d's record. Request formation, classification, permits and counters are what this slice proves;
provider answer quality stays tickets 06/11, browser acceptance stays 10a, and the real OCR / CoreML /
external-provider runtime gates stay 10b. No user-facing copy or behavior changed, so no user documentation
needed updating. Nothing was committed.

## Ticket 07e verification record

Ordinary extraction for non-image formats is preserved under **Check and improve**: `ImportPipeline.sinkFor(documentId, mode, pageImageSupport)` now takes the *selected* extractor's `pageImageSupport` (passed by `DocumentIngest.ingest` as `extractor.pageImageSupport`) and opens the candidate sink only when the mode is `CHECK_AND_IMPROVE` **and** the extractor reports `PageImageSupport.Supported`. Any other combination commits through the ordinary `sink`, so a format without page images publishes its units, chunks and index rows instead of stranding every page in `NEEDS_REVIEW`, and the staging review never runs for it — no page-image rebuild (`PageImage.ofProvenance`) and no image-provider request can happen on its behalf. `DocumentExtractor.pageImageSupport`'s KDoc records the new consumer. No migration (SchemaMigrator `SUPPORTED_VERSION` stays 26), no DTO, counter, approval or HTTP surface change, and the explicit rescan refusal (`RescanRefusalException.PAGE_IMAGES_UNSUPPORTED`) is untouched. The test extractors that stand in for page-image formats now declare `pageImageSupport = Supported`; the new `NonPageImageFormat` wrapper declares the opposite for the routing probe.

Red first, 2026-10-06 — the focused command with only the new tests in place, against compiling code and the
unmodified production path (`./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.extract.ExtractorRegistryTest' --tests 'infoscry.jobs.RescanJobHandlerTest'` = 77 tests, 3 failed):

- `a check-and-improve import of plain text publishes its ordinary extraction` and `a check-and-improve import of an office fixture publishes its ordinary extraction`: `the ordinary reading was stranded: NEEDS_REVIEW null null => expected: <COMPLETE> but was: <NEEDS_REVIEW>`.
- `the sink follows the selected extractor's page image support, not the mode alone`: `an image-provider request was made for a format that reports no page images => expected: <0> but was: <1>` — the reviewer was reached, which also means the raster provenance had already been rebuilt before the fix.
- The three pins were green in the same run: the registry classification test, the rescan-refusal test, and `a supported page with no direct baseline stays pending instead of publishing as ordinary text`.

Infrastructure, not evidence: three Kotlin-daemon OOM runs (`java.lang.OutOfMemoryError: GC overhead limit exceeded` in `:compileKotlin`, twice before an authorized repair and once after it) — no test executed in any of them, so none is red evidence. With the owner's authorization the wedged `kotlin-compiler-embeddable` daemon was verified by command line and killed, and `./gradlew --stop` run before each subsequent Gradle invocation (the daemon wedged after roughly two compile passes each time). Two positional-argument `assertContains` calls in the new tests were corrected to the repository's named `message =` form. No `gradle.properties` or dependency change was made.

Green gates, 2026-10-06:

- Focused (the ticket's exact command plus the two suites that share its test extractors):
  `export JAVA_HOME="$(asdf where java)" && ./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.extract.ExtractorRegistryTest' --tests 'infoscry.jobs.RescanJobHandlerTest' --tests 'infoscry.jobs.RetryJobHandlerTest'`
  = BUILD SUCCESSFUL; ImportJobHandlerTest 51, ExtractorRegistryTest 10, RescanJobHandlerTest 16,
  RetryJobHandlerTest 18 — 95 tests, 0 failures, 0 skipped.
- Accumulated: `export JAVA_HOME="$(asdf where java)" && ./gradlew check` = BUILD SUCCESSFUL in 17m04s;
  backend 107 suites / 1397 tests / 0 failures / 0 skipped, frontend `vitest --run` 10 files / 213 tests passed.
- `git diff --check` clean.

Coverage added: ImportJobHandlerTest pins scenarios 1, 2 and 4 — plain text and the committed `sample.docx`
fixture under an admitted check-and-improve snapshot publish ordinary text, chunks, index rows, an active
revision and no pending review with the candidate sink never opened; the unsupported-format probe reaches
neither the candidate sink nor the reviewer; and a supported page with no direct baseline stages `PENDING`
with nothing published, no review row and no reviewer request. ExtractorRegistryTest classifies every format
the production registry claims: `Supported` exactly for PDF and the picture types, `Unsupported` with
`PAGE_IMAGES_UNSUPPORTED` for everything else. RescanJobHandlerTest pins scenario 3's refusal: a preview of
an imported `text/plain` document answers `RescanRefusalException` with code `PAGE_IMAGES_UNSUPPORTED` and
the curated reason, while the document's published reading stays untouched; scenario 3's staging half stays
pinned by the pre-existing `a check-and-improve import records a pending review for a page its reading
disagrees with` and RescanJobHandlerTest's proposal tests, which the `Supported` declarations keep green.

Residuals: no user-facing copy changed — the spec already says other formats retain their extraction with a
clear unsupported rescan reason — so no user documentation needed updating; nothing was committed or staged.
A non-image document admitted under a collection whose OCR selection differs re-reads rather than reusing
another selection's checkpoints (the attempt identity and mode are in the fingerprint): safe, but a
redundant pass. The open gates are untouched by this slice: the 07b repair gate, browser acceptance (10a),
and the real OCR / CoreML / external-provider runtime gates (10b).

## Ticket 03b verification record

Root-confined, all-or-nothing image provenance is enforced at the persistence boundary. Migration 027
(`027_page_source_image_constraints.sql`) audits the rows an archive already holds — a partially present
core provenance (root/path/hash/render version), a half-measured image (width without height) or a
present-but-unusable reference (absolute, climbing out with `..`, cancelling its way out, naming the root
itself, or a digest that is not a SHA-256) has its six provenance columns cleared to NULL, with the page's
text, chunks and identity untouched and no hash guessed — then rebuilds `page_text_revisions` with
table-level CHECKs that make those rules inescapable for any connection, store or raw SQL. The domain
record `SourceImageProvenance` now refuses an absolute, `..`-escaping or root-naming reference at
construction (the same lexical rule `PageImage.resolveInside` applies when an artifact is opened), so a
store write cannot carry one, and `DocumentRevisionStore.toSourceImage` refuses to read a malformed row
back as absent provenance — an all-absent row remains the one honest answer for a page no image was
observed for. `SchemaMigrator.SUPPORTED_VERSION` is 27; no applied migration was edited.

Red first, 2026-10-06 — the focused command with only the new tests in place, against compiling code and
the unmodified production path
(`./gradlew test --tests 'infoscry.document.RevisionPublicationTest' --tests 'infoscry.storage.SchemaMigratorTest'`
= 35 tests, 4 failed):

- `aProvenanceReferenceThatEscapesItsNamedRootIsRejectedBeforePersistence`:
  `'/etc/passwd' was accepted as a source image reference. Expected an exception of class
  java.lang.IllegalArgumentException to be thrown, but was completed successfully with the result:
  <SourceImageProvenance(root=ARTIFACTS, relativePath=/etc/passwd, sha256=bbb…, width=1200, height=1600,
  renderVersion=1)>`.
- `raw sql cannot write half-present or root-escaping source image provenance`:
  `the schema must refuse: INSERT INTO page_text_revisions … 'ARTIFACTS', 'attempt/pages/page-000001.png',
  1 … Expected an exception of class java.sql.SQLException to be thrown, but was completed successfully`.
- `a malformed source image row cannot read back as absent provenance`:
  `Expected an exception of class java.lang.IllegalStateException to be thrown, but was completed
  successfully with the result: <RevisionPageText(… sourceImage=null)>` — the half-present row read back
  as a silent absence, which is exactly the lie the ticket forbids.
- The fourth failure in that first run was excluded as evidence: `a legacy archive's unusable source
  image provenance is cleared while its pages stay readable` failed on a fixture defect in the new test
  itself (`MissingFieldException: Field 'name' is required for type with serial name 'image'` — the
  seeded `{"type":"image"}` locator omitted the variant's required field). After correcting the fixture,
  `./gradlew test --tests 'infoscry.storage.SchemaMigratorTest'` = 14 tests, 3 failed, and the audit
  test showed its true red: `a demonstrably unusable legacy provenance survived the audit ==> expected:
  <0> but was: <2>`.

Green gates, 2026-10-06:

- Focused (the ticket's exact command plus the three suites that write provenance through the store):
  `export JAVA_HOME="$(asdf where java)" && ./gradlew test --tests 'infoscry.document.RevisionPublicationTest'
  --tests 'infoscry.storage.SchemaMigratorTest' --tests 'infoscry.ocr.OcrEngineContractTest'
  --tests 'infoscry.extract.ImageExtractorTest' --tests 'infoscry.extract.PdfExtractorTest'`
  = BUILD SUCCESSFUL; RevisionPublicationTest 21, SchemaMigratorTest 14, OcrEngineContractTest 20,
  ImageExtractorTest 24, PdfExtractorTest 52 — 131 tests, 0 failures, 0 skipped. One intermediate
  focused attempt failed on a test-authoring syntax defect in the new direct-SQL test (`pageInsert(10,
  "", "")` emitted a double comma → `SQLiteException near ",": syntax error`); the helper was fixed to
  omit the empty column list, and the refusal assertions were strengthened to require
  `CHECK constraint failed` in the message so a syntax error can never pass as a schema refusal.
- Accumulated: `export JAVA_HOME="$(asdf where java)" && ./gradlew check` = BUILD SUCCESSFUL in 17m08s;
  backend 107 suites / 1402 tests / 0 failures / 0 skipped, frontend `vitest --run` 10 files / 213 tests
  passed.
- `git diff --check` clean.

Coverage: the five new tests pin the ticket's scenarios across the two boundaries the ticket names —
the domain/store path (an absolute reference, a `..` traversal, a cancelling reference and a root-naming
reference are refused at construction with nothing reaching persistence, and a staged page's provenance
round-trips under its own root with the two roots kept distinct) and the direct-SQL path (nine refused
raw inserts and two refused raw updates, each proven to fail on the CHECK rather than on syntax; the
018/019-upgrade audit clearing unusable provenance while all five pages stay readable and the complete,
confined rows survive byte for byte under their distinct managed-copy/artifact roots; and the
malformed-row read refusal at the schema version that could still hold such a row). SchemaMigrator
SUPPORTED_VERSION and the test literals that pin it moved 26 → 27 (`SchemaMigratorTest`,
`DefaultRetirementTest`).

Infrastructure incidents, separate from behavioral evidence: per the owner's mid-run guidance the
per-run `./gradlew --stop` + daemon-kill pre-step was cancelled once `~/.gradle/gradle.properties`
gained `kotlin.daemon.jvmargs=-Xmx6g`; exactly one `./gradlew --stop` was run before the first (red)
Gradle invocation and no live `kotlin-compiler-embeddable` daemon was found. No run after that change
hit a compile-daemon OOM, no gradle.properties or dependency change was made, and no test failure in
this ticket was an infrastructure failure.

Residuals: artifact lifetime is untouched — renders referenced by staged candidates staying alive is
ticket 03c, which was not started, and the as-is/derived distinct-root naming it depends on is pinned
here and in the pre-existing extractor/contract tests. Rendering, image-serving endpoints and artifact
cleanup are unchanged, as the ticket requires; no user-facing copy changed, so no user documentation
needed updating. The domain confinement rule is lexical, matching `PageImage.resolveInside`; an archive
whose rows satisfy the new CHECKs is exactly one whose references a page-image rebuild can open. No real
provider, browser, real-OCR or CoreML gate applies to this persistence-only slice and none is claimed.
Nothing was committed or staged.

## Ticket 03c verification record

Artifact lifetime is closed at the extraction record: `PdfExtractor` now records a `sourceImage` only for
the image it keeps. A check-and-improve render is written under `{fingerprint}/pages` and outlives the
attempt, so its provenance still opens after the attempt ends; a fill-missing render is working material —
deleted the moment its reading commits and its directory when the attempt ends (including on cancellation
or failure) — so it records no reference at all (`sourceImage = image.takeIf { readsEveryPage }?.
artifactProvenance(input.artifactRoot)`), and the unit's durable evidence stays its word boxes, which are
written beside the attempt's artifacts and survive. The class/`OcrResult`/call-site KDoc states the rule.
No other production file changed: `ImageExtractor` (managed original and bounded derived copy are both
kept, so their references were already resolvable) and `CandidateRevisionSink` (staging, adoption and
withdrawal never touch files) needed no fix and are byte-identical to the starting tree. No migration
(`SchemaMigrator.SUPPORTED_VERSION` stays 027), no DTO, HTTP, counter or user-facing copy change, no
artifact garbage collector, and no deletion path was touched — the diff removes a reference, never a file.
Reach of the defect, by inspection: `content_units` has no source-image columns and `StoredUnitsSink` does
not persist `ContentUnitDraft.sourceImage`, so no archived row ever held the dangling working reference —
it lived in the extraction record itself (whose only consumers, `CandidateRevisionSink.pageFor` and
`StagedPageReview.review`, are check-and-improve-only), which the fix makes honest at the source.

Red first, 2026-10-06 — the ticket's exact focused command with only the new tests in place, against
compiling code and the unmodified production path (`./gradlew test --tests
'infoscry.extract.PdfExtractorTest' --tests 'infoscry.extract.ImageExtractorTest' --tests
'infoscry.ocr.OcrEngineContractTest'` = 101 tests completed, 2 failed):

- `fill-missing finish leaves every recorded image reference resolvable and hash-matched`:
  `a unit records an image that is not there:
  ARTIFACTS/d82c86371c5985aad707fb462a039b92cf81307cd0194b6e13b24e0f556b94bf/working/page-000003.png`
  — the OCR unit of page three named the working render after the attempt had deleted it.
- The second failure of that first run was excluded as evidence, per the precedent of 03b: the new
  `a fill-missing render is working material and records no durable reference` failed on a test-authoring
  defect (`java.lang.IllegalArgumentException: Collection contains more than one matching element` — a
  `.single { method == OCR }` over the fixture's three OCR pages), not on the production behavior. After
  correcting the test to assert over every OCR unit, `./gradlew test --tests
  'infoscry.extract.PdfExtractorTest'` = 54 tests, 2 failed showed its true red:
  `fill-missing recorded a reference to its temporary render: SourceImageProvenance(root=ARTIFACTS,
  relativePath=d82c86…/working/page-000003.png, sha256=8874a7d8a4eb03f3ce3879a98354c98686309c344dd3dba8e4984
  6917addf28a, width=2480, height=3507, renderVersion=1) ==> expected: <null> but was: <…>`.
- The four scenario-2/scenario-3 tests were green in that same first run (101 completed, only the two
  above failed): the retention and distinct-root behavior needed no fix, so they are recorded as
  regression pins, following 07f's precedent for a slice whose guards already held.

Green gates, 2026-10-06:

- Focused (the ticket's exact command):
  `export JAVA_HOME="$(asdf where java)" && ./gradlew test --tests 'infoscry.extract.PdfExtractorTest'
  --tests 'infoscry.extract.ImageExtractorTest' --tests 'infoscry.ocr.OcrEngineContractTest'`
  = BUILD SUCCESSFUL; PdfExtractorTest 54, ImageExtractorTest 25, OcrEngineContractTest 22 —
  101 tests, 0 failures, 0 skipped.
- Accumulated: `export JAVA_HOME="$(asdf where java)" && ./gradlew check` = BUILD SUCCESSFUL in 17m09s;
  backend 107 suites / 1407 tests / 0 failures / 0 skipped, frontend `vitest --run` 10 files / 213 tests
  passed.
- `git diff --check` clean.

Coverage against the four acceptance scenarios:

- **Scenario 1** (red first, then green): `PdfExtractorTest` pins that a finished fill-missing extraction
  leaves every recorded reference resolvable and hash-matched — the engine writes real word-box artifact
  bytes in the test, and every `sourceImage` (if any) is resolved from its named root and hashed — and
  that the deliberately temporary render records no durable reference. The pre-existing expectation in
  `an ocred page's unit carries its word boxes and the confidence ocr reported` had pinned
  `${fingerprint}/working/page-000003.png` as the unit's durable image; it was flipped with the fix per
  this ticket's scenario (now `assertNull`, with a comment saying why), its word-box/confidence/method
  assertions untouched, and the same test now pins that the render itself sat under the attempt's `working`
  directory.
- **Scenario 2** (regression pins, green before and after): `OcrEngineContractTest` —
  `a cancelled attempt leaves the images its staged pages name on disk` (cancellation at page 4 leaves the
  pages staged before it resolvable and hash-matched under the document artifact root) and
  `a restart and a competing fill-missing attempt do not erase the images a staged candidate names` (a
  fresh sink adopts the candidate through `committedKeys`, the staged pages are skipped rather than
  re-rendered, and a fill-missing attempt under its own fingerprint — the retry shape — reads the scanned
  pages again and deletes only its own working renders; the candidate's images hash-match afterwards).
- **Scenario 3** (regression pin): `ImageExtractorTest` — `a managed original and a bounded derived image
  resolve under their own document roots` (the managed original resolves from the document directory and
  the bounded copy from the artifact root, each hash-matched, and neither reference resolves under the
  other's root). The existing `OcrEngineContractTest` staged-page tests already prove the same at the
  durable-row boundary.
- **Scenario 4**: satisfied by absence — no collector, no new deletion, and the production diff removes
  only a reference; `git diff --stat` over main code is the one `PdfExtractor.kt` hunk set shown above.

Infrastructure incidents, separate from behavioral evidence: none. No compile-daemon OOM occurred, no
`./gradlew --stop` or daemon kill was needed, no `gradle.properties` or dependency changed, and no test
failure in this ticket was an infrastructure failure.

Residuals: the "published history" half of scenario 2 is pinned at candidate level — a published
revision's pages are the same rows and files (publication changes the revision's state, not its pages'
references), and the full publish flow stays covered by `RevisionPublicationTest` inside the accumulated
gate, which passed. By inspection (not tested or changed here), the rescan path renders every attempt of a
document into one shared `documentArtifactRoot/rescan/pages` directory (`RescanJobHandler`
`RESCAN_PAGES_DIRECTORY`), so a later rescan at a different render DPI would overwrite bytes a published
historical page names — `RescanJobHandler`/`RescanService` are outside this ticket's file ownership, and
the question is flagged for the review-read/deletion slices (08d/09c) rather than silently changed here.
No user-facing copy changed, so no user documentation needed updating. No real provider, browser, real-OCR
or CoreML gate applies to this extraction-record slice and none is claimed. Nothing was committed or
staged.

## Ticket 02c verification record

Saved Ask evidence opens at its recorded revision, and a citation that names no revision shows its own
saved excerpt with an English revision-unknown label instead of a live-unit read. The slice is
client-side only: `web/src/routes/+page.svelte` replaces the AskPanel inline `onOpenSource` lambda with
`openAskEvidence(evidence)`, which builds the hit once and routes it — a recorded revision keeps the
existing `readSource(..., revisionId)` path (now shared through the new `prepareSourceSheet` preamble,
which bumps the source generation and clears the view), while a citation with **no** revision and a
saved excerpt goes to `openRevisionUnknownExcerpt(hit, excerpt)`, which performs no request and shows
the excerpt in the sheet under `Revision unknown — showing the excerpt saved with this answer, not the
document's current text.` A live citation, which carries no saved excerpt, still reads its unit,
because that unit is the reading it just came from. `web/src/lib/api.ts` carries only a corrected
`readSource` doc comment: no wire field, signature, HTTP route, DTO, schema or backend file changed (no
migration; SchemaMigrator SUPPORTED_VERSION stays 027). `docs/usage.md`'s Ask section now documents the
behavior for readers. The server already reports a recorded-but-deleted revision as absent on the
history route (`loadAskEvidence`'s `LEFT JOIN document_revisions`), so the unknown case is an honest
field on the existing DTO (`AskEvidence.revisionId` absent, `AskEvidence.excerpt` present —
`citations.snippet` is `NOT NULL`) and needed no backend work.

Red first, 2026-10-06 — the focused command with only the five new tests in place, against compiling
code and the unmodified production path (`cd web && npm test -- --run` = `Test Files 1 failed | 9
passed (10)`, `Tests 4 failed | 214 passed (218)`; baseline before any edit was 213 passed):

- `shows the saved excerpt for a citation that names no revision and reads no live unit`:
  `TestingLibraryElementError: Unable to find an element with the text: Signed by Mira in 1998.. This
  could be because the text is broken up by multiple elements.` — the old path issued a live-unit read.
- `shows the saved excerpt when the unit the citation names no longer exists`:
  `Unable to find an element with the text: Signed by Mira in 1998.` — the live read hit the source
  route's 404 (the unit is gone) and showed an error alert instead of the saved excerpt.
- `renders a saved excerpt literally rather than interpreting its markup`:
  `Unable to find an element with the text: <script>unsafe()</script>.`
- `ignores a pending source response after the reader opens another citation`:
  `Unable to find an element with the text: Jo countersigned the deed.` — the second citation issued a
  second live read instead of the excerpt fallback.

Green in that same red run, recorded as regression pins per the 03c/07e precedent:
`keeps the citation's revision while paging through the source it opens` (214 = 213 + 1), plus the
pre-existing `opens stored Ask evidence at the revision the citation names` and `ignores a pending
source response after switching collections`.

A second red cycle covered the one gap the first green run exposed by inspection rather than by any
scenario: the fallback opened the modal sheet without moving keyboard focus into it. With only its
test in place, `cd web && npm test -- --run` = `Test Files 1 failed | 9 passed (10)`, `Tests 1 failed |
218 passed (219)` — `moves focus into the source sheet when a saved excerpt opens it` failed with
`AssertionError: expected false to be true // Object.is equality` (the dialog did not contain
`document.activeElement`, which stayed on the citation behind the backdrop). The fix is one line:
`openRevisionUnknownExcerpt` focuses the sheet after `tick()`, exactly as `openSource` does; the same
command then reported 219 passed.

Green gates, 2026-10-06:

- Focused (the ticket's exact UI commands, run as three invocations from `web/`; the counts below are
  the final run, after the focus fix): `npm test -- --run` = Test Files 10 passed (10), Tests 219
  passed (219); `npm run check` = `svelte-check found 0 errors and 0 warnings`; `npm run build` =
  `✓ built`, adapter-static `Wrote site to "build"  ✔ done`.
- Accumulated: two runs of
  `export JAVA_HOME="$(asdf where java)" && timeout 3000 ./gradlew check`, both BUILD SUCCESSFUL with
  zero failures. The first (17m 12s; backend 107 suites / 1407 tests / 0 failures / 0 errors /
  0 skipped counted from `build/test-results/test/*.xml`, frontend `:frontendTest` 10 files / 218
  tests) covered the tree before the focus addition; because production code then changed, the gate
  was re-run on the final tree — the authoritative run (17m 14s; backend 107 suites / 1407 tests /
  0 failures / 0 errors / 0 skipped, frontend 10 files / 219 tests).
- `git diff --check` clean (exit 0, no output), re-run after the documentation edits.

Coverage against the acceptance scenarios:

- **Scenario 1** (red → green): the two unknown-revision tests assert no `/sources/` request at all,
  the saved excerpt on screen with the `Revision unknown` label, no alert, and no trace of the live
  unit's text — the second test's source stub answers 404, which is what a removed unit looks like.
- **Scenario 2**: the initial pass (`revision=revision-8` on the first read) was already pinned before
  this slice; the new pagination pin shows `Load more` issuing
  `.../sources/unit-1?offset=11&limit=16384&revision=revision-8`, green before and after.
- **Scenario 3**: literal excerpt rendering red → green (text in the `<pre>`, no `script` element
  created); the collection-switch late-response test is pre-existing and green; the new
  source-selection test resolves the first citation's read after the reader opened the second and
  asserts the late page never appears, only one `/sources/` request was made, and the excerpt stays.
- Beyond the three scenarios, the modal focus contract is mirrored: `moves focus into the source sheet
  when a saved excerpt opens it` red → green as described above, and closing the fallback sheet
  restores focus to the opening citation through the opener the shared preamble already records.

Infrastructure incidents, separate from behavioral evidence: none. No compile-daemon OOM, no failed or
retried Gradle invocation (the two successful `./gradlew check` runs are disclosed above and both were
required — the second follows the last production change), no dependency, lockfile or gradle.properties
change, and every red above is a behavioral assertion failure against a compiling suite.

Residuals: a citation whose *recorded* revision resolves to nothing at read time (a revision deleted
between the history read and the click, or a published revision that holds no page for the unit) still
shows the server's not-found message in the sheet — the fallback is keyed on the history route's honest
"no revision" answer, which already covers a deleted recorded revision, and extending it to a
not-found response on a named revision would also mask transient read failures; deliberately not done
here. `openInvestigationSource` is untouched: stored Investigate evidence carries an excerpt but still
reads the live unit, because Investigate provenance is ticket 02d's slice. The sheet's "Open original"
link still points at the managed original in the fallback view and 404s if the document is gone
(existing behavior for every viewer state). The editor's advisory lint also reports the file-wide
`|---|` table-separator style (MD060) in STATUS.md and a `web/tsconfig.json rootDir` note — both
pre-existing, advisory-only, untouched files; `svelte-check` itself is clean. No browser acceptance
(10a) and no real OCR/CoreML/external-provider gate applies to this frontend-only slice and none is
claimed. Nothing was committed or staged.

## Ticket 02e verification record

The seal now covers the whole read boundary this ticket names, proven by a deterministic in-process
injection at both handoffs instead of the child-JVM kills the pre-existing recovery tests use. No
migration (SchemaMigrator SUPPORTED_VERSION stays 027), no DTO, counter or user-facing copy change, and
the `publish(..., observe: (PublicationStep) -> Unit)` seam is unchanged: the ticket's "deterministic
counter hook" is that callback counted per step — the first `STAGED_COMMITTED` belongs to the
publication, the second to the inline roll-forward that runs only because the authority already moved.
The production diff is two files: `RevisionSnapshotGate.requireUnsealed()` raises the same
`RevisionSnapshotUnavailableException` `acquire()` raises, for reads that do not take a lease, and
`SourceRoutes`' live-read path calls it, which maps through the existing `ApplicationCall.handle` clause
to the existing 503 `REVISION_SNAPSHOT_UNAVAILABLE`. A source read that names a revision is unchanged:
its text is that revision's immutable text, which the spec lets finish against its own revision.

Red first, 2026-10-07 — the ticket's exact focused command with only the new tests in place, against
compiling code and the unmodified production path
(`export JAVA_HOME="$(asdf where java)" && ./gradlew test --tests
'infoscry.document.RevisionPublicationRecoveryTest' --tests 'infoscry.server.SearchRoutesTest'`
= BUILD FAILED in 1m 21s; 35 tests completed, 1 failed):

- `while a publication cannot switch, search and the live source refuse instead of mixing readings`
  (SearchRoutesTest.kt:249): `{"id":"…","documentId":"…","ordinal":0,…,"text":"the replacement
  wording",…} ==> expected: <503 Service Unavailable> but was: <200 OK>` — with the publication failed
  at AUTHORITATIVE and again at the roll-forward's STAGED_COMMITTED, the live source route answered
  from SQLite the text the authority commit had already moved while search refused, which is exactly
  the "old index with new text" pairing the deliverable forbids.
- Green in that same red run, recorded as regression pins per the 03c/07e precedent for guards that
  already held: RevisionPublicationRecoveryTest's three additions (10/10) and SearchRoutesTest's other
  24 tests — the seal on the search side and the roll-forward's refusal to report failure both exist
  from ticket 02; the source half was the gap.
- Baseline before any edit, same command: BUILD SUCCESSFUL (Recovery 7, SearchRoutes 24, 0 failures).

Green gates, 2026-10-07:

- Focused (the ticket's exact command), re-run on the final tree after every edit:
  `export JAVA_HOME="$(asdf where java)" && ./gradlew test --tests
  'infoscry.document.RevisionPublicationRecoveryTest' --tests 'infoscry.server.SearchRoutesTest'`
  = BUILD SUCCESSFUL in 1m 22s; RevisionPublicationRecoveryTest 10, SearchRoutesTest 25 —
  35 tests, 0 failures, 0 skipped.
- Accumulated: `export JAVA_HOME="$(asdf where java)" && timeout 3000 ./gradlew check`
  = BUILD FAILED in 17m 22s; backend 107 suites / 1413 tests / 2 failures / 0 errors / 0 skipped,
  frontend `vitest --run` 10 files / 219 tests passed and the static build wrote its site. The only
  two failures are **pre-existing baseline failures outside this slice**, both in `SchemaMigratorTest`
  and both asserting ticket 02d's migration 028:
  `migration 028 preserves conversations and evidence and collection deletion still cascades`
  (`migration 028 must have been applied ==> expected: <28> but was: <27>`) and
  `the evidence ledger gains a revision column while its stored excerpts survive byte for byte`
  (`… expected: <[…, revision_id]> but was: <[…]>`).
- Attribution, proven rather than asserted: baseline commit `8dc876d` itself contains those ticket-02d
  tests (`git show HEAD:src/test/kotlin/infoscry/storage/SchemaMigratorTest.kt` names "Scenario 4 of
  ticket 02d" and "migration 028 must have been applied") while the same commit has no `028_*.sql`
  migration and `SchemaMigrator.SUPPORTED_VERSION = 27`; neither the test nor the migrator is touched
  by this slice (`git status` shows both unmodified). Reproduced on the pristine baseline: with every
  change of this slice stashed (`git stash push -m 02e-wip`), `./gradlew test --tests
  'infoscry.storage.SchemaMigratorTest'` = BUILD FAILED, 16 tests / the same 2 failures with identical
  messages (result XML written 08:41), then the stash was popped and the tree diff is byte-identical
  to before (7 files, 327 insertions, 8 deletions). Per the stop rule the full gate was not retried:
  the only failures are out of slice — 02d's migration lands in the main checkout — and an unchanged
  re-run would fail identically. Everything else in that accumulated run was green, including both
  02e suites (Recovery 10, SearchRoutes 25) and the frontend.
- `git diff --check` clean.

Coverage against the acceptance scenarios:

- **Scenario 1** — `theCounterHookFailsTheAuthorityCommitAndTheRollForwardsStagedCommit`: one publish
  through the counter hook observes exactly INTENT_PERSISTED ×1, ROWS_STAGED ×2, STAGED_COMMITTED ×2,
  AUTHORITATIVE ×1, and the attempt comes back as a success carrying its operation id, with a PREPARED
  intent whose `authoritative_at` is durable and which `unfinishedIntents()` still lists — a failure
  after the authority commit is never reported as a failure.
- **Scenario 2** — service side, `whileRecoveryCannotSwitchSearchAndSourceReadsAreRefusedInsteadOfMixed`:
  the lease and both searches throw `RevisionSnapshotUnavailableException` while the database already
  serves the replacement and the scope still hides the target (asserted), i.e. the mixture exists and
  is refused rather than served. Wire side, the SearchRoutesTest test: search and the live source both
  answer 503 `REVISION_SNAPSHOT_UNAVAILABLE`, the live response carries no replacement text, and the
  source read that names the base revision still answers 200 with the superseded text.
- **Scenario 3** — restart, `restartingAfterBothInjectedFailuresCompletesThePublicationAndReadsResume`:
  reopening the data directory reports `publicationRecovery.completed == [operation]`, leaves nothing
  unfinished, marks the intent PUBLISHED with its cleanup, and reads resume coherently on the
  authoritative revision (search serves only the replacement, the live unit says the same thing, the
  baseline stays readable at its own revision). In-process completion, the SearchRoutesTest half:
  `recoverUnfinished()` completes the same operation and both routes serve the replacement, with the
  replaced reading's search hits gone.

Infrastructure incidents, separate from behavioral evidence: the child worker session was killed by the
workflow's 30-minute timeout while the accumulated check was running; the check's client stdout died
with it, but the daemon completed that build at 08:35 and its results were recovered from the
test-result XMLs and the daemon log (both quoted above), and the run is counted as the one accumulated
gate. No compile-daemon OOM, no `gradle.properties` or dependency change, no `./gradlew --stop` or
daemon kill was needed, and no test failure recorded here was an infrastructure failure — the two
backend failures are behavioral and pre-date this slice.

Residuals: the seal is archive-wide and blunt while it is raised — every search and every live source
read refuses, including for documents unrelated to the failing publication — matching the search-side
behavior ticket 02 established and cleared by the next startup's recovery; Ask/Investigate live-unit
readers are not sealed here (revision-pinned citations are coherent by construction, and Investigate's
live-unit dependence is ticket 02d's slice); the baseline `SchemaMigratorTest` red blocks a green
accumulated gate on this branch until 02d's migration 028 lands, which is recorded above rather than
hidden; no user-facing copy changed (the 503 body is the pre-existing message), so no user
documentation needed updating. No browser acceptance (10a) and no real OCR/CoreML/external-provider
gate applies to this backend slice and none is claimed.
