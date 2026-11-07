# Image foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every embedded image, scanned-page picture region and imported picture becomes a stable, citable image unit of its document, shown in the reader and found by its caption.

**Architecture:** A new `infoscry.image` package finds images per format, filters them, writes content-addressed artifacts and runs an *image stage* after the text of each attempt. The stage delivers image units through the attempt's existing target (the import sink, or the rescan's candidate revision), so they are published in the same transaction as the text. Image metadata lives in `document_images`; revisions bind to it through their page rows' `source_image_*`.

**Tech Stack:** Kotlin/JVM 25, PostgreSQL 17 (schema v4), PDFBox, POI, jsoup, ImageIO, Ktor, Clikt, Svelte 5, Vitest, Playwright (`externalTest`).

**Spec:** [docs/specs/2026-11-07-image-foundation-design.md](../specs/2026-11-07-image-foundation-design.md)

## Global Constraints

- Product copy in English; restrained, accessible, normal HTML/CSS; every `<img>` has an `alt`.
- Modes: `OFF`, `EMBEDDED`, `ALL`; collection default `OFF`; frozen in the attempt snapshot as `imageExtraction`, never part of `ExtractionFingerprint`.
- Filters: shortest side ≥ 96 px; area ≥ 1 % of the parent page when it has geometry; SHA-256 on more than 3 pages skipped; masks and one-colour images skipped; at most 500 images per document.
- Scanned page: one image covering ≥ 90 % of the page area.
- Unit ID: UUID v5 over document ID, parent unit ID, source kind and SHA-256 (embedded/standalone) or box rounded to 3 decimals (region), plus occurrence number for duplicates in one parent.
- Ordinal: `1_000_000_000 + parentOrdinal * 1_000 + (index - 1)`; parent ordinal > `1_147_482` gets no images, with a warning.
- Artifacts: `artifacts/images/<sha256>.<ext>`, thumbnails `artifacts/images/thumbs/<sha256>.jpg` (longest side 320 px); temp file + atomic move; existing hash reused.
- Codes: `NEEDS_SURYA_LAYOUT`, `IMAGE_DECODE_FAILED`, `IMAGE_TOO_LARGE`, `IMAGE_LIMIT_REACHED`.
- No image bytes, captions or document text in logs or errors; byte routes send `nosniff`, `inline`, never SVG.
- Every operation idempotent: run twice, interrupt at each step, run again → same IDs, no duplicate files or rows.
- Every JVM headless (`-Djava.awt.headless=true`); iterate with `./gradlew test -PskipFrontend --tests <class>` and `cd web && npm test -- --run && npm run check`; `./gradlew check` before reporting done.

## Review Focus

- Image units leaking into page-centric features: page review, rescan page census and selection, selectable-PDF generation, "page x of y" progress and the document page count must see only text units. Test in Task 7.
- One XObject drawn twice on one page, or a Form XObject reused on many pages: each drawing is one occurrence; the repetition rule counts pages, not drawings. Test in Task 4.
- An image ImageIO cannot decode (CMYK JPEG, 16-bit PNG, JBIG2): the image fails as `IMAGE_DECODE_FAILED` with a warning, the page's text and other images are kept. Test in Task 4.
- A document deleted or a collection tombstoned during the image stage: the stage stops with `DocumentBeingDeletedException`/`CollectionNotActiveException` as the text stage does, and leaves no orphan rows. Test in Task 6.
- A selected-page rescan with images on, then a second with images off: the second revision has no image units, the first keeps its own, and Restore of the first brings its images back. Test in Task 8.

---

### Task 1: Image locator, identity and mode

