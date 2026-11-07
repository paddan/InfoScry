package infoscry.jobs

import infoscry.AppContext
import infoscry.document.RescanPageSource
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.NormalizedBox
import infoscry.domain.SourceLocation
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.PngPageRenderer
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
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.PageOcrEngines
import infoscry.ocr.ReadingMethod
import infoscry.ocr.ReadingMethodUnavailableException
import infoscry.ocr.RescanSelection
import infoscry.storage.CaptionSource
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageText
import infoscry.storage.StoredImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What a rescan does with a document's images: the image step runs beside the candidate's text, carries what the
 * baseline holds for pages outside the rescan's scope, and converges after an interruption.
 *
 * The image finder is a table-driven fake and a call counter stands in for OCR cost; the stores, the candidate
 * revision, the publication and Restore are the real ones.
 */
class RescanImagesTest {

    @Test
    fun `keep current text reads nothing, keeps every page's text byte for byte and publishes image units`() {
        withHarness { harness ->
            // The published text of page one is not what its own text layer holds, so a layer read would show.
            val picture = harness.importCleanAndScannedPdf(
                cleanText = "The text layer of the first page says something else entirely",
                scannedText = "published scanned page",
                publishedCleanText = "published text of the first page",
            )
            val finder = TableFinder(mapOf(1 to listOf(embedded("Sunset over the harbor", 1)), 2 to listOf(embedded(null, 2))))
            val renders = AtomicInteger()
            harness.pageSource = RescanPageSource(
                draw = { renderer, page, dpi, directory ->
                    renders.incrementAndGet()
                    PngPageRenderer.render(renderer, page, dpi, directory)
                },
            )
            harness.useImages(finder, ImageExtractionMode.EMBEDDED)
            val before = harness.publishedPagesOf(picture)
            val engine = FakePageEngine(readings = listOf("must never be read"))

            val attempt = harness.rescan(picture, engine, method = ReadingMethod.KeepText)

            assertNull(attempt.failure)
            assertEquals(OcrOperationStage.COMPLETE, attempt.operation.stage)
            assertNull(attempt.operation.errorCode)
            assertEquals(DocumentStatus.COMPLETE, attempt.documentStatus)
            assertEquals(0, engine.calls, "no OCR call")
            assertEquals(0, renders.get(), "no page was rendered")
            val after = harness.publishedPagesOf(picture)
            assertEquals(
                before.map { it.ordinal to it.extractedText },
                after.filterNot { ImageIdentity.isImageOrdinal(it.ordinal) }.map { it.ordinal to it.extractedText },
                "every page's text is carried byte for byte",
            )
            assertEquals(before.map { it.producerRevisionId }, after.filterNot { isImage(it) }.map { it.producerRevisionId })
            assertEquals(
                listOf(ImageIdentity.ordinal(0, 1), ImageIdentity.ordinal(1, 1)),
                after.filter { isImage(it) }.map { it.ordinal },
            )
            AppContext.open(harness.archiveDir).use { context ->
                val stored = context.imageStore.imagesOf(assertNotNull(attempt.activeRevisionId))
                assertEquals(listOf("Sunset over the harbor", null), stored.map { it.caption })
                assertEquals(listOf(CaptionSource.ALT_TEXT, null), stored.map { it.captionSource })
                val hit = context.index().searchKeyword(CollectionId("default"), "harbor", limit = 10).single()
                assertEquals(stored[0].unitId.value, hit.unitId)
                assertEquals(
                    2,
                    contentUnitImageRows(context, picture.documentId),
                    "published units keep their image provenance beside the revision's pages",
                )
            }
        }
    }

