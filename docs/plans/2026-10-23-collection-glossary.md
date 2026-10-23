# Collection glossary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A per-collection glossary of equivalent names that expands keyword search and tells Ask and Investigate which names mean the same, without changing any text.

**Architecture:** A new `infoscry.glossary` package owns the rules, storage, matching and the LLM block. Keyword search rewrites the parsed Lucene query at search time (no reindex, no job); the semantic half is untouched. Ask's `ContextPacker` and Investigate's pruning add the glossary block to the system message within the request budget.

**Tech Stack:** Kotlin/JVM 25, SQLite (schema v4), Lucene 10 (`QueryParser`, `BooleanQuery`, `PhraseQuery`, `PrefixQuery`), ICU folding via the index analyzer, Ktor, Clikt, Svelte 5 (legacy `export let`), Vitest, Playwright e2e via node.

**Spec:** [docs/specs/2026-10-23-collection-glossary-design.md](../specs/2026-10-23-collection-glossary-design.md)

## Global Constraints

- Product copy in English; restrained, accessible, normal HTML/CSS.
- Terms are UTF-8, stored NFC as typed; `match_key` = index analyzer tokens joined by one space.
- Group: 2–20 terms; term ≤ 200 characters and ≤ 8 analyzed tokens, never empty after analysis.
- Prefix flag only on a single-token term with ≥ 3 characters after analysis (`MIN_ANCHOR_PREFIX`).
- Expansion adds at most 256 clauses; at most 16 variants per quoted phrase; query stays within `MAX_QUERY_TOKENS` (1 000).
- LLM block: at most 40 groups and 1 500 tokens; evidence is never dropped and tool results are never pruned for it.
- Every operation idempotent (repeat `PUT` changes nothing incl. `updated_at`; `DELETE` of a missing group answers 204).
- No terms, queries or document text in logs or exception messages; a glossary failure never fails a search, Ask or Investigate.
- Every JVM headless (`-Djava.awt.headless=true`); tests that spawn `java` pass it with `--enable-native-access=ALL-UNNAMED`.
- Iterate with `./gradlew test -PskipFrontend --tests <class>` and `cd web && npm test -- --run && npm run check`; `./gradlew check` before reporting done.

## Review Focus

- A query mixing a glossary term with Lucene syntax (`+Sthlm -hamn`, `Sthlm AND "stora torget"`, `Sthl*`) keeps its operators' meaning; only the matched clause gains alternatives. Test in Task 3.
- A query whose glossary term appears twice (`Sthlm Sthlm`) or two groups' terms overlap in the query (`Sthlms stad` vs `Sthlms`) expands each span once, longest first, with no duplicate clauses. Test in Task 3.
- A group edited so a term moves to another group in one `PUT` (term removed from A, added to B) succeeds only in the right order and never leaves a term in two groups. Test in Task 1.
- Decomposed input (NFD `Malmö`) and different case typed in the form match the same key as the stored term, both for conflicts and for search. Test in Task 1.
- A collection deleted while its glossary is cached no longer expands any search (cache entry dropped on deletion; cascade removes rows). Test in Task 2.

---

### Task 1: Schema v4, rules and store

**Files:**
- Create: `src/main/kotlin/infoscry/search/TextAnalysis.kt`
- Create: `src/main/kotlin/infoscry/glossary/Glossary.kt`
- Create: `src/main/kotlin/infoscry/glossary/GlossaryRules.kt`
- Create: `src/main/kotlin/infoscry/storage/GlossaryStore.kt`
- Modify: `src/main/resources/db/migration/001_baseline.sql` (two tables, marked `-- schema 4`)
- Modify: `src/main/kotlin/infoscry/storage/SchemaMigrator.kt` (`SUPPORTED_VERSION = 4`, `upgradeToVersion4()`)
- Test: `src/test/kotlin/infoscry/glossary/GlossaryRulesTest.kt`, `src/test/kotlin/infoscry/storage/GlossaryStoreTest.kt`, `src/test/kotlin/infoscry/storage/SchemaMigratorTest.kt`

