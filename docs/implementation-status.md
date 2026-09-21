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
| 18 | **Partial commit** `3363e76` — gates never run, review pending, test code does not compile. |
| 19–28 | Not started. |

Verified at the pause (checked against git and the compiler, not against agent reports):

- `main` compiles (`./gradlew compileKotlin` clean).
- `./gradlew compileTestKotlin` **fails**; every error is in
  `src/test/kotlin/infoscry/search/ReindexRecoveryTest.kt` (~lines 85–111), written against a misremembered API:
  ambiguous `SeededArchive` destructuring, a **private** `LuceneIndex.use`, unresolved `index`, and wrong
  argument types (`AppContext` expected; `String`→`Double`; `Int`→`Double`; `Any`→`CollectionId`).
- Four implementer attempts on Task 18 died on provider errors mid-run; their accumulated WIP is what
  `3363e76` contains. None of it has been reviewed or gated.

### Task 18 WIP contents (unverified)

Present and plausibly complete: `search/ReindexService`, `jobs/ReindexJobHandler`, `server/SearchRoutes`,
`cli/SearchCommand`, `cli/ReindexCommand`, `cli/JobWaiting`, migration
`db/migration/003_job_types.sql` (a second job type — the migration Task 3's review predicted), plus edits in
`search/LuceneIndex`, `search/SearchService`, `AppContext`, `server/Routes`, `jobs/ImportJobHandler`,
`jobs/JobHandler`, `storage/SchemaMigrator`, `domain/Models` and three test files.

The design direction the last attempt died on: Lucene 10.4 cannot read vectors back, so instead of an API that
reuses vectors between index generations, decide **per document** whether the carried-forward content is
already authoritative (marker + row counts) and otherwise delete and rebuild that document's entries. The plan
permits "carry forward **or reconstruct**", but the choice must satisfy six protocol points recorded in the
ledger ruling "Task 18: Ruling (WIP handover, partner choice 1A)": per-document idempotency; stable content-unit
IDs; the one-index rule (never a collection-only index); the `index/current` marker protocol; the exclusive
maintenance protocol; and no live writer publishing during a rebuild.

## Resume checklist, in order

1. **Make the test code compile.** Fix `ReindexRecoveryTest.kt` against the real `AppContext`/`LuceneIndex`/
   `ContentStore` API. Confirm with `./gradlew compileTestKotlin`.
2. **Review the whole WIP against the brief** — nothing in `3363e76` is trusted. Generate the brief with the SDD
   script (`bash <superpowers>/skills/subagent-driven-development/scripts/task-brief docs/superpowers/plans/2026-09-20-infoscry-implementation.md 18`).
3. **Write the missing Review Focus tests**: child-process kills before and after the marker swap; competing
   import and deletion during a rebuild; the all-collections assertion; and the route contract tests
   (collection mandatory at the route/CLI boundary; 4xx for `QUERY_TOO_LONG`, `FILTER_TOO_BROAD` and the
   wildcard/fuzzy rewrite residual; a caller can tell which hybrid branch failed).
4. **Run the Phase 3 gate**: `./gradlew check` and
   `./gradlew run --args='search --collection Default --json test'` (must return valid JSON even with zero hits).
5. **Task-review Task 18** (`review-package` from `534fad3` to `HEAD`), then continue with Task 19.

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