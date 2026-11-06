# Ticket 3: Search in Postgres

Spec: [Multi-user server](../../specs/2026-11-05-multi-user-server-design.md), ticket 3 of the suggested
order, after the [pg_search spike](../../../scripts/spikes/pg_search/README.md) and
[ticket 2](02-postgres-storage.md). Status: implemented on branch `pg-search` (2026-11-06), not yet
merged to `main`.

## Goal

Keyword and vector search run in PostgreSQL: BM25 through `pg_search`, nearest neighbours through
`pgvector`. The Lucene index goes away, and with it everything that existed only to keep a second store
consistent with the database.

## Decisions

The owner chose on 2026-11-06 to simplify fully rather than port the Lucene protocol behind the same
interface, and to store vectors in half precision.

| Question | Decision |
|---|---|
| Where rows live | One table, `search_chunks`: one row per chunk of each document's published reading, with foreign keys to its collection, document and revision (`ON DELETE CASCADE`) |
| When rows are written | In the same transaction that makes a reading the document's text (import, rescan, restore, corrections) |
| Publication protocol | One transaction: recheck, record, replace rows, make authoritative, mark published. The snapshot gate, reader leases, staged rows, the cleanup drain and the authority roll-forward are removed |
| Generations | None. A reindex rebuilds each document in its own transaction under exclusive maintenance, and records the model last |
| Tokenizer | `pdb.unicode_words('ascii_folding=true')`, which yields exactly the tokens of the Lucene analyzer (verified on every spike probe, including CJK) |
| Query parsing | Lucene's classic `QueryParser` and the glossary rewriter stay as libraries; the parsed tree is translated to `paradedb` builders. User text never reaches `pdb.parse` |
| Prefix, wildcard, regex | Constant-score `paradedb.regex`, as Lucene scores multi-term queries |
| Vectors | `halfvec` plus its dimension; an HNSW index per dimension on `embedding::halfvec(<dimension>)`; exact search for a scope of at most 1,000 rows or four times the requested count; iterative scans otherwise |
| Model identity | `search_index_state`; a different model makes semantic search refuse until a full reindex |
| Deletion | The deletion state machine is unchanged; its index phase is idempotent work because the rows go with the document and collection anyway |

## Out of scope

Native `timestamptz`/`jsonb`, the job queue, users and the multi-user rules (tickets 4–8).

## Verification record (2026-11-06)

- `./gradlew check` on this Mac with Podman: 2,205 backend and 576 frontend tests, 0 failures.
- Manual with the real CoreML embedder (model reinstalled with `./gradlew embeddingModel`) and a ParadeDB
  container: a CLI import of three fixtures, keyword, semantic and hybrid search with the same hits as on
  `main`, ten rows tagged with their revision and a 768-dimension HNSW index; `reindex` with an unchanged
  model kept the rows; with an older model on record semantic search refused with `INDEX_REBUILD_REQUIRED`,
  a full `reindex` re-embedded and recorded the model, and hybrid search worked again; `serve`, a restart
  and the same search.
- Not run: `externalTest` (browser acceptance, Tesseract) and `gpuIntegrationTest`.
- `SearchIndexTest` covers the translated query syntax (phrase, prefix, folding, `+`/`-`, wildcard,
  fuzzy, exclusions alone), glossary expansion, lexical anchors, vector search and model identity.
- Tests of the removed protocol (snapshot gate, reader leases, publication kill points, generation swap
  and sweep) were deleted; publication, restore, corrections and reindex keep their behavioral tests, and
  new ones cover a deletion that lands before publication, recovery of a publication record that never
  took effect, a publication switching search and source text together, and a rebuild killed before it
  records the model.
