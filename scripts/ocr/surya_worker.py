#!/usr/bin/env python3
"""Read pages with the local Surya runtime: one request line in, one result line out.

This is the child half of `infoscry.ocr.SuryaOcr`. The parent owns the lifecycle — it starts this process,
writes one page per line on stdin and reads one answer per line on stdout — and this file owns the model:

- **The runtime is loaded once per process.** `SuryaInferenceManager` and its `RecognitionPredictor` are
  built on the first page and reused for every page after it, so `llama-server` and its weights are started
  once for a whole attempt rather than once per page. That is the reason this is a worker at all: the
  runtime costs seconds of imports and a model load before the first page, and about three seconds per page
  after it.
- **One page is one full-page model call.** The predictor is asked for the full-page path, which is the
  accurate one and the one the measured numbers in `docs/technical-reference.md` describe. A block the
  model cannot read is dropped, and a page whose blocks all come back empty is an *empty* reading rather
  than a successful one: nothing here decides that paper is blank — the parent holds the raster, and only
  it may ask that question.
- **Nothing is unbounded.** A request line, a result line, a page's text, its block count and its raster
  each have a bound, and crossing one is reported rather than silently trimmed: a truncated reading is not
  evidence of what a page says.
- **stdin closing is the signal to leave.** The parent is gone, so the pages are over; this process exits
  through its normal path, which is what lets the runtime's own cleanup kill the `llama-server` it spawned.
  A `SIGTERM` is turned into the same exit, because a process that is terminated outright runs no cleanup
  and would leave the server (and a gigabyte of weights) behind.

The protocol is versioned JSON, and it is the parent's `infoscry.ocr.SuryaOcr` that defines it: a request is
`{"protocol": 1, "type": "page", "unitId": ..., "ordinal": ..., "page": ..., "imagePath": ...}` and a result
is either a reading (`status` `read` or `empty`, with `text`, `blocks` and the `model` identity) or an error
(`status` `error` with a `code`: `NEEDS_LLAMA_CPP`, `NEEDS_SURYA_MODEL`, `NEEDS_SURYA`,
`SURYA_START_FAILED`, `UNREADABLE_IMAGE`, `PAGE_TOO_LARGE`, `MALFORMED_REQUEST`, `INFERENCE_FAILED`).

Started with `--identity` it answers one such line about **itself** and exits — the runtime's version, its
backend, its weights and the `llama-server` build it would spawn — without starting a model or a server.
The parent asks that before it reads a page, because whether a committed page may be reused is decided by
what the reading was made with.
"""

from __future__ import annotations

import json
import math
import os
import re
import shutil
import signal
import subprocess
import sys
from html import unescape

PROTOCOL_VERSION = 1
PAGE_TYPE = "page"
IDENTITY_TYPE = "identity"

# The flag that asks for that identity instead of a page.
IDENTITY_FLAG = "--identity"

# How long the server binary is given to answer `--version`: it answers instantly, and a binary that never
# answers must not hold the probe open.
LLAMA_SERVER_TIMEOUT_SECONDS = 10

STATUS_READ = "read"
STATUS_EMPTY = "empty"
STATUS_ERROR = "error"
STATUS_OK = "ok"

NEEDS_SURYA = "NEEDS_SURYA"
NEEDS_LLAMA_CPP = "NEEDS_LLAMA_CPP"
NEEDS_SURYA_MODEL = "NEEDS_SURYA_MODEL"
SURYA_START_FAILED = "SURYA_START_FAILED"
MALFORMED_REQUEST = "MALFORMED_REQUEST"
UNREADABLE_IMAGE = "UNREADABLE_IMAGE"
PAGE_TOO_LARGE = "PAGE_TOO_LARGE"
OUTPUT_TOO_LARGE = "OUTPUT_TOO_LARGE"
INFERENCE_FAILED = "INFERENCE_FAILED"

# How much of one request or result line is held. The parent's own bound is the same number for results,
# so a line this worker refuses is a line the parent would have refused as well.
MAX_REQUEST_BYTES = 64 * 1024
MAX_RESULT_BYTES = 4 * 1024 * 1024

