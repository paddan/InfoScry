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