    @Test
    fun `keep current text republishes a picture-only document that has no searchable text`() {
        withHarness { harness ->
            val photo = harness.importPhotoOnly()
            harness.useImages(StandaloneImageFinder(), ImageExtractionMode.EMBEDDED)
            val engine = FakePageEngine(readings = listOf("must never be read"))

            val attempt = harness.rescan(photo, engine, method = ReadingMethod.KeepText)

            assertNull(attempt.failure)
            assertNull(attempt.operation.errorCode, "${attempt.operation.errorCode}: ${attempt.operation.errorMessage}")
            assertEquals(OcrOperationStage.COMPLETE, attempt.operation.stage)
            assertNotEquals(photo.baselineRevisionId, attempt.activeRevisionId, "the rescan published a new reading")
            assertEquals(0, attempt.indexedChunks)
            assertEquals(0, engine.calls)
            AppContext.open(harness.archiveDir).use { context ->
                assertEquals(1, context.imageStore.imagesOf(assertNotNull(attempt.activeRevisionId)).size)
            }
        }
    }

    @Test
    fun `keep current text is refused at preview while images are off`() {
        withHarness { harness ->
            val picture = harness.importCleanAndScannedPdf("clean", "scanned")
            val refusal = AppContext.open(harness.archiveDir).use { context ->
                runBlocking {
                    runCatching {
                        harness.rescanServiceOf(context, FakePageEngine()).preview(
                            CollectionId("default"), picture.documentId, ReadingMethod.KeepText,
                            imageExtraction = ImageExtractionMode.OFF,
                        )
                    }.exceptionOrNull()
                }
            }
            val unavailable = assertIs<ReadingMethodUnavailableException>(refusal)
            assertEquals("METHOD_UNAVAILABLE", unavailable.code)
            assertEquals("Keep current text needs images on", unavailable.message)

            // With no stated mode, the collection's default (off) decides the same.
            val byDefault = AppContext.open(harness.archiveDir).use { context ->
                runBlocking {
                    runCatching {
                        harness.rescanServiceOf(context, FakePageEngine())
                            .preview(CollectionId("default"), picture.documentId, ReadingMethod.KeepText)
                    }.exceptionOrNull()
                }
            }
            assertIs<ReadingMethodUnavailableException>(byDefault)
        }
    }

