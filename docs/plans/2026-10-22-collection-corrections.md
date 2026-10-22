# Collection Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A standing, per-collection list of corrections (`spinners → Spinnars`) that rewrites misread words
in every document's published text now and on every later import, rescan and restore, while the uncorrected
reading is kept so the text always follows the list.

**Architecture:**
- **Pure core** (`infoscry.correction`): rule normalization and fingerprint, a Unicode-aware matcher that
  returns edits, an offset map, and a page corrector that rewrites a page's text, structure and layout.
- **One seam for staged text.** `CandidateCorrector` corrects the pages of a staged candidate revision
  before it is chunked. Rescans, check-and-improve imports, restores and the `CORRECTIONS` job all go
  through it; plain imports correct each `ContentUnitDraft` in `StoredUnitsSink` before commit.
- **Storage.** Corrected pages keep their reading in `reading_extracted_text`/`reading_search_text`
  (page revisions and content units). Revisions record `corrections_fingerprint` and `corrections_applied`.
  Publication, chunking, embeddings, Lucene and history are the existing machinery, unchanged.

**Tech Stack:** Kotlin/JVM 25, SQLite (xerial JDBC), ICU4J (already on the classpath through
`lucene-analysis-icu`; no new dependency), Ktor, Clikt, SvelteKit/TypeScript, Vitest, Playwright.

**Spec:** `docs/specs/2026-10-22-collection-corrections-design.md` (read it first; it is the authority on
behaviour).

## Global Constraints

- `find`/`replace`: NFC, trimmed, inner whitespace collapsed to one space; at most `200` characters each;
  `find` begins and ends with a letter, mark or digit; `find != replace`; blank values refused
  (`400 INVALID_REQUEST`). A taken `find` answers `409 CORRECTION_FIND_TAKEN`.
- Word boundary: text start/end, or a neighbouring code point that is not `L*`, `M*` or `Nd`.
- Case folding is Unicode full folding (`UCharacter.foldCase(s, UCharacter.FOLD_CASE_DEFAULT)`), never
  the default locale; `Locale.ROOT` for any upper/lower conversion.
- Substring mode only when every code point of `find` that is not a space has script Han, Hiragana,
  Katakana, Thai, Lao, Khmer or Myanmar.
- Provenance of a correction revision: `"CORRECTIONS"`. Job type: `JobType.CORRECTIONS`.
- `SchemaMigrator.SUPPORTED_VERSION = 3`; the upgrade is atomic, idempotent and checks foreign keys.
- Rules, page text and readings stay out of logs and error messages.
- Product copy is in English. Every JVM runs headless (`-Djava.awt.headless=true`).
- Iterate with `./gradlew test -PskipFrontend --tests <class>` and `cd web && npm test -- --run && npm run check`.
  Run `./gradlew check` before reporting done. Commit only when the owner asks; "Commit" steps mark scope.
- Every operation is idempotent: run it twice, interrupt it at each step and run it again (AGENTS.md).

## Review Focus

1. **A page whose stored text is not NFC** (decomposed `å` from a text layer). The rule matches, and every
   character outside the match is byte-identical afterwards. Tested in Task 2.
2. **Removing the last rule that touched a document.** The document returns to its reading in a new
   revision, and its pages carry no reading columns. Tested in Task 7.
3. **A rescan or restore publishes the document while the job is between staging and publishing.** The job's
   publication is refused, the document is redone from the new revision, nothing is lost. Tested in Task 7.
4. **A rule whose match crosses a structure block or a layout span boundary** (a phrase over a line break,
   a heading followed by a paragraph). Ranges stay valid, or the page falls back as specified; text is never
   wrong. Tested in Task 4.
5. **The process is killed during a `CORRECTIONS` run.** Startup recovery finishes or discards the
   publication, the job starts again and converges without a second revision for finished documents.
   Tested in Task 7.

---

### Task 1: Schema version 3 and the reading columns

**Files:**
- Modify: `src/main/resources/db/migration/001_baseline.sql` (new table, columns, `jobs.type` CHECK)
- Modify: `src/main/kotlin/infoscry/storage/SchemaMigrator.kt`
- Modify: `src/main/kotlin/infoscry/domain/Models.kt` (`JobType.CORRECTIONS`, `ContentUnit` reading fields)
- Modify: `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt`, `src/main/kotlin/infoscry/storage/ContentStore.kt`
- Modify: `src/main/kotlin/infoscry/extract/DocumentExtractor.kt` (`ContentUnitDraft`)
- Modify: `docs/development.md` (the upgrade steps)
- Test: `src/test/kotlin/infoscry/storage/SchemaMigratorTest.kt`, `src/test/kotlin/infoscry/storage/ReadingColumnsStoreTest.kt` (new)

