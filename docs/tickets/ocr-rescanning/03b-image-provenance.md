# 03b: Image provenance persistence boundary

**Status:** Implemented — accumulated gate green (`./gradlew check`: backend 1402 tests + web 213 tests, 0 failures); focused suite green (131 tests, 0 failures). Artifact lifetime is owned by [03c](03c-image-artifact-lifetime.md).
**Blocked by:** 03.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
**Contracts:** [CONTRACTS.md](CONTRACTS.md).

## Deliverable

Reject root-escaping or incomplete image provenance at both domain/store and SQLite boundaries. Preserve legacy rows with entirely absent provenance. This ticket changes persistence validation only; it does not change rendering, image-serving endpoints or artifact cleanup.

## Files and interfaces

- `src/main/kotlin/infoscry/domain/Models.kt` — `SourceImageProvenance` validation.
- `src/main/kotlin/infoscry/storage/DocumentRevisionStore.kt` — write/read validation.
- `src/main/resources/db/migration/` — new forward migration; inspect current numbering (through 024), never edit 019.
- `src/test/kotlin/infoscry/document/RevisionPublicationTest.kt` and `src/test/kotlin/infoscry/storage/SchemaMigratorTest.kt`.

## Acceptance and execution

- [x] Add and run failing tests for absolute references, `..` traversal and references escaping the named root; reject before persistence.
- [x] Raw SQL cannot write half-present core provenance (root/path/hash/render version). Width and height are both positive or both absent, preserving the current optional measurement contract.
- [x] A malformed row cannot silently read back as absent provenance. Before tightening constraints, audit existing rows; preserve page text and clear demonstrably unusable legacy provenance rather than deleting revisions or guessing hashes.
- [x] An archive upgraded from 018/019 retains readable historical pages; all-absent provenance remains valid. As-is and derived image references name their distinct managed-copy/artifact roots.
- [x] Implement bounded validation and forward migration; verify store and direct-SQL paths independently.
- [x] Run focused tests, `./gradlew check`, `git diff --check`, and update STATUS.md with actual evidence.

```sh
export JAVA_HOME="$(asdf where java)"
./gradlew test --tests 'infoscry.document.RevisionPublicationTest' --tests 'infoscry.storage.SchemaMigratorTest'
```

Stop on migration data-loss risk or a spec conflict. Do not add artifact collection, change pinned dependencies or close 03c based on this slice. Commit/push only under separate Git authorization.
