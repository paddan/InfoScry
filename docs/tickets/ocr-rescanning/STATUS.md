# OCR and rescanning implementation plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation or superpowers:subagent-driven-development if the user selects delegated execution. Steps use unchecked criteria; these are requirements, not completion evidence.

**Goal:** Select OCR engines, compare rescans with published text, review differences and publish reversible document revisions safely.

**Architecture:** Persist OCR profiles and immutable attempt snapshots in SQLite. Stage text revisions independently, run pluggable page-image OCR and review, and publish coherent text/index revisions through a recoverable boundary. Keep normal imports and Retry compatible.

**Tech Stack:** Kotlin/JVM 25, SQLite, Lucene, TypeScript/SvelteKit; bounded local Surya child process and image-capable LLM endpoints.

**Spec:** [2026-09-30 OCR design](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts:** [Shared interfaces, constraints and test gates](CONTRACTS.md).

Product decisions and documentation were authorized on 2026-09-30. Tickets 01, 02, 03, 04, 05 and 06 are
implemented and verified and ticket 07 is partially implemented (its rescan path is verified; its import half
and three admission/concurrency gaps are [07b](07b-import-execution-and-admission.md)); tickets 02b, 03b and
08-11 have not started. Ticket decomposition and technical
defaults are engineering proposals within that scope, not verified product behavior. No implementation,
installation, model download, private-data experiment, commit or push is implicitly authorized by this
document.

## Global constraints

- Kotlin/JVM 25; one application process and one Gradle backend module; TypeScript/SvelteKit static frontend.
- SQLite authority, rebuildable Lucene; immutable originals and stable content-unit IDs.
- GPU embeddings through CoreML on macOS arm64; no CPU embedding fallback.
- Bind only to 127.0.0.1; existing bearer/CSRF, mutation admission and deletion guards apply.
- Secrets only from environment variables; no source data or credentials in logs/errors.
- Preserve pinned dependencies and unrelated changes; English restrained accessible UI.
- Pilot mode first. Automatic replacement stays disabled until measured thresholds are explicitly accepted and met.

## Dependency order

| Ticket | Blocked by | State |
|---|---|---|
| [01 — OCR profiles and immutable attempt settings](01-profiles-and-settings.md) | None | Done — migration 017; focused suite green (67 tests: `OcrSettingsTest` 19, `OcrProfileRoutesTest` 10, `CollectionRoutesTest` 29, `SchemaMigratorTest` 9) and `./gradlew check` green (13m45s); legacy archives and legacy fingerprints unchanged |
| [02 — Isolated revisions and recoverable publication](02-revision-publication.md) | 01 | Done — migration 018 (document/page revisions, revision chunks, publication intents, `citations.revision_id`) and the PREPARED → staged commit → authority → snapshot → PUBLISHED protocol with roll-forward/roll-back recovery; `./gradlew check` green (97 suites, 1133 tests, 0 failures, 14m) plus the focused suites; three independent review rounds drove the untagged-baseline, reader-acquire race, candidate-leak, unadmitted-cleanup, post-authority-failure, draft-source-route and recovery-window fixes |
| [02b — Revision-aware saved evidence](02b-revision-aware-evidence.md) | 02 | Not started — carries ticket 02's evidence tail: the Ask viewer's revision-unknown fallback, the Investigate ledger's excerpt/revision columns, and the pre-switch seal test |
| [03 — Page-image OCR seam and Tesseract compatibility](03-ocr-engine-contract.md) | 01, 02 | Done — `PageOcrEngine`/`PageImage`/`OcrPageResult`, `PageImageRenderer`, Tesseract behind the seam and the FILL_MISSING / CHECK_AND_IMPROVE / rescan branch, with legacy fill-missing behavior and the pinned legacy fingerprints unchanged; `./gradlew check` green (98 suites, 1176 tests, 0 failures, 14m) plus the focused suites; four review rounds drove the bounded-raster, overflow, over-cap-picture, multipage-blank, candidate-key and fingerprint-collision fixes |
| [03b — Image provenance and artifact lifetime](03b-image-provenance.md) | 03 | Not started — carries what ticket 03's reviews left open: root-confined provenance enforced at the store and schema, no half-populated rows, and renders that staged candidates reference kept alive |
| [04 — Local Surya adapter and Mac runtime proof](04-surya.md) | 03 | Done — `SuryaOcr` + `scripts/ocr/surya_worker.py` (bounded versioned JSON, one inference manager per worker, `--identity` probe, process-tree teardown) with the real runtime measured on this Mac (surya-ocr 0.22.1, llama.cpp 0.5.0 build 11146, weights 1.36 GiB; cold ~5.2 s / warm ~1.8 s per page); `./gradlew check --rerun-tasks` green (99 suites, 1197 tests, 0 failures, 14m43s), `externalTest --tests SuryaRealToolTest` green, `python3 scripts/ocr/test_surya_worker.py` ok; three review rounds drove whole-tree teardown, `OCR_EMPTY` failures instead of committed empty text, the runtime identity probe in the fingerprint, and `NEEDS_TOOL` remedies in the import queue |
| [05 — Image-based LLM transcription and profile capability](05-image-llm.md) | 03 | Done — `ImageLlmClient` speaks both image protocols from the page's own bytes (hash-checked, format-checked, `max_tokens`-bounded, response-size bounded, per-attempt timeout, bounded retries on 429/5xx only, redirects refused unfollowed, whole request budgeted against the window and reserved output, resolved model version carried back) and refuses a non-local destination without ticket 07's permit validator; `LlmOcr` reads only through the snapshotted revision and the shipped prompt version; `prompts/ocr-transcription.txt` v1 ships with the transcription schema; `POST /api/ocr/profiles/{profileId}/probe` sends only the synthetic image and records `imageCapabilityMeasured`/`imageCapabilityCheckedAt`; focused suites green with both key invariants proven by guard-revert runs, and `./gradlew check` green (102 suites, 1268 tests, 0 failures, 15m55s); a security round closed the credentialed-endpoint hole across the OCR, LLM and catalog validators and remediated stored ones with migration 020 (credential removed, repaired conversation snapshots marked, repaired profiles switched off), and made "a switched-off profile is not a dispatch destination" hold at the routes, the CLI and the services that dispatch; a migration delimiter bug the parent found (`COALESCE` order, which would have rewritten `https://host?email=a@b/c` into `https://b/c`) was fixed and is covered by regression cases. Unverified: no real provider gate (ticket 10), and the engine is deliberately not wired into `ExtractorRegistry` — that is ticket 07's admission path (external-page accounting, previews, approval) |
| [06 — Image-grounded comparison and pilot decisions](06-comparison.md) | 03, 05 | Not started |
| [06 — Image-grounded comparison and pilot decisions](06-comparison.md) | 03, 05 | Done — deterministic diagnostics, the side-neutral A/B review call (prompt v2) with its answer mapped to the application's vocabulary in code, pilot mode enforced server-side so every difference is `PROPOSE`, durable reviews keyed by baseline/candidate/reviewer/prompt/policy, identical-nonblank no-ops, empty pairs pending and unsearchable, and failures that keep the baseline with zero re-transcription; `./gradlew check` green (104 suites, 1313 tests, 0 failures, 15m53s); four review rounds made approval structural (a policy that holds the review store, whose acceptance can only be resolved from a live row) and verified baselines against the stored revision page before every shortcut |
| [07 — Rescan jobs, import modes and external approval](07-jobs-and-admission.md) | 04, 05, 06 | Partially implemented — the rescan path is verified: `RESCAN` job type and operation record (migration 023), preview/admission with request-id idempotency, per-page checkpoints and bounded cancellation, external-page accounting, `AWAITING_APPROVAL`, comparison and review wiring, decisions and publication, and operation ownership that holds a document while an operation is unfinished or awaiting review (migration 024 adds review-pending imports); `./gradlew check` green after three hand repairs (106 suites, 1340 tests, 0 failures, 16m27s). The import half executes none of what it admits, admission does not revalidate the whole settings snapshot, and resume/approval can start a second attempt — all carried by 07b |
| [07b — Import execution, admission revalidation and attempt claiming](07b-import-execution-and-admission.md) | 07 | Not started — the import path must execute its admitted OCR mode, review and job-owned external approval; admission must revalidate the whole settings snapshot; and an attempt must be claimed so resume/approval cannot double-enqueue |
| [08 — Admin profiles, collection controls and page review](08-admin-and-review.md) | 07 | Not started |
| [09 — Revision history and explicit restoration](09-history-and-restore.md) | 08 | Not started |
| [10 — Integrated browser and real-runtime acceptance](10-integrated-acceptance.md) | 09 | Not started |
| [11 — Measured pilot and guarded automatic replacement](11-pilot-and-auto-activation.md) | 10 | Not started |

Work only on tickets whose dependencies have verified completion. Tickets 04 and
05 can be worked separately after 03, but integration files overlap; do not infer
permission for parallel agent edits. Ticket 02 is a correctness gate, not optional
refactoring; [02b](02b-revision-aware-evidence.md) carries its evidence-provenance tail and
is not a prerequisite for ticket 03. [03b](03b-image-provenance.md) carries the per-page image-provenance
and artifact-lifetime tail of ticket 03 and blocks nothing before ticket 06, which is the first slice that
has to compare and show that image. Ticket 10 delivers usable manual/pilot functionality; 11 has an
explicit evidence-and-user-acceptance gate before automatic activation.

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

No application behavior beyond tickets 01 and 02 has changed. Ticket 02b and tickets 03-11 remain
unchecked: no rescan, review, Surya, local image-model, browser, GPU or quality-evaluation gate has been
run for this feature. Existing Ask/source-viewer and Collections manual gates remain open.

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
revision-unknown excerpt fallback, the Investigate evidence ledger's missing excerpt/revision columns, and
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
