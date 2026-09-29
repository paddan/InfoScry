# CLI reference

[Project overview](../README.md) · [Installation](installation.md) · [User guide](usage.md)


Commands expose `--json` where supported for machine-readable output and
`--data-dir /path` to work against a specific data directory (default
`~/.infoscry`). Both options work before or after the subcommand name, so
`infoscry --json collection list` and `infoscry collection list --json` are
equivalent. `infoscry --help` and `infoscry <command> --help` print usage.

## serve

Start the local API and web UI in the foreground (Ctrl-C stops it). `--serve`
is accepted as a top-level alias for `serve`.

```bash
infoscry serve
infoscry serve --port 9000
infoscry serve --json            # print the URL/port/pid as JSON, no browser
infoscry serve --data-dir /path/to/test-data
```

Options: `--port` (default `8080`; `0` asks the OS for a free port),
`--json`, `--data-dir`.

## collection

```bash
infoscry collection list
infoscry collection create Notes --description "Meeting notes"
infoscry collection create Notes --json
```

Subcommands: `list` (shows name, id, OCR languages, description) and
`create <name>` with `--description`. Options: `--json`, `--data-dir`.

## import

Import files or directories into a collection as immutable managed copies.
Requires `--collection` and at least one path. The collection must already
exist; create it with `infoscry collection create` first. A directory is read at
its top level only; pass `--recursive` to descend into its subdirectories. With
`--wait`, the command blocks until the import finishes and reports every
document (and exits nonzero if any failed). Without a running server, the
import runs in this process regardless of `--wait`.

```bash
infoscry collection create Notes
infoscry import --collection Notes /path/to/document.pdf
infoscry import --collection Notes --recursive /path/to/dir
infoscry import --collection Notes --wait /path/to/dir /path/to/another.pdf
infoscry import --collection Notes --wait --json /path/to/document.pdf
```

Options: `--collection`, `--recursive`, `--wait`, `--json`, `--data-dir`.

## search

Search one collection. `--collection` is required; the query is the
positional argument. Default mode is hybrid. Hybrid answers a query that shares
no term, or term prefix, with the collection with no results; use
`--mode semantic` for the nearest passages regardless of wording.

```bash
infoscry search --collection Notes "quarterly report"
infoscry search --collection Notes --mode keyword "invoice"
infoscry search --collection Notes --mode semantic "revenue trend"
infoscry search --collection Notes --limit 10 --media-type application/pdf "budget"
infoscry search --collection Notes --path reports/ --text "smith" --ocr-only "scan"
infoscry search --collection Notes --from 2026-01-01 --until 2026-03-01 "note"
infoscry search --collection Notes --status COMPLETE --json "summary"
```

`--from` and `--until` accept an ISO date or an ISO instant with a time zone. A date is inclusive for
the whole day, so `--until 2026-03-01` includes documents imported through the final millisecond of
March 1. Blank optional path, text, and date values are ignored.

Options: `--collection`, `--mode` (`keyword`, `semantic`, `hybrid`),
`--media-type` (repeatable), `--path`, `--text`, `--from`, `--until`,
`--status` (repeatable; `QUEUED`, `COPYING`, `EXTRACTING`, `OCR`, `CHUNKING`,
`EMBEDDING`, `INDEXING`, `COMPLETE`, `COMPLETE_WITH_WARNINGS`, `FAILED`,
`CANCELLED`, `NEEDS_TOOL`), `--ocr-only`, `--limit` (default `30`), `--json`,
`--data-dir`.

## ask

Ask one question answered from a single retrieval pass, streaming the answer
with citation markers. Requires a configured LLM profile; see `llm` below.

```bash
infoscry ask --collection Notes --profile my-profile "What does the contract say about renewal?"
infoscry ask --collection Notes --profile my-profile --json "Summarize this document"
```

Options: `--collection`, `--profile`, `--json`, `--data-dir`.

## jobs

List jobs newest-first, or record a cancellation request for one job.

```bash
infoscry jobs
infoscry jobs --limit 25
infoscry jobs cancel <job-id>
infoscry jobs --json
```

Options: `--limit` (default `100`), `--json`, `--data-dir`.

## reindex

Rebuild the search index from persisted text. Rebuilds every collection by
default; `--collection` limits it to one. `--wait` blocks until finished.

```bash
infoscry reindex
infoscry reindex --collection Notes
infoscry reindex --wait --json
```

Options: `--collection`, `--wait`, `--json`, `--data-dir`.

## llm

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

## logs

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
