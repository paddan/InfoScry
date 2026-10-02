#!/usr/bin/env python3
"""The bounds one page's reading is held to, checked without the runtime.

This is the one part of `surya_worker.py` a fake child cannot exercise — the Kotlin tests drive the
protocol with a stand-in worker, and the real runtime test reads a page far below every bound — so what is
checked here is the part whose failure would be silent: a page's text and block count are refused *while
they are collected* rather than measured after the whole answer exists, and a result line is refused as it
is written rather than after it was built.

It runs anywhere: `python3 scripts/ocr/test_surya_worker.py`. Nothing imports `surya`, `PIL` or the
runtime to import the module it tests, because the module has to be startable where those are absent; the
one case that measures the raster bound against the decoder itself says so and is skipped where there is no
decoder, rather than failing for the environment's reason.
"""

from __future__ import annotations

import io
import json
import struct
import sys
import tempfile
import zlib
from pathlib import Path

import surya_worker as worker

# The real decoder, kept aside: one case below patches the module's own `open_image` to stand in for a
# runtime, and the case that measures the raster bound has to run against the decoder itself.
REAL_OPEN_IMAGE = worker.open_image


def image_library_available() -> bool:
    """Whether the environment running this file has the decoder the raster bound is about."""
    try:
        import PIL  # noqa: F401
    except Exception:
        return False
    return True


def over_bound_png() -> bytes:
    """A PNG whose *header* declares a raster past the bound, and which holds no pixels at all.

    Nothing is decoded to make this file, and nothing needs to be: the bound is about the size a header
    declares, so a header is the whole of what the decoder has to read before the worker refuses the page.
    """
    side = int(worker.MAX_PAGE_PIXELS**0.5) + 1

    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", side, side, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IEND", b"")


class FakeStdin:
    """A stand-in for the process the parent writes request lines to."""

    def __init__(self, payload: bytes):
        self.buffer = io.BytesIO(payload)


def drive_main(payload: bytes) -> list:
    """One worker process's answers to [payload], read the way the parent reads them: JSON lines."""
    stdin, stdout = sys.stdin, sys.stdout
    sys.stdin = FakeStdin(payload)
    sys.stdout = io.StringIO()
    try:
        assert worker.main() == 0, "the worker did not exit cleanly on the lines it was handed"
        written = sys.stdout.getvalue()
    finally:
        sys.stdin, sys.stdout = stdin, stdout
    return [json.loads(line) for line in written.splitlines() if line.strip()]


class FakeBlock:
    """One of the model's blocks, as the attributes this worker reads from it."""

    def __init__(self, text: str, polygon=None, confidence: float | None = None):
        self.html = f"<p>{text}</p>"
        self.polygon = polygon if polygon is not None else [(0, 0), (10, 0), (10, 10), (0, 10)]
        self.confidence = confidence
        self.label = "Text"
        self.reading_order = 0


class FakePage:
    def __init__(self, blocks):
        self.blocks = blocks


def blocks_of(count: int, characters: int = 1):
    return [FakeBlock("x" * characters) for _ in range(count)]


def refused(page) -> bool:
    try:
        worker.collect_blocks(page, None)
    except worker.ResultTooLarge:
        return True
    return False


class FakeEngine(worker.Engine):
    """The runtime this test stands in for: one page, no model, no server, no imports."""

    def __init__(self, page):
        self._page = page

    def predictor(self):
        return lambda images, full_page=False: [self._page]

    @property
    def model(self) -> str:
        return "a stand-in"


