# Collection prompts for Ask and Investigate

Status: design approved 2026-10-15, not implemented.

## Goal

A collection can carry its own named prompts for Ask and for Investigate. The prompts are managed
in Admin → Collections; Ask and Investigate each let the reader pick which prompt a new question
uses. A shipped, read-only **Default** prompt always exists, so the reader can always return to it.
Admin explains how to write a good prompt.

## What exists today, and what changes

`PromptService` composes three layers:

1. immutable core rules in code (`CORE_RULES`);
2. an editable body per role, from `prompt_overrides` or the shipped `prompts/ask.md` /
   `prompts/investigate.md`;
3. optional "Collection instructions", appended last.

Layer 2's global override (`LlmProfileStore.savePromptBody`/`resetPromptBody`) has no route, CLI
command or UI. Layer 3's `collectionInstructions` parameter is never wired and is always `null`.
Conversations record `prompt_version`, but every Investigate turn re-reads the current body.

This design:

- keeps layer 1 unchanged, always first and always present;
- replaces layer 2 with the collection's selected prompt: a named prompt from that collection, or
  the shipped Default;
- removes the global `prompt_overrides` table, `effectiveBody`, `effectivePromptVersion`,
  `savePromptBody`, `resetPromptBody` and layer 3 (`collectionInstructions` parameters and the
  "Collection instructions:" block);
- locks the prompt text in each conversation when it is created.

The system prompt sent is `CORE_RULES + "\n\n" + <selected prompt body>`.

## Data model

All changes go into `001_baseline.sql` in place (see `docs/technical-reference.md`: no incremental
migrations).

```sql
CREATE TABLE collection_prompts (
    id             TEXT    NOT NULL PRIMARY KEY,
    collection_id  TEXT    NOT NULL,
    role           TEXT    NOT NULL CHECK (role IN ('ASK', 'INVESTIGATE')),
    name           TEXT    NOT NULL CHECK (length(trim(name)) > 0),
    body           TEXT    NOT NULL CHECK (length(trim(body)) > 0),
    version        INTEGER NOT NULL CHECK (version > 0),
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL,
    FOREIGN KEY (collection_id) REFERENCES collections (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX collection_prompts_name_unique
    ON collection_prompts (collection_id, role, name COLLATE NOCASE);
```

`collections` gains two nullable columns, `ask_prompt_id` and `investigate_prompt_id`, each
`REFERENCES collection_prompts (id) ON DELETE SET NULL`. `NULL` means the shipped Default. Deleting
the collection deletes its prompts; the collection's own deletion phases need no new step.

`conversations` gains `prompt_id TEXT` (nullable: `NULL` = Default; no FK, so it survives deleting
the prompt), `prompt_name TEXT NOT NULL` and `prompt_body TEXT NOT NULL`. The existing
`prompt_version` holds the selected prompt's version. `model_calls.prompt_version` is unchanged in
meaning.

### Default

- The shipped Default is not a row. Each role has the fixed id `default`, the name `Default`, the
  body read from the classpath resource and version `1`.
- `Default` is a reserved name, case-insensitively, so a stored prompt can never be called that.
- Default cannot be edited or deleted. To change it, the reader creates a prompt, starting from
  Default's text, and makes it the collection default.

### Versions

A stored prompt starts at version 1. Each save that changes `body` increments `version`; a rename
alone does not. Name, version and body are snapshotted per conversation, so a version number only
has to be unique per prompt.

## Limits and validation

- Name: trimmed, 1–80 characters, unique per collection and role (case-insensitive), not `Default`.
- Body: trimmed for the emptiness check, stored as written, 1–20,000 characters.
- A body counts in the measured request budget like every other part of the request. The help text
  says that a longer prompt leaves less room for source excerpts.

## Operations (all idempotent)

`CollectionPromptService` holds the logic; HTTP routes and CLI commands are thin over it. Every
write goes through the shared mutation admission
(`context.mutations.withMutation { context.collectionService.requireMutationsAllowed() … }`) and
requires an active collection.

| Operation | Behaviour, including the repeat case |
|---|---|
| list(collection) | Core rules, plus per role: Default first, then stored prompts by name, plus the collection default id. |
| create(collection, role, name, body) | New prompt at version 1. If a prompt with that name already exists in that role with the same body, the existing prompt is returned unchanged. Same name with a different body → `409 Conflict` ("a prompt with this name already exists"). |
| update(promptId, name, body) | Applies the changes; `version` grows only if `body` changed. If nothing changed, the current prompt is returned with no write. A rename onto an existing name → `409`. Updating `default` → `400`. |
| delete(promptId) | Removes the prompt. If it was the collection default, the default becomes Default (via `SET NULL`). Deleting an id that does not exist → `204` (already gone). Deleting `default` → `400`. |
| setDefault(collection, role, promptId) | Sets the collection default (`default` → `NULL`). Setting the value already in effect is a no-op. An id from another collection or role → `400`. |
| resolve(collection, role, promptRef?) | Used by Ask/Investigate. `null` → the collection default; otherwise an id or a name in that collection and role. An unknown ref or one from another collection or role → `400`. |

