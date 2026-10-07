# Using InfoScry

[Project overview](../README.md) · [Installation](installation.md) · [CLI reference](cli.md)

Start `infoscry serve` and open its printed URL. The dark web reader has a
sidebar and four views: **Search**, **Ask**, **Investigate** and **Admin**.
Switching views preserves their current content. Search filters apply only to
Search.

## Create a collection and import files

1. Open **Admin → Collections** and create a collection.
2. Select the destination collection and choose **Add documents**.
3. Use **Choose files…** or **Choose folder…**. Enable **Include subfolders**
   to import a folder recursively.
4. Follow processing progress and inspect the per-file results in import history.

The picker opens on the Mac running the server and requires a graphical session.
When that is unavailable, enter paths manually. Imports use immutable managed
copies and detect duplicates within each collection; originals are unchanged.

A new archive starts empty. Older archives retain an existing automatic
`Default` collection, which you can rename or delete. You may also create a
collection named `Default` yourself.

## Browse documents and import history

The collection's document table shows filename, media type, size, import date,
status and processing progress. Search filenames, filter by status, sort by
name or import date, and browse 50 rows per page.

**Details** shows metadata, readable failure messages, directly extracted units,
OCR units and unreadable units. Progress uses the document's units: pages,
sections, sheets, slides, lines or images. If the total is unknown, only the
committed count is shown.

**Open document** opens its first readable unit in the extracted-source viewer.
Documents without readable content say so. The reader offers a managed-original
link; format-specific source views remain unfinished.

Import history shows current and previous imports, their stage and file
counters. **Show files** reveals pending, imported, duplicate and failed
results. These counters count files, not OCR pages. History and unfinished work
return after navigation, browser reload or a server restart.

Managing a collection in Admin does not switch the workspace's selected
collection or its open conversations. Deleting the selected workspace collection
clears its results and source viewer.

## Change settings and retry processing

Edit a collection's name or OCR languages in its management area. Renaming
preserves its documents and history. Language changes apply to future imports
and explicit retries; saving them does not reprocess completed documents.

A failed, cancelled or tool-blocked document offers **Retry**. It reads from
the managed copy, so moving the external original does not prevent retrying.
Successfully read units are retained where reusable; changed OCR languages may
require extraction again.

**Retry all eligible documents** covers the whole collection, including hidden
pages and documents outside the current filter. The result distinguishes
accepted attempts from skipped documents and explains refusals, such as an
active import, deletion or missing runtime dependency.

If a failed import item never became a document, add that file again through
**Add documents**. If the managed copy is missing, that document fails safely.
Queued retries continue on the server after leaving Admin.

## Delete documents or collections

- **Delete** removes one document after confirmation.
- Row checkboxes and **Delete selected** remove the confirmed set together.
  Select-all covers the current page only; changing the collection, filter,
  sort or page clears the selection.
- Collection deletion requires typing the collection's exact name.

Deletion permanently removes managed content and search entries. It interrupts
affected work after its current step; deleting one document leaves other files
in its import running. Original files outside InfoScry are never touched.

The panel shows **Deleting…** until the server finishes. Pending deletions
return after reload or restart. Saved links to deleted documents report
unavailable content.

## Search your archive

Select a collection in the workspace and enter a query in **Search**. Results
appear as you type, in the selected mode; press Enter or **Search** to run the
query immediately. Changing the mode, collection, or any advanced filter
searches the current query again; typing in path or metadata filters is briefly
debounced. Matched words are highlighted in the result excerpt, with source text
escaped before display.

| Mode | Use |
|---|---|
| Keyword | Match words and phrases |
| Semantic | Find passages by meaning |
| Hybrid | Combine keyword and semantic retrieval; the default |

Hybrid returns results only when the archive holds a term that one of the
query's words begins — an exact match or a term the query is a prefix of. A
query with no such foothold is answered with no results, rather than with the
passages that happen to sit nearest to it. Semantic mode is the explicit
nearest-neighbour mode: it always returns the closest passages, even for a query
whose words the archive has never seen.

Refine results with file type, path, metadata text, import dates, document status
or OCR-only filtering. Import dates accept inclusive calendar days in the web
interface; the `infoscry search` CLI also accepts ISO instants. Open a result to
read its source. Semantic and hybrid
retrieval require the local embedding model and supported GPU runtime.

If an archive was indexed before chunker version 3, run `infoscry reindex --wait`
to add short text such as single letters, digits and formulas that the earlier
chunking rule omitted. Existing extraction checkpoints and source files are
preserved.

## Configure LLM profiles

Open **Admin → LLM profiles** to create, edit or delete a profile. Presets cover
OpenAI, Anthropic, Ollama, OpenRouter and Custom endpoints. **Fetch models** lists
models and fills known context limits, output limits and prices; fill in unknown
values manually.

A profile identifies the endpoint, model and environment-variable name for its
API key. The server reads the key from its own environment; the value never
reaches the browser. You can test a profile and optionally select separate
defaults for Ask and Investigate. Testing makes real requests to that endpoint.

Ask and Investigate send questions and selected source evidence to the chosen
endpoint, which may be external. See the
[technical privacy boundaries](technical-reference.md#privacy-and-local-api-boundaries).

## Ask a question

In **Ask**, select a collection and LLM profile and enter a question. Ask answers
from one retrieval pass and streams an answer with citations. Open a citation
to inspect the source passage. A citation saved with a recorded revision opens
that exact reading, so a later rescan cannot change what an old answer shows.
When the saved citation names no revision, the viewer shows the excerpt saved
with the answer under a “Revision unknown” label rather than the document's
current text.

Reopen stored answers from that collection's conversation history. A default
profile only preselects a choice; a configured profile must be selected.

Citation validation checks identifiers against evidence sent to the model.
Read the cited passages to assess whether they support the answer's claims.

## Investigate and ask follow-up questions

In **Investigate**, select a collection and profile and start a conversation.
The model can search and read sources over several tool steps. Follow-ups can
reuse retained evidence, and saved conversations can be reopened after reload.
Each completed turn displays one adopted final answer. Opening saved evidence
follows the same rule as Ask: evidence recorded with its revision opens that
exact reading, and evidence naming no revision shows its saved excerpt under a
“Revision unknown” label.

The sidebar sets the limits for the next question or follow-up:

| Setting | Default | Range |
|---|---|---|
| Tool rounds | 50 | 1–50 |
| Tool calls | 50 | 1–100 |
| Time per question | 600 seconds | 10–1800 seconds |

The first limit reached stops research and triggers a final answer using the
available evidence. With no usable evidence, the answer reports that limitation.
Settings persist in this browser and do not change an already running turn.
A synchronous native tool may finish after the deadline, so the time setting
is not a strict wall-clock cutoff.

## Jobs, logs and maintenance

Use the CLI for job inspection, cancellation, logs and reindexing:

```bash
infoscry jobs
infoscry jobs cancel <job-id>
infoscry logs --follow
infoscry reindex --wait
```

See the [CLI reference](cli.md) for filtering, JSON output and data-directory
options, and [implementation status](implementation-status.md) for open checks.
