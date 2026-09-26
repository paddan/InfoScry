# InfoScry

InfoScry is a planned local, single-user application for importing documents,
searching their contents, and asking questions with citations to exact source
locations.

**Status: implementation in progress.** The local backend, CLI, import/extraction
pipeline, CoreML embedding path, hybrid search, crash-aware reindexing, LLM
profiles, streaming OpenAI-compatible/Anthropic adapters, cited Ask, and
bounded Investigate exist.
The reader-focused web UI now has collection search, a bounded extracted-source
view with a managed-original link, Ask, an Investigate conversation panel, and
an Admin view with two sub-tabs. The LLM profiles sub-tab offers provider
presets (OpenAI, Anthropic, Ollama, OpenRouter, and Custom) and a Fetch models
action that lists the provider's models and prefills the context window, output
limit, and prices where known. The Import sub-tab creates a collection and loads
local files or folders into it: the local server opens a native macOS picker and
returns the chosen absolute paths to the page, which hands them to the same
import job the CLI starts, with a recursive toggle and per-file results. Local
end-to-end acceptance of the browser flows is still open, as are the
format-specific source views described in the design.
Import is available in the CLI and the web Admin view; jobs and logs belong in
the CLI.
This project is built and run locally; CI and distributable packaging are not
planned.

The web reader uses a dark theme with a sidebar and separate Search, Ask,
Investigate, and Admin views. Search settings include retrieval mode, file
type, path, metadata, import dates, document status, and OCR-only filtering.
Ask and Investigate select from configured LLM profiles; both can reopen the
conversations the server kept for the selected collection, Ask as a history of
its stored answers and Investigate as its multi-turn conversations. The Admin
view holds
two sub-tabs, LLM profiles (create, edit, and delete profiles and set the
per-role defaults) and Import (create a collection and load local files or
folders into it). The native picker needs a graphical macOS session; without
one, the Import sub-tab falls back to entering paths by hand.
Search filters apply only to Search. Switching views preserves their current
content.

## Planned capabilities

- Import files and directories into collections with immutable managed copies
  and duplicate detection within each collection.
- Extract text and structure from PDFs, images, Office documents, plain text,
  Markdown, HTML, CSV, and e-books; OCR scanned pages with Tesseract.
- Search with keyword, semantic, or hybrid retrieval.
- Use **Ask** for a question answered from one retrieval pass, or **Investigate**
  for a multi-turn conversation with bounded search and reading tools.
- Open citations at a page, section, slide, cell range, line range, or e-book
  chapter. Citation validation checks source identifiers; it does not by itself
  prove that an answer's claims are supported.
- Inspect sources, search results, answers, and citations in an English web
  interface; import files from the CLI or the Admin view's Import sub-tab, manage
  LLM profiles from the CLI or the LLM profiles sub-tab, and handle jobs and logs
  in the CLI.

The design targets roughly 10,000 documents or one million pages. This is a
sizing target, not a measured capacity claim.

## Architecture

One Kotlin/JVM process owns the application services, local web server,
persistent jobs, managed library, database, search index, and LLM adapters.

| Responsibility | Planned technology |
|---|---|
| Backend and CLI | Kotlin, Java 25, Ktor, Clikt |
| Web interface | SvelteKit and TypeScript, built as static assets served by Ktor |
| Authoritative state and extracted text | SQLite |
| Rebuildable keyword and vector index | Apache Lucene |
| Local embeddings | Multilingual E5 via ONNX Runtime with GPU acceleration |
| Extraction | Tika, PDFBox, POI, jsoup, Commons CSV |
| External document tools | Tesseract; optional Calibre |

The planned data directory is `~/.infoscry`. Extraction checkpoints preserve
finished OCR work across restarts. Collection deletion has durable recovery
phases, and index rebuilds pause mutations while existing searches continue.
Without a running server, CLI import owns the job and runs in the foreground;
with a server, the server owns queued work.

## Target platforms and requirements

GPU acceleration is required for embeddings. The initial runtime targets are:

| Platform | GPU and execution provider | Model export |
|---|---|---|
| macOS arm64 | Apple GPU through CoreML | Standard E5 ONNX export |

Linux/CUDA is outside this local project: no CUDA runtime or O4 model path is
included. Intel Macs and other GPU configurations are also outside the target.
Diagnostics, source viewing, and existing keyword search remain
available if GPU readiness fails.

