package infoscry.image

import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.NormalizedBox
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.extract.UnitBoundary
import infoscry.ocr.BlockKind
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.PageOcrEngines
import infoscry.ocr.PageStructure
import infoscry.ocr.PixelBox
import infoscry.ocr.STRUCTURE_VERSION
import infoscry.ocr.StructureBlock
import infoscry.ocr.TextRange
import infoscry.pdf.PdfModelRules
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentStore
import infoscry.storage.ImageCheckpointOutcome
import infoscry.storage.ImageStore
import infoscry.storage.SchemaMigrator
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ImageStageTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var documentStore: DocumentStore
    private lateinit var store: ImageStore
    private lateinit var document: Document
    private lateinit var artifactRoot: Path
    private lateinit var managedPath: Path

    private val settings = OcrSettingsSnapshot(
        engine = OcrEngine.SURYA,
        mode = OcrImportMode.READ_ALL,
        language = "eng",
        extractorVersion = "schema-1",
        renderDpi = 300,
    )

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("infoscry-image-stage")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        documentStore = DocumentStore(database)
        store = ImageStore(database)
        val collection = CollectionStore(database).create("Images")
        document = documentStore.insert(newDocument(collection.id))
        artifactRoot = Files.createDirectories(directory.resolve("artifacts"))
        managedPath = directory.resolve("managed.pdf")
        Files.write(managedPath, byteArrayOf(1))
    }

    @AfterTest
    fun tearDown() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `running twice delivers each image once and the second run makes no finder call`() {
        val parents = pages(3) { page -> listOf(picture(100 + page, 120)) }
        val finder = FakeFinder(parents)
        val target = RecordingTarget()

        val first = run(finder, parents, target)
        val callsAfterFirst = finder.calls.size
        val second = run(finder, parents, target)

        assertEquals(3, first.images)
        assertEquals(3, target.delivered.size)
        assertEquals(3, target.delivered.map { it.unitId }.toSet().size)
        assertEquals(first, second)
        assertEquals(callsAfterFirst, finder.calls.size)
        assertEquals(3, imageRows())
        assertEquals(3, imageFiles())
    }

    @Test
    fun `a parent's images are ordered and located after every text unit`() {
        val parents = pages(2) { page -> listOf(picture(100, 120 + page), picture(130, 120 + page)) }
        val target = RecordingTarget()

        run(FakeFinder(parents), parents, target)

        val second = target.delivered.filter { it.locator.parent == SourceLocation.PdfPage(2) }
        assertEquals(listOf(1, 2), second.map { it.locator.index })
        assertEquals(
            listOf(ImageIdentity.ordinal(1, 1), ImageIdentity.ordinal(1, 2)),
            second.map { it.ordinal },
        )
        assertTrue(second.all { it.ordinal >= ImageIdentity.ORDINAL_BASE && it.root == SourceImageRoot.ARTIFACTS })
    }

    @Test
    fun `an interruption after the artifact write converges on rerun`() = interrupted(Step.RECORD)

    @Test
    fun `an interruption after the record converges on rerun`() = interrupted(Step.DELIVER)

    @Test
    fun `an interruption after the delivery converges on rerun`() = interrupted(Step.CHECKPOINT)

    @Test
    fun `a logo on four of five pages is skipped and on three pages is kept`() {
        val logo = picture(150, 150)
        val onFour = pages(5) { page -> listOfNotNull(picture(100 + page, 120), logo.takeIf { page < 4 }) }
        val four = run(FakeFinder(onFour), onFour, RecordingTarget())

        assertEquals(5, four.images)
        assertEquals(4, four.skipped[SkipReason.REPEATED])

        val other = newDocument(document.collectionId, "doc-two")
        documentStore.insert(other)
        val onThree = pages(5) { page -> listOfNotNull(picture(100 + page, 120), logo.takeIf { page < 3 }) }
        val three = run(FakeFinder(onThree), onThree, RecordingTarget(), other)

        assertEquals(8, three.images)
        assertEquals(null, three.skipped[SkipReason.REPEATED])
    }

    @Test
    fun `a logo drawn twice on one page counts that page once`() {
        val logo = picture(150, 150)
        val parents = pages(5) { page -> if (page < 3) listOf(logo, logo) else listOf(picture(100 + page, 120)) }

        val result = run(FakeFinder(parents), parents, RecordingTarget())

        assertEquals(null, result.skipped[SkipReason.REPEATED])
        assertEquals(8, result.images)
    }

    @Test
    fun `the 501st image is not delivered and the result warns`() {
        val parents = pages(2) { page ->
            val range = if (page == 0) 0 until 300 else 300 until 501
            range.map { picture(96 + it, 100) }
        }
        val target = RecordingTarget()

        val result = run(FakeFinder(parents), parents, target)

        assertEquals(ImageFilter.MAX_PER_DOCUMENT, result.images)
        assertEquals(ImageFilter.MAX_PER_DOCUMENT, target.delivered.size)
        assertEquals(1, result.skipped[SkipReason.LIMIT])
        assertTrue(ImageStage.LIMIT_REACHED in result.warnings)
    }

    @Test
    fun `missing Surya in all mode warns once, keeps embedded images and tries the pages again next time`() {
        val parents = pages(2, scanned = true) { page -> listOf(picture(100 + page, 120)) }
        val finder = FakeFinder(parents)
        var renders = 0
        val contexts = contexts(parents) { renders++; unusedPage() }
        val target = RecordingTarget()

        val result = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                document, managedPath, artifactRoot, contexts, ImageExtractionMode.ALL, target, Direct, settings,
            )
        }

        assertEquals(2, result.images)
        assertEquals(listOf("NEEDS_SURYA_LAYOUT"), result.warnings)
        assertEquals(1, renders)
        val checkpoints = store.checkpoints(document.id, target.target, ImageExtractionMode.ALL, IMAGE_EXTRACTOR_VERSION)
        assertEquals(setOf(ImageCheckpointOutcome.FAILED), checkpoints.values.map { it.outcome }.toSet())

        val again = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                document, managedPath, artifactRoot, contexts, ImageExtractionMode.ALL, target, Direct, settings,
            )
        }
        assertEquals(2, again.images)
        assertEquals(2, imageRows())
    }

    @Test
    fun `embedded mode never renders a page or calls the layout engine`() {
        val parents = pages(2, scanned = true) { page -> listOf(picture(100 + page, 120)) }
        val engine = FakeSurya()
        var renders = 0
        val contexts = contexts(parents) { renders++; unusedPage() }

        val result = runBlocking {
            stage(FakeFinder(parents), LayoutRegions(PageOcrEngines(listOf(engine)))).run(
                document, managedPath, artifactRoot, contexts, ImageExtractionMode.EMBEDDED, RecordingTarget(), Direct,
                settings,
            )
        }

        assertEquals(2, result.images)
        assertEquals(0, renders)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `off mode does nothing`() {
        val parents = pages(1) { listOf(picture(100, 120)) }
        val finder = FakeFinder(parents)

        val result = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                document, managedPath, artifactRoot, contexts(parents) { null }, ImageExtractionMode.OFF,
                RecordingTarget(), Direct, settings,
            )
        }

        assertEquals(ImageStageResult.NONE, result)
        assertTrue(finder.calls.isEmpty())
    }

    @Test
    fun `a scanned page in all mode delivers its picture regions`() {
        val parents = pages(1, scanned = true) { emptyList() }
        val engine = FakeSurya()
        val page = pageWithPicture()
        val contexts = contexts(parents) { page }
        val target = RecordingTarget()

        val result = runBlocking {
            stage(FakeFinder(parents), LayoutRegions(PageOcrEngines(listOf(engine)))).run(
                document, managedPath, artifactRoot, contexts, ImageExtractionMode.ALL, target, Direct, settings,
            )
        }

        assertEquals(1, result.images)
        assertEquals(1, engine.calls)
        val region = target.delivered.single()
        assertEquals(ImageSourceKind.PAGE_REGION, store.image(document.id, region.unitId, region.image.sha256)?.kind)
        assertEquals(NormalizedBox(0.1, 0.1, 0.5, 0.4), region.locator.box)
        assertEquals(SourceImageRoot.ARTIFACTS, region.root)
    }

    @Test
    fun `a standalone picture is its own managed copy with only a thumbnail written`() {
        val bytes = FinderFixtures.png(200, 160)
        val picture = directory.resolve("scan.png")
        Files.write(picture, bytes)
        val owner = documentStore.insert(
            newDocument(document.collectionId, "doc-picture").copy(
                mediaType = "image/png",
                sha256 = infoscry.storage.ContentAddressedFile.sha256Of(bytes),
            ),
        )
        val parent = ParentUnit(ContentUnitId.new(), 0, SourceLocation.Image("scan.png"))
        val candidate = ImageProbe.candidateOf(bytes, box = null)!!
        val finder = FakeFinder(
            mapOf(parent.unitId to ParentImages(listOf(FoundImage(parent, ImageSourceKind.STANDALONE, candidate, null, null)), emptyList(), false)),
            mediaType = "image/png",
        )
        val target = RecordingTarget()

        val result = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                owner, picture, artifactRoot, listOf(ParentUnitContext(parent, "", null) { null }),
                ImageExtractionMode.EMBEDDED, target, Direct, settings,
            )
        }

        assertEquals(1, result.images)
        val delivered = target.delivered.single()
        assertEquals(SourceImageRoot.MANAGED_COPY, delivered.root)
        assertEquals("scan.png", delivered.image.relativePath)
        assertEquals(owner.sha256, delivered.image.sha256)
        assertEquals("", delivered.caption)
        assertEquals(0, imageFiles())
        assertTrue(Files.exists(artifactRoot.resolve(delivered.image.thumbnailRelativePath)))
    }

    @Test
    fun `a standalone tiff gets a png display copy beside its thumbnail and the managed copy is untouched`() {
        val picture = FinderFixtures.picture(300, 200, 5)
        val tiff = directory.resolve("scan.tiff")
        check(ImageIO.write(picture, "tiff", tiff.toFile())) { "no TIFF writer is available" }
        val before = Files.readAllBytes(tiff)
        val owner = documentStore.insert(
            newDocument(document.collectionId, "doc-tiff").copy(
                mediaType = "image/tiff",
                sha256 = infoscry.storage.ContentAddressedFile.sha256Of(before),
            ),
        )
        val parent = ParentUnit(ContentUnitId.new(), 0, SourceLocation.Image("scan.tiff"))
        val candidate = ImageProbe.candidateOf(before, box = null)!!
        val finder = FakeFinder(
            mapOf(parent.unitId to ParentImages(listOf(FoundImage(parent, ImageSourceKind.STANDALONE, candidate, null, null)), emptyList(), false)),
            mediaType = "image/tiff",
        )

        repeat(2) {
            runBlocking {
                stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                    owner, tiff, artifactRoot, listOf(ParentUnitContext(parent, "", null) { null }),
                    ImageExtractionMode.EMBEDDED, RecordingTarget(), Direct, settings,
                )
            }
        }

        val copy = artifactRoot.resolve(ImageArtifacts.displayCopyPath(owner.sha256))
        assertTrue(Files.isRegularFile(copy), "the display copy was not written")
        val shown = ImageIO.read(copy.toFile())
        assertEquals(300, shown.width)
        assertEquals(200, shown.height)
        assertTrue(Files.readAllBytes(copy).take(4) == listOf<Byte>(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
        assertEquals(before.toList(), Files.readAllBytes(tiff).toList(), "the managed copy changed")
    }

    @Test
    fun `a standalone png needs no display copy`() {
        assertEquals(false, ImageArtifacts.needsDisplayCopy("image/png"))
        assertEquals(false, ImageArtifacts.needsDisplayCopy("image/jpeg"))
        assertEquals(true, ImageArtifacts.needsDisplayCopy("image/tiff"))
    }

    @Test
    fun `a standalone picture that is tiny or one colour is still kept`() {
        val tiny = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
        val bytes = FinderFixtures.encode(tiny, "png")
        val picture = directory.resolve("tiny.png")
        Files.write(picture, bytes)
        val owner = documentStore.insert(
            newDocument(document.collectionId, "doc-tiny").copy(
                mediaType = "image/png",
                sha256 = infoscry.storage.ContentAddressedFile.sha256Of(bytes),
            ),
        )
        val parent = ParentUnit(ContentUnitId.new(), 0, SourceLocation.Image("tiny.png"))
        val found = FoundImage(parent, ImageSourceKind.STANDALONE, ImageProbe.candidateOf(bytes, null)!!, null, null)
        val finder = FakeFinder(mapOf(parent.unitId to ParentImages(listOf(found), emptyList(), false)), "image/png")

        val result = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                owner, picture, artifactRoot, listOf(ParentUnitContext(parent, "", null) { null }),
                ImageExtractionMode.EMBEDDED, RecordingTarget(), Direct, settings,
            )
        }

        assertEquals(1, result.images)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `a retried middle parent delivers only what the cap leaves`() {
        // Parents 0 and 2 hold 200 images each and are committed; the retried parent 1 holds 150 but only 100 fit.
        val parents = pages(3) { page ->
            val size = if (page == 1) 150 else 200
            (0 until size).map { picture(96 + page * 300 + it, 100) }
        }
        val finder = FakeFinder(parents)
        val target = RecordingTarget()
        run(finder, parents.filterKeys { it != ContentUnitId("page-1") }, target)
        store.commitCheckpoint(
            document.id, target.target, ImageExtractionMode.EMBEDDED, IMAGE_EXTRACTOR_VERSION,
            infoscry.storage.ImageCheckpoint(ContentUnitId("page-1"), ImageCheckpointOutcome.FAILED, 0, null),
        )

        val result = run(finder, parents, target)

        assertEquals(ImageFilter.MAX_PER_DOCUMENT, result.images)
        assertEquals(ImageFilter.MAX_PER_DOCUMENT, target.delivered.size)
        assertEquals(100, target.delivered.count { it.locator.parent == SourceLocation.PdfPage(2) })
        assertEquals(50, result.skipped[SkipReason.LIMIT])
        assertTrue(ImageStage.LIMIT_REACHED in result.warnings)
    }

    @Test
    fun `a first parent retried after a full later parent cannot push the document over the cap`() {
        val parents = pages(2) { page ->
            (0 until if (page == 0) 50 else 500).map { picture(96 + page * 600 + it, 100) }
        }
        val finder = FakeFinder(parents)
        val target = RecordingTarget()
        run(finder, parents.filterKeys { it != ContentUnitId("page-0") }, target)
        store.commitCheckpoint(
            document.id, target.target, ImageExtractionMode.EMBEDDED, IMAGE_EXTRACTOR_VERSION,
            infoscry.storage.ImageCheckpoint(ContentUnitId("page-0"), ImageCheckpointOutcome.FAILED, 0, null),
        )

        val result = run(finder, parents, target)

        assertEquals(ImageFilter.MAX_PER_DOCUMENT, result.images)
        assertEquals(ImageFilter.MAX_PER_DOCUMENT, target.delivered.size)
        assertTrue(ImageStage.LIMIT_REACHED in result.warnings)
    }

    @Test
    fun `a document marked for deletion mid-run delivers nothing further`() {
        val parents = pages(3) { page -> listOf(picture(100 + page, 120)) }
        val target = RecordingTarget(after = { markForDeletion() })

        assertFailsWith<DocumentBeingDeletedException> { run(FakeFinder(parents), parents, target) }

        assertEquals(1, target.delivered.size)
    }

    @Test
    fun `a page beyond the ordinal bound gets no images and a warning`() {
        val late = ParentUnit(ContentUnitId.new(), ImageIdentity.MAX_PARENT_ORDINAL + 1, SourceLocation.PdfPage(1))
        val finder = FakeFinder(mapOf(late.unitId to ParentImages(listOf(picture(100, 120)), emptyList(), false)))

        val result = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                document, managedPath, artifactRoot, listOf(ParentUnitContext(late, "", null) { null }),
                ImageExtractionMode.EMBEDDED, RecordingTarget(), Direct, settings,
            )
        }

        assertEquals(0, result.images)
        assertEquals(listOf(ImageStage.PARENT_TOO_LATE), result.warnings)
        assertTrue(finder.calls.isEmpty())
    }

    @Test
    fun `an image that cannot be written is a counted warning and the others are kept`() {
        val bad = FoundImageFixture.undecodable(ParentUnit(ContentUnitId.new(), 0, SourceLocation.PdfPage(1)))
        val parent = bad.parent
        val finder = FakeFinder(
            mapOf(parent.unitId to ParentImages(listOf(bad, picture(100, 120).copy(parent = parent)), emptyList(), false)),
        )

        val result = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                document, managedPath, artifactRoot, listOf(ParentUnitContext(parent, "", null) { null }),
                ImageExtractionMode.EMBEDDED, RecordingTarget(), Direct, settings,
            )
        }

        assertEquals(1, result.images)
        assertEquals(listOf(ImageRejectedException.IMAGE_DECODE_FAILED), result.warnings)
    }

    @Test
    fun `switching from all to embedded drops the region units the earlier run left`() {
        val parents = pages(1, scanned = true) { listOf(picture(100, 120)) }
        val finder = FakeFinder(parents)
        val page = pageWithPicture()
        val contexts = contexts(parents) { page }
        val target = RecordingTarget()

        val all = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(listOf(FakeSurya())))).run(
                document, managedPath, artifactRoot, contexts, ImageExtractionMode.ALL, target, Direct, settings,
            )
        }
        assertEquals(2, all.images)
        assertEquals(2, target.held.size)

        val embedded = runBlocking {
            stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
                document, managedPath, artifactRoot, contexts, ImageExtractionMode.EMBEDDED, target, Direct, settings,
            )
        }

        assertEquals(1, embedded.images)
        assertEquals(1, target.held.size)
        assertEquals(ImageSourceKind.EMBEDDED, store.image(document.id, target.held.keys.single(), target.delivered.first().image.sha256)?.kind)
    }

    @Test
    fun `a retry that delivers fewer images leaves no stale unit, and repeating it changes nothing`() {
        val target = RecordingTarget()
        val before = pages(2) { listOf(picture(100, 120), picture(110, 120)) }
        run(FakeFinder(before), before, target)
        assertEquals(4, target.held.size)

        // The same document, read again after its checkpoints are gone (a retry): page 0 now yields one image.
        execute("DELETE FROM image_extraction_checkpoints")
        val after = pages(2) { page -> if (page == 0) listOf(picture(100, 120)) else listOf(picture(100, 120), picture(110, 120)) }
        run(FakeFinder(after), after, target)
        assertEquals(3, target.held.size)
        val onFirst = target.held.values.filter { it < ImageIdentity.ordinal(1, 1)!! }
        assertEquals(listOf(ImageIdentity.ordinal(0, 1)!!), onFirst)

        execute("DELETE FROM image_extraction_checkpoints")
        run(FakeFinder(after), after, target)
        assertEquals(3, target.held.size)
    }

    @Test
    fun `a parent that cannot be read keeps the units it already holds`() {
        val target = RecordingTarget()
        val before = pages(1) { listOf(picture(100, 120)) }
        run(FakeFinder(before), before, target)

        execute("DELETE FROM image_extraction_checkpoints")
        val failing = object : ImageFinder {
            override val mediaTypes: Set<String> = setOf("application/pdf")
            override fun open(managedPath: Path): OpenedSource = object : OpenedSource {
                override fun imagesOf(parent: ParentUnit): ParentImages = throw IllegalStateException("unreadable")
                override fun close() = Unit
            }
        }
        runBlocking {
            ImageStage(ImageFinders(listOf(failing)), LayoutRegions(PageOcrEngines(emptyList())), ImageArtifacts(), store, documentStore).run(
                document, managedPath, artifactRoot, contexts(before) { null }, ImageExtractionMode.EMBEDDED, target, Direct, settings,
            )
        }

        assertEquals(1, target.held.size)
    }

    @Test
    fun `vector drawings are counted as skipped without a warning`() {
        val parent = ParentUnit(ContentUnitId("page-0"), 0, SourceLocation.PdfPage(1))
        orderedParents[parent.unitId] = parent
        val answers = mapOf(parent.unitId to ParentImages(listOf(picture(100, 120).copy(parent = parent)), emptyList(), false, vectorSkipped = 2))

        val result = run(FakeFinder(answers), answers, RecordingTarget())

        assertEquals(1, result.images)
        assertEquals(2, result.skipped[SkipReason.VECTOR])
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun `the repetition pass and the delivery pass each open their own source`() {
        val parents = pages(5) { page -> listOf(picture(100 + page, 120)) }
        val finder = FakeFinder(parents)

        run(finder, parents, RecordingTarget())

        assertEquals(2, finder.opens)
    }

    @Test
    fun `a page that cannot be rendered fails alone and the rest of the document is still looked at`() {
        val parents = pages(3, scanned = true) { emptyList() }
        val engine = FakeSurya()
        val page = pageWithPicture()
        var asked = 0
        val contexts = contexts(parents) {
            asked++
            if (asked == 2) throw java.io.IOException("the page cannot be rendered") else page
        }
        val target = RecordingTarget()

        val result = runBlocking {
            stage(FakeFinder(parents), LayoutRegions(PageOcrEngines(listOf(engine)))).run(
                document, managedPath, artifactRoot, contexts, ImageExtractionMode.ALL, target, Direct, settings,
            )
        }

        assertEquals(2, result.images, "pages one and three delivered their regions")
        assertEquals(2, engine.calls)
        assertTrue(ImageFindFailure.PARENT_UNREADABLE in result.warnings)
        val outcomes = store.checkpoints(document.id, target.target, ImageExtractionMode.ALL, IMAGE_EXTRACTOR_VERSION)
        assertEquals(
            listOf(ImageCheckpointOutcome.DONE, ImageCheckpointOutcome.FAILED, ImageCheckpointOutcome.DONE),
            contexts.map { outcomes.getValue(it.parent.unitId).outcome },
        )
    }

    private enum class Step { RECORD, DELIVER, CHECKPOINT }

    /** Interrupts the first run at [step], runs again, and expects the rows a clean run leaves. */
    private fun interrupted(step: Step) {
        val parents = pages(3) { page -> listOf(picture(100 + page, 120), picture(100 + page, 140)) }
        val finder = FakeFinder(parents)
        val reference = RecordingTarget("reference")
        run(finder, parents, reference)
        val expected = reference.delivered.map { it.unitId }.toSet()
        wipeRows()

        installFailure("document_images".takeIf { step == Step.RECORD } ?: "image_extraction_checkpoints".takeIf { step == Step.CHECKPOINT })
        val target = RecordingTarget("t") { throwOnce(step) }
        assertFailsWith<Exception> { run(finder, parents, target) }
        removeFailure()

        val resumed = RecordingTarget("t")
        val result = run(finder, parents, resumed)

        assertEquals(6, result.images)
        val seen = (target.delivered + resumed.delivered).map { it.unitId }.toSet()
        assertEquals(expected, seen)
        assertEquals(expected.size, imageRows())
        assertEquals(6, imageFiles())
        val checkpoints = store.checkpoints(document.id, "t", ImageExtractionMode.EMBEDDED, IMAGE_EXTRACTOR_VERSION)
        assertEquals(3, checkpoints.size)
    }

    private var failOnce = true

    private fun throwOnce(step: Step) {
        if (step == Step.DELIVER && failOnce) {
            failOnce = false
            throw IllegalStateException("interrupted")
        }
    }

    private fun wipeRows() = execute("DELETE FROM document_images", "DELETE FROM image_extraction_checkpoints")

    private fun installFailure(table: String?) {
        if (table == null) return
        execute(
            "CREATE OR REPLACE FUNCTION test_interrupt() RETURNS trigger AS \$\$ BEGIN " +
                "RAISE EXCEPTION 'interrupted'; END \$\$ LANGUAGE plpgsql",
            "CREATE TRIGGER test_interrupt BEFORE INSERT ON $table FOR EACH ROW EXECUTE FUNCTION test_interrupt()",
        )
        installedOn = table
    }

    private var installedOn: String? = null

    private fun removeFailure() {
        installedOn?.let { execute("DROP TRIGGER test_interrupt ON $it") }
        installedOn = null
    }

    private fun markForDeletion() = execute(
        "INSERT INTO deletion_operations (id, collection_id, collection_name, trash_basename, " +
            "managed_originals_existed, phase, created_at, updated_at, kind) VALUES ('op', '${document.collectionId.value}', " +
            "'C', 't', 0, 'PREPARED', 'x', 'x', 'DOCUMENT')",
        "INSERT INTO document_deletion_targets (operation_id, document_id, managed_existed) " +
            "VALUES ('op', '${document.id.value}', 0)",
    )

    private fun execute(vararg statements: String) = database.transaction { connection ->
        connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
    }

    private fun imageRows(): Int = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM document_images").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun imageFiles(): Int {
        val images = artifactRoot.resolve(ImageArtifacts.IMAGE_DIRECTORY)
        if (!Files.isDirectory(images)) return 0
        return Files.list(images).use { entries -> entries.filter { Files.isRegularFile(it) }.count().toInt() }
    }

    private fun run(
        finder: FakeFinder,
        parents: Map<ContentUnitId, ParentImages>,
        target: RecordingTarget,
        owner: Document = document,
    ): ImageStageResult = runBlocking {
        stage(finder, LayoutRegions(PageOcrEngines(emptyList()))).run(
            owner, managedPath, artifactRoot, contexts(parents) { null }, ImageExtractionMode.EMBEDDED, target, Direct,
            settings,
        )
    }

    private fun stage(finder: FakeFinder, regions: LayoutRegions) =
        ImageStage(ImageFinders(listOf(finder)), regions, ImageArtifacts(), store, documentStore)

    private fun contexts(parents: Map<ContentUnitId, ParentImages>, page: suspend () -> PageImage?): List<ParentUnitContext> =
        parents.keys.map { id -> orderedParents.getValue(id) }
            .sortedBy { it.ordinal }
            .map { ParentUnitContext(it, "", null, page) }

    private val orderedParents = HashMap<ContentUnitId, ParentUnit>()

    private fun pages(
        count: Int,
        scanned: Boolean = false,
        images: (Int) -> List<FoundImage>,
    ): Map<ContentUnitId, ParentImages> = buildMap {
        for (page in 0 until count) {
            val parent = ParentUnit(ContentUnitId("page-$page"), page, SourceLocation.PdfPage(page + 1))
            orderedParents[parent.unitId] = parent
            put(parent.unitId, ParentImages(images(page).map { it.copy(parent = parent) }, emptyList(), scanned))
        }
    }

    private fun picture(width: Int, height: Int): FoundImage {
        val bytes = FinderFixtures.png(width, height)
        val candidate = ImageCandidate(
            ImageBytes.Encoded(bytes, "image/png"), width, height, NormalizedBox(0.1, 0.1, 0.6, 0.6), isMask = false,
        )
        return FoundImage(
            ParentUnit(ContentUnitId("none"), 0, SourceLocation.PdfPage(1)),
            ImageSourceKind.EMBEDDED, candidate, null, null,
        )
    }

    private fun unusedPage(): PageImage = PageImage.ofFile(
        documentId = document.id,
        unitId = "page:1",
        ordinal = 0,
        imageRoot = directory,
        imageReference = unusedFile(),
        artifactRoot = artifactRoot,
        renderDpi = 72,
        rotationDegrees = 0,
    )

    private fun unusedFile(): String {
        check(ImageIO.write(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("unused.png").toFile()))
        return "unused.png"
    }

    private fun pageWithPicture(): PageImage {
        val raster = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
        val graphics = raster.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, 1000, 1000)
            graphics.drawImage(FinderFixtures.picture(400, 300), 100, 100, null)
        } finally {
            graphics.dispose()
        }
        check(ImageIO.write(raster, "png", directory.resolve("page.png").toFile()))
        return PageImage.ofFile(
            documentId = document.id,
            unitId = "page-0",
            ordinal = 0,
            imageRoot = directory,
            imageReference = "page.png",
            artifactRoot = artifactRoot,
            renderDpi = 72,
            rotationDegrees = 0,
        )
    }

    private fun newDocument(collectionId: CollectionId, id: String = "doc-one"): Document = Document(
        id = DocumentId(id),
        collectionId = collectionId,
        sha256 = id.padEnd(64, 'a'),
        mediaType = "application/pdf",
        originalFilename = "$id.pdf",
        sourcePath = "/private/$id.pdf",
        sizeBytes = 1,
        status = DocumentStatus.COMPLETE,
        createdAt = "2026-01-01T00:00:00.000Z",
        updatedAt = "2026-01-01T00:00:00.000Z",
    )

    private class Delivery(
        val ordinal: Int,
        val unitId: ContentUnitId,
        val locator: SourceLocation.ImageRegion,
        val caption: String,
        val image: WrittenImage,
        val root: SourceImageRoot,
    )

    private class RecordingTarget(
        override val target: String = "target",
        private val after: suspend () -> Unit = {},
    ) : ImageUnitTarget {
        val delivered = mutableListOf<Delivery>()

        /** What the target holds now: delivered units, less what a later delivery retained away. */
        val held = LinkedHashMap<ContentUnitId, Int>()

        override suspend fun retainImages(fromOrdinal: Int, toOrdinal: Int, keep: Set<ContentUnitId>) {
            held.entries.removeIf { (id, ordinal) -> ordinal in fromOrdinal..toOrdinal && id !in keep }
        }

        override suspend fun deliver(
            ordinal: Int,
            unitId: ContentUnitId,
            locator: SourceLocation.ImageRegion,
            caption: String,
            image: WrittenImage,
            provenanceRoot: SourceImageRoot,
        ) {
            delivered += Delivery(ordinal, unitId, locator, caption, image, provenanceRoot)
            held[unitId] = ordinal
            after()
        }
    }

    private object Direct : UnitBoundary {
        override suspend fun <T> unit(block: suspend () -> T): T = block()
    }

    /** A finder that answers from a table and records which parents it was asked about. */
    private class FakeFinder(
        private val answers: Map<ContentUnitId, ParentImages>,
        mediaType: String = "application/pdf",
    ) : ImageFinder {
        override val mediaTypes: Set<String> = setOf(mediaType)
        val calls = mutableListOf<ContentUnitId>()
        var opens = 0
            private set

        override fun open(managedPath: Path): OpenedSource = object : OpenedSource {
            init { opens++ }

            override fun imagesOf(parent: ParentUnit): ParentImages {
                calls += parent.unitId
                return answers[parent.unitId] ?: ParentImages.NONE
            }

            override fun close() = Unit
        }
    }

    /** A Surya that finds one figure at [FIGURE] on every page. */
    private class FakeSurya : PageOcrEngine {
        override val engine: OcrEngine = OcrEngine.SURYA
        var calls = 0
            private set

        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
            calls++
            val text = "Figure one."
            val structure = PageStructure(
                version = STRUCTURE_VERSION,
                textSha256 = PdfModelRules.sha256Of(text),
                imageSha256 = "a".repeat(64),
                parser = "surya",
                blocks = listOf(
                    StructureBlock(
                        kind = BlockKind.FIGURE, order = 0, bbox = PixelBox(100, 100, 500, 400),
                        extracted = TextRange(0, text.length), search = TextRange(0, text.length),
                    ),
                ),
            )
            return OcrPageResult(text = text, engine = OcrEngine.SURYA, imageSha256 = page.sha256, structure = structure)
        }
    }
}

/** Candidates the finder tests have no use for: bytes that claim to be an image and are not. */
internal object FoundImageFixture {
    fun undecodable(parent: ParentUnit): FoundImage = FoundImage(
        parent,
        ImageSourceKind.EMBEDDED,
        ImageCandidate(
            ImageBytes.Encoded(ByteArray(64) { 7 }, "image/png"), 200, 200, NormalizedBox(0.1, 0.1, 0.6, 0.6), isMask = false,
        ),
        null,
        null,
    )
}
