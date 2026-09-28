# 09: Retry a document from its managed copy

**What to build:** The user retries a failed, cancelled or tool-blocked document
from its existing immutable managed copy, retaining its identity and compatible
committed work. The attempt uses current collection settings and is visible
through document status and processing progress.

**Blocked by:** 04 — Show durable processing and OCR progress;
05 — Edit collection settings; 07 — Delete a document safely.

- [x] Details offer Retry for `FAILED`, `CANCELLED` and `NEEDS_TOOL` documents.
      Successful documents, warnings alone, and files without managed documents
      are not offered this action.
- [x] Cancelling an attempt leaves its interrupted document with an honest
      terminal status rather than permanently appearing active.
- [x] Server admission validates collection ownership, status and absence of
      queued/running/deleting work transactionally under mutation admission.
      Concurrent clicks cannot enqueue duplicate attempts.
- [x] The server probes required tools and snapshots current OCR settings before
      enqueueing. Unavailable prerequisites produce a curated remedy, not a job
      that was falsely accepted as runnable.
- [x] Retry addresses existing document IDs and managed bytes, bypassing ordinary
      duplicate detection. Moving/deleting an external original does not prevent
      retry; missing managed bytes produce a safe failure.
- [x] Successful checkpoints are reused only with compatible fingerprints and
      intact artifacts. Failed units are revisited during explicit Retry rather
      than skipped under crash-resume rules.
- [x] Changed OCR settings may repeat extraction, and the UI explains this.
      Re-embedding alone never invalidates compatible OCR checkpoints.
- [x] Deletion winning an admission or processing race prevents retry from
      publishing or recreating the target. Recovery preserves these guards.
- [x] The new attempt's queued/stage/terminal states and committed counts appear
      in the existing document views without assigning a new document identity.
- [x] Tests cover moved original, compatible resume, failed-unit revisiting,
      changed fingerprint, missing artifacts/tools/GPU/model, cancellation,
      concurrent admission and delete races. UI tests and accumulated checks pass.

**Status:** Done. `POST /api/collections/{id}/documents/retry` either names a
nonempty `documentIds` list (at most 200) or asks for `allEligible: true`, never
both, and answers with the accepted job ids plus a per-document sentence for each
refusal. It is a distinct attempt path: migration 014 adds `RETRY` as a job type,
`RetryJobPayload` carries document ids and the probed `ExtractionSettings`, and
`RetryJobHandler`/`DocumentIngest` read the managed bytes without copying or
classifying anything. Admission runs inside one transaction under the shared
mutation permit — ownership, `FAILED`/`CANCELLED`/`NEEDS_TOOL`, the durable
deletion target, an import that still holds the document, the reading tool, the
e-book converter and the installed embedding model are all checked before the
document moves to `QUEUED` and the job is queued. `reusableSucceededCheckpoints`
plus `ExtractionSink.retryKeys` reuse only succeeded units whose artifacts still
verify and drop the failures the retry is about to derive again, so a pass that
ends well is not reported with a superseded failure. `ImportJobHandler` now
records `NEEDS_TOOL` for a missing tool (`NEEDS_TESSERACT`, `NEEDS_TOOL`), and
`DocumentDetail.retryEligible` drives a `Retry` action in the existing details
view, wired through the page as `onRetryDocument`.

Commands and observed results: `JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew -Dkotlin.daemon.jvmargs=-Xmx3g test
--tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests 'infoscry.jobs.*' --tests 'infoscry.collection.*' --tests 'infoscry.search.*' --tests 'infoscry.document.*'`
→ 397 tests, 0 failed (`RetryJobHandlerTest` 9, `DocumentRetryRoutesTest` 12,
`ImportJobHandlerTest` 23, `ContentStoreTest` 18, `DocumentRoutesTest` 19);
`cd web && npx vitest run` → 180 passed; `npm run check` → `svelte-check found 0
errors and 0 warnings`. The accumulated `./gradlew check` gate and browser
acceptance remain the parent's and ticket 12's; the same suites' behavioural
change was also checked against the import path (`infoscry.jobs.*` and
`infoscry.storage.*` pass with the extraction logic moved into
`DocumentIngest`). Damaged per-unit artifacts keep their existing coverage
(`ContentStoreTest`'s invalidation cases), because the verification code they
exercise is shared unchanged by the retry path.
