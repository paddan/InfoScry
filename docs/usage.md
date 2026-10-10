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
4. Press **Import** to open the reading dialog. Choose Tesseract, Surya or an
   enabled image-capable LLM profile. The collection default only pre-selects it.
5. Check the page count, destination and estimated cost, then confirm **Read …**
   or **Send …**. Unavailable methods show their reason.
6. Follow processing progress and inspect the per-file results in import history.

The picker opens on the Mac running the server and requires a graphical session.
When that is unavailable, enter paths manually. Imports use immutable managed
copies and detect duplicates within each collection; originals are unchanged.

A new archive starts empty. Older archives retain an existing automatic
`Default` collection, which you can rename or delete. You may also create a
collection named `Default` yourself.

## Browse documents and import history

**Documents** starts collapsed. Its total and status counts cover the whole
collection, including documents on other pages. Click the total to show all
documents, or a status count to show that status; either clears the filename
filter. **Add documents**, **Settings** and **Import history** remain reachable
while the document list is collapsed. Active import progress and history errors
appear outside the history disclosure.

Expand **Documents** to see filename, reading status and progress in a bounded
scrolling table, with search, status, sorting and pagination outside the scroll
area. Filename search applies after a short pause; Enter applies it immediately.
Search matches filenames only. Filter by status, sort by name or import date,
and browse 50 rows per page.

**Details** opens one inline row inside the table, with media type, size, import
date, readable failure messages, directly extracted units, OCR units and unreadable
units. Opening another document replaces the previous details. Progress uses the
document's units: pages, sections, sheets, slides, lines or images. If the total is
unknown, only the committed count is shown.

Expansion, filename, status and sort are remembered separately for each collection
while navigating within the current browser session. Returning starts at page one
with fresh data. Collapsing, changing filters or leaving clears document selections
and open details. Reloading the browser resets the list to collapsed with its default
filters and sort; selections are never restored.

**Open document** opens PDF/image review with the page image beside extracted
text, including blank pages and documents without extracted text. Other formats
open their first readable unit in the text source viewer. **Open original** opens
the managed copy; the wider format-specific source-viewer finish line remains open.

Page review keeps image and text bound to one exact revision. **Previous** and
**Next** include blank/failed physical pages; an unavailable total is labelled
rather than inferred from extracted pages. Stored OCR images are labelled separately
from original-page renders. Image and text scroll independently, with zoom/fit,
a keyboard/pointer divider, independent resource Retry and bounded **Load more**.
Narrow screens use **Image**/**Text** tabs. The workspace Back action returns to
context while retaining the page; **Open selected source** reopens it. Closing
review returns to the document list with its session filters and sort.

Import history shows current and previous imports, their stage and file
counters. **Show files** reveals pending, imported, duplicate and failed
results. These counters count files, not OCR pages. History and unfinished work
return after navigation, browser reload or a server restart.

Managing a collection in Admin does not switch the workspace's selected
collection or its open conversations. Deleting the selected workspace collection
clears its results and source viewer.

## Change settings and retry processing

Collection settings keep OCR language and one default reading method. Saving a
default does not start a run; the start dialog confirms the method each time.

**Scan again** opens the same dialog for a document's managed copy. Only pages
that need image reading are read; other pages retain their existing text. If no
pages need reading, the dialog explains this and no rescan starts. A successful run replaces
the text and search entries automatically. **Text history** keeps previous
versions and offers **Restore** without reading the pages again.

To force reading of a particular PDF/image page, open it in page review and choose
**Scan this page again**. Use **Mark page N** while navigating, then **Scan selected
pages** to read a sorted selection. The dialog shows exact one-based page numbers,
the reading method, destination and estimated cost. Changing method preserves the
selection. Explicit pages are read even when their PDF text layer is usable; other
pages retain text, IDs, images and method provenance. Back, changing document or
opening history clears marks. Empty selection cannot start a run.

Progress appears in the live reader. A completed reading offers **Refresh**, which
loads image and text from the same active revision; historical review cannot rescan.
Leaving pauses UI status polling while server work continues. If start confirmation
has an uncertain result, retry the frozen request; it keeps the same page set,
preview and request ID until resolved.

Each document shows Imported, Reading n of N, Done, Failed or Cancelled. A
failed document offers **Retry**, which resumes reusable committed pages;
choosing a different method reads them again. Cancelled and interrupted work
can be started again immediately. Cancellation or failure leaves existing
published text intact. A stopped manually selected rescan offers **Resume selected
pages** in document details. It continues the same page set and reading method
and skips committed pages; **Scan again** opens a new run. Repeating a resume
returns the current run without creating another job.

External sending is confirmed at start for the shown
pages; exceeding that count fails the run before another page is sent.

If a failed import item never became a document, add that file again through
**Add documents**. If the managed copy is missing, that document fails safely.
Jobs continue on the server after leaving Admin.

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
OpenAI, Anthropic, Ollama, OpenRouter and Custom endpoints. The form lists the
selected provider's models by itself and fills known context limits, output
limits and prices when you pick one; fill in unknown values manually.
For a new profile, choosing a catalog model also fills the name with the provider
preset label and model ID, such as `OpenRouter/gpt-luna-latest`. The suggested
name follows later model choices until you edit the name yourself. Choosing a
model while editing an existing profile keeps its name.

To let a profile read scanned pages, open it and choose **Check image reading**.
This offers the profile for OCR and sends one test image to its endpoint. Only a
profile that reads the image can be chosen as a reading method for a collection.
Editing the profile afterwards makes it unchecked again until you repeat the
check.

If the image check fails, its result shows InfoScry's explanation, error code
and the provider's HTTP status when available. A provider refusal or timeout
does not establish that the model lacks image support. Failure details describe
that check and are not retained in the saved profile; repeat the check to get a
new result. The provider's raw response is not displayed.

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
Known-revision PDF page and supported-image citations use the image/text
comparison and stay pinned; other locators retain the revision-bound text source view. Search, Ask and
Investigate retain context beside sources when width permits; history yields space
first, and narrow Back keeps the selected page.
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

## Which pages are read

Automatic import/Scan again reads a PDF page with the method you chose when it
has no usable text layer, or when an embedded image covers at least a quarter of the page and the page's
own text scores below 75 (a missing or garbled hidden text layer). Other pages
keep their own text and are not rendered or sent anywhere. The start dialog
counts only the pages that will be read, for example "12 of 48 pages will be
read", and the cost and the external approval follow that count. A picture file
is always one page and is always read. An import with no pages needing image
reading can still proceed with **Import without image reading**.

Explicit page review selection overrides this automatic PDF rule and counts only
the chosen pages. Confirmation and any external allowance use that exact set.
