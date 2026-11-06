# Technical reference

[Project overview](../README.md) · [Development](development.md) · [Implementation status](implementation-status.md)

Current feature contracts: [OCR workflow](ocr-workflow.md),
[selectable PDF](selectable-pdf.md) and
[PDF interfaces](selectable-pdf-contracts.md).

## Architecture

One Kotlin/JVM process owns the services, web server, persistent jobs,
managed library, database, search index and LLM adapters. HTTP routes and CLI
commands delegate to shared services. The frontend is built as static assets
and served by Ktor.

| Responsibility | Technology |
|---|---|
| Backend and CLI | Kotlin, Java 25, Ktor, Clikt |
| Web reader | SvelteKit, Svelte and TypeScript |
| Authoritative state and extracted text | PostgreSQL 17 (ParadeDB image, `compose.yaml`) |
| Keyword and vector search | `pg_search` (BM25) and `pgvector` (HNSW) in the same database; Lucene's analyzer and query parser as libraries |
| Local embeddings | Multilingual E5, ONNX Runtime and CoreML |
| Extraction | Tika, PDFBox, POI, jsoup and Commons CSV |
| External document tools | Tesseract; optional Calibre |

Pinned backend versions live in [the Gradle catalog](../gradle/libs.versions.toml);
frontend versions live in [package.json](../web/package.json) and its lockfile.
The sizing target is roughly 10,000 documents or one million pages. This is
not a measured capacity claim.

## Storage and process ownership

The default data directory is `~/.infoscry`. Use `--data-dir` to select another
archive. [AppPaths](../src/main/kotlin/infoscry/config/AppPaths.kt) defines its layout:

| Path | Contents |
|---|---|
| `infoscry.lock` | Process ownership lock |
| `library/` | Immutable managed copies and document artifacts |
| `models/` | Installed embedding model and tokenizer |
| `logs/` | Structured application logs |
| `tmp/` | Temporary work and GPU profiling artifacts |
| `uploads/` | Uploaded files waiting for import, removed after seven days |

Authoritative state lives in PostgreSQL, not in the data directory. The process
reads its connection from `INFOSCRY_DATABASE_URL`, `INFOSCRY_DATABASE_USER` and
`INFOSCRY_DATABASE_PASSWORD` (defaults `jdbc:postgresql://127.0.0.1:5432/infoscry`
and `infoscry`; the password is never logged). `compose.yaml` runs the pinned
`paradedb/paradedb:0.26.5-pg17` image. [Database](../src/main/kotlin/infoscry/storage/Database.kt)
reads through a HikariCP pool; a transaction binds one connection to its thread,
nests through savepoints, and transactions still run one at a time in the process.
Unique and foreign-key violations are recognized by SQLSTATE. Case-insensitive
names use the ICU collation `infoscry_nocase`.

The schema is one baseline,
[001_baseline.sql](../src/main/resources/db/migration/001_baseline.sql), applied
to an empty database by [SchemaMigrator](../src/main/kotlin/infoscry/storage/SchemaMigrator.kt)
in one transaction and recorded in `schema_version` as version 1. The baseline
always describes the current schema and has no upgrade path. Values keep their
earlier representations: instants are ISO-8601 text, flags 0/1 integers.
A database whose version is newer than the build is refused rather than written
to. SQLite archives from earlier builds are not read or migrated; collections are
imported again.

Besides the data-directory lock, a process claims the database with a
session-level advisory lock held on its own connection
([ProcessOwnership](../src/main/kotlin/infoscry/storage/ProcessOwnership.kt)). A
second process is refused at once with the holder's host and pid; a killed
process releases the claim with its connection.

Import deduplicates by collection and SHA-256. External originals are never
modified or deleted. Content-unit IDs remain stable; extraction results and
checkpoints are committed per unit with durable artifact references. Restarting
resumes committed work, including OCR. Re-embedding does not invalidate
extraction checkpoints.

The server owns every job. The CLI is an HTTP client of it (`INFOSCRY_URL`,
`INFOSCRY_TOKEN`) and never opens the database, except `infoscry admin`, which
changes account rows directly so the first administrator can be created before
the server runs.