**Interfaces:**
- Produces:
  - Table `collection_corrections (id TEXT PK, collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE, find TEXT NOT NULL, find_key TEXT NOT NULL, replace TEXT NOT NULL, exact_case INTEGER NOT NULL CHECK (exact_case IN (0,1)), created_at, updated_at, UNIQUE (collection_id, find_key))`.
    `find_key` is computed by Task 3 (`CorrectionRules.findKey`).
  - Columns `reading_extracted_text TEXT`, `reading_search_text TEXT` on `page_text_revisions` and
    `content_units`, with `CHECK ((reading_extracted_text IS NULL) = (reading_search_text IS NULL))`.
  - Columns `corrections_fingerprint TEXT`, `corrections_applied TEXT` (JSON) on `document_revisions`.
  - `data class ReadingText(val extractedText: String, val searchText: String)` in `infoscry.domain`.
  - `reading: ReadingText? = null` on `RevisionPageText`, `RevisionPageDraft`, `ContentUnit`, `ContentUnitDraft`.
  - `data class AppliedCorrection(val find: String, val replace: String, val matches: Int)` (`@Serializable`, `infoscry.domain`).
  - `DocumentRevision.correctionsFingerprint: String?`, `DocumentRevision.correctionsApplied: List<AppliedCorrection>`.
  - `DocumentRevisionStore.recordCorrections(revisionId: String, fingerprint: String, applied: List<AppliedCorrection>)`.

- [ ] **Step 1: Write the failing tests**

  - `SchemaMigratorTest.upgradesVersion2ToVersion3`: build a version-2 database with a collection, a job
    and an import item; migrate; assert `PRAGMA user_version = 3`, the job and item survive,
    `PRAGMA foreign_key_check` is empty, a `CORRECTIONS` job can be inserted, and migrating again changes
    nothing. `upgradesVersion1ToVersion3` runs both steps.
  - `ReadingColumnsStoreTest.readingRoundTripsThroughRevisionAndContentUnit`: append a page draft with
    `reading = ReadingText("spinners", "spinners")`; read it back; publish via `recordPublishedContent`
    after committing a unit with a reading; assert the copied page and the content unit carry it.
  - `ReadingColumnsStoreTest.restoreCopiesReading`: `stageRestore` from a revision whose page has a reading;
    the candidate page has the same reading.
  - `ReadingColumnsStoreTest.halfAReadingIsRefused`: inserting only `reading_extracted_text` fails.

- [ ] **Step 2: Run them to verify they fail**

  Run: `./gradlew test -PskipFrontend --tests infoscry.storage.SchemaMigratorTest --tests infoscry.storage.ReadingColumnsStoreTest`
  Expected: FAIL (unknown column / version 2).

- [ ] **Step 3: Implement**

  Baseline gains the table and columns. `SchemaMigrator.upgradeToVersion3()` follows `upgradeToVersion2`:
  `foreign_keys = OFF`, one transaction that rebuilds `jobs` with the new CHECK (copy, drop, rename,
  recreate `jobs_state` and `jobs_collection_state`), `ALTER TABLE … ADD COLUMN` for the other columns,
  creates `collection_corrections`, `foreign_key_check` must be empty, set `user_version = 3`.
  Store mappings read and write the new columns in `writePageText`, `toPageText`, `copyPublishedPages`,
  `copyApprovedPages`, `commitExtractedUnit`, the content-unit reader, and `toRevision`.

- [ ] **Step 4: Run them to verify they pass**, plus `--tests 'infoscry.storage.*' --tests 'infoscry.document.*'`. Expected: PASS.

- [ ] **Step 5: Commit** — `Add schema version 3 for collection corrections`

---

### Task 2: The matcher and the offset map

**Files:**
- Create: `src/main/kotlin/infoscry/correction/CorrectionMatcher.kt`, `src/main/kotlin/infoscry/correction/OffsetMap.kt`
- Test: `src/test/kotlin/infoscry/correction/CorrectionMatcherTest.kt`, `src/test/kotlin/infoscry/correction/OffsetMapTest.kt`

**Interfaces:**
- Consumes: `CorrectionRule` (Task 3 defines it; create it here as the plain data class and let Task 3 add
  the normalizer): `data class CorrectionRule(val id: String, val collectionId: CollectionId, val find: String, val replace: String, val exactCase: Boolean, val createdAt: String, val updatedAt: String)`.
- Produces:
  - `data class TextEdit(val start: Int, val endExclusive: Int, val replacement: String, val ruleId: String)` — UTF-16 indices into the **input** string, always on code-point boundaries.
  - `data class CorrectionResult(val text: String, val edits: List<TextEdit>) { val changed: Boolean; fun matchesByRule(): Map<String, Int> }`
  - `class CorrectionMatcher(rules: List<CorrectionRule>) { fun apply(text: String): CorrectionResult }`
  - `class OffsetMap(edits: List<TextEdit>) { fun mapStart(offset: Int): Int; fun mapEnd(offset: Int): Int }` —
    an offset inside an edit maps to the edit's new start (`mapStart`) or new end (`mapEnd`).

