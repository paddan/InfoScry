# InfoScry Design Specification

**Date:** 2026-09-20  
**Status:** Current local-use product contract
**Product language:** English

**Scope:** InfoScry is built and used locally by one person on macOS arm64 with an Apple GPU. CLI handles import/administration; the web UI handles collection selection, search, exact source viewing, Ask, and Investigate. Web import, job, Settings, prompt, diagnostic, and log pages are out of scope. CI, Linux/CUDA portability work, distributable packaging, signing, and release gates are out of scope. Existing administrative HTTP routes need not be removed.

## 1. Purpose

InfoScry is a local, single-user application for importing heterogeneous,
unstructured documents, making them searchable, and asking LLM-assisted
questions against them with verifiable citations.

The first local version must:

- import documents into an immutable managed library;
- extract text and structure from common document, image, Office, web, and
  e-book formats;
- OCR scanned PDFs and images;
- support common European languages;
- provide keyword, semantic, and hybrid search;
- provide a one-shot RAG mode named **Ask**;
- provide a multi-step tool-using chat mode named **Investigate**;
- cite an exact page, section, slide, cell range, line range, or e-book chapter;
- keep originals, extracted text, embeddings, and indexes on the local machine;
- send only the question, selected source excerpts, and their citation metadata to
  the configured LLM;
- run on macOS arm64 with an Apple GPU;
- expose both an English-language web interface and a CLI.

The design target is a local archive of up to roughly 10,000 documents or one
million pages. This is a sizing target, not a required test-corpus size.

## 2. Non-goals for the first local version

The first local version will not include:

- multiple users, authentication, roles, or tenant isolation;
- Windows support;
- internet or web search;
- email mailbox ingestion;
- audio or video transcription;
- a knowledge graph or persistent entity extraction;
- DRM removal or password cracking;
- a public server binding;
- a separately deployed worker service;
- Docker as the primary installation method;
- a native desktop shell such as Electron or Tauri;
- a native installer or automatic updater;
- an external MCP server;
- automatic LLM correction of OCR text.

## 3. Technology choices

### 3.1 Backend and CLI

The backend and CLI use Kotlin targeting JVM 25 LTS, built with a JDK 25
Gradle toolchain. Ktor provides the local HTTP server. The CLI uses Clikt.

Application orchestration, persistence, extraction coordination, indexing,
search, and LLM integration remain Kotlin code. External tools implemented in
other languages are allowed when no equally suitable maintained JVM option
exists, but they do not own application state or business logic.

### 3.2 Frontend

The frontend uses SvelteKit with TypeScript. It is built as static assets and
served by Ktor. Node.js is required for local frontend builds, not when
running an already built application.

The frontend uses normal CSS and accessible HTML. The first local version will not
introduce Tailwind, a design-system framework, or a large component library.

### 3.3 Persistence and search

- SQLite stores authoritative application state, extracted source text, jobs,
  conversations, configuration profiles, and citations.
- Apache Lucene stores the rebuildable full-text and vector search index.
- Managed original files and generated artifacts live under the InfoScry data
  directory.
- ONNX Runtime runs the local embedding model with required GPU acceleration
  through the macOS CoreML execution provider with GPU-enabled compute units.
  Diagnostics, source viewing, and existing keyword search remain usable
  without a ready GPU, but embedding-dependent operations report an actionable
  error instead of silently falling back to CPU-only inference.

Lucene is preferred over LanceDB because it embeds directly in the JVM, has
mature lexical and OCR-tolerant search features, and supports HNSW/KNN vectors
in the same local index. LanceDB's local Java support is not mature enough for
the one-process JVM design. SQLite remains the source of truth, so the Lucene
index may be rebuilt without re-importing originals.

### 3.4 Document libraries and external tools

The implementation uses these responsibilities:

- Apache Tika: media-type detection, metadata, and fallback extraction;
- Apache PDFBox: page-level PDF extraction and rendering;
- Apache POI: DOCX, XLS/XLSX, and PPTX extraction;
- jsoup: sanitized HTML and EPUB XHTML parsing;
- Apache Commons CSV: CSV parsing;
- Tesseract CLI: OCR for images and rendered PDF pages;
- Calibre `ebook-convert`: optional conversion of Kindle and legacy e-books;
- ONNX Runtime with the macOS CoreML native library: local document and query embeddings.

