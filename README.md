# InfoScry

InfoScry is a local, single-user application for searching a document archive
and asking questions with citations back to the sources. It provides a web
reader and a command-line interface.

## What you can do

- Import files and folders into collections without changing the originals.
- Extract text from PDFs, images, Office documents, text files and e-books,
  including OCR for scanned material.
- Find passages with keyword, semantic or hybrid search.
- Use **Ask** for answers from one search, or **Investigate** for follow-up
  conversations that search and read sources over several steps.
- Open cited source passages and manage collections and LLM profiles in the
  English web interface.

## Get started

InfoScry is built and run locally on an Apple Silicon Mac. Start with the
[installation guide](docs/installation.md), then follow the
[user guide](docs/usage.md) to import documents and search your archive.
Commands for installing Java, Node.js, Tesseract and Calibre are under
[local dependencies](docs/installation.md#install-local-dependencies).
[asdf setup](docs/asdf.md) covers project-specific Java and Node.js versions.

Documents and archive data stay on your machine. Ask and Investigate send
questions and selected source passages to the LLM endpoint you configure,
which may be an external service.

## Documentation

| Guide | Contents |
|---|---|
| [Installation](docs/installation.md) | Requirements, local build, model setup and first start |
| [Using InfoScry](docs/usage.md) | Collections, import, search, Ask, Investigate and LLM profiles |
| [Development](docs/development.md) | Project layout, build tasks, tests and contribution workflow |
| [Technical reference](docs/technical-reference.md) | Architecture, storage, embeddings, security and LLM contracts |
| [CLI reference](docs/cli.md) | Commands, options and examples |
| [Implementation status](docs/implementation-status.md) | Implemented features, recorded verification and open checks |

## Project status

Implementation is in progress. The backend, CLI and web reader exist;
Collections, Search and Investigate have browser acceptance against local test doubles.
The [OCR workflow redesign](docs/specs/2026-10-08-ocr-workflow-redesign.md) adds a
confirmed reading method for every import and rescan, reads every page and publishes
a complete reading automatically while keeping previous text in history. Its new
browser gate passes against local test doubles. Ask browser acceptance and the format-specific
source-viewer finish line remain open. See [implementation status](docs/implementation-status.md)
for recorded checks.

Real OCR providers and real CoreML are separate manual gates. Before treating the
redesigned OCR workflow as verified on a real archive, check local Tesseract/Surya,
a confirmed external OCR profile, page totals and cost/destination summaries,
cancellation and restart, preserved old text on failure, and History → Restore with
real GPU embeddings. Automated fake-provider tests do not establish OCR quality.

The project targets local use on macOS arm64. CI, distributable packaging and
public or multi-user hosting are outside the current scope.

## License

Noncommercial use is free. Commercial use requires prior written approval
from Patrik Lindefors. See the complete
[InfoScry Noncommercial License 1.0](LICENSE.md).
