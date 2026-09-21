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
| 18 | **Recovery in progress under `docs/superpowers/plans/2026-09-21-infoscry-task-18-recovery.md`.** Stages 1–2 of 6 done (`5122893`, `0d11bcc`); Stages 3–6 remain. The recovery plan's Stage 6 owns the gate run and the task review, so Task 18 is **not yet reviewed**. |
| 19–28 | Not started. |

### Task 18 recovery progress

| Stage | State |
|---|---|
| 1. Make the test source compile | **Done** `5122893` — test-only change; the 61 compiler errors reduced to three real ones (manual `componentN` members on a data-class fixture, a missing `AppPaths` import, a `suspend` fixture), the rest were cascades. Product code untouched, and the product was right in both test/product disagreements it surfaced. |
| 2. Make the generation content exact | **Done** `0d11bcc` — a rebuilt generation now publishes only the live document set: the copy-based path prunes documents that are no longer in SQLite, `validate` checks every collection's live-ID set rather than row counts, and new stale-row tests fail through **production validation** when the pruning is disabled. |
| 3. Prove the marker and crash protocol | Not started. The kill tests already pass from the WIP (real child-process death at `GENERATION_BUILT`, `BEFORE_MARKER_SWAP`, `AFTER_MARKER_SWAP` behaves correctly), but Stages 3's fuller sweep/lease assertions are not written. |
| 4. Prove exclusive maintenance and job restart | Not started. **Seeded diagnostic:** the two maintenance tests time out after 60 s because a fully-copied document never reaches the embedder; Stage 4 must seed a document with an **outdated chunking marker** (a different `tokenizerId`) so `needsChunking` is true and the blocking embedder is reached. Stage 2 also made `validate` check every collection, so the seeded document needs agreed rows for a narrowed rebuild. |
| 5. HTTP and CLI boundary tests | Not started — no `SearchRoutesTest`, `SearchCommandTest` or `ReindexCommandTest` exists. |
| 6. Gate, review, commit | Not started. `ReindexResult` gained `staleDocuments: Int = 0` (the `--json` shape is unchanged; Stage 5 may surface it). The test class takes ~2 m 4 s dominated by the two 60 s timeouts, so it should drop well under a minute once Stage 4's seed is fixed. |

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

1. **Stage 3** — prove the marker and crash protocol: stop the child at each `ReindexStep`, restart, and assert
   `index/current` selects exactly the named generation; unpublised `lucene-next-*`, old generations and the
   temporary marker are swept at startup while the marked generation is never removed; and a reader holding a
   lease on the old generation can finish before it is closed.
2. **Stage 4** — prove exclusive maintenance and job restart: with a blocking fake embedder (seeded as above),
   an import and a collection deletion during the build must be refused (423 / `MaintenanceInProgressException`)
   and accepted afterwards; then prove the `REINDEX` migration from an older schema version, claim, process
   death, `resetInterrupted`, resume with the payload's collection ID, and that a stale publisher refuses to
   replace a newer current generation.
3. **Stage 5** — add the HTTP and CLI boundary tests: GET/POST search, reindex and content-unit; mandatory
   collection; cross-collection 404; no full documents in a search response; stable empty JSON; **4xx for
   caller-fixable input and 503 for model/GPU/index environment failures**, with no sensitive text in logs or
   errors; and CLI behaviour in both server and foreground mode, including `--wait --json` reporting a terminal
   job result while holding process ownership.
4. **Stage 6** — run the gate (`compileTestKotlin`, the focused reindex and jobs tests, `./gradlew check`, the
   CLI smoke `search --collection Default --json test`, `git diff --check`), verify the diff against Task 18's
   six protocol points, task-review Task 18 over `534fad3..HEAD`, update this file with the real gate status,
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