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
