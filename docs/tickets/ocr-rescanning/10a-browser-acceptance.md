# 10a: Complete manual OCR browser acceptance

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 08f, 09b, 09c, 07f, 02d, 02e. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Drive the shipped manual workflow through browser, real server and temporary archive with local fakes.

Excluded from this slice: Real runtime installation or pilot evaluation. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/test/kotlin/infoscry/server/OcrBrowserAcceptanceTest.kt (new)`
- `web/e2e/ocr-browser-acceptance.mjs (new)`
- `src/test/resources/fixtures/ocr/`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Configure profiles/defaults, import mixed PDF, preview rescan, approve external scope, edit/keep/use pages, publish, inspect history and restore.
- [ ] Restart during OCR, approval, review and publication; retain decisions and skip committed transcription.
- [ ] Search/Ask/Investigate omit unapproved text, keep saved citations and switch coherently after publication.
- [ ] Use production validation, ownership and permits; no test-only bypass, private archive or paid provider.
- [ ] Record keyboard/narrow-layout checks separately; browser fakes do not prove real model quality or GPU.

## Execution and verification

- [ ] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [ ] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [ ] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew externalTest --tests 'infoscry.server.OcrBrowserAcceptanceTest'
```

- [ ] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [ ] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