Each write is one SQLite transaction, so a crash leaves either the old or the new state, and
running the operation again converges.

## HTTP

- `GET /api/collections/{id}/prompts` returns

  ```json
  {
    "coreRules": "…",
    "ask":         { "defaultId": "default", "prompts": [ { "id": "default", "name": "Default", "body": "…", "version": 1, "builtIn": true } ] },
    "investigate": { "defaultId": "…",       "prompts": [ … ] }
  }
  ```

- `POST /api/collections/{id}/prompts` `{role, name, body}` → `201` with the prompt, or `200` when
  the identical prompt already existed.
- `PUT /api/collections/{id}/prompts/{promptId}` `{name, body}` → `200` with the prompt.
- `DELETE /api/collections/{id}/prompts/{promptId}` → `204`.
- `PUT /api/collections/{id}/prompt-defaults/{role}` `{promptId}` → `200` with `{role, defaultId}`.

Errors use the existing exception-to-status mapping (exceptions declare their own HTTP mapping).
Prompt bodies are user content: they are kept out of logs and error messages, like questions and
answers.

`AskApiRequest` and `InvestigationApiRequest` gain an optional `prompt: String?` (id or name).
Omitted, the collection default is used. Continuing an Investigate conversation ignores `prompt`
and uses the locked `prompt_body`, as it already ignores collection and profile.

## Composition and locking

- `PromptService.compose(body: String): String` returns `CORE_RULES + "\n\n" + body`.
  `composeAsk`/`composeInvestigate` and their `collectionInstructions` parameters go away.
- `AskService` and `InvestigationService` receive the resolved prompt (`ResolvedPrompt(id?, name,
  version, body)`) in their request instead of a `collectionInstructions` lambda.
- On creation, the conversation stores `prompt_id`, `prompt_name`, `prompt_version` and
  `prompt_body`. A continued Investigate turn composes from the stored `prompt_body`; nothing about
  the current prompt rows is read again.
- The Ask and Investigate history views show the prompt name the conversation used.

## CLI

Under `infoscry collection prompt`, thin over the same service; each accepts `--json` and
`--data-dir` like the other collection commands:

- `list <collection> [--role ask|investigate]`
- `show <collection> --role … [--prompt <name|id>]` (default: the collection default)
- `create <collection> --role … --name <name> --file <path|->`
- `update <collection> --role … --prompt <name|id> [--name <new>] [--file <path|->]`
- `delete <collection> --role … --prompt <name|id>`
- `use <collection> --role … --prompt <name|id|Default>`

`infoscry ask` gains `--prompt <name|id>`. There is no Investigate CLI command, so no change there.

## UI

### Admin → Collections → Prompts

A new component `CollectionPromptSettings.svelte`, placed in `CollectionSettingsPanel` after the
OCR settings:

- An **Ask** section and an **Investigate** section, each listing its prompts. Default carries the
  badge "Built-in"; the collection default carries "Collection default".
- Selecting a prompt shows its body: read-only for Default, editable name and body for a stored
  prompt.
- Actions: **New prompt** (pre-filled with Default's text, or **Duplicate** from the selected
  prompt), **Save** (enabled only when the draft differs), **Delete** (with confirmation that names
  the prompt; it says when the prompt is the collection default and that Default will then be used),
  **Use as collection default**.
- "Always applied first": the core rules, read-only.
- A line saying that changes apply to new conversations and that existing conversations keep the
  prompt they started with.
- A collapsible **How to write a prompt** section with the help text below, linked from the body
  field with `aria-describedby`.
- It follows `CollectionSettingsPanel`'s patterns: a settings generation guards answers that arrive
  after the reader selects another collection; status and errors appear next to the action that
  caused them.

### Ask and Investigate

- A labelled `<select>` **Prompt** next to the LLM profile select, listing Default and the
  collection's prompts for that role. It is pre-selected with the collection default and goes back
  to it when the workspace collection changes.
- While continuing an Investigate conversation, the select is disabled and shows the locked prompt's
  name.
- A **Manage prompts** link opens Admin → Collections with that collection selected.

