# OCR workflow redesign: choose at start, read everything, no review

Status: approved for implementation on 2026-10-08. Implementation and verification
are recorded in the [ticket status](../tickets/ocr-workflow-redesign/STATUS.md). It supersedes the
parts of [selectable OCR and rescanning](2026-09-30-ocr-rescanning.md) listed
under "Superseded contracts". Where the two disagree, this document wins once
approved; until then the older spec describes the shipped behavior.

## Why

Import, scanning and approval are hard to follow in the shipped UI:

- A run starts, then pauses for an external-page approval the person did not
  expect, and the pause is shown as "Complete".
- Engine, transcription profile, mode, reviewer and allowance are separate
  fields on the collection and again per scan. They can contradict each other
  ("engine LLM needs a transcription profile, and a local engine must not name
  one") and the person cannot tell which values a run will use.
- Pages can wait for review while the review list is empty, which leaves a
  document unusable.

The redesign gives one place to decide, one confirmation, and one result.

## Product decisions (from the owner)

1. The **document** is the unit of work. Each document shows one status and one
   next action.
2. Import of a file or a directory, and **Scan again**, share one **start
   dialog**. The reading method is chosen each time. The collection's default
   method pre-fills the dialog; the dialog is always shown and must be
   confirmed. Nothing starts silently on a default.
3. **All pages are read** with the chosen method. There is no "fill missing"
   versus "check and improve" mode, and no page limit to enter.
4. External sending is approved **once, in the dialog**, for all pages shown.
5. There is **no reviewer** and no review step. A finished run replaces the
   document's text. The previous version stays in history and can be restored.
6. OCR language belongs to the collection.
7. **A stopped run never blocks the next one.** If an import or rescan is
   cancelled, fails, or is cut off in any other way (process killed, provider
   gone, machine restarted), the person can always start again. No message about
   an operation that "waits for approval", "has pages to review" or "cannot
   start yet" may be the answer to starting again. See "Always restartable".
8. The application is single-user. Protection for other readers of a document
   while it is being rescanned is not required.
9. For an external import with an unknown page count, approval covers every page
   in the exact unchanged files shown in the dialog. The confirmed source
   manifest (path, size and SHA-256) is the authority; no exact page allowance or
   exact cost is claimed. A retry may retain that scope only when it resumes the
   original reading against the same immutable managed document and unchanged
   method snapshot.

## Reading methods

A **reading method** is one entry in one list, never a pair of fields:

| Method | Where pages go | Needs |
|---|---|---|
| Tesseract | this machine | installed tool |
| Surya | this machine | installed model |
| LLM: *profile name* | the profile's endpoint (local or external) | an enabled OCR profile |

Engine and profile are stored together as one value, so a method cannot name an
engine without its profile or the reverse. A method that cannot run now (tool
missing, profile disabled, key variable unset, image check not passed) is
listed with the reason and cannot be chosen.

## Start dialog

Opened by *Import* (file or directory) and by *Scan again* on a document.

- **Method list** with the collection's default pre-selected.
- **Summary** for the selection: number of pages that will be read, destination
  (`this machine` or the endpoint host for an external profile), and, for an
  external profile with known prices, an estimated cost with its basis. An
  unknown price says so.
- **Confirm button** names the consequence: `Read 48 pages with Surya` or
  `Send 48 pages to <host> with <profile>`. Pressing it is the approval of
  exactly those pages and that method.
- For an import of a directory the page total is computed before the dialog
  opens (see "Preview"). A file whose page count cannot be determined is listed
  as such; the total then reads `at least N` and the approval covers the stated
  files only. When the method is external, an unknown count means the exact
  unchanged files shown are approved for all their pages; the dialog says that
  no exact cost estimate is available.

## Preview

`POST /api/imports/preview` and the existing rescan preview return, for a
proposed method: the files or document, page counts, destination, cost estimate,
and a snapshot hash binding them. Starting the run submits that hash. A run
whose inputs changed since the preview (file added or removed, method changed,
profile revision changed) is refused with a message to preview again; it does
not read a different set of pages than the person confirmed.

## Run and document status

States shown per document: **Imported**, **Reading n of N**, **Done**,
**Failed**, **Cancelled**. There is no *waiting for approval*, *needs review* or
*complete with pending pages* state.

- Pages are committed and checkpointed one at a time as today. A failure (for
  example the provider cannot be reached) ends the run as **Failed** with a
  plain cause label; **Retry** resumes from the committed pages and never pays
  for a page twice.
- When every page is read, the new text replaces the document's text and is
  indexed. This is one publication, using the existing publication machinery,
  without a candidate that waits for a person.
- Cancel stops the run; nothing is published; the existing text is unchanged.
- The previous version is kept in history. **Restore** is unchanged.
- A run never sends more pages than were confirmed. If it would, it ends as
  Failed with that reason rather than pausing.

## Always restartable

The rule: **starting a run never depends on the state a previous run left
behind.**

- A new run for a document that has an unfinished or stopped run of its own
  (running, queued, failed, cancelled, interrupted) replaces that run. If a
  stopped run holds committed pages for the same method, the new run may reuse
  them; otherwise they are ignored. The person is not asked to cancel, resume,
  discard or approve first.
- A run that is genuinely still executing in this process is the only thing a
  new start waits for, and the dialog says so ("Reading 12 of 48; start over?")
  and offers to cancel it and start again in one step.
- On startup, runs left in a non-terminal state by a previous process are
  marked **Failed (interrupted)**. Nothing stays "running" or "waiting" across a
  restart.
- Partial results are never published. A cancelled or failed run leaves the
  document's text exactly as it was.
- Data written by the shipped flow is cleaned up once, by migration or on first
  start: jobs stored as complete with an `awaiting-approval` stage become
  Cancelled; operations that hold a candidate revision with pending pages have
  that candidate withdrawn. After that no stored state can produce a
  "waits for approval" or "pending review" refusal.
- Start, Retry and Cancel are idempotent: repeating one returns the current
  state instead of an error. Idempotence is the governing principle for every
  step of this redesign, not only these three: preview, start, page commit,
  publication, migration, startup recovery and cleanup must each be safe to run
  twice and must converge from any partial state a crash leaves. A start carries
  a client request id; the same id and body returns the run that exists, as the
  shipped rescan admission does.

## Collection settings

Kept: OCR language, default reading method (engine and profile as one value).
Removed: import mode, reviewer profile, external page allowance. The removed columns remain for database compatibility and are no longer read or written;
idempotent startup cleanup withdraws stale work. a stored engine/profile pair that disagrees is
resolved to the engine's local method.

## Single-user simplifications (read this part carefully)

These relax guarantees the older spec makes for concurrent readers:

- A rescan no longer refuses to start, or holds the document, because another
  reader or operation might see intermediate state. Two writers to the same
  document are still serialized by the shared mutation admission.
- Publication no longer waits for readers to drain from the previous revision.

Unchanged, because they protect data rather than other users and `AGENTS.md`
requires them: per-page durable checkpoints and resume, immutable originals and
managed copies, stable content-unit ids, atomic index publication that preserves
all collections, recovery of unfinished publications and collection deletion,
and keeping keys, questions and document text out of logs.

## Superseded contracts

| Older contract | Replaced by |
|---|---|
| Collection engine, profiles, mode, reviewer, allowance as import defaults | Collection language and default method only |
| Per-scan overrides merged field by field with collection settings | One method chosen in the dialog |
| Import modes FILL_MISSING and CHECK_AND_IMPROVE | All pages read |
| Reviewer, comparison, review decisions, *Review pages*, *Discard pending pages* | Removed; history and Restore |
| External page allowance, mid-run approval, `awaiting-approval` stage, `POST /api/jobs/{id}/approve-external` | Approval in the dialog; no mid-run pause |
| Candidate revision held for a person before publication | Publish when the run completes |
| Scan again refused while a scan or pending review holds the document | Not applicable |

Removal happens in steps after the new flow works: first the UI and routes stop
offering the old flow, then the backend code and tickets 08d–08f, 11a and 11b
are retired. Ticket and status documents are updated with each step.

## Verification

- Backend tests with fake providers and temporary data directories for: preview
  hash binding and stale refusal, a run that reads every page, publication on
  completion, failure and resume without re-reading committed pages, and
  cancellation leaving the text unchanged.
- Idempotence tests for each operation above: call it twice, and interrupt it
  after every durable step then call it again; the end state and the response
  are the same as an uninterrupted single call.
- Restart tests: after cancel, failure, provider loss and a simulated process
  kill at each stage, a new start for the same document succeeds without any
  prior action, and the text is unchanged until a run completes. A database
  written by the shipped flow (paused job, pending candidate) is cleaned and
  restartable.
- Web tests for the dialog: pre-filled default, unavailable methods with
  reasons, summary and cost text, confirm label, and the document status line.
- The existing `externalTest` Playwright acceptance is rewritten for the new
  flow with the local fake provider.
- Real CoreML and a real external provider are not exercised by these tests and
  stay unverified until run by hand.

## Open items

- Cost estimate for LLM profiles uses the existing token assumption per page;
  whether a better estimate is wanted is not decided.
- Whether Restore should also be offered right after a run (an *Undo* link on
  the Done status) is not decided.
