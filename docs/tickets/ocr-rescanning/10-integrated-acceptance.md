# 10: Integrated browser and real-runtime acceptance

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 09.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

The complete pilot-mode workflow has evidence from browser tests, recovery tests and separately reported real local tool checks.

## Files and interfaces

- `src/test/kotlin/infoscry/server/OcrBrowserAcceptanceTest.kt (new)`
- `web/e2e/ocr-browser-acceptance.mjs (new)`
- `src/test/resources/fixtures/ (redistributable OCR cases)`
- `docs/usage.md`
- `docs/installation.md`
- `docs/technical-reference.md`
- `docs/implementation-status.md`
- `README.md`

Exercise existing APIs and UI as shipped; no acceptance-only bypass of profile validation, publication or external approval.

**Tests:** Use OcrBrowserAcceptanceTest plus accumulated suites and real OCR fixtures; record command, outcome and limitations rather than copying historical test totals.

## Test-first implementation

- [ ] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Unapproved text never appears in Search/Ask/Investigate; approved mixed-page revision appears coherently after completion.
  - Stop/restart in OCR, review, approval and publication states without losing a human decision or repeating committed transcription.
  - Human keyboard/screen-reader and real image-quality inspection remain explicit manual checks.
  - No private archive or external paid provider is required for normal acceptance.
- [ ] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [ ] Drive profiles, collection defaults, mixed PDF import, forced rescan, external approval, uncertain review/manual edit, publication, history/restore, cancellation and restart through the real UI with local fake providers.
- [ ] Test search, Ask evidence and Investigate source opening before/after publication. Do not infer completion of their unrelated open acceptance gates.
- [ ] Run actual Tesseract, Surya and a configured local image model against public fixtures, record versions/hardware and verify real CoreML publication separately. Missing runtime is an open blocker, not a passing skip.
- [ ] Update install commands, exact tested runtimes, privacy description, new controls, CLI approval behavior and current implementation status. Keep automatic acceptance disabled.
- [ ] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [ ] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew check
(cd web && npm run check && npm run build)
./gradlew externalTest
./gradlew gpuIntegrationTest
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
