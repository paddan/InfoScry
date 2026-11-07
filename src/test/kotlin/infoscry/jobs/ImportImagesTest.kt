package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.JobState
import infoscry.domain.NormalizedBox
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DocumentExtractor
import infoscry.extract.EXTRACTOR_SCHEMA_VERSION
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.ExtractionSink
import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.PAGE_IMAGES_UNSUPPORTED_CODE
import infoscry.extract.PageImageSupport
import infoscry.extract.TextualFallbackExtractor
import infoscry.image.FinderFixtures
import infoscry.image.FoundImage
import infoscry.image.ImageArtifacts
import infoscry.image.ImageBytes
import infoscry.image.ImageCandidate
import infoscry.image.ImageExtractionMode
import infoscry.image.ImageFinder
import infoscry.image.ImageFinders
import infoscry.image.ImageIdentity
import infoscry.image.ImageSourceKind
import infoscry.image.ImageStage
import infoscry.image.LayoutRegions
import infoscry.image.OpenedSource
import infoscry.image.ParentImages
import infoscry.image.ParentUnit
import infoscry.image.StandaloneImageFinder
import infoscry.image.WrittenImage
import infoscry.ocr.CandidateRevisionSink
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageOcrEngines
import infoscry.storage.CaptionSource
import infoscry.storage.PageApproval
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * What an import does with a document's images: the stage runs after the text is complete, its units are
 * committed through the same sink as the text, and everything it leaves converges after an interruption.
 *
 * The text extractors and the image finders are table-driven fakes (a call counter stands in for OCR cost);
 * the stores, the sinks, the runner and the publication are the real ones.
 */
class ImportImagesTest {

    @Test
    fun `an embedded-mode import publishes image units whose captions are searchable`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val extractor = Pages(pages = 3)
            val run = runImport(harness, source, snapshot(ImageExtractionMode.EMBEDDED), extractor, tableFinder())

