# 09: Published history and restoration

**Status:** Open coordination ticket, decomposed on 2026-10-02. Execute a child ticket, not this umbrella as one implementation task.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts and evidence:** [CONTRACTS.md](CONTRACTS.md), [STATUS.md](STATUS.md).

## Current baseline

Revision storage, publication and a revision-list route exist. Restore and history UI are not verified complete. Confirm current deletion cascades and phases before adding code.

## Independently reviewable slices

- [ ] [09a — Guarded restore service/API](09a-restore-service.md)
- [ ] [09b — History/restore UI](09b-history-ui.md)
- [ ] [09c — Revision-aware deletion recovery](09c-revision-deletion-acceptance.md)

## Completion boundary

History is bounded, restoration creates a new publication without OCR, historical evidence remains readable, and deletion cleans owned revisions/proposals without resurrection.

Children retain their own focused tests, files, exclusion boundaries and stop conditions. An umbrella closes only after every child has current verification evidence. Preserve historical passing runs; do not describe future criteria as implemented behavior. No product implementation, runtime installation, private-data experiment, commit or push is authorized by this planning document.