**Interfaces:**
- Produces:
  - `object TextAnalysis { fun tokens(text: String): List<String> }` — one shared `LuceneIndex.newAnalyzer()` instance (Analyzer is thread-safe), field `LuceneSchema.FIELD_TEXT`.
  - `data class GlossaryTermInput(val text: String, val prefix: Boolean = false)`
  - `data class GlossaryTerm(val text: String, val matchKey: String, val prefix: Boolean) { val tokens: List<String> }`
  - `data class GlossaryGroup(val id: String, val collectionId: CollectionId, val terms: List<GlossaryTerm>, val createdAt: String, val updatedAt: String)`
  - `data class Glossary(val collectionId: CollectionId, val groups: List<GlossaryGroup>) { companion object { fun empty(id: CollectionId) } }`
  - `class InvalidGlossaryException(message: String, val term: Int?) : ApiFaultException(BAD_REQUEST, "INVALID_GLOSSARY_GROUP", …)`; `class GlossaryTermTakenException(val term: Int, val groupId: String) : ApiFaultException(CONFLICT, "GLOSSARY_TERM_TAKEN", …)`; `class GlossaryIdTakenException : ApiFaultException(CONFLICT, "GLOSSARY_ID_TAKEN", …)`. `term` is the 0-based index in the submitted list.
  - `object GlossaryRules { const val MIN_TERMS = 2; const val MAX_TERMS = 20; const val MAX_LENGTH = 200; const val MAX_TOKENS = 8; const val MIN_PREFIX_LENGTH = 3; fun normalize(input: List<GlossaryTermInput>): List<GlossaryTerm> }` — NFC, trim, analyze, merge same key keeping the first, then validate count.
  - `class GlossaryStore(database: Database) { fun list(collectionId): Glossary; fun group(collectionId, id): GlossaryGroup?; fun put(collectionId, id, terms: List<GlossaryTermInput>): GlossaryGroup; fun delete(collectionId, id): Boolean }`

- [ ] **Step 1: Write failing rules tests** in `GlossaryRulesTest`: `STHLM`/`sthlm` merge to one term; NFD `Malmö` and `Malmö` give key `malmo`; `Sthlms stad` key `sthlms stad`; `"!!!"` → `InvalidGlossaryException(term = 0)`; 1 term → invalid (term null); 21 terms → invalid; 201 characters → invalid; 9 tokens → invalid; prefix on `Sthlms stad` and on `KF` → invalid with that term index; prefix on `Sthlm` ok; `東京` is accepted with two-or-more tokens as the analyzer gives them.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.glossary.GlossaryRulesTest'` — FAIL (unresolved references).
- [ ] **Step 3: Implement** `TextAnalysis`, `Glossary.kt`, `GlossaryRules`.
- [ ] **Step 4: Run** the same command — PASS.
- [ ] **Step 5: Write failing store tests** in `GlossaryStoreTest`, mirroring `CorrectionStoreTest`'s setup: put then list keeps typed order and creation order; repeating the same `put` returns the same `updatedAt`; a term already in group A answers `GlossaryTermTakenException(term = 1, groupId = A)`; moving a term from A to B: `put(A without term)` then `put(B with term)` succeeds; an id owned by another collection → `GlossaryIdTakenException`; `delete` twice answers `true` then `false`; deleting the collection row cascades both tables.
- [ ] **Step 6: Add a failing migration test** in `SchemaMigratorTest` following its version-3 test: a version 3 database (baseline without `-- schema 4` statements) upgrades to 4 with both tables present, `schema_version` row 4, and existing collections untouched.
- [ ] **Step 7: Run** `./gradlew test -PskipFrontend --tests 'infoscry.storage.GlossaryStoreTest' --tests 'infoscry.storage.SchemaMigratorTest'` — FAIL.
- [ ] **Step 8: Implement** the schema and store.
  - `collection_glossary_groups (id TEXT PRIMARY KEY, collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)`.
  - `collection_glossary_terms (group_id TEXT NOT NULL REFERENCES collection_glossary_groups(id) ON DELETE CASCADE, collection_id TEXT NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, match_key TEXT NOT NULL, prefix INTEGER NOT NULL, PRIMARY KEY (group_id, ordinal), UNIQUE (collection_id, match_key))`.
  - `upgradeToVersion4()` adds the tables in one transaction with the foreign-key check like `upgradeToVersion3()`; `migrate()` chains 1→2→3→4.
  - `put` in one transaction: owner check, conflict check against other groups' keys, unchanged-content short circuit (same texts, keys, prefixes in order), else upsert group row (keeping `created_at`) and replace its term rows.