Tesseract is a mandatory runtime dependency. Calibre is optional; formats that
require it receive `NEEDS_TOOL` status when it is unavailable.

## 4. Process architecture

InfoScry is an embedded local monolith:

```text
One InfoScry JVM process
├── Ktor API and static frontend
├── persistent job runner
├── document extractors
├── Lucene hybrid index
├── SQLite state
├── managed document library
└── LLM provider adapters

External child processes
├── Tesseract
└── Calibre ebook-convert (optional)
```

The web routes and CLI commands use the same application services. There is no
separate worker, message broker, PostgreSQL instance, vector service, or Node
server in production.

A process lock protects the data directory. Only one InfoScry process may
write at a time:

- when the server is running, mutating CLI commands use its loopback API;
- the server publishes its PID, port, and a random private bearer token in
  `runtime.json`; the CLI validates the PID and uses that token;
- when the server is not running, a CLI command opens the application services
  directly and holds the process lock; import runs to completion in that same
  foreground process, with no detached worker;
- with a server, import returns after the server accepts ownership by default;
  `--wait` waits for completion. Without a server, import always waits, and a
  zero exit means all documents completed (warnings allowed), not merely queued;
- read-only diagnostics and log tailing do not require the server.

## 5. Domain model

### 5.1 Collections

A collection is a manually created logical search boundary. It is not a mirror
of one filesystem directory. Files and directories from multiple locations may
be imported into one collection.

Each document belongs to exactly one collection. Search and LLM conversations
normally operate within one selected collection. A pre-created collection
named **Default** supports users who do not need organization.

Deleting a collection removes its InfoScry-managed originals, derived
artifacts, database rows, and index entries after explicit confirmation. It
never deletes the original files from which documents were imported.

Deletion holds exclusive maintenance admission, drains active write stages,
and transactionally marks the collection `DELETING` while recording a durable
operation independent of foreign-key cascades. New work and reads for that
collection are rejected. The operation moves managed files into a recorded
private trash directory, deletes DB rows, commits index deletion, then purges
trash. Its phases are `PREPARED`, `FILES_MOVED`, `DB_DELETED`, `INDEX_DELETED`,
and `DONE`. Restart rolls unfinished operations forward before serving requests
or admitting jobs. Failures remain pending and visible; recovery never relies
on an in-memory exception handler to restore files. Stale index entries cannot
make a deleting or absent collection readable.

### 5.2 Document hierarchy

```text
Collection
└── Document
    └── ContentUnit
        └── Chunk
```

- A **Document** represents one immutable imported original.
- A **ContentUnit** is the smallest independently readable and citable source
  location.
- A **Chunk** is a retrieval excerpt with an embedding. A chunk never crosses a
  content-unit boundary.

A unique constraint on `(collection_id, sha256)` prevents duplicates within a
collection. The same bytes imported into two collections create two separate
documents, avoiding global blob ownership and garbage collection.

### 5.3 Source locations

The Kotlin domain uses a closed set of source-location types:

```kotlin
sealed interface SourceLocation {
    data class PdfPage(val page: Int) : SourceLocation
    data class Image(val name: String) : SourceLocation
    data class WordSection(
        val headingPath: List<String>,
        val paragraphStart: Int,
        val paragraphEnd: Int,
    ) : SourceLocation
    data class SpreadsheetRange(
        val sheet: String,
        val startCell: String,
        val endCell: String,
    ) : SourceLocation
    data class Slide(val number: Int) : SourceLocation
    data class TextLines(val start: Int, val end: Int) : SourceLocation
    data class HtmlSection(val headingPath: List<String>) : SourceLocation
    data class EbookSection(
        val chapter: String?,
        val headingPath: List<String>,
        val spineIndex: Int,
    ) : SourceLocation
}
```

The type and its fields are serialized into SQLite. Every Lucene chunk stores
its collection, document, and content-unit identifiers.

## 6. Local storage layout

The default data directory is `~/.infoscry`:

```text
~/.infoscry/
├── infoscry.db
├── infoscry.lock
├── runtime.json             present only while the server runs
├── library/
│   └── <collection-id>/
│       └── <document-id>/
│           ├── original.<ext>
│           └── artifacts/
├── index/
│   ├── current                 atomic marker naming the active generation
│   └── lucene-<generation>/
├── models/
│   └── embedding/
│       ├── model.onnx
│       └── tokenizer files
└── logs/
```

The data directory and its contents use private user-only permissions where the
operating system supports them.