The job worker runs two lanes, each claiming its oldest queued job: up to two
imports, rescans, retries, reindexes or correction runs at once, and beside
them one selectable-PDF job at a time. A short PDF job therefore never waits
behind a long import, and PDF jobs never take an import's place.

## Mutation, deletion and recovery

Services share mutation admission and exclusive maintenance. Reindexing pauses
mutations while existing searches continue.

## Search storage

Every document's published reading has one row per chunk in `search_chunks`
(text, locator, `halfvec` embedding). The rows are written in the same
transaction that makes the reading the document's text, and a deleted document
or collection takes its rows with it, so search never serves a reading the
database does not publish and there is nothing to stage, hide or repair.
[SearchIndex](../src/main/kotlin/infoscry/search/SearchIndex.kt) holds the
operations:

- **Keyword.** Lucene's classic `QueryParser` parses the query with the analyzer
  (standard tokenizer, ICU folding, lower case) and
  [PgSearchQuery](../src/main/kotlin/infoscry/search/PgSearchQuery.kt) translates
  the parsed tree to `paradedb.boolean`/`term`/`phrase`/`regex`/`fuzzy_term`/
  `boost`/`const_score`. The BM25 index tokenizes with
  `pdb.unicode_words('ascii_folding=true')`, which yields the same tokens, so a
  parsed term is an index term. User text never reaches `pdb.parse`. Prefixes,
  wildcards and regular expressions score as constants, as in Lucene. Collection
  and document IDs are literal fields of the BM25 index, so their filters run
  inside it.
- **Vectors.** One HNSW index (`halfvec_ip_ops`) per model dimension on
  `embedding::halfvec(<dimension>)`, created when the index opens. A scope of at
  most 1,000 rows, or four times the requested count, is searched exactly;
  larger ones use iterative index scans. Scores are `(1 + dot) / 2`.
- **Identity.** `search_index_state` records the model the vectors were embedded
  with. A different model in this build makes semantic search refuse with
  `INDEX_REBUILD_REQUIRED` until a full `infoscry reindex` has rebuilt every
  document and recorded the new model; keyword search keeps working.
- **Reindex.** Under exclusive maintenance each document whose rows do not agree
  with its chunks (or every document, after a model change) is re-chunked if
  needed, embedded and replaced in its own transaction, so an interrupted rebuild
  leaves every document whole and the next run continues.

A publication (import, rescan, restore, corrections) is one transaction: recheck
that the document and collection are live and the base is still published,
record the publication, replace the document's rows, make the reading
authoritative and mark it published. Its published hook (the selectable-PDF
queue) runs after the commit; `cleaned_at` records that it ran, and startup runs
it again for a publication whose process died before it.

Collection and document deletion use persisted operations and recovery phases.
An admitted deletion continues even if its initiating request is lost. The UI
reads durable operation status after reload or restart. Deletion removes managed
content and search entries and cancels affected work at a processing boundary.

Document progress comes from committed content units and extraction checkpoints.
Import-history counters count files. Retry reads managed copies, snapshots the
collection's current OCR settings and preserves reusable extraction work.

The Collections spec and its tickets (removed from `docs/` once complete; see git history before commit `c9edfab`) hold the original contracts and
verification.

## Embeddings and GPU requirements

The pinned model is `intfloat/multilingual-e5-base`, with 768-dimensional vectors
and a 512-token input limit. The
[model manifest](../models/embedding-model.json) fixes the revision, checksums,
tokenizer, query/passage prefixes and execution-provider options.

Every complete embedding input must fit within 512 tokens using that tokenizer,
including prefixes, special tokens and repeated headers. Passages are never
silently truncated. Blank pages and a page consisting only of the known `Â`
doubled-encoding artifact are not chunked, embedded or indexed. Single letters,
numbers, formulas and non-Latin text remain searchable. A passage without a run
of two letters or digits (or a Han, kana or Hangul character), such as OCR noise
like `? 1 . . . . . .`, stays a keyword result for its exact characters but is
never returned by semantic search: its vector carries no meaning and sits close
to every query's. Chunker version 3
rebuilds chunks saved under the earlier two-character rule during reindex; the
extraction checkpoint and source reading stay unchanged.

The v1 runtime is macOS arm64 with Apple GPU execution through CoreML and ONNX
Runtime. CPU-only embedding fallback is refused. GPU readiness requires evidence
of actual accelerated execution, not merely registering a provider. Diagnostics,
source viewing and existing keyword search remain accessible if readiness fails.

