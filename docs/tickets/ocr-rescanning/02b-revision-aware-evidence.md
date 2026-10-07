# 02b: Revision-aware saved evidence and read sealing

**Status:** Open coordination ticket, decomposed on 2026-10-02. Execute a child ticket, not this umbrella as one implementation task.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts and evidence:** [CONTRACTS.md](CONTRACTS.md), [STATUS.md](STATUS.md).

## Current baseline

Ticket 02 implemented revision-aware citations and publication. Ask still opens a live unit when revision is unknown. Investigate already stores `evidence_ledger.excerpt` (migration 006 and LlmStore); it lacks ledger revision provenance, and InvestigationRoutes history joins live units. The older claim that the ledger has no excerpt column was incorrect. The pre-switch seal window needs its own integration regression.

## Independently reviewable slices

- [ ] [02c — Ask saved-excerpt viewer](02c-ask-evidence-viewer.md)
- [ ] [02d — Investigate ledger revision provenance](02d-investigate-evidence-provenance.md)
- [ ] [02e — Publication seal/recovery acceptance](02e-publication-seal-acceptance.md)

## Completion boundary

All three slices pass. Saved evidence never silently displays current text as historical evidence; a failed publication handoff refuses inconsistent reads and recovers coherently.

Children retain their own focused tests, files, exclusion boundaries and stop conditions. An umbrella closes only after every child has current verification evidence. Preserve historical passing runs; do not describe future criteria as implemented behavior. No product implementation, runtime installation, private-data experiment, commit or push is authorized by this planning document.