- [ ] **Step 9: Run** the store and migrator tests — PASS.
- [ ] **Step 10: Commit** `Store collection glossary groups`.

### Task 2: Service, cache and HTTP routes

**Files:**
- Create: `src/main/kotlin/infoscry/glossary/GlossaryService.kt`
- Create: `src/main/kotlin/infoscry/server/GlossaryRoutes.kt`
- Modify: `src/main/kotlin/infoscry/server/Routes.kt` (`ApiError` gains `val details: Map<String, String>? = null`; the `ApiFaultException` clause passes `fault.details()`; mount `glossaryRoutes` beside `correctionRoutes`)
- Modify: `src/main/kotlin/infoscry/domain/ApiFault.kt` (`open fun details(): Map<String, String>? = null`)
- Modify: `src/main/kotlin/infoscry/AppContext.kt` (`val glossaryService`)
- Test: `src/test/kotlin/infoscry/glossary/GlossaryServiceTest.kt`, `src/test/kotlin/infoscry/server/GlossaryRoutesTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `class GlossaryService(store: GlossaryStore, collections: CollectionService, mutations: MutationCoordinator) { fun list(collectionId): Glossary; suspend fun put(collectionId, id, terms): GlossaryGroup; suspend fun delete(collectionId, id); fun snapshot(collectionId: CollectionId): Glossary; fun forget(collectionId: CollectionId) }`
  - `snapshot` reads through a `ConcurrentHashMap` cache; `put`/`delete`/`forget` drop the entry. `AppContext` calls `forget` when a collection deletion completes.
  - Wire: `GlossaryTermWire(text: String, prefix: Boolean)`, `GlossaryGroupWire(id: String, terms: List<GlossaryTermWire>)`, `GlossaryListWire(groups: List<GlossaryGroupWire>)`, `PutGlossaryGroupRequest(terms: List<GlossaryTermWire>)`.
  - `InvalidGlossaryException.details()` = `{"term": "<index>"}` when known; `GlossaryTermTakenException.details()` = `{"term": "<index>", "groupId": "<id>"}`.

- [ ] **Step 1: Write failing service tests:** `put` on a deleting or missing collection is refused like `CorrectionService`; `snapshot` after `put` sees the new group without restarting; `snapshot` after `delete` is empty; after the collection is deleted and `forget` called, `snapshot` is empty.
- [ ] **Step 2: Write failing route tests** in the style of `CorrectionRoutesTest`: `GET` empty list; `PUT` twice answers 200 with the same body; `DELETE` twice answers 204 both times; invalid term answers 400 `INVALID_GLOSSARY_GROUP` with `details.term`; taken term answers 409 `GLOSSARY_TERM_TAKEN` with `details.term` and `details.groupId`; another collection's id answers 409 `GLOSSARY_ID_TAKEN`; missing CSRF answers as the other mutating routes do.
- [ ] **Step 3: Run** `./gradlew test -PskipFrontend --tests 'infoscry.glossary.GlossaryServiceTest' --tests 'infoscry.server.GlossaryRoutesTest'` — FAIL.
- [ ] **Step 4: Implement** service, routes (`GET /glossary`, `PUT /glossary/{groupId}`, `DELETE /glossary/{groupId}` under `/api/collections/{id}`), `details`, wiring.
- [ ] **Step 5: Run** the same tests plus `--tests 'infoscry.server.*'` to see the `ApiError` change break nothing — PASS.
- [ ] **Step 6: Commit** `Serve collection glossary groups`.

### Task 3: Matching and query rewriting

**Files:**
- Create: `src/main/kotlin/infoscry/glossary/GlossaryMatcher.kt`
- Create: `src/main/kotlin/infoscry/search/GlossaryQueryRewriter.kt`
- Test: `src/test/kotlin/infoscry/glossary/GlossaryMatcherTest.kt`, `src/test/kotlin/infoscry/search/GlossaryQueryRewriterTest.kt`

**Interfaces:**
- Consumes: `Glossary`, `GlossaryTerm.tokens`, `TextAnalysis.tokens`.
- Produces:
  - `data class GlossaryHit(val start: Int, val endExclusive: Int, val group: GlossaryGroup, val term: GlossaryTerm)` (token indexes).
  - `class GlossaryMatcher(glossary: Glossary) { fun hits(tokens: List<String>): List<GlossaryHit> }` — longest match at each position, non-overlapping, left to right; a prefix term hits a single token that starts with its key.
  - `data class ExpansionResult(val query: Query, val groups: List<GlossaryGroup>, val truncated: Boolean)`
  - `class GlossaryQueryRewriter(maxAddedClauses: Int = 256, maxPhraseVariants: Int = 16) { fun rewrite(parsed: Query, glossary: Glossary): ExpansionResult }`
  - Alternatives for a hit = the group's terms other than the matched one: single token → `TermQuery(FIELD_TEXT, key)`; multi token → `PhraseQuery(FIELD_TEXT, *tokens)`; prefix → `PrefixQuery(Term(FIELD_TEXT, key))` (default constant-score rewrite).

- [ ] **Step 1: Write failing matcher tests:** `[sthlms, stad, hamn]` with groups {`Stockholm`, `Sthlms stad`} and {`Sthlm`* prefix} hits `Sthlms stad` at 0..2 (longest wins over the prefix hit on `sthlms`); `[sthlm, sthlm]` gives two hits; no chaining (an alternative's tokens are never matched again — matcher only reads query tokens); `[kfum]` does not hit exact `KF`.
- [ ] **Step 2: Write failing rewriter tests**, parsing with `QueryParser(FIELD_TEXT, LuceneIndex.newAnalyzer())` and asserting on `query.toString()`:
  - `Sthlm` → `text:sthlm text:stockholm text:"sthlms stad"` grouped in one SHOULD disjunction.
  - `+Sthlm +hamn` → the `+sthlm` clause becomes `+(text:sthlm text:stockholm …)`, `+text:hamn` unchanged.
  - `+Sthlm -hamn`, `Sthl*`, `Sthlm~1`, `title:Sthlm`, `[a TO b]` — only plain text-field term clauses expand; others are byte-for-byte unchanged.
  - Unquoted `Sthlms stad` (SHOULD): both word clauses stay, alternatives added as SHOULD. With `+Sthlms +stad`: replaced by one MUST `((+text:sthlms +text:stad) alternatives…)`.
  - `"hamnen i Sthlm"` → OR of the original phrase and `"hamnen i stockholm"` and `"hamnen i sthlms stad"`; a group with 20 alternatives in a 2-hit phrase yields at most 16 variants and `truncated = true`.
  - Prefix term `Sthlm`* as an alternative outside a phrase is a `PrefixQuery`; inside a phrase it is the exact term.
  - 300 hits' worth of alternatives stops at 256 added clauses, `truncated = true`, groups in query order.
  - `Sthlm Sthlm` lists the group once in `groups`.
  - Empty glossary returns the parsed query itself.
- [ ] **Step 3: Run** `./gradlew test -PskipFrontend --tests 'infoscry.glossary.GlossaryMatcherTest' --tests 'infoscry.search.GlossaryQueryRewriterTest'` — FAIL.
- [ ] **Step 4: Implement** the matcher, then the rewriter: walk `BooleanQuery` recursively; within each level read the maximal runs of consecutive `TermQuery` clauses on `FIELD_TEXT` with the same `Occur` as a token sequence; treat each `PhraseQuery` on `FIELD_TEXT` as its own token sequence (only when it has no position gaps — otherwise leave it); rebuild with replacements; count added clauses against the bound before adding each hit's alternatives.
- [ ] **Step 5: Run** the same tests — PASS.
- [ ] **Step 6: Commit** `Rewrite keyword queries with the glossary`.

### Task 4: Search integration

**Files:**
- Modify: `src/main/kotlin/infoscry/search/LuceneIndex.kt` (keyword search takes per-collection glossaries; `hasLexicalAnchor` takes extra tokens)
- Modify: `src/main/kotlin/infoscry/search/SearchService.kt` (glossary lookup, outcome fields, highlighting, fallback)
- Modify: `src/main/kotlin/infoscry/search/SearchModels.kt` (`SearchOutcome`)
- Modify: `src/main/kotlin/infoscry/server/SearchRoutes.kt`, `src/main/kotlin/infoscry/cli/LoopbackApi.kt` (response fields)
- Modify: `src/main/kotlin/infoscry/AppContext.kt` (pass `glossaries` to `SearchService`)
- Test: `src/test/kotlin/infoscry/search/LuceneIndexTest.kt`, `src/test/kotlin/infoscry/search/SearchServiceTest.kt`, `src/test/kotlin/infoscry/server/SearchRoutesTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `data class SearchOutcome(val hits, val staleFiltered: Int, val expandedWith: List<GlossaryGroup> = emptyList(), val expansionTruncated: Boolean = false)`
  - `SearchResponse` gains `expandedWith: List<GlossaryGroupWire> = emptyList()` and `expansionTruncated: Boolean = false`.
  - `LuceneIndex.searchKeyword(collectionId, queryText, documentIds, limit, revisionScope, glossaries: Map<CollectionId, Glossary> = emptyMap()): KeywordSearch` where `data class KeywordSearch(val hits: List<IndexHit>, val groups: List<GlossaryGroup>, val truncated: Boolean)`; existing callers read `.hits`.
  - With `collectionId` set: rewrite under that collection's glossary. Without: `(Q AND NOT collection_id IN hitCollections) OR Σ (collection_id = c AND E_c)` over collections whose rewrite added anything.
  - `hasLexicalAnchor(queryText, collectionId, documentIds, revisionScope, extraTokens: List<String> = emptyList())`.
  - `SearchService(..., glossaries: (CollectionId?) -> Map<CollectionId, Glossary> = { emptyMap() })`; `AppContext` passes `{ id -> if (id != null) mapOf(id to glossaryService.snapshot(id)) else collections.list().associate { it.id to glossaryService.snapshot(it.id) }.filterValues { it.groups.isNotEmpty() } }`. Any exception from the lambda is caught in `SearchService`, logged without terms, and treated as empty.

