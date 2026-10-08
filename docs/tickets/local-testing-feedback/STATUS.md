# Local testing feedback — status

[Plan](../../plans/2026-10-07-local-testing-feedback.md) · Source: the owner's local test on 2026-10-07.

| Ticket | Blocked by | State |
|---|---|---|
| [01 — Measure tool calling from Admin and explain an unmeasured profile in Investigate](01-measure-tool-calling-in-admin.md) | None | Implemented; browser scenario passes (fake provider) |
| [02 — Import history shows the final stage of a finished import](02-import-history-final-stage.md) | None | Implemented; browser scenario passes (fake provider) |
| [03 — Revision history names the OCR method an import used](03-import-reading-in-history.md) | None | Implemented; browser scenario passes (fake provider) |
| [04 — OCR profiles offer the LLM providers and only image-capable models](04-ocr-profile-providers-and-catalog.md) | None | Implemented; browser scenario passes (fake provider) |
| [05 — Files that cannot be imported are skipped and not shown](05-skip-unsupported-files.md) | None | Implemented; browser scenario passes (fake provider) |
| [06 — Include or exclude file extensions when importing a folder](06-include-exclude-extensions.md) | 05 | Implemented; browser scenario passes (fake provider) |
| [07 — Per-collection ignore patterns for files that are never imported](07-ignore-patterns.md) | 05 | Implemented; browser scenario passes (fake provider) |
| [08 — Read a document again with a chosen OCR method, including after a failed import](08-retry-with-chosen-ocr.md) | 04 | Implemented; browser scenario passes (fake provider) |

A profile can be measured in Admin → LLM profiles ("Check tool calling") or with `infoscry llm test <profile name>`.

## Merge and verification (2026-10-08)

All eight tickets are merged to `main` ([PR #6](https://github.com/paddan/InfoScry/pull/6), `caaa0c2`). Run in a
Linux cloud container as root, not on the Mac:

- Backend `test`: 1,589 tests, 10 failures, all environmental (root bypasses permission checks, no init process
  to reap children, locale-dependent fixtures).
- Web: 423 Vitest tests; `npm run check` 0 errors, 0 warnings.
- `externalTest`: 37 tests; Collections 13, OCR 12, Investigate 8 and Search 1 browser scenarios pass; 3 failures
  need Tesseract or Surya, which are not installed there.
- The browser scenarios found and fixed two UI bugs: a collection's copied LLM profile shown as "no longer
  available" after a reload, and an empty answer bubble left by a refused Investigate turn.
- Seen once each and passing on rerun, not investigated: `legacy-default` (Collections), `review-use-new` and
  `review-keep-existing` (OCR), and `JobEventRoutesTest` under full-suite load.

Not verified: `./gradlew check`, real providers, real Tesseract/Surya through these flows, CoreML.

## Open owner decisions

- 06: the extension filter also applies to individually chosen files, not only to files found in a folder.
- 04: `deepseek-chat` and `deepseek-reasoner` are marked text-only in `providers.json` without a cited source.
- 07: matching ignores case, `[abc]` classes are unsupported (brackets are literal), and an individually chosen
  file is matched by its own name only, so a directory pattern such as `node_modules/` does not skip it.
- 07: collections created before this change start with an empty ignore list rather than the defaults.