# What one page's reading may hold. Crossing either bound is reported rather than trimmed.
MAX_PAGE_TEXT_CHARS = 200_000
MAX_BLOCKS = 2_000

# The raster bound the pipeline renders and reduces a page to; a page past it is not one this reads.
MAX_PAGE_PIXELS = 16_777_216

NEWLINE = b"\n"


class LineTooLong(Exception):
    """A line longer than the bound its reader holds."""


class PageTooLarge(Exception):
    """A page whose raster is past the bound a page image is held to."""


class ResultTooLarge(Exception):
    """A result past the bound one result line, or one reading, is held to."""


class ProtocolReader:
    """Lines from a byte stream, holding at most `bound` bytes of one."""

    def __init__(self, stream, bound: int):
        self._stream = stream
        self._bound = bound

    def read_line(self):
        """The next line as text, or `None` at the end of the stream. Raises `LineTooLong` past the bound."""
        chunk = self._stream.readline(self._bound + 1)
        if chunk == b"":
            return None
        if len(chunk) > self._bound and not chunk.endswith(NEWLINE):
            # The rest of the line is read and dropped rather than refused: the child on the other end of
            # this pipe is blocked until it is drained, and a blocked child can only be killed.
            while True:
                rest = self._stream.readline(self._bound + 1)
                if rest == b"" or rest.endswith(NEWLINE):
                    break
            raise LineTooLong(f"more than {self._bound} bytes on one line")
        return chunk.decode("utf-8", errors="replace")


class Engine:
    """The inference runtime this process owns: started once, and never started twice."""

    def __init__(self):
        self._manager = None
        self._predictor = None
        self._model = None
        self._failure = None

    def predictor(self):
        """The predictor, starting the runtime on first use and reporting the same failure afterwards."""
        if self._predictor is not None:
            return self._predictor
        if self._failure is not None:
            raise self._failure
        try:
            # Imported here, and unresolvable by a checker outside this worker's own interpreter: Surya is
            # installed in the pinned virtualenv this file is *run* with, never in the tree, and this module
            # has to start where it is absent so that a missing runtime becomes `NEEDS_SURYA` rather than a
            # crash before the first request.
            from surya.inference import get_default_manager  # pyright: ignore[reportMissingImports]
            from surya.recognition import RecognitionPredictor  # pyright: ignore[reportMissingImports]

            manager = get_default_manager()
            # Started explicitly rather than on the first request, so a missing `llama-server`, a failed
            # download or an unhealthy server is the failure of *this* step and carries its own code.
            manager.start()
            self._manager = manager
            self._model = describe_model(manager)
            self._predictor = RecognitionPredictor(manager)
        except Exception as failure:
            self._failure = failure
            raise
        return self._predictor

    @property
    def model(self):
        """The identity of the weights this worker reads with, as one line a fingerprint can carry."""
        return self._model

    def stop(self):
        """Ends what this worker started, on the way out.

        Surya's backend registers its own cleanup when *it* spawns `llama-server`, and that cleanup is what
        kills the server; this call ends the runtime's state as well, and is deliberately forgiving because
        a failure on the way out must not stop this process from exiting.
        """
        try:
            if self._manager is not None:
                self._manager.stop()
        except Exception:
            pass


def describe_model(manager) -> str:
    """What this worker reads with: the version, the backend, and the cached weights' revision and size.

    Read from the manager that is already running, so it is the identity of the reading a page carries.
    """
    return " ".join(model_parts(manager.method))


def runtime_identity() -> str:
    """What this interpreter's runtime is, asked *without* starting a model, a server or a page.

    This is the answer to `--identity`, and the parent asks it before it reads anything: the identity
    reaches the attempt's fingerprint, which is what decides whether a page an earlier attempt committed
    may be reused. Starting the model to learn it would make the question cost what the reading costs, so
    only things that can be answered from the installed runtime and its cache are in it. The
    `llama-server` build is one of them and is read from the binary's own `--version`, because Homebrew's
    `llama.cpp` floats: the same weights served by another build are another reader.
    """
    return " ".join([*model_parts(surya_backend()), f"llama-server {llama_server_build()}"])


