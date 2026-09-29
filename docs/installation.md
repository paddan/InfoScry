# Installation and first start

[Project overview](../README.md) · [User guide](usage.md) · [Development](development.md)

## Requirements

| Requirement | Purpose |
|---|---|
| macOS arm64 with an Apple GPU | Supported local runtime; CoreML embeddings |
| JDK 25 | Build and run the application |
| Node.js 24 and npm | Build and test the web interface; the setup below uses Node 24 |
| Tesseract and the OCR languages you use | OCR runtime dependency |
| Calibre (optional) | E-book formats that need conversion |

Linux/CUDA, Intel Macs and Windows are outside the current runtime scope.
Embeddings require GPU execution; there is no silent CPU-only fallback.
Diagnostics, source viewing and existing keyword search remain available if
GPU initialization fails.

## Install local dependencies

The commands below use [Homebrew](https://brew.sh/). If `brew` is not installed,
follow its official installation instructions first and apply the printed
shell setup. If you already manage Java or Node with another tool, keep that
setup and check its versions instead of installing a second copy.

### Java and Node.js

Choose either the [asdf setup](asdf.md) for project-specific versions or the
direct Homebrew installation below. The checkout already pins Java in
`.tool-versions`; the asdf guide also explains selecting Node 24 and `JAVA_HOME`.

Install [JDK 25](https://formulae.brew.sh/formula/openjdk@25) and
[Node.js 24](https://formulae.brew.sh/formula/node@24), which includes npm:

```bash
brew install openjdk@25 node@24
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix node@24)/bin:$PATH"
java -version
node --version
npm --version
```

Check that Java reports version 25 and Node reports version 24. These exports
apply to the current terminal. Add them to `~/.zshrc` if you want this setup in
future interactive sessions. If using asdf, follow the
[asdf guide](asdf.md) instead of these Homebrew runtime exports.

Gradle itself is provided by `./gradlew`. Java libraries and frontend packages
are installed by the build; SQLite, Lucene and ONNX Runtime do not need separate
system installations. CoreML is supplied by macOS.

### Tesseract and OCR language data

Install [Tesseract](https://formulae.brew.sh/formula/tesseract) and
[additional language data](https://formulae.brew.sh/formula/tesseract-lang):

```bash
brew install tesseract tesseract-lang
command -v tesseract
tesseract --version
tesseract --list-langs
```

Homebrew's base Tesseract package includes English (`eng`) and orientation data
(`osd`). `tesseract-lang` adds other languages, including Swedish (`swe`). Check
that `eng` and `swe` appear in the language list if you use both.

Collections default to `eng`. For Swedish and English documents, save
`swe+eng` under **Admin → Collections → OCR languages** before importing or
retrying. Saving languages alone does not reprocess existing documents.

### Calibre for e-book conversion

Install [Calibre](https://formulae.brew.sh/cask/calibre) when importing formats
that need conversion, such as MOBI/AZW-family or legacy e-books:

```bash
brew install --cask calibre
command -v ebook-convert
ebook-convert --version
```

InfoScry uses Calibre's `ebook-convert` command, not its graphical library.
Homebrew exposes that command in its `bin` directory. EPUB and FictionBook
formats are read directly by InfoScry and do not require Calibre conversion.

If you installed Calibre from its macOS download instead, its command-line
tools are inside the app bundle. Make them available in the terminal that will
start InfoScry:

```bash
export PATH="/Applications/calibre.app/Contents/MacOS:$PATH"
command -v ebook-convert
ebook-convert --version
```

Adjust the path if the app is installed elsewhere; add the export to `~/.zshrc`
to keep it for future terminal sessions. See the
[Calibre command-line documentation](https://manual.calibre-ebook.com/generated/en/cli-index.html)
for the macOS bundle layout.

### Make tools visible to the server

InfoScry launches `tesseract` and `ebook-convert` by name using the process's
`PATH`. Start the server from the terminal where the checks above succeed.
After changing `PATH`, stop the server with Ctrl-C and start it again from
the updated terminal; an existing process keeps its old environment.

The embedding weights are another required local dependency for semantic and
hybrid search: install them with the `embeddingModel` task below. An LLM is
separate and needed only for Ask and Investigate; see
[Configure answers](#configure-answers).

## Build from source

Open a terminal in your local checkout of InfoScry with the dependency setup
above active and `JAVA_HOME` pointing to JDK 25.

Build the application and install the pinned embedding model:

```bash
./gradlew installDist
./gradlew embeddingModel
```

`installDist` builds the backend and static web UI and writes the runnable app
to `build/install/infoscry/`. The model task downloads about 1.1 GB, verifies
checksums and installs into `~/.infoscry` by default. It is separate from the
application build and is needed for initial model setup.

The first build and model download need network access. Rebuild with
`installDist` after code changes. Running the built app requires JDK 25 but
does not require Gradle or Node.js.

## Make the launcher available

From the repository root, add the local build to this terminal's `PATH`:

```bash
export PATH="$PWD/build/install/infoscry/bin:$PATH"
infoscry --help
```

Alternatively, run `build/install/infoscry/bin/infoscry` directly. To keep the
short command in future sessions, add the absolute build `bin` directory to
your shell's `PATH`. There is no separate installer or system-wide installation.

## Import a first document and start

A new archive has no collections. Create one, import a document and start the
server:

```bash
infoscry collection create Notes
infoscry import --collection Notes --wait /absolute/path/to/document.pdf
infoscry serve
```

Replace the document path with your own. For a disposable sample, use
`src/test/resources/fixtures/sample.txt` from the repository root; it still adds
a document to the chosen archive. Import keeps an immutable managed copy and
leaves the original unchanged.

`serve` and `--serve` start the same foreground server and open the web UI in
your default browser. If opening the browser fails, use the URL printed in the
terminal, normally <http://127.0.0.1:8080>. Press Ctrl-C to stop the server.
`serve --json` prints connection information without opening a browser.

You can also start the server first and create a collection and import files
from **Admin → Collections**. See the [user guide](usage.md).

## Use a separate archive

Use one data-directory path consistently for model installation, collections,
imports and the server:

```bash
./gradlew embeddingModel -PdataDir=/absolute/path/to/test-data
infoscry collection create Notes --data-dir /absolute/path/to/test-data
infoscry import --collection Notes --wait --data-dir /absolute/path/to/test-data /absolute/path/to/document.pdf
infoscry serve --data-dir /absolute/path/to/test-data
```

This installs a separate model copy. The normal archive remains `~/.infoscry`.

## Configure answers

Search does not need an LLM profile. Ask and Investigate do: configure an
endpoint in **Admin → LLM profiles** or use the
[CLI profile commands](cli.md#llm). InfoScry calls the configured endpoint;
it does not start or host an LLM.

Supply any required API key through an environment variable in the server's
launch environment. Profiles store the variable's name, never its value.
Profile-management CLI commands require the server for that archive to be
stopped; use Admin while the server is running.

## Common startup issues

- **Port in use:** choose another port with `infoscry serve --port 9000`, or
  use `--port 0` to let the OS choose one. Open the printed URL.
- **Embedding model unavailable:** run `embeddingModel` for the same data
  directory as the application, then check the
  [GPU verification command](development.md#hardware-and-browser-checks).
- **OCR tool or language unavailable:** check that Tesseract and the requested
  language data are installed. Save collection OCR languages before retrying.
- **E-book converter unavailable:** check `command -v ebook-convert` and
  `ebook-convert --version` in the server's launch terminal. See
  [Calibre setup](#calibre-for-e-book-conversion), then restart the server and retry.
- **No graphical session:** the web file picker offers manual path entry.
