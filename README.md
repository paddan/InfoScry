# InfoScry

InfoScry is a planned local, single-user application for importing documents,
searching their contents, and asking questions with citations to exact source
locations.

**Status: design and implementation planning.** The repository does not yet
contain application code, a build wrapper, tests, or an installable release.
The capabilities and requirements below describe the intended first release.

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
- Inspect jobs, failures, source material, model usage, and logs through an
  English web interface and a CLI.

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
| Linux x86_64 | NVIDIA GPU through CUDA | E5 O4 ONNX export |

The CUDA-optimized O4 export is not the Mac model. Exact OS, driver, native
runtime, and device compatibility must be validated on real hardware before
release. Intel Macs and non-CUDA Linux GPUs are outside the initial matrix.
Diagnostics, source viewing, and existing keyword search are designed to remain
available if GPU readiness fails.

Tesseract is a mandatory runtime dependency; Calibre is optional for formats
that require conversion. Releases will bundle Java 25, static web assets, and
the matching model and native runtime. Development will require JDK 25 and
Node.js/npm; Node.js will not be needed to run a release. Pinned dependency
versions are maintained in the implementation plan.

## Privacy and boundaries

Originals, extracted text, embeddings, indexes, and saved conversations stay
local. Ask and Investigate send prompts, questions, and selected evidence with
citation metadata to the configured OpenAI-compatible or Anthropic endpoint;
their use is not necessarily offline. API keys come from environment variables
and are not stored in profiles or exposed to the browser.

The server binds only to `127.0.0.1`. Imported documents are untrusted evidence;
LLM tools cannot access arbitrary paths, switch collections, or browse the web.
The first release excludes multi-user/public hosting, Windows, a knowledge
graph, DRM bypass, and an external MCP server.

## Development

Start with these documents:

1. [Design specification](docs/superpowers/specs/2026-09-20-infoscry-design.md)
   — product behavior, architecture, security boundaries, and acceptance criteria.
2. [Implementation plan](docs/superpowers/plans/2026-09-20-infoscry-implementation.md)
   — 28 tasks, dependency baseline, interfaces, tests, and phase gates.
3. [Agent instructions](AGENTS.md) — repository workflow and implementation invariants.

Build and CLI commands in the plan are instructions for future implementation,
not commands that work in the current checkout. Once bootstrap is implemented,
this README will document verified setup and development commands. Normal CI
will use fakes; real GPU execution on both platforms is a separate mandatory
validation gate.

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