- [ ] **Step 1: Write the failing tests** (`CorrectionMatcherTest`, one test per line; rule ids `r1`, `r2`):

  ```kotlin
  matches("spinners → Spinnars", "brev från spinners, igen") == "brev från Spinnars, igen"
  matches("spinners → Spinnars", "(spinners)") == "(Spinnars)"
  unchanged("spinners → Spinnars", "spinnersen ospinners")
  matches("spinners → Spinnars", "SPINNERS") == "SPINNARS"
  matches("spinners → Spinnars", "Spinners") == "Spinnars"
  matches("malmo → malmö", "Malmo MALMO malmo") == "Malmö MALMÖ malmö"
  exactCase("AB → Ab", "AB ab") == "Ab ab"
  matches("åsa → Åsa", "åsa") == "Åsa"; unchanged("åsa → Åsa", "såsa")
  matches("Karl Johan → Carl Johan", "Karl\nJohan") == "Carl\nJohan"          // separators kept
  matches("Karl Johan → Carl", "Karl  \n Johan") == "Carl"                      // different word count
  matches("Straße → Gatan", "STRASSE") == "GATAN"                              // full case folding
  matches("strasse → gatan", "Straße") == "Gatan"
  matches("istanbul → İstanbul", "ISTANBUL") == "İSTANBUL"                    // Locale.ROOT, no Turkish rules
  nfdText: text "åsa" (NFD åsa) with "åsa → Åsa" == "Åsa"; text "x åsa ý" keeps "ý" bytes
  surrogates: "spinners 😀" → "Spinnars 😀"; rule "𠀋 → 𠀌" in "前𠀋後" == "前𠀌後"  // substring mode
  substring: "東京 → 東亰" in "東京都" == "東亰都"
  combiningNeighbour: unchanged("spinners → Spinnars", "spinnerś")
  longestWins: rules "Karl → Carl", "Karl Johan → Carl Johan"; "Karl Johan" → "Carl Johan", one edit by the phrase rule
  noChaining: rules "a1 → b1", "b1 → c1"; "a1 b1" == "b1 c1"
  orderIndependent: shuffling the rule list gives the same result for a 5-rule fixture
  idempotentOnReading: apply(reading) twice from the same reading gives equal results
  ```

  `OffsetMapTest`: edits `(10,18,"Spinnars")` (delta 0) and `(30,35,"Stockholm")` (delta +4):
  `mapStart(5) == 5`, `mapStart(20) == 20`, `mapStart(31) == 30`, `mapEnd(31) == 39`, `mapStart(40) == 44`.

- [ ] **Step 2: Run to verify they fail** — `./gradlew test -PskipFrontend --tests 'infoscry.correction.*'`. Expected: FAIL (unresolved).

- [ ] **Step 3: Implement `CorrectionMatcher.apply`**

  Algorithm (the tests do not fully determine it):
  1. NFC view: split the input at `Normalizer2.getNFCInstance().hasBoundaryBefore` code points into
     segments; normalize each; keep `nfcStart → originalStart` for every segment start. Matching runs over
     the NFC string; an edit's start and end are segment boundaries (word boundaries always are) and map back
     to original indices. The output is built from the **original** string with only the edited ranges
     replaced.
  2. Pre-compute each rule: tokens = `find.split(' ')`; folded tokens (`UCharacter.foldCase`) unless
     `exactCase`; substring mode per the Global Constraints.
  3. Scan positions left to right. At a position that is a word boundary on its left (or any code-point
     position for substring-mode rules), try every rule: each inner token must equal (folded) the whole next
     non-whitespace run and be followed by ≥1 whitespace code points; the last token must equal a prefix of
     the next run that ends at a word boundary. Keep the longest match (code points of the text); ties:
     `exactCase` first, then smaller `id`. Emit the edit, continue after it. No match: advance one code point.
  4. Replacement casing (non-`exactCase`): if `replace` has an upper/title-case letter, use it as written
     unless every cased letter of the match is upper case (then `uppercase(Locale.ROOT)`); if `replace` is all
     lower case: all-upper match → upper case; match whose first cased letter is upper and the rest lower →
     title-case the first letter (`UCharacter.toTitleCase(Locale.ROOT, …)` on the first code point); otherwise as
     written. Equal word counts: interleave replacement words with the matched text's own separators.

- [ ] **Step 4: Implement `OffsetMap`** as a sorted edit list with cumulative deltas (binary search).

- [ ] **Step 5: Run to verify they pass.** Expected: PASS.

- [ ] **Step 6: Commit** — `Match correction rules across Unicode text`

---

### Task 3: Rules, fingerprint and the rule store

