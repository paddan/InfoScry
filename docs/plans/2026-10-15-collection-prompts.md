# Collection Prompts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Named, per-collection Ask and Investigate prompts, managed in Admin → Collections and
chosen on the Ask and Investigate pages, with a read-only shipped Default and help text.

**Architecture:** A new `collection_prompts` table and two nullable default columns on
`collections`, behind `CollectionPromptStore` and `CollectionPromptService`. Ask and Investigate
resolve a `ResolvedPrompt` up front, compose `CORE_RULES + body`, and snapshot the prompt into the
conversation. The global `prompt_overrides` layer and the unwired "collection instructions" layer
are removed. HTTP, CLI and the Svelte UI are thin over the service.

**Tech Stack:** Kotlin/JVM 25, Ktor, SQLite, Clikt; SvelteKit/TypeScript, Vitest; Playwright via
`externalTest`.

**Spec:** [docs/specs/2026-10-15-collection-prompts-design.md](../specs/2026-10-15-collection-prompts-design.md)

## Global Constraints

- Schema changes are edited into `src/main/resources/db/migration/001_baseline.sql` in place; there are no incremental migrations.
- The system prompt is `CORE_RULES + "\n\n" + <selected prompt body>`; `CORE_RULES` stays unchanged and first.
- The Default prompt has id `default`, name `Default` and version `1`; it is not a row and is never editable or deletable.
- `Default` is a reserved name, case-insensitively.
- Name: trimmed, 1–80 characters, unique per collection and role, case-insensitively.
- Body: 1–20,000 characters, not blank after trimming, stored as written.
- Every write goes through `context.mutations.withMutation { context.collectionService.requireMutationsAllowed() … }` and requires an active collection.
- Every operation is idempotent (see the spec's Operations table). Each write is one SQLite transaction.
- Prompt bodies stay out of logs and error messages.
- Product copy is in English. The UI uses plain, accessible HTML/CSS.
- Backend iteration: `./gradlew test -PskipFrontend --tests <class>`. Frontend: `cd web && npm test -- --run && npm run check`. Run `./gradlew check` before reporting done.

## Review Focus

1. **Case-insensitive names beyond ASCII.** SQLite `NOCASE` folds only ASCII, so `Ägare` and `ägare` would both pass the index. The service must compare `name.trim().lowercase(Locale.ROOT)` itself, and reject `DEFAULT` and `default`. Test in Task 2.
2. **Prompt deleted in Admin while Ask/Investigate has it selected.** The start request then gets a 400. The panel should show the error, reload the prompt list and fall back to the collection default, not stay stuck on an id that no longer exists. Test in Task 7.
3. **Workspace collection switched while the prompt list is loading.** A late answer for the previous collection must not fill the select, and a stale id from another collection must never be sent. Test in Task 7.
4. **CRLF and trailing newlines from `--file`.** A CLI-saved body read on Windows-style line endings would otherwise count as a new version against the same text saved from the UI. The CLI normalises `\r\n` to `\n` before sending. Test in Task 5.
5. **A large prompt with a small context window.** A 20,000-character body with a small `contextWindow` profile must produce the existing budget error event (`ContextBudgetExceeded` / the Investigate budget error), not a crash or a truncated prompt. Test in Task 3.

---

### Task 1: Schema, store and shipped Default prompts

**Files:**
- Modify: `src/main/resources/db/migration/001_baseline.sql`: the `collections` table; add `collection_prompts` after `collections`; remove the `prompt_overrides` table. The `conversations` columns come in Task 3, together with the code that writes them.
- Modify: `src/main/resources/prompts/ask.md`, `src/main/resources/prompts/investigate.md`: replace with the spec's "Shipped Default bodies", verbatim.
- Create: `src/main/kotlin/infoscry/llm/ShippedPrompts.kt`
- Create: `src/main/kotlin/infoscry/storage/CollectionPromptStore.kt`
- Test: `src/test/kotlin/infoscry/storage/CollectionPromptStoreTest.kt`, `src/test/kotlin/infoscry/llm/ShippedPromptsTest.kt`

**Interfaces:**
- Produces:
  - `data class CollectionPrompt(val id: String, val collectionId: CollectionId, val role: LlmPromptRole, val name: String, val body: String, val version: Int, val builtIn: Boolean)`
  - `object ShippedPrompts { const val DEFAULT_ID = "default"; const val DEFAULT_NAME = "Default"; fun body(role: LlmPromptRole): String; fun prompt(collectionId: CollectionId, role: LlmPromptRole): CollectionPrompt }`. `body` reads `prompts/<role lowercase>.md` from the classpath, moved from `LlmProfileStore.shippedPromptBody`.
  - `class CollectionPromptStore(database: Database)` with:
    - `fun list(collectionId: CollectionId, role: LlmPromptRole): List<CollectionPrompt>`: stored rows only, ordered by `name COLLATE NOCASE`.
    - `fun findById(id: String): CollectionPrompt?`
    - `fun insert(collectionId: CollectionId, role: LlmPromptRole, name: String, body: String): CollectionPrompt`: version 1.
    - `fun update(id: String, name: String, body: String): CollectionPrompt`: `version + 1` only when `body` differs; `updated_at` is set on any change.
    - `fun delete(id: String): Boolean`
    - `fun defaultPromptId(collectionId: CollectionId, role: LlmPromptRole): String?`
    - `fun setDefaultPromptId(collectionId: CollectionId, role: LlmPromptRole, promptId: String?)`

Schema: exactly the spec's DDL. `collections` gains:

```sql
ask_prompt_id         TEXT REFERENCES collection_prompts (id) ON DELETE SET NULL,
investigate_prompt_id TEXT REFERENCES collection_prompts (id) ON DELETE SET NULL
```

Define `collection_prompts` directly after `collections`. SQLite checks FK targets when rows are
written, not at `CREATE TABLE`, so the forward reference from `collections` is valid.

- [ ] **Step 1: Write failing tests**
  - `CollectionPromptStoreTest`:
    - `insert then list returns the prompt at version 1`
    - `update with a changed body increments version`
    - `update with only a new name keeps version`
    - `delete of the collection default sets defaultPromptId to null`
    - `deleting the collection removes its prompts`: use `DELETE FROM collections`, then `list` is empty.
    - `setDefaultPromptId round-trips and null clears it`
  - `ShippedPromptsTest`:
    - `prompt has id default, name Default, version 1, builtIn true`
    - `ask body starts with "The source excerpts are tagged"`
    - `investigate body starts with "Investigate the question with the provided tools"`
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.storage.CollectionPromptStoreTest' --tests 'infoscry.llm.ShippedPromptsTest'`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement the schema, the resources, `ShippedPrompts` and `CollectionPromptStore`.**
  - Ids are `UUID.randomUUID().toString()`; timestamps use `Instants.now()`.
  - Leave `LlmProfileStore`'s prompt functions in place until Task 3, but point its `shippedPromptBody` at `ShippedPrompts.body`.
  - `prompt_overrides` is dropped here, so `effectiveBody` returns `ShippedPrompts.body(role)` and `effectivePromptVersion` returns `1` until Task 3 removes them. Remove `savePromptBody`/`resetPromptBody` and their tests now.
- [ ] **Step 4: Run** the same command, plus `--tests 'infoscry.storage.SchemaMigratorTest' --tests 'infoscry.llm.LlmProfileStoreTest'`. Expected: PASS.
- [ ] **Step 5: Commit** `Add collection_prompts schema, store and new shipped Default prompts`.

### Task 2: CollectionPromptService

**Files:**
- Create: `src/main/kotlin/infoscry/collection/CollectionPromptService.kt`
- Modify: `src/main/kotlin/infoscry/AppContext.kt`: add `val collectionPrompts: CollectionPromptService` next to `collectionService`.
- Test: `src/test/kotlin/infoscry/collection/CollectionPromptServiceTest.kt`

**Interfaces:**
- Consumes: Task 1's store, `ShippedPrompts`, `CollectionService.requireActive`, `MutationCoordinator.withMutation`, `CollectionService.requireMutationsAllowed`.
- Produces:
  - `data class ResolvedPrompt(val id: String?, val name: String, val version: Int, val body: String)` in `infoscry/llm/ResolvedPrompt.kt`. `id == null` means Default.
  - `data class RolePrompts(val defaultId: String, val prompts: List<CollectionPrompt>)`: Default first.
  - `data class CollectionPrompts(val coreRules: String, val ask: RolePrompts, val investigate: RolePrompts)`
  - `data class PromptWrite(val prompt: CollectionPrompt, val created: Boolean)`
  - `class PromptNameTaken : ApiFaultException(ApiStatus.CONFLICT, "PROMPT_NAME_TAKEN", "a prompt with this name already exists")`
  - `class InvalidPromptRequest(message: String) : ApiFaultException(ApiStatus.BAD_REQUEST, "INVALID_REQUEST", message)`. Messages never include the body.
  - `class CollectionPromptService(store, collections: CollectionService, mutations: MutationCoordinator, coreRules: () -> String)` with:
    - `fun list(collectionId: CollectionId): CollectionPrompts`
    - `suspend fun create(collectionId, role, name, body): PromptWrite`
    - `suspend fun update(collectionId, promptId, name, body): CollectionPrompt`
    - `suspend fun delete(collectionId, promptId)`: no-op when missing.
    - `suspend fun setDefault(collectionId, role, promptId): String`: returns `defaultId`.
    - `fun resolve(collectionId, role, ref: String?): ResolvedPrompt`: `ref` is an id or a name.
- `PromptService` exposes `fun coreRules(): String` (Task 3 also uses it). Add it here as a one-liner returning `CORE_RULES`.

- [ ] **Step 1: Write failing tests** (`CollectionPromptServiceTest`, temp data dir like `CollectionStoreTest`):
  - `create twice with same name and body returns created=false and the same id`
  - `create with taken name and other body throws PromptNameTaken`
  - `names compare case-insensitively beyond ASCII`: create `Ägare`, then create `ägare` with another body throws `PromptNameTaken`.
  - `Default is reserved`: names `Default`, ` default `, `DEFAULT` all throw `InvalidPromptRequest`.
  - `name over 80 chars, blank body and body over 20000 chars are refused`
  - `update with no change returns the prompt and leaves updated_at unchanged`
  - `update or delete of default throws InvalidPromptRequest`
  - `delete twice succeeds`
  - `setDefault to the value in effect is a no-op; promptId from another collection or role throws InvalidPromptRequest`
  - `resolve(null) returns the collection default, or Default when none`
  - `resolve by name and by id; unknown ref throws InvalidPromptRequest`
  - `list puts Default first with builtIn true and reports defaultId`
  - `writes on a DELETING collection are refused` (the error `requireActive` already throws).
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.collection.CollectionPromptServiceTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Validation, the name comparison (`trim().lowercase(Locale.ROOT)` against `list(...)`) and idempotence live in the service. The store stays dumb.
  - Each write runs inside `mutations.withMutation { collections.requireMutationsAllowed(); collections.requireActive(id); … }`.
  - A prompt id is checked to belong to `collectionId` (and role, where one is given). Otherwise it is `InvalidPromptRequest`, or a no-op for a missing id in `delete`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `Add CollectionPromptService with idempotent prompt operations`.

### Task 3: Composition, conversation locking, removal of old layers

**Files:**
- Modify: `src/main/resources/db/migration/001_baseline.sql`: `conversations` gains `prompt_id TEXT`, `prompt_name TEXT NOT NULL` and `prompt_body TEXT NOT NULL`, after `prompt_version`.
- Modify: `src/main/kotlin/infoscry/llm/PromptService.kt`: remove `composeAsk`/`composeInvestigate` and the store dependency; add `fun compose(body: String): String`. Update the class doc to two layers.
- Modify: `src/main/kotlin/infoscry/storage/LlmProfileStore.kt`: remove `effectiveBody`, `effectivePromptVersion`, `shippedPromptBody` and `SHIPPED_PROMPT_VERSION`.
- Modify: `src/main/kotlin/infoscry/ask/AskService.kt`: `AskRequest` gains `val prompt: ResolvedPrompt`; remove the `collectionInstructions` parameters; compose with `prompt.compose(request.prompt.body)`.
- Modify: `src/main/kotlin/infoscry/storage/ConversationStore.kt`: `persistAsk` writes `prompt_id`, `prompt_name`, `prompt_body` and `prompt_version` from a new `prompt: ResolvedPrompt` parameter. Drop the `effectivePromptVersion` lookup.
- Modify: `src/main/kotlin/infoscry/investigate/InvestigationService.kt`:
  - `InvestigateRequest` gains `val prompt: ResolvedPrompt`.
  - `InvestigateHistory.promptVersion` is replaced by `val prompt: ResolvedPrompt`.
  - `InvestigatePersistence.createConversation(collectionId, profile, prompt: ResolvedPrompt, retrievalSnapshot)`
  - Remove the constructor params `promptVersion` and `collectionInstructions`.
  - Locked prompt is `history?.prompt ?: request.prompt`; the system prompt is `prompt.compose(locked.body)`; `lockedPromptVersion = locked.version`.
- Modify: `src/main/kotlin/infoscry/storage/InvestigationLedgerStore.kt`: persist and load the prompt columns.
- Modify: `src/main/kotlin/infoscry/server/AskRoutes.kt`, `src/main/kotlin/infoscry/server/InvestigationRoutes.kt`:
  - `AskApiRequest` and `InvestigationApiRequest` gain `val prompt: String? = null`.
  - Resolve with `context.collectionPrompts.resolve(collection.id, role, body.prompt)` before building the service. A continue ignores `prompt`.
- Modify: `src/main/kotlin/infoscry/cli/AskCommand.kt`: resolve with `ref = null` for now (Task 5 adds `--prompt`).
- Test:
  - `src/test/kotlin/infoscry/llm/PromptServiceTest.kt`
  - `src/test/kotlin/infoscry/investigate/` (existing service tests: add a locking test)
  - `src/test/kotlin/infoscry/server/AskRoutesTest.kt`, `InvestigationRoutesTest.kt`
  - `src/test/kotlin/infoscry/llm/ConversationStoreTest.kt`

- [ ] **Step 1: Write failing tests**
  - `PromptServiceTest`:
    - `compose puts core rules first, then a blank line, then the body`
    - `compose never contains "Collection instructions"`
  - Investigate service test, `continuation uses the locked body after the prompt is edited`: create a conversation with prompt body `"A"`; the fake persistence's history returns prompt `"A"`; continue with `request.prompt` body `"B"`; assert the fake client's system message ends with `"A"`.
  - `ConversationStoreTest`, `persistAsk stores prompt id, name, version and body`.
  - `AskRoutesTest`:
    - `prompt by name reaches the provider after the core rules`: create prompt `Short` with body `"Answer in one line."`; the fake provider's system message ends with it.
    - `unknown prompt gives 400 INVALID_REQUEST`
    - `prompt from another collection gives 400`
    - `prompt body is absent from logs`: reuse the existing log-capture helper used for question text.
  - `InvestigationRoutesTest`:
    - `prompt by id is locked; continue with another prompt keeps the first`
    - `deleting the prompt does not break continuing`
  - Review Focus 5, in `AskRoutesTest`: `20000-char prompt with a small context window returns the budget error event`.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.llm.*' --tests 'infoscry.investigate.*' --tests 'infoscry.server.AskRoutesTest' --tests 'infoscry.server.InvestigationRoutesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the changes listed under Files.**
  - Fix every compile error caused by the removed functions.
  - `grep -rn "effectivePromptVersion\|effectiveBody\|collectionInstructions\|composeAsk\|composeInvestigate" src` must return nothing.
- [ ] **Step 4: Run** the same command, then `./gradlew test -PskipFrontend`. Expected: PASS.
- [ ] **Step 5: Commit** `Compose Ask and Investigate from the selected collection prompt and lock it per conversation`.

### Task 4: Prompt HTTP routes and history prompt name

**Files:**
- Create: `src/main/kotlin/infoscry/server/CollectionPromptRoutes.kt`: `fun Route.collectionPromptRoutes(context: AppContext)`, registered next to the collection routes in `Routes.kt`.
- Modify: `src/main/kotlin/infoscry/server/AskRoutes.kt` (`AskSummaryWire` and its query) and the Investigate summaries query: add `promptName: String`.
- Test: `src/test/kotlin/infoscry/server/CollectionPromptRoutesTest.kt`, plus history assertions in `AskRoutesTest` and `InvestigationRoutesTest`.

**Interfaces:**
- Consumes: Task 2's service and types.
- Produces (wire, `@Serializable`):
  - `PromptWire(id, name, body, version, builtIn)`
  - `RolePromptsWire(defaultId, prompts)`
  - `CollectionPromptsWire(coreRules, ask, investigate)`
  - `CreatePromptRequest(role: LlmPromptRole, name, body)`
  - `UpdatePromptRequest(name, body)`
  - `SetPromptDefaultRequest(promptId)`
  - `PromptDefaultWire(role, defaultId)`
- Endpoints: exactly the spec's HTTP section. `POST` answers `201` when `created`, otherwise `200`. Writes require CSRF like `put("/ignore-patterns")`.

- [ ] **Step 1: Write failing tests**:
  - `GET returns coreRules and Default first for both roles`
  - `POST creates 201, repeat 200 same id`
  - `POST taken name 409 PROMPT_NAME_TAKEN`
  - `PUT updates and bumps version only on body change`
  - `PUT default 400`
  - `DELETE 204 twice`
  - `PUT prompt-defaults sets and resets with "default"`
  - `writes without CSRF are refused`
  - `ask and investigation history include promptName`
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.server.CollectionPromptRoutesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the routes and the history field.**
- [ ] **Step 4: Run** the same command plus `AskRoutesTest` and `InvestigationRoutesTest`. Expected: PASS.
- [ ] **Step 5: Commit** `Add collection prompt HTTP routes and prompt name in history`.

### Task 5: CLI

**Files:**
- Modify: `src/main/kotlin/infoscry/cli/CollectionCommand.kt`: `PromptCommand` (name `prompt`) with subcommands `list`, `show`, `create`, `update`, `delete`, `use`, following `IgnoreCommand`'s shape (`--json`, `--data-dir`, `CliFailure`).
- Modify: `src/main/kotlin/infoscry/cli/CliSession.kt`, `src/main/kotlin/infoscry/cli/LoopbackApi.kt`: add `listPrompts`, `createPrompt`, `updatePrompt`, `deletePrompt` and `setPromptDefault`, with local implementations over `context.collectionPrompts` and remote ones over Task 4's endpoints.
- Modify: `src/main/kotlin/infoscry/cli/AskCommand.kt`: `--prompt <name|id>`, passed to `resolve`.
- Test: `src/test/kotlin/infoscry/cli/CollectionCommandTest.kt`, `src/test/kotlin/infoscry/cli/AskCommandTest.kt`

**Interfaces:**
- Consumes: Task 2's service and Task 4's wire types (the remote path decodes `CollectionPromptsWire`).
- Options:
  - `--role ask|investigate` is required, except on `list`.
  - `--prompt <name|id>` selects a prompt; for `use` it also accepts `Default`.
  - `--name <name>`.
  - `--file <path|->`: `-` reads stdin. The body is read as UTF-8 and `\r\n` becomes `\n`.
  - `show` prints the body only; `list` prints `name<TAB>version<TAB>(default)`.

- [ ] **Step 1: Write failing tests**:
  - `create from file then list shows it`
  - `create from stdin with dash`
  - `CRLF file is stored with LF` (Review Focus 4): create from a CRLF file, then update from the same text with LF; the version stays 1.
  - `show without --prompt prints the collection default body`
  - `use Default resets`
  - `delete twice exits 0`
  - `ask --prompt Short sends that body`
  - `ask --prompt Unknown fails with a message naming the prompt`
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.cli.CollectionCommandTest' --tests 'infoscry.cli.AskCommandTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `Add collection prompt CLI commands and ask --prompt`.

### Task 6: Admin → Collections → Prompts

**Files:**
- Modify: `web/src/lib/api.ts`:
  - Types `CollectionPrompt`, `RolePrompts`, `CollectionPrompts`.
  - Functions:
    - `getCollectionPrompts(collectionId): Promise<CollectionPrompts>`
    - `createCollectionPrompt(collectionId, role, name, body): Promise<CollectionPrompt>`
    - `updateCollectionPrompt(collectionId, promptId, name, body): Promise<CollectionPrompt>`
    - `deleteCollectionPrompt(collectionId, promptId): Promise<void>`
    - `setCollectionPromptDefault(collectionId, role, promptId): Promise<string>`
  - `startAsk` and `startInvestigation` gain an optional trailing `prompt?: string`, sent as `prompt` only when defined.
  - `AskHistoryEntry` and `InvestigationSummary` gain `promptName: string`.
- Create: `web/src/lib/CollectionPromptSettings.svelte`, `web/src/lib/promptHelp.ts` (the spec's help text as structured data, rendered by the component, so the copy lives in one place).
- Modify: `web/src/lib/CollectionSettingsPanel.svelte`: render `<CollectionPromptSettings {collection} />` after `CollectionOcrSettings`.
- Test: `web/src/lib/CollectionPromptSettings.test.ts`, `web/src/lib/api.test.ts`

**Interfaces:**
- Consumes: Task 4's wire shape.
- Produces: `CollectionPromptSettings` props: `collection: Collection`.

Behaviour, copy and layout as in the spec's UI section. The settings-generation guard follows `CollectionSettingsPanel` (`settingsGeneration`). The Delete confirmation uses the existing dialog pattern (`dialogFocus.ts`).

- [ ] **Step 1: Write failing tests**:
  - `lists Default first with Built-in badge and marks the collection default`
  - `Default is read-only`
  - `New prompt pre-fills Default's text; Duplicate copies the selected prompt`
  - `Save is disabled until the draft changes`
  - `409 shows "a prompt with this name already exists" next to Save`
  - `Delete asks for confirmation naming the prompt and mentions fallback when it is the collection default`
  - `Use as collection default calls setCollectionPromptDefault`
  - `a collection switch during save discards the late answer`
  - `help section is collapsed by default and linked by aria-describedby`
  - `api.test.ts`: each new function's method, URL and body; `startAsk` sends `prompt` only when given.
- [ ] **Step 2: Run** `cd web && npm test -- --run CollectionPromptSettings api`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `cd web && npm test -- --run && npm run check`. Expected: PASS.
- [ ] **Step 5: Commit** `Add prompt management to Admin collection settings`.

### Task 7: Prompt select on Ask and Investigate

**Files:**
- Create: `web/src/lib/PromptSelect.svelte`: shared by both panels.
- Modify: `web/src/lib/AskPanel.svelte`, `web/src/lib/InvestigatePanel.svelte`, `web/src/routes/+page.svelte` (the **Manage prompts** link opens Admin → Collections with the collection selected, through the existing Admin-collection selection).
- Modify: `web/src/lib/HistoryColumn.svelte` (or wherever the entries render): show `promptName` when it is not `Default`.
- Test: `web/src/lib/AskPanel.test.ts`, `web/src/lib/InvestigatePanel.test.ts`, `web/src/routes/page.test.ts`

**Interfaces:**
- Consumes: Task 6's `getCollectionPrompts` and the `startAsk`/`startInvestigation` `prompt` argument.
- Produces: `PromptSelect` props `collectionId: string`, `role: 'ASK' | 'INVESTIGATE'`, `disabled: boolean`, `lockedName: string | null`, `bind:value: string | undefined`, plus `export function reload(): Promise<void>`.

- [ ] **Step 1: Write failing tests**:
  - `select pre-selects the collection default and sends its id`
  - `collection change resets to the new collection's default` (Review Focus 3): the first collection's `getCollectionPrompts` resolves after the second's; the select shows the second's prompts, and the started request carries the second's id.
  - `deleted prompt` (Review Focus 2): `startAsk` answers 400 `INVALID_REQUEST`; the panel shows the error, reloads and selects the collection default.
  - `Investigate continuing disables the select and shows the locked name`
  - `Manage prompts opens Admin Collections for that collection` (`page.test.ts`)
  - `history shows a non-Default prompt name`
- [ ] **Step 2: Run** `cd web && npm test -- --run AskPanel InvestigatePanel page`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `cd web && npm test -- --run && npm run check`. Expected: PASS.
- [ ] **Step 5: Commit** `Choose the prompt on Ask and Investigate`.

### Task 8: Browser acceptance and documentation

**Files:**
- Modify: `src/test/kotlin/infoscry/server/DocumentBrowserAcceptanceTest.kt` (Ask flow).
- Modify: `README.md`, `docs/usage.md` (prompts section and help text), `docs/cli.md`, `docs/technical-reference.md` (prompt layers, schema, locking). Also update `AGENTS.md`'s "Current repository" paragraph only if it names the browser coverage.

- [ ] **Step 1: Write the failing browser test** `Ask uses a prompt created in Admin`:
  - open Admin → Collections → Prompts, create `Short` with body `Answer in one line.`;
  - open Ask, choose `Short`, ask a question;
  - assert that the fake provider's last system message is `coreRules + "\n\n" + "Answer in one line."`.
- [ ] **Step 2: Run** `./gradlew externalTest --tests 'infoscry.server.DocumentBrowserAcceptanceTest'` (no `-PskipFrontend`: it needs the compiled frontend). Expected: PASS, since Tasks 1–7 provide the behaviour. If it fails, fix the cause before continuing.
- [ ] **Step 3: Update the docs.** In `docs/technical-reference.md`, replace the prompt-layer description with two layers plus the per-conversation snapshot. Copy the help text verbatim from the spec into `docs/usage.md`.
- [ ] **Step 4: Run** `./gradlew check`. Expected: BUILD SUCCESSFUL. Check the doc links with `grep -o '](\.\./[^)]*)' docs/*.md` and confirm each target exists.
- [ ] **Step 5: Commit** `Cover collection prompts in the browser and document them`. Delete this plan in the same commit once the open gates are recorded, following AGENTS.md.