    @Test
    fun `a scoped rescan extracts its pages afresh, carries the others, and turning images off drops them until Restore`() {
        withHarness { harness ->
            val picture = harness.importCleanTextPdf(listOf(PAGE_ONE, PAGE_TWO, PAGE_THREE))
            val finder = TableFinder(
                mapOf(
                    1 to listOf(embedded("first picture", 1)),
                    2 to listOf(embedded("second picture", 2)),
                    3 to listOf(embedded("third picture", 3)),
                ),
            )
            harness.useImages(finder, ImageExtractionMode.EMBEDDED)

            // 1. Every page, text kept.
            val first = harness.rescan(picture, FakePageEngine(), method = ReadingMethod.KeepText)
            val revision1 = assertNotNull(first.activeRevisionId)
            val images1 = imagesOf(harness, revision1)
            assertEquals(listOf("first picture", "second picture", "third picture"), images1.map { it.caption })
            assertEquals(listOf(revision1), producersOfImages(harness, revision1).distinct())

            // 2. Only page two, whose picture changed: its image is new, the others are carried unchanged.
            finder.byPage[2] = listOf(embedded("second picture, again", 9))
            finder.pagesAsked.clear()
            val second = harness.rescan(
                picture, FakePageEngine(), selection = RescanSelection.explicit(listOf(1)), method = ReadingMethod.KeepText,
            )
            val revision2 = assertNotNull(second.activeRevisionId)
            assertNotEquals(revision1, revision2)
            val images2 = imagesOf(harness, revision2)
            assertEquals(listOf("first picture", "second picture, again", "third picture"), images2.map { it.caption })
            assertEquals(images1[0].unitId, images2[0].unitId, "the carried picture keeps its unit id")
            assertEquals(images1[2].unitId, images2[2].unitId)
            assertNotEquals(images1[1].unitId, images2[1].unitId)
            assertEquals(listOf(revision1, revision2, revision1), producersOfImages(harness, revision2), "carried units keep their producer")
            assertEquals(listOf(2), finder.pagesAsked.toList(), "only the selected page was looked at")

            // 3. A text rescan of page one with images on: its picture is extracted afresh, the others carried.
            finder.pagesAsked.clear()
            val third = harness.rescan(
                picture, FakePageEngine(readings = listOf("page one reread")),
                selection = RescanSelection.explicit(listOf(0)),
            )
            val revision3 = assertNotNull(third.activeRevisionId)
            assertEquals("page one reread", harness.publishedPagesOf(picture).first().extractedText)
            val images3 = imagesOf(harness, revision3)
            assertEquals(images2.map { it.unitId }, images3.map { it.unitId })
            assertEquals(listOf(revision3, revision2, revision1), producersOfImages(harness, revision3))
            assertEquals(listOf(1), finder.pagesAsked.toList())

            // 4. Images off: the new revision has no image unit; the earlier ones keep theirs.
            harness.imageMode = ImageExtractionMode.OFF
            val fourth = harness.rescan(
                picture, FakePageEngine(readings = listOf("page one reread twice")),
                selection = RescanSelection.explicit(listOf(0)),
            )
            val revision4 = assertNotNull(fourth.activeRevisionId)
            assertEquals(OcrOperationStage.COMPLETE, fourth.operation.stage)
            assertTrue(imagesOf(harness, revision4).isEmpty())
            assertTrue(harness.publishedPagesOf(picture).none { isImage(it) })
            assertEquals(images1.map { it.unitId }, imagesOf(harness, revision1).map { it.unitId })
            assertEquals(images3.map { it.unitId }, imagesOf(harness, revision3).map { it.unitId })

            // 5. Restore brings the images of the revision it restores back, and publishes their provenance.
            val restoredId = AppContext.open(harness.archiveDir, restoreEmbedder = { TestDocumentEmbedder() }).use { context ->
                runBlocking {
                    context.revisionRestore.restore(
                        CollectionId("default"), picture.documentId, "restore-images", revision4, revision3,
                    )
                }.newRevisionId
            }
            AppContext.open(harness.archiveDir).use { context ->
                assertEquals(restoredId, context.revisions.activeRevisionId(picture.documentId))
                assertEquals(
                    images3.map { Triple(it.unitId, it.sha256, it.caption) },
                    context.imageStore.imagesOf(restoredId).map { Triple(it.unitId, it.sha256, it.caption) },
                )
                assertEquals(3, contentUnitImageRows(context, picture.documentId))
                val hit = context.index().searchKeyword(CollectionId("default"), "again", limit = 10).single()
                assertEquals(images3[1].unitId.value, hit.unitId)
            }
        }
    }

    @Test
    fun `an interrupted image step resumes to the same units without duplicates or repeated lookups`() {
        withHarness { harness ->
            val picture = harness.importCleanTextPdf(listOf(PAGE_ONE, PAGE_TWO, PAGE_THREE))
            val table = mapOf(
                1 to listOf(embedded("first picture", 1)),
                2 to listOf(embedded("second picture", 2)),
                3 to listOf(embedded("third picture", 3)),
            )
            val finder = TableFinder(table, killOnPage = 2)
            harness.useImages(finder, ImageExtractionMode.EMBEDDED)
            val engine = FakePageEngine()

            val interrupted = harness.rescan(picture, engine, method = ReadingMethod.KeepText)

            assertIs<SimulatedKill>(interrupted.failure)
            assertEquals(listOf(1, 2), finder.pagesAsked.toList())
            finder.killOnPage = null
            finder.pagesAsked.clear()

            // A restart: opening the archive again marks the stopped operation interrupted, and resuming it queues
            // another attempt of the same operation.
            val resumed = AppContext.open(harness.archiveDir).use { context ->
                val operationId = interrupted.operation.operationId
                runBlocking {
                    harness.rescanServiceOf(context, engine).resume(CollectionId("default"), picture.documentId, operationId)
                }
                harness.runAttempt(
                    context, operationId, engine, TestDocumentEmbedder(), null, picture, useAdmittedJob = true,
                )
            }

            assertNull(resumed.failure)
            assertEquals(OcrOperationStage.COMPLETE, resumed.operation.stage, "${resumed.operation.errorCode}: ${resumed.operation.errorMessage}")
            assertEquals(listOf(2, 3), finder.pagesAsked.toList(), "the committed first parent was not looked at again")
            assertEquals(0, engine.calls)
            val revision = assertNotNull(resumed.activeRevisionId)
            val images = imagesOf(harness, revision)
            assertEquals(listOf("first picture", "second picture", "third picture"), images.map { it.caption })
            AppContext.open(harness.archiveDir).use { context ->
                val pages = context.revisions.pages(revision)
                assertEquals(pages.size, pages.map { it.ordinal }.toSet().size, "one row per ordinal")
                assertEquals(pages.size, pages.map { it.unitId }.toSet().size, "one row per unit")
                assertEquals(3, pages.count { isImage(it) })
                assertEquals(3, imageRows(context, picture.documentId), "no duplicate image metadata")
                assertTrue(pages.all { it.approval == PageApproval.APPROVED })
            }

            // A fresh, uninterrupted rescan finds the same identities.
            val clean = harness.rescan(picture, engine, method = ReadingMethod.KeepText)
            assertEquals(
                images.map { it.unitId },
                imagesOf(harness, assertNotNull(clean.activeRevisionId)).map { it.unitId },
            )
        }
    }