**Files:**
- Modify: `src/main/kotlin/infoscry/domain/SourceLocation.kt`
- Create: `src/main/kotlin/infoscry/image/ImageIdentity.kt`
- Create: `src/main/kotlin/infoscry/image/ImageExtractionMode.kt`
- Test: `src/test/kotlin/infoscry/domain/SourceLocationTest.kt`, `src/test/kotlin/infoscry/image/ImageIdentityTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class NormalizedBox(val left: Double, val top: Double, val right: Double, val bottom: Double)` (in `SourceLocation.kt`), all in `0.0..1.0`, positive size; `fun rounded(): NormalizedBox` to 3 decimals.
  - `@SerialName("image_region") data class SourceLocation.ImageRegion(val parent: SourceLocation, val index: Int, val box: NormalizedBox?)`; `unitKind = IMAGE`; `parent` is not `ImageRegion`; `index >= 1`.
  - `enum class ImageSourceKind { EMBEDDED, PAGE_REGION, STANDALONE }`
  - `enum class ImageExtractionMode { OFF, EMBEDDED, ALL; val id: String }` with ids `off|embedded|all`, `fun parse(id: String): ImageExtractionMode` throwing `IllegalArgumentException`.
  - `object ImageIdentity { const val ORDINAL_BASE = 1_000_000_000; const val MAX_PER_PARENT = 1_000; const val MAX_PARENT_ORDINAL = 1_147_482; fun unitId(documentId: DocumentId, parentUnitId: ContentUnitId, kind: ImageSourceKind, sha256: String?, box: NormalizedBox?, occurrence: Int): ContentUnitId; fun ordinal(parentOrdinal: Int, index: Int): Int?; fun isImageOrdinal(ordinal: Int): Boolean }` — `ordinal` returns null above `MAX_PARENT_ORDINAL`.

- [ ] **Step 1: Write failing tests.** `SourceLocationTest`: `ImageRegion(PdfPage(14), 2, null).describe() == "image 2 on page 14"`; `ImageRegion(Slide(3), 1, box).describe() == "image 1 on slide 3"`; `ImageRegion(Image("a.png"), 1, null).describe() == "image a.png"`; JSON round-trip keeps `parent` and `box`; nested `ImageRegion` parent, `index = 0`, box with `right <= left` or `1.2` each throw. `ImageIdentityTest`: same inputs give the same ID twice; different occurrence, kind or box (after rounding: `0.12341` vs `0.12344` equal, `0.123` vs `0.124` different) give different IDs; `ordinal(14, 2) == 1_000_014_001`; `ordinal(1_147_483, 1) == null`; `isImageOrdinal(999_999_999) == false`.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.domain.SourceLocationTest' --tests 'infoscry.image.ImageIdentityTest'` — FAIL (unresolved references).
- [ ] **Step 3: Implement.** UUID v5 with a fixed namespace UUID constant in `ImageIdentity`; inputs joined one per line as `key=value`, the same canonical style as `ExtractionFingerprint`. Add `IMAGE` handling to every exhaustive `when` over `SourceLocation` the compiler reports, with labels from `describe()`.
- [ ] **Step 4: Run** the same command — PASS; then `./gradlew test -PskipFrontend` to catch `when` exhaustiveness consumers — PASS.
- [ ] **Step 5: Commit** `Add the image region locator and image identity`.

### Task 2: Schema v4, image store and collection default