def model_parts(backend: str) -> list:
    """The version, the backend and the cached weights' revision and size, as one identity's words.

    The revision and the size are here because two readings are only comparable when they come from the
    same weights: a repository name alone would call a re-downloaded model the same model, and a page
    committed under one set of weights would then be reused under another.
    """
    settings = surya_settings()

    parts = [
        f"surya-ocr {package_version('surya-ocr')}",
        f"backend {backend}",
        f"model {settings.SURYA_MODEL_CHECKPOINT}",
    ]
    for name in (settings.SURYA_GGUF_MODEL_FILE, settings.SURYA_GGUF_MMPROJ_FILE):
        parts.append(f"{name} {cached_artifact(settings.SURYA_GGUF_REPO, name)}")
    return parts


def surya_backend() -> str:
    """The backend this runtime would read with, asked of the runtime rather than guessed.

    Constructing the manager picks the backend (`llamacpp`, or `vllm` where an NVIDIA GPU is), and a
    manager that was never started has started nothing: `SuryaInferenceManager` is lazy, so this reads the
    choice without spawning the server the choice is about.
    """
    try:
        from surya.inference import get_default_manager  # pyright: ignore[reportMissingImports]

        return get_default_manager().method
    except Exception:
        return "unresolved"


def llama_server_build() -> str:
    """The build of the `llama-server` this runtime would spawn, or why it could not be read.

    The binary is resolved the way the runtime itself resolves it — Surya's own setting first, then `PATH`
    — so what is named here is the build a page would really be read by. `llama.cpp` from Homebrew is not
    a pinned version: it moves with each upgrade, which is exactly why the build belongs in a reading's
    identity rather than beside the weights'.
    """
    try:
        binary = surya_settings().LLAMA_CPP_BINARY
        path = binary if binary and os.path.isfile(binary) else shutil.which(binary or "llama-server")
        if not path:
            return "unresolved"
        # llama.cpp writes its version to stderr, under a line about initializing, so the answer is picked
        # by what it says rather than by where it came out.
        outcome = subprocess.run(
            [path, "--version"],
            capture_output=True,
            text=True,
            timeout=LLAMA_SERVER_TIMEOUT_SECONDS,
        )
    except Exception:
        return "unresolved"
    for line in (outcome.stdout + "\n" + outcome.stderr).splitlines():
        if "version:" in line:
            return line.strip()
    return "unresolved"


def surya_settings():
    """Surya's own settings, loaded in whatever interpreter is running this worker.

    The import is dynamic because this file has to be *startable* where Surya is not installed: that is what
    turns a missing runtime into the parent's `NEEDS_SURYA` instead of a crash before the first request. The
    values are read from the package rather than repeated here, so a pinned runtime whose weights changed
    could not be described by this file as the weights it had before.
    """
    import importlib

    return importlib.import_module("surya.settings").settings


def package_version(name: str) -> str:
    """One installed package's version, or `unknown` when it cannot be asked."""
    try:
        from importlib.metadata import version

        return version(name)
    except Exception:
        return "unknown"


def cached_artifact(repository: str, name: str) -> str:
    """Which revision of a file the cache holds and how big it is, without fetching anything.

    Loaded dynamically for the same reason as the runtime's settings: a worker whose cache client is not
    installed has to be able to answer, and "the weights cannot be resolved from here" is an answer.
    """
    import importlib

    try:
        hub = importlib.import_module("huggingface_hub")
        path = hub.try_to_load_from_cache(repository, name)
    except Exception:
        return "unresolved"
    if not isinstance(path, str) or not os.path.isfile(path):
        return "uncached"
    revision = os.path.basename(os.path.dirname(path))
    return f"{revision}:{os.path.getsize(path)}"


