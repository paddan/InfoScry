# 11: Retire automatic Default safely

**What to build:** New archives start without collections, while existing user
data in legacy Default survives until the user chooses to rename or explicitly
delete it. An unused legacy Default is removed automatically through forward
migration, and import guidance no longer assumes it exists.

**Blocked by:** 01 — Collections replaces Import; 05 — Edit collection settings;
06 — Delete collections with recoverable visible status.

- [x] Forward migration leaves a fresh archive with no exposed Default after
      initialization, without changing the meaning of an applied migration.
- [x] Automatic cleanup applies only to the active legacy ID `default` named
      `Default` with no documents, jobs, conversations, unfinished deletion or
      other owned user state. Cleanup is repeat/reopen safe.
- [x] Populated, renamed or otherwise used legacy collections are preserved.
      A collection is never removed just because its name is Default, and a
      user-created collection with that name is never mistaken for the seed.
- [x] Preserved legacy Default shows an explanation with working rename and
      exact-name-confirmed deletion choices. Neither choice is taken silently.
- [x] Empty-archive UI guides creation and explicit selection before first
      import. It remains possible to create a user-named Default intentionally.
- [x] CLI examples create `Notes` first and use that collection consistently;
      documented UI/import flows do not rely on an automatic default destination.
- [x] Migration and route tests cover fresh, empty legacy, populated, renamed,
      job-bearing, conversation-bearing and unfinished-deletion archives, plus
      reopening and user-created Default. UI tests cover both legacy choices and
      first import; accumulated checks pass.

**Status:** Done. Migration 013 (`src/main/resources/db/migration/013_retire_automatic_default.sql`)
removes the seeded row only when `id = 'default'`, `name = 'Default'`, `lifecycle = 'ACTIVE'`, and no
document, job, conversation or unfinished collection/document deletion references it; `SUPPORTED_VERSION`
and the migration list are 13. `CollectionsPanel.svelte` explains a preserved legacy Default and points at
the existing rename and exact-name deletion, guides creation and explicit selection in an empty archive,
and still creates a user-named Default. README CLI examples create `Notes` first.
Verified: `JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew test --tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests 'infoscry.jobs.*' --tests 'infoscry.collection.*' --tests 'infoscry.search.*'`
— BUILD SUCCESSFUL, 354 tests, 0 failed (includes `DefaultRetirementTest` 9 and `SchemaMigratorTest`);
`--tests 'infoscry.cli.*' --tests 'infoscry.llm.*' --tests 'infoscry.document.*'` — BUILD SUCCESSFUL, 185
tests, 0 failed; `cd web && npx vitest run` — 176 passed; `npm run check` — 0 errors, 0 warnings. Not run:
`./gradlew check` (ticket 12's accumulated gate) and `externalTest` browser acceptance.
