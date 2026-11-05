#!/usr/bin/env python3
"""The pg_search half of the spike: loads chunks.tsv into ParadeDB, builds a BM25 index with the ICU
tokenizer and ASCII folding, and runs queries.tsv through pdb.parse on the text column (strict first, lenient on error).
An unqualified pdb.parse on the table also searches the id key, so a query "3" would match chunk 3.

A row with a fifth column runs that SQL predicate instead of pdb.parse.

Usage: uv run --with 'psycopg[binary]==3.2.13' pg_side.py <dsn> <chunks.tsv> <queries.tsv> <probes.txt> <out-dir>
"""
import pathlib
import statistics
import sys
import time

import psycopg

TOKENIZER = "pdb.icu('ascii_folding=true')"


def main() -> None:
    dsn, chunks, queries, probes, out = sys.argv[1], *map(pathlib.Path, sys.argv[2:6])
    with psycopg.connect(dsn, autocommit=True) as conn:
        conn.execute("DROP TABLE IF EXISTS chunks")
        conn.execute("CREATE TABLE chunks (id bigint PRIMARY KEY, doc text NOT NULL, text text NOT NULL)")
        with conn.cursor().copy("COPY chunks (id, doc, text) FROM STDIN") as copy:
            for line in chunks.open(encoding="utf-8"):
                copy.write_row(line.rstrip("\n").split("\t", 2))
        start = time.monotonic()
        conn.execute(f"CREATE INDEX chunks_bm25 ON chunks USING bm25 (id, (text::{TOKENIZER}))")
        build_ms = (time.monotonic() - start) * 1000
        index_bytes = conn.execute("SELECT pg_relation_size('chunks_bm25')").fetchone()[0]

        hits_out = (out / "pg-hits.tsv").open("w", encoding="utf-8")
        counts_out = (out / "pg-counts.tsv").open("w", encoding="utf-8")
        for line in queries.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("\t")
            qid, query = fields[0], fields[3]
            mode, error, params = "strict", "", ()
            if len(fields) > 4:
                # A query-builder predicate written in this file, standing in for what InfoScry would generate.
                mode, predicate = "builder", fields[4]
            else:
                try:
                    conn.execute("SELECT count(*) FROM chunks WHERE text @@@ pdb.parse(%s)", (query,))
                except psycopg.Error as failure:
                    mode, error = "lenient", " ".join(str(failure).split())[:200]
                predicate, params = "text @@@ pdb.parse(%s, lenient => %s)", (query, mode == "lenient")
            top_sql = f"SELECT id, pdb.score(id) AS s FROM chunks WHERE {predicate} ORDER BY s DESC LIMIT 10"
            rows, runs = [], []
            for i in range(8):
                begin = time.perf_counter()
                rows = conn.execute(top_sql, params).fetchall()
                if i >= 3:
                    runs.append((time.perf_counter() - begin) * 1000)
            total = conn.execute(f"SELECT count(*) FROM chunks WHERE {predicate}", params).fetchone()[0]
            counts_out.write(f"{qid}\t{total}\t{statistics.median(runs):.3f}\t{mode}\t{error}\n")
            for rank, (chunk_id, score) in enumerate(rows, 1):
                hits_out.write(f"{qid}\t{rank}\t{chunk_id}\t{score:.6f}\n")
        hits_out.close()
        counts_out.close()

        with (out / "pg-tokens.tsv").open("w", encoding="utf-8") as tokens_out:
            for probe in probes.read_text(encoding="utf-8").splitlines():
                if probe.strip():
                    tokens = conn.execute(f"SELECT %s::{TOKENIZER}::text[]", (probe,)).fetchone()[0]
                    tokens_out.write(f"{probe}\t{' '.join(tokens)}\n")
        (out / "pg-stats.tsv").write_text(f"build_ms\t{build_ms:.0f}\nindex_bytes\t{index_bytes}\n")


if __name__ == "__main__":
    main()
