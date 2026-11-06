# CLI reference

[Project overview](../README.md) · [Installation](installation.md) · [User guide](usage.md)


The CLI is a client of the running server: every command except `serve`,
`logs` and `admin` sends its request to the server at `INFOSCRY_URL` (default
`http://127.0.0.1:8080`) with the personal access token in `INFOSCRY_TOKEN`.
Create a token in the web interface under Admin → Account and export it:

```bash
export INFOSCRY_TOKEN=isc_...
```

Commands expose `--json` where supported for machine-readable output; it works
before or after the subcommand name, so `infoscry --json collection list` and
`infoscry collection list --json` are equivalent. `serve`, `logs` and `admin`
also take `--data-dir /path` (default `~/.infoscry`), the directory for
managed files, models and logs. `infoscry --help` and `infoscry <command>
--help` print usage. Import paths are paths on the server's machine.

## serve

Start the API and web UI in the foreground (Ctrl-C stops it). `--serve`
is accepted as a top-level alias for `serve`. It listens on `INFOSCRY_BIND`
(`host:port`, default `127.0.0.1:8080`); set `INFOSCRY_PUBLIC_ORIGIN` (for
example `https://archive.example.org`) when browsers reach it through a reverse
proxy. Sessions last `INFOSCRY_SESSION_IDLE_HOURS` idle (default 12) and
`INFOSCRY_SESSION_MAX_DAYS` in all (default 14).

```bash
infoscry serve
infoscry serve --port 9000
infoscry serve --json            # print the URL/port/pid as JSON, no browser
infoscry serve --data-dir /path/to/test-data
```

Options: `--port` (overrides the port in `INFOSCRY_BIND`; `0` asks the OS for
a free port), `--json`, `--data-dir`.

## admin

Account administration on the server's machine. These commands connect to the
database directly with the `INFOSCRY_DATABASE_*` variables, so they work before
the server has ever started and beside a running one.

```bash
infoscry admin create-user --admin anna            # prints a one-time password
infoscry admin create-user --display-name "Erik Lund" erik
infoscry admin reset-password anna                 # new one-time password, ends anna's sessions
infoscry admin list-users
```

A one-time password must be replaced at the first sign-in. Creating a user
that exists changes nothing and says so. Users are otherwise managed in the web
interface under Admin → Users.

Options: `--admin`, `--display-name` (create-user), `--json`, `--data-dir`.

## collection

```bash
infoscry collection list
infoscry collection create Notes --description "Meeting notes"
infoscry collection create Notes --json
```

Subcommands: `list` (shows name, id, OCR languages, description),
`create <name>` with `--description`, `ignore <collection> list|set`, and `prompt` (below).
Options: `--json`.

### collection ignore

Each collection has a list of ignore patterns, written like a `.gitignore`
file, that every import into it applies before anything else (before
`--include` and `--exclude`). A matching file is skipped silently, as an
unsupported file is, and a matching folder is not read at all. A new collection
starts with `.DS_Store`, `._*`, `Thumbs.db`, `desktop.ini`, `~$*`, `*.tmp`,
`.git/` and `node_modules/`. The list can also be edited in Admin, under the
collection's settings.

```bash
infoscry collection ignore Notes list
infoscry collection ignore Notes list --json
infoscry collection ignore Notes set '*.bak' '!keep.bak' 'drafts/'
infoscry collection ignore Notes set --file patterns.txt
infoscry collection ignore Notes set --clear
```

`set` replaces the whole list with the patterns named as arguments and/or read
from `--file` (one per line). Naming nothing is refused; use `--clear` to ignore
nothing. An invalid pattern is refused when saved, and the list is unchanged.

Syntax: `*` and `?` match within one name and never across a `/`; `**` as a
whole path segment matches any number of folders; a trailing `/` matches folders
only, and nothing inside an ignored folder is read or can be re-included; a
leading `!` re-includes what an earlier pattern ignored (the last match wins); a
line starting with `#` is a comment; `\#` and `\!` name a literal `#` or `!`. A
pattern with a `/` at the start or in the middle is matched against the path
below the imported folder; any other pattern matches a name at any depth. A file
named directly on the command line is matched by its own name only. Matching
ignores letter case, and `[abc]` classes are not supported.

An import uses the list as it was when the import was queued: editing the list
afterwards does not change an import that is queued, running or resumed.

