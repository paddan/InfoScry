# 01 - Rescan where no page needs reading

## Problem
When every page of a document keeps its text under the page-selection rule, a rescan
preview has `pageTotal` 0. `OcrOperation` requires `pageTotal > 0`, so admission
stores null (`admitRescan` passes `preview.pageTotal?.takeIf { it > 0 }`). Nothing
tests this path. It may refuse, store a wrong allowance, or publish a revision
identical to the old one.

## Decide first
What should "Scan again" do when no page needs reading? Recommended: the dialog
says "No page needs reading; the document keeps its text." and the confirm button is
disabled, so no operation is created. The alternative (start and publish an
unchanged copy) adds a history entry for nothing.

## Scope
- `RescanService.preview` and `admitRescan`; `StartReadingDialog.svelte`.
- If the start is refused, a clear error code instead of a null allowance.

## Acceptance
- Test (RescanJobHandlerTest or RescanServiceTest): a document whose pages all keep
  their text gives `pageTotal` 0 and `documentPages` N, and the chosen behaviour.
- Dialog test: the zero case, with the button state the decision requires.
- Running the same start twice returns the same result (idempotence rule).

## Implementation and focused verification (2026-10-09)

A zero-read preview reports `pageTotal = 0` and the document's total pages.
Repeated admission returns `RESCAN_NO_PAGES_TO_READ` before creating or replacing
an operation, job or revision. The dialog explains that existing text is kept
and disables confirmation. Imports with zero image-reading pages remain
startable as “Import without image reading”.

The backend regression first failed because admission created an operation; it
then passed alongside existing admission/idempotence coverage. The dialog's
rescan and zero-OCR import regressions failed before their fixes; all 13 focused
dialog tests and typecheck passed. Accumulated verification is recorded in
[implementation status](../../implementation-status.md).
