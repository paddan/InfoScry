# Follow-ups after page selection, merged LLM profiles and the page browser

Date: 2026-10-09. Spec: [OCR workflow redesign](../specs/2026-10-08-ocr-workflow-redesign.md)
(section "Page selection"). Tickets: [follow-ups](../tickets/follow-ups/STATUS.md).

## What was delivered in the work this plan follows

- The source viewer can step between the pages of a document (previous, next,
  "Page N of M"); the source response carries `previousId`, `nextId`, `position`
  and `unitCount` for the live reading only.
- LLM profiles and OCR profiles share one Admin section. A profile is offered for
  OCR with "Check image reading", which copies it to an OCR profile and measures it
  with the synthetic test image. Only a confirmed profile is a selectable reading
  method. Model lists load by themselves, show image support, filter on it and
  sort on price.
- Start dialog: Cancel stays visible, unusable (unchecked) LLM profiles are not
  listed, the method select stays narrow.
- Page selection: a PDF page is read when it has no usable text layer, or when an
  embedded image covers at least 25% of it and its text layer scores below 75.
  Import, rescan and the previews follow the rule. `EXTRACTOR_SCHEMA_VERSION` is 2.

## What is deliberately not planned

- A local OCR pre-check (Tesseract) that skips pages without recognised text. It
  fails on handwriting, where an image model helps most, and a skipped page is
  silent. Recorded in the spec.
- Reasoning/"thinking" flags in the model list. Not requested; no behaviour
  depends on it.

## Tickets, in the order to do them

| Order | Ticket | Why now |
|---|---|---|
| 1 | 01 Rescan where no page needs reading | May refuse or store a wrong value; a realistic case |
| 2 | 02 Progress counts the pages that are read | Dialog and progress disagree |
| 3 | 03 Keep the document page count with a stored preview | Small; removes a null |
| 4 | 05 Check page selection on real PDFs and a real model | Mock tests prove no OCR quality |
| 5 | 08 Browser acceptance for the page browser and page selection | Two UI flows have only unit tests |
| 6 | 07 Legacy OCR-only profiles | Decide before they surprise someone |
| 7 | 06 Owner decisions carried over | Needs the owner, not code |
| 8 | 04 Do not render pages that are not read | Only when a large PDF is slow |

Ticket 01 first because it may be a real defect. Ticket 04 last because it changes
the page contract (`RescanPage.image`) for a time and disk saving, not correctness.

## Verification rule for every ticket

Failing test first, then the focused passing tests, then `./gradlew check`.
Ticket 05 is a manual gate on the owner's Mac and is not complete until it has been
run there.
