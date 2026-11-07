# Installation and first start

[Project overview](../README.md) · [User guide](usage.md) · [Development](development.md)

## Requirements

| Requirement | Purpose |
|---|---|
| macOS arm64 with an Apple GPU | Supported local runtime; CoreML embeddings |
| JDK 25 | Build and run the application |
| Docker Desktop or Podman | Runs PostgreSQL from `compose.yaml`; the test suite starts its own container |
| Node.js 24 and npm | Build and test the web interface; the setup below uses Node 24 |
| Tesseract and the OCR languages you use | OCR runtime dependency |
| Surya and llama.cpp (optional) | Local page reading with the Surya model instead of Tesseract |
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
are installed by the build; ONNX Runtime does not need a separate system
installations. CoreML is supplied by macOS.

### PostgreSQL

InfoScry keeps its state in PostgreSQL, which `compose.yaml` runs in a container;
InfoScry itself runs on the Mac so it can use the GPU. Install
[Docker Desktop](https://www.docker.com/products/docker-desktop/) or Podman with a
Compose provider (`brew install podman podman-compose`), then from the checkout:

```bash
cp .env.example .env
```

Set `INFOSCRY_DATABASE_PASSWORD` in `.env`, then start the database:

```bash
docker compose up -d
```

With Podman, run `podman machine start` once and use `podman compose up -d`.
InfoScry reads the same variables from its environment, so export them in the
shell that starts it:

```bash
set -a; source .env; set +a
```

The database survives restarts in the `pgdata` volume. Archives created by
earlier SQLite-based builds are not read; import the collections again.

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

### Surya for local page reading (optional)

Surya is the second local OCR engine. It reads a whole page with one
vision-language model call instead of line by line, which is better on
irregular letterforms and layouts, and it needs no cloud service. Install it
only if you select Surya as an engine; Tesseract remains the default and the two
are alternatives, never a fallback chain.

The runtime has two halves: a Python environment with the `surya-ocr` package,
and `llama-server` from [llama.cpp](https://formulae.brew.sh/formula/llama.cpp),
which serves the model on the Apple GPU through Metal. Install both:

```bash
brew install llama.cpp uv
uv venv --python 3.12 "$HOME/.local/share/infoscry/surya-venv"
uv pip install --python "$HOME/.local/share/infoscry/surya-venv/bin/python" 'surya-ocr==0.22.1'
```

Only the Python half is pinned, and `surya-ocr==0.22.1` in a Python 3.12
environment is what this project is verified against. Homebrew's `llama.cpp`
is **not** pinned: the formula moves with each `brew upgrade`. That is why
InfoScry reads the `llama-server` build, the package version, the backend and
the cached weights' revisions and sizes into the identity it fingerprints a
reading with, so a page read by another build is read again instead of being
reused under a runtime that did not read it.

`--python 3.12` is deliberate: the PyTorch wheels this package needs are not
built for Homebrew's newer Python, so a 3.14 environment installs a package that
cannot import. The commands pin `surya-ocr==0.22.1` because the reading a
collection receives is fingerprinted by the runtime and model that produced it.

Check the installation before starting InfoScry:

```bash
command -v llama-server && llama-server --version
"$HOME/.local/share/infoscry/surya-venv/bin/python" --version
"$HOME/.local/share/infoscry/surya-venv/bin/python" \
  -c "import importlib.metadata as m; print(m.version('surya-ocr'))"
```

`llama-server --version` writes to stderr; a verified installation answers with
version `0.5.0`, build `11146`, commit `7fe450e19` on macOS arm64, though a later
Homebrew upgrade answers with a build of its own. Python should report `3.12.x`
and the last command `0.22.1`.

On the first page it reads, the runtime downloads the weights from Hugging Face
into `~/.cache/huggingface/hub/models--datalab-to--surya-ocr-2-gguf`:
`surya-2.gguf` (1,266,400,864 bytes) and `surya-2-mmproj.gguf`
(204,986,688 bytes), about 1.36 GiB together. Nothing else is fetched at run
time; check that the cache directory exists after reading one page. The server
holds the model in memory while InfoScry runs and is stopped with the process
that started it.

InfoScry finds this runtime by itself when it is installed at the path above
and the application is started from the checkout. To point it somewhere else,
set these environment variables in the terminal that starts the server:

| Variable | Meaning | Default |
|---|---|---|
| `INFOSCRY_SURYA_PYTHON` | Interpreter with `surya-ocr` installed | `~/.local/share/infoscry/surya-venv/bin/python` |
| `INFOSCRY_SURYA_WORKER` | The worker script InfoScry spawns | `scripts/ocr/surya_worker.py`, relative to the working directory |
| `LLAMA_CPP_BINARY` | `llama-server` when it is not on `PATH` (read by Surya) | `llama-server` on `PATH` |

A collection that selects Surya on a machine where the runtime is missing,
unusable or pointing at the wrong interpreter is refused with `NEEDS_SURYA`,
`NEEDS_LLAMA_CPP`, `NEEDS_SURYA_MODEL` or `SURYA_START_FAILED` and the install
line that fixes it: the document ends in `NEEDS_TOOL` with that line beside the
code, so a document waiting for something to be installed is not reported as a
broken one. Nothing is read with Tesseract instead, because a reading is
attributed to the engine the collection selected.

**Licences.** The `surya-ocr` package is Apache-2.0. The model weights use a
modified AI Pubs Open Rail-M licence: free for research, personal use and
startups under $5M funding/revenue, with broader commercial self-hosting needing
a licence from the model authors
([model card](https://huggingface.co/datalab-to/surya-ocr-2-gguf),
[pricing](https://www.datalab.to/pricing)). llama.cpp itself is MIT licensed.

Surya's runtime is separate from the embedding requirement under
[Requirements](#requirements): embeddings still need CoreML on the Apple GPU,
while Surya reads pages through llama.cpp. Installing one does not satisfy the
other.

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

## Create the first administrator and start

Every installation has accounts. With Postgres running (`docker compose up -d`),
create the first administrator; the command prints a one-time password:

```bash
infoscry admin create-user --admin anna
infoscry serve
```

`serve` and `--serve` start the same foreground server and open the web UI in
your default browser. If opening the browser fails, use the URL printed in the
terminal, normally <http://127.0.0.1:8080>. Sign in with the username and the
one-time password, then choose your own password (at least 12 characters).
Press Ctrl-C to stop the server. `serve --json` prints connection information
without opening a browser. Add other people under **Admin → Users**.

### Start and stop everything with one command

`server.sh` in the repository root does the steps above in the background: it
loads `.env` (creating it from `.env.example` on first use), starts Postgres
with Docker or Podman and waits until it is ready, builds the app if no build
exists, and starts `infoscry serve`. Its output goes to `.run/server.log`.

```bash
./server.sh start
```

```bash
./server.sh stop
```

`stop` stops the server and then the database; the data is kept in the
Compose volume. `status`, `restart`, `logs` and `admin <command>` (for example
`./server.sh admin create-user --admin anna`, with the database settings from
`.env`) are also available. `start --build` rebuilds first, and options after
`start`, such as `--port 9000` or `--data-dir <path>`, go to `infoscry serve`.

## Import a first document

Create a collection and import files from **Admin → Collections**; see the
[user guide](usage.md). To use the command line, create a personal access token
under **Admin → Account** and export it in the shell, then:

```bash
export INFOSCRY_TOKEN=isc_...
infoscry collection create Notes
infoscry import --collection Notes --wait /absolute/path/to/document.pdf
```

The CLI sends each request to the running server (`INFOSCRY_URL`, default
<http://127.0.0.1:8080>) and uploads the files it imports.

Imports come only from the person's own computer: the web reader and the CLI
upload each file, and the server never reads its own folders. Uploaded files
wait in `<data directory>/uploads` and are removed a week later; an upload may
be at most `INFOSCRY_UPLOAD_MAX_BYTES` (default 4 GiB).

### Choose where imported documents are kept

Imported documents are kept in the library, one directory per collection
(`<library>/<collection id>/`). The library is `<data directory>/library`
unless `INFOSCRY_LIBRARY_DIR` names another absolute directory on the server,
such as a larger disk. Set it in `.env` before the first import:

```bash
INFOSCRY_LIBRARY_DIR=/Volumes/Archive/InfoScry
```

The folder that holds it must exist; the server refuses to start rather than
write elsewhere when the disk is not connected. Changing the variable later
does not move documents already imported: move the library's contents to the
new place while the server is stopped. Back up both the Postgres database and
the library.
For a disposable sample, use `src/test/resources/fixtures/sample.txt` from the
repository root. Import keeps an immutable managed copy and leaves the original
unchanged.

## Serve other machines

By default the server listens on `127.0.0.1:8080`. To serve other machines, put
it behind a reverse proxy that terminates TLS, and set where it listens and the
origin browsers use:

```bash
export INFOSCRY_BIND=127.0.0.1:8080
export INFOSCRY_PUBLIC_ORIGIN=https://archive.example.org
```

With an HTTPS origin the session cookie is `Secure`. Requests that name another
host are refused.

## Start the server at login

A launchd agent can start the server when you sign in to the Mac and start it
again if it exits. Postgres restarts on its own (`restart: unless-stopped`) once
Docker or the Podman machine runs. Save this as
`~/Library/LaunchAgents/org.infoscry.server.plist`, replacing the paths and the
password with your own:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>org.infoscry.server</string>
  <key>ProgramArguments</key>
  <array>
    <string>/absolute/path/to/infoscry/build/install/infoscry/bin/infoscry</string>
    <string>serve</string>
    <string>--json</string>
  </array>
  <key>WorkingDirectory</key><string>/absolute/path/to/infoscry</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>JAVA_HOME</key><string>/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home</string>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/bin:/bin</string>
    <key>INFOSCRY_DATABASE_PASSWORD</key><string>change-me</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/infoscry-server.out</string>
  <key>StandardErrorPath</key><string>/tmp/infoscry-server.err</string>
</dict>
</plist>
```

`--json` keeps the server from opening a browser. `PATH` must reach `tesseract`,
`ebook-convert` and `llama-server` if you use them, and the working directory
is the checkout so Surya finds its worker script. The file holds the database
password, so keep it readable only by you (`chmod 600`). Load it with:

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/org.infoscry.server.plist
```

Stop it with `launchctl bootout gui/$(id -u)/org.infoscry.server`.

## Use a separate archive

Use one data-directory path consistently for model installation and the server,
and a database of its own (`INFOSCRY_DATABASE_URL`):

```bash
./gradlew embeddingModel -PdataDir=/absolute/path/to/test-data
infoscry serve --data-dir /absolute/path/to/test-data
```

This installs a separate model copy. The normal data directory remains `~/.infoscry`.

## Configure answers

Search does not need an LLM profile. Ask and Investigate do: configure an
endpoint in **Admin → LLM profiles** or use the
[CLI profile commands](cli.md#llm). InfoScry calls the configured endpoint;
it does not start or host an LLM.

Supply any required API key through an environment variable in the server's
launch environment. Profiles store the variable's name, never its value.
The CLI profile commands go through the running server, like Admin does.

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