**Files:**
- Create: `src/main/kotlin/infoscry/correction/CorrectionRules.kt`, `src/main/kotlin/infoscry/storage/CorrectionStore.kt`
- Test: `src/test/kotlin/infoscry/correction/CorrectionRulesTest.kt`, `src/test/kotlin/infoscry/storage/CorrectionStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class CorrectionRuleInput(val find: String, val replace: String, val exactCase: Boolean)`
  - `class InvalidCorrectionException(message: String) : IllegalArgumentException(message)` (→ `400 INVALID_REQUEST`)
  - `class CorrectionFindTakenException : IllegalStateException` (→ `409 CORRECTION_FIND_TAKEN`)
  - `object CorrectionRules { const val MAX_LENGTH = 200; fun normalize(input: CorrectionRuleInput): CorrectionRuleInput; fun findKey(input: CorrectionRuleInput): String; fun fingerprint(rules: List<CorrectionRule>): String }`
    `findKey` = `"fold:" + foldCase(find)` or `"exact:" + find`. `fingerprint` = SHA-256 hex over
    `exactCase\tfind\treplace\n` lines sorted by `(findKey, replace)`; empty list has a fixed fingerprint.
  - `class CorrectionStore(database: Database) { fun list(collectionId: CollectionId): List<CorrectionRule>; fun put(collectionId: CollectionId, id: String, input: CorrectionRuleInput): CorrectionRule; fun delete(collectionId: CollectionId, id: String): Boolean; fun rule(collectionId: CollectionId, id: String): CorrectionRule? }`
    `put` normalizes, creates or replaces in one transaction, keeps `createdAt` on replace, leaves `updatedAt`
    untouched when nothing changed; `list` orders by `created_at, id`.

- [ ] **Step 1: Write the failing tests**
  - `normalize`: `"  Karl \n Johan "` → `"Karl Johan"`; NFD → NFC; refuses blank, `find == replace`, 201
    characters, `find` `"-spinners"` (starts with a non-letter); accepts `"St. Erik"`.
  - `fingerprint`: independent of order and of ids; changes when `exactCase` flips; equal before/after a
    no-op `put`.
  - `CorrectionStore`: `put` twice with the same id and input → one row, same `updatedAt`; `"spinners"` and
    `"Spinners"` (both non-exact) under different ids → `CorrectionFindTakenException`; the same with one
    `exactCase` → both stored; `delete` twice → `true` then `false`; deleting the collection removes rules.
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** as specified above; move `CorrectionRule` here from Task 2's file if placed there.
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit** — `Store a collection's correction rules`

---

### Task 4: Correcting one page — text, structure and layout

**Files:**
- Create: `src/main/kotlin/infoscry/correction/PageCorrector.kt`
- Test: `src/test/kotlin/infoscry/correction/PageCorrectorTest.kt`

**Interfaces:**
- Consumes: `CorrectionMatcher`, `OffsetMap` (Task 2); `PageStructure`, `PageStructureStore`,
  `PageTextLayout`, `PageTextLayoutStore`, `TextNormalizer`.
- Produces:
  - `data class CorrectedPage(val extractedText: String, val searchText: String, val reading: ReadingText?, val structureReference: StructureReference?, val layoutReference: LayoutReference?, val matchesByRule: Map<String, Int>)`
  - `class PageCorrector(matcher: CorrectionMatcher) { fun correct(base: ReadingText, structure: StructureReference?, layout: LayoutReference?, artifactRoot: Path): CorrectedPage }`
    - `base` is the page's reading (or its text when it has none).
    - `searchText = TextNormalizer.normalize(correctedExtracted).search`. `reading = base` when the corrected
      text differs from it, else `null`.
    - Unchanged text → the input references are returned as they are (no artifact written).
    - Structure: read with `PageStructureStore.read(artifactRoot, ref, base.extractedText)`; shift
      `extracted` ranges by the extracted-text `OffsetMap` and `search` ranges by a second map from matching
      `base.searchText`; set `textSha256` to the corrected text's hash; `verifiedAgainst(corrected)`; write a
      new artifact. On failure: a degraded paragraph structure (one `PARAGRAPH` per non-blank line, both
      ranges computed directly on the corrected texts) that passes `verifiedAgainst`, else `null`.
    - Layout: read with `PageTextLayoutStore.read(artifactRoot, ref, base.extractedText)`; shift spans by
      the extracted map; an edit spanning several spans goes into the first, later spans shrink; spans that
      become empty are dropped; new `textSha256`; write. A layout that then fails its own checks → `null`.

- [ ] **Step 1: Write the failing tests**
  - `unchangedPageKeepsReferences` (same `StructureReference` and `LayoutReference` objects, `reading == null`).
  - `correctsTextAndKeepsReading`: base `"Brev från spinners"` → text `"Brev från Spinnars"`, `reading == base`.
  - `structureRangesShift`: heading `"Hos spinners"` + paragraph after it, rule `spinners → Spinnarsson`;
    the read-back structure verifies against the corrected text and the paragraph starts 3 later.
  - `tableCellRangesShift` with a two-cell row.
  - `phraseAcrossBlocksFallsBackToDegradedParagraphs`: rule `Hos spinners Paragraf → X` crosses a heading
    and paragraph; the structure is degraded and verifies.
  - `layoutSpansShift` and `phraseAcrossSpansGoesIntoFirstSpan` (two line spans, `Karl⏎Johan → Carl Johansson`).
  - `searchTextMatchesNormalizedExtracted`: for a text with `\r\n` and a control character, `searchText ==
    TextNormalizer.normalize(extractedText).search`.
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement `PageCorrector.correct`.**
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit** — `Correct a page's text, structure and layout together`