- [ ] **Step 1: Write failing index tests** on a real temporary index (follow `LuceneIndexTest`'s fixtures): text *Brev från Stockholm* found by `Sthlm` with group {Stockholm, Sthlm}; *Sthlms hamn* found by `Stockholm` with prefix term `Sthlm`*; collection B has no glossary, so its document with only *Stockholm* is not found by `Sthlm`, neither scoped to B nor in a cross-collection search (which does find A's); `hasLexicalAnchor("Sthlm", …, extraTokens = ["stockholm"])` is true on an index with only *Stockholm*.
- [ ] **Step 2: Write failing service tests:** `expandedWith` lists the group and `expansionTruncated` false; highlight marks *Stockholm* for query `Sthlm`, marks *Sthlms stad* only where both words are consecutive, and marks *Sthlms* for prefix term `Sthlm`*; a glossary lambda that throws yields hits for the plain query and empty `expandedWith`; a hybrid search with `requireLexicalAnchor = true` keeps semantic hits for `Sthlm` when only *Stockholm* is in the index.
- [ ] **Step 3: Write a failing route test:** `POST /api/search` answers `expandedWith` with the group's terms.
- [ ] **Step 4: Run** `./gradlew test -PskipFrontend --tests 'infoscry.search.*' --tests 'infoscry.server.SearchRoutesTest'` — FAIL.
- [ ] **Step 5: Implement.** Highlight terms become: query words, plus single-token alternatives and prefix keys of the used groups (existing token-contains rule), plus multi-token alternatives matched as consecutive token sequences.
- [ ] **Step 6: Run** the same tests — PASS. Also run `--tests 'infoscry.ask.*' --tests 'infoscry.investigate.*'` to confirm the `searchKeyword` return type change broke nothing.
- [ ] **Step 7: Commit** `Expand keyword search with the collection glossary`.

