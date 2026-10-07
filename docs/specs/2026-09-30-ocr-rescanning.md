# Selectable OCR, comparison and document rescanning

Status: design interview completed and documentation authorized on 2026-09-30.
**Planned, not implemented.** [Implementation tickets](../tickets/ocr-rescanning/STATUS.md)
record dependencies and verification. No current runtime capability is implied.

## Purpose and scope

Improve recognition errors and missed text in typed, printed, handwritten and
poorly scanned documents. Provide Tesseract, local Surya, and image-based LLM
transcription through local or external endpoints. The LLM reads page images;
text-only correction is not a substitute. Apple Vision and a separate linguistic
post-correction pipeline are excluded from this first version.

Admin → Collections owns import defaults and document rescanning. OCR profiles
are reusable, separately managed in Admin, and do not change Ask/Investigate
profiles. Single-document rescanning is in scope; bulk rescanning, scheduled
rescanning, automatic engine fallback, PDF rewriting, new embedding platforms,
CI and packaging are not. Original source files and managed copies stay immutable.
Initial page-image workflows cover PDF and image documents. Other formats retain
their existing extraction, with a clear unsupported rescan reason rather than
invented page rendering or silently ignoring a chosen mode.

## Existing behavior and explicit contract changes

Verified against the repository on 2026-09-30:

- `extract/ExtractorRegistry.kt` wires Tesseract; collection settings currently
  select OCR languages, not an engine. PDF extraction prefers a text layer when
  it has at least 40 alphanumeric characters and a 50% alphanumeric ratio.
- `extract/DocumentExtractor.kt` fingerprints bytes, extraction schema, language,
  tool versions and rendering. New settings must extend this contract without
  allowing incompatible checkpoint reuse.
- `storage/ContentStore.kt` updates stable document/ordinal units and discards
  superseded chunks while processing. `jobs/DocumentIngest.kt` subsequently
  publishes an index replacement. This does not isolate a rescan candidate from
  the currently readable text.
- [Collections management](2026-09-27-collections-management.md) permits Retry
  only for FAILED/CANCELLED/NEEDS_TOOL and excludes reprocessing successful
  documents. This design explicitly adds a separate **Scan again** operation for
  successful managed PDF/image documents. Retry eligibility is not broadened.
- The earlier spec's non-goal of changing LLM behavior does not cover this newly
  authorized OCR feature. Ask/Investigate generation behavior remains unchanged,
  but their source retrieval must respect published text revisions.

Historical Palmemordsarkivet implementation is inspiration, not a dependency:
its Surya performs local OCR, Apple Vision uses ocrit, and its LLM correction
works on extracted text rather than page images.

## Agreed user experience

### Profiles and collection defaults

An OCR profile describes provider protocol, endpoint, model, context/output
limits, optional pricing and the **name** of an API-key environment variable.
Support OpenAI-compatible image requests and the existing Anthropic provider
family with separate image-capability checks. A loopback endpoint can omit a key.
Never serialize key values. Capability checks use a redistributable synthetic
image; success proves image transport, not transcription quality.

Collection settings select engine (TESSERACT, SURYA, LLM), transcription profile
when engine is LLM, independent image-based review profile, OCR language, import
mode and external-page allowance. A reviewer can equal the transcription model;
show that this is not an independent second opinion. Profiles are separately
versioned. Snapshot all effective settings and profile revisions at admission;
editing a default affects future attempts, not running jobs or old documents.

Import modes:

| Label | Behavior |
|---|---|
| Fill missing text | Default. Retain usable direct text; invoke chosen engine where text is absent/unusable. |
| Check and improve | Read page images even when a text layer exists, then compare and adjudicate. |

Selecting an external profile is an explicit external-processing choice. Show
endpoint/model and that page images and candidate/baseline text leave the Mac.
No external service is selected implicitly. Changes to an endpoint must be
visible and invalidate old capability/pilot approvals; do not follow redirects
that silently turn a local request into an external one.

### Scan again

The document detail action previews page count, engine, transcription/reviewer
profiles, external processing and a cost estimate only when image/token pricing
is sufficient. Missing price data reads `Cost unavailable`, not zero.
A per-document override does not alter collection defaults. Read every page
image regardless of the embedded text layer. Compare with the **currently
published** text, including previous manual corrections. Retain the original
embedded text as provenance; on the first rescan it is the baseline when that
is the currently published text. Do not replace a manual correction merely
because it differs from the PDF text layer.