SQLite stores:

- collections and documents;
- content units and extracted/search text;
- import and processing jobs, per-unit extraction checkpoints, and deletion operations;
- LLM profiles without secrets;
- Ask and Investigate conversations;
- model-call usage and cost;
- answers and validated citations;
- schema and index metadata.

Lucene stores:

- chunk text needed for result snippets;
- BM25 fields;
- embedding vectors;
- metadata fields and filters;
- collection, document, and content-unit identifiers.

## 7. Import and extraction pipeline

### 7.1 Import sequence

```text
Select files or directory
→ copy to temporary managed storage while calculating SHA-256
→ reject a duplicate within the target collection
→ detect media type from content
→ atomically move the managed original into place
→ extract structured content
→ OCR only where required
→ normalize search text
→ commit content units/checkpoints incrementally and create bounded chunks
→ generate local embeddings in batches
→ write Lucene entries
→ mark the document complete
```

The original source path is retained as metadata. The source file and managed
copy are never modified.

### 7.2 Supported formats

| Format | Primary extractor | Citable unit |
|---|---|---|
| text PDF | PDFBox | page |
| scanned or mixed PDF | PDFBox + Tesseract | page |
| PNG, JPEG, TIFF | Tesseract | image |
| DOC/DOCX | Apache POI | heading path and paragraph range |
| XLS/XLSX | Apache POI | sheet and cell range |
| PPT/PPTX | Apache POI | slide |
| TXT/Markdown | JVM text parser | line range |
| HTML | jsoup | heading path |
| CSV | Apache Commons CSV | row and column range |
| EPUB 2/3, KEPUB, iBooks | ZIP/OPF/XML + jsoup | chapter/section |
| FB2/FBZ | XML/ZIP + XML | section |
| Kindle and legacy e-books | Calibre to EPUB, then EPUB extractor | chapter/section |

The initial Calibre-backed set includes MOBI, PRC, AZW, AZW3, AZW4, LIT, PDB,
LRF, RB, SNB, and TCR. Other Calibre-supported inputs may be accepted only when
the same conversion path and tests demonstrate stable extraction.

DRM-protected and password-protected inputs are not decrypted. They receive an
actionable unsupported/encrypted error.

### 7.3 PDF and OCR behavior

PDF extraction runs per page. PDFBox text is accepted when extraction succeeds
and the page contains usable text. A page is sent to OCR when extraction fails,
contains fewer than 40 alphanumeric characters, or has an alphanumeric ratio
below 0.5 after whitespace is removed.

For OCR pages:

1. PDFBox renders the page to an image.
2. Tesseract runs with the collection's configured OCR language codes. New
   collections default to `eng`; the user may select any installed European
   language packs.
3. InfoScry atomically publishes compressed TSV word positions, then commits
   extracted text, mean confidence, artifact checksum/reference, and the page
   checkpoint together. A checkpoint is not committed before its artifact.
4. The original PDF remains unchanged.

Image documents use the same Tesseract output. Compressed OCR positions live at
`artifacts/ocr/page-<number>.tsv.gz` or the corresponding image artifact path.
They support a source-viewer overlay without creating hundreds of millions of
SQLite rows. Artifact paths are namespaced by extraction fingerprint. Restart
skips committed matching pages before rendering or invoking Tesseract; an
uncommitted page may be retried. A corrupt/missing artifact invalidates only the
affected checkpoint and produces an actionable repair warning.

### 7.4 E-book behavior

Native EPUB-family extraction reads package metadata, the spine, navigation,
chapter HTML, headings, footnotes, and embedded images. It retains title,
author, publisher, language, identifiers, and chapter order.

Calibre-backed formats are converted to `artifacts/normalized.epub`. The
artifact, Calibre version, and conversion log are retained for reproducibility.
The managed original remains authoritative. Since reflowable e-books do not
have stable pages, citations use chapter and heading paths.

Image-only e-book sections use the normal OCR path. DRM-protected content is
rejected rather than bypassed.

### 7.5 Text preservation and normalization

InfoScry retains two forms:

- **extracted text**, as close as practical to extractor output;
- **search text**, normalized to Unicode NFC with consistent line endings and
  control characters removed.

No LLM or spelling correction runs during import. Search normalization must not
change source meaning or overwrite extracted text.

### 7.6 Chunking and embeddings

