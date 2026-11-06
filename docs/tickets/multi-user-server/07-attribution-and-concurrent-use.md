# Ticket 7: Attribution, optimistic edits and concurrent use

Spec: [Multi-user server](../../specs/2026-11-05-multi-user-server-design.md), sections *Tracking who did what*
and *Concurrent use*; ticket 7 of the suggested order, after [ticket 6](06-memberships-and-authorization.md).
Status: implemented on branch `attribution` (2026-11-06), not yet merged to `main`.

## Goal

The records people create and change say who did it, and the interface shows it next to the dates it
already shows. Two people editing the same settings cannot silently overwrite each other, and two people
starting the same work get the same job.

## Attribution

| Record | Columns |
|---|---|
| Collection | `created_by`, `updated_by` (name, ignore patterns, OCR settings) |
| Document | `imported_by` |
| Job | `started_by`, `cancelled_by` |
| Document revision (a page text version) | `created_by` |
| Page decision (new `page_decisions`) | `decided_by`, `decided_at` |
| Correction rule, glossary group, collection prompt | `created_by`, `updated_by` |
| LLM profile, OCR profile | `created_by`, `updated_by` |

Memberships (`added_by`), conversations (`owner_id`) and deletions (`requested_by`) were done in ticket 6.

### Decisions

| Question | Decision |
|---|---|
| How the actor reaches the stores | An ambient actor: the request guard runs each request with the caller's user ID in a coroutine context element backed by a thread-local, and the job runner runs each attempt with the job's `started_by`. A store writes `Actor.current()` into the attribution columns. Work a job does (documents, revisions) is thereby attributed to whoever started the job, and recovery after a restart keeps it, because the job row keeps `started_by` |
| Null | **Deviation from the spec**, which asks for non-null columns: the columns are nullable. A person's request or job always sets them; `NULL` means nobody did it in person (startup recovery that has no job, and the many store tests that write rows directly). The spec's non-null rule existed to attribute rows written before the columns were added, and this baseline has no such rows |
| Prompt versions | A prompt keeps one row whose `version` counts its edits; `updated_by` is who made the current version. Conversations already keep the prompt's text and version they used. A per-version history table is not added |
| Showing names | API answers carry `{id, displayName}` for each attribution, resolved in one query per answer |
| Page decisions | Approve, which the web page only remembered until now, and Reject (which restores the previous text) are both recorded per revision. The first decision for a revision decides; a later one answers 200 with the recorded decision and who made it, and a Reject that comes second starts no restore |

## Optimistic edits

- Collections, correction rules and glossary groups get a `version` column; prompts already have one. A read
  answers it, and an edit may send it in `If-Match`. A stale version answers 409 `EDIT_CONFLICT` with the
  current record and who last changed it. An edit whose content equals the current record changes nothing
  and answers 200, whatever version it sent. An edit without `If-Match` (the CLI) applies as before.
- The web sends `If-Match` from what it showed and, on 409, shows "Changed by Erik just now" with the
  current content.

## Starting work

- An import whose confirmed sources equal those of an active (queued or running) import job in the same
  collection answers with that job, whoever started it.
- A rescan of a document under someone else's active OCR operation answers with that operation instead of
  replacing it. A rescan by the user who started the running one keeps today's behaviour (the new choice
  replaces it), so starting again never waits.
- A retry already answers per document (`ALREADY_RUNNING` for a document in work) and is unchanged.

## Interface

Document details show "Imported by Anna, 4 Nov 2026"; page review shows who approved or rejected the reading
on show; collection settings show who last changed corrections, glossary groups and prompts; the Jobs tab
shows who started each job. Controls the caller's role does not allow (renaming, ignore patterns, OCR
settings and deleting for non-owners; editing for readers) are hidden.

## Tests

- Attribution written by a request and by a job (including after a restart), and shown in answers.
- Two users editing the same glossary group, correction and prompt: the second stale edit is 409 with the
  first user's name, a repeat of the same content is 200.
- Two decisions on one page; two imports of the same files by two users; a rescan under another user's
  running operation.
- Web: the conflict message and the attribution lines.

## Verification record (2026-11-06)

- `./gradlew check` with Podman: 2,240 backend and 587 frontend tests, 0 failures.
- `AttributionRoutesTest`: a collection's creator and editor and its version; a stale glossary, correction,
  prompt and collection edit refused with the other person's name, a repeat of the current content and an
  edit without a version accepted; a job's starter and canceller (and nobody for a job enqueued outside a
  request); an import's document and reading attributed to whoever started it; the first page decision
  deciding and a later rejection starting no restore; two people importing the same files getting one job.
  `OcrRoutesTest`: a rescan under another user's running operation answers with it.
- Web: the conflict and attribution lines, page decisions (including someone else deciding first), versions
  sent with edits, and controls hidden from readers and non-owners.
- Manual with the installed distribution, a ParadeDB container and the real CoreML model: two
  administrators; the second imported a file with the CLI and was shown as its importer and as the job's
  starter; the first's glossary edit against the version the second had replaced was refused with
  `EDIT_CONFLICT` naming the second; a hybrid search for a glossary synonym found the document.
- Not run: `externalTest`, `gpuIntegrationTest`, and an explicit kill-and-restart of a running import to
  watch its attribution survive (the runner takes the actor from the job row on every attempt, which is the
  same code path a restart takes).