def read_page(request: dict, engine: Engine) -> dict:
    """One page's reading, or the error result that says why there is none.

    The image path is the parent's own managed path: it derived it from the page image whose root it
    validated, so what is checked here is that it is an absolute path to a file this process can read — a
    request is a wire format, and a worker does not take a caller's word for what a path is.
    """
    image_path = request.get("imagePath")
    if not isinstance(image_path, str) or not image_path or not os.path.isabs(image_path):
        return error(request, UNREADABLE_IMAGE, "the page's image path is not an absolute path")
    if not os.path.isfile(image_path):
        return error(request, UNREADABLE_IMAGE, "the page's image is not a file")
    try:
        image = open_image(image_path)
    except PageTooLarge:
        return error(
            request,
            PAGE_TOO_LARGE,
            f"the page's raster is past the {MAX_PAGE_PIXELS}-pixel bound a page image is held to",
        )
    except ImportError:
        # The interpreter cannot import the runtime's own packages, so this is not a page that could not be
        # decoded: it is the machine's runtime that has to be installed, which is the parent's answer to give.
        return error(request, NEEDS_SURYA, "the runtime's packages are not installed in this interpreter")
    except Exception:
        return error(request, UNREADABLE_IMAGE, "the page's image could not be decoded")

    try:
        page = engine.predictor()([image], full_page=True)[0]
    except Exception as failure:
        code = runtime_failure_code(failure)
        if code is not None:
            return error(request, code, "the runtime could not read a page")
        return error(request, INFERENCE_FAILED, "the model did not answer for this page")

    try:
        blocks = collect_blocks(page, engine)
    except ResultTooLarge:
        return error(
            request,
            OUTPUT_TOO_LARGE,
            "the reading for this page is past the bound one result line holds",
        )
    text = "\n".join(block["text"] for block in blocks)
    return {
        "protocol": PROTOCOL_VERSION,
        "type": PAGE_TYPE,
        "unitId": request.get("unitId"),
        "ordinal": request.get("ordinal"),
        "page": request.get("page"),
        # A page whose blocks all came back empty is not a page that was read successfully: it is an empty
        # reading, and whether the paper is blank is the parent's question, asked of the raster.
        "status": STATUS_READ if text.strip() else STATUS_EMPTY,
        "text": text,
        "model": engine.model,
        "blocks": blocks,
    }


def collect_blocks(page, engine):
    """One page's blocks as boxes, refusing as soon as the reading is past the bound it is held to.

    The bounds are spent while the blocks are collected rather than measured once the answer exists: a
    page with a hundred thousand blocks, and the text of all of them, is already the allocation the bound
    exists to prevent, and asking how long the reading was after building it bounds nothing. A block with
    no text a match could be shown at is dropped before it counts, because it is not part of the reading.

    Raises `ResultTooLarge` when the page's text or its block count crosses its bound.
    """
    blocks = []
    text_chars = 0
    for item in page.blocks:
        block = block_result(item, engine)
        if block is None:
            continue
        # The page's text is these blocks joined by newlines, so the separator the join adds counts as well.
        text_chars += len(block["text"]) + (1 if blocks else 0)
        if len(blocks) >= MAX_BLOCKS or text_chars > MAX_PAGE_TEXT_CHARS:
            raise ResultTooLarge(
                f"more than {MAX_BLOCKS} blocks or {MAX_PAGE_TEXT_CHARS} characters of text"
            )
        blocks.append(block)
    return blocks


def open_image(path: str):
    """One page's raster, decoded, inside the bound a page image is held to.

    The declared size is read from the header before anything is decoded, because that size is a number a
    small file chooses: decoding first and asking afterwards is how one page exhausts this process.
    """
    from PIL import Image

    image = Image.open(path)
    width, height = image.size
    if width <= 0 or height <= 0 or width * height > MAX_PAGE_PIXELS:
        raise PageTooLarge(f"{width}x{height}")
    image.load()
    if image.mode not in ("RGB", "L"):
        image = image.convert("RGB")
    return image


def block_result(block, engine: Engine):
    """One of the model's blocks as a box, or `None` when it carries no text a match could be shown at.

    Surya answers a page as blocks, not as words: one box per block, and one confidence for the page's
    single call — the mean token probability of that call's answer, which the library reports on each of
    its blocks. It is the *model's* confidence and not this project's.
    """
    text = html_to_text(block.html)
    if not text.strip():
        return None
    xs = [point[0] for point in block.polygon]
    ys = [point[1] for point in block.polygon]
    return {
        "text": text,
        "bbox": [
            int(round(min(xs))),
            int(round(min(ys))),
            int(round(max(xs))),
            int(round(max(ys))),
        ],
        "confidence": measured_confidence(block, engine),
        "label": block.label,
        "readingOrder": block.reading_order,
    }