Each complete embedding input fits at most 512 model tokens, including the
`passage: ` prefix, special tokens, and repeated table headers. Chunking uses
the exact tokenizer with truncation disabled and 100 body tokens of overlap
where available. It shrinks the body to fit the fully encoded passage, rather
than subtracting a guessed prefix length. Oversized headers become separately
citable chunks. No source text, including the final tail of a unit, is silently
discarded. Oversized queries receive a validation error. Boundaries follow
document structure:

- PDF and image chunks remain within a page;
- Word, HTML, and e-book chunks remain within a heading section;
- PowerPoint chunks remain within a slide;
- Excel and CSV chunks cover contiguous row ranges and repeat column headers;
- plain-text chunks respect paragraph and line boundaries.

The default local embedding model is `intfloat/multilingual-e5-base` at revision
`d128750597153bb5987e10b1c3493a34e5a4502a`, producing 768-dimensional vectors:

- macOS arm64/Apple GPU: `onnx/model.onnx` with ONNX Runtime CoreML and GPU
  enabled. There is one platform entry because there is one validated platform;
  the CUDA-optimized O4 export is not used and no CUDA dependency is declared.

The local model installation pins artifact checksums, matching tokenizer files,
provider/native-runtime build, and provider options. GPU presence alone is not
sufficient: the real model must load and execute accelerated transformer
compute on the supported platform. CPU support for shape/control operators is
allowed; CPU-only embedding inference is not. CoreML provider registration
alone is not proof of GPU execution. The local hardware check records device
traces, tested OS/driver/runtime versions, latency, and memory in
`docs/gpu-validation.md`; exact compatibility is established by those tests,
not asserted by this design. Intel Macs, Linux x86_64, and non-Apple GPUs are
outside the v1 runtime matrix.

Query and document prefixes follow the model specification. The index records
model revision, export checksum, tokenizer/pooling/prefix version, and vector
dimension as a model fingerprint. Changing that fingerprint requires a
whole-index re-embedding/rebuild. Rechunk from persisted text when needed;
content-unit IDs, extraction, and OCR remain reusable. An active index retains
its matching query embedder until the new generation is published.

### 7.7 Job states and recovery

Documents use these persisted states:

```text
QUEUED
COPYING
EXTRACTING
OCR
CHUNKING
EMBEDDING
INDEXING
COMPLETE
COMPLETE_WITH_WARNINGS
FAILED
CANCELLED
NEEDS_TOOL
```

State is committed after each recoverable stage and after each extracted
content unit. Extractors stream deterministic unit events with backpressure;
they do not return a document-sized list. Checkpoints use stable unit keys,
ordinals, and a fingerprint covering original hash, extractor/schema version,
OCR tool version/languages, and render parameters. These settings are frozen
for the job; incompatible tool changes require an explicit extraction restart.
Text, locator, artifact reference/checksum, and checkpoint commit atomically. Successful and failed
units have persisted outcomes; explicit retry may clear failures.

A restart validates checkpoints and resumes at unfinished units, skipping
completed matching pages before expensive rendering/OCR. Cheap container
parsing to locate units is allowed. A terminal extraction marker distinguishes
a finished partial-success document from an interrupted one. Chunking then
processes persisted units incrementally with per-unit transactional replacement
and stable content-unit IDs. A document with some failed content units becomes
`COMPLETE_WITH_WARNINGS` after successful units are embedded/indexed; an
interrupted extraction is not marked complete. Changing embedding settings
alone never invalidates OCR checkpoints.

Tesseract and embedding work use separate bounded worker pools. Cancellation
occurs between safe units and terminates an active child process without
removing completed work.

## 8. Search design

### 8.1 Search modes

InfoScry exposes:

- **Keyword**: Lucene BM25;
- **Semantic**: local query embedding and Lucene HNSW/KNN;
- **Hybrid**: both, combined by reciprocal rank fusion.

Hybrid is the default. It retrieves the top 50 lexical and top 50 vector
results, then computes:

```text
RRF score = 1 / (60 + lexical rank) + 1 / (60 + vector rank)
```

Missing ranks contribute zero. The top 30 fused chunks proceed to result
presentation or Ask context selection. The first local version does not include a
cross-encoder reranker.

### 8.2 Filters and results

Search supports filters for:

- collection;
- document type;
- filename and original path;
- title, author, and language;
- import date;
- processing status;
- OCR-derived content.

Results display document title, source location, matched excerpt, matching mode,
and an action to open the exact source.