Tesseract is a mandatory runtime dependency; Calibre is optional for formats
that require conversion. Local builds require JDK 25 and Node.js/npm.

## Privacy and boundaries

Originals, extracted text, embeddings, indexes, and saved conversations stay
local. Ask and Investigate send prompts, questions, and selected evidence with
citation metadata to the configured OpenAI-compatible or Anthropic endpoint;
their use is not necessarily offline. API keys come from environment variables
and are not stored in profiles or exposed to the browser; the model catalog is
fetched with only the environment-variable name, never the key value, crossing
the boundary.

The server binds only to `127.0.0.1`. Imported documents are untrusted evidence;
LLM tools cannot access arbitrary paths, switch collections, or browse the web.
The local-use scope excludes multi-user/public hosting, Windows, a knowledge
graph, DRM bypass, and an external MCP server.

## Build locally

From the repository root on macOS arm64, with JDK 25, Node.js/npm, and
Tesseract installed:

```bash
cd /Users/patrik/projects/infoscry
export JAVA_HOME="$(asdf where java)"
./gradlew installDist
./gradlew embeddingModel # First time only; downloads about 1.1 GB
```

`installDist` compiles the backend, builds the web UI, and writes the runnable
app and its dependencies to `build/install/infoscry/`. Rebuild after code
changes. Gradle is not needed to run the built app; JDK 25 still is. The model
download is separate from the build and installs into `~/.infoscry` by default.

## Use the built app

From the same repository directory, add the built launcher to `PATH` for this
terminal session, then use `infoscry` directly:

```bash
export PATH="$PWD/build/install/infoscry/bin:$PATH"
infoscry --help
infoscry import --collection Default --wait /absolute/path/to/document.pdf
infoscry --serve
```

Without changing `PATH`, run `build/install/infoscry/bin/infoscry` instead of
`infoscry`. To use the short command in future terminal sessions, add the
build's absolute `bin` directory to your shell's `PATH`. The executable stays
inside the local build; no installer or system-wide installation is required.

