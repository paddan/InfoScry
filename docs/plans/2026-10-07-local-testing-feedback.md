# Local testing feedback — implementation plan

**Source:** the owner's first local test of the OCR work on 2026-10-07 (Admin → OCR profiles, Admin →
Collections, Investigate). **Tickets:** [docs/tickets/local-testing-feedback](../tickets/local-testing-feedback/STATUS.md).

## Goal

Fix what blocked the local test and add the import controls the test showed are missing, without changing the
OCR review model or the v1 scope in [AGENTS.md](../../AGENTS.md).

## Findings and how they are handled

| Finding | Kind | Ticket |
|---|---|---|
| Investigate does nothing: `the profile '…' has not been measured for tool calling` | Bug: the measurement exists only as `infoscry llm test`, and the reader sees no usable error | [01](../tickets/local-testing-feedback/01-measure-tool-calling-in-admin.md) |
| Import history shows State `Complete` with Stage `Queued` | Bug | [02](../tickets/local-testing-feedback/02-import-history-final-stage.md) |
| Revision history does not say which OCR method an import used | Gap: history reads engine and model only from rescan operations | [03](../tickets/local-testing-feedback/03-import-reading-in-history.md) |
| OCR profiles do not offer the providers LLM profiles do; Transcription and Review profile list only `None` | Feature: OCR profiles are separate from LLM profiles and have no presets or catalog, so a fresh archive has none to choose | [04](../tickets/local-testing-feedback/04-ocr-profile-providers-and-catalog.md) |
| Files that cannot be imported become `Failed` documents | Change: they are skipped and not shown anywhere | [05](../tickets/local-testing-feedback/05-skip-unsupported-files.md) |
| No way to include or exclude file extensions when importing a folder | Feature | [06](../tickets/local-testing-feedback/06-include-exclude-extensions.md) |
| No global ignore list for system files | Feature | [07](../tickets/local-testing-feedback/07-global-ignore-patterns.md) |
| A file cannot be read again with another OCR method, in particular after a failed import | Feature: Scan again needs a published text, and Retry uses the collection's settings | [08](../tickets/local-testing-feedback/08-retry-with-chosen-ocr.md) |

## Product decisions taken by the owner

- OCR profiles offer the same providers and look like LLM profiles, but list only models that support image input
  (ticket 04). They remain their own profiles; an LLM profile is not reused as an OCR profile.
- A file that cannot be imported is skipped and not shown at all: no document, no failed item, no per-file result,
  and it is not counted among the import's files (ticket 05).
- Folder imports can either include only chosen extensions or exclude chosen extensions; including means only those
  are imported, excluding means everything else is imported (ticket 06).
- File types can be excluded globally, like `.gitignore`, so system files are never attempted (ticket 07). The list
  is global for the archive, not per collection.

## Engineering defaults (proposals, not product decisions)

- Order of filtering for one candidate file: global ignore (07) → the import's include or exclude list (06) →
  "can this be read at all" (05). A file removed by any step is skipped silently, as in 05.
- Include and exclude are mutually exclusive in one request; a request carrying both is refused with 400 before
  any side effect. Extensions are compared case-insensitively without the leading dot.
- Ignore patterns use `.gitignore` glob syntax for names and relative paths (`*`, `?`, `**`, a trailing `/` for
  directories, `!` to re-include), stored in the baseline schema and seeded with a default list
  (`.DS_Store`, `._*`, `Thumbs.db`, `desktop.ini`, `~$*`, `*.tmp`, `.git/`, `node_modules/`).
- Schema changes edit `src/main/resources/db/migration/001_baseline.sql`; there is no migration path.

## Order and dependencies

01, 02 and 03 are independent and small; they come first because they block the local test. 04 is next because it
makes the profile selects usable. 05 is the shared file-selection seam that 06 and 07 build on. 08 uses 04's
profiles when an image-model engine is chosen.

```
01   02   03   04 ──► 08
               05 ──► 06
               05 ──► 07
```

## Verification gates

- Each ticket: meaningful failing tests first, then focused tests, as in [docs/development.md](../development.md).
- Each ticket that changes the UI adds or extends a browser scenario in `externalTest` (fake providers).
- After the last ticket: the whole backend suite, the web gate (`npm test -- --run`, `npm run check`,
  `npm run build`) and `externalTest`. Real Tesseract, Surya, image-model providers and CoreML stay manual gates on
  the owner's Mac.

## Out of scope

The OCR acceptance and pilot work still open in [ocr-rescanning](../tickets/ocr-rescanning/STATUS.md) (10a–11b),
the compact document list tickets 02–04, and any change to how reviews are decided or published.
