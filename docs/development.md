# Development

[Project overview](../README.md) · [Installation](installation.md) · [Technical reference](technical-reference.md)

## Set up the checkout

Follow [installation](installation.md) for JDK 25, Node.js/npm and the local
runtime dependencies. Work from the repository root. Gradle uses the checked-in
wrapper and builds one backend module; preserve the pinned dependencies.

Set `JAVA_HOME` to JDK 25. With asdf:

```bash
export JAVA_HOME="$(asdf where java)"
```

## Project layout

| Path | Purpose |
|---|---|
| `src/main/kotlin/infoscry/` | Services, HTTP routes and CLI commands |
| `src/main/resources/` | Database migrations, prompts and provider metadata |
| `src/test/kotlin/infoscry/` | JVM tests and acceptance harnesses |
| `src/test/resources/fixtures/` | Redistributable test documents |
| `web/src/` | Svelte reader and frontend tests |
| `web/e2e/` | Browser acceptance scripts |
| `models/embedding-model.json` | Pinned embedding artifacts and runtime settings |
| `docs/specs/` | Design contracts |
| `docs/tickets/` | Implementation slices and their verification reports |

## Build and run

```bash
./gradlew installDist
build/install/infoscry/bin/infoscry serve
```

`installDist` installs frontend dependencies with `npm ci`, builds static assets
and packages them with the backend. Rebuild after changing the backend or UI;
a running server must be restarted to use the rebuilt application.

Install the model separately with `./gradlew embeddingModel` when testing actual
embeddings. For a disposable archive, use the same `-PdataDir` for model tasks
and `--data-dir` for the app, as shown in [installation](installation.md#use-a-separate-archive).

## Normal verification

```bash
./gradlew check
```

The normal suite builds the frontend and runs JVM and Vitest tests with fake
providers, redistributable fixtures and temporary data directories. It excludes
tests tagged `external`, `model` or `gpu`. Initial dependency installation can
require network access; tests do not need a real LLM provider.

For focused backend work:

```bash
./gradlew test --tests infoscry.server.DocumentRoutesTest
```

For frontend work, after dependencies have been installed:

```bash
cd web
npm test -- --run
npm run check
npm run build
```

`check` runs Vitest through Gradle; run `npm run check` explicitly for
Svelte/TypeScript diagnostics.

## Hardware and browser checks

```bash
./gradlew externalTest
./gradlew gpuIntegrationTest
```

`externalTest` includes real Tesseract OCR and Chromium browser acceptance for
Investigate and Collections. Browser tests use a local server, temporary archive
and local fake provider or extraction pipeline. Playwright and its Chromium
runtime must be available to run them; the acceptance harnesses report a missing
browser dependency as a failure.

`gpuIntegrationTest` loads the real pinned model through CoreML on the local
Mac. For another model directory:

```bash
./gradlew gpuIntegrationTest -PdataDir=/absolute/path/to/test-data
```

These gates fail rather than silently skip when their required dependencies
are unavailable. Fake browser tests do not prove real OCR/GPU behavior within
that workflow. The native macOS picker and human keyboard/screen-reader checks
remain manual gates. See [implementation status](implementation-status.md).

## Change workflow

Read [AGENTS.md](../AGENTS.md) and the relevant spec and ticket before changing
behavior. Implement the requested slice and its dependencies, preserve unrelated
changes and report conflicts between contracts explicitly.

For code changes, run meaningful failing tests first, focused passing tests
next and the applicable accumulated gate last. Documentation-only changes need
link, consistency and diff checks rather than application tests.

Use temporary test archives and fake providers for normal tests. Do not send
private documents to external endpoints as test data. Only claim GPU-dependent
behavior after a real local CoreML run.

Keep the project overview concise and put procedures and technical contracts
in these guides or the relevant spec. Update
[implementation status](implementation-status.md) with verified results and
remaining gates; plans and unchecked criteria are not completion evidence.
CI, release automation and distributable packaging are outside this project's
current scope.