Replace the example document path with a real absolute path. Import finishes
before the server starts and does not change the source file; InfoScry stores a
managed copy. `infoscry --serve` (or `infoscry serve`) opens the local web UI
in your default browser after the server starts. If that fails, open the URL
printed in the terminal (normally <http://127.0.0.1:8765>) yourself. `--json`
mode does not open a browser. The server uses `~/.infoscry` by default and runs
until you press Ctrl-C. A disposable sample is
`src/test/resources/fixtures/sample.txt` if you prefer not to start
with a personal document; importing it still adds it to the selected archive.

For an isolated archive, use the same `--data-dir /absolute/path/to/test-data`
with both `import` and `--serve` (put the data-dir option after `--serve`),
and install the model there with
`./gradlew embeddingModel -PdataDir=/absolute/path/to/test-data`. That downloads
a separate model copy. Do not run profile-management CLI commands while the
server owns that data directory.

The Admin view's Import sub-tab imports documents without leaving the browser.
"Choose files…" and "Choose folder…" open a native macOS dialog on the machine
running the server, and the chosen absolute paths are queued as the same import
job `infoscry import` starts. A folder is read at its top level; tick "Include
subfolders" to descend into it. The tab also creates a collection. The dialog
needs a graphical macOS session: when the server has none, the tab reports that
and offers a field to enter paths by hand. The pick route (`POST /api/imports/pick`)
is covered by route tests with the dialog replaced by a fake; the dialog itself
has had no end-to-end browser acceptance on this machine.

Ask and Investigate require a configured LLM profile to be selected in the
UI; a default profile for each mode is optional and only preselects a
convenient choice. InfoScry does **not** start or host an LLM: it calls the
endpoint you configure. Add and test profiles from the CLI (`llm add`,
`llm test <name>`) or from the web Admin view while the server is running.
The Admin view's Fetch models action is backed by two read-only GET routes,
`/api/llm/presets` and `/api/llm/catalog` (query parameters `provider`,
`endpoint`, and `apiKeyEnvironmentVariable`), which need no credential or
CSRF token. The API-key value never reaches the
browser; only the environment-variable name crosses the boundary, and the
server reads the key from its own environment. Prices come from OpenRouter's
live `/models` response, from the built-in table
(`src/main/resources/llm/providers.json`) for OpenAI and Anthropic because
their `/models` APIs return only model ids, and as free for a loopback
endpoint such as Ollama; unknown values are left blank for manual entry. The
catalog fetch and panel are covered by unit, route, and Vitest tests and the
frontend build passes, but there has been no end-to-end browser acceptance
against a real provider.
Optionally set a default with `llm set-default --ask <name>` or
`llm set-default --investigate <name>`, or with the Admin view's per-role
selectors; `llm test` makes real probe requests to that endpoint. Ask and Investigate
send selected document evidence to the configured endpoint, which may be
external. Their browser flows have not yet had the final manual fake-provider
acceptance check.

## CLI reference

Every command accepts `--json` for stable machine-readable output and
`--data-dir /path` to work against a specific data directory (default
`~/.infoscry`). Both options work before or after the subcommand name, so
`infoscry --json collection list` and `infoscry collection list --json` are
equivalent. `infoscry --help` and `infoscry <command> --help` print usage.

### serve

Start the local API and web UI in the foreground (Ctrl-C stops it). `--serve`
is accepted as a top-level alias for `serve`.

```bash
infoscry serve
infoscry serve --port 9000
infoscry serve --json            # print the URL/port/pid as JSON, no browser
infoscry serve --data-dir /path/to/test-data
```

Options: `--port` (default `8765`; `0` asks the OS for a free port),
`--json`, `--data-dir`.

### collection

```bash
infoscry collection list
infoscry collection create Notes --description "Meeting notes"
infoscry collection create Notes --json
```

Subcommands: `list` (shows name, id, OCR languages, description) and
`create <name>` with `--description`. Options: `--json`, `--data-dir`.

### import

Import files or directories into a collection as immutable managed copies.
Requires `--collection` and at least one path. A directory is read at its top
level only; pass `--recursive` to descend into its subdirectories. With
`--wait`, the command blocks until the import finishes and reports every
document (and exits nonzero if any failed). Without a running server, the
import runs in this process regardless of `--wait`.

```bash
infoscry import --collection Default /path/to/document.pdf
infoscry import --collection Default --recursive /path/to/dir
infoscry import --collection Default --wait /path/to/dir /path/to/another.pdf
infoscry import --collection Default --wait --json /path/to/document.pdf
```

Options: `--collection`, `--recursive`, `--wait`, `--json`, `--data-dir`.

### search

Search one collection. `--collection` is required; the query is the
positional argument. Default mode is hybrid.

```bash
infoscry search --collection Default "quarterly report"
infoscry search --collection Default --mode keyword "invoice"
infoscry search --collection Default --mode semantic "revenue trend"
infoscry search --collection Default --limit 10 --media-type application/pdf "budget"
infoscry search --collection Default --path reports/ --text "smith" --ocr-only "scan"
infoscry search --collection Default --from 2026-01-01 --until 2026-03-01 "note"
infoscry search --collection Default --status COMPLETE --json "summary"
```

Options: `--collection`, `--mode` (`keyword`, `semantic`, `hybrid`),
`--media-type` (repeatable), `--path`, `--text`, `--from`, `--until`,
`--status` (repeatable; `QUEUED`, `COPYING`, `EXTRACTING`, `OCR`, `CHUNKING`,
`EMBEDDING`, `INDEXING`, `COMPLETE`, `COMPLETE_WITH_WARNINGS`, `FAILED`,
`CANCELLED`, `NEEDS_TOOL`), `--ocr-only`, `--limit` (default `30`), `--json`,
`--data-dir`.

### ask

Ask one question answered from a single retrieval pass, streaming the answer
with citation markers. Requires a configured LLM profile; see `llm` below.

```bash
infoscry ask --collection Default --profile my-profile "What does the contract say about renewal?"
infoscry ask --collection Default --profile my-profile --json "Summarize this document"
```

Options: `--collection`, `--profile`, `--json`, `--data-dir`.

### jobs

List jobs newest-first, or record a cancellation request for one job.

```bash
infoscry jobs
infoscry jobs --limit 25
infoscry jobs cancel <job-id>
infoscry jobs --json
```

Options: `--limit` (default `100`), `--json`, `--data-dir`.

### reindex

Rebuild the search index from persisted text. Rebuilds every collection by
default; `--collection` limits it to one. `--wait` blocks until finished.

```bash
infoscry reindex
infoscry reindex --collection Default
infoscry reindex --wait --json
```

Options: `--collection`, `--wait`, `--json`, `--data-dir`.

### llm

Configure LLM profiles and the per-role defaults. Profiles store only the
endpoint, model, and the name of the environment variable holding the API
key — the key itself never enters the profile.

```bash
infoscry llm add --name my-profile --provider openai-compatible \
  --endpoint http://127.0.0.1:11434/v1 --model llama3.2 --api-key-env MY_API_KEY
infoscry llm add --name anthropic-profile --provider anthropic \
  --model claude-sonnet-4-5 --api-key-env ANTHROPIC_API_KEY
infoscry llm list
infoscry llm test my-profile        # makes real probe requests to the endpoint
infoscry llm set-default --ask my-profile
infoscry llm set-default --investigate my-profile
```

Subcommands: `list`, `add`, `set-default`, `test <name>`. `add` options:
`--name`, `--provider` (`openai-compatible` or `anthropic`), `--model`,
`--endpoint`, `--api-key-env`, `--context-window` (default `128000`),
`--max-output-tokens` (default `4096`), `--input-price`,
`--output-price`, `--cache-read-price`, `--tool-calling`, `--json`,
`--data-dir`. `set-default` takes exactly one of `--ask <name>` or
`--investigate <name>`.

Note: profile-management commands cannot run while a server owns the same
data directory.

### logs

Read the structured log stream, filtered and rendered for a terminal. Never
takes the process lock, so it works while the server is running.

```bash
infoscry logs
infoscry logs --follow
infoscry logs --level WARN --since 30m
infoscry logs --job <job-id> --component ingest
```

Options: `--follow`, `--level` (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`;
default `INFO`), `--job`, `--component`, `--since` (e.g. `30m`, `12h`, `7d`),
`--data-dir`.

## Tests and development

Run the normal offline suite first. It installs frontend dependencies, builds
the web UI, and runs the JVM and Vitest tests with fake providers and temporary
data directories:

```bash
JAVA_HOME="$(asdf where java)" ./gradlew check
```

The separate local dependency checks use real Tesseract and the installed
CoreML model respectively; they fail rather than silently skip when required
dependencies are unavailable:

```bash
JAVA_HOME="$(asdf where java)" ./gradlew externalTest
JAVA_HOME="$(asdf where java)" ./gradlew gpuIntegrationTest
cd web && npm run check # Svelte/TypeScript diagnostics after Gradle installed dependencies
```

These Gradle checks passed on this Mac on 2026-09-24. The browser was also
used to find a CLI-imported sample through keyword, semantic, and hybrid
search and open its source. See [implementation status](docs/implementation-status.md)
for what remains unverified. For implementation work, read the
[design specification](docs/superpowers/specs/2026-09-20-infoscry-design.md),
[current work plan](docs/superpowers/plans/2026-09-23-infoscry-next-work.md),
and [agent instructions](AGENTS.md).

## License

### InfoScry Noncommercial License 1.0

Copyright © 2026 Patrik Lindefors.

This is a project-specific license. It applies to the original source code and
documentation in this repository (the “Software”). Third-party dependencies,
models, and other third-party materials retain their respective licenses.

**Noncommercial use is free of charge.** Subject to these terms, Patrik
Lindefors grants you a worldwide, non-exclusive, royalty-free license to use,
copy, modify, and distribute the Software solely for noncommercial purposes.
Noncommercial means not intended for or directed toward commercial advantage
or monetary compensation.

**Commercial use requires prior written approval from Patrik Lindefors.** This
includes use in business operations, paid consulting or services, commercial
products, hosted services operated for commercial benefit, and selling or
commercially licensing the Software or modified versions. Making the Software
available at no charge does not make an otherwise commercial use noncommercial.
Contact Patrik Lindefors to obtain a separate written permission or commercial
license before beginning such use. This license grants no commercial rights.

When distributing the Software or modified versions, you must retain this
copyright notice and the complete license text, clearly identify your changes,
and provide recipients with the same noncommercial permissions and commercial
restrictions for the Software and your modifications. You may not remove these
restrictions or grant commercial rights on behalf of Patrik Lindefors.

The Software is provided “as is”, without warranties of any kind, including
merchantability, fitness for a particular purpose, and noninfringement. To the
extent permitted by applicable law, the copyright holder is not liable for any
claim, damage, or other liability arising from the Software or its use.

If you violate these terms, the permissions granted by this license terminate.
Rights you have under applicable law independently of this license remain
unaffected. All rights not expressly granted are reserved.
