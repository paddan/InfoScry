# 07e: Keep ordinary extraction for non-image formats

**Status:** Implemented — 2026-10-06. Red observed first on the three independent failure modes (plain-text and Office check-and-improve imports stranded in `NEEDS_REVIEW`; an unsupported-format draft reaching the reviewer with `expected: <0> but was: <1>` request), then focused suite green (ImportJobHandlerTest 51, ExtractorRegistryTest 10, RescanJobHandlerTest 16, RetryJobHandlerTest 18 — 95 tests, 0 failures) and the accumulated gate green (`./gradlew check`: 107 suites / 1397 backend tests + web 213 tests, 0 failures, 17m04s; `git diff --check` clean). Kotlin-daemon OOM runs during the red attempt are infrastructure, not evidence. Record in [STATUS.md](STATUS.md).
**Blocked by:** 07b implementation. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Collection Check and improve applies to PDF/images; non-image formats keep their ordinary searchable extraction.

Excluded from this slice: New renderers or unsupported OCR formats. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/main/kotlin/infoscry/jobs/DocumentIngest.kt`
- `src/main/kotlin/infoscry/jobs/ImportPipeline.kt`
- `src/main/kotlin/infoscry/extract/DocumentExtractor.kt`
- `src/test/kotlin/infoscry/jobs/ImportJobHandlerTest.kt`
- `src/test/kotlin/infoscry/extract/ExtractorRegistryTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [x] Import plain text and a redistributable Office fixture with Check and improve selected: ordinary text, chunks and index rows are published, not stranded in NEEDS_REVIEW.
- [x] Choose sink using the selected extractor pageImageSupport; no raster or image-provider request is made for unsupported formats.
- [x] PDF/image imports still stage proposals; explicit rescan of unsupported formats returns the existing clear refusal.
- [x] A supported page with no direct baseline remains pending; do not confuse that case with a non-image format.

## Execution and verification

- [x] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [x] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [x] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.extract.ExtractorRegistryTest'
```

- [x] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [x] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
