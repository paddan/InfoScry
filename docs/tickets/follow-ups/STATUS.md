# Follow-ups: status

[Plan](../../plans/2026-10-09-follow-ups.md) · [Spec](../../specs/2026-10-08-ocr-workflow-redesign.md)

Each ticket can be built from its own file, `AGENTS.md` and the spec.

| Ticket | Blocked by | State |
|---|---|---|
| [01 - Rescan where no page needs reading](01-zero-read-rescan.md) | None | Complete; normal accumulated gate passed |
| [02 - Progress counts the pages that are read](02-progress-counts-read-pages.md) | None | Complete; normal accumulated gate passed |
| [03 - Keep the document page count with a stored preview](03-persist-document-pages.md) | None | Complete; normal accumulated gate passed |
| [04 - Do not render pages that are not read](04-skip-rendering-unread-pages.md) | 01 | Complete; normal accumulated gate passed |
| [05 - Check page selection on real PDFs and a real model](05-real-pdf-and-model-check.md) | None | Automated PDF coverage added; manual gate open |
| [06 - Owner decisions carried over](06-owner-decisions.md) | None | Complete; decisions recorded and catalog correction verified |
| [07 - Legacy OCR-only profiles](07-legacy-ocr-profiles.md) | None | Cancelled by owner; database reset instead |
| [08 - Browser acceptance for the page browser and page selection](08-browser-acceptance.md) | None | Complete; all 36 browser tests passed |

The compact document list (tickets 02-04 under
[compact-document-list](../compact-document-list/STATUS.md)) is older, unrelated
and still not started.

## Owner decisions (2026-10-09)

Existing filter/ignore/page-selection behaviour is retained. Existing-data
migrations and profile conversion were explicitly declined: this implementation
changes only the fresh database baseline, and the owner will recreate the
database. The agent has not deleted or converted any archive data.
