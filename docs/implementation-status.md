# InfoScry implementation status

**Last updated:** 2026-09-21 — paused mid-Phase 3 (`Task 18` is a partial commit).
**Purpose:** a durable, versioned summary so a fresh session can resume without the SDD scratch directory.
The working record — per-task reports, review packages, and every controller ruling — lives in the
git-ignored `.superpowers/sdd/2026-09-20-infoscry-implementation/progress.md`; this file is the durable
condensation of it. Keep it current as tasks land.

Planning documents:

- Design spec: `docs/superpowers/specs/2026-09-20-infoscry-design.md`
- Implementation plan (28 tasks, phases, gates, Review Focus): `docs/superpowers/plans/2026-09-20-infoscry-implementation.md`

## Where the work stands

| Tasks | State |
|---|---|
| 1–17 | **Complete, each independently reviewed, pushed.** Phase 1 and Phase 2 gates passed. |
| 18 | **Recovery in progress under `docs/superpowers/plans/2026-09-21-infoscry-task-18-recovery.md`.** Stages 1–3 and 5 are done and the gate is green; Stage 4 was partially attempted and its two tests were disabled by operator decision; Stage 6 (the task review) is the remaining step, so Task 18 is **not yet reviewed**. Verified state of the tree: `./gradlew check` builds successfully with **602 tests, 0 failures, 2 skipped**, and `search --collection Default --json test` prints valid empty JSON. |
| 19–28 | Not started. |

**Resumed 2026-09-21/22; now at Task 18 Stage 6.** Stages 1–3 and 5 are done and pushed, the gate is green, and
Stage 6 (the task review over `534fad3..HEAD`) is the only step left for Task 18. Stage 4's two maintenance tests
are `@Disabled` by operator decision — see the Stage 4 row for the fixture deadlock that blocks them.

### Task 18 recovery progress

| Stage | State |
|---|---|
| 1. Make the test source compile | **Done** `5122893` — test-only change; the 61 compiler errors reduced to three real ones (manual `componentN` members on a data-class fixture, a missing `AppPaths` import, a `suspend` fixture), the rest were cascades. Product code untouched, and the product was right in both test/product disagreements it surfaced. |
| 2. Make the generation content exact | **Done** `0d11bcc` — a rebuilt generation now publishes only the live document set: the copy-based path prunes documents that are no longer in SQLite, `validate` checks every collection's live-ID set rather than row counts, and new stale-row tests fail through **production validation** when the pruning is disabled. |
| 3. Prove the marker and crash protocol | **Done** `294ff2c` — three properties pinned: startup sweeps an unpublished `lucene-next-*`, a retired generation and the temporary `current.tmp` while never removing the generation the marker names (even when that name still spells `next`); kill-before/after-swap restart selects exactly the generation the marker names; and the swapped-in generation holds **exactly the database's live document set** (`liveDocumentIds == storedDocumentIds`), which is Review Focus 10's core claim. **Recorded gap:** the lease property — a reader that leased the old generation finishing before it is retired — is **implemented but unproven**: `LuceneIndex.drainLeases` exists (kotlin:303, with `openLeases`) and `ReindexService` calls it (kotlin:246, `RETIRE_TIMEOUT_MILLIS`), but no test exercises it, and writing one needs a way to hold a reader inside the old generation while the swap publishes. Stage 6's protocol verifier owns proving it or escalating a small Stage 3 addendum. Also deferred: the new sweep test's reliance on the seeded documents' ordinal order (`list[0]`/`list[1]`) is a fixture artefact worth pinning. **Record correction:** this stage's report claimed three green commands, but the committed test file did not compile (an undefined `collectionA` in the marked-generation test). The controller found that when Stage 4 was dispatched, repaired it in `8dacd3d` by dropping the redundant search assertion the helper already makes, and verified `compileTestKotlin` plus the class run. Treat the stage reports on this task as claims to verify, not as evidence. |
| 4. Prove exclusive maintenance and job restart | **Blocked and its two tests are `@Disabled` by operator decision.** Attempt 1 finished chunking twice in the *shared* seeding helper, which throws during seeding and regressed another test — reverted. Attempt 2 used a single `finishChunking` with a deliberately stale `tokenizerId` **only for these two tests**, which worked: the rebuild now reaches the blocking embedder (that fix is not committed; re-derive it from the ledger entry "Task 18: Stage 4 attempt 2"). It then exposed the real blocker: **`BlockingDocumentEmbedder` blocks inside the reindex coroutine**, so the rebuild never yields and neither test can ever observe maintenance being held. Fixing the harness — block off the reindex dispatcher, or drive the rebuild from a separate coroutine and release it from the test thread — is the prerequisite for re-enabling them. **Consequence to remember: exclusive maintenance currently has no test.** |
| 5. HTTP and CLI boundary tests | **Done** `f4fc518` — `SearchRoutesTest` (11 tests: mandatory collection 400, unknown-collection 404, cross-collection unit 404, no full document text in a response, stable empty JSON, over-long query 400 `QUERY_TOO_LONG`, hybrid-without-model 503 `MODEL_NOT_INSTALLED` with the remedy, reindex 202) plus `SearchCommandTest` and `ReindexCommandTest` driving real child processes in both modes, including `--wait --json` reporting a terminal job result while keeping ownership. It also fixed a product defect: `SearchCommand` printed a raw stack trace for a refused search and now prints the code and remedy. |
| 6. Gate, review, commit | **Gate run done:** `./gradlew check` green (602 tests, 0 failures, 2 skipped) and the CLI smoke prints `{"hits":[],"staleFiltered":0}` with CoreML loaded (557/637 nodes). **The task review over `534fad3..HEAD` is the remaining step**, together with the two unproven items: the lease property (below) and the fact that the two disabled tests mean exclusive maintenance has no test. |