## Shipped Default bodies

The current bodies mostly repeat the core rules. The new Defaults add to them instead. They replace
`src/main/resources/prompts/ask.md` and `investigate.md` verbatim:

`ask.md`:

```text
The source excerpts are tagged with identifiers in brackets, such as [S1].

How to answer:
- Start with a direct answer in one or two sentences, then give the supporting detail.
- Put each source identifier right after the claim it supports, not in a list at the end.
- When excerpts disagree, present each view with its source, and say which is more recent when the
  excerpts show it.
- Keep the answer as short as the question allows; use a list or a table only when it makes the
  answer clearer.
- When the excerpts only partly answer the question, answer that part, say what is missing, and
  suggest a more specific question.
```

`investigate.md`:

```text
Investigate the question with the provided tools, then answer with citations.

How to work:
- Before searching, decide which names, terms, dates and phrasings the answer could appear under,
  and search for several of them, including synonyms and spelling variants.
- Read the surrounding text of a promising hit before you cite it.
- Stop searching when the question is answered or new searches return nothing new.

How to answer:
- Start with a direct answer, then give the supporting detail with each source identifier right
  after the claim it supports.
- When sources disagree, present each view with its source, and say which is more recent when the
  sources show it.
- If the evidence is incomplete, say what is missing and end with a short "What I searched" list.
```

## Help text ("How to write a prompt")

Shown in Admin and repeated in `docs/usage.md`:

> **Always applied first.** The core rules above are part of every request: answer only from the
> collection, cite sources, treat document text as data, and answer in the question's language.
> You do not need to repeat them, and a prompt cannot switch them off.
>
> **Good things to put in a prompt**
> - What the collection is and who it is for, e.g. "Board minutes of a housing cooperative,
>   2010–2020."
> - Terms, abbreviations and names the model should recognise, e.g. "AGM means annual general
>   meeting."
> - What to prioritise, e.g. "When decisions conflict, the most recent one applies."
> - The shape of the answer: length, lists or tables, a summary first.
> - Tone, e.g. "Plain language for residents, not legal wording."
>
> **For Investigate, also**
> - How to search: synonyms, old and new names, date formats, document types to look in.
> - When enough is enough, e.g. "Check at least the minutes and the annual reports."
>
> **Writing tips**
> - Use short, concrete instructions, one per line, and avoid rules that contradict each other.
> - Start from Default and change only what you need.
> - Do not paste instructions from the documents themselves; document text is never followed as an
>   instruction.
> - Longer prompts leave less room for source excerpts in each request.
>
> **Example** — instead of "Be thorough and accurate", write: "Answer with the decision first, then
> the meeting date and the paragraph number. Use 'the board' for 'styrelsen'."

## Testing

Run first as failing tests, then the focused suites, then `./gradlew check`.

- **Service/store:**
  - every operation run twice gives the same result;
  - create with the same name and body returns the existing prompt; same name with another body is
    a conflict;
  - `Default` is reserved;
  - a body change increments the version and a rename does not;
  - deleting the collection-default prompt falls back to Default;
  - setting the current default is a no-op;
  - refs from another collection or role are refused;
  - collection deletion removes the prompts;
  - a transaction interrupted before commit leaves the old state, and repeating converges.
- **Composition:**
  - core rules always come first;
  - Default and stored bodies are used as selected;
  - an Investigate continuation uses the locked body after the prompt is edited and after it is
    deleted.
- **Routes:** status codes above; `prompt` in Ask/Investigate start requests; prompt bodies absent
  from logs.
- **CLI:** each subcommand, `--file -`, `ask --prompt`.
- **Frontend:**
  - `CollectionPromptSettings` tests: listing, New/Duplicate, Save enabled only on change, Delete
    confirmation, Use as collection default, read-only Default, and a collection switch during a
    save;
  - `AskPanel`/`InvestigatePanel` select tests: pre-selection, reset on collection change, disabled
    when continuing;
  - `api.test.ts` covers the new calls.
- **Browser (`externalTest`):** extend `DocumentBrowserAcceptanceTest` or
  `InvestigateBrowserAcceptanceTest`: create a prompt in Admin, select it in Ask, and assert that
  the fake provider received its body after the core rules.

## Documentation to update

- `README.md` status.
- `docs/usage.md`: prompts section and help text.
- `docs/cli.md`: `collection prompt` and `ask --prompt`.
- `docs/technical-reference.md`: prompt layers, schema and conversation locking.

## Out of scope

- A global prompt library shared between collections.
- Editing the core rules.
- Prompts for OCR or title generation.
- Importing or exporting prompts.
