# OCR test fixtures

`typed-render.png` and `handwriting-style-caveat.png` were generated for these tests on 2026-10-01 with
Pillow: the first with a system serif face, the second with the Caveat typeface. They contain no text from
any real document.

**The second one is a handwriting *typeface render*, not real handwriting.** It exercises the engine's
handling of irregular letterforms, and it says nothing about transcription quality on handwriting written by
a person; that is measured in the pilot (ticket 11) on user-supplied samples.

`Caveat.ttf` is the Caveat typeface by Impallari Type, distributed under the SIL Open Font License 1.1
(https://github.com/google/fonts/tree/main/ofl/caveat), which permits redistribution and embedding.
