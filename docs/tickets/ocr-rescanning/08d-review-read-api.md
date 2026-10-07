# 08d: Read review material for imports and rescans

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 03c, 07c, 07e. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Return bounded review material and opaque source-image links for both staged imports and rescans.

Excluded from this slice: Decision writes, publication or UI. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/main/kotlin/infoscry/server/OcrRoutes.kt`
- `src/main/kotlin/infoscry/document/RescanService.kt`
- `src/main/kotlin/infoscry/storage/OcrReviewStore.kt`
- `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt`
- `src/main/kotlin/infoscry/server/SourceRoutes.kt`
- `src/test/kotlin/infoscry/server/OcrRoutesTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Resolve an import proposal without requiring a nonexistent RESCAN operation; scope owner/candidate/document explicitly.
- [ ] Return actual baseline, candidate, their hashes, differences and safe reasons for one bounded page batch, including absent reviewer/baseline.
- [ ] Keep existing on a direct-text import must remain implementable: persist or deterministically recover that immutable direct baseline, with its hash.
- [ ] Serve only images referenced by the selected candidate/revision inside its roots; cross-collection IDs return 404, draft text stays unavailable through ordinary source routes.
- [ ] Two candidates sharing a baseline cannot leak reviews into each other; page identity maps extraction key to stable document/ordinal correctly.

## Execution and verification

- [ ] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [ ] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [ ] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.server.OcrRoutesTest' --tests 'infoscry.document.RevisionPublicationTest'
```

- [ ] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [ ] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