### collection prompt

Lists, shows and changes a collection's Ask and Investigate prompts, through the
same service as the web UI. A prompt is named by its name or id (the name is
case-insensitive). `Default` is the shipped prompt every collection can select.
Prompt bodies are never printed in messages.

```bash
infoscry collection prompt list Notes --role ask
infoscry collection prompt create Notes --role ask --name Short --file short.md
infoscry collection prompt show Notes --role ask --prompt Short
infoscry collection prompt update Notes --role ask --prompt Short --file short-v2.md
infoscry collection prompt use Notes --role ask --prompt Short
infoscry collection prompt delete Notes --role ask --prompt Short
```

- `list <collection> [--role ask|investigate]` prints one line per prompt:
  name, version, and `(default)` on the role's collection default.
- `show <collection> --role … [--prompt …]` prints the body alone; `--json` prints
  the prompt. Without `--prompt` it shows the collection default.
- `create <collection> --role … --name <name> --file <path|->` stores a new prompt
  at version 1. `-` reads the body from standard input.
- `update <collection> --role … --prompt … [--name …] [--file <path|->]` changes the
  name, the body, or both. The version grows only when the body changes.
- `delete <collection> --role … --prompt …` removes a prompt. A prompt already gone
  is reported as nothing to delete, not as an error.
- `use <collection> --role … --prompt <name|id|Default>` sets the collection default
  for the role. `Default` resets it.

Options: `--role` (`ask` or `investigate`; required except for `list`), `--prompt`,
`--name`, `--file`, `--json`.

### collection corrections

Lists, previews and changes a collection's standing corrections: words the
reading got wrong, such as `spinners` for the surname `Spinnars`. A rule matches
whole words and ignores case unless `--exact-case` is given; the replacement keeps
the writing it replaces. Saving or removing a rule rewrites every document that
needs it, as new revisions; with no running server the command does that work
here and waits for it, as `import` does. Text is UTF-8, so a rule may be in any
script.

```bash
infoscry collection corrections list Notes
infoscry collection corrections preview Notes spinners Spinnars
infoscry collection corrections add Notes spinners Spinnars
infoscry collection corrections add Notes AB Ab --exact-case
infoscry collection corrections remove Notes spinners
```

- `list <collection>` prints one line per rule: find, replace, `exact` for an
  exact-case rule, and the number of places it changed. `--json` prints the list
  and the correction job.
- `preview <collection> <find> <replace>` prints `N matches in M documents` and a
  few examples, and stores nothing.
- `add <collection> <find> <replace>` prints the preview, saves the rule and applies
  it. Adding the same rule again replaces it. `--id` names the rule; by default the
  id is derived from the word it finds.
- `remove <collection> <find-or-id>` removes a rule; the text returns to what was
  read. A rule already gone is reported as nothing to remove, not as an error.

Use `--` before a word that starts with `-`. Options: `--exact-case`, `--id`,
`--json`.

### collection glossary

Lists and changes a collection's glossary: groups of names with the same
meaning, such as `Sthlm` and `Stockholm`. A keyword search for one term also
finds the others; nothing in the text changes, and nothing runs in the
background.

```bash
infoscry collection glossary list Notes
infoscry collection glossary add Notes Stockholm Sthlm "Sthlms stad"
infoscry collection glossary add Notes Stockholm Sthlm --prefix Sthlm
infoscry collection glossary remove Notes 0b6f2c1e-8d4a-4f3b-9a51-2c7e5d9f1a03
```

- `list <collection>` prints one line per group: its id, then its terms separated
  by tabs, a prefix term followed by `*`. `--json` prints the list.
- `add <collection> <term>...` saves a group of two or more terms. `--prefix <term>`
  (repeatable) marks one of the terms to also match words that start with it.
  `--group <id>` replaces that group; without it, a group with exactly these terms
  is left as it is, so the command can be run again.
- `remove <collection> <group-id>` removes a group; a group already gone succeeds.
- A term another group of the collection has is refused with a non-zero exit.

Options: `--prefix`, `--group`, `--json`.

## import

