# 02b: Revision-aware saved evidence (Ask viewer and Investigate ledger)

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 02 (the publication gate is verified; this ticket carries what it left open).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

A saved excerpt opens the text it was actually taken from, or says plainly that its revision is unknown —
in the Ask viewer and in Investigate history, not only in the API.

## Why this is separate from 02

[Ticket 02](02-revision-publication.md) made search hits and citations carry revision provenance, made the
source route serve a named revision only when it was published and refuse an unplaceable one, and made Ask
history return each citation's own saved excerpt instead of dropping it or rewriting it to current text.
Two read paths were left, and both are display or schema work in other subsystems rather than publication
machinery:

- The Ask viewer forwards a citation's revision when it has one, but opens the *live* unit when it does
  not, so a legacy citation with an unrecorded revision can still show today's text beside yesterday's
  answer, and an excerpt whose unit a replacement removed cannot be opened at all.
- The Investigate evidence ledger has no excerpt or revision columns, and its history inner-joins the
  ledger to live units, so it has the same two failure modes: misattributed text, or dropped evidence.

The third item carried here is the test ticket 02 left open: the pre-switch seal window (a completion
failure whose snapshot switch never happened) is covered by a gate unit test and a direct 503 mapping
assertion, but not by an end-to-end test that reads while the service has sealed itself.

## Files and interfaces

- `web/src/routes/+page.svelte`, `web/src/lib/api.ts`
- `src/main/kotlin/infoscry/server/InvestigationRoutes.kt`
- `src/main/kotlin/infoscry/investigate/InvestigationService.kt` (evidence written with its provenance)
- `src/main/kotlin/infoscry/storage/` (ledger columns; the migration number must be verified at coding time — 018 is applied)
- `docs/technical-reference.md` (the ledger's provenance columns, once they exist)

## Test-first implementation

- [ ] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Ask evidence with an unknown revision shows its saved excerpt, not the document's current text.
  - An excerpt whose unit a replacement removed still opens, and its revision is labelled unknown.
  - Investigate history keeps an excerpt whose unit was removed, marks its revision unknown, and never renders current text as old evidence.
  - The ledger's write path stores what its read path needs (excerpt and revision together with the evidence).
  - A completion failure whose switch never happened seals reads: the roll-forward fails too, a search refuses rather than mixing readings, and recovery completes the publication and unseals.
- [ ] The Ask viewer: open the named revision when the citation has one; otherwise show the saved excerpt with a revision-unknown label instead of fetching the live unit.
- [ ] The Investigate ledger: store the excerpt and the revision with the evidence, read them back, and give the viewer the same choice Ask has.
- [ ] Make the seal test deterministic with a counter-based step hook (fail at `AUTHORITATIVE`, then again at the second `STAGED_COMMITTED` so the roll-forward fails as well); assert the refusal, then recovery completing and unsealing.
- [ ] While touching the ledger, index the citation-to-document resolver that ticket 02 left as an unindexed per-row lookup if Ask history reads at archive scale make it visible; otherwise record why it was left.
- [ ] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [ ] Review the diff against the spec and update STATUS.md. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.server.*' --tests 'infoscry.investigate.*' --tests 'infoscry.document.RevisionPublication*' --tests 'infoscry.storage.*'
(cd web && npm test -- --run && npm run check && npm run build)
```

New test classes named above are planned paths where they do not exist yet. A successful fake-provider run
does not satisfy a real-tool or hardware gate.