def main() -> int:
    # A reading inside the bound is collected, in the order the model wrote it.
    blocks = worker.collect_blocks(FakePage(blocks_of(3, 4)), None)
    assert [block["text"] for block in blocks] == ["xxxx"] * 3, blocks
    assert blocks[0]["bbox"] == [0, 0, 10, 10], blocks[0]

    # A block with no text is not part of the reading, and does not count towards either bound.
    mixed = worker.collect_blocks(FakePage([FakeBlock("  "), FakeBlock("text"), FakeBlock("")]), None)
    assert [block["text"] for block in mixed] == ["text"], mixed

    # The block count is refused as the block past it arrives, not after the page was collected.
    assert refused(FakePage(blocks_of(worker.MAX_BLOCKS + 1))), "an over-long block count was collected"
    assert not refused(FakePage(blocks_of(worker.MAX_BLOCKS))), "a page at the block bound was refused"

    # The text bound is about the page's text, which is these blocks joined by newlines: one block at the
    # bound fits, one character more does not, and two blocks that would fit without the separator do not.
    assert not refused(FakePage(blocks_of(1, worker.MAX_PAGE_TEXT_CHARS)))
    assert refused(FakePage(blocks_of(1, worker.MAX_PAGE_TEXT_CHARS + 1))), "an over-long text was collected"
    half = worker.MAX_PAGE_TEXT_CHARS // 2
    assert refused(FakePage(blocks_of(2, half))), "the newline the blocks are joined with was not counted"
    assert not refused(FakePage(blocks_of(2, half - 1)))

    # A result line inside the bound survives the round trip, whole — checked on a result this test can
    # build without a model, because what is under test here is the line's bound and not the runtime.
    request = {"protocol": worker.PROTOCOL_VERSION, "type": worker.PAGE_TYPE, "unitId": "page:1"}
    answer = worker.error(request, worker.INFERENCE_FAILED, "the test reads no page")
    line = worker.encode(answer)
    assert line is not None and len(line.encode("utf-8")) <= worker.MAX_RESULT_BYTES
    assert json.loads(line)["unitId"] == "page:1"

    # And a payload past the bound is refused rather than built and then measured.
    too_wide = dict(answer, text="x" * worker.MAX_RESULT_BYTES)
    assert worker.encode(too_wide) is None, "a payload past the bound was serialised whole"

    # A page past a reading bound is *reported* and not raised out of the worker: the parent is owed a
    # result line for the request it sent, whatever happened to the page.
    worker.open_image = lambda path: object()
    with tempfile.TemporaryDirectory() as directory:
        image = Path(directory, "page.png")
        image.write_bytes(b"the stand-in decodes this")
        request = {"protocol": worker.PROTOCOL_VERSION, "type": worker.PAGE_TYPE, "unitId": "page:1"}
        over_long = worker.read_page(
            dict(request, imagePath=str(image)),
            FakeEngine(FakePage(blocks_of(1, worker.MAX_PAGE_TEXT_CHARS + 1))),
        )
        assert over_long["status"] == worker.STATUS_ERROR, over_long
        assert over_long["code"] == worker.OUTPUT_TOO_LARGE, over_long
        assert over_long["unitId"] == "page:1", over_long

    # A request line past the reader's bound is answered rather than crashed on — the parent wrote it, so the
    # parent is owed this protocol's own refusal — and the line after it is still served, which is what tells
    # a long line apart from a wedged worker. The over-long line here is *valid JSON* on purpose: if the reader
    # let it through, it would reach the page reader and come back as something else, so the code below is
    # this project's refusal of the line rather than the JSON parser's refusal of 64 KiB of padding.
    long_line = b'{"protocol":1,"type":"page","pad":"' + b"x" * worker.MAX_REQUEST_BYTES + b'"}'
    answers = drive_main(long_line + b"\n" + b"not json\n")
    assert [answer["code"] for answer in answers] == [worker.MALFORMED_REQUEST] * 2, answers
    assert all(answer["protocol"] == worker.PROTOCOL_VERSION for answer in answers), answers

    # The reader itself, because the two outcomes above cannot tell a refused line from a parsed one: a line
    # past the bound is refused *and* the rest of it is drained, so the next line the parent writes is still
    # read rather than being swallowed with the tail of the long one.
    reader = worker.ProtocolReader(
        io.BytesIO(b"x" * (worker.MAX_REQUEST_BYTES + 1) + b"\nsecond\n"),
        worker.MAX_REQUEST_BYTES,
    )
    try:
        reader.read_line()
        raise AssertionError("a line past the bound was read rather than refused")
    except worker.LineTooLong:
        pass
    assert reader.read_line() == "second\n", "the rest of an over-long line was not drained"

    # A page whose *header* declares a raster past the bound is rejected from the header, before anything is
    # decoded or handed to the model: the declared size is a number the file chooses, and a page past the
    # bound is not one this worker reads.
    worker.open_image = REAL_OPEN_IMAGE
    if image_library_available():
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory, "over-bound.png")
            image.write_bytes(over_bound_png())
            request = {"protocol": worker.PROTOCOL_VERSION, "type": worker.PAGE_TYPE, "unitId": "page:1"}
            too_large = worker.read_page(
                dict(request, imagePath=str(image)),
                FakeEngine(FakePage(blocks_of(1))),
            )
            assert too_large["status"] == worker.STATUS_ERROR, too_large
            assert too_large["code"] == worker.PAGE_TOO_LARGE, too_large
            assert too_large["unitId"] == "page:1", too_large
    else:
        print("over-bound raster: not checked, this environment has no image decoder")

    print("surya_worker bounds: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