### Task 5: Glossary block in Ask

**Files:**
- Create: `src/main/kotlin/infoscry/glossary/GlossaryPrompt.kt`
- Modify: `src/main/kotlin/infoscry/ask/ContextPacker.kt`, `src/main/kotlin/infoscry/ask/AskService.kt`, `src/main/kotlin/infoscry/AppContext.kt`
- Test: `src/test/kotlin/infoscry/glossary/GlossaryPromptTest.kt`, `src/test/kotlin/infoscry/ask/ContextPackerTest.kt`, `src/test/kotlin/infoscry/ask/AskServiceTest.kt`

**Interfaces:**
- Consumes: `GlossaryMatcher`, `TextAnalysis`.
- Produces:
  - `class GlossaryPrompt(glossary: Glossary, investigate: Boolean) { fun groupsIn(texts: List<String>): List<GlossaryGroup>; fun render(groups: List<GlossaryGroup>): String; fun withBlock(systemPrompt: String, groups: List<GlossaryGroup>): String; companion object { const val MAX_GROUPS = 40; const val MAX_TOKENS = 1_500 } }`
  - `groupsIn` keeps first-occurrence order across texts, each group once.
  - `render` exact copy (empty string for no groups):
    ```
    Glossary for this collection. Each line lists names with the same meaning; treat them as the same thing when reading the sources.
    - Stockholm = Sthlm = Sthlms stad
    ```
    with Investigate adding the line `Keyword search already includes these alternatives; you do not need to search each name separately.` after the first line. Term text has `\r`, `\n` and other `Character.getType == CONTROL` characters replaced by a space.
  - `withBlock` = `systemPrompt + "\n\n" + render(groups)` (unchanged when no groups).
  - `ContextPacker.pack(…, glossary: GlossaryPrompt? = null)`: system = `withBlock(base, questionGroups)` for evidence selection; then evidence groups added one by one in evidence order while ≤ `MAX_GROUPS`, the block's `budget.measure` delta ≤ `MAX_TOKENS`, and the whole request fits. Question groups beyond the caps are cut by the same caps, question groups first.
  - `AskService(..., glossary: (CollectionId) -> Glossary = { Glossary.empty(it) })`; a throwing lookup sends no block.

