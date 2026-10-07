# 10: Manual workflow acceptance

**Status:** Open coordination ticket, decomposed on 2026-10-02. Execute a child ticket, not this umbrella as one implementation task.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts and evidence:** [CONTRACTS.md](CONTRACTS.md), [STATUS.md](STATUS.md).

## Current baseline

Historical full normal runs and a real Surya run are recorded below in STATUS.md. The 2026-10-02 assessment ran 116 focused tests successfully. Neither constitutes acceptance of the unfinished OCR UI, all runtimes or final manual workflow.

## Independently reviewable slices

- [ ] [10a — Shipped browser flow and restart acceptance](10a-browser-acceptance.md)
- [ ] [10b — Real OCR/local image-model/CoreML gates](10b-real-runtime-gates.md)
- [ ] [10c — Documentation and final normal/external/hardware gates](10c-manual-release-checkpoint.md)

## Completion boundary

10c records browser, recovery, real-tool and CoreML evidence independently. Missing runtimes or manual accessibility/image checks remain explicit open gates. Unrelated Ask/source-viewer acceptance is not closed by this feature.

Children retain their own focused tests, files, exclusion boundaries and stop conditions. An umbrella closes only after every child has current verification evidence. Preserve historical passing runs; do not describe future criteria as implemented behavior. No product implementation, runtime installation, private-data experiment, commit or push is authorized by this planning document.
