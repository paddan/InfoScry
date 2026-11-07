package infoscry.document

import infoscry.AppContext
import infoscry.domain.ContentUnitId
import infoscry.domain.SourceLocation
import infoscry.image.ImageIdentity
import infoscry.jobs.seedCollectionWithId
import infoscry.pdf.SelectablePdfInputResolver
import infoscry.pdf.SelectablePdfResolution
import infoscry.pdf.SelectablePdfTestFixtures
import infoscry.server.PageReviewFixture
import infoscry.storage.PageApproval
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The consumers that think in pages (page review, history, the rescan census, the selectable-PDF copy) must read a
 * revision that also holds image units as if they were not there: an image unit is not a page of the document.
 */
class ImageUnitConsumersTest {

    private lateinit var root: Path
    private lateinit var context: AppContext
    private lateinit var fixture: PageReviewFixture

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("infoscry-image-consumers")
        context = AppContext.open(root)
        context.seedCollectionWithId("default", "Default")
        fixture = PageReviewFixture(context, root)
    }

    @AfterTest
    fun tearDown() {
        context.close()
        root.toFile().deleteRecursively()
    }

    private val imageOrdinal = ImageIdentity.ordinal(0, 1)!!

    private fun imagePage(base: infoscry.storage.RevisionPageDraft) = base.copy(
        ordinal = imageOrdinal,
        locator = SourceLocation.ImageRegion(SourceLocation.PdfPage(1), 1, null),
        approval = PageApproval.APPROVED,
    )

    @Test
    fun `page review offers no image unit as a neighbour and refuses its ordinal`() {
        val document = fixture.pdf()
        val revision = fixture.publish(
            document,
            listOf(fixture.page(0, "first"), fixture.page(1, "second"), fixture.page(2, "third"), imagePage(fixture.page(0, "a caption"))),
        )
        assertEquals(1, context.revisions.neighbourOrdinal(revision, 2, previous = true))
        assertNull(context.revisions.neighbourOrdinal(revision, 2, previous = false), "the page after the last is not an image")
        assertEquals(2, context.revisions.neighbourOrdinal(revision, imageOrdinal, previous = true))

        val service = PageReviewService(
            context.paths, context.documents, context.revisions, context.library, context.ocrOperations,
            { context.ocrProfiles.findRevision(it) },
        )
        assertFailsWith<Exception> { service.review(document.collectionId, document.id, imageOrdinal) }
        assertEquals("third", service.review(document.collectionId, document.id, 2).text)
    }

    @Test
    fun `the history counts pages, not image units`() {
        val document = fixture.pdf()
        fixture.publish(
            document,
            listOf(fixture.page(0, "first"), fixture.page(1, "second"), imagePage(fixture.page(0, "a caption"))),
        )

        val entry = context.revisionHistory.history(document.collectionId, document.id).single()

        assertEquals(2, entry.pageCount)
    }

    @Test
    fun `the source viewer's previous, next, place and count are text-only`() {
        val document = fixture.pdf(count = 3)
        fixture.publish(
            document,
            listOf(
                fixture.page(0, "first"), fixture.page(1, "second"), fixture.page(2, "third"),
                imagePage(fixture.page(0, "a caption")),
            ),
        )

        val last = context.content.neighboursOf(document.id, 2)
        assertEquals(3, last.total, "the image unit is not a page of y")
        assertEquals(3, last.position)
        assertNull(last.next, "the unit after the last page is not the image")
        assertEquals(ContentUnitId("${document.id.value}-1"), last.previous)

        val head = context.content.neighboursOf(document.id, 0)
        assertEquals(1, head.position)
        assertEquals(3, head.total)
        assertEquals(ContentUnitId("${document.id.value}-1"), head.next)
    }

    @Test
    fun `history change counts leave image units out of both revisions`() {
        val document = fixture.pdf(count = 2)
        fixture.publish(
            document,
            listOf(fixture.page(0, "first"), fixture.page(1, "second"), imagePage(fixture.page(0, "a caption"))),
        )
        // The next reading keeps the text, changes the image's caption and adds a second image.
        val secondImage = ImageIdentity.ordinal(1, 1)!!
        fixture.publish(
            document,
            listOf(
                fixture.page(0, "first"), fixture.page(1, "second"),
                imagePage(fixture.page(0, "a new caption")),
                imagePage(fixture.page(0, "another")).copy(ordinal = secondImage),
            ),
        )

        val entry = context.revisionHistory.history(document.collectionId, document.id).last()
        assertEquals(2, entry.pageCount)
        assertEquals(2, entry.pageChanges.unchanged)
        assertEquals(0, entry.pageChanges.added)
        assertEquals(0, entry.pageChanges.automatic + entry.pageChanges.manual + entry.pageChanges.unknown + entry.pageChanges.restored)
    }

    @Test
    fun `the rescan census and baseline count only the pages of the original`() {
        val document = fixture.pdf(count = 3)
        val revision = fixture.publish(
            document,
            listOf(fixture.page(0, "first"), fixture.page(1, "second"), fixture.page(2, "third"), imagePage(fixture.page(0, "a caption"))),
        )
        val pages = context.revisions.pages(revision)
        assertEquals(4, pages.size)

        val baseline = textPagesOf(pages)
        assertEquals(setOf(0, 1, 2), baseline.keys)

        val census = RescanPageSource().census(context.library.managedPathOf(document), "application/pdf", baseline)
        assertEquals(3, census.documentPages)
        assertEquals(RescanPageSource().census(context.library.managedPathOf(document), "application/pdf", baseline), census)
    }

    @Test
    fun `the selectable copy of a revision with image units resolves exactly as it does without them`() {
        val published = SelectablePdfTestFixtures.publishNativePdf(context, root)
        val document = context.documents.get(published.documentId)!!
        val revision = context.revisions.revision(published.revisionId)!!
        val pages = context.revisions.pages(published.revisionId)
        val output = root.resolve("out/selectable.pdf")

        val plain = SelectablePdfInputResolver.resolve(context.paths, context.library, document, revision, pages, output)
        val withImages = SelectablePdfInputResolver.resolve(
            context.paths, context.library, document, revision,
            pages + pages.first().copy(
                ordinal = imageOrdinal,
                unitId = ContentUnitId("an-image"),
                locator = SourceLocation.ImageRegion(SourceLocation.PdfPage(1), 1, null),
            ),
            output,
        )

        assertIs<SelectablePdfResolution.Ready>(plain)
        assertEquals(plain, withImages)
    }
}