---

### Task 5: Correcting staged text — candidates and plain imports

**Files:**
- Create: `src/main/kotlin/infoscry/correction/CandidateCorrector.kt`
- Modify: `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt` (`replaceCorrectedPage`)
- Modify: `src/main/kotlin/infoscry/jobs/StoredUnitsSink.kt` (correct each `ContentUnitDraft` before `commitExtractedUnit`)
- Modify: `src/main/kotlin/infoscry/jobs/DocumentIngest.kt` (record corrections on the import revision)
- Test: `src/test/kotlin/infoscry/correction/CandidateCorrectorTest.kt`, `src/test/kotlin/infoscry/jobs/ImportJobHandlerTest.kt`

**Interfaces:**
- Consumes: `CorrectionStore.list`, `CorrectionRules.fingerprint`, `PageCorrector`, Task 1 columns.
- Produces:
  - `DocumentRevisionStore.replaceCorrectedPage(revisionId: String, ordinal: Int, page: CorrectedPage)` —
    only for a `CANDIDATE`; writes text, search text, reading and both references, recomputes `text_sha256`,
    deletes that page's chunks, all in one transaction. Idempotent: the same page twice changes nothing more.
  - `class CandidateCorrector(revisions: DocumentRevisionStore, corrections: CorrectionStore, paths: AppPaths) { fun correct(collectionId: CollectionId, documentId: DocumentId, candidateRevisionId: String): List<AppliedCorrection> }`
    For every page: `base = page.reading ?: ReadingText(page.extractedText, page.searchText)`; correct; when
    the result differs from the staged text, `replaceCorrectedPage`. Then `recordCorrections(candidate,
    fingerprint, applied)` with the counts summed over **all** pages (unchanged pages included, from the
    edits found on their base). Pure function of (staged pages, rules): calling it twice is a no-op.
  - `CandidateCorrector.wouldChange(collectionId: CollectionId, revisionId: String): Boolean` — the same
    computation without writes: whether any page of that (published) revision differs from base + rules.
  - `fun correctDraft(draft: ContentUnitDraft, rules: List<CorrectionRule>, artifactRoot: Path): ContentUnitDraft` (same file) for plain imports.

- [ ] **Step 1: Write the failing tests**
  - `CandidateCorrectorTest.correctsPagesAndDropsTheirChunks`: candidate with two pages, one containing
    `spinners`, both chunked; after `correct`, page 0 has new text and no chunks, page 1 keeps its chunks.
  - `CandidateCorrectorTest.secondCallChangesNothing`.
  - `CandidateCorrectorTest.restoredPageStartsFromItsReading`: a page with `reading = "spinners"` and text
    `"Spinnars"`, rules now empty → text back to `"spinners"`, `reading == null`.
  - `ImportJobHandlerTest.plainImportPublishesCorrectedText`: collection rule `spinners → Spinnars`, import a
    text fixture containing `spinners`; the content unit and the published revision page read `Spinnars`,
    reading `spinners`; the revision's `correctionsApplied == [AppliedCorrection("spinners","Spinnars",1)]`;
  - `ImportJobHandlerTest.resumedImportKeepsCorrectedUnits`: kill after the first unit commits; resume skips
    it (checkpoint) and the unit still reads `Spinnars` with reading `spinners`.
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** the store method, `CandidateCorrector`, `correctDraft` in `StoredUnitsSink.deliver`
  (rules read once per document, at its first unit), and `recordCorrections` after `recordPublishedContent`
  in `DocumentIngest`.
- [ ] **Step 4: Run to verify they pass**, plus `--tests 'infoscry.jobs.*'`.
- [ ] **Step 5: Commit** — `Apply corrections to imported and staged text`

---

### Task 6: Rescan, check-and-improve, Restore and Reject

**Files:**
- Modify: `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt` (call `CandidateCorrector.correct` before `phases.chunkCandidate`, line ~429)
- Modify: `src/main/kotlin/infoscry/jobs/DocumentIngest.kt` (same, before its `chunkCandidate`, line ~345)
- Modify: `src/main/kotlin/infoscry/document/RevisionRestoreService.kt` (after `stageRestore`: correct, chunk pages without chunks, then embed)
- Modify: `src/main/kotlin/infoscry/document/PageReviewService.kt` (quality scores on `reading ?: text`)
- Modify: `src/main/kotlin/infoscry/AppContext.kt` (construct and pass `CandidateCorrector`)
- Test: `src/test/kotlin/infoscry/jobs/RescanJobHandlerTest.kt`, `src/test/kotlin/infoscry/document/RevisionRestoreTest.kt`, `src/test/kotlin/infoscry/document/PageReviewServiceTest.kt`

