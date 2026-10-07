# 08: Admin controls and manual review

**Status:** Open coordination ticket, decomposed on 2026-10-02. Execute a child ticket, not this umbrella as one implementation task.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts and evidence:** [CONTRACTS.md](CONTRACTS.md), [STATUS.md](STATUS.md).

## Current baseline

Profile, collection-setting, rescan-operation, review-decision and revision-list routes already exist. Extend and verify them rather than rebuilding them. Current review routes require a rescan operation; staged/partially published imports still lack a usable decision surface. OCR Admin/review panels are not implemented.

## Independently reviewable slices

- [ ] [08a — OCR profiles](08a-ocr-profile-admin.md)
- [ ] [08b — Collection defaults](08b-collection-ocr-controls.md)
- [ ] [08c — Preview, approval and operation controls](08c-rescan-controls.md)
- [ ] [08d — Bounded review/image API for import and rescan](08d-review-read-api.md)
- [ ] [08e — Guarded decisions and publication](08e-review-decision-publication.md)
- [ ] [08f — Page review UI](08f-page-review-ui.md)

## Completion boundary

A user can configure, import/rescan, approve external scope and resolve pages in Admin. Both initial/partially published imports and rescans are covered; publication is durable and pending text stays outside retrieval.

Children retain their own focused tests, files, exclusion boundaries and stop conditions. An umbrella closes only after every child has current verification evidence. Preserve historical passing runs; do not describe future criteria as implemented behavior. No product implementation, runtime installation, private-data experiment, commit or push is authorized by this planning document.
