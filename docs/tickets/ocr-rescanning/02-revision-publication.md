# 02: Isolated revisions and recoverable publication

**Status:** Done (2026-09-30) for the publication and read-isolation core. Migration 018 adds
`document_revisions`, `page_text_revisions`, `revision_chunks`, `revision_publications` and
`citations.revision_id`; an import or the backfill records an initial published revision, candidates
stage isolated text, and the publication protocol runs PREPARED → staged commit → SQLite authority →
snapshot switch → PUBLISHED with roll-forward and roll-back recovery. Verified by `./gradlew check`
(97 suites, 1133 tests, 0 failures, 14m) and the focused suites, which I re-ran myself after the last fix.

Three independent review rounds drove fixes that are in the tree: untagged baseline rows served beside a
replacement (superseded rows are removed before the snapshot switch), a check-then-increment race in reader
acquisition plus non-exclusive handoffs (atomic acquire, serialized handoffs, base recheck inside the
handoff), a failed publication leaving unowned candidate rows visible (the candidate is hidden before its
rows exist), cleanup writing to the index outside mutation admission, post-authority failures reported as
failures, a draft revision readable through the source route (only PUBLISHED and SUPERSEDED are readable),
and a recovery window that served the old reading beside the new source text (reads are sealed before a
roll-forward whose switch has not happened, and unsealed by the completion).

Known residuals, reported rather than papered over: the Ask viewer's revision-unknown fallback and the
Investigate evidence ledger's missing excerpt/revision columns are [ticket 02b](02b-revision-aware-evidence.md);
a legacy citation that neither its live unit nor any revision that once held the unit can place is dropped
from history (never misattributed); that resolver is an unindexed per-row lookup; the pre-switch seal window
has no automated test, only the gate unit test and the direct 503 mapping assertion; and the implementing
agent wrote production code before its tests, so the strict test-first order was not kept (the recorded
failures and temporary-revert proofs are real).
**Blocked by:** 01.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

A fake replacement can be staged, published and recovered without exposing draft text or mismatching source text and search hits.

## Files and interfaces

- `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt (new)`
- `src/main/kotlin/infoscry/document/RevisionPublicationService.kt (new)`
- `src/main/kotlin/infoscry/storage/ContentStore.kt`
- `src/main/kotlin/infoscry/search/LuceneSchema.kt`
- `src/main/kotlin/infoscry/search/LuceneIndex.kt`
- `src/main/kotlin/infoscry/search/SearchService.kt`
- `src/main/kotlin/infoscry/search/ReindexService.kt`
- `src/main/kotlin/infoscry/jobs/DocumentIngest.kt`
- `src/main/kotlin/infoscry/server/SourceRoutes.kt`
- `src/main/kotlin/infoscry/AppContext.kt`
- `src/main/resources/db/migration/ (new forward migration)`

Consume snapshots; produce immutable page/document revision storage and publish(documentId, baseRevisionId, candidateRevisionId) as described in CONTRACTS.md. All readers use a consistent revision snapshot.

**Tests:** Create src/test/kotlin/infoscry/document/RevisionPublicationTest.kt and RevisionPublicationRecoveryTest.kt; extend ContentStoreTest and SearchService/source-route tests discovered during implementation.

## Test-first implementation

- [x] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Crash before/after staged index commit, authority commit and reader handoff; reopen yields exactly one coherent document version.
  - Search continuously during staging/publication and retain an in-flight reader: no mixed text, missing baseline or leaked candidate.
  - Delete or reindex races with prepared publication; no resurrection and other collections are unchanged.
  - Candidate embedding failure/cancellation leaves baseline text/chunks searchable; exact 512-token tokenizer limits remain enforced.
  - Old saved evidence still shows its original text after a replacement; unknown legacy provenance is labelled honestly.
- [x] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [x] Backfill existing published content, stable unit IDs and revision-owned chunks. Preserve old extraction fingerprints. Candidates use an isolated sink; no current-unit mutation or chunk deletion during staging.
- [x] Implement PREPARED/authority handoff/PUBLISHED recovery with generation-tagged index rows and reader leases. Filter inactive rows before ranking; never let staged rows consume top-k.
- [x] Persist complete rebuildable target artifacts before authority changes. Recovery finishes an authoritative publication before serving, or retains old authority when not committed. Maintenance/deletion and cancellation recheck under the publication boundary.
- [ ] Make source retrieval and newly generated evidence revision-aware. Preserve saved excerpts and resolve legacy citations only where provenance is provable. Reindex only published revisions; historical reads must not require stale Lucene rows. — **Partially:** the store, search hits, citations, the source route and the Ask history API do this; the Ask viewer's revision-unknown fallback and the Investigate evidence ledger are [ticket 02b](02b-revision-aware-evidence.md).
- [x] No approved text is a valid pending-review result, not a false indexing success. Keep keyword/source access to old revisions when CoreML initialization fails.
- [x] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [x] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.document.RevisionPublication*' --tests 'infoscry.storage.*' --tests 'infoscry.search.*' --tests 'infoscry.server.*'
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.

## Continuation ownership (2026-10-02)

Saved-evidence and seal follow-ups are now decomposed under [02b](02b-revision-aware-evidence.md) into 02c, 02d and 02e; the core publication record does not close them.