**Interfaces:**
- Consumes: `CandidateCorrector.correct` (Task 5), `Chunker` as `CandidateRevisionPhases.chunkCandidate` uses it.

- [ ] **Step 1: Write the failing tests**
  - `RescanJobHandlerTest.rescanKeepsCorrection`: fake engine reads `spinners`; rule exists; published page
    reads `Spinnars`, reading `spinners`.
  - `PageReviewServiceTest.qualityScoresUseReadings` and `wordDifferencesIgnoreCorrections`: before and after
    both corrected identically → no difference listed for `Spinnars`.
  - `RevisionRestoreTest.restoreAppliesTodaysRules`: revision A published before the rule existed (`spinners`);
    add the rule (no job run); restore A → published text `Spinnars`, history provenance `RESTORE`,
    `correctionsApplied` non-empty.
  - `RevisionRestoreTest.rejectAfterRescanAppliesTodaysRules` through `PageReviewService` Reject.
  - `RevisionRestoreTest.restoreRepeatedWithSameRequestReplays` still passes (existing idempotence).
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** the four call sites; in restore, chunk only pages with no chunks (reuse the
  `ContentUnit` construction from `CandidateRevisionPhases.chunkCandidate` through an `internal` helper
  rather than a copy).
- [ ] **Step 4: Run to verify they pass**, plus `--tests 'infoscry.document.*' --tests 'infoscry.jobs.*' --tests 'infoscry.ocr.*'`.
- [ ] **Step 5: Commit** — `Keep corrections through rescans, restores and page review`

---

### Task 7: The service, the preview and the `CORRECTIONS` job

**Files:**
- Create: `src/main/kotlin/infoscry/correction/CorrectionService.kt`, `src/main/kotlin/infoscry/jobs/CorrectionJobHandler.kt`
- Modify: `src/main/kotlin/infoscry/storage/JobStore.kt` (`activeJob`), `src/main/kotlin/infoscry/AppContext.kt`, `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt:845` (dispatch map)
- Test: `src/test/kotlin/infoscry/correction/CorrectionServiceTest.kt`, `src/test/kotlin/infoscry/jobs/CorrectionJobHandlerTest.kt`

**Interfaces:**
- Produces:
  - `JobStore.activeJob(type: JobType, collectionId: CollectionId): Job?` (QUEUED or RUNNING).
  - `data class CorrectionExample(val documentId: DocumentId, val unitId: ContentUnitId, val before: String, val after: String)` — ≤ 60 characters of context each side, at most 5 per preview.
  - `data class CorrectionPreview(val matches: Int, val documents: Int, val examples: List<CorrectionExample>)`
  - `data class CorrectionListView(val rules: List<CorrectionRuleView>, val job: Job?)`, `data class CorrectionRuleView(val rule: CorrectionRule, val matches: Int)` — `matches` sums `correctionsApplied` of active revisions by `find`.
  - `class CorrectionService(…) { fun list(collectionId): CorrectionListView; fun put(collectionId, id, input): CorrectionRule; fun delete(collectionId, id); fun preview(collectionId, draft: CorrectionRuleInput, replacingId: String?): CorrectionPreview; fun apply(collectionId): Job }`
    `put`/`delete` store the change, then `apply`. `apply` returns `activeJob(CORRECTIONS, collection)` or
    enqueues one with `total` = the collection's document count. `preview` runs the matcher with the draft
    in the list (replacing `replacingId`) over every active revision's base texts and counts only the
    draft's edits. `fun previewRemoval(collectionId: CollectionId, id: String): CorrectionPreview` counts the
    documents whose text would change if that rule were removed.
  - `DocumentRevisionStore.stageCorrectionCandidate(documentId: DocumentId, baseRevisionId: String, fingerprint: String): String`
    — opens (or, through `resumableCandidate`, reuses) a `CANDIDATE` with provenance `"CORRECTIONS"`, parent
    `baseRevisionId` and `attempt_fingerprint = "corrections:$baseRevisionId:$fingerprint"`, copying the
    base's pages (with readings) and chunks **with** their vectors, so unchanged pages are not re-embedded.
  - `class CorrectionJobHandler(…) : JobHandler` — per pass: record the fingerprint; for each document of the
    collection: if `CandidateCorrector.wouldChange` is false for its active revision, advance; else in
    `mutations.withMutation` call `stageCorrectionCandidate`, `CandidateCorrector.correct`, chunk pages without chunks, embed chunks
    without vectors, `publication.publish(documentId, activeId, candidate)`; a refusal because the base moved
    → retry that document from the new active revision (at most 3 times, then fail the job with
    `CORRECTIONS_CONFLICT`). After the last document: if the fingerprint changed, run another pass.
    Embedder unavailable → fail with `EMBEDDING_UNAVAILABLE`; cancellation withdraws the open candidate.