Admission uses managed bytes, preserves document and page IDs, bypasses import
deduplication, checks lifecycle/deletion guards, and rejects overlapping rescan,
review-publication or restore operations on the same document. Network requests
are not held inside a database transaction or exclusive maintenance lock.

### Comparison and review

Use deterministic checks (empty/truncated output, repeated text, suspicious
character sequences, missing regions, length changes), aligned differences and
image-based adjudication. Word-list scores and model self-confidence alone may
never authorize a replacement. Names, numbers, dates and negations need explicit
attention; more fluent or longer text is not necessarily better.

Review returns EXISTING_BETTER, NEW_BETTER or UNCERTAIN, with bounded structured
reasons tied to differing spans/regions. It receives the image and both versions
without favoring a named engine. Treat source text and images as untrusted data;
no tools, web access or execution instructions are available to the reviewer.
Invalid/truncated responses and an unavailable reviewer mean UNCERTAIN, not
implicit acceptance. Do not synthesize a third merged text automatically.

`Needs review · N pages` opens image, baseline, candidate, highlighted differences
and the reviewer's explanation. Actions: Keep existing, Use new, Edit text; bulk
approval/keep for the current document has an explicit page scope. Persist every
manual decision. Review has optimistic version checks so an old tab cannot
silently overwrite a newer revision. Explain omitted pages and pending review in
reader/details. Keyboard focus, labelled controls and bounded page loading are
required; model output and OCR text render as untrusted plain text.

The first release runs in **pilot mode**: differing candidates require manual
approval before replacement, even when the reviewer recommends NEW_BETTER.
Identical text requires no replacement. Later, an explicitly enabled, measured
policy can approve well-supported changes. Otherwise retain existing text and
flag the page. If there is no baseline, retain an uncertain proposal outside
search/Ask/Investigate until approved. An identical empty pair is not evidence of
a blank page; distinguish a verified blank image from failed/missed extraction.

### Publication and history

Publish a whole document revision once extraction, decisions, chunking, embedding
and indexing succeed. It may combine approved new text with retained baseline
text on uncertain pages. Initial imports may publish approved pages while
unapproved pages have image access and pending-review state but no searchable
text. A document with no approved text is visibly pending review, never silently
reported as a fully searchable success.

Failed or cancelled rescans leave the previous entire published revision active.
Later review decisions create another revision through the same publication
service. Save every published revision and its page provenance, engine/model,
time and automatic/manual decision. Retain unresolved proposals until handled.
Restore creates a new publication referencing the selected historical content;
it never rewrites history or reruns OCR. Share immutable images/artifacts where
possible. Normal document/collection deletion removes all revisions and proposals
under existing deletion recovery; history is not a soft-delete feature.

Saved evidence must not silently resolve to different text after a rescan.
Preserve stable unit IDs and add revision provenance for new citations/source
reads. Backfill references where identity is provable; otherwise keep saved
excerpts and label revision unknown rather than attributing new text to old
answers. Requests already using a revision can finish against that revision.

## Durability and publication contract

SQLite owns immutable document revisions, page text revisions, candidates,
review decisions, job settings snapshots and publication intents. Existing
content becomes an initial published revision when it is written.
The schema is the single baseline, edited in place; there are no incremental migrations.
Preserve stable content-unit IDs using revision-owned text/chunks, not replacement
unit identities. Rendering/checkpoint artifacts are verified by hash before reuse.

Introduce an isolated candidate sink. Neither candidate extraction nor its
chunking can mutate published content, remove its chunks, or invalidate evidence.
Fingerprint extraction separately from review: document/page identity, engine,
tool/model/profile revision, language, prompt/schema, render DPI and decoding
settings affect extraction; baseline revision, candidate hash, reviewer settings
and policy version affect review. Re-embedding or a changed reviewer does not
rerun compatible transcription. New settings require a new explicit attempt;
restart resumes the original snapshot. Provider model aliases record their
returned version when available and are not claimed to be immutable.

Use a recoverable publication protocol, not a fictitious SQLite/Lucene transaction:

1. Persist a PREPARED intent with base/target revision and verified chunk/vector
   artifacts. Stage generation-tagged Lucene rows without exposing them.
