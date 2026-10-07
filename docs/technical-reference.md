# Technical reference

[Project overview](../README.md) · [Development](development.md) · [Implementation status](implementation-status.md)

## Architecture

One Kotlin/JVM process owns the services, loopback web server, persistent jobs,
managed library, database, search index and LLM adapters. HTTP routes and CLI
commands delegate to shared services. The frontend is built as static assets
and served by Ktor.

| Responsibility | Technology |
|---|---|
| Backend and CLI | Kotlin, Java 25, Ktor, Clikt |
| Web reader | SvelteKit, Svelte and TypeScript |
| Authoritative state and extracted text | SQLite |
| Rebuildable keyword and vector index | Apache Lucene |
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
| `infoscry.db` | Authoritative SQLite state |
| `infoscry.lock` | Process ownership lock |
| `runtime.json` | Running-server connection information |
| `library/` | Immutable managed copies and document artifacts |
| `index/` | Rebuildable Lucene index |
| `models/` | Installed embedding model and tokenizer |
| `logs/` | Structured application logs |
| `tmp/` | Temporary work and GPU profiling artifacts |

The SQLite schema is one baseline,
[001_baseline.sql](../src/main/resources/db/migration/001_baseline.sql), applied
to an empty database by [SchemaMigrator](../src/main/kotlin/infoscry/storage/SchemaMigrator.kt)
and recorded as `PRAGMA user_version = 1`. It is edited in place when the schema
changes: there are no incremental migrations and no upgrade path, because no
deployed archive has to be preserved. A database whose version is newer than the
build is refused rather than written to.

Import deduplicates by collection and SHA-256. External originals are never
modified or deleted. Content-unit IDs remain stable; extraction results and
checkpoints are committed per unit with durable artifact references. Restarting
resumes committed work, including OCR. Re-embedding does not invalidate
extraction checkpoints.

A standalone CLI import owns processing until completion, regardless of
`--wait`. With a running server, that server owns queued jobs. CLI
profile-management commands require exclusive ownership of the archive;
the browser manages profiles through the running server.

## Mutation, deletion and recovery

Services share mutation admission and exclusive maintenance. Reindexing pauses
mutations while existing searches continue; index publication must preserve all
collections and publish the writer, reader and embedder together.

Collection and document deletion use persisted operations and recovery phases.
An admitted deletion continues even if its initiating request is lost. The UI
reads durable operation status after reload or restart. Deletion removes managed
content and search entries and cancels affected work at a processing boundary.

Document progress comes from committed content units and extraction checkpoints.
Import-history counters count files. Retry reads managed copies, snapshots the
collection's current OCR settings and preserves reusable extraction work.

See the [Collections spec](specs/2026-09-27-collections-management.md) for the
contracts and [tickets](tickets/collections-management/STATUS.md) for verification.

## Embeddings and GPU requirements

The pinned model is `intfloat/multilingual-e5-base`, with 768-dimensional vectors
and a 512-token input limit. The
[model manifest](../models/embedding-model.json) fixes the revision, checksums,
tokenizer, query/passage prefixes and execution-provider options.

Every complete embedding input must fit within 512 tokens using that tokenizer,
including prefixes, special tokens and repeated headers. Passages are never
silently truncated. Blank pages and a page consisting only of the known `Â`
doubled-encoding artifact are not chunked, embedded or indexed. Single letters,
numbers, formulas and non-Latin text remain searchable. Chunker version 3
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
it, and an empty reading on its own never establishes it.

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
versioned JSON request line per page on the worker's stdin — the page's stable
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

## Privacy and local API boundaries

Originals, extracted text, embeddings, indexes and saved conversations stay
local. Ask and Investigate send prompts, questions, selected evidence and
citation metadata to the configured OpenAI-compatible or Anthropic endpoint.
That endpoint may be external; these features are not necessarily offline.

The server binds only to `127.0.0.1` and checks loopback Host headers. Read-only
requests need no credential. Mutations require either the CLI's bearer token or
the browser's CSRF token; credentials are generated per server launch.
See [Security.kt](../src/main/kotlin/infoscry/server/Security.kt).

API keys are read only from environment variables. Profiles and browser requests
carry the variable's name, never its value. Keys, questions, answers, document
text and provider authorization headers must stay out of logs and errors.

Imported instructions are untrusted source data. LLM tools use collection-scoped
opaque IDs: they cannot switch collections, access arbitrary paths or browse
the web. Multi-user/public hosting, Windows, knowledge graphs, DRM bypass and
an external MCP server are outside the local-use scope.

## LLM profiles and model catalog

InfoScry calls configured endpoints and does not host an LLM. Profiles store
provider, endpoint, model, context/output limits, prices, tool support and the
API-key environment-variable name. Ask and Investigate have separate optional
default profiles.

Admin's model picker uses read-only `GET /api/llm/presets` and
`GET /api/llm/catalog`. Catalog parameters are `provider`, `endpoint` and
`apiKeyEnvironmentVariable`; these GET requests need no bearer or CSRF token.
Only the server reads the named API-key variable.

Catalog prices come from OpenRouter's `/models` response, the built-in
[provider table](../src/main/resources/llm/providers.json) for OpenAI and
Anthropic, and zero pricing for loopback endpoints such as Ollama. Unknown values
remain blank for manual entry. Catalog/unit/route/component coverage does not
establish real-provider browser acceptance.

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

The [user guide](usage.md#investigate-and-ask-follow-up-questions) lists defaults
and configurable ranges. Limits are numeric browser preferences stored under
`infoscry-investigate-limits:v1` and apply to future turns.

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

See the [Investigate spec](specs/investigate-follow-up-and-efficiency.md) and
[verification report](tickets/investigate-follow-up-and-efficiency/STATUS.md).