## 9. Ask

Ask answers one question with one retrieval pass:

1. run hybrid retrieval;
2. remove near-duplicate chunks;
3. retain source diversity where possible;
4. pack at most 12 excerpts within the model context budget;
5. assign source identifiers such as `[S1]`;
6. call the selected LLM profile;
7. validate every returned source identifier;
8. persist the answer, usage, and citations.

The prompt requires the model to:

- use only supplied material;
- cite each verifiable factual claim;
- distinguish source observations from conclusions;
- say when the material is insufficient;
- answer in the question's language.

Unknown source IDs never become clickable citations. InfoScry makes at most one
citation-correction call. If validation still fails, the invalid reference is
visibly marked instead of being linked to an unrelated source.

## 10. Investigate

Investigate is a multi-turn conversation in which the model performs repeated
search and reading operations. A conversation is fixed to one collection when
created.

The model receives these tools:

```text
search_collection(query, searchMode, filters, limit)
get_document_metadata(documentId)
list_document_structure(documentId)
read_content_unit(contentUnitId)
read_adjacent_units(contentUnitId, before, after)
```

Default hard limits are:

- 10 tool rounds;
- 20 total tool calls;
- 10 search hits returned per tool call;
- provider and operation timeouts;
- user cancellation at any point;
- each tool result is capped at 2,048 budget units and remaining request space;
- every request fits the configured context window after reserving output and
  a 1,024-token safety margin, including correction and continuation calls.

The budget counts the full provider-serialized request: core/user/collection
instructions, current question, history, tool schemas/calls/results, citation
metadata, and framing. Use the LLM tokenizer when locally available; otherwise
use serialized UTF-8 byte count as a conservative estimate and label it as
estimated. The embedding tokenizer is not an LLM token counter. Provider
context-limit rejections are typed errors and never replayed after streaming.

When space is needed, omit oldest completed prior user turns, then oldest
completed tool exchanges as whole call/result groups. Keep core rules and the
current question, never split a tool-call/result pair, and record omissions.
Full local history remains available; v1 does not generate LLM summaries.
Excerpt reduction preserves source boundaries, locator/offset metadata, and a
visible truncation marker. If the irreducible request cannot fit, stop before
calling the provider with `CONTEXT_BUDGET_EXCEEDED`.

Evidence IDs are allocated monotonically per conversation and never reused or
renumbered after eviction. Each model request records its eligible evidence;
new answers may cite only evidence actually supplied in that request. Older
persisted answers retain their original citation mappings. An evicted source
becomes eligible again only when its evidence is supplied again. Ask uses the
same complete-request budget and correction-call rules.

The model cannot switch collections, access arbitrary paths, or use the
internet. Tool arguments expose opaque IDs rather than filesystem paths.

Documents are untrusted data. Core prompts explicitly tell the model that text
inside tool results is evidence, not instruction. Active HTML content is never
executed.

OpenAI-compatible and Anthropic provider adapters invoke the Kotlin tool
implementations directly. The first local version does not start an MCP subprocess.
The tools remain reusable application services so an `infoscry mcp` transport
may be added as a later project if an external client needs it.

## 11. LLM configuration

### 11.1 Profiles

Named LLM profiles are stored in SQLite with these fields:

```text
name
provider: openai-compatible | anthropic
endpoint
model
api_key_environment_variable
context_window
max_output_tokens
supports_tool_calling
input_price_per_million
output_price_per_million
cache_read_price_per_million
enabled
```

A profile contains no API-key value. The backend reads the configured
environment variable when making a request. The frontend sees only whether the
variable is available. Local OpenAI-compatible endpoints may omit a key.

Separate default profiles are selected for Ask and Investigate. Models without
working tool calling may be used for Ask but not Investigate.

### 11.2 Capability testing

A profile test performs:

1. a short text-generation request;
2. a harmless test tool call.

The measured result determines whether Investigate may use the profile. The
system does not trust a capability checkbox alone.

### 11.3 Conversation snapshots

Starting a conversation stores a non-secret snapshot of provider, endpoint,
model, profile name, prompt version, and retrieval settings. Later profile
edits do not rewrite conversation history.

### 11.4 Prompt layers

Prompts have three layers:

1. immutable core safety, source, citation, and tool rules;
2. versioned, user-editable Ask and Investigate prompt bodies that can be
   viewed and reset to the shipped defaults;
