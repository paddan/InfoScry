# 01: Collection-wide document summary

**Status:** Implemented 2026-10-07; evidence in the [STATUS.md verification record](STATUS.md).
**Blocked by:** None.
**Spec:** [Compact document list](../../specs/2026-09-30-compact-document-list.md).
Read the [shared constraints and gates](STATUS.md) before starting.

## Deliverable and files

Add grouped status counts and the scoped summary endpoint. Return every current status, derive total from the same SQL snapshot, and expose the typed frontend call. Preserve existing document eligibility and lifecycle/privacy guards.

- `src/main/kotlin/infoscry/storage/DocumentStore.kt`
- `src/main/kotlin/infoscry/document/DocumentService.kt`
- `src/main/kotlin/infoscry/server/DocumentRoutes.kt`
- `web/src/lib/api.ts`

No change was needed in `DocumentService.kt`: the summary is a read like the existing
listing, served thin over `DocumentStore` under the same `requireActiveByNameOrId` guard.

**Tests:** src/test/kotlin/infoscry/server/DocumentRoutesTest.kt and src/test/kotlin/infoscry/storage/DocumentStoreTest.kt (new if no matching store test exists)

## Test-first steps

- [x] Add failing tests for these exact observable cases:
  - Seed more than 50 documents with attention statuses only on the second page; summary still counts all of them.
  - Filename/status filters on listing calls cannot change summary; another collection and file-only imports do not contribute.
  - Empty collection returns zero counts; unknown/deleting collection follows existing 404 behavior; summary route is not mistaken for a document ID.
  - After Retry/deletion/import transitions, the next summary reflects authoritative state and contains no source paths or text.
- [x] Run the focused command and record a meaningful behavioral failure before implementation.
- [x] Implement the deliverable against the spec's shared endpoint/state contracts, preserving unrelated work and pinned dependencies.
- [x] Run focused verification green, then `./gradlew check`; frontend work also runs web `npm run check` and `npm run build`.
  (Focused green: 24 + 3 tests, 0 failures. The accumulated check runs 1417 backend tests
  with only the two pre-existing `SchemaMigratorTest` migration-028 failures recorded in
  STATUS.md; web check/build are clean.)
- [x] Review spec coverage, update this ticket and STATUS.md with actual evidence and remaining gates. Do not commit or push without the user's Git authorization.
  (Commit authorized by the orchestrator's follow-up for this worktree branch.)

## Focused command

```sh
./gradlew test --tests 'infoscry.server.DocumentRoutesTest' --tests 'infoscry.storage.*'
```