### Task 18 WIP contents

Present in the WIP and partially exercised: `search/ReindexService`, `jobs/ReindexJobHandler`,
`server/SearchRoutes`, `cli/SearchCommand`, `cli/ReindexCommand`, `cli/JobWaiting`, migration
`db/migration/003_job_types.sql` (a second job type — the migration Task 3's review predicted), plus edits in
`search/LuceneIndex`, `search/SearchService`, `AppContext`, `server/Routes`, `jobs/ImportJobHandler`,
`jobs/JobHandler`, `storage/SchemaMigrator`, `domain/Models` and three test files. No part of it is reviewed
or gated yet.

The design direction the WIP chose: Lucene 10.4 cannot read vectors back, so instead of an API that reuses
vectors between index generations, decide **per document** whether the carried-forward content is already
authoritative (marker + row counts) and otherwise delete and rebuild that document's entries. The plan permits
"carry forward **or reconstruct**".

## Resume checklist, in order

1. **Stage 4** — prove exclusive maintenance and job restart: with a blocking fake embedder (seeded as above),
   an import and a collection deletion during the build must be refused (423 / `MaintenanceInProgressException`)
   and accepted afterwards; then prove the `REINDEX` migration from an older schema version, claim, process
   death, `resetInterrupted`, resume with the payload's collection ID, and that a stale publisher refuses to
   replace a newer current generation.
2. **Stage 5** — add the HTTP and CLI boundary tests: GET/POST search, reindex and content-unit; mandatory
   collection; cross-collection 404; no full documents in a search response; stable empty JSON; **4xx for
   caller-fixable input and 503 for model/GPU/index environment failures**, with no sensitive text in logs or
   errors; and CLI behaviour in both server and foreground mode, including `--wait --json` reporting a terminal
   job result while holding process ownership.
3. **Stage 6** — run the gate (`compileTestKotlin`, the focused reindex and jobs tests, `./gradlew check`, the
   CLI smoke `search --collection Default --json test`, `git diff --check`), verify the diff against Task 18's
   six protocol points — including the **lease property**, which is implemented (`LuceneIndex.drainLeases`,
   called by `ReindexService`) but has no test: proving it needs a way to hold a reader inside the old
   generation while the swap publishes, and if no such seam exists the verifier escalates it as a small Stage 3
   addendum — task-review Task 18 over `534fad3..HEAD`, update this file with the real gate status,
   and only then continue to Task 19.


Review depth from here, per the partner's split: **full per-task review for Tasks 19–22** (the LLM chain:
profiles, streaming clients, Ask's budget and citation validation, Investigate's bounded tool loop) and
**lighter review for Tasks 23–28** (UI, docs, CI, packaging) — implementer plus the task's own gate, with the
single broad whole-branch review after Task 28.

