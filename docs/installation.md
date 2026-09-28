# Installation and first start

[Project overview](../README.md) · [User guide](usage.md) · [Development](development.md)

## Requirements

| Requirement | Purpose |
|---|---|
| macOS arm64 with an Apple GPU | Supported local runtime; CoreML embeddings |
| JDK 25 | Build and run the application |
| Node.js and npm | Build the web interface |
| Tesseract and the OCR languages you use | OCR runtime dependency |
| Calibre (optional) | E-book formats that need conversion |

Linux/CUDA, Intel Macs and Windows are outside the current runtime scope.
Embeddings require GPU execution; there is no silent CPU-only fallback.
Diagnostics, source viewing and existing keyword search remain available if
GPU initialization fails.

## Build from source

Open a terminal in your local checkout of InfoScry. Set `JAVA_HOME` to your
JDK 25 installation; if you use asdf, run:

```bash
export JAVA_HOME="$(asdf where java)"
```

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
- **No graphical session:** the web file picker offers manual path entry.
