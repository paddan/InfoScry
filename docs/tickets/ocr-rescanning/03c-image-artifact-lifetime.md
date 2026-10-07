# 03c: Retained image references remain usable

**Status:** Implemented — accumulated gate green (`./gradlew check`: backend 1407 tests + web 213 tests, 0 failures, 17m09s); focused suite green (101 tests, 0 failures). Red evidence and details in [STATUS.md](STATUS.md).
**Blocked by:** 03b. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

A durable page never references a deleted working render; artifacts needed by pending or historical revisions remain available.

Excluded from this slice: Review image HTTP endpoints or new garbage collector. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/main/kotlin/infoscry/extract/PdfExtractor.kt`
- `src/main/kotlin/infoscry/extract/ImageExtractor.kt`
- `src/main/kotlin/infoscry/ocr/CandidateRevisionSink.kt`
- `src/test/kotlin/infoscry/extract/PdfExtractorTest.kt`
- `src/test/kotlin/infoscry/extract/ImageExtractorTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [x] Finish fill-missing extraction and verify every recorded image reference still resolves and matches its hash; a deliberately temporary image records no durable reference.
- [x] Cancellation/restart retains images referenced by staged pages; retry does not erase another candidate or published history image.
- [x] A managed original and a bounded derived image resolve under their own document roots.
- [x] Do not introduce artifact garbage collection here: preserve referenced images until normal deletion owns cleanup.

## Execution and verification

- [x] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [x] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [x] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.extract.PdfExtractorTest' --tests 'infoscry.extract.ImageExtractorTest' --tests 'infoscry.ocr.OcrEngineContractTest'
```

- [x] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [x] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