## Rulings that bind the remaining work

- **v1 runs on macOS arm64 with CoreML only.** The Linux x86_64/NVIDIA CUDA target was dropped on 2026-09-21
  because no NVIDIA hardware exists to validate it, so **no CUDA path is implemented at all** — no
  `onnxruntime_gpu` dependency, no `model_O4.onnx`, no CUDA registration. Linux remains a non-GPU portability
  job in CI that cannot authorize a release.
- **Chunk character offsets address the unit's search text**, the single offset space. `extractedText` is
  byte-faithful evidence; **Task 24 must locate a highlighted span inside the stored `searchText`** and never
  carry an offset into `extractedText`.
- **A flow that completes with no `Finished` event is a terminal `FAILED`** with a persisted warning — never
  `COMPLETE_WITH_WARNINGS` — and no unit count is required. Two refusal keys exist
  (`HtmlExtractor.OVERSIZED_KEY = "document-too-large"`, `DocumentExtractor.DOCUMENT_REFUSED_KEY = "document"`).
- **The extraction fingerprint covers** the document hash, extractor schema version, OCR languages, the probed
  OCR tool version, the render DPI and the probed e-book tool version — so a tool upgrade forces a new
  fingerprint rather than silently reusing checkpoints.
- **An absent model is `MODEL_NOT_INSTALLED`** (remedy: `./gradlew embeddingModel`); a model present without a
  usable GPU is `GPU_UNAVAILABLE`. Embedding never falls back to CPU-only inference. Chunking may use the
  self-naming provisional counter when the model is absent, because chunk metadata records which tokenizer built
  the chunks and `needsChunking` keys on it.
- **Client-input refusals are 4xx, never 500**: `QUERY_TOO_LONG`, `FILTER_TOO_BROAD`, and the wildcard/fuzzy
  rewrite residual. The mandatory collection belongs at the route/CLI boundary, not in `SearchService`.
- **Logging convention:** redaction is by named field only, so never interpolate a document excerpt, question,
  path, filename or credential into a log message — pass it as a named field.
- **No model weights are committed.** `models/embedding-model.json` pins the revision, artifacts and expected
  SHA-256 values; `ModelManager` installs into the data directory's `models/`.

## Working environment

- Java 25 comes from asdf (`temurin-25.0.4+101.0.LTS`, pinned in `.tool-versions`). The asdf layout has no
  `Contents/Home`, so **run every Gradle command as** `JAVA_HOME=$(asdf where java) ./gradlew …`.
- The embedding model (~1.1 GB) is installed in the default data directory (`~/.infoscry`). Redirect both the
  `embeddingModel` task and `gpuIntegrationTest` with `-PdataDir=<dir>`.
- Test tags: `external` (real Tesseract), `model`/`gpu` (real CoreML). The default suite excludes them; run them
  with `./gradlew externalTest` and `./gradlew gpuIntegrationTest`. The GPU test **fails rather than skips**
  when the hardware or provider is unavailable.
- The suite is ~586 `@Test` methods and the gate takes a few minutes; one archive-scale search test alone costs
  ~30 s because Task 16's per-document idempotent writer has no batch entry point.

## Known deferred items

Each task's review recorded deferred minors in the ledger; the whole-branch review after Task 28 must triage
them. The recurring ones: test-only seams left in production code; a handful of files missing trailing
newlines; `doctor` and the operator docs still to name several surfaces (`embeddingModel`, `ModelInstaller`,
`-PdataDir`, the profiling directory, the DOCTYPE refusal for EPUB 2 books, the 15-minute Calibre permit hold);
and calibration-style judgement calls (the 50 000-chunk per-document index ceiling, the 1 000-token query bound,
the 8-pictures-per-e-book-chapter bound).