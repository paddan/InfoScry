# pg_search spike

Ticket 1 of the [multi-user server spec](../../../docs/specs/2026-11-05-multi-user-server-design.md):
compare ParadeDB `pg_search` BM25 with InfoScry's current Lucene keyword search before the search port.
Run 2026-11-05 on macOS arm64 with Podman, `paradedb/paradedb:0.26.5-pg17` (`pg_search` 0.26.5,
`pgvector` 0.8.6) and Lucene 10.4.0.

## Verdict

**`pg_search` is fit for approach 1.** With the same tokens and the same BM25, it returns the same
matches and, apart from ties, the same order. Every difference found is about query syntax, not
ranking, and InfoScry removes it by building queries with the query-builder functions instead of
handing user text to `pdb.parse`. The port needs that query builder anyway, because the glossary
rewrite works on a parsed query.

## Setup

- **Corpus:** 1,753 Swedish statutes from Riksdagen's open data (`scripts/fetch_sfs_laws.py` →
  `data/sfs-lagar`; statutes are public under 9 § of the Swedish Copyright Act). `prepare.py` cuts them
  into 29,161 chunks of at most 1,500 characters, roughly InfoScry's 512-token passages. Both engines
  index the same chunks.
- **Lucene side** (`LuceneSide.java`): InfoScry's analyzer (standard tokenizer, ICU folding, lower case),
  default BM25, the classic `QueryParser` with InfoScry's escape fallback.
- **pg_search side** (`pg_side.py`): `USING bm25 (id, (text::pdb.icu('ascii_folding=true')))`, default
  BM25, `pdb.parse` on the text column (strict first, lenient on a syntax error). Rows with a fifth column
  in `queries.tsv` run a query-builder predicate instead, written the way InfoScry would generate it.
- **Queries** (`queries.tsv`): 43 user queries in ten categories (terms, OR, phrases, prefixes, boolean,
  folding, numbers, special characters, natural-language questions as Ask sends them, glossary
  expansions) plus 6 builder variants. `probes.txt` compares tokenization directly.

Run it with `scripts/spikes/pg_search/run.sh` (`ENGINE=podman` without Docker). Results land in
`build/spike-pg-search/`, with per-query metrics in `comparison.tsv`.

## Results

| Measure | Result |
|---|---|
| Same match count | 41 of 49 queries; all 8 differences explained below |
| Same top hit | 37 of 49 |
| Same count and same top hit | 36 of 49 |
| Scores | pg_search = 2.200 × Lucene on all 344 shared hits of queries that mean the same in both engines (only the hyphen phrases `s1`, `q2` differ): the `(k1 + 1)` factor Lucene drops. Same BM25 |
| Top-10 overlap below 1.0 with identical counts | Ties at the cut: the tenth hits on both sides have equal scores, so their order is arbitrary. `n1` and `q5` are explained by difference 2 and the `?` defect below |
| Tokens | Identical for every probe (folding `Malmö`→`malmo`, `straße`→`strasse`, `ﬁ`→`fi`, `e-post`, `12:3`, `bl.a.`), except CJK: ICU segments `東京都` as `東京 都`, Lucene as `東 京 都` |
| Latency (median, warm) | Lucene 0.01–0.7 ms in-process; pg_search 0.6–2 ms including the JDBC round trip (5.6 ms worst) |
| Index build | Lucene 1.8 s (cold JVM), pg_search 0.6 s |
| Index size | Lucene 11.7 MB, pg_search 16.8 MB |
| Filter pushdown | A `doc` field in the BM25 index turns `AND doc = …` into an index term: 0.7 ms, against 3.9 ms as a heap filter |

## Differences and what the port does about them

1. **Prefixes are not supported by `pdb.parse`.** `förvaltn*` finds nothing and `sekretess*` is treated as
   `sekretess`. `pdb.regex('forvaltn.*')`, with the prefix folded by the same analyzer and regex-escaped,
   finds exactly Lucene's 2,182, 590 and 730 matches. The ordering differs: Lucene scores a prefix as a
   constant (order by document ID), `pdb.regex` scores it by BM25. Neither order carries meaning for a
   pure prefix query. Combined with other terms (glossary prefix flag, `g4b`), counts and ranking agree.
2. **Unqualified `pdb.parse` also searches the key column.** `3 kap 5` matched chunks 3 and 5 by `id`,
   even with `text @@@ pdb.parse(…)`. Only `text:(…)` or a builder function stays on the text. InfoScry
   must never send user text to an unqualified `pdb.parse`.
3. **Hyphens and slashes.** `pdb.parse` makes `e-post` a phrase (5 matches) and `och/eller` a phrase (27).
   Lucene ORs the parts (1,764 and 26,128), mostly matching `post` or `och` alone. `text ||| 'e-post'`
   reproduces Lucene exactly. The phrase is arguably more precise; keeping today's behavior is a product
   choice for the port.
4. **Strict syntax.** `pdb.parse` rejects `3 kap. 5 §`, `SFS 2010:800` and `1962:700`; lenient mode
   recovers with Lucene's counts. Builder functions avoid the parser entirely.
5. **One ParadeDB index per table.** Collection, document and revision IDs used as filters must be fields
   of the one BM25 index from the start, or filters run against the heap.

## Defects found in today's Lucene search

Both come from passing raw user text, including Ask questions (`AskService` → hybrid search), to the
classic `QueryParser`:

- A trailing `?` is a wildcard: `… av unga?` searches for `unga` plus exactly one character, so the word
  `unga` itself drops out of the keyword half (`q5`: top-10 overlap 0.1).
- A colon names a field: `1962:700` and the `2010:800` in `SFS 2010:800` search a field called `1962` or
  `2010` and find nothing (`n2`, `n3`: 0 matches for the number in both engines).

## Not covered

Hybrid fusion with vectors, highlighting offsets as InfoScry's reader uses them (`pdb.snippet` works,
not compared), the cross-collection glossary branch, write and update throughput, and scale beyond this
corpus (29k chunks; the sizing target is about 3 million).
