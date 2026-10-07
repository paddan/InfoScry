# 11: Measured pilot and guarded activation

**Status:** Open coordination ticket, decomposed on 2026-10-02. Execute a child ticket, not this umbrella as one implementation task.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts and evidence:** [CONTRACTS.md](CONTRACTS.md), [STATUS.md](STATUS.md).

## Current baseline

Pilot policy and validation storage exist, but a complete transcription/reviewer/model/prompt/policy binding must be verified before any future activation. No pilot-quality result or automatic activation is claimed.

## Independently reviewable slices

- [ ] [11a — Manifest and local scoring harness](11a-evaluation-harness.md)
- [ ] [11b — Human pilot, threshold acceptance and validated activation](11b-pilot-and-activation.md)

## Completion boundary

11b has a mandatory human boundary: owner-selected corpus, accepted numeric thresholds and passing held-out results for the exact activated scope. Planning and normal fake-provider tests do not authorize activation or private external evaluation.

Children retain their own focused tests, files, exclusion boundaries and stop conditions. An umbrella closes only after every child has current verification evidence. Preserve historical passing runs; do not describe future criteria as implemented behavior. No product implementation, runtime installation, private-data experiment, commit or push is authorized by this planning document.
