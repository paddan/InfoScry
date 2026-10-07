# 08e: Persist and publish manual page decisions

**Status:** Not started — planned on 2026-10-02; unchecked criteria are requirements, not evidence.
**Blocked by:** 08d, 02e. Dependency IDs resolve in [STATUS.md](STATUS.md).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Shared contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable and boundary

Guard manual decisions and publish a new revision for pending import or rescan pages through the existing publisher.

Excluded from this slice: Automatic policy activation or restore. Preserve existing implementations and add only what the scenarios require. No broad refactoring or dependency upgrades.

## Files and interface ownership

- `src/main/kotlin/infoscry/document/RescanService.kt`
- `src/main/kotlin/infoscry/server/OcrRoutes.kt`
- `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt`
- `src/main/kotlin/infoscry/storage/OcrReviewStore.kt`
- `src/main/kotlin/infoscry/document/RevisionPublicationService.kt`
- `src/test/kotlin/infoscry/server/OcrRoutesTest.kt`
- `src/test/kotlin/infoscry/document/RevisionPublicationTest.kt`

Consume existing contracts and DTOs first. This slice owns the behavior described above; update its service, storage/API consumers and focused tests together when a signature changes. Forward migration numbers are allocated at execution time, after inspecting all existing migrations (currently through 024); never edit applied migrations. Record final signatures and response fields in CONTRACTS.md before a dependent slice starts.

## Observable acceptance scenarios

- [ ] Read the exact publication wording conflict in CONTRACTS.md. Preserve the implemented all-pending-page refusal for rescans in this slice; any change to mixed rescan publication requires owner reconciliation and synchronized spec/tests before implementation.

- [ ] KEEP/USE_NEW/EDIT with expected active revision and candidate hash persist idempotently; changed request body with same ID conflicts.
- [ ] First import with no active revision is decidable; already partially published import creates a new candidate/revision rather than mutating PUBLISHED pages.
- [ ] Decide one pending page, publish, then decide another: rebase only if that page baseline hash is unchanged; otherwise 409.
- [ ] Whole-document action resolves the server-side pending set; absent baseline makes KEEP explicit and safe rather than approving new text.
- [ ] Failure/cancellation before authority preserves active text; publication recovery after authority completes; pending pages never enter Search/Ask/Investigate.

## Execution and verification

- [ ] Read this ticket, its dependencies, the spec and actual callers; record existing behavior and the bounded intended change.
- [ ] Add the smallest regression/acceptance test for the first scenario, run it and record the expected behavioral failure. Repeat for independent failure modes; do not mistake infrastructure failure for red evidence.
- [ ] Implement only this slice and run its focused suite:

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.server.OcrRoutesTest' --tests 'infoscry.document.RevisionPublicationTest' --tests 'infoscry.document.RevisionPublicationRecoveryTest'
```

- [ ] Run the applicable accumulated gate in CONTRACTS.md; each code slice still requires `./gradlew check`. UI slices require frontend tests, check and build; runtime gates remain separately labelled.
- [ ] Review the final diff and caller contracts, run `git diff --check`, update the evidence row in STATUS.md and applicable user documentation. Commit/push only under separate Git authorization.

## Stop conditions and handoff

Stop dependent implementation if a required scenario fails, a contract contradicts the spec, or a required runtime is unavailable. Report the exact conflict or blocked gate; do not weaken assertions, broaden scope or add fallback. A finished handoff records changed files/contracts, actual commands/results, remaining limits and the next unlocked ticket. Checkboxes alone do not close this ticket.
