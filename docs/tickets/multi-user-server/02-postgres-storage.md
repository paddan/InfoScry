# Ticket 2: Postgres storage

Spec: [Multi-user server](../../specs/2026-11-05-multi-user-server-design.md), ticket 2 of the suggested
order. Status: in progress on branch `postgres-storage` (2026-11-05).

## Goal

InfoScry keeps all of its authoritative state in PostgreSQL instead of SQLite. Behavior is unchanged:
every existing test passes against Postgres, the Lucene index (keywords and vectors) stays as it is
until ticket 3, and there are no users yet. Existing SQLite archives are not migrated; a new
installation starts empty.

## Decisions

| Question | Decision |
|---|---|
| Server | `docker.io/paradedb/paradedb:0.26.5-pg17` (Postgres 17, `pg_search` 0.26.5, `pgvector` 0.8.6), the image the spike used, for Compose and tests |
| Driver and pool | `org.postgresql:postgresql` 42.7.14 and `com.zaxxer:HikariCP` 7.1.0 |
| Tests | `org.testcontainers:testcontainers-postgresql` 2.0.5; one container per test JVM, one database per `Database` a test opens |
| Configuration | `INFOSCRY_DATABASE_URL` (default `jdbc:postgresql://127.0.0.1:5432/infoscry`), `INFOSCRY_DATABASE_USER` (default `infoscry`), `INFOSCRY_DATABASE_PASSWORD`; environment only, the password never logged |
| Value representations | Unchanged: instants stay ISO-8601 `text`, flags stay integer 0/1, enums stay checked `text`, JSON payloads stay `text`. Only types SQLite does not have change: `INTEGER` → `bigint`, `REAL` → `double precision`, `BLOB` → `bytea`. Native `timestamptz`/`jsonb` are a later cleanup, not this port |
| Case-insensitive names | A nondeterministic ICU collation `infoscry_nocase` (`und-u-ks-level2`) replaces `COLLATE NOCASE` in unique indexes, lookups and ordering |
| Dialect | `GLOB` checks become `~` regular expressions, `INSERT OR IGNORE` becomes `ON CONFLICT DO NOTHING`, `STRICT`/`WITHOUT ROWID` go, unique violations are recognized by SQLSTATE `23505` instead of message text |
| Schema versions | `schema_version` stays the record of the applied version; `PRAGMA user_version` goes. The Postgres baseline is version 1. The SQLite upgrade steps 1 → 4 are deleted |
| One process per database | `pg_try_advisory_lock` on a fixed key, held on its own connection for the process lifetime, taken wherever the data-directory `ProcessLock` is taken today. A second holder is refused at once, as `ProcessLock` refuses today; a killed process releases it with its connection |
| Connections | A Hikari pool. `read {}` borrows a pooled connection for its block. `transaction {}` binds one connection to the thread for the outermost block; nested calls use savepoints on that connection, as today |
| Write concurrency | Transactions still run one at a time in the process (the lock `Database` holds today, now around transactions only); reads no longer wait for writes. Concurrent writers are ticket 7, together with optimistic edits |
| Compose | `compose.yaml` with one `postgres` service, `.env.example`, health check, `127.0.0.1:5432` |
| SQLite | `sqlite-jdbc` is removed from the build; `infoscry.db` is no longer created |

## Out of scope

`pgvector`/`halfvec` and BM25 in Postgres (ticket 3; embeddings stay `bytea`), the job queue and
restart recovery (ticket 4), users and sessions (ticket 5), concurrent writers (ticket 7), and any
migration of existing archives.

## Steps

1. **Build and test infrastructure.** Pin the three libraries in the version catalog, drop
   `sqlite-jdbc`. A `TestDatabases` helper starts the ParadeDB container once per JVM, migrates a
   template database, and gives each test a fresh database cloned from it (`CREATE DATABASE … TEMPLATE`),
   dropped on close. Tests that spawn a child JVM pass it the database URL. `docs/development.md`
   says how to run the tests with Docker or Podman.
2. **Baseline.** Translate `001_baseline.sql` into the Postgres baseline with the rules above, keeping
   every table, column, constraint, index and comment.
3. **`Database` and `SchemaMigrator`.** Pool, thread-bound transactions with savepoints, the transaction
   lock, the advisory lock, the schema version check (a newer version is refused), and the SQLite
   upgrade code removed.
4. **Stores.** Port every SQL statement in `storage/` and the few outside it (`OrphanedStructureSweep`,
   `RetryService`, `AskRoutes`, `InvestigationRoutes`) until their tests pass.
5. **Wiring.** `AppContext`, `AppPaths` and the CLI commands open the database from configuration; the
   advisory lock sits beside `ProcessLock`.
6. **Compose and docs.** `compose.yaml`, `.env.example`, README, `docs/installation.md`,
   `docs/development.md`, `docs/technical-reference.md` and AGENTS.md updated for Postgres.

## Verification

- `./gradlew check` passes with all existing tests running against Postgres.
- `DatabaseTest`: nested transactions roll back to their savepoint; a failed outer transaction leaves
  nothing; a read runs while a transaction holds the write lock; a second advisory-lock holder is
  refused and succeeds after the first connection is closed.
- `SchemaMigratorTest`: an empty database gets the baseline; running it again changes nothing; a
  database with a newer `schema_version` is refused.
- Manual on this Mac: `docker compose up -d` (or Podman), `infoscry serve`, import a fixture collection
  with the real CoreML embedder, search it, restart the server and search again.
