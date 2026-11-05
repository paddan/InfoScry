#!/usr/bin/env python3
"""Turn data/sfs-lagar/*.txt into chunks.tsv (id, document, text) that both search engines index.

Chunks are paragraphs grouped up to MAX_CHARS characters, roughly the size of InfoScry's 512-token passages.
Stdlib only. Usage: prepare.py <corpus-dir> <out.tsv>
"""
import pathlib
import re
import sys

MAX_CHARS = 1500


def paragraphs(text: str):
    # Skip the metadata header: everything up to the line starting with "Källa:".
    marker = text.find("\nKälla:")
    if marker >= 0:
        text = text[text.find("\n", marker + 1) + 1:]
    for block in re.split(r"\n\s*\n", text):
        block = " ".join(block.split())
        if block:
            yield block


def pieces(block: str):
    while len(block) > MAX_CHARS:
        cut = block.rfind(" ", 0, MAX_CHARS)
        cut = cut if cut > 0 else MAX_CHARS
        yield block[:cut]
        block = block[cut:].lstrip()
    if block:
        yield block


def chunks(text: str):
    current = ""
    for block in paragraphs(text):
        for piece in pieces(block):
            if current and len(current) + 1 + len(piece) > MAX_CHARS:
                yield current
                current = piece
            else:
                current = f"{current} {piece}" if current else piece
    if current:
        yield current


def main() -> None:
    corpus, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    next_id = 1
    with out.open("w", encoding="utf-8") as sink:
        for path in sorted(corpus.glob("*.txt")):
            for chunk in chunks(path.read_text(encoding="utf-8")):
                sink.write(f"{next_id}\t{path.stem}\t{chunk.replace(chr(9), ' ')}\n")
                next_id += 1
    print(f"{next_id - 1} chunks", file=sys.stderr)


if __name__ == "__main__":
    main()
