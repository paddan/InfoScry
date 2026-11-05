#!/usr/bin/env python3
"""Compares the Lucene and pg_search runs: match counts, top-10 overlap, same top hit and rank-biased overlap.

Usage: compare.py <queries.tsv> <out-dir>   (writes <out-dir>/comparison.tsv and prints a summary)
"""
import collections
import pathlib
import sys


def hits(path: pathlib.Path):
    table = collections.defaultdict(list)
    for line in path.read_text(encoding="utf-8").splitlines():
        qid, _rank, chunk_id, score = line.split("\t")
        table[qid].append((chunk_id, float(score)))
    return table


def counts(path: pathlib.Path):
    table = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = line.split("\t")
        table[fields[0]] = fields
    return table


def rbo(left, right, p=0.9):
    """Rank-biased overlap truncated at depth 10 (Webber et al.), 1.0 for identical lists."""
    if not left and not right:
        return 1.0
    depth = max(len(left), len(right))
    total, weight_sum = 0.0, 0.0
    for d in range(1, depth + 1):
        agreement = len(set(left[:d]) & set(right[:d])) / d
        weight = p ** (d - 1)
        total += weight * agreement
        weight_sum += weight
    return total / weight_sum


def main() -> None:
    queries, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    lucene_hits, pg_hits = hits(out / "lucene-hits.tsv"), hits(out / "pg-hits.tsv")
    lucene_counts, pg_counts = counts(out / "lucene-counts.tsv"), counts(out / "pg-counts.tsv")
    rows = []
    for line in queries.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        qid, category, lucene_query = line.split("\t")[:3]
        left = [chunk for chunk, _ in lucene_hits.get(qid, [])]
        right = [chunk for chunk, _ in pg_hits.get(qid, [])]
        scores = [score for _, score in lucene_hits.get(qid, [])]
        # A tie at the cut means the tenth place is arbitrary on the Lucene side.
        tied_cut = len(scores) == 10 and abs(scores[8] - scores[9]) < 1e-6
        expected = min(10, len(left), len(right))
        overlap = len(set(left) & set(right)) / expected if expected else (1.0 if len(left) == len(right) else 0.0)
        rows.append({
            "qid": qid, "category": category, "query": lucene_query,
            "lucene_total": int(lucene_counts[qid][1]), "pg_total": int(pg_counts[qid][1]),
            "overlap10": overlap, "top1": bool(left and right and left[0] == right[0]) or (not left and not right),
            "rbo": rbo(left, right), "tied_cut": tied_cut,
            "lucene_ms": float(lucene_counts[qid][2]), "pg_ms": float(pg_counts[qid][2]), "pg_mode": pg_counts[qid][3],
        })
    columns = list(rows[0])
    with (out / "comparison.tsv").open("w", encoding="utf-8") as sink:
        sink.write("\t".join(columns) + "\n")
        for row in rows:
            sink.write("\t".join(f"{row[c]:.2f}" if isinstance(row[c], float) else str(row[c]) for c in columns) + "\n")
    print(f"{'qid':5} {'cat':9} {'L total':>8} {'P total':>8} {'ov@10':>6} {'top1':>5} {'rbo':>5} {'tie':>4} {'L ms':>6} {'P ms':>6} mode")
    for r in rows:
        print(f"{r['qid']:5} {r['category']:9} {r['lucene_total']:8} {r['pg_total']:8} {r['overlap10']:6.2f} "
              f"{str(r['top1'])[0]:>5} {r['rbo']:5.2f} {('T' if r['tied_cut'] else ''):>4} {r['lucene_ms']:6.2f} {r['pg_ms']:6.2f} {r['pg_mode']}")


if __name__ == "__main__":
    main()