- [ ] **Step 1: Write the failing tests** (`CorrectionJobHandlerTest` uses `JobHarness` and `TestDocumentEmbedder`):
  - `appliesToMatchingDocumentsOnly`: 3 documents, 1 contains `spinners` → one new `CORRECTIONS` revision, the other two keep their revision ids.
  - `runningTwiceCreatesNoSecondRevision`.
  - `removingTheRuleRestoresTheReading`: text `spinners`, `reading == null` on every page after the second run.
  - `changingTheRuleUpdatesTheText`: `→ Spinnarö` replaces `Spinnars`, base is still the reading.
  - `listChangedDuringRunTakesAnotherPass` (change the list from the `observe` hook after document 1).
  - `concurrentPublicationRedoesTheDocument`: publish a rescan of document 1 between staging and publish (hook); end state follows the list and descends from the rescan.
  - `interruptedAtEachPublicationStepConverges`: for each `PublicationStep`, kill at it, run `recoverUnfinished`, rerun the job → same final texts, exactly one `CORRECTIONS` revision per changed document.
  - `embedderUnavailableFailsAndRetryContinues`.
  - `CorrectionServiceTest`: `apply` twice returns the same job; `preview` changes nothing (revision ids and job count unchanged); preview counts `14 matches in 6 documents` on a fixture; examples ≤ 5; `previewRemoval` counts documents that would change.
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** the service, the handler, `activeJob`, wiring in `AppContext` and the dispatch map
  (`JobType.CORRECTIONS to CorrectionJobHandler(...)`).
- [ ] **Step 4: Run to verify they pass**, plus `--tests 'infoscry.jobs.*' --tests 'infoscry.document.*'`.
- [ ] **Step 5: Commit** — `Apply a collection's corrections to every document`

---

### Task 8: HTTP routes, history wire and the reading endpoint

**Files:**
- Create: `src/main/kotlin/infoscry/server/CorrectionRoutes.kt`
- Modify: `src/main/kotlin/infoscry/server/Routes.kt:335` (mount beside `collectionPromptRoutes`), `src/main/kotlin/infoscry/server/OcrRoutes.kt` (revision view gains `corrections`), `src/main/kotlin/infoscry/server/SourceRoutes.kt`
- Test: `src/test/kotlin/infoscry/server/CorrectionRoutesTest.kt`, `src/test/kotlin/infoscry/server/SourceRoutesTest.kt`, `src/test/kotlin/infoscry/server/OcrRoutesTest.kt`

**Interfaces:**
- Produces (all under `/api/collections/{id}`, existing bearer/CSRF):
  - `GET /corrections` → `{ rules: [{ id, find, replace, exactCase, matches }], job: JobWire|null }`
  - `PUT /corrections/{ruleId}` body `{ find, replace, exactCase }` → `200` rule; `400 INVALID_REQUEST`; `409 CORRECTION_FIND_TAKEN`
  - `DELETE /corrections/{ruleId}` → `204`, also when already gone
  - `POST /corrections/preview` body `{ find, replace, exactCase, replacingId? }` or `{ removingId }` → `{ matches, documents, examples: [{ documentId, sourceId, before, after }] }`
  - `POST /corrections/apply` → `200` job (the Retry button)
  - `GET /sources/{sourceId}/reading[?revision=]` → `{ reading: string|null }`; `SourceContentResponse` gains `hasReading: Boolean`
  - Revision view gains `corrections: [{ find, replace, matches }]` (empty list when none)

- [ ] **Step 1: Write the failing tests**: each route's success; `PUT` twice → same body, one row; `DELETE` twice → `204` both; preview leaves `GET /corrections` and the document's revisions unchanged; missing CSRF → `403`; `hasReading` true only for a corrected page; `/reading` of a superseded revision works and of a candidate is `404` (same rule as the source route); no rule text in logs (`LogsCapture` as `CollectionPromptRoutesTest` does).
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** thin routes over `CorrectionService`, mapping the exceptions to the codes above.
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit** — `Serve collection corrections over HTTP`

---

### Task 9: CLI

**Files:**
- Modify: `src/main/kotlin/infoscry/cli/CollectionCommand.kt` (new `CorrectionCommand` beside `PromptCommand`), `src/main/kotlin/infoscry/cli/CliSession.kt`, `docs/cli.md`
- Test: `src/test/kotlin/infoscry/cli/CollectionCommandTest.kt`

**Interfaces:**
- Produces: `infoscry collection corrections list <collection> [--json]` (lines `find<TAB>replace<TAB>exact|<TAB>matches`),
  `add <collection> <find> <replace> [--exact-case] [--id <id>]` (prints the preview, then saves and starts
  the job; with no running server it waits for the job like `import`), `remove <collection> <find-or-id>`,
  `preview <collection> <find> <replace> [--exact-case]`. Arguments are UTF-8.