3. optional collection instructions for domain terminology and response style.

Collection instructions cannot disable source validation, enable internet or
filesystem access, or override core security rules.

### 11.5 Streaming, retries, and cost

LLM output streams through Ktor to the frontend using SSE. Temporary 429 and
5xx responses use bounded exponential backoff. A request that has begun
streaming is not automatically replayed.

Provider-reported token usage is stored per call. Cost is calculated from the
profile's configured prices and shown per conversation. Investigate displays
running elapsed time, token use, estimated cost, and a cancel action.

## 12. Web interface

All UI copy is English.

### 12.1 Global navigation

```text
InfoScry
└── Collection selector

Selected collection
├── Search
├── Ask
└── Investigate
```

The collection selector shows existing collections. Creating collections,
importing documents, inspecting jobs, and configuring the application remain
CLI administration tasks.

### 12.2 Search and source viewer

Search uses a result pane and source-viewer pane. Citation clicks open the exact
source location.

Viewer behavior:

- PDF: PDF.js at the cited page;
- image: image with OCR overlay;
- Word, HTML, and e-book: sanitized section rendering;
- Excel and CSV: a table with the cited range highlighted;
- PowerPoint: slide preview plus extracted text;
- fallback: extracted text and an **Open original** action.

### 12.3 Ask and Investigate

Ask displays one answer with inline citations and source cards. Investigate
displays chat plus a collapsed activity timeline of searches and source reads.
Tool results may be expanded for inspection but do not dominate the default
view.

Questions, answers, conversations, citations, model snapshots, token usage, and
cost remain associated with the collection and may be deleted by the user.

The web UI does not accept API-key values or provide an administration Settings
page. CLI commands remain the operator interface; add a missing command only
when a concrete local workflow needs it.

## 13. CLI

Required commands include:

```bash
infoscry serve
infoscry collection create "Project Nightfall"
infoscry collection list

infoscry import --collection "Project Nightfall" ./documents
infoscry jobs
infoscry jobs cancel <job-id>

infoscry search --collection "Project Nightfall" "offshore payments"
infoscry ask --collection "Project Nightfall" "Who approved the payments?"

infoscry llm list
infoscry llm add --name <name> --provider <provider> --model <model> [options]
infoscry llm test <name>
infoscry llm set-default --ask <name>
infoscry llm set-default --investigate <name>

infoscry reindex --collection "Project Nightfall"

infoscry logs
infoscry logs --follow
infoscry logs --follow --level WARN
infoscry logs --follow --job <job-id>
infoscry logs --follow --component ingest
infoscry logs --since 30m
```

Commands return zero for success and non-zero for failure. A general `--json`
flag provides machine-readable output without creating separate business
flows. `logs --follow` continues across log rotation.

## 14. Logging and diagnostics

Logs are structured JSON lines on disk and human-readable in terminal output.
Each entry contains timestamp, level, component, optional job and document IDs,
message, and exception details.

Logs rotate and have a bounded total size. They do not contain document text,
questions, answers, source excerpts, or API keys by default. The CLI can read
and follow the logs; a web log viewer is not part of the local-use scope.

## 15. Security

- Ktor binds only to `127.0.0.1` in the first local version.
- CORS is disabled.
- Mutating browser requests require a per-launch session/CSRF token.
- The private `runtime.json` bearer token authenticates CLI loopback requests
  and is removed on clean shutdown; stale files are rejected by PID validation.
- The data directory uses private permissions.
- API keys come only from environment variables.
- HTML and e-book content is sanitized before display.
- ZIP-based formats enforce entry-count and uncompressed-size limits.
- Directory imports do not follow symbolic links unless a symlink is explicitly
  named as the import target.
- Child tools receive isolated temporary directories and timeouts.
- Child tools never modify the managed original.
- Imported text is always treated as untrusted evidence, never instruction.
- Arbitrary filesystem paths are not available to an LLM tool.
- DRM and password protection are not bypassed.

## 16. Error handling and consistency

A failure in one document does not stop the remaining import job. Errors are
persisted with actionable messages and visible in logs and CLI output.

SQLite is authoritative. Lucene changes are idempotent by `document_id`: all
chunks for that ID are atomically replaced and committed before SQLite marks
completion. If a crash occurs between Lucene writing and SQLite status update,
recovery repeats the replacement from persisted chunks, without repeating OCR.

