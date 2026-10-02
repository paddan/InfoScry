# 04: Local Surya adapter and Mac runtime proof

**Status:** Done (2026-10-01) for the local Surya engine and its runtime proof. `scripts/ocr/surya_worker.py`
(versioned bounded JSON on stdin/stdout, the inference manager loaded once, `--identity` probe, SIGTERM
converted into a clean exit) and `src/main/kotlin/infoscry/ocr/SuryaOcr.kt` (configured interpreter and
script only, bounded I/O, process-tree teardown, `NEEDS_SURYA` / `NEEDS_LLAMA_CPP` / `NEEDS_SURYA_MODEL` /
`SURYA_START_FAILED` diagnostics) plus the registry wiring. Measured on this Mac: surya-ocr 0.22.1, torch
2.14.1, transformers 5.18.0, llama.cpp 0.5.0 (build 11146), weights 1 266 400 864 + 204 986 688 bytes in
the Hugging Face cache; the typed and handwriting-*style* fixtures transcribed exactly, cold ~5.2 s and
warm ~1.8 s per page, identity probe 1.3 s with no model start, zero surviving `llama-server`.

Verified by my own runs: `./gradlew check --rerun-tasks` green (99 suites, 1197 tests, 0 failures,
14m43s), the focused suites green, `./gradlew externalTest --tests 'infoscry.ocr.SuryaRealToolTest'` green
(1 test, 0 failures, 0 skipped) and `python3 scripts/ocr/test_surya_worker.py` ok. Three review rounds
drove: whole-tree teardown (handles captured before signalling, waited per handle, force-killed, verified;
the stderr drain joined) after a helper that ignores SIGTERM was found to survive; empty readings becoming
explicit `OCR_EMPTY` failures at the seam and in both consumers instead of committed empty text; out-of-page
boxes refused; the unconfigured path naming the pinned remedy; incremental worker output bounds; the
runtime identity probe entering the fingerprint before committed keys are read (a checkpoint committed under
identity A is not reused when the probe reports B); and the Surya codes reaching the import queue as
`NEEDS_TOOL` with the remedy. The parent also fixed a `close()`/`transcribe()` race by hand: the closed
check and worker publication now share the monitor `close()` takes, so no worker can start after `close()`
returns.

Residuals: `OcrSettingsSnapshot` carries no `runtimeIdentity`, so ticket 07's admission path must put the
probe value into the snapshot it persists; invalidation is proven with stand-in identities plus a real
probe, not by upgrading the real model; the probe's 10 s hang bound has no test; a descendant re-parented
before teardown is invisible to `ProcessHandle.descendants()`, which is why the documented guarantee names
the handles captured while the worker was alive; the handwriting fixture is a Caveat typeface render, so it
says nothing about human handwriting (ticket 11's pilot measures that); and the licence facts are the
package's Apache-2.0 code with model weights under a modified AI Pubs Open Rail-M license.
**Blocked by:** 03.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

Users can select a verified local Surya installation with actionable diagnostics and resumable page output.

## Files and interfaces

- `src/main/kotlin/infoscry/ocr/SuryaOcr.kt (new)`
- `scripts/ocr/surya_worker.py (new bounded process protocol)`
- `src/main/kotlin/infoscry/extract/ExtractorRegistry.kt`
- `docs/installation.md`
- `docs/technical-reference.md`

Implement OcrEngine with local process transport; exchange bounded versioned JSON and managed image paths internally, never arbitrary user-supplied commands.

**Tests:** Create src/test/kotlin/infoscry/ocr/SuryaOcrTest.kt and SuryaRealToolTest.kt (external tag). Use a fake child process for normal tests.

## Test-first implementation

- [x] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Nonzero exit, valid JSON with empty text, malformed/oversized JSON, child holding stderr open and cancellation all terminate safely.
  - Page output already committed is reused after restart; a model change is detected in fingerprints.
  - Run a redistributable typed and handwritten fixture on this Mac; record actual runtime and limitations without claiming superiority from transport success. — **Caveat:** the handwriting fixture is a Caveat *typeface render*, not human handwriting; human handwriting is ticket 11's pilot.
- [x] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [x] Verify supported package/model license, pinned installation commands, model download locations and actual macOS arm64 backend before choosing a runtime. Do not promise MPS or other acceleration without measuring it. — Verified: `surya-ocr` Apache-2.0, weights under a modified AI Pubs Open Rail-M licence, llama.cpp/Metal measured on this Mac (no MPS claim).
- [x] Use a bounded child lifecycle controlled by the application; capture schema-validated text/boxes with page identity. Empty/whitespace output is not automatically successful extraction.
- [x] Kill the process group on cancellation/timeout and drain pipes without deadlock. Limit output and report safe missing-package/model/incompatible-runtime errors; never fall back to Tesseract. — **Residual:** a descendant re-parented before teardown is invisible to `ProcessHandle.descendants()`; the guarantee covers the handles captured while the worker was alive.
- [x] Document exact tested versions, installation/version/PATH checks, hardware backend and resource limits. Keep embedding CoreML requirements separate from Surya execution.
- [x] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [x] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.ocr.SuryaOcrTest'
./gradlew externalTest --tests 'infoscry.ocr.SuryaRealToolTest'
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
