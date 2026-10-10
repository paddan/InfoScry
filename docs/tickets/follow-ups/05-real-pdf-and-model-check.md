# 05 - Check page selection on real PDFs and a real model

Manual gate. Fake tests do not establish OCR quality or that the selection rule
fits real documents (`AGENTS.md`: never mark a hardware or provider gate complete
from mock results).

## Do on the Mac
1. A scanned PDF with no text layer, a searchable scan with a good hidden layer, a
   scan with a poor hidden layer, a born-digital PDF with a logo on each page, and
   a PDF with a full-page photo plus text. For each, preview the Scan again dialog
   and note which pages it selects.
2. Confirm the 25% image threshold and the 75 text-quality threshold behave
   sensibly; record any page they got wrong. Thresholds are the constants
   `PdfPageSelector.MIN_IMAGE_COVERAGE` and `MIN_TEXT_LAYER_QUALITY`.
3. Run one document through "Check image reading" and a real scan with a real
   image-capable model. Confirm only the selected pages are sent (the provider's
   request log or the page counter).
4. Note inline images: they are not measured. If a real scan uses them, a page may
   be skipped wrongly; report it.

## Also in code
- Unit test for `DefaultPageCounter.readablePageCount` on a PDF with a mix of
  pages (today it is covered through fakes and one all-blank PDF).
- The import preview opens each PDF twice (`pageCount` and `readablePageCount`).
  Count once if it shows up in timing.

## Done when
The results are written into `docs/implementation-status.md` with the date and
what was run.

## Automated coverage (2026-10-09)

Added a real PDFBox-generated three-page PDF to `PageCounterTest`: two clean text
pages and one full-page raster without a text layer. The production
`DefaultPageCounter` reports three document pages and one page to read, through
both media-type entry points. This test passed alongside `LlmModelCatalogTest`.
This adds page-selection coverage; it does not establish OCR quality. The real
PDF corpus, real image-model check and real-provider scan remain open. The
conditional double-open optimization has not been justified by a timing result.
