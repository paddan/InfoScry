# 03: Page-image OCR seam and Tesseract compatibility

**Status:** Done (2026-09-30) for the seam, the mode semantics and Tesseract compatibility. The engine seam
(`PageOcrEngine`, `PageImage`, `OcrPageResult`), `PageImageRenderer`, the Tesseract adapter and the
FILL_MISSING / CHECK_AND_IMPROVE / rescan branch are in the tree; FILL_MISSING keeps the previous heuristics
and thresholds, and the legacy extraction fingerprints stay byte-identical (two pinned digests, re-derived
by hand). Verified by `./gradlew check` green on the final tree (98 suites, 1176 tests, 0 failures, 14m) and
by the focused suites I re-ran after the last fix.

Four adversarial review rounds drove the hardening, each fix proven by a test that fails when the fix is
reverted: the blank scan decoded a whole raster before bounding it; the bound arithmetic could overflow
`Int` (now `Long`, with a regression test); an over-cap imported picture was handed to the engine unbounded
(now reduced to a bounded derived copy with its own hash and render version, or refused with
`PAGE_RASTER_UNBOUNDED`); multipage artifacts are called blank only when every frame is white; candidate
keys are scoped by document and fingerprint; and a line break in a fingerprint field is refused instead of
composing two readings into one digest.

Two findings from the last round are handed to [ticket 03b](03b-image-provenance.md), because they concern
the per-page source-image provenance pulled forward for the comparison and review slices rather than this
ticket's deliverable: the persistence boundary does not yet enforce path safety or reject half-populated
provenance rows, and a fill-missing PDF render is recorded as provenance and then deleted, leaving a
candidate page pointing at an image a reviewer cannot reopen.

Reported residuals: an ImageIO reader that ignores source subsampling can still allocate a full raster
before the post-check refuses the result (subsampled decoding was measured bounded on this JDK for JPEG,
PNG and TIFF, not proven for every format); PDFBox's own intermediate allocations sit outside the raster
bound; and the real-Tesseract, browser and GPU/CoreML gates remain unverified and belong to ticket 10.
**Blocked by:** 01, 02.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

PDF/image extraction can select an engine and force page-image reading without changing legacy fill-missing behavior.

## Files and interfaces

- `src/main/kotlin/infoscry/ocr/OcrEngine.kt (new)`
- `src/main/kotlin/infoscry/ocr/PageImageRenderer.kt (new)`
- `src/main/kotlin/infoscry/extract/ExtractorRegistry.kt`
- `src/main/kotlin/infoscry/extract/PdfExtractor.kt`
- `src/main/kotlin/infoscry/extract/ImageExtractor.kt`
- `src/main/kotlin/infoscry/extract/TesseractOcr.kt`
- `src/main/kotlin/infoscry/extract/DocumentExtractor.kt`

Produce OcrEngine.transcribe(PageImage, OcrSettingsSnapshot): OcrPageResult; rendering and result contracts are in CONTRACTS.md. Output commits through the isolated candidate sink.

**Tests:** Create src/test/kotlin/infoscry/ocr/OcrEngineContractTest.kt; extend PdfExtractorTest, ImageExtractorTest and ExtractorRegistryTest.

## Test-first implementation

- [x] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - A PDF with a long but incorrect embedded text layer invokes OCR in rescan mode and does not invoke it in legacy fill mode.
  - Rotated images, mixed text/image PDFs, corrupt/encrypted PDF, huge rendering dimensions and verified blank pages produce bounded honest results.
  - A changed render DPI invalidates transcription; re-embedding alone does not.
  - Cancelled rendering does not leave a successful checkpoint or mutate baseline.
- [x] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [x] Adapt Tesseract behind the seam; retain language handling, boxes/confidence where available, and safe missing-tool errors.
- [x] Add explicit FILL_MISSING, CHECK_AND_IMPROVE and rescan behavior. A rescan forces image reading even when the embedded text passes current heuristics.
- [x] Bound rendering pixels, memory and process duration; preserve page ordinal/rotation metadata and artifact hashes. No temporary filesystem paths cross the browser API. — **Residual:** a reader that ignores subsampling can allocate before the post-check (measured bounded for JPEG/PNG/TIFF on this JDK).
- [x] Persist a completed page before increasing progress. Reuse only valid successful checkpoints and compatible artifacts. Unsupported formats advertise a safe reason without changing their existing import behavior.
- [x] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [x] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.ocr.OcrEngineContractTest' --tests 'infoscry.extract.*'
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