Import files or directories into a collection as immutable managed copies.
Requires `--collection` and at least one path. The collection must already
exist; create it with `infoscry collection create` first. The paths are on this
machine: each file is uploaded to the server (a folder's files, its whole tree
with `--recursive`; hidden files are left out), and a file whose bytes are
already a document of the collection is reported as a duplicate without being
sent. With `--server-path` the paths are instead on the server, inside one of
its import roots (`INFOSCRY_IMPORT_ROOTS`); a directory is read at its top level
unless `--recursive` is given. With `--wait`, the command blocks until the
import finishes and reports every document (and exits nonzero if any failed).

`--method none|tesseract|surya|llm:<profileId>` selects the reading method; without it
the collection default is used. `none` (No OCR) reads no page: each page keeps the
document's own text, and a page without text is kept without text. The command prints pages and destination before
starting. An external method asks for confirmation; `--yes` confirms the shown
pages without a prompt. EOF or a negative answer aborts before a job is queued.
Repeating the same command returns its active or completed job. After `FAILED`
or `CANCELLED`, it starts a fresh attempt from the confirmed files. Changing
the method, files or preview inputs creates a different request. When an external
page total is unknown, confirmation covers all pages in the listed unchanged
files and no exact total cost is shown.

`--include` imports only files with the listed extensions, and `--exclude`
imports every file except those with the listed extensions. Both take a
comma-separated list (`--include=pdf,docx`); extensions match without regard to
case and with or without the leading dot. The two cannot be given together, and
a refused list exits nonzero before anything is queued. A file the filter
removes is skipped silently: it is not an item and not counted among the files.

```bash
infoscry collection create Notes
infoscry import --collection Notes /path/to/document.pdf
infoscry import --collection Notes --recursive /path/to/dir
infoscry import --collection Notes --wait /path/to/dir /path/to/another.pdf
infoscry import --collection Notes --method tesseract --wait --json /path/to/document.pdf
infoscry import --collection Notes --method llm:profile-id --yes /path/to/document.pdf
infoscry import --collection Notes --recursive --include=pdf,docx /path/to/dir
infoscry import --collection Notes --recursive --exclude=tmp,log /path/to/dir
```

Options: `--collection`, `--server-path`, `--recursive`, `--include`, `--exclude`, `--wait`, `--json`.

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
`CANCELLED`, `NEEDS_TOOL`), `--ocr-only`, `--limit` (default `30`), `--json`.

## ask

Ask one question answered from a single retrieval pass, streaming the answer
with citation markers. Requires a configured LLM profile; see `llm` below.

```bash
infoscry ask --collection Notes --profile my-profile "What does the contract say about renewal?"
infoscry ask --collection Notes --profile my-profile --json "Summarize this document"
```

Options: `--collection`, `--profile`, `--prompt` (the Ask prompt's name or id;
default: the collection default), `--json`.

## jobs

List jobs newest-first, or record a cancellation request for one job.

```bash
infoscry jobs
infoscry jobs --limit 25
infoscry jobs cancel <job-id>
infoscry jobs --json
```

Options: `--limit` (default `100`), `--json`.

## reindex

Rebuild the search index from persisted text. Rebuilds every collection by
default; `--collection` limits it to one. `--wait` blocks until finished.

```bash
infoscry reindex
infoscry reindex --collection Notes
infoscry reindex --wait --json
```

Options: `--collection`, `--wait`, `--json`.

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

`test <name>` records whether the profile supports tool calling, which Investigate
requires. The Admin → LLM profiles "Check tool calling" action runs the same check through the
same service. A switched-off profile is refused before any request is sent.

Subcommands: `list`, `add`, `set-default`, `test <name>`. `add` options:
`--name`, `--provider` (`openai-compatible` or `anthropic`), `--model`,
`--endpoint`, `--api-key-env`, `--context-window` (default `128000`),
`--max-output-tokens` (default `4096`), `--input-price`,
`--output-price`, `--cache-read-price`, `--disabled`, `--json`. `set-default` takes exactly one of `--ask <name>` or
`--investigate <name>`.

## logs

Read the server's structured log stream from the data directory, filtered and
rendered for a terminal. It reads the files directly, so it runs on the
server's machine, also while the server is running.

```bash
infoscry logs
infoscry logs --follow
infoscry logs --level WARN --since 30m
infoscry logs --job <job-id> --component ingest
```

Options: `--follow`, `--level` (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`;
default `INFO`), `--job`, `--component`, `--since` (e.g. `30m`, `12h`, `7d`),
`--data-dir`.