**Files:**
- Create: `src/main/resources/db/migration/004_images.sql`
- Modify: `src/main/kotlin/infoscry/storage/SchemaMigrator.kt` (`SUPPORTED_VERSION = 4`, add the script to `STEPS`)
- Create: `src/main/kotlin/infoscry/storage/ImageStore.kt`
- Modify: `src/main/kotlin/infoscry/storage/CollectionStore.kt`, `src/main/kotlin/infoscry/domain/Models.kt` (`Collection.defaultImageExtraction`)
- Modify: `src/main/kotlin/infoscry/collection/CollectionService.kt` (`updateImageDefault`)
- Test: `src/test/kotlin/infoscry/storage/SchemaMigratorTest.kt`, `src/test/kotlin/infoscry/storage/ImageStoreTest.kt`, `src/test/kotlin/infoscry/storage/CollectionStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
  - `data class StoredImage(val documentId: DocumentId, val unitId: ContentUnitId, val sha256: String, val parentUnitId: ContentUnitId, val kind: ImageSourceKind, val relativePath: String, val thumbnailRelativePath: String, val mediaType: String, val widthPx: Int, val heightPx: Int, val box: NormalizedBox?, val caption: String?, val captionSource: CaptionSource?)`; `enum class CaptionSource { ALT_TEXT, FIGCAPTION, LAYOUT_CAPTION }`.
  - `data class ImageCheckpoint(val parentUnitId: ContentUnitId, val outcome: ImageCheckpointOutcome, val imageCount: Int, val warning: String?)`; `enum class ImageCheckpointOutcome { DONE, FAILED }`.
  - `class ImageStore(database: Database) { fun record(image: StoredImage); fun image(documentId, unitId, sha256): StoredImage?; fun imagesOf(revisionId: String): List<StoredImage>; fun checkpoints(documentId, target: String, mode: ImageExtractionMode, extractorVersion: String): Map<ContentUnitId, ImageCheckpoint>; fun commitCheckpoint(documentId, target, mode, extractorVersion, checkpoint: ImageCheckpoint) }` — `record` and `commitCheckpoint` are upserts that change nothing when repeated; `imagesOf` joins the revision's `image_region` page rows on `(document_id, unit_id, source_image_sha256)` in ordinal order.
  - `CollectionService.updateImageDefault(id: CollectionId, mode: ImageExtractionMode, expectedVersion: Long?): Collection` — same no-op-if-equal and `requireVersion` pattern as `CollectionStore.updateOcrSettings`.

- [ ] **Step 1: Write failing tests.** `SchemaMigratorTest`: a v3 database with a collection and a document migrates to v4, keeps both rows, and `default_image_extraction = 'OFF'`; migrating twice is a no-op. `ImageStoreTest`: `record` twice leaves one row; `commitCheckpoint` twice with the same values leaves one row; `imagesOf` returns only images whose unit has a page row in that revision; deleting the document removes images and checkpoints. `CollectionStoreTest`: setting `EMBEDDED` with a stale version throws `EDIT_CONFLICT`; setting the current value with a stale version returns the collection unchanged.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.storage.SchemaMigratorTest' --tests 'infoscry.storage.ImageStoreTest' --tests 'infoscry.storage.CollectionStoreTest'` — FAIL.
- [ ] **Step 3: Implement** the script (tables and column as the spec's *Storage* section; `CHECK` on enum columns; `ON DELETE CASCADE` from `documents`; comment header like `003_drop_import_roots.sql`), the store and the collection field.
- [ ] **Step 4: Run** the same command — PASS.
- [ ] **Step 5: Commit** `Schema v4: image metadata, image checkpoints and the collection default`.

### Task 3: Image artifacts and filters

**Files:**
- Create: `src/main/kotlin/infoscry/image/ImageArtifacts.kt`
- Create: `src/main/kotlin/infoscry/image/ImageFilter.kt`
- Test: `src/test/kotlin/infoscry/image/ImageArtifactsTest.kt`, `src/test/kotlin/infoscry/image/ImageFilterTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface ImageBytes { data class Encoded(val bytes: ByteArray, val mediaType: String) ; data class Raster(val image: BufferedImage) }` — `Encoded` only for `image/jpeg`, `image/png`, `image/gif`.
  - `data class WrittenImage(val sha256: String, val relativePath: String, val thumbnailRelativePath: String, val mediaType: String, val widthPx: Int, val heightPx: Int)`
  - `class ImageArtifacts(maxImageBytes: Long = ImageExtractor.MAX_IMAGE_BYTES, maxRenderedPixels: Long = PdfExtractor.MAX_RENDERED_PIXELS) { fun write(documentArtifactRoot: Path, bytes: ImageBytes): WrittenImage; fun thumbnailFor(documentArtifactRoot: Path, source: Path, sha256: String): String }` — throws `ImageRejectedException(code)` with `IMAGE_TOO_LARGE` or `IMAGE_DECODE_FAILED`. Relative paths are relative to the document's artifact root.
  - `data class ImageCandidate(val bytes: ImageBytes, val widthPx: Int, val heightPx: Int, val box: NormalizedBox?, val isMask: Boolean)`
  - `enum class SkipReason { TOO_SMALL, REPEATED, MASK, UNIFORM, LIMIT }`
  - `object ImageFilter { const val MIN_SIDE_PX = 96; const val MIN_PAGE_AREA = 0.01; const val MAX_REPEAT_PAGES = 3; const val MAX_PER_DOCUMENT = 500; const val SCANNED_PAGE_COVERAGE = 0.90; fun skipReason(candidate: ImageCandidate, decoded: BufferedImage?): SkipReason?; fun isScanned(box: NormalizedBox?): Boolean }` — repetition and the document limit are decided by the stage (Task 6), not here.

- [ ] **Step 1: Write failing tests.** `ImageArtifactsTest`: writing the same JPEG twice returns the same `WrittenImage` and leaves exactly one image file and one thumbnail, no `*.tmp`; a `Raster` is written as PNG; a 4000×3000 image's thumbnail is 320×240 JPEG; an `Encoded` above `maxImageBytes` → `IMAGE_TOO_LARGE`; garbage bytes → `IMAGE_DECODE_FAILED`; a declared raster above `maxRenderedPixels` is reduced, not refused; a pre-existing `*.tmp` from a killed run does not block the write. `ImageFilterTest`: 95×400 → `TOO_SMALL`; 96×96 → null; box area 0.009 → `TOO_SMALL`; `isMask` → `MASK`; all-white raster → `UNIFORM`; `isScanned` of a box `0.02..0.98` on both axes (area 0.9216) is true and of `0.05..0.97` (area 0.8464) is false.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.image.ImageArtifactsTest' --tests 'infoscry.image.ImageFilterTest'` — FAIL.
- [ ] **Step 3: Implement.** Reuse `ContentAddressedFile.sha256Of`; read dimensions from the ImageIO header before decoding; uniformity = every sampled pixel (64×64 grid) within 2 of the first per channel.
- [ ] **Step 4: Run** the same command — PASS.
- [ ] **Step 5: Commit** `Write image artifacts by content hash and filter trivial images`.

### Task 4: Image finders for embedded and standalone images

**Files:**
- Create: `src/main/kotlin/infoscry/image/ImageFinder.kt` (interface + `ImageFinders` registry by media type)
- Create: `src/main/kotlin/infoscry/image/PdfImageFinder.kt`, `OfficeImageFinder.kt`, `EpubImageFinder.kt`, `StandaloneImageFinder.kt`
- Test: `src/test/kotlin/infoscry/image/PdfImageFinderTest.kt`, `OfficeImageFinderTest.kt`, `EpubImageFinderTest.kt`, `StandaloneImageFinderTest.kt`; fixtures generated in test code (PDFBox, POI, a zipped EPUB), no binary fixtures checked in.

**Interfaces:**
- Consumes: Task 3 `ImageCandidate`, `ImageBytes`.
- Produces:
  - `data class ParentUnit(val unitId: ContentUnitId, val ordinal: Int, val locator: SourceLocation)`
  - `data class FoundImage(val parent: ParentUnit, val kind: ImageSourceKind, val candidate: ImageCandidate, val caption: String?, val captionSource: CaptionSource?)`
  - `data class ParentImages(val images: List<FoundImage>, val failures: List<String>, val scanned: Boolean)` — `failures` are codes; `scanned` is true when the parent has a full-page scan image (PDF only).
  - `interface ImageFinder { val mediaTypes: Set<String>; fun open(managedPath: Path): OpenedSource }` and `interface OpenedSource : AutoCloseable { fun imagesOf(parent: ParentUnit): ParentImages }` — one open document per stage run; a parent whose locator the finder does not own returns an empty result.

- [ ] **Step 1: Write failing tests.** `PdfImageFinderTest`: a page with a 400×300 JPEG drawn at a known matrix yields one `EMBEDDED` image with original JPEG bytes and the box within 0.001 of the drawn rectangle; the same XObject drawn twice on one page yields two occurrences; a Form XObject containing an image yields it; a page whose only image covers 95 % is `scanned = true` with no `FoundImage`; a page with a scan plus a small embedded logo yields the logo; an image with `/SMask` yields only the base image; a JBIG2 or CMYK stream that ImageIO cannot decode yields `failures == [IMAGE_DECODE_FAILED]` and the page's other images. `OfficeImageFinderTest`: docx with two pictures and `descr="Map of Uppsala"` yields them in document order with `ALT_TEXT` caption under their `WordSection` parent; pptx picture on slide 2 belongs to `Slide(2)`. `EpubImageFinderTest`: `<figure><img alt=""><figcaption>Harbour 1920</figcaption></figure>` yields `FIGCAPTION` caption; `alt` wins when present. `StandaloneImageFinderTest`: a PNG yields one `STANDALONE` image whose parent is the `Image(name)` unit.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.image.*FinderTest'` — FAIL.
- [ ] **Step 3: Implement.** PDF: a `PDFStreamEngine` subclass handling `Do` and inline images, box from the CTM over the page's crop box (y flipped); DCT streams as `Encoded(image/jpeg)`, everything else via `PDImageXObject.getImage()` as `Raster`. Office: POI `XWPFPictureData`/`XSLFPictureShape`/`XSSFPicture` in the order the existing extractors walk blocks, mapped to the parent units the extractor emitted (match by locator). EPUB: same spine walk as `EbookExtractors`, resolving `src` inside the archive through `SafeArchive`.
- [ ] **Step 4: Run** the same command — PASS.
- [ ] **Step 5: Commit** `Find embedded images in PDF, Office and EPUB documents`.

### Task 5: Picture regions from scanned pages

**Files:**
- Create: `src/main/kotlin/infoscry/image/LayoutRegions.kt`
- Test: `src/test/kotlin/infoscry/image/LayoutRegionsTest.kt`

**Interfaces:**
- Consumes: `PageStructureStore.read`, `PageImageRenderer.render`, `PageOcrEngines` (Surya engine), Task 4 `FoundImage`.
- Produces:
  - `class LayoutRegions(engines: PageOcrEngines, renderer: PageImageRenderer) { suspend fun regionsOf(parent: ParentUnit, page: PageImage, structure: PageStructure?, settings: OcrSettingsSnapshot): ParentImages }` — uses `structure` when present (the page was read by Surya in this attempt or its baseline), else calls the Surya engine's `transcribe` and uses only its structure's `FIGURE` blocks; the reading's text is discarded. A `CAPTION` block directly below or above a figure (vertical gap ≤ 3 % of page height, horizontal overlap ≥ 50 %) is its caption with `LAYOUT_CAPTION`. Each region is cropped from the page raster as `Raster`.
  - `class SuryaLayoutUnavailableException : IOException` with code `NEEDS_SURYA_LAYOUT`, thrown when no Surya engine is configured or its runtime cannot start.

- [ ] **Step 1: Write failing tests** with a fake Surya `PageOcrEngine` returning a structure: two `FIGURE` blocks and one adjacent `CAPTION` yield two `PAGE_REGION` images with normalized boxes and one caption; a stored structure is used and the fake engine records zero calls; no `FIGURE` blocks → empty; no Surya engine → `SuryaLayoutUnavailableException`; a figure whose crop is 80 px tall is still returned (filtering is Task 6's).
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.image.LayoutRegionsTest'` — FAIL.
- [ ] **Step 3: Implement** `LayoutRegions`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `Cut picture regions out of scanned pages from Surya layout`.

### Task 6: The image stage

**Files:**
- Create: `src/main/kotlin/infoscry/image/ImageStage.kt`
- Create: `src/main/kotlin/infoscry/image/ImageUnitTarget.kt`
- Test: `src/test/kotlin/infoscry/image/ImageStageTest.kt`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces:
  - `const val IMAGE_EXTRACTOR_VERSION = "1"`
  - `interface ImageUnitTarget { val target: String; suspend fun deliver(ordinal: Int, unitId: ContentUnitId, locator: SourceLocation.ImageRegion, caption: String, image: WrittenImage, provenanceRoot: SourceImageRoot) }` — implemented in Tasks 7 and 8.
  - `data class ImageStageResult(val images: Int, val skipped: Map<SkipReason, Int>, val warnings: List<String>)`
  - `class ImageStage(finders: ImageFinders, regions: LayoutRegions, artifacts: ImageArtifacts, store: ImageStore) { suspend fun run(document: Document, managedPath: Path, artifactRoot: Path, parents: List<ParentUnitContext>, mode: ImageExtractionMode, target: ImageUnitTarget, boundary: UnitBoundary, settings: OcrSettingsSnapshot): ImageStageResult }` with `data class ParentUnitContext(val parent: ParentUnit, val page: PageImage?, val structure: PageStructure?)`.
- Rules the stage owns: skip parents with a `DONE` checkpoint for `(target.target, mode, IMAGE_EXTRACTOR_VERSION)`; first pass counts SHA-256 per page across all parents and drops hashes on more than `MAX_REPEAT_PAGES` pages; regions only in `ALL` mode and only for `scanned` PDF pages; per parent, write artifact → `store.record` → `target.deliver` → `commitCheckpoint`, all inside one `boundary.unit {}`; stop adding after `MAX_PER_DOCUMENT` with `IMAGE_LIMIT_REACHED`; `SuryaLayoutUnavailableException` once → warning `NEEDS_SURYA_LAYOUT`, regions off for the rest of the document, embedded images continue; parent ordinal above `MAX_PARENT_ORDINAL` → warning, no images. The stage checks `documents.isDeletionTarget` at each parent boundary, like the text stage.

- [ ] **Step 1: Write failing tests** against an in-memory `ImageUnitTarget` and a Testcontainers `ImageStore`: running twice delivers each image once and the second run makes zero finder calls for committed parents; an interruption thrown after the artifact write, after `record` and after `deliver` each converge on rerun to the same unit IDs and one file per hash; a logo on 4 of 5 pages is skipped as `REPEATED`, on 3 pages it is kept; the 501st image is not delivered and the result warns `IMAGE_LIMIT_REACHED`; Surya missing in `ALL` mode gives one `NEEDS_SURYA_LAYOUT` warning and the embedded images; `EMBEDDED` mode never calls `LayoutRegions`; a document marked for deletion mid-run throws `DocumentBeingDeletedException` and delivers nothing further.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.image.ImageStageTest'` — FAIL.
- [ ] **Step 3: Implement** `ImageStage`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `Run image extraction per parent unit with its own checkpoint`.

### Task 7: Images at import

**Files:**
- Modify: `src/main/kotlin/infoscry/ocr/OcrModels.kt` (`OcrSettingsSnapshot.imageExtraction: ImageExtractionMode = OFF`; excluded from `attemptIdentity()`)
- Modify: `src/main/kotlin/infoscry/jobs/ImportStartService.kt` (`ImportStartRequest.imageExtraction: ImageExtractionMode?`, null = collection default; part of the request identity), `ImportJobPayload.kt`, `ImportPreviewService.kt`
- Modify: `src/main/kotlin/infoscry/extract/DocumentExtractor.kt` (`ContentUnitDraft.unitId: ContentUnitId? = null`), `src/main/kotlin/infoscry/storage/ContentStore.kt` (honour `unitId` on insert)
- Modify: `src/main/kotlin/infoscry/jobs/DocumentIngest.kt` (run `ImageStage` after a finished extraction, before `chunkContent`/`publishStagedReading`)
- Create: `src/main/kotlin/infoscry/jobs/SinkImageTarget.kt` (`ImageUnitTarget` over the attempt's `ExtractionSink`; `target = fingerprint.value`; key `image:<unitId>`)
- Modify: page-centric consumers to ignore `ImageIdentity.isImageOrdinal` / `ImageRegion` units: `document/PageReviewService.kt`, `document/RescanService.kt` (census and pages), `pdf/` selectable-PDF page source, `storage/ContentStore.recordProgress` callers and the document page count
- Modify: `src/main/kotlin/infoscry/AppContext.kt` (wire `ImageStore`, `ImageStage`)
- Test: `src/test/kotlin/infoscry/jobs/ImportImagesTest.kt`, plus the existing tests of each modified consumer

**Interfaces:**
- Consumes: Task 6 `ImageStage.run`, `ImageUnitTarget`.
- Produces: `ImportStartRequest.imageExtraction`; `OcrSettingsSnapshot.imageExtraction`; `SinkImageTarget(sink: ExtractionSink, documentId: DocumentId, fingerprint: ExtractionFingerprint)`.

- [ ] **Step 1: Write failing tests** in `ImportImagesTest` (fake OCR engine with a call counter, generated fixtures): a PDF import with `EMBEDDED` publishes text units plus image units with the captions searchable by keyword and `locator_label` `image 1 on page 2`; an image unit without a caption has no `search_chunks` row; the same import with `OFF` has no image units and the same text fingerprint; a check-and-improve import publishes image units through the candidate; killing the job after the image stage's first parent and resuming makes zero extra OCR calls and yields the same unit IDs; an imported PNG gets its OCR unit and one `STANDALONE` image unit. Consumer tests: page review lists no image units; the rescan census of a document with images counts only PDF pages; selectable-PDF generation over a document with image units produces the same page count; the document's page count excludes images.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportImagesTest'` — FAIL.
- [ ] **Step 3: Implement** the snapshot field, the request plumbing, `SinkImageTarget`, the `DocumentIngest` hook (parents = the units this attempt committed, with page images re-rendered only when `ALL` mode needs a region pass) and the consumer filters.
- [ ] **Step 4: Run** `./gradlew test -PskipFrontend --tests 'infoscry.jobs.*' --tests 'infoscry.document.*' --tests 'infoscry.pdf.*'` — PASS.
- [ ] **Step 5: Commit** `Extract images during import`.

### Task 8: Images at rescan and Keep current text

**Files:**
- Modify: `src/main/kotlin/infoscry/ocr/ReadingMethod.kt` (`data object KeepText : ReadingMethod { id = "keep" }`, accepted only by rescan), `ReadingMethodCatalog.kt`
- Modify: `src/main/kotlin/infoscry/server/OcrRoutes.kt` (`RescanPreviewRequest.imageExtraction: String? = null`), `src/main/kotlin/infoscry/document/RescanService.kt` (preview and admission carry the mode; `KeepText` has no engine, no cost and reads no page)
- Modify: `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt` (with `KeepText` every page goes through `stageBaselineText`; after the page loop and before `STAGE_CHUNK`, run `ImageStage` and carry baseline image units forward)
- Create: `src/main/kotlin/infoscry/jobs/CandidateImageTarget.kt` (`target = candidate revision id`; `revisions.appendPage` with `approval = APPROVED`, `producerRevisionId = candidate`)
- Test: `src/test/kotlin/infoscry/jobs/RescanImagesTest.kt`, `src/test/kotlin/infoscry/document/RescanServiceTest.kt`

**Interfaces:**
- Consumes: Task 6, Task 7 snapshot field.
- Produces: `ReadingMethod.KeepText`; `CandidateImageTarget(revisions: DocumentRevisionStore, candidate: String, document: Document)`.
- Carry-forward rule: when the mode is not `OFF`, baseline image units whose parent is not selected are appended unchanged (keeping their producer); selected parents are extracted fresh. When the mode is `OFF`, no image unit is appended.

- [ ] **Step 1: Write failing tests** in `RescanImagesTest`: a published PDF without images rescanned with `KeepText` + `EMBEDDED` makes zero OCR calls and zero text-layer reads, keeps every page's text byte-identical, and publishes image units; `KeepText` with `OFF` is refused at preview with `METHOD_UNAVAILABLE` ("Keep current text needs images on"); a selected-page rescan of page 2 with `EMBEDDED` re-extracts page 2's images and carries page 5's image unit with its unit ID; then a rescan with `OFF` yields a revision without image units, and restoring the first revision brings its images back (`imagesOf` of the restored revision equals the first's); interrupt after the image stage's first parent and resume → same IDs, no duplicate page rows. `RescanServiceTest`: import with `KeepText` is refused; the preview's page total for `KeepText` is 0.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.jobs.RescanImagesTest' --tests 'infoscry.document.RescanServiceTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew test -PskipFrontend --tests 'infoscry.jobs.*' --tests 'infoscry.document.*' --tests 'infoscry.ocr.*'` — PASS.
- [ ] **Step 5: Commit** `Extract images at rescan, with Keep current text`.

### Task 9: HTTP API, authorization and CLI

**Files:**
- Create: `src/main/kotlin/infoscry/server/ImageRoutes.kt`
- Modify: `src/main/kotlin/infoscry/server/Authorization.kt` (four `GET` rules, `InCollection(READER)`), `Routes.kt` (install; collection OCR settings request gains `defaultImageExtraction`), `UploadRoutes.kt`/import start route (`imageExtraction`)
- Modify: `src/main/kotlin/infoscry/cli/ImportCommand.kt` (`--images off|embedded|all`), `docs/cli.md`
- Test: `src/test/kotlin/infoscry/server/ImageRoutesTest.kt`, `src/test/kotlin/infoscry/server/AuthorizationTableTest.kt` (existing rule-table completeness test), `src/test/kotlin/infoscry/cli/ImportCommandProcessTest.kt`

**Interfaces:**
- Consumes: `ImageStore.imagesOf`, `ImageStore.image`, `DocumentRevisionStore.activeRevisionId`.
- Produces:
  - `@Serializable data class ImageView(val unitId: String, val locator: SourceLocation.ImageRegion, val label: String, val parentUnitId: String, val kind: String, val mediaType: String, val widthPx: Int, val heightPx: Int, val caption: String?, val captionSource: String?)`
  - `GET /api/collections/{id}/documents/{documentId}/images?revision=` → `List<ImageView>`; `…/images/{unitId}` → `ImageView`; `…/images/{unitId}/file` and `…/thumbnail` → bytes. `?revision=` must belong to the document or answers 404.

- [ ] **Step 1: Write failing tests.** `ImageRoutesTest`: a reader lists the published revision's images in ordinal order; `?revision=` of an older revision returns its images; a non-member gets 404 on all four routes; an unknown unit gets 404; `file` answers the stored media type with `X-Content-Type-Options: nosniff` and `Content-Disposition: inline`; an artifact path escaping the document root (forged row) answers 404, never the file; the import start route accepts `imageExtraction: "all"` and a repeated request with another value is a different request. `AuthorizationTableTest`: every route has a rule (existing check passes with the new routes). `ImportCommandProcessTest`: `--images all` sends `imageExtraction = "all"`; `--images bogus` exits nonzero before anything is uploaded.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.server.ImageRoutesTest' --tests 'infoscry.server.AuthorizationTableTest' --tests 'infoscry.cli.ImportCommandProcessTest'` — FAIL.
- [ ] **Step 3: Implement**; byte responses resolve through `ManagedLibrary`/artifact root with the same confinement check as `PageStructureStore.confined`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `Serve document images and choose image extraction from the CLI`.

### Task 10: Web UI

**Files:**
- Modify: `web/src/lib/api.ts` (`ImageView`, `listImages`, `imageFileUrl`, `imageThumbnailUrl`, `imageExtraction` on import start and rescan preview, `defaultImageExtraction` on collection settings)
- Modify: `web/src/lib/StartReadingDialog.svelte` (*Extract images* select: Off / Embedded images / Embedded + regions from scanned pages, pre-filled from the collection; in rescan mode the method list gains *Keep current text*, which requires images not Off)
- Modify: `web/src/lib/CollectionOcrSettings.svelte` (default image extraction, saved with `If-Match`)
- Create: `web/src/lib/ImageStrip.svelte` (thumbnail strip for one unit), `web/src/lib/ImageViewer.svelte` (full-size dialog with caption, "from page 14", box outline on the page image)
- Modify: `web/src/lib/SourceReading.svelte` (strip under each unit with images; a hit or citation on an `image_region` opens the viewer over its parent), `Workspace.svelte` document details (image count)
- Test: `web/src/lib/ImageStrip.test.ts`, `ImageViewer.test.ts`, `StartReadingDialog.test.ts`, `CollectionOcrSettings.test.ts`, `SourceReading.test.ts`

**Interfaces:**
- Consumes: Task 9 routes and `ImageView`.
- Produces: `ImageStrip` props `{ collectionId, documentId, images: ImageView[], onOpen(image) }`; `ImageViewer` props `{ collectionId, documentId, image: ImageView, open: boolean, onClose() }`.

- [ ] **Step 1: Write failing tests.** `ImageStrip`: renders one `<img>` per image with `alt` = caption, else `Image 2 on page 14`; Enter on a thumbnail calls `onOpen`. `ImageViewer`: shows caption and `from page 14`; region images render an outline element with the box as percentages; Escape closes and returns focus. `StartReadingDialog`: the select is pre-filled from the collection default; choosing *Keep current text* with Off disables Start with the message `Choose which images to extract.`; the chosen value is sent. `CollectionOcrSettings`: saving sends `defaultImageExtraction` with `If-Match`; a 409 shows the existing conflict message. `SourceReading`: a citation on an `image_region` locator opens the viewer.
- [ ] **Step 2: Run** `cd web && npm test -- --run` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `cd web && npm test -- --run && npm run check` — PASS.
- [ ] **Step 5: Commit** `Show, open and cite document images in the reader`.

### Task 11: Browser coverage, docs and gates

**Files:**
- Create: `src/test/kotlin/infoscry/server/ImagesBrowserAcceptanceTest.kt` (`externalTest`, signs in first, fake providers and embeddings, fake Surya layout)
- Modify: `README.md`, `AGENTS.md` (one line on `ImagesBrowserAcceptanceTest`), `docs/usage.md`, `docs/technical-reference.md` (schema v4, image stage, storage), `docs/implementation-status.md`, spec status line
- Test: the acceptance test; full gates

- [ ] **Step 1: Write the acceptance scenarios:** import a generated PDF with *Embedded images*, see two thumbnails under page 2, open one and see its caption; search for the caption and open the hit; rescan the document with *Keep current text* + *Embedded + regions from scanned pages* and see a region outlined on the scanned page; Ask cites the caption's image and the citation opens the viewer.
- [ ] **Step 2: Run** the acceptance test as `DocumentBrowserAcceptanceTest` is run in `docs/development.md` — FAIL before the scenarios' fixtures exist, then PASS.
- [ ] **Step 3: Real Surya layout check on this Mac:** import a rendered page with a photograph using *Embedded + regions from scanned pages* and Tesseract reading; record in `docs/implementation-status.md` whether the region was found and its box, with the date. Do not mark region extraction verified without this run.
- [ ] **Step 4: Update docs** (status, usage of the dialog choice and Keep current text, CLI flag, technical reference) and run link/consistency checks.
- [ ] **Step 5: Run** `./gradlew check` — PASS; record JVM and frontend test counts in `docs/implementation-status.md`.
- [ ] **Step 6: Commit** `Images: browser coverage, docs and gates`.