- [ ] **Step 1: Write the failing tests**: `add` then `list` shows the rule; `add` repeated with `--id` is idempotent; `remove` twice succeeds; `preview` changes nothing; a rule with `Åsa` and `東京` round-trips through arguments and `--json`.
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement**, following `ListPromptsCommand` (`CliSession.connect`, `resolveOptions`, `CliFailure`).
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit** — `Manage collection corrections from the CLI`

---

### Task 10: Web UI

**Files:**
- Modify: `web/src/lib/api.ts` (`getCorrections`, `putCorrection`, `deleteCorrection`, `previewCorrection`, `previewCorrectionRemoval`, `applyCorrections`, `readSourceReading`; types `CorrectionRule`, `CorrectionList`, `CorrectionPreview`)
- Create: `web/src/lib/CollectionCorrectionSettings.svelte`, `web/src/lib/CorrectionDialog.svelte`
- Modify: `web/src/lib/CollectionSettingsPanel.svelte` (mount after `CollectionPromptSettings`, line ~209), `web/src/lib/SourceWorkspace.svelte` (selection button, **Show reading**), `web/src/lib/OcrHistoryPanel.svelte:300` (labels)
- Test: `web/src/lib/CollectionCorrectionSettings.test.ts`, `web/src/lib/CorrectionDialog.test.ts`, `web/src/lib/SourceWorkspace.test.ts`, `web/src/lib/OcrHistoryPanel.test.ts`, `web/src/lib/api.test.ts`

**Interfaces:**
- Consumes: Task 8 routes.
- Copy (exact): section heading `Corrections`; columns `Wrong`, `Correct`, `Exact case`, `Matches`; buttons
  `Edit`, `Remove`, `Save`, `Cancel`, `Retry`; status `All documents follow the list` /
  `Applying: {done} of {total} documents`; preview `{n} matches in {m} documents` (singular `match`/`document` for 1); removal confirmation
  `The text returns to the reading in {m} documents.`; reader `Correct across collection`, `Show reading`,
  `Show corrected text`; history `Corrections: {find} → {replace} ({n} match|matches)` (singular for 1; comma-joined for several)
  and `Restored from {date}, with the collection's corrections` when a `RESTORE` revision has corrections.

- [ ] **Step 1: Write the failing tests**: settings list renders rules and status; Save is disabled until a preview has run for the current inputs; editing an input invalidates the preview; Remove asks with the document count; `409` shows `That word already has a correction.`; dialog opened from a reader selection is pre-filled and previews immediately; selection over 200 characters offers no button; **Show reading** toggles the text and is absent when `hasReading` is false; history labels as above; the dialog is reachable and operable by keyboard (focus moves into it and back, as `dialogFocus.ts`).
- [ ] **Step 2: Run to verify they fail** — `cd web && npm test -- --run`.
- [ ] **Step 3: Implement** with normal HTML form elements; poll the job through the existing job events used by `JobsPanel`.
- [ ] **Step 4: Run to verify they pass** — `cd web && npm test -- --run && npm run check`.
- [ ] **Step 5: Commit** — `Manage corrections in collection settings and the reader`

---

### Task 11: Browser acceptance, documentation and the gates

**Files:**
- Create: `src/test/kotlin/infoscry/server/CorrectionsBrowserAcceptanceTest.kt` (`@Tag` as `DocumentBrowserAcceptanceTest`)
- Modify: `README.md` (status), `docs/usage.md` (a "Corrections" section), `docs/technical-reference.md` (a "Collection corrections" section: tables, columns, job, matching rules, Unicode), `AGENTS.md` (one status line), `docs/specs/2026-10-22-collection-corrections-design.md` (status line)

- [ ] **Step 1: Write the acceptance test** with fake providers and embeddings: import a text fixture with
  `spinners` on two documents; select `spinners` in the reader → **Correct across collection** → type
  `Spinnars` → preview shows `2 matches in 2 documents` → Save → status reaches
  `All documents follow the list` → the reader shows `Spinnars` and **Show reading** shows `spinners` →
  history shows `Corrections: spinners → Spinnars (1 match)` → Remove → confirm → the reader shows
  `spinners`.
- [ ] **Step 2: Run** `./gradlew externalTest --tests infoscry.server.CorrectionsBrowserAcceptanceTest`. Expected: PASS after Tasks 1–10.
- [ ] **Step 3: Write the documentation** listed above; check links and that README commands still run.
- [ ] **Step 4: Run the gates** — `./gradlew check` (expected: BUILD SUCCESSFUL), then
  `./gradlew gpuIntegrationTest` on the local Mac with a correction applied to a fixture collection, to
  verify the real CoreML embedding path of a `CORRECTIONS` publication. Report it as unverified if the GPU run
  was not possible.
- [ ] **Step 5: Commit** — `Document collection corrections and add browser coverage`