    // ---- fixtures ----

    private fun isImage(page: RevisionPageText) = ImageIdentity.isImageOrdinal(page.ordinal)

    private fun imagesOf(harness: RescanHarness, revisionId: String): List<StoredImage> =
        AppContext.open(harness.archiveDir).use { context -> context.imageStore.imagesOf(revisionId) }

    private fun producersOfImages(harness: RescanHarness, revisionId: String): List<String?> =
        AppContext.open(harness.archiveDir).use { context ->
            context.revisions.pages(revisionId).filter { isImage(it) }.map { it.producerRevisionId }
        }

    private fun RescanHarness.useImages(finder: ImageFinder, mode: ImageExtractionMode) {
        imageMode = mode
        imageStage = { context ->
            ImageStage(
                ImageFinders(listOf(finder)), LayoutRegions(PageOcrEngines(emptyList())), ImageArtifacts(),
                context.imageStore, context.documents,
            )
        }
    }

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

    private fun contentUnitImageRows(context: AppContext, documentId: DocumentId): Int = count(
        context, "SELECT COUNT(*) FROM content_units WHERE document_id = ? AND source_image_sha256 IS NOT NULL", documentId,
    )

    private fun imageRows(context: AppContext, documentId: DocumentId): Int =
        count(context, "SELECT COUNT(*) FROM document_images WHERE document_id = ?", documentId)

    private fun count(context: AppContext, sql: String, documentId: DocumentId): Int = context.database.read { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun withHarness(block: (RescanHarness) -> Unit) {
        val directory = Files.createTempDirectory("infoscry-rescan-images")
        try {
            RescanHarness(directory).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** A process that ended mid-step: it is an [Error], so nothing in the step or the handler treats it as a failure to record. */
    private class SimulatedKill : Error("the process ended")

    /** Finds the pictures a table lists for each page number, and records which pages it was asked about. */
    private class TableFinder(
        table: Map<Int, List<FoundImage>>,
        @Volatile var killOnPage: Int? = null,
    ) : ImageFinder {
        val byPage: MutableMap<Int, List<FoundImage>> = table.toMutableMap()
        override val mediaTypes: Set<String> = setOf("application/pdf")
        val pagesAsked = CopyOnWriteArrayList<Int>()

        override fun open(managedPath: Path): OpenedSource = object : OpenedSource {
            override fun imagesOf(parent: ParentUnit): ParentImages {
                val page = (parent.locator as SourceLocation.PdfPage).page
                pagesAsked += page
                if (page == killOnPage) throw SimulatedKill()
                val found = byPage[page].orEmpty().map { it.copy(parent = parent) }
                return ParentImages(found, emptyList(), scanned = false)
            }

            override fun close() = Unit
        }
    }

    private companion object {
        const val PAGE_ONE = "This first page has enough useful text for the automatic selector to preserve it."
        const val PAGE_TWO = "This second page has enough useful text for the automatic selector to preserve it."
        const val PAGE_THREE = "This third page has enough useful text for the automatic selector to preserve it."
    }
}