def measured_confidence(block, engine: Engine):
    """A block's confidence when the runtime measured one, and `None` when it only substituted a value.

    Without logprobs the library reports `1.0` for every block — a substitution rather than a measurement —
    so a run with them disabled reports no confidence at all. Certainty that was never measured is worse
    than an absent number: absence is not a confidence of zero, and it is not a confidence of one either.
    """
    if not logprobs_requested():
        return None
    confidence = block.confidence
    if not isinstance(confidence, (int, float)) or not math.isfinite(confidence):
        return None
    if not 0.0 <= confidence <= 1.0:
        return None
    return float(confidence)


def logprobs_requested() -> bool:
    """Whether the runtime is being asked for the token probabilities a confidence comes from."""
    try:
        return bool(surya_settings().SURYA_INFERENCE_LOGPROBS)
    except Exception:
        return False


HTML_BREAK_BEFORE = re.compile(r"(?i)<(?:br|hr)\s*/?>")
HTML_BREAK_AFTER = re.compile(r"(?i)</(?:p|div|li|tr|h[1-6]|pre|table|caption|thead|tbody)>")
HTML_TAG = re.compile(r"<[^>]*>")


def html_to_text(html: str) -> str:
    """A block's HTML as the lines of text it says.

    Surya answers with HTML because a block can be a table, a heading or a formula. What a page's reading is
    for — searching it, citing it, comparing it — needs the text and where it was, so block-level tags end
    a line and the rest of the markup is dropped, which is the same reading order the model wrote.
    """
    if not html:
        return ""
    text = HTML_BREAK_BEFORE.sub("\n", html)
    text = HTML_BREAK_AFTER.sub("\n", text)
    text = unescape(HTML_TAG.sub("", text))
    lines = [line.strip() for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


def runtime_failure_code(failure: Exception):
    """Which piece of the runtime a failure is about, or `None` when it is this page's own failure.

    The classes are asked rather than the text where a class exists — Surya's own `SpawnError` for a server
    it could not start, and the Hub's own errors for weights it could not fetch — because a message is prose
    that changes while a type does not. A name missing from the runtime's environment is an `ImportError`,
    and the two failures a fresh machine meets are a server that is not installed and weights that could not
    be fetched.
    """
    if isinstance(failure, ImportError):
        return NEEDS_SURYA
    if _is_spawn_failure(failure):
        return NEEDS_LLAMA_CPP if "llama-server binary not found" in str(failure) else SURYA_START_FAILED
    if type(failure).__module__.split(".")[0] in FETCHING_MODULES:
        return NEEDS_SURYA_MODEL
    return None


# The modules whose failures mean the weights could not be obtained: the Hub's own client, and the HTTP
# stacks underneath it. An inference failure comes from `openai`, which is deliberately not here: a server
# that answered badly is a page's failure, not a machine that is missing its model.
FETCHING_MODULES = frozenset(
    {"huggingface_hub", "huggingface", "requests", "httpx", "httpcore", "urllib3", "socket", "ssl"}
)


def _is_spawn_failure(failure: Exception) -> bool:
    """Whether a failure is Surya's own server-spawning failure.

    The class is recognized by its own name rather than by importing it: this runs on the path where the
    runtime has just failed, and a diagnostic that itself needs the broken runtime imported could not say
    what is wrong with it.
    """
    return type(failure).__name__ == "SpawnError"


def error(request, code: str, message: str) -> dict:
    """One error result, carrying the identity of the request it answers when there was one."""
    result = {
        "protocol": PROTOCOL_VERSION,
        "type": PAGE_TYPE,
        "status": STATUS_ERROR,
        "code": code,
        "message": message,
    }
    if isinstance(request, dict):
        for key in ("unitId", "ordinal", "page"):
            if key in request:
                result[key] = request[key]
    return result


def answer(line: str, engine: Engine) -> dict:
    """One request line's answer, whatever the line turns out to be."""
    try:
        request = json.loads(line)
    except Exception:
        return error(None, MALFORMED_REQUEST, "the request is not JSON")
    if not isinstance(request, dict):
        return error(None, MALFORMED_REQUEST, "the request is not a JSON object")
    if request.get("protocol") != PROTOCOL_VERSION:
        return error(request, MALFORMED_REQUEST, "the request is not this protocol's version")
    if request.get("type") != PAGE_TYPE:
        return error(request, MALFORMED_REQUEST, "the request is not a page")
    return read_page(request, engine)


def write_result(result: dict) -> None:
    """One result line on stdout, or the bound's own answer when the result does not fit."""
    line = encode(result)
    if line is None:
        line = encode(error(result, OUTPUT_TOO_LARGE, "the reading is longer than one result line holds"))
    if line is None:
        line = json.dumps(
            {"protocol": PROTOCOL_VERSION, "type": PAGE_TYPE, "status": STATUS_ERROR, "code": OUTPUT_TOO_LARGE},
            separators=(",", ":"),
        )
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


def encode(result: dict):
    """One result as a line, or `None` when it reaches the bound a result line is held to."""
    writer = BoundedLine(MAX_RESULT_BYTES)
    try:
        json.dump(result, writer, ensure_ascii=False, separators=(",", ":"))
    except ResultTooLarge:
        return None
    return writer.line()


class BoundedLine:
    """A JSON payload that refuses to grow past the bound a result line is held to.

    The bound is spent as the payload is *written*, not measured once it exists: `json.dumps` over a page
    whose reading is enormous builds the whole line before anyone can ask how long it was, which is the
    allocation the bound exists to prevent. What is bounded is the encoded bytes, because that is what the
    parent's own reader holds; a chunk is refused before it is encoded when it cannot fit even at one byte
    per character, and counted exactly when it can.
    """

    def __init__(self, bound: int):
        self._bound = bound
        self._written = 0
        self._parts: list[str] = []

    def write(self, chunk: str) -> int:
        over = ResultTooLarge(f"more than {self._bound} bytes")
        if self._written + len(chunk) > self._bound:
            raise over
        self._written += len(chunk.encode("utf-8"))
        if self._written > self._bound:
            raise over
        self._parts.append(chunk)
        return len(chunk)

    def line(self) -> str:
        """The payload as one line, which is only asked for once it is known to fit."""
        return "".join(self._parts)


def install_signal_handlers() -> None:
    """Turn `SIGTERM` and `SIGINT` into an exit, so the runtime this worker spawned ends with it.

    A process that is terminated outright runs no `atexit` handlers, and Surya's backend kills the
    `llama-server` it spawned from one — so the signal is raised as an exit instead of being obeyed as a
    death. The parent also stops this worker's whole process tree, which is the second half of the same
    guarantee.
    """

    def stop(_signum, _frame):
        raise SystemExit(0)

    for number in (signal.SIGTERM, signal.SIGINT):
        signal.signal(number, stop)


def write_identity() -> None:
    """One identity line on stdout, and nothing else done: no model, no server, no page.

    A runtime that cannot describe itself is not a crash: the parent asked what the runtime is, and "it
    cannot be read from here" is an answer that becomes an *absent* identity in the attempt rather than a
    failed one. The reading itself is what reports a runtime that has to be installed.
    """
    try:
        result = {
            "protocol": PROTOCOL_VERSION,
            "type": IDENTITY_TYPE,
            "status": STATUS_OK,
            "identity": runtime_identity(),
        }
    except Exception:
        result = {
            "protocol": PROTOCOL_VERSION,
            "type": IDENTITY_TYPE,
            "status": STATUS_ERROR,
            "code": NEEDS_SURYA,
        }
    write_result(result)


def main() -> int:
    install_signal_handlers()
    if IDENTITY_FLAG in sys.argv[1:]:
        # The probe the parent makes once per attempt: it starts nothing, so it costs a Python start and
        # the runtime's own metadata rather than a model load.
        write_identity()
        return 0
    reader = ProtocolReader(sys.stdin.buffer, MAX_REQUEST_BYTES)
    engine = Engine()
    try:
        while True:
            try:
                line = reader.read_line()
            except LineTooLong:
                write_result(error(None, MALFORMED_REQUEST, "the request line is longer than this protocol holds"))
                continue
            if line is None:
                # stdin is closed: the parent that owned these pages is gone, and so are the pages.
                break
            write_result(answer(line, engine))
    finally:
        engine.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
