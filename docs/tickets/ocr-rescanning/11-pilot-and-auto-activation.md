# 11: Measured pilot and guarded automatic replacement

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 10.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

Automatic replacement can be enabled only for an explicitly accepted, validated model/policy combination; pilot/manual mode remains available.

## Files and interfaces

- `scripts/ocr/evaluate.py (new local evaluation runner)`
- `docs/evaluations/ocr-pilot.md (new report, no private source content)`
- `src/main/kotlin/infoscry/ocr/OcrDecisionPolicy.kt`
- `src/main/kotlin/infoscry/storage/OcrReviewStore.kt`
- `web/src/lib/CollectionsPanel.svelte`
- `docs/implementation-status.md`

Produce a validation record keyed by transcription/reviewer/model/prompt/policy revisions and corpus manifest. Policy activation requires an accepted passing record, not a model confidence field. The record and its table now exist as `OcrReviewStore.OcrValidationRecord` over `ocr_validation_records` (migration 022), written only by `OcrReviewStore.acceptValidation` and read by `acceptedValidation`; activation means wiring a store-backed `OcrDecisionPolicy(policyVersion, reviews)` — 06's policy resolves the record from that table on every decision, so an activation ends when its row is removed.

**Tests:** Create src/test/kotlin/infoscry/ocr/OcrActivationTest.kt and local evaluation-runner fixture tests with redistributable ground truth.

## Test-first implementation

- [ ] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - High reviewer confidence with no validation cannot activate automation.
  - Accepted record with a different prompt/model hash is rejected; matching accepted held-out result can enable exactly that scope.
  - False-approval and omitted-region fixtures are counted separately from CER; zero attempts never produces a perfect accuracy claim.
  - Pilot failure and missing user acceptance leave manual operation fully usable.
- [ ] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [ ] Select representative pilot documents with the user, prepare human-verified references and separate tuning/held-out document families. Keep private content local unless explicit experiment-specific external authorization exists.
- [ ] Measure CER/WER against ground truth, omissions, hallucinations, critical name/number damage, false approvals, missed improvements, review burden, latency and usage with denominators and uncertainty.
- [ ] Propose numeric activation thresholds from pilot evidence and obtain explicit acceptance; then evaluate held-out cases. Do not invent thresholds that silently authorize production changes.
- [ ] Bind enabled policy to validated fingerprints. Changed models/prompts/policy or missing validation revert to manual/pilot approval; no settings edit bypasses the gate.
- [ ] Publish the report with approved scope and limits. Failed/insufficient pilot keeps automation disabled and records the actual blocker rather than marking the feature validated.
- [ ] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [ ] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.ocr.*'
python3 -m unittest discover -s scripts/ocr -p 'test_*.py'
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