Each import item retains its managed document ID, so recovery continues from
the managed original even if its external source has moved. Each extraction
unit holds a shared mutation permit from artifact production through checkpoint
commit, and yields before the next unit. Other mutation stages also use shared
admission permits; collection deletion and index rebuild use exclusive
maintenance. Maintenance blocks new stages and
drains active ones before changing files or taking the rebuild snapshot.
New mutating commands receive `MAINTENANCE_IN_PROGRESS` (HTTP 423/nonzero CLI);
existing jobs wait at durable checkpoints. Existing search/source reads may
continue unless their collection is deleting.

Rebuild constructs a complete new generation while holding exclusive
maintenance. The single index always includes every live collection:
`reindex --collection` regenerates the selected collection and preserves the
others; a model/schema change rebuilds all collections. The old index and its
matching query embedder serve reads until the new generation is validated and
committed. An atomic `index/current` marker selects the durable generation.
Publish writer, SearcherManager, and query embedder together; retire the old
generation only after existing readers release their leases. Resume jobs only
after they recheck collection lifecycle and model fingerprint. Failed rebuilds
before the marker swap leave the old generation active; crashes after the swap
recover the new generation.

Startup holds the process lock, opens the valid marker target, completes
pending deletion recovery, and only then admits jobs/requests. Cleanup never
removes the marker target. Missing/invalid targets and inconsistent deletion
paths are explicit recovery errors rather than permission to guess or discard
managed data.

SQLite uses numbered forward migrations. Lucene has a separate index-schema
version. A mismatch in schema, embedding model, or vector dimension requires an
index rebuild. Existing managed originals, extracted text, and OCR artifacts
remain reusable.

## 17. Testing

The repository includes a small, redistributable fixture archive containing:

- a text PDF;
- a mixed text/OCR PDF;
- a PNG with European-language text;
- a DOCX with headings and a table;
- an XLSX with multiple sheets and formulas;
- a PPTX;
- HTML and Markdown;
- EPUB and FB2;
- a malformed file;
- a password-protected file.

Test layers:

1. unit tests for source locations, chunking, RRF, citation validation, and job
   transitions;
2. extractor integration tests that assert expected text and locations;
3. search-quality tests with fixed questions and expected content units;
4. forced-process-termination tests for per-page OCR reuse, deletion phases,
   and generation swaps; concurrent import/delete attempts during rebuild;
   standalone and server-owned CLI process-lifetime/exit-code tests;
5. provider-adapter tests against local mock HTTP servers, never paid APIs;
6. one local browser flow after CLI import, covering search and opening a citation;
7. local macOS tests with fakes, plus a real CoreML run on this Mac before
   claiming GPU-dependent functionality works. Real-model tests include
   512-token boundary coverage and must fail, not skip, when required hardware
   is absent;
8. complete-request budget tests with long histories, small contexts, Unicode,
   tool schemas/results, whole-group eviction, and stable citation mappings.

Large performance runs are manual and use generated or separately obtained
corpora. They are not committed to the repository.

## 18. Local build and operation

Build from the local checkout with JDK 25 and Node.js/npm. Ktor serves the
compiled SvelteKit assets; Tesseract is installed separately and is mandatory,
while Calibre is optional. Run the real CoreML/model and Tesseract checks on
the local Mac. No archive, bundled Java runtime, installer, signing, CI, or
release workflow is required.

## 19. Acceptance criteria

The first locally usable version is complete when its user on the macOS arm64
GPU target can:

1. start InfoScry and open the local English web UI;
2. create or use a collection;
3. import a mixed directory of supported documents into managed storage;
4. stop and restart an import without duplication, loss, or repeated OCR of committed pages;
5. inspect failures through the CLI without stopping successful documents;
6. run keyword, semantic, and hybrid searches with verified GPU embeddings and complete token coverage;
7. open each result at its exact source location;
8. configure an OpenAI-compatible or Anthropic profile through the CLI without storing its key;
9. ask a RAG question and receive validated clickable citations;
10. run an Investigate conversation with bounded search/read calls, complete-request budgets, and stable citations after context eviction;
11. see token use, estimated cost, and tool activity in the web UI, and live logs in the CLI;
12. run `infoscry logs --follow` while processing continues;
13. rebuild Lucene and embeddings without repeating extraction/OCR or losing concurrent work, and recover interrupted collection deletion consistently;
14. verify through local automated tests that no paid provider call occurs;
15. confirm original source files were never modified or deleted.