## Local OCR engines and Surya

A page is read by the engine its attempt selected: Tesseract, the local Surya
model, or a configured image model through a profile. The three are
alternatives, never a chain. `PageOcrEngine` hands an engine one `PageImage`
and the attempt's settings, and answers with one `OcrPageResult`; an engine
cannot choose where it dispatches, cannot switch engines, and cannot call a page
blank. Blankness is a fact about the raster, verified by the caller that holds
it, and an empty reading on its own never establishes it. A PDF page whose
raster has ink and whose reading is empty keeps its own text layer (or no
text) and is counted in the extraction's `empty_reading_pages`, which makes the
document complete with a warning instead of failing it.

No OCR is the engine `NONE` (method id `none`). It has no `PageOcrEngine`; a
No-OCR import emits every PDF page as direct text and a picture as one empty
unit, and counts pages without usable text in `pages_without_text`, which also
makes the document complete with a warning. A No-OCR rescan stages each page's
own text layer, keeps the published text of a page whose layer is unusable, and
records no page total for its operation.

Surya is a vision-language model, so a page is one model call rather than a
command per line. It is served by `llama-server` from
[llama.cpp](https://github.com/ggml-org/llama.cpp) with Metal offload
(`-ngl 99`), spawned by the Python runtime that
[installation.md](installation.md#surya-for-local-page-reading-optional)
describes. This is a **different execution path from embeddings**: embeddings
run the pinned E5 model through ONNX Runtime with the CoreML provider and are
refused without GPU readiness, while Surya's inference is llama.cpp's own Metal
backend and touches neither ONNX Runtime nor CoreML. A working Surya runtime is
not evidence that embeddings work, and the CoreML gate is not a gate on reading
pages.

InfoScry runs that runtime as a worker process it owns. `SuryaOcr` spawns
`scripts/ocr/surya_worker.py` through the configured interpreter, writes one
versioned JSON request line per page (protocol 2) on the worker's stdin — the page's stable
unit id, its ordinal, its page number and the managed image path that
`PageImage` already validated inside its root — and reads one result line per
page from its stdout. There is no shell anywhere in that exchange, and no
user-supplied command: the interpreter and the worker script are configured, so
nothing an imported document carries can become a program or an argument.

| Property | How it is bounded |
|---|---|
| Request and result lines | 64 KiB in, 4 MiB out; an over-long line is drained and refused |
| One page | 20 minutes. The worker and the descendants visible from it *while it is alive* are stopped on timeout or cancellation: their handles are captured as the tree is signalled, deepest first, every one of them is waited for, and a process that survives the kill is reported rather than forgotten. A helper that was already re-parented before the capture is not reachable through `descendants()` and is therefore not accounted for |
| Pipes | stdout is read as a bounded line per page; stderr is drained by a daemon thread that is never waited for on the reading path, so a helper holding it open cannot hold a reading — and stopping the engine joins that reader, bounded, once the process tree is gone |
| One worker | started on the first page of an engine and reused, so the runtime's imports and the model load are paid once, and one page is exchanged at a time |
| Output | a page's text and block count have bounds; crossing one is reported, never trimmed |
| Runtime identity | one probe per attempt that reads page images: the worker script started with `--identity`, bounded at 10 seconds and 4 KiB of line, which starts no model, no `llama-server` and no page |

A result is validated before it becomes evidence: the protocol version, the
result type, and the page the answer claims (unit id, ordinal and page number)
all have to be this page's, every box has to be a rectangle inside the page, and
a confidence has to be a fraction. A result that fails is discarded along with
the worker. The four runtime failures are document-level answers with an install
line — `NEEDS_SURYA` for the interpreter or worker script, `NEEDS_LLAMA_CPP`
when `llama-server` is not installed, `NEEDS_SURYA_MODEL` when the weights
cannot be obtained, and `SURYA_START_FAILED` when the server will not come up —
and everything else is that page's failure. Each of them reaches the item, the
queue and the CLI as `NEEDS_TOOL` with that install line as the message, the way
a missing Tesseract does, so a document waiting for a tool is distinguishable
from a broken one. Tesseract is never consulted for a page whose settings select
Surya.

Surya answers with block boxes rather than word boxes, and one confidence per
page: the mean token probability of the single full-page call, which the library
reports on every block of that page. That is what a reading carries, and with
`SURYA_INFERENCE_LOGPROBS=false` the library substitutes `1.0` instead of
measuring anything — a run configured that way reports **no** confidence rather
than a certainty nobody measured, and the runtime is documented and verified
with the default (logprobs on).

The identity of the runtime is asked for **before** a page is read rather than
learned from one: `SuryaOcr` starts the worker script once with `--identity`,
which answers the installed `surya-ocr` version, the backend, the checkpoint,
the cached weights' revision and size and the `llama-server` build without
starting a model or a server (about 1.4 s measured, against 3.6 s for a first
page, and once per attempt that reads page images rather than once per page).
That identity is written into the attempt the extraction fingerprints
(`OcrAttemptIdentity.runtimeIdentity`) *before* any committed key is read, so
another Surya version, other weights or another `llama-server` build is another
key and the page is read again — a `brew upgrade` of `llama.cpp` alone changes
what a reading is. A runtime that cannot be described is recorded as **no**
identity, which is a value of its own and never matches a discovered one: the
page is read again instead of being reused under weights nobody named. Each
reading additionally carries the identity the running worker reported for itself
(`OcrPageResult.modelVersion`): the package version, the backend and the cached
weights' revision and sizes, which is the reading's own provenance.

Measured on the development machine (macOS 27.0.1, Apple M1 Max, 32 GB):
`surya-ocr` 0.22.1 with torch 2.14.1 and transformers 5.18.0 under Python
3.12.14, served by `llama-server` 0.5.0 (build 11146, commit `7fe450e19`), on
the 1.36 GiB `datalab-to/surya-ocr-2-gguf` weights. One 900x260 typed page came
back in 3.6 s including the worker's start and the model load, and the second
page in 1.3 s, in the `externalTest` run recorded by
[SuryaRealToolTest](../src/test/kotlin/infoscry/ocr/SuryaRealToolTest.kt).
Per-invocation numbers from the command line are much larger — the CLI pays the
interpreter's imports and the Hub cache check every time, which the worker pays
once. `llama-server` held about 2.9 GiB resident (`ps` RSS, which includes its
Metal buffers) while reading a page with the runtime's default
`--parallel 8 --ctx-size 98304`.

Two limitations belong to these numbers. The second committed fixture is a
Caveat *typeface render*, not handwriting a person wrote, so it exercises
irregular letterforms and says nothing about transcription quality on real
handwriting — that is measured in the pilot on user-supplied samples. And when
the server is stopped, llama.cpp 0.5.0 logs an assertion and a native backtrace
in `~/.cache/datalab/surya/llamacpp_server.log` while tearing its Metal buffers
down; the process is gone either way, which `SuryaRealToolTest` asserts by
comparing the `llama-server` processes before and after.

### Page structure

An engine's reading carries an optional `PageStructure` (`infoscry.ocr`,
`STRUCTURE_VERSION` 1): blocks in reading order, each with its kind (heading,
paragraph, list item, table, formula, code, caption, footnote, page header or
footer, figure), its level, rows and cells for tables, and two ranges for each
block and cell, one into `extractedText` and one into `searchText`. The text is
derived from the blocks, never the other way round, so the structure can be
dropped and rebuilt without changing what is searched or cited.

| Engine | Parser | Input |
|---|---|---|
| Surya | `surya-html/1` | Worker protocol 2 sends each block's `html`, `label`, `readingOrder` and `bbox` (from the polygon). The parser allowlists `table`, `thead`/`tbody`, `tr`, `th`/`td` (`rowspan`, `colspan`), headings, lists, `math` and `code`, and the block order is `readingOrder` |
| LLM reader | `markdown/1` | Transcription prompt version 2 asks for GitHub-flavoured Markdown with page header and footer comment markers; a hand-written, line-based Markdown parser with no library builds the blocks |
| Tesseract | `tesseract-tsv/1` | Lines grouped by Tesseract's own block and paragraph numbers; no tables or headings |

- Derivation invariants (ranges fit both texts, do not overlap, cover every
  non-whitespace character, never split a surrogate pair) are checked when a
  reading is made and again when it is committed. A structure that breaks them
  is not committed; the page keeps a paragraph-only structure marked degraded.
- The artifact is `structure/page-%06d.<sha256>.structure.json.gz` under the
  document's artifact root, written atomically and content-addressed, with its
  reference and hash on the content unit (`structureRelativePath`,
  `structureSha256`, complete or both absent). A hash mismatch is treated as
  missing structure: the page is shown and cited without it.
- `STRUCTURE_DEGRADED` is the `degraded` flag on the structure artifact, shown
  by page review. There is no separate table for it.
- The reader's source-page API sends `structure` with `start`/`end` ranges only, in
  the `extracted` text's space, shifted by the window offset and clamped to the
  window. It sends no `search` ranges. Hit highlighting is not wired: the reader
  does not mark hits today, and `StructuredPage` has a highlight prop that nothing
  sets.
- The structure version and the parser version are part of the reading's
  identity and the extraction fingerprint, so a changed version reads the page
  again on rescan, and the previous reading stays in history.
- Chunking, evidence rendering and the selectable-PDF text layer read the
  structure only when it is present and verified against the unit's text;
  otherwise they behave as they did before structure existed.

The contract is tested by the parser suites, the derivation and chunker tests,
`StructuredEvidenceSourceTest`, the selectable-PDF text-layer tests, the
`DocumentBrowserAcceptanceTest` scanned-table scenario (fake engine) and
`SuryaRealToolTest`'s table page on the real runtime.

## Collection corrections

The [design](specs/2026-10-22-collection-corrections-design.md) is implemented.

- **Storage.** `collection_corrections` holds a collection's rules (`find`,
  `find_key`, `replace`, `exact_case`); `find_key` is the case-folded `find`, or the
  exact one for an exact-case rule, and is unique per collection
  (`409 CORRECTION_FIND_TAKEN`). A corrected page keeps its reading in
  `reading_extracted_text`/`reading_search_text` and the reading's own structure and
  layout references in `reading_references` (JSON), on both `page_text_revisions`
  and `content_units`. A revision records `corrections_fingerprint` (SHA-256 of the
  list's rules) and `corrections_applied` (per rule: find, replace, matches).
- **Matching** (`CorrectionMatcher`, ICU4J from `lucene-analysis-icu`): over the
  text's NFC view mapped back to the stored text, on code points; word boundaries
  are neighbours that are not `L*`, `M*` or `Nd`; full case folding; a rule written
  only in Han, Kana, Thai, Lao, Khmer or Myanmar matches as a substring; longest
  match wins, then exact case, then the smaller id; no chaining.
- **One seam.** `CandidateCorrector` corrects a staged candidate from each page's
  reading before it is chunked (rescan, check-and-improve import, restore, the
  `CORRECTIONS` job); `StoredUnitsSink` corrects each unit of a plain import before
  commit. `PageCorrector` shifts structure ranges and layout spans with an
  `OffsetMap`, re-verifies them, and falls back to degraded paragraphs or no layout.
- **Job.** `JobType.CORRECTIONS`, one per collection. Each pass skips documents whose
  text already follows the list (`wouldChange`), stages a copy of the active revision
  with its vectors (`stageCorrectionCandidate`, resumable by base and list), re-chunks
  and embeds only rewritten pages, and publishes through `RevisionPublicationService`.
  A list changed during a pass takes another pass; a document another publication
  moved is redone from its new revision (at most 3 times, then `CORRECTIONS_CONFLICT`);
  no embedder fails it as `EMBEDDING_UNAVAILABLE`.
- **API.** `GET/PUT/DELETE /api/collections/{id}/corrections[/{ruleId}]`,
  `POST …/corrections/preview` (a draft, or `removingId`), `POST …/corrections/apply`,
  `GET …/sources/{sourceId}/reading`; source reads carry `hasReading`, revision views
  `corrections`. CLI: `infoscry collection corrections list|add|remove|preview`.
- Rules are user content and stay out of logs and error messages.

## Collection glossary

The [design](specs/2026-10-23-collection-glossary-design.md) is implemented.

- **Storage.** `collection_glossary_groups` and `collection_glossary_terms` (`text`
  as typed, NFC; `match_key`, the term's tokens under the index analyzer joined by
  one space; `prefix`). A key is unique per collection
  (`409 GLOSSARY_TERM_TAKEN`, `details.term` and `details.groupId`); a group id
  another collection owns is `409 GLOSSARY_ID_TAKEN`; an invalid group is
  `400 INVALID_GLOSSARY_GROUP` with `details.term` when one term is at fault. Rules
  in `GlossaryRules`: 2–20 terms, at most 200 characters and 8 tokens each, prefix
  only on one token of at least 3 characters.
- **Search.** `GlossaryService.snapshot` (cached per collection, dropped on every
  write) feeds `SearchService`. `SearchIndex.expandKeyword` parses the query and
  `GlossaryQueryRewriter` adds alternatives to runs of plain text-field term
  clauses and to phrases (`GlossaryMatcher`: longest match, prefix, no chaining):
  SHOULD hits gain SHOULD alternatives, MUST hits become one MUST choice, phrases
  gain up to 16 variants, prefix terms are `PrefixQuery`. At most 256 added clauses
  and the whole query within `MAX_QUERY_TOKENS`; `expansionTruncated` reports what
  was left out. Across collections each expansion is filtered to its collection.
  Alternatives also anchor `hasLexicalAnchor` and are highlighted. A glossary
  failure leaves the search unexpanded. The search response carries
  `expandedWith` and `expansionTruncated`, omitted when empty.
- **LLM.** `GlossaryPrompt` renders the groups a request names after the
  collection prompt in the system message, at most 40 groups and 1 500 budget
  units. `ContextPacker` reserves the question's groups before choosing evidence
  and adds the evidence's groups only where they fit; `pruneWithGlossary` does the
  same for each Investigate call and never stores the block.
- **API.** `GET/PUT/DELETE /api/collections/{id}/glossary[/{groupId}]`. CLI:
  `infoscry collection glossary list|add|remove`.
- Terms are user content and stay out of logs and error messages.

## Privacy and API boundaries

Originals, extracted text, embeddings, indexes and saved conversations stay
on the server and in its database. Ask and Investigate send prompts, questions, selected evidence and
citation metadata to the configured OpenAI-compatible or Anthropic endpoint.
That endpoint may be external; these features are not necessarily offline.

The server binds `INFOSCRY_BIND` (default `127.0.0.1:8080`). The `Host` header
must be a loopback name or the host of `INFOSCRY_PUBLIC_ORIGIN`, and a mutation
whose `Origin` is present must come from that origin or the loopback origin it
addressed. Every `/api/` route except `GET /api/session`, sign-in and sign-out
needs a signed-in caller: a browser session (cookie `infoscry_session`,
`HttpOnly`, `SameSite=Lax`, `Secure` behind HTTPS) or a personal access token
(`Authorization: Bearer isc_…`). A mutation with the cookie also carries the
session's CSRF token in `X-InfoScry-Csrf`; it is derived from the session ID,
so it is bound to that session. Only SHA-256 digests of session IDs and tokens
are stored. A user whose password was reset may only change it. See
[Security.kt](../src/main/kotlin/infoscry/server/Security.kt) and
[AccountService](../src/main/kotlin/infoscry/account/AccountService.kt).

Accounts are local: usernames are case-insensitive, passwords are at least 12
characters and hashed with Argon2id (19 MiB, two passes, one lane; the
parameters are stored in the hash). Sessions end after 12 idle hours or 14
days. Failed sign-ins are limited to 10 per username and 50 per client address
in 15 minutes (429 with `Retry-After`); the answer never says whether the
username exists. Users are never deleted; deactivation ends their sessions and
revokes their tokens, and the last active administrator cannot be deactivated
or demoted. Personal access tokens are created and revoked only from a browser
session.

Collections have members with the role `OWNER`, `EDITOR` or `READER`
(`collection_members`); the creator of a collection becomes its owner, and a
collection always keeps one (409 `LAST_OWNER`). Readers search, read and ask;
editors also import, rescan, retry, delete documents, review pages and edit
corrections, glossary groups and prompts; owners also change settings and
members, reindex and delete the collection. Administrators act as owners of
every collection and alone manage users, LLM and OCR profiles and a reindex of
everything. A collection the caller is not a member of answers 404 as if it did
not exist; too low a role answers 403 `COLLECTION_ROLE_REQUIRED`. Every API
route has an entry in the rule table in
[Authorization.kt](../src/main/kotlin/infoscry/server/Authorization.kt), which
the request guard enforces and a test checks against the routing tree. Ask
conversations and investigations belong to the user who started them and are
private to that user.

Attribution is part of the records: collections, correction rules, glossary
groups, prompts and LLM and OCR profiles have `created_by` and `updated_by`;
documents `imported_by`; jobs `started_by` and `cancelled_by`; document
revisions `created_by`; page decisions `decided_by`. The request guard runs each
request as its caller and the job runner each attempt as the job's starter
([Actor](../src/main/kotlin/infoscry/storage/Actor.kt)); `Database` hands the
actor to each connection as the setting `infoscry.actor`, and triggers in the
baseline stamp it, so work a job does is attributed to whoever started it, also
after a restart. `NULL` means nobody acted in person (startup recovery).

Collections, correction rules, glossary groups and prompts carry a `version`.
An edit may send it in `If-Match`; a stale version answers 409 `EDIT_CONFLICT`
naming who changed the record, and an edit equal to the current content changes
nothing whatever version it names. An edit without `If-Match` (the CLI) applies
to what is current. Starting an import of files an active import job of the
collection already holds answers with that job; a rescan under another user's
running OCR operation answers with that operation. The first approve or reject
of a published reading decides; a later one answers with the recorded decision
and starts no restore.

Imports read from two places ([ImportRoots](../src/main/kotlin/infoscry/jobs/ImportRoots.kt)):
the directories in `INFOSCRY_IMPORT_ROOTS` and the collection's upload area
`<data>/uploads/<collection>/<upload>/<file>`. The import preview, which the
start also runs, refuses any path whose real path (links resolved) is outside
them with 403 `IMPORT_PATH_NOT_ALLOWED`, for administrators too. Editors browse
the roots through `GET /api/import-roots/browse`, where links out of a root are
not listed. An upload is one file per request as the raw body
(`POST /api/collections/{id}/uploads`, file name in `X-InfoScry-Filename`),
streamed and hashed; bytes that are already a document of the collection answer
with that document and are not kept. Preview and start take upload ids in
`uploads`. Upload areas older than seven days are removed at start. The
server's native pick dialog stays administrator-only.

API keys are read only from environment variables. Profiles and browser requests
carry the variable's name, never its value. Keys, questions, answers, document
text, provider authorization headers, passwords, session IDs, CSRF tokens and
access tokens must stay out of logs and errors; usernames may be logged for
sign-in and administration events.

Imported instructions are untrusted source data. LLM tools use collection-scoped
opaque IDs: they cannot switch collections, access arbitrary paths or browse
the web. Windows, knowledge graphs, DRM bypass and an external MCP server are
outside scope.

## LLM profiles and model catalog

InfoScry calls configured endpoints and does not host an LLM. Profiles store
provider, endpoint, model, context/output limits, prices, tool support and the
API-key environment-variable name. Ask and Investigate have separate optional
default profiles.

Admin's model picker uses read-only `GET /api/llm/presets` and
`GET /api/llm/catalog`. Catalog parameters are `provider`, `endpoint` and
`apiKeyEnvironmentVariable`. The catalog sends the server's key to the named
endpoint, so it asks what a mutation does: a token, or the session's CSRF token.
Only the server reads the named API-key variable.

Catalog prices come from OpenRouter's `/models` response, the built-in
[provider table](../src/main/resources/llm/providers.json) for OpenAI and
Anthropic, and zero pricing for loopback endpoints such as Ollama. Unknown values
remain blank for manual entry. Catalog/unit/route/component coverage does not
establish real-provider browser acceptance.

## Collection prompts

Ask and Investigate send the system message `CORE_RULES + "\n\n" + <selected prompt body>`.
`CORE_RULES` is code, always first and unchanged. The selected prompt is either the
shipped Default (id `default`, name `Default`, version 1, read from the classpath; not a
row) or a stored prompt of the collection and role.

- `collection_prompts` holds stored prompts: `id`, `collection_id`, `role` (`ASK` or
  `INVESTIGATE`), `name`, `body`, `version`, and timestamps. Names are unique per
  collection and role, case-insensitively, and `Default` is reserved. Deleting the
  collection deletes its prompts. A taken name answers `409 PROMPT_NAME_TAKEN`, also
  when a racing write meets the unique index; a prompt reference outside the collection
  and role answers `400 INVALID_REQUEST`.
- `collections.ask_prompt_id` and `collections.investigate_prompt_id` hold each role's
  collection default. `NULL` means Default. They reference `collection_prompts` with
  `ON DELETE SET NULL`, so deleting a prompt falls back to Default.
- `version` grows only when the body changes. Each write is one database transaction and
  every operation is idempotent.
- A conversation records its prompt when it is created: `prompt_id` (nullable and
  without a foreign key, so it survives the prompt's deletion), `prompt_name`,
  `prompt_body`, and `prompt_version`. A continued Investigate turn composes from the
  stored `prompt_body` and does not read the prompt rows again.
- Prompt bodies are user content. Like questions and answers, they stay out of logs
  and error messages.

## Request budgets and citation validation

Budget the full LLM request and reserved output, including history, tools and
citation metadata. Context pruning preserves complete tool-call/result groups.
Evidence IDs stay stable and citations are validated only against evidence
actually sent in the generating request.

Investigate restores retained historical evidence for follow-ups. Evidence
pruned from a request cannot justify a new citation, though old displayed
citations still resolve. One final answer is adopted per completed turn;
superseded drafts remain audit data rather than displayed conversation answers.
Citation validity alone does not prove that a claim is supported by its source.

### Saved evidence provenance

Saved evidence carries its own provenance and is read back from itself, never
from the live unit. An Ask citation (`citations`) and an Investigate ledger
entry (`evidence_ledger`) each store the `excerpt` that was supplied and a
nullable `revision_id`, the revision that excerpt was read from. A search hit
names its revision; a unit read directly names the document's active revision
only when the revision and the unit's text are unchanged across the read, and
otherwise none. A null revision means "revision unknown", not "current".
Neither history read joins to live unit text, so evidence whose unit a
replacement removed stays in history (placed in its document through the
revision that once held the unit, using the `page_text_revisions (unit_id)`
index) and current text is never shown as old evidence. The viewer opens the
named revision when there is one; otherwise it shows the saved excerpt labelled
"revision unknown". `revision_id` is not a foreign key and is never sent to the
model.

## Investigate limits

[Effort contracts](investigate-effort.md) describe resolution, validation and native transport.

The [user guide](usage.md#investigate-and-ask-follow-up-questions) lists the four
effort levels. The browser stores its selection under `infoscry-investigate-effort:v1`;
the old numeric preference is ignored. Start/continue accept `limits: { effortLevel }`,
defaulting to MEDIUM when omitted, and reject obsolete numeric fields or unknown levels.
The server resolves sparse per-profile overrides against global defaults and reports
`resolvedLimits` only on the `started` SSE event. Follow-ups use the conversation's
locked provider profile. Limits apply to future turns.

Optional profile-native reasoning affects research requests only. Anthropic signed
and redacted content is retained in request-local ordered envelopes and measured in
the actual provider budget; required tool exchanges cannot be pruned. It never enters
stored history or SSE. Synthesis and citation correction use None and an envelope-free
outbound view. The nullable `effort_overrides_json` column is part of the fresh baseline
only: existing version-1 archives require separately authorized recreation before
using this schema; no automatic migration or user-data reset is included.

Time includes request preparation, provider work, synthesis and citation
correction, but excludes the optional conversation title. Synthesis reserves
`min(120 seconds, 20% of the total budget)`. Reaching a research limit leads to
a tool-free final answer from the evidence available to the model.

The 120-second provider-inactivity cap resets on every provider event. An active
stream may outlive one interval, but the fixed total deadline still bounds the
turn. A synchronous native tool cannot be hard-cancelled and may overshoot the
research deadline by one in-flight call.

Repeated calls are identified by tool name and canonical JSON arguments.
Object-key order and whitespace are ignored; array order, types and values are
preserved. An equivalent repeated call is refused and research ends with
synthesis from available evidence.

The Investigate spec and its verification report (removed from `docs/` once complete; see git history before commit `c9edfab`) hold the original
contracts and evidence.