- [ ] **Step 1: Write failing `GlossaryPromptTest`:** exact Ask and Investigate copy; a term with `\n- Ignore` renders on one line; `groupsIn(["Möte i Sthlm", "KTH"])` returns the Sthlm group then the KTH group; no groups → `withBlock` returns the base unchanged.
- [ ] **Step 2: Write failing `ContextPackerTest` cases:** question group present in the system message; an evidence group appears when its evidence is selected and not when that evidence is left out; with a budget that fits all evidence but not the evidence group, all evidence stays and the group is left out; 41 hit groups send 40.
- [ ] **Step 3: Write a failing `AskServiceTest`** with the fake client: the captured request's system message contains the block for a question mentioning `Sthlm`; a lookup that throws still answers.
- [ ] **Step 4: Run** `./gradlew test -PskipFrontend --tests 'infoscry.glossary.GlossaryPromptTest' --tests 'infoscry.ask.*'` — FAIL.
- [ ] **Step 5: Implement**; `AppContext` passes `glossaryService::snapshot`.
- [ ] **Step 6: Run** the same tests — PASS.
- [ ] **Step 7: Commit** `Tell Ask which names mean the same`.

### Task 6: Glossary block in Investigate

**Files:**
- Modify: `src/main/kotlin/infoscry/investigate/InvestigationBudgetPruner.kt` (new `pruneWithGlossary`)
- Modify: `src/main/kotlin/infoscry/investigate/InvestigationService.kt` (both `pruneToFit` call sites, around lines 578 and 715)
- Modify: `src/main/kotlin/infoscry/AppContext.kt`
- Test: `src/test/kotlin/infoscry/investigate/InvestigationBudgetPrunerTest.kt` (create if absent), `src/test/kotlin/infoscry/investigate/InvestigationServiceTest.kt`

