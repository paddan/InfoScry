# 04 - Do not render pages that are not read

## Problem
A rescan renders every page of a PDF to a PNG before reading starts, including
pages that keep their text. Only time and disk are wasted; the text and the cost
are right. It matters for long PDFs where few pages are read.

## Scope
`RescanPageSource.pdfPages` and `RescanPage`. `RescanPage.image` is non-null and is
used by the `alreadyStaged` check (image sha256) and by source-image provenance.
Either make the image nullable for pages that are not read, or render lazily. Keep
the resume check correct: a skipped page is recognised as staged by ordinal and
unit id, not by pixels.

## Do this only when
A large PDF is measurably slow to start. Measure first (render time per page and
disk used for a 300-page PDF with 10 selected pages).

## Acceptance
- A 3-page PDF with one selected page leaves one PNG under the attempt directory.
- All existing rescan tests, including resume and cancel, still pass.

## Baseline measurement (2026-10-09)

On the local Mac, PDFBox rendered 300 synthetic letter-size pages at the
production default of 300 dpi and encoded every page as PNG. The PDF contained
290 clean digital-text pages and ten pages without a text layer. Rendering and
PNG encoding took 48.915 seconds (163.051 ms/page) and produced 18,049,700 bytes;
the ten selected pages accounted for 1.639 seconds and 319,970 bytes. Files were
removed after their sizes were counted. This isolates the rendering pipeline,
not full rescan startup, selection, chunking or embedding time. The baseline
justifies implementing this conditional ticket; it is not a before/after
measurement of the complete application.

## Implementation and focused verification

Preserved pages carry ordinal, stable unit ID and baseline without an image.
Only selected pages are rendered. Candidate staging keeps the baseline's
extraction method and source-image provenance for preserved pages. Their resume
check uses ordinal and unit ID; selected pages still compare image hashes.

A generated three-page PDF with one selected page leaves exactly one PNG under
the rescan attempt and publishes all three stable unit IDs. The handler suite
also exercises resume and cancellation. The initial regression failed when
clean pages acquired newly rendered source-image provenance. Accumulated
verification is recorded in [implementation status](../../implementation-status.md).
No optimized full-workflow timing or OCR-quality claim is made.