2. Under a short shared publication boundary, recheck deletion, base revision,
   cancellation and maintenance; commit staged Lucene rows and make the SQLite
   target revision authoritative. Block new reader acquisition across the handoff.
3. Publish a matching reader/active-revision snapshot and mark PUBLISHED. Existing
   readers retain their leased snapshot. Candidate rows must be filtered before
   search top-k/ranking so they cannot displace live hits.
4. Recover unfinished intents before admitting traffic/workers. Before authority
   moves, the old revision wins; after it moves, complete publication of the new
   revision. Rebuild missing index state from durable target artifacts. A pending
   publication is never labelled failed after its authority commit. Cleanup of
   stale index rows waits for leases; SQLite text history remains.

This extends existing runtime generation/reader leasing rather than replacing
its writer/reader/embedder ownership. The implementation ticket must demonstrate
read consistency and crash recovery at every boundary. An inability to satisfy
this contract blocks dependent work; do not downgrade it to page-by-page updates.

Keep cancellation bounded between pages/provider requests. Each complete result
and its checkpoint commit durably. A crash after a provider response but before
commit can require a repeat request; do not promise exactly-once external billing.
Bound retries/timeouts; malformed results are not successful checkpoints. Engine
failure pauses/fails safely with a curated remedy and never switches engines.
Review failure retains/flags the baseline instead of discarding completed OCR.

## External-page admission

Persist a nonnegative page allowance per collection and a job-specific approval.
The initial safe implementation default is 0 external pages without approval;
it is an engineering default, configurable in Admin, not a measured cost limit.
Count distinct document/page identities sent externally for OCR or review across
all files in the job. A page used by both stages counts once toward this page
allowance, but both calls count toward usage/cost; the UI must explain this.
Preflight known totals conservatively; unknown totals require approval or pause
before the first page beyond allowance. Snapshot the authorized page scope and
profile revisions. Larger jobs wait in an explicit approval state, retained
across restart. No image or text is sent before approval for that scope.
Resume never resets the counter. Bound retry calls separately: a page limit is
not a currency cap. Keep call counts and usage separate from page progress.

## Evaluation and activation gate

Use redistributable fixtures for tests and a user-selected representative pilot
covering clean print, typewriting, faint/noisy copies, handwriting, names/numbers,
blank pages, partial loss and mixed text/image PDFs. Private pilot data stays
local unless the user explicitly approves the particular external experiment;
the general design agreement is not permission to send private test documents.

Human-verified transcriptions are ground truth for CER/WER. Also measure omitted
regions, invented content, damaged names/numbers, false automatic acceptances,
missed improvements, abstention/review load, elapsed time and actual usage.
Keep a held-out document-level set separate from policy tuning. Record corpus
hashes, engine/model/prompt/policy versions, hardware and denominators. No runtime
CER against the previous OCR should be labelled accuracy.

The evaluation ticket proposes measurable activation thresholds from the pilot
and presents results for explicit user acceptance. Until those thresholds are
accepted and met on held-out documents, automatic replacements remain disabled.
A changed review/transcription model or policy requires renewed validation.
No claim of general superiority over Tesseract from a few selected successes.

## Shared constraints and verification

Kotlin/JVM 25; one application process and one Gradle backend module;
TypeScript/SvelteKit static frontend; SQLite authority and rebuildable Lucene.
Local Surya may run as a bounded child process, like existing extraction tools,
not a second application server. Probe and document a tested macOS arm64 runtime,
package/model versions, hardware backend, resource use and model license before
advertising support. Do not change pinned dependencies without evidence.

GPU embeddings remain CoreML on macOS arm64 with no CPU fallback. Diagnostic,
keyword-search and source-view access survive GPU initialization failure; new
publication that needs embeddings waits safely. Bind HTTP only to 127.0.0.1 and
preserve bearer/CSRF, collection scoping, mutation admission and maintenance.
Keys come only from environment variables. Logs/errors contain no keys, document
text, images, source paths or provider authorization headers. Use safe error codes.

Each implementation ticket starts with meaningful failing tests, then focused
passing tests and `./gradlew check`; UI work also runs `npm run check` and
`npm run build` in web. Relevant `externalTest` browser scenarios use local fake
providers and temporary archives. Real Surya/local image-model tests and CoreML
on the Mac are separate gates; mock/browser success does not prove them. Ask and
format-specific source-viewer finish lines stay open unless independently tested.
