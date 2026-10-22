# Collection corrections

Status: design approved 2026-10-22; not implemented.

## Goal

A reading sometimes gets a word wrong in the same way across many pages: the surname *Spinnars* is read as
*spinners*. A person keeps a standing list of corrections per collection. The list applies to every
document in the collection now, and to every later import, rescan and restore, so the same misreading never
comes back. The text follows the list: changing or removing a rule changes the text back everywhere.

Corrections are for text that is **wrong**. Synonyms and abbreviations (*Sthlm* for *Stockholm*) are
correct as written and are not replaced; they belong to a separate, later sub-project (a collection
glossary used by search expansion and by Ask and Investigate).

## Decisions

| Question | Decision |
|---|---|
| Lifetime of a correction | A standing rule per collection, applied now and to every future import, rescan and restore |
| Matching | Whole words or phrases, case-insensitive, the text's own writing kept; an optional exact-case flag |
| Rule removed or changed | The text follows the list. The reading is kept as the base and the rules are applied again |
| Text kinds | All text: OCR readings and documents' own text (text layers, e-books, Office, plain text) |
| Character sets | Full Unicode; text is UTF-8 at every boundary (see [Unicode](#unicode)) |
| Construction | A corrected revision that keeps its reading (approach 1 below) |

### Approaches considered

1. **Corrected revision that keeps its reading (chosen).** The published page text is the corrected text,
   so chunking, Lucene, embeddings, the reader, citations and selectable PDF read it unchanged. A
   corrected page also stores its uncorrected reading. Applying the list is one idempotent operation per
   document: reading + rules → publish if different.
2. **Correct at read time.** Every consumer would have to apply the rules, saved excerpts and offsets would
   drift from stored text, and a rule change would still need re-indexing and re-embedding. Rejected.
3. **A separate edit layer per page, merged at publication.** Same result as 1 with an extra structure to
   keep consistent. Rejected.

## Rules

### Storage

New table `collection_corrections`:

| Column | Meaning |
|---|---|
| `id` | Client-chosen id (`PUT` is idempotent) |
| `collection_id` | References `collections (id) ON DELETE CASCADE` |
| `find` | Text to find, NFC, trimmed, inner whitespace collapsed to single spaces |
| `replace` | Replacement, NFC, trimmed, inner whitespace collapsed |
| `exact_case` | `0` or `1` |
| `created_at`, `updated_at` | Timestamps |

- `find` is unique per collection by its case-folded form among rules without `exact_case`, and by its
  exact form among rules with `exact_case`. A taken `find` answers `409 CORRECTION_FIND_TAKEN`.
- Refused with `400 INVALID_REQUEST`: blank `find` or `replace`, `find` equal to `replace` (exactly), a
  `find` that does not begin and end with a letter, mark or digit, and either value over 200 characters.
- The **list fingerprint** is SHA-256 over the rules' normalized `(find, replace, exact_case)` triples in
  a canonical order. It describes the list, not the time of an edit, so an edit that changes nothing leaves
  it unchanged.
- Rules are user content: kept out of logs and error messages, like prompt bodies.

### Matching

- **Whole words.** A match begins and ends at a word boundary: the text's start or end, or a neighbouring
  code point that is not a letter (`L*`), combining mark (`M*`) or digit (`Nd`). `spinners,` and
  `(spinners)` match; `spinnersen` and `ospinners` do not.
- **Phrases.** Each single space in `find` matches one or more whitespace code points in the text,
  including line breaks. When `replace` has as many words as `find`, the text's own separators are kept
  (a line break stays a line break); otherwise the separators become single spaces.
- **Hyphenation** across a line break (`spin-⏎ners`) is not matched in v1.
- **Case**, without `exact_case`: matching is by Unicode full case folding with `Locale.ROOT`, never the
  default locale. The written replacement is:
  - `replace` contains an upper- or title-case letter (it has its own case, as in `Spinnars`): written as
    is, except when every cased letter of the match is upper case, then written in upper case
    (`SPINNARS`);
  - `replace` is all lower case: it follows the match. All upper → upper case; first cased letter upper
    and the rest lower → first letter title case; otherwise as written.
  - Letters of scripts without case are left as written.
- **Exact case.** With `exact_case`, only the exact code-point sequence (after NFC) matches, and the
  replacement is written exactly.
- **Several rules.** All rules run in one left-to-right pass. Where several rules match at one position,
  the longest match (in code points of the text) wins; ties go to the `exact_case` rule, then to the rule
  with the smaller `id`. Replaced text is never matched again: rules do not chain, and the result does not
  depend on the order of the list.

### Unicode

Text is handled as Unicode throughout and is UTF-8 at every boundary: SQLite, artifacts, HTTP JSON, CLI
arguments and output, and the Lucene index.

- Matching works on code points, never on UTF-16 units: a match never begins or ends inside a surrogate
  pair, and never separates a base letter from its combining marks.
- Matching compares canonically equivalent text as equal. Rules are stored in NFC; the matcher runs over
  the NFC form of the page text with a map from that form back to the stored text, so a decomposed `å`
  in a reading matches a rule written with a composed `å`. Text outside a match is never rewritten, so
  a match changes nothing else in the page (including its normalization form).
- **Scripts written without spaces** (Han, Hiragana, Katakana, Thai, Lao, Khmer, Myanmar): a whole-word
  rule could never find a boundary there. When `find` consists only of code points of these scripts it
  matches as a substring instead, with no boundary requirement.
- Right-to-left scripts need no special handling: matching is in logical order.

## Applying the list

### When

- Every change to the list (add, change, remove) is stored at once and enqueues a `CORRECTIONS` job
  (`JobType.CORRECTIONS`) for the collection. At most one such job is queued or running per collection;
  enqueueing while one exists returns that job.
- The job records the list fingerprint it started with. A change during the run makes the job take one
  more pass when it reaches the end, until a pass completes with the fingerprint unchanged.
- The job shows progress per document ("312 of 1,200 documents") in the import history and can be
  cancelled. A cancelled, failed or interrupted job never blocks a new one.

### Per document

1. Read the active revision's pages. A page's **base text** is its stored reading when it has one,
   otherwise its text.
2. Apply the current rules to each page's base text (both `extracted_text` and `search_text`, see
   [Offsets](#offsets)).
3. When every page's result equals its published text, nothing happens: no revision, no embedding.
4. Otherwise create a revision with provenance `CORRECTIONS`, its parent the active revision:
   - every page is carried over as a Restore carries it (producer, artifacts, source image, layout and
     structure references), with the corrected texts;
   - a page whose corrected text differs from its base text stores the base text in two new columns,
     `reading_extracted_text` and `reading_search_text` (both present or both absent); a page whose text
     equals its base text has neither;
   - the revision stores `corrections_fingerprint` and `corrections_applied` (JSON: per rule that changed
     something, its `find`, `replace` and match count);
   - it is published through `RevisionPublicationService` with the active revision as its base, so
     chunks, embeddings, the Lucene index, history and the selectable-PDF hook follow as for any
     publication.

A removed rule therefore brings back the reading; when no rule applies any longer, the page's reading
columns are absent again.

### Offsets

- A replacement yields an edit map (offset and length change per match). Each text is matched on its
  own: `extracted_text` and `search_text` each get their own edits and their own map.
- `PageStructure` ranges into each text are shifted by that text's map. A range that a match crosses at one
  end is extended or shortened to the match boundary. The corrected structure is written as a new
  content-addressed artifact.
- Layout spans (`PageTextLayoutStore`) are shifted by the `extracted_text` map. A match spanning several
  spans (a phrase over a line break) puts its replacement in the first span and shortens the others.
- The existing invariant checks then run. A structure that fails falls back to a paragraph-only structure
  marked degraded; a layout that fails leaves that page unselectable in the export. Text and citations
  are unaffected, as today.

### Conflicts, interruptions and GPU

- If an import, rescan or restore publishes the same document meanwhile, the job's publication is refused
  because its base revision is no longer active; the job processes that document again from the new active
  revision.
- An interrupted publication is completed or discarded by the existing startup recovery.
- An interrupted job starts again from the beginning; documents that already follow the list are skipped
  by the text comparison in step 3.
- Publication requires embeddings, as Restore does. When CoreML cannot start, the job fails with that
  cause; the rules stay stored, and **Retry** continues later. Search and source viewing keep working with
  the published text.

## Imports, rescans and restores

- **Import.** After a document's pages are read, the collection's current rules are applied before the
  candidate is staged. Extraction checkpoints and OCR artifacts always hold the uncorrected reading, so a
  changed list never repeats OCR or external sending, and the extraction fingerprint does not include the
  list. A list changed during an import is caught by the `CORRECTIONS` job afterwards.
- **Rescan.** The new reading is the base; the rules are applied before publication. Page review shows the
  corrected new text with **Approve** and **Reject**, as today. The word differences compare corrected
  texts, so corrections do not show as differences. The quality scores before and now are computed on the
  readings, since they judge the engine.
- **Restore and Reject.** Restore (and Reject, which is a Restore) takes the restored revision's readings
  and applies **today's** rules. History says "Restored from …, with the collection's corrections". The
  existing check that refuses a Reject when the text changed meanwhile compares the corrected text that was
  shown.

## Reader and history

- The document history lists a `CORRECTIONS` revision as, for example, "Corrections: spinners → Spinnars
  (14 matches)".
- The reader shows the corrected text. A page with corrections has a small marker and **Show reading**,
  which shows that page's uncorrected reading.
- Saved Ask citations and Investigate ledger entries keep their own excerpt and revision; old answers show
  the text as it was. New questions use the corrected text. Selectable PDF uses the corrected text.

## User interface

- Collection settings get a **Corrections** section beside prompts and OCR: a table with *Wrong*,
  *Correct*, *Exact case* and *Matches*, with **Edit** and **Remove** per rule, and a status line: "All
  documents follow the list" or "Applying: 312 of 1,200 documents" linking to the job.
- **Preview before saving.** Adding or editing a rule first shows "14 matches in 6 documents" and up to
  five examples in context ("…letter from **spinners** → **Spinnars** to…"), each linking to its page.
  **Save** makes the rule standing and starts the job; there is no separate apply step. Removing a rule
  asks first: "The text returns to the reading in 6 documents."
- **From the reader.** Selecting a word in the reader offers **Correct across collection**, which opens the
  same dialog with the selection as *Wrong* and the preview already run.
- Match counts, here and in `corrections_applied`, count matches in `extracted_text`.
- The preview matches against base texts, the same text the job uses. Lucene may narrow the candidate
  documents; the matcher decides what counts.
- Copy is in English; the controls are normal HTML form elements and are keyboard accessible.

## HTTP and CLI

- `GET /api/collections/{id}/corrections` lists rules with match counts and the job status.
- `PUT /api/collections/{id}/corrections/{ruleId}` creates or replaces a rule; repeating it returns the
  same rule. `DELETE` on the same path removes it; repeating it answers as removed.
- `POST /api/collections/{id}/corrections/apply` starts the job again (Retry); it returns the running job when there is one.
- `POST /api/collections/{id}/corrections/preview` takes a draft rule (and an optional `ruleId` it
  replaces) and answers counts and examples. It changes nothing.
- The existing bearer and CSRF boundaries apply.
- CLI: `infoscry collection corrections list|add|remove|preview <collection>` (beside `collection prompt`), thin over the same service. A
  standalone CLI run owns the job until it completes, as an import does; with a running server the server
  owns it.

## Schema

- The baseline gains `collection_corrections`, the `CORRECTIONS` job type, the two reading columns on
  `page_text_revisions` and `content_units`, and `corrections_fingerprint` and `corrections_applied` on
  `document_revisions`.
- An upgrade step takes a version 2 database to version 3 in one transaction, with foreign keys checked
  before it commits; `jobs` is rebuilt for the new type check.

## Testing

- **Matcher:** boundaries with å, ä, ö and combining marks; surrogate pairs (emoji, rare Han); NFC versus
  NFD text; case folding (`ß`, Turkish `İ`/`ı` under `Locale.ROOT`); each case rule; phrases across line
  breaks; scripts without spaces as substrings; longest match wins; no chaining.
- **Offsets:** structure ranges and layout spans shift correctly, including a phrase across spans; failed
  invariants fall back as specified.
- **Service:** applying twice gives one revision; removing a rule restores the reading and clears the
  columns; interrupting at each publication step and restarting converges; a concurrent rescan causes the
  document to be redone; import and rescan apply the rules; Restore and Reject apply today's rules; a GPU
  failure fails the job and Retry continues.
- **HTTP and CLI:** idempotent `PUT` and `DELETE`, conflict and validation answers, preview changes
  nothing.
- **Browser (`externalTest`, Playwright):** add a rule from the reader, preview, save, see the corrected
  text and **Show reading**, remove the rule and see the reading back. Fake providers and embeddings.
- **Real CoreML** run on the local Mac before the feature is reported as working.

## Out of scope

- The collection glossary for synonyms and abbreviations (separate sub-project).
- Rules shared between collections.
- Regular expressions.
- Words hyphenated across a line break.
