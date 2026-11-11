#!/usr/bin/env python3
"""
Download consolidated text of Swedish laws currently in force from
Riksdagen's open data API (data.riksdagen.se). Stdlib only.

Included:
  - acts whose title is a law: "Lag (...)", "...lag (...)", "...balk (...)"
  - the four fundamental laws (grundlagar), by SFS number
  - optionally the Riksdag Act (riksdagsordningen), --include-riksdagsordning
Excluded:
  - ordinances (förordningar), proclamations, etc.
  - repealed acts (header line "Upphävd: <date>" with date <= today)
  - acts not yet in force ("/Träder i kraft I:<date>/" in header), unless
    --include-not-in-force

Caveat: pre-1974 acts passed by the Riksdag may be titled "Förordning"
and are therefore not caught by the title filter.

Output: <out>/<sfs-nr>.txt (or .html) + <out>/index.jsonl manifest.
Re-runs skip files that already exist; use --force to refetch.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

API = "https://data.riksdagen.se"
PAGE_SIZE = 200  # API maximum
MAX_HITS = 10_000  # API refuses pages beyond this offset
LIST_FROM = dt.date(1600, 1, 1)  # before the oldest act (1734 års lag)
USER_AGENT = "fetch_sfs_laws/1.0 (+personal research)"

GRUNDLAGAR = {"1974:152", "1949:105", "1991:1469", "1810:0926"}
RIKSDAGSORDNINGEN = "2014:801"

# Part of title before the first "(" must be "Lag" or end in "lag"/"balk".
LAW_TITLE = re.compile(r"^[^(]*?(?:lag|balk)\s*\(", re.IGNORECASE)
REPEALED = re.compile(r"^Upphävd:[ \t]*(\d{4}-\d{2}-\d{2})", re.MULTILINE)
NOT_YET_IN_FORCE = re.compile(r"/Träder i kraft I:\s*(\d{4}-\d{2}-\d{2})/")


class RateLimiter:
    def __init__(self, per_second: float):
        self.interval = 1.0 / per_second
        self.lock = threading.Lock()
        self.next_at = 0.0

    def wait(self) -> None:
        with self.lock:
            now = time.monotonic()
            delay = self.next_at - now
            self.next_at = max(now, self.next_at) + self.interval
        if delay > 0:
            time.sleep(delay)


def http_get(url: str, limiter: RateLimiter, retries: int = 4) -> bytes:
    if url.startswith("//"):
        url = "https:" + url
    url = url.replace("http://", "https://", 1)
    for attempt in range(retries):
        limiter.wait()
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=120) as resp:
                return resp.read()
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if attempt == retries - 1:
                raise
            backoff = 2 ** attempt * 3
            print(f"  retry {attempt + 1} for {url} in {backoff}s ({e})", file=sys.stderr)
            time.sleep(backoff)
    raise RuntimeError("unreachable")


def list_page(start: dt.date, end: dt.date, page: int, limiter: RateLimiter) -> dict:
    url = (f"{API}/dokumentlista/?doktyp=sfs&utformat=json"
           f"&from={start.isoformat()}&tom={end.isoformat()}"
           f"&sz={PAGE_SIZE}&p={page}&sort=datum&sortorder=asc")
    return json.loads(http_get(url, limiter))["dokumentlista"]


def list_sfs_range(start: dt.date, end: dt.date, limiter: RateLimiter):
    # The API rejects (HTTP 400) any page past the first MAX_HITS results,
    # so bisect the date range until each window fits.
    data = list_page(start, end, 1, limiter)
    hits = int(data.get("@traffar", 0))
    if hits > MAX_HITS and start < end:
        mid = start + (end - start) // 2
        yield from list_sfs_range(start, mid, limiter)
        yield from list_sfs_range(mid + dt.timedelta(days=1), end, limiter)
        return
    total_pages = int(data.get("@sidor", 1))
    page = 1
    while True:
        docs = data.get("dokument") or []
        print(f"list {start}..{end} page {page}/{total_pages} ({len(docs)} docs)",
              file=sys.stderr)
        yield from docs
        if page >= total_pages or not docs:
            return
        page += 1
        data = list_page(start, end, page, limiter)


def list_all_sfs(limiter: RateLimiter):
    yield from list_sfs_range(LIST_FROM, dt.date.today() + dt.timedelta(days=3650), limiter)


def is_candidate(doc: dict, include_ro: bool) -> bool:
    nr = (doc.get("beteckning") or "").strip()
    if nr in GRUNDLAGAR or (include_ro and nr == RIKSDAGSORDNINGEN):
        return True
    return bool(LAW_TITLE.match(doc.get("titel") or ""))


def header_of(text: str) -> str:
    # Metadata header ends at "Källa:" line; include a little body for the
    # whole-act "Träder i kraft" marker, which sits right at the start.
    idx = text.find("Källa:")
    return text[: idx + 600] if idx >= 0 else text[:1500]


def safe_name(nr: str) -> str:
    return re.sub(r"[^0-9A-Za-z:_-]+", "_", nr).replace(":", "-")


def process(doc: dict, args, limiter: RateLimiter, today: str) -> dict | None:
    nr = doc["beteckning"].strip()
    dok_id = doc["dok_id"].strip()
    out_file = args.out / f"{safe_name(dok_id)}.{'html' if args.format == 'html' else 'txt'}"
    # Old acts have spaces in dok_id ("sfs-1904-48 s.1"); percent-encode.
    quoted = urllib.parse.quote(dok_id)
    text_url = f"{API}/dokument/{quoted}.text"

    # Always need the text header to decide in-force status.
    text = http_get(text_url, limiter).decode("utf-8", errors="replace")
    head = header_of(text)

    m = REPEALED.search(head)
    if m and m.group(1) <= today:
        return None
    m2 = NOT_YET_IN_FORCE.search(head)
    not_in_force = bool(m2 and m2.group(1) > today)
    if not_in_force and not args.include_not_in_force:
        return None

    if args.force or not out_file.exists():
        if args.format == "html":
            html_url = f"{API}/dokument/{quoted}.html"
            out_file.write_bytes(http_get(html_url, limiter))
        else:
            out_file.write_text(text, encoding="utf-8")

    andrad = re.search(r"^Ändrad:[ \t]*(.*)$", head, re.MULTILINE)
    return {
        "sfs_nr": nr,
        "titel": " ".join((doc.get("titel") or "").split()).rstrip(";"),
        "dok_id": dok_id,
        "organ": doc.get("organ"),
        "andrad_tom": andrad.group(1).strip() if andrad else None,
        "upphavd_framtida": m.group(1) if m else None,
        "ikraft": m2.group(1) if m2 else None,
        "fil": out_file.name,
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", type=Path, default=Path("data/sfs-lagar"))
    p.add_argument("--format", choices=["text", "html"], default="text")
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--rate", type=float, default=4.0, help="max requests/second")
    p.add_argument("--limit", type=int, default=0, help="stop after N candidates (testing)")
    p.add_argument("--force", action="store_true", help="refetch existing files")
    p.add_argument("--include-not-in-force", action="store_true")
    p.add_argument("--include-riksdagsordning", action="store_true")
    args = p.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    limiter = RateLimiter(args.rate)
    today = dt.date.today().isoformat()

    seen: set[str] = set()
    candidates = []
    for doc in list_all_sfs(limiter):
        # Some old acts are listed twice ("1904:48 s.1" and "1904:48s1").
        key = re.sub(r"[\s.]", "", doc.get("beteckning") or "")
        if key and key not in seen and is_candidate(doc, args.include_riksdagsordning):
            seen.add(key)
            candidates.append(doc)
            if args.limit and len(candidates) >= args.limit:
                break
    print(f"{len(candidates)} law candidates; checking in-force status...", file=sys.stderr)

    kept, failed = [], []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(process, d, args, limiter, today): d for d in candidates}
        for i, fut in enumerate(as_completed(futures), 1):
            d = futures[fut]
            try:
                rec = fut.result()
                if rec:
                    kept.append(rec)
            except Exception as e:  # noqa: BLE001
                failed.append(d.get("beteckning"))
                print(f"  FAILED {d.get('beteckning')}: {e}", file=sys.stderr)
            if i % 100 == 0:
                print(f"  {i}/{len(candidates)} checked, {len(kept)} in force", file=sys.stderr)

    kept.sort(key=lambda r: r["sfs_nr"])
    with (args.out / "index.jsonl").open("w", encoding="utf-8") as f:
        for r in kept:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"done: {len(kept)} laws in force -> {args.out}, {len(failed)} failed", file=sys.stderr)
    if failed:
        print("failed: " + ", ".join(map(str, failed)), file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