            assertEquals(JobState.COMPLETE, run.job.state)
            AppContext.open(harness.dataDir).use { context ->
                val documentId = run.documentId
                assertEquals(DocumentStatus.COMPLETE, context.documents.get(documentId)!!.status)
                val units = context.content.listUnits(documentId, afterOrdinal = -1, limit = 50)
                val text = units.filter { !ImageIdentity.isImageOrdinal(it.ordinal) }
                val images = units.filter { ImageIdentity.isImageOrdinal(it.ordinal) }
                assertEquals(listOf(0, 1, 2), text.map { it.ordinal })
                assertEquals(
                    listOf(ImageIdentity.ordinal(1, 1), ImageIdentity.ordinal(2, 1)),
                    images.map { it.ordinal },
                )
                assertEquals("Sunset over the harbor", images[0].extractedText)
                assertEquals("", images[1].extractedText)

                val hit = context.index().searchKeyword(CollectionId("default"), "harbor", limit = 10).single()
                assertEquals(images[0].id.value, hit.unitId)
                assertEquals("image 1 on page 2", hit.locatorLabel)
                assertTrue(context.content.chunksOf(images[1].id).isEmpty(), "a picture without a caption has no passage")
                assertEquals(
                    4,
                    context.index().chunkCount(CollectionId("default"), documentId),
                    "three pages and the one caption are searchable; the uncaptioned picture is not",
                )
                assertEquals(
                    0,
                    searchRowsOf(context, images[1].id),
                    "an image unit without a caption has no search_chunks row",
                )

                // Progress counts pages: an image unit is neither "page 4 of 3" nor a unit read without OCR.
                val progress = context.content.documentProgress(listOf(documentId)).getValue(documentId)
                assertEquals(UnitKind.PAGE, progress.unitKind)
                assertEquals(3, progress.totalUnits)
                assertEquals(3, progress.processedUnits)
                assertEquals(3, progress.ocrUnits)
                assertEquals(0, progress.directTextUnits)

                // R3: the published revision names the image files, so a revision finds its images.
                val revisionId = assertNotNull(context.revisions.activeRevisionId(documentId))
                val stored = context.imageStore.imagesOf(revisionId)
                assertEquals(images.map { it.id }, stored.map { it.unitId })
                assertEquals(listOf(CaptionSource.ALT_TEXT, null), stored.map { it.captionSource })
                val page = context.revisions.pages(revisionId).single { it.unitId == images[0].id }
                assertEquals(SourceImageRoot.ARTIFACTS, page.sourceImage!!.root)
                assertTrue(page.sourceImage!!.relativePath.startsWith("images/"))
                assertEquals(PageApproval.APPROVED, page.approval)
            }
        }
    }

    @Test
    fun `the same import with images off has no image units and the same text fingerprint`() {
        val fingerprints = listOf(ImageExtractionMode.OFF, ImageExtractionMode.EMBEDDED).map { mode ->
            withHarnessResult<Set<String>> { harness ->
                val source = harness.writeText("album.txt", "scanned album\n")
                val run = runImport(harness, source, snapshot(mode), Pages(pages = 3), tableFinder())
                AppContext.open(harness.dataDir).use { context ->
                    val units = context.content.listUnits(run.documentId, afterOrdinal = -1, limit = 50)
                    val images = units.count { ImageIdentity.isImageOrdinal(it.ordinal) }
                    assertEquals(if (mode == ImageExtractionMode.OFF) 0 else 2, images)
                    fingerprintsOf(context, run.documentId)
                }
            }
        }
        assertEquals(fingerprints[0], fingerprints[1])
        assertEquals(1, fingerprints[0].size)
    }

    @Test
    fun `a check-and-improve import publishes its image units through the candidate`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val extractor = Pages(pages = 3, pageImageSupport = PageImageSupport.Supported)
            val run = runImport(
                harness, source, snapshot(ImageExtractionMode.EMBEDDED, OcrImportMode.CHECK_AND_IMPROVE), extractor,
                tableFinder(), staging = true,
            )

            assertEquals(JobState.COMPLETE, run.job.state)
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(DocumentStatus.COMPLETE, context.documents.get(run.documentId)!!.status)
                val revisionId = assertNotNull(context.revisions.activeRevisionId(run.documentId))
                val pages = context.revisions.pages(revisionId)
                val imagePages = pages.filter { ImageIdentity.isImageOrdinal(it.ordinal) }
                assertEquals(2, imagePages.size)
                assertTrue(imagePages.all { it.approval == PageApproval.APPROVED && it.sourceImage != null })
                assertEquals(imagePages.map { it.unitId }, context.imageStore.imagesOf(revisionId).map { it.unitId })
                val hit = context.index().searchKeyword(CollectionId("default"), "harbor", limit = 10).single()
                assertEquals("image 1 on page 2", hit.locatorLabel)
                assertEquals(0, context.revisions.pages(revisionId).count { it.approval == PageApproval.PENDING })
            }
        }
    }

    @Test
    fun `killing the job after the first parent's images and resuming reads nothing twice and keeps the unit ids`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val parked = CompletableDeferred<Unit>()
            val first = Pages(pages = 3)
            val firstFinder = tableFinder()
            val jobId = AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(source), ocr = snapshot(ImageExtractionMode.EMBEDDED))
                harness.attach(
                    context,
                    pipeline(context, first, imagesWith(context, firstFinder), parkAfterImages(context, parked, 2)),
                )
                runBlocking { withTimeout(10_000) { parked.await() } }
                job.id
            }
            assertEquals(listOf("page-0", "page-1", "page-2"), first.produced)
            assertEquals(listOf(1, 2, 3), firstFinder.pagesAsked, "every parent was looked at before the kill")

            val second = Pages(pages = 3)
            val secondFinder = tableFinder()
            AppContext.open(harness.dataDir).use { context ->
                harness.attach(context, pipeline(context, second, imagesWith(context, secondFinder)))
                assertEquals(JobState.COMPLETE, runBlocking { awaitTerminal(context, jobId) }.state)
                val documentId = context.importItems.listForJob(jobId).single().documentId!!

                assertEquals(emptyList(), second.produced, "committed OCR is not repeated")
                assertEquals(listOf(3), secondFinder.pagesAsked, "the parent that finished is not looked at again")
                val images = context.content.listUnits(documentId, afterOrdinal = -1, limit = 50)
                    .filter { ImageIdentity.isImageOrdinal(it.ordinal) }
                assertEquals(2, images.size)
                assertEquals(2, imageRows(context, documentId))
                assertEquals(DocumentStatus.COMPLETE, context.documents.get(documentId)!!.status)

                // The ids are those an uninterrupted import of the same document gives.
                val reference = withHarnessResult { other ->
                    val again = other.writeText("album.txt", "scanned album\n")
                    val run = runImport(other, again, snapshot(ImageExtractionMode.EMBEDDED), Pages(3), tableFinder())
                    AppContext.open(other.dataDir).use { c ->
                        c.content.listUnits(run.documentId, afterOrdinal = -1, limit = 50)
                            .filter { ImageIdentity.isImageOrdinal(it.ordinal) }.map { it.ordinal }
                    }
                }
                assertEquals(reference, images.map { it.ordinal })
            }
        }
    }

    @Test
    fun `an imported picture gets its reading unit and one standalone image unit`() {
        withHarness { harness ->
            val source = harness.sourcesDir.resolve("scan.png")
            writePng(source)
            val extractor = object : DocumentExtractor {
                override val supportedMediaTypes = setOf("image/png")
                override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
                    input.boundary.unit {
                        emit(
                            ExtractionEvent.UnitReady(
                                "image-0", 0,
                                ContentUnitDraft(
                                    locator = SourceLocation.Image("scan.png"), extractedText = "a read sentence",
                                    searchText = "a read sentence", method = ExtractionMethod.OCR,
                                ),
                            ),
                        )
                    }
                    emit(ExtractionEvent.Finished(emptyMap(), 1))
                }
            }
            val run = runImport(
                harness, source, snapshot(ImageExtractionMode.EMBEDDED), extractor,
                finder = null, standard = true,
            )

            assertEquals(JobState.COMPLETE, run.job.state)
            AppContext.open(harness.dataDir).use { context ->
                val units = context.content.listUnits(run.documentId, afterOrdinal = -1, limit = 10)
                assertEquals(listOf(0, ImageIdentity.ordinal(0, 1)), units.map { it.ordinal })
                val image = context.imageStore.imagesOf(context.revisions.activeRevisionId(run.documentId)!!).single()
                assertEquals(ImageSourceKind.STANDALONE, image.kind)
                assertEquals(units[1].id, image.unitId)
                val page = context.revisions.pages(context.revisions.activeRevisionId(run.documentId)!!).last()
                assertEquals(SourceImageRoot.MANAGED_COPY, page.sourceImage!!.root)
                assertEquals("image scan.png", page.locator.describe())
            }
        }
    }

    @Test
    fun `an imported photograph that OCR reads as empty still gets its image unit`() {
        withHarness { harness ->
            val source = harness.sourcesDir.resolve("photo.png")
            Files.write(source, FinderFixtures.png(240, 180, 3))
            val empty = object : infoscry.ocr.PageOcrEngine {
                override val engine = OcrEngine.TESSERACT
                override suspend fun transcribe(page: infoscry.ocr.PageImage, settings: OcrSettingsSnapshot) =
                    infoscry.ocr.OcrPageResult(
                        text = "", engine = OcrEngine.TESSERACT, imageSha256 = page.sha256,
                        errorCode = infoscry.ocr.OcrPageResult.EMPTY_READING_CODE,
                    )
            }
            val run = runImport(
                harness, source, snapshot(ImageExtractionMode.EMBEDDED),
                infoscry.extract.ImageExtractor(PageOcrEngines(listOf(empty))), finder = null, standard = true,
            )

            assertEquals(JobState.COMPLETE, run.job.state)
            AppContext.open(harness.dataDir).use { context ->
                val units = context.content.listUnits(run.documentId, afterOrdinal = -1, limit = 10)
                assertEquals(listOf(0, ImageIdentity.ordinal(0, 1)), units.map { it.ordinal })
                assertEquals("", units[0].extractedText)
                val document = context.documents.get(run.documentId)!!
                assertEquals(DocumentStatus.COMPLETE_WITH_WARNINGS, document.status, "${document.errorCode}: ${document.errorMessage}")
                val image = context.imageStore.imagesOf(context.revisions.activeRevisionId(run.documentId)!!).single()
                assertEquals(ImageSourceKind.STANDALONE, image.kind)
                assertEquals(units[1].id, image.unitId)
            }
        }
    }

    @Test
    fun `a check-and-improve import of a photograph read as empty publishes through the candidate`() {
        withHarness { harness ->
            val source = harness.sourcesDir.resolve("photo.png")
            Files.write(source, FinderFixtures.png(240, 180, 3))
            val empty = emptyReadingEngine()
            val run = runImport(
                harness, source, snapshot(ImageExtractionMode.EMBEDDED, OcrImportMode.CHECK_AND_IMPROVE),
                infoscry.extract.ImageExtractor(PageOcrEngines(listOf(empty))), finder = null, standard = true,
                staging = true,
            )

            assertEquals(JobState.COMPLETE, run.job.state)
            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.get(run.documentId)!!
                assertEquals(DocumentStatus.COMPLETE_WITH_WARNINGS, document.status, "${document.errorCode}: ${document.errorMessage}")
                val revisionId = assertNotNull(context.revisions.activeRevisionId(run.documentId))
                val pages = context.revisions.pages(revisionId)
                assertEquals(listOf(0, ImageIdentity.ordinal(0, 1)), pages.map { it.ordinal })
                assertEquals(0, context.index().chunkCount(CollectionId("default"), run.documentId))
                assertEquals(ImageSourceKind.STANDALONE, context.imageStore.imagesOf(revisionId).single().kind)
            }
        }
    }

    @Test
    fun `an empty page beside a picture-only page is still an embedding failure, not a picture-only document`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val run = runImport(
                harness, source, snapshot(ImageExtractionMode.EMBEDDED), Pages(pages = 2, emptyPages = setOf(0, 1)),
                TableFinder(mapOf(2 to listOf(embedded(null, seed = 5)))),
            )

            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.get(run.documentId)!!
                assertEquals(DocumentStatus.FAILED, document.status)
                assertEquals("EMBEDDING_FAILED", document.errorCode)
            }
        }
    }

    @Test
    fun `an embedded then off then embedded import of one failed document delivers the images again`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val first = runImport(harness, source, snapshot(ImageExtractionMode.EMBEDDED), Pages(3), tableFinder())
            fun reset() = AppContext.open(harness.dataDir).use { context ->
                context.documents.updateStatus(first.documentId, DocumentStatus.FAILED, "EXTRACTION_FAILED")
            }
            reset()
            runImport(harness, source, snapshot(ImageExtractionMode.OFF), Pages(3), tableFinder())
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(0, context.content.listUnits(first.documentId, afterOrdinal = -1, limit = 50).count { ImageIdentity.isImageOrdinal(it.ordinal) })
            }
            reset()

            val third = runImport(harness, source, snapshot(ImageExtractionMode.EMBEDDED), Pages(3), tableFinder())
            assertEquals(first.documentId, third.documentId)
            assertImagesDelivered(harness, first.documentId)

            reset()
            runImport(harness, source, snapshot(ImageExtractionMode.EMBEDDED), Pages(3), tableFinder())
            assertImagesDelivered(harness, first.documentId)
        }
    }

    private fun assertImagesDelivered(harness: Harness, documentId: DocumentId) {
        AppContext.open(harness.dataDir).use { context ->
            val images = context.content.listUnits(documentId, afterOrdinal = -1, limit = 50)
                .filter { ImageIdentity.isImageOrdinal(it.ordinal) }
            assertEquals(2, images.size)
            assertEquals(2, imageRows(context, documentId))
            val counted = context.database.read { connection ->
                connection.prepareStatement(
                    "SELECT COALESCE(SUM(image_count), 0) FROM image_extraction_checkpoints WHERE document_id = ?",
                ).use { statement ->
                    statement.setString(1, documentId.value)
                    statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
            }
            assertEquals(2, counted, "the cap counts the images that exist")
        }
    }

    private fun emptyReadingEngine() = object : infoscry.ocr.PageOcrEngine {
        override val engine = OcrEngine.TESSERACT
        override suspend fun transcribe(page: infoscry.ocr.PageImage, settings: OcrSettingsSnapshot) =
            infoscry.ocr.OcrPageResult(
                text = "", engine = OcrEngine.TESSERACT, imageSha256 = page.sha256,
                errorCode = infoscry.ocr.OcrPageResult.EMPTY_READING_CODE,
            )
    }

    @Test
    fun `an image warning completes the document with a warning and keeps its text`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val finder = object : TableFinder(emptyMap()) {
                override fun open(managedPath: Path): OpenedSource = throw java.io.IOException("unreadable")
            }
            val run = runImport(harness, source, snapshot(ImageExtractionMode.EMBEDDED), Pages(2), finder)

            assertEquals(JobState.COMPLETE, run.job.state)
            AppContext.open(harness.dataDir).use { context ->
                val document = context.documents.get(run.documentId)!!
                assertEquals(DocumentStatus.COMPLETE_WITH_WARNINGS, document.status)
                assertEquals("IMAGE_SOURCE_UNREADABLE", document.errorCode, "the document names the image warning")
                assertTrue(document.errorMessage!!.isNotBlank())
                assertEquals(2, context.index().chunkCount(CollectionId("default"), run.documentId))
            }
        }
    }

    @Test
    fun `delivering an image again at another ordinal moves the unit in the plain sink and in a candidate`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val run = runImport(harness, source, snapshot(ImageExtractionMode.OFF), Pages(2), tableFinder())
            AppContext.open(harness.dataDir).use { context ->
                val documentId = run.documentId
                val document = context.documents.get(documentId)!!
                val fingerprint = ExtractionFingerprint.of(document.sha256, ExtractionSettings(ocrLanguages = "eng"))
                val unitId = ContentUnitId("00000000-0000-5000-8000-000000000001")
                val sinks = listOf(
                    StoredUnitsSink(context.paths, context.documents, context.content),
                    CandidateRevisionSink(context.revisions, documentId, "TEST", baselineRevisionId = null),
                )
                for (sink in sinks) {
                    val target = SinkImageTarget(sink, documentId, fingerprint)
                    val image = writtenImage()
                    val locator = SourceLocation.ImageRegion(SourceLocation.PdfPage(1), 1, null)
                    val first = ImageIdentity.ordinal(0, 1)!!
                    val moved = ImageIdentity.ordinal(0, 2)!!
                    runBlocking {
                        target.deliver(first, unitId, locator, "once", image, SourceImageRoot.ARTIFACTS)
                        target.deliver(first, unitId, locator, "once", image, SourceImageRoot.ARTIFACTS)
                        target.deliver(moved, unitId, locator, "once", image, SourceImageRoot.ARTIFACTS)
                    }
                    val ordinals = if (sink is CandidateRevisionSink) {
                        context.revisions.pages(assertNotNull(sink.candidateRevisionId)).map { it.ordinal to it.unitId }
                    } else {
                        context.content.listUnits(documentId, afterOrdinal = -1, limit = 50).map { it.ordinal to it.id }
                    }.filter { ImageIdentity.isImageOrdinal(it.first) }
                    assertEquals(listOf(moved to unitId), ordinals, "${sink.javaClass.simpleName} kept one row, at the new ordinal")
                }
            }
        }
    }

    @Test
    fun `retaining image units removes the rest in the plain sink and in a candidate, and repeating changes nothing`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val run = runImport(harness, source, snapshot(ImageExtractionMode.OFF), Pages(2), tableFinder())
            AppContext.open(harness.dataDir).use { context ->
                val documentId = run.documentId
                val document = context.documents.get(documentId)!!
                val fingerprint = ExtractionFingerprint.of(document.sha256, ExtractionSettings(ocrLanguages = "eng"))
                val kept = ContentUnitId("00000000-0000-5000-8000-000000000001")
                val stale = ContentUnitId("00000000-0000-5000-8000-000000000002")
                val sinks = listOf(
                    StoredUnitsSink(context.paths, context.documents, context.content),
                    CandidateRevisionSink(context.revisions, documentId, "TEST", baselineRevisionId = null),
                )
                for (sink in sinks) {
                    val target = SinkImageTarget(sink, documentId, fingerprint)
                    val locator = SourceLocation.ImageRegion(SourceLocation.PdfPage(1), 1, null)
                    val first = ImageIdentity.ordinal(0, 1)!!
                    val second = ImageIdentity.ordinal(0, 2)!!
                    val outsideBand = ImageIdentity.ordinal(1, 1)!!
                    val other = ContentUnitId("00000000-0000-5000-8000-000000000003")
                    runBlocking {
                        target.deliver(first, kept, locator, "kept", writtenImage(), SourceImageRoot.ARTIFACTS)
                        target.deliver(second, stale, locator, "stale", writtenImage(), SourceImageRoot.ARTIFACTS)
                        target.deliver(outsideBand, other, locator, "other parent", writtenImage(), SourceImageRoot.ARTIFACTS)
                        repeat(2) { target.retainImages(first, ImageIdentity.ordinal(0, ImageIdentity.MAX_PER_PARENT)!!, setOf(kept)) }
                    }
                    fun held(): List<ContentUnitId> = if (sink is CandidateRevisionSink) {
                        context.revisions.pages(assertNotNull(sink.candidateRevisionId)).map { it.ordinal to it.unitId }
                    } else {
                        context.content.listUnits(documentId, afterOrdinal = -1, limit = 50).map { it.ordinal to it.id }
                    }.filter { ImageIdentity.isImageOrdinal(it.first) }.map { it.second }
                    assertEquals(listOf(kept, other), held(), "${sink.javaClass.simpleName} dropped only the stale unit in the band")
                    runBlocking { target.retainImages(ImageIdentity.ORDINAL_BASE, Int.MAX_VALUE, emptySet()) }
                    assertEquals(emptyList(), held(), "${sink.javaClass.simpleName} dropped every image unit")
                }
            }
        }
    }

    @Test
    fun `importing again with images off removes the image units an earlier import left`() {
        withHarness { harness ->
            val source = harness.writeText("album.txt", "scanned album\n")
            val first = runImport(harness, source, snapshot(ImageExtractionMode.EMBEDDED), Pages(3), tableFinder())
            AppContext.open(harness.dataDir).use { context ->
                assertEquals(2, context.content.listUnits(first.documentId, afterOrdinal = -1, limit = 50).count { ImageIdentity.isImageOrdinal(it.ordinal) })
                // A document that did not finish is read again by the next import.
                context.documents.updateStatus(first.documentId, DocumentStatus.FAILED, "EXTRACTION_FAILED")
            }

            val again = runImport(harness, source, snapshot(ImageExtractionMode.OFF), Pages(3), tableFinder())

            assertEquals(first.documentId, again.documentId)
            AppContext.open(harness.dataDir).use { context ->
                val units = context.content.listUnits(first.documentId, afterOrdinal = -1, limit = 50)
                assertEquals(0, units.count { ImageIdentity.isImageOrdinal(it.ordinal) })
                assertEquals(3, context.index().chunkCount(CollectionId("default"), first.documentId))
            }
        }
    }

    // ---- fixtures ----

    private fun snapshot(
        images: ImageExtractionMode,
        mode: OcrImportMode = OcrImportMode.FILL_MISSING,
    ) = OcrSettingsSnapshot(
        engine = OcrEngine.TESSERACT,
        mode = mode,
        language = "eng",
        extractorVersion = EXTRACTOR_SCHEMA_VERSION,
        imageExtraction = images,
    )

    private class Imported(val job: infoscry.domain.Job, val documentId: DocumentId)

    private fun runImport(
        harness: Harness,
        source: Path,
        snapshot: OcrSettingsSnapshot,
        extractor: DocumentExtractor,
        finder: ImageFinder?,
        staging: Boolean = false,
        standard: Boolean = false,
    ): Imported = AppContext.open(harness.dataDir).use { context ->
        val job = harness.enqueueForTest(context, listOf(source), ocr = snapshot)
        val images = if (standard) {
            ImageStage(
                ImageFinders(listOf(StandaloneImageFinder())), LayoutRegions(PageOcrEngines(emptyList())),
                ImageArtifacts(), context.imageStore, context.documents,
            )
        } else {
            imagesWith(context, finder!!)
        }
        harness.attach(context, pipeline(context, extractor, images, staging = staging))
        val finished = harness.awaitJob(context, job.id)
        Imported(finished, context.importItems.listForJob(job.id).single().documentId!!)
    }

    private fun imagesWith(context: AppContext, finder: ImageFinder) = ImageStage(
        ImageFinders(listOf(finder)), LayoutRegions(PageOcrEngines(emptyList())), ImageArtifacts(),
        context.imageStore, context.documents,
    )

    private fun pipeline(
        context: AppContext,
        extractor: DocumentExtractor,
        images: ImageStage,
        sink: ExtractionSink = StoredUnitsSink(context.paths, context.documents, context.content, context.corrections),
        staging: Boolean = false,
    ) = ImportPipeline(
        detector = MediaTypeDetector(),
        registry = ExtractorRegistry(listOf(extractor), TextualFallbackExtractor()),
        sink = sink,
        candidateSinkFor = if (staging) {
            { documentId, reading ->
                CandidateRevisionSink(
                    revisions = context.revisions,
                    documentId = documentId,
                    provenance = ImportPipeline.PROVENANCE_CHECK_AND_IMPROVE,
                    readingSnapshot = reading,
                )
            }
        } else {
            null
        },
        images = images,
    )

    /** A plain sink that parks the attempt once [count] image units have been delivered through it. */
    private fun parkAfterImages(context: AppContext, parked: CompletableDeferred<Unit>, count: Int): ExtractionSink {
        val delegate = StoredUnitsSink(context.paths, context.documents, context.content, context.corrections)
        return object : ExtractionSink by delegate {
            private var delivered = 0

            override suspend fun deliver(documentId: DocumentId, fingerprint: ExtractionFingerprint, event: ExtractionEvent) =
                deliver(documentId, fingerprint, event, approval = null)

            override suspend fun deliver(
                documentId: DocumentId,
                fingerprint: ExtractionFingerprint,
                event: ExtractionEvent,
                approval: PageApproval?,
            ) {
                delegate.deliver(documentId, fingerprint, event, approval)
                if (event is ExtractionEvent.UnitReady && event.key.startsWith("image:") && ++delivered == count) {
                    parked.complete(Unit)
                    awaitCancellation()
                }
            }
        }
    }

    /** Three pages; page 2 has a captioned picture and page 3 an uncaptioned one. */
    private fun tableFinder() = TableFinder(
        mapOf(
            2 to listOf(embedded("Sunset over the harbor", seed = 1)),
            3 to listOf(embedded(null, seed = 2)),
        ),
    )

    private fun embedded(caption: String?, seed: Int) = FoundImage(
        ParentUnit(ContentUnitId("unused"), 0, SourceLocation.PdfPage(1)),
        ImageSourceKind.EMBEDDED,
        ImageCandidate(
            ImageBytes.Encoded(FinderFixtures.png(240, 180, seed), "image/png"), 240, 180,
            NormalizedBox(0.1, 0.1, 0.6, 0.6), isMask = false,
        ),
        caption,
        caption?.let { CaptionSource.ALT_TEXT },
    )

    private fun writtenImage(): WrittenImage {
        val sha = "c".repeat(64)
        return WrittenImage(sha, "images/$sha.png", "images/thumbs/$sha.png", "image/png", 240, 180)
    }

    private fun searchRowsOf(context: AppContext, unitId: ContentUnitId): Int = context.database.read { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM search_chunks WHERE content_unit_id = ?").use { statement ->
            statement.setString(1, unitId.value)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun imageRows(context: AppContext, documentId: DocumentId): Int = context.database.read { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM document_images WHERE document_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun fingerprintsOf(context: AppContext, documentId: DocumentId): Set<String> =
        context.database.read { connection ->
            connection.prepareStatement(
                "SELECT DISTINCT fingerprint FROM extraction_checkpoints WHERE document_id = ?",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    private fun withHarness(block: (Harness) -> Unit) = withHarnessResult(block)

    private fun <T> withHarnessResult(block: (Harness) -> T): T {
        val directory = Files.createTempDirectory("infoscry-import-images")
        try {
            return Harness(directory).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** Text pages of the media type `text/plain`; counts the pages it reads, which is what OCR would cost. */
    private class Pages(
        private val pages: Int,
        private val emptyPages: Set<Int> = emptySet(),
        override val pageImageSupport: PageImageSupport = PageImageSupport.Unsupported(PAGE_IMAGES_UNSUPPORTED_CODE),
    ) : DocumentExtractor {
        override val supportedMediaTypes = setOf("text/plain")
        val produced = CopyOnWriteArrayList<String>()

        override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
            emit(ExtractionEvent.Progress(UnitKind.PAGE, pages))
            repeat(pages) { ordinal ->
                val key = "page-$ordinal"
                if (!input.isCommitted(key)) {
                    produced += key
                    val text = if (ordinal in emptyPages) "" else "page text $ordinal"
                    input.boundary.unit {
                        emit(
                            ExtractionEvent.UnitReady(
                                key, ordinal,
                                ContentUnitDraft(
                                    locator = SourceLocation.PdfPage(ordinal + 1),
                                    extractedText = text, searchText = text,
                                    method = ExtractionMethod.OCR,
                                ),
                            ),
                        )
                    }
                }
            }
            emit(ExtractionEvent.Finished(emptyMap(), pages))
        }
    }

    /** Finds the pictures a table lists for each page number, and records which pages it was asked about. */
    private open class TableFinder(private val byPage: Map<Int, List<FoundImage>>) : ImageFinder {
        override val mediaTypes: Set<String> = setOf("text/plain")
        val pagesAsked = CopyOnWriteArrayList<Int>()

        override fun open(managedPath: Path): OpenedSource = object : OpenedSource {
            override fun imagesOf(parent: ParentUnit): ParentImages {
                val page = (parent.locator as SourceLocation.PdfPage).page
                pagesAsked += page
                val found = byPage[page].orEmpty().map { it.copy(parent = parent) }
                return ParentImages(found, emptyList(), scanned = false)
            }

            override fun close() = Unit
        }
    }
}
