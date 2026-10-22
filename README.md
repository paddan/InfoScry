# InfoScry

InfoScry is a local, single-user application for searching a document archive
and asking questions with citations back to the sources. It provides a web
reader and a command-line interface.

## What you can do

- Import files and folders into collections without changing the originals. Follow both
  file progress and the current document’s page progress during processing.
- Extract text from PDFs, images, Office documents, text files and e-books,
  including OCR for scanned material.
- Find passages with keyword, semantic or hybrid search.
- Use **Ask** for answers from one search, or **Investigate** for follow-up
  conversations that search and read sources over several steps, with Low, Medium,
  High or Extra high effort and optional per-profile overrides.
- Open cited source passages and manage collections, running jobs and LLM
  profiles in the English web interface.
- Correct words the reading got wrong (a misread surname, say) across a whole
  collection with a standing list of corrections; the reading is kept underneath.

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
| [Using InfoScry](docs/usage.md) | Collections, import, search, prompts, Ask, Investigate and LLM profiles |
| [Development](docs/development.md) | Project layout, build tasks, tests and contribution workflow |
| [Technical reference](docs/technical-reference.md) | Architecture, storage, embeddings, security and LLM contracts |
| [CLI reference](docs/cli.md) | Commands, options and examples |
| [Implementation status](docs/implementation-status.md) | Implemented features, recorded verification and open checks |

## Project status

Implementation is in progress. The backend, CLI and web reader exist;
Collections, Search, Ask and Investigate have browser acceptance against local test doubles.
The [OCR workflow](docs/ocr-workflow.md) adds a
confirmed reading method for every import and rescan (including No OCR, which uses the
document's own text), reads selected pages and publishes
a complete reading automatically while keeping previous text in history. Its browser
gate passes against local test doubles. Remaining product choices are listed in
[docs/tickets/follow-ups](docs/tickets/follow-ups/STATUS.md). Document paging, selective
rescanning and historical Ask citations have browser coverage; the format-specific
source-viewer finish line remains open. See [implementation status](docs/implementation-status.md)
for recorded checks.

Structured page reading is implemented: Surya and LLM OCR pages keep their headings,
lists, tables and formulas for the reader, Ask and chunking. The selectable-PDF text
layer follows the structure's reading order; it has no cell positions yet, as exact
geometry is sub-project 2. The [design](docs/specs/2026-10-18-structured-page-reading-design.md) and
[OCR workflow](docs/ocr-workflow.md#structured-page-reading-2026-10-18) describe the
contract. The real Surya table gate passed on a rendered table page on this Mac;
the browser scenario runs against fake engines. Existing readings gain structure on
their next rescan.

The workspace and page review are implemented: common views use the available
width, and the compact document list has live filename search, inline details and session view memory.
The adaptive source view preserves context and supports narrow-screen Back.
Ask and Investigate keep conversation history closed in the left panel, with
New conversation always available.
Ask and Investigate choose a per-collection prompt (Default, or one stored in Admin →
Collections); the Ask prompt flow has browser coverage in `DocumentBrowserAcceptanceTest`.
PDF/image review shows exact-revision images beside extracted text, including
historical PDF page and image citations. Live review can mark pages and confirm an exact
page set and reading method for rescanning. Stopped selected-page runs can resume
from committed pages. Integrated browser verification passed 42 scenarios; the
latest accumulated check passed 1,838 JVM and 474 frontend tests.
The latest Investigate browser run passed all nine scenarios against local fake
providers. [Effort contracts](docs/investigate-effort.md) describe profile overrides
and the fresh-database prerequisite; no existing archive was reset or migrated.

Patrik confirmed the remaining OCR/PDF checks complete on 2026-10-11,
including real-provider/CoreML workflow and native PDF-reader checks. This owner
confirmation is recorded separately from automated test results in
[implementation status](docs/implementation-status.md).

The [selectable-PDF design](docs/selectable-pdf.md) is
implemented, with [remaining integration work](docs/tickets/selectable-pdf/STATUS.md):
a revision-bound PDF copy places the published reading invisibly
over its page image so it can be selected and copied in an ordinary PDF reader.
Generation is durable, repeatable and recoverable; a selected-page rescan or
Restore changes only its own export and keeps historical exports exact; archive
search continues through Lucene, unaffected. Automated fake-provider tests cover
this end to end, including independent PDFBox byte inspection of the produced
files and a real-browser acceptance scenario. Original and managed PDF files are
never changed. A document's plain first import does not yet
automatically queue generation (use **Create PDF with selectable text**
explicitly). The remaining OCR/PDF checks are confirmed complete by the owner.
See [implementation status](docs/implementation-status.md).

Collection corrections are implemented: a standing per-collection list rewrites
misread words in every document's published text, now and on later imports, rescans
and restores, and keeps each corrected page's reading so a removed rule brings it
back. Rules match whole words in any script (UTF-8 throughout), ignore case unless
marked exact, and keep the writing they replace. See the
[design](docs/specs/2026-10-22-collection-corrections-design.md) and
[usage](docs/usage.md#correct-misread-words). `CorrectionsBrowserAcceptanceTest`
covers the reader-to-settings flow against fake embeddings; synonyms and
abbreviations are a separate, later glossary.

The project targets local use on macOS arm64. CI, distributable packaging and
public or multi-user hosting are outside the current scope.

## License

Noncommercial use is free. Commercial use requires prior written approval
from Patrik Lindefors. See the complete
[InfoScry Noncommercial License 1.0](LICENSE.md).