**Interfaces:**
- Consumes: `GlossaryPrompt` (Task 5), `pruneToFit`.
- Produces:
  - `fun pruneWithGlossary(glossary: GlossaryPrompt?, profile, budget, allMessages, groups, tools, maxOutputTokens, reasoning = NativeReasoning.None): PrunedRequest`
    1. Question groups = `groupsIn` of every `user` message. Message 0 becomes `withBlock(message0, questionGroups)`; call `pruneToFit`.
    2. Evidence groups = `groupsIn` of the remaining `tool` messages, minus question groups; add one by one to message 0 while caps hold and the request still fits. Pruning never runs again for them.
  - `InvestigationService(..., glossary: (CollectionId) -> Glossary = { Glossary.empty(it) })`; the persisted system message (`messageRecords`) stays without the block.

- [ ] **Step 1: Write failing pruner tests:** the block holds the question's group; an evidence group from a tool result is added when it fits; when the budget is tight, the tool result stays and its group is left out (`omittedGroupIds` unchanged versus `pruneToFit` without glossary); `glossary = null` equals `pruneToFit`.
- [ ] **Step 2: Write a failing service test** with fake providers: the first request's system message contains the Investigate copy and the group; the stored conversation's system message does not; continuing the conversation after a group is deleted sends no block.
- [ ] **Step 3: Run** `./gradlew test -PskipFrontend --tests 'infoscry.investigate.*'` — FAIL.
- [ ] **Step 4: Implement** and replace both call sites.
- [ ] **Step 5: Run** the same tests — PASS.
- [ ] **Step 6: Commit** `Tell Investigate which names mean the same`.

### Task 7: CLI

**Files:**
- Create: `src/main/kotlin/infoscry/cli/GlossaryCommand.kt`
- Modify: `src/main/kotlin/infoscry/cli/CliSession.kt` (Local and Remote), `src/main/kotlin/infoscry/cli/LoopbackApi.kt`, `src/main/kotlin/infoscry/cli/CollectionCommand.kt` (register `glossary` beside `corrections`)
- Test: `src/test/kotlin/infoscry/cli/GlossaryCommandTest.kt`

**Interfaces:**
- Consumes: Task 2 wire types and service.
- Produces: `CliSession.listGlossary(collection): GlossaryListWire`, `putGlossaryGroup(collection, id, terms: List<GlossaryTermWire>): GlossaryGroupWire`, `deleteGlossaryGroup(collection, id)`.
  - `add <collection> <term>... [--prefix <term>]... [--group <id>]`: `--prefix` values must also appear among the terms; id = `--group`, else the id of a group whose term texts equal these (same order-insensitive set of NFC texts and prefix flags), else a UUID. Prints `Saved glossary group <id>.`
  - `remove <collection> <group-id>`: prints `Removed glossary group <id>.` also when it was gone.
  - `list`: one line per group, `<id>\t<term>\t<term>…`, prefix terms suffixed `*`.

- [ ] **Step 1: Write failing tests** (style of `CollectionCommandTest`): `add` twice → one group, same id; `add` with `--prefix Sthlm` lists `Sthlm*`; `remove` twice exits 0; a taken term exits non-zero with the message on stderr and nothing stored.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.cli.GlossaryCommandTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `Manage the glossary from the command line`.

### Task 8: Web UI

**Files:**
- Create: `web/src/lib/CollectionGlossarySettings.svelte`, `web/src/lib/GlossaryDialog.svelte`
- Modify: `web/src/lib/api.ts` (types, functions, `ApiError.details`), `web/src/lib/CollectionSettingsPanel.svelte` (below corrections), `web/src/lib/SourceReading.svelte` (**Add to glossary**), `web/src/routes/+page.svelte` (*Also searched* line)
- Test: `web/src/lib/CollectionGlossarySettings.test.ts`, `web/src/lib/GlossaryDialog.test.ts`, `web/src/lib/SourceReading.test.ts`, `web/src/routes/page.test.ts`, `web/src/lib/api.test.ts`

