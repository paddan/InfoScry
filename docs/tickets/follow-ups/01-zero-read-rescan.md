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
