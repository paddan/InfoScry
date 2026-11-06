# Multi-user server

Status: approved 2026-11-05; implemented 2026-11-06 (tickets 1–9). Verification, deviations and
open gates are recorded in [implementation status](../implementation-status.md#multi-user-server-2026-11-06).

## Goal

InfoScry becomes a server that several people use at once. Each person signs in with their own account,
sees the collections they are members of, and can tell who imported a document, approved a page, wrote a
correction or changed a prompt. The local single-user archive (SQLite and Lucene in `~/.infoscry`, open
API on loopback) is retired. Existing archives are not migrated: a new installation starts empty and
collections are imported again (decided 2026-11-05).

InfoScry itself stays one process running directly on the host, so CoreML embeddings and Surya on the
Apple GPU work exactly as today. Only PostgreSQL runs in Docker, started with `docker compose up`.

This replaces several architecture rules in [AGENTS.md](../../AGENTS.md); they are listed under
[Rules that change](#rules-that-change) and are updated together with the first implementing ticket.

## Decisions

| Question | Decision |
|---|---|
| Authoritative store | PostgreSQL 17 in Docker, replacing SQLite |
| Vector search | `pgvector` in the same database (HNSW, `halfvec(768)`), replacing Lucene's vector field |
| Keyword search | BM25 in the same database through ParadeDB `pg_search`, replacing Lucene; gated by a ranking spike |
| Application process | One InfoScry process on the host: web server, job lanes and embedder, as today |
| Docker | `compose.yaml` runs Postgres only; the application is not containerized |
| GPU | Unchanged: CoreML on macOS arm64, never a silent CPU fallback |
| Managed files | The data directory on the host, behind a `BlobStore` interface |
| Job queue | Rows in Postgres, claimed with `FOR UPDATE SKIP LOCKED` |
| One server per database | A Postgres advisory lock held for the process lifetime |
| Sign-in | Local accounts: username and password (Argon2id), server-side sessions in an HttpOnly cookie |
| User management | Administrators create, deactivate and reset users; no self sign-up |
| Authorization | Global role `ADMIN` or `USER`; per-collection membership `OWNER`, `EDITOR` or `READER` |
| Tracking who did what | Attribution columns on the records people create and change, shown in the UI |
| Audit log, retention, GDPR | Out of scope |
| Single-user local mode | Removed; every installation has accounts |
| CLI | An HTTP client authenticated with a personal access token, plus admin commands |
| Existing archives | Not migrated; collections are imported again |

### Approaches considered for search

1. **Postgres for everything (chosen).** `pgvector` for vectors and `pg_search` for BM25 make one
   transactional store. Index publication, the Lucene index swap, its recovery and the duplicated vector
   copy disappear; a document's text, chunks, vectors and keyword entries commit in one transaction.
   Cost: the glossary rewrite, prefix and phrase handling and highlighting move from Lucene's query API to
   `pg_search`'s query builder, and `pg_search` is AGPL-licensed (acceptable for self-hosting; recorded
   here so it is a conscious choice).
2. **Postgres with built-in full-text search.** No extension beyond `pgvector`, but `ts_rank` is not BM25,
   the `swedish` snowball configuration differs from today's ICU folding, and hybrid fusion quality would
   change. Kept as the fallback if the spike rejects `pg_search`.
3. **Postgres for state, Lucene kept for keywords, a vector database such as Qdrant for vectors.** Three
   stores to keep consistent, an outbox for every write and three recovery paths. Rejected: it adds the
   complexity this change is meant to remove.

**Spike gate.** Before the search ticket starts, run today's search fixtures and a held-out query set
through `pg_search` (ICU tokenizer, lower case, ASCII folding) and compare the top-10 results and the
glossary expansion against current Lucene. The spike reports the differences; the owner decides between
approach 1 and 2.

**Spike result (2026-11-05, [report](../../scripts/spikes/pg_search/README.md)).** On 29,161 chunks of
Swedish statutes `pg_search` gave the same tokens, the same BM25 scores (times the constant `k1 + 1`) and
the same matches as Lucene; the differences were query syntax only. The search port therefore follows
these rules:

- Queries are built with the query-builder functions (`|||`, `###`, `pdb.regex`, boolean SQL), never by
  passing user text to `pdb.parse`. An unqualified `pdb.parse` also searches the key column, rejects
  ordinary text such as `3 kap. 5 §`, and does not support prefixes.
- A prefix term is folded by the index analyzer, regex-escaped and sent as `pdb.regex('<prefix>.*')`.
- The single BM25 index per table includes the collection, document and revision filter fields from
  its first version, since a table can have only one ParadeDB index.

### Approaches considered for deployment

1. **Postgres in Docker, InfoScry natively (chosen).** The Apple GPU stays reachable, the existing GPU
   readiness checks and Surya setup are unchanged, and development is `docker compose up` plus the usual
   Gradle run. Cost: JDK 25, Tesseract, Surya and optional Calibre are installed on the host as today, and
   the host's service manager restarts the process.
2. **Everything in Docker, embedder natively on the Mac.** Docker on macOS runs containers in a Linux VM
   without access to CoreML or Metal, so the embedder and Surya would have to run outside anyway. More
   moving parts (an internal embedding service, a shared library volume, cross-process events) for no
   gain on a Mac.
3. **Everything in Docker with CUDA on Linux.** The natural choice for a dedicated Linux GPU server, but
   CUDA is outside the v1 runtime. Option 1's job queue and single process boundary leave this open.

## Architecture

```
browser ──HTTP(S)──▶ InfoScry process (host) ──JDBC──▶ postgres (Docker)
                     │  web server, sign-in            state, vectors, BM25, jobs, sessions
                     │  job lanes (import, OCR, …)
                     │  embedder (CoreML)
                     ▼
                     data directory (managed files, models, logs, tmp)
```

- The process keeps today's structure: routes and CLI commands stay thin over shared services, the job
  worker runs in-process with its two lanes, query and passage embeddings are computed in-process, and job
  events reach the browser's event stream in-process.
- **One server per database.** At start the process takes `pg_try_advisory_lock` on a fixed key and
  holds it on a dedicated connection. A second server against the same database is refused, naming the
  host and start time recorded beside the lock. A killed process loses its connection and thereby the
  lock, so the next start never waits for stale state.
- **Listen address.** `INFOSCRY_BIND` defaults to `127.0.0.1:8080`. To serve other machines it is set to
  another address, normally behind a reverse proxy that terminates TLS. `INFOSCRY_PUBLIC_ORIGIN` decides
  the allowed `Host` and `Origin` headers and whether cookies are `Secure`.
- **Database connection.** `INFOSCRY_DATABASE_URL`, user and password from environment variables, the
  password never logged. A connection pool (HikariCP, pinned) serves requests and job lanes.

### Data

- The current SQLite baseline is translated into one Postgres baseline migration (numbered SQL files run
  by `SchemaMigrator` at start, inside an advisory lock). Table and column names stay, and so do value
  representations (ISO-8601 text instants, 0/1 flags, JSON as text); converting them to `timestamptz` and
  `jsonb` is a later cleanup (decided in ticket 2). A database whose schema is newer than the build is
  refused. The SQLite upgrade steps (1 → 4) are dropped with SQLite.
- `chunks.embedding` becomes `halfvec(768)` with an HNSW index (`halfvec_ip_ops`, matching today's
  dot-product similarity). Filtered queries by collection, document and revision scope use pgvector's
  iterative index scans (`hnsw.iterative_scan = relaxed_order`) so a selective filter still returns
  `limit` rows; the candidate-count logic in `LuceneIndex` is removed.
- A `pg_search` BM25 index covers the chunk's search text with the collection ID as a filter field.
- Re-embedding with a new model writes into a new `chunk_embeddings` row set tagged with the model ID; a
  collection's active model ID is switched in one transaction when every chunk has a vector. Search reads
  only the active model's rows. This replaces "publish writer, reader and embedder together".
- Extraction checkpoints, stable content-unit IDs, per-unit commits and resume without repeating OCR stay
  exactly as specified today; only the store changes.

### Data directory

- `INFOSCRY_DATA_DIR` (default `~/.infoscry`) keeps `library/`, `models/`, `logs/` and `tmp/` at today's
  paths. `infoscry.db`, `index/`, `infoscry.lock` and `runtime.json` are no longer used.
- Managed copies and artifacts stay immutable and deduplicated by collection and SHA-256. All access goes
  through a `BlobStore` interface (`put` by content hash, `open`, `exists`) with one filesystem
  implementation, so object storage can be added later without touching services.
- Backups need both the Postgres database and `library/`; the installation guide says so.

### Import sources

Users no longer import from paths on their own machine. Two sources:

- **Upload.** The browser uploads files (multipart, one request per file, streamed to a temporary blob and
  hashed). A repeated upload of the same bytes to the same collection answers with the existing document.
  The import start dialog follows the upload, as today.
- **Import roots.** An administrator adds host directories under Admin → Server folders (amended
  2026-11-06; `INFOSCRY_IMPORT_ROOTS` still adds roots that are changed only there). Users with
  `EDITOR` on a collection may browse those roots and pick files or folders; a path outside a root,
  including through a symbolic link, is refused. Originals are never modified, as today.

### Jobs

- Jobs are rows in Postgres. Each lane claims its oldest queued job with `FOR UPDATE SKIP LOCKED`, so the
  lanes never take the same job and a separate worker process could be added later without a new design.
- At start, holding the advisory lock, the process returns every job left `RUNNING` to `QUEUED`; it then
  resumes from committed checkpoints. A stopped job never blocks starting again and nothing waits for
  approval.
- Deletion keeps its persisted phases and recovery.

## Users, sign-in and authorization

### Accounts

- `users (id, username UNIQUE, display_name, password_hash, role, active, created_at, created_by,
  updated_at, updated_by, last_sign_in_at)`. Usernames are case-insensitive (`citext`).
- Passwords: at least 12 characters, hashed with Argon2id (parameters pinned in the code and stored in the
  hash string). No password rules beyond length.
- There is no sign-up page. The first administrator is created with `infoscry admin create-user --admin
  <username>`, which connects to the database directly and prints a one-time password; repeating it for
  an existing user changes nothing and says so.
- Administrators create users, set their role, reset a password (one-time password shown once, changed at
  next sign-in) and deactivate or reactivate them. Users are never deleted, so attribution always resolves
  to a name. Deactivation ends that user's sessions and tokens at once. The last active administrator
  cannot be deactivated or demoted (409).
- A user changes their own display name and password.

### Sessions

- Sign-in with username and password creates a row in `sessions (id_hash, user_id, created_at,
  last_seen_at, expires_at, user_agent)`; the browser gets the random session ID in an `HttpOnly`,
  `SameSite=Lax` cookie, `Secure` when the public origin is HTTPS. Idle expiry 12 hours, absolute expiry
  14 days; both configurable. Sessions survive a server restart.
- Browser mutations additionally carry a CSRF token bound to the session, replacing today's per-launch
  token. Read-only routes now also require a session.
- Failed sign-ins are rate-limited per username and per client address. Errors never say whether the
  username exists.
- Sign-out deletes the session row; signing out twice answers 204.

### Personal access tokens

- The CLI authenticates with a personal access token that a user creates in their settings: shown once,
  stored as a SHA-256 hash, with a name and optional expiry, revocable. It replaces the per-launch bearer
  token in `runtime.json`. The CLI reads the server URL and token from `INFOSCRY_URL` and
  `INFOSCRY_TOKEN`.

### Authorization

| Action | READER | EDITOR | OWNER | ADMIN |
|---|---|---|---|---|
| See the collection, search, read documents, Ask, Investigate | yes | yes | yes | yes |
| Import, rescan, retry, delete documents, review pages | | yes | yes | yes |
| Edit corrections, glossary and collection prompts | | yes | yes | yes |
| Change collection settings, manage members, delete the collection | | | yes | yes |
| Create collections | any signed-in user; the creator becomes `OWNER` | | | |
| Manage users, LLM profiles, OCR profiles, import roots | | | | yes |

- `collection_members (collection_id, user_id, role, added_at, added_by)`. A collection always keeps at
  least one owner; removing the last one is refused with 409.
- Every route resolves the caller's role for the collection before running the service, in one place
  (an authorization seam beside `Security.kt`). A collection the caller is not a member of answers 404, so
  its existence is not revealed. Administrators see every collection.
- LLM tools keep collection-scoped opaque IDs and are additionally bound to the caller's membership.
- Ask conversations and investigations belong to the user who started them and are private to that user.
  Saved evidence links into documents work for anyone with access to the collection.

## Tracking who did what

Attribution is part of the records themselves, not a separate log. It shows who did the latest thing to
a record and, where InfoScry already keeps history, who did each earlier step.

| Record | Attribution |
|---|---|
| Collection | `created_by`, `updated_by` (settings) |
| Membership | `added_by` |
| Document and import item | `imported_by` (the user who started the import) |
| Job (import, rescan, retry, reindex, corrections, selectable PDF) | `started_by`, `cancelled_by` |
| Page text revision | `created_by` (the user whose run or edit produced it) |
| Page review decision | `decided_by`, `decided_at` (approve or reject) |
| Correction rule, glossary group, collection prompt | `created_by`, `updated_by` |
| Prompt version | `created_by` per version |
| Conversation and investigation | `owner_id` |
| LLM and OCR profile | `created_by`, `updated_by` |

- Every attribution column references `users(id)` and is non-null. Records written before ticket 7
  adds attribution are attributed to the first administrator when the columns are added.
- Work a job does on a user's behalf (OCR, chunking, embedding) is attributed to the job's `started_by`.
  Recovery after a restart does not change attribution.
- The UI shows attribution next to timestamps it already shows, for example "Imported by Anna,
  4 Nov 2026" in document details and "Approved by Erik" in page review history. Collection settings show
  who last changed corrections, glossary groups and prompts.
- There is no append-only event history, no export of activity and no retention policy.

## Concurrent use

The single-user rule "add no protection or waiting for other readers" no longer holds. The replacement
keeps the rules about stopped work and idempotence:

- **Edits** to collection settings, corrections, glossary groups and prompts carry the record's `version`
  (`If-Match`). A stale version answers 409 with the current record, and the UI shows "Changed by Erik
  just now" with the current content. Repeating the same content still changes nothing.
- **Starting work.** Starting an import, rescan or retry for documents that already have an active job of
  the same kind answers with that job, whoever started it. It never answers "waiting" or "cannot start".
  A stopped job never blocks a new start.
- **Page review.** The first approve or reject decides; a later decision for the same revision answers
  with the recorded decision and who made it. Review stays available to other users while a rescan of the
  same document runs, as today.
- **Reading.** Readers are never blocked by imports, rescans or reindexing; Postgres MVCC gives each
  request a consistent snapshot.
- In-process caches such as the glossary snapshot stay as today; there is only one process.

## Embeddings and GPU

Unchanged from today: the pinned model, tokenizer and 512-token budget, passages never truncated, CoreML
on macOS arm64 with evidence of accelerated execution, CPU fallback refused, and keyword search, source
viewing and diagnostics available when GPU readiness fails. Surya keeps its host Python environment and
GPU. Tests keep the fake embedder.

## Docker Compose

`compose.yaml` at the repository root runs Postgres for development and for installations:

- One service, `postgres`, using the pinned `paradedb/paradedb` image (which bundles `pgvector` and
  `pg_search`), with a named volume `pgdata`, a health check, and `restart: unless-stopped`.
- It publishes `127.0.0.1:5432` only. The database name, user and password come from `.env`; an
  `.env.example` is committed with the variables the InfoScry process also reads.
- Starting the system is `docker compose up -d` followed by `infoscry server`. The installation guide
  shows a launchd agent that starts `infoscry server` at login and restarts it if it exits.
- The application, a `Dockerfile` and a containerized embedder are out of scope.

## Privacy and logging

- The existing rules stay: keys, questions, answers, document text, prompts, corrections, glossary terms
  and provider authorization headers stay out of logs and errors.
- Passwords, session IDs, CSRF tokens, access tokens and the database password are added to that list.
  Usernames may be logged for sign-in and administration events.

## Rules that change

| Current rule in AGENTS.md / technical reference | New rule |
|---|---|
| SQLite owns authoritative state; Lucene is rebuildable | Postgres owns all state, including vectors and the BM25 index |
| Index swap publishes writer, reader and embedder together | Active embedding model per collection switches in one transaction |
| Standalone CLI import retains process ownership | The CLI is a client; the server owns all jobs |
| Single-user application, no concurrency protection | Multi-user with optimistic edits; still no waiting or approval gates on starting work |
| Bind only to `127.0.0.1`; per-launch bearer and CSRF tokens | Loopback by default, other addresses by configuration; sessions, session-bound CSRF and personal access tokens |
| Multi-user/public hosting outside scope | Multi-user hosting in scope as described here |

Rules that stay unchanged: one process and one Gradle backend module, GPU embeddings through CoreML on
macOS arm64 with Linux/CUDA outside v1, immutable originals, stable content-unit IDs, per-unit
checkpoints, resume without repeating OCR, the OCR workflow in [ocr-workflow.md](../ocr-workflow.md),
idempotence for every operation, request budgets and citation validation, untrusted imported instructions
and English product copy.

## Testing

- Backend tests use Testcontainers with the pinned `paradedb/paradedb` image and the fake embedder;
  `./gradlew check` needs a running Docker daemon. Each test class gets its own database.
- Every new operation gets the idempotence tests the project already requires: run twice, and interrupt
  at each step and run again. That covers sign-in, user administration, membership changes, restart with
  running jobs and the advisory lock.
- Authorization is tested route by route against the table above, including 404 for non-members.
- Concurrency tests: two users editing the same glossary group, two decisions on one page, two starts of
  the same rescan, the process killed mid-unit and started again.
- The existing Playwright acceptance tests sign in first and run against the server with a Testcontainers
  database and fake providers; one new test covers user creation, membership and attribution display.
- The real CoreML test on the local Mac stays the gate for GPU-dependent claims.

## Out of scope

Audit log or activity history beyond attribution, data retention and deletion policies, GDPR tooling,
single sign-on (OIDC, SAML), self sign-up, email, organizations or tenants, per-user LLM keys or quotas,
object storage, a containerized application or embedder, separate worker processes, Linux/CUDA, CI and
published images, and a single-user mode without accounts.

## Suggested ticket order

1. Search spike: `pg_search` against Lucene on the fixtures (gate for ticket 3).
2. `compose.yaml` with Postgres, Postgres baseline, `SchemaMigrator` on Postgres, stores ported,
   advisory lock, Testcontainers in the test suite.
3. Search on `pgvector` and BM25, glossary rewrite and highlighting ported, Lucene removed.
4. Job queue in Postgres and restart recovery.
5. Users, sessions, CSRF, personal access tokens, admin commands; CLI as HTTP client.
6. Memberships and the authorization seam; private conversations.
7. Attribution columns and UI; optimistic edits and concurrent-use rules.
8. Uploads and import roots.
9. README, AGENTS.md, technical reference and installation docs updated; old local-mode docs removed.

## Open questions

- Is the AGPL license of `pg_search` acceptable, or should approach 2 be the default regardless of the
  spike?
- Should collection members see each other's Ask conversations on request (a "share" action), or stay
  strictly private as specified?
- Is one shared set of LLM profiles and keys for all users right, or do some users need their own?