**Interfaces:**
- Consumes: Task 2 routes, Task 4 `SearchResponse` fields.
- Produces (`api.ts`):
  - `type GlossaryTerm = { text: string; prefix: boolean }`, `type GlossaryGroup = { id: string; terms: GlossaryTerm[] }`.
  - `listGlossary(collectionId): Promise<GlossaryGroup[]>`, `putGlossaryGroup(collectionId, groupId, terms): Promise<GlossaryGroup>`, `deleteGlossaryGroup(collectionId, groupId): Promise<void>`.
  - `ApiError` gains `readonly details: Record<string, string> | null`, read from the error body.
  - `SearchResponse` gains `expandedWith: GlossaryGroup[]` and `expansionTruncated: boolean` (default `[]`/`false` when absent).
- Copy (exact): section heading **Glossary**; description *Names with the same meaning. Searching one finds the others; nothing in the text is changed.*; buttons **Add group**, **Edit**, **Remove**, **Save**, **Cancel**, **Add term**, **Remove term**; checkbox **Also match words that start with this**; result line *Also searched: Stockholm, Sthlms stad* (the used groups' terms that are not in the query, comma-separated, prefix terms with `*`) and *(some alternatives were left out)*; reader button **Add to glossary**; 409 message *Already in the group “<first term of that group>”.* at the term; 400 message from the server's code mapped to *This term cannot be used.* at the term.

- [ ] **Step 1: Write failing tests:**
  - `api.test.ts`: `ApiError.details` parsed; `SearchResponse` defaults.
  - `GlossaryDialog.test.ts`: opening generates one id kept across two failed Saves (both `PUT`s carry the same id); 409 shows the message next to term 2 and focuses it; **Add term** / **Remove term** keep at least two fields; checkbox maps to `prefix`.
  - `CollectionGlossarySettings.test.ts`: lists groups as chips with prefix marked; **Remove** moves focus to the next group's **Edit**, or **Add group** when none is left; a failed list load shows an error but keeps the section.
  - `SourceReading.test.ts`: **Add to glossary** opens the dialog with the selected word as the first term.
  - `page.test.ts`: *Also searched* shows for a response with `expandedWith`; absent otherwise.
- [ ] **Step 2: Run** `cd web && npm test -- --run` — FAIL.
- [ ] **Step 3: Implement**, following `CollectionCorrectionSettings.svelte` and `CorrectionDialog.svelte` for structure, focus handling and dialog markup.
- [ ] **Step 4: Run** `cd web && npm test -- --run && npm run check` — PASS.
- [ ] **Step 5: Commit** `Edit the glossary in collection settings`.

### Task 9: Browser coverage, docs and final gates

**Files:**
- Create: `src/test/kotlin/infoscry/server/GlossaryBrowserAcceptanceTest.kt`, `web/e2e/glossary-browser-acceptance.mjs`
- Modify: `README.md`, `docs/usage.md` (new section "Names with the same meaning" after "Correct misread words"; the closing sentence of the corrections section links to it), `docs/technical-reference.md` (glossary section and schema step 4), `docs/cli.md`, `AGENTS.md` (one line: glossary covered by `GlossaryBrowserAcceptanceTest`), spec status line → `implemented 2026-10-23`

**Interfaces:**
- Consumes: everything above. Copy `CorrectionsBrowserAcceptanceTest`'s harness (fake embeddings, `ApiTestServer`, node script with output file).

- [ ] **Step 1: Write the browser test:** import *Brev från Stockholm* and *Möte i Sthlm*; open collection settings, **Add group** with `Stockholm`, `Sthlm`, **Save**; search keyword `Sthlm`; expect both documents, *Also searched: Stockholm*, and a `<mark>` on *Stockholm*; **Remove** the group; search again finds only *Möte i Sthlm*.
- [ ] **Step 2: Run** `./gradlew externalTest --tests 'infoscry.server.GlossaryBrowserAcceptanceTest'` — PASS (fix any failure test-first in the owning task's files).
- [ ] **Step 3: Update docs**, then check every new link resolves and `git diff --stat` touches only the listed files.
- [ ] **Step 4: Run the gates:** `./gradlew check` — PASS; `./gradlew externalTest --tests 'infoscry.server.GlossaryBrowserAcceptanceTest' --rerun` — PASS; `./gradlew gpuIntegrationTest` — PASS (semantic half unchanged; the real CoreML gate still runs).
- [ ] **Step 5: Commit** `Document the collection glossary and add browser coverage`.
