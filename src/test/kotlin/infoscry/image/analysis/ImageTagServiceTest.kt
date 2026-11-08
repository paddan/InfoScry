package infoscry.image.analysis

import infoscry.AppContext
import infoscry.account.testOwner
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.domain.EditConflictException
import infoscry.embedding.TestDocumentEmbedder
import infoscry.image.ImageIdentity
import infoscry.jobs.RescanHarness
import infoscry.storage.Actor
import infoscry.storage.ImageAnalysisStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Editing one picture's tags: the view, the republished unit text, the categories, attribution, idempotence and
 * the version two editors share.
 */
class ImageTagServiceTest {

    @Test
    fun `confirm remove and add change the view the unit text and the categories`() {
        val directory = Files.createTempDirectory("infoscry-image-tags")
        RescanHarness(directory).use { harness ->
            val picture = harness.importPhotoOnly()
            AppContext.open(harness.archiveDir).use { context ->
                context.attachDocumentEmbedder { TestDocumentEmbedder() }
                context.attachChunker(Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)))
                val collectionId = picture.documentId.let { documentId ->
                    context.documents.get(documentId)!!.collectionId
                }
                val revisionId = assertNotNull(context.revisions.activeRevisionId(picture.documentId))
                val page = context.revisions.pages(revisionId).first { ImageIdentity.isImageOrdinal(it.ordinal) }
                val unitId = page.unitId
                val sha256 = assertNotNull(page.sourceImage).sha256
                val analyses = ImageAnalysisStore(context.database)
                val person = ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.APPEARANCE)
                analyses.record(
                    ImageAnalysis(
                        key = ImageAnalysisKey(
                            picture.documentId, unitId, sha256, "revision-1", 1, "swe", true,
                        ),
                        outcome = AnalysisOutcome.DONE,
                        description = "Två män vid en bil.",
                        tags = listOf(person),
                        errorCode = null,
                        caption = "Fig. 3",
                        pageContext = "Sidan 14: en man vid en bil.",
                    ),
                )
                val service = context.imageTagService

                val before = service.tags(collectionId, picture.documentId, unitId)
                assertEquals("Två män vid en bil.", before.description)
                assertEquals(listOf(person), before.tags)

                val confirmed = runBlocking {
                    service.edit(
                        collectionId, picture.documentId, unitId, "e1", TagEditAction.CONFIRM,
                        person.copy(source = TagSource.MANUAL), before.tagsVersion,
                    )
                }
                assertEquals(ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.MANUAL), confirmed.tags.single())
                assertEquals(1L, confirmed.tagsVersion)

                val published = context.revisions.pages(assertNotNull(context.revisions.activeRevisionId(picture.documentId)))
                    .first { it.unitId == unitId }
                assertEquals(
                    "Fig. 3\n\nTvå män vid en bil.\n\nman: Jeffrey Epstein\n\nSidan 14: en man vid en bil.",
                    published.extractedText,
                )

                val added = runBlocking {
                    service.edit(
                        collectionId, picture.documentId, unitId, "e2", TagEditAction.ADD,
                        ImageTag(TagCategory.OBJECT, "bil", "Volvo PV444", TagSource.TEXT),
                        confirmed.tagsVersion,
                    )
                }
                assertTrue(added.tags.any { it.label == "bil" && it.source == TagSource.MANUAL })

                val removed = runBlocking {
                    service.edit(
                        collectionId, picture.documentId, unitId, "e3", TagEditAction.REMOVE,
                        person.copy(source = TagSource.MANUAL),
                        added.tagsVersion,
                    )
                }
                assertEquals(listOf("bil"), removed.tags.map { it.label })
                val afterRemove = context.revisions.pages(assertNotNull(context.revisions.activeRevisionId(picture.documentId)))
                    .first { it.unitId == unitId }
                assertEquals(
                    "Fig. 3\n\nTvå män vid en bil.\n\nbil: Volvo PV444\n\nSidan 14: en man vid en bil.",
                    afterRemove.extractedText,
                )
            }
        }
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a repeated edit id changes nothing and names who made the first one`() {
        val directory = Files.createTempDirectory("infoscry-image-tags-repeat")
        RescanHarness(directory).use { harness ->
            val picture = harness.importPhotoOnly()
            AppContext.open(harness.archiveDir).use { context ->
                context.attachDocumentEmbedder { TestDocumentEmbedder() }
                context.attachChunker(Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)))
                val collectionId = picture.documentId.let { context.documents.get(it)!!.collectionId }
                val revisionId = assertNotNull(context.revisions.activeRevisionId(picture.documentId))
                val page = context.revisions.pages(revisionId).first { ImageIdentity.isImageOrdinal(it.ordinal) }
                val sha256 = assertNotNull(page.sourceImage).sha256
                ImageAnalysisStore(context.database).record(
                    ImageAnalysis(
                        key = ImageAnalysisKey(picture.documentId, page.unitId, sha256, "revision-1", 1, "swe", false),
                        outcome = AnalysisOutcome.DONE,
                        description = "En bil.",
                        tags = emptyList(),
                        errorCode = null,
                    ),
                )
                val owner = testOwner(context.database)
                val service = context.imageTagService
                val tag = ImageTag(TagCategory.OBJECT, "bil", "Volvo PV444", TagSource.MANUAL)

                val first = runBlocking {
                    withContext(Actor.element(owner.id)) {
                        service.edit(
                            collectionId, picture.documentId, page.unitId, "same-edit", TagEditAction.ADD,
                            tag, service.tags(collectionId, picture.documentId, page.unitId).tagsVersion,
                        )
                    }
                }
                val publishedRevision = context.revisions.activeRevisionId(picture.documentId)
                val repeated = runBlocking {
                    service.edit(
                        collectionId, picture.documentId, page.unitId, "same-edit", TagEditAction.ADD,
                        tag, first.tagsVersion,
                    )
                }

                assertEquals(first, repeated)
                assertEquals(publishedRevision, context.revisions.activeRevisionId(picture.documentId), "no new revision")
                assertEquals(owner.id.value, first.edits.single().createdBy?.id)
            }
        }
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `two edits from one version leave the second a conflict`() {
        val directory = Files.createTempDirectory("infoscry-image-tags-conflict")
        RescanHarness(directory).use { harness ->
            val picture = harness.importPhotoOnly()
            AppContext.open(harness.archiveDir).use { context ->
                context.attachDocumentEmbedder { TestDocumentEmbedder() }
                context.attachChunker(Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)))
                val collectionId = picture.documentId.let { context.documents.get(it)!!.collectionId }
                val revisionId = assertNotNull(context.revisions.activeRevisionId(picture.documentId))
                val page = context.revisions.pages(revisionId).first { ImageIdentity.isImageOrdinal(it.ordinal) }
                val sha256 = assertNotNull(page.sourceImage).sha256
                ImageAnalysisStore(context.database).record(
                    ImageAnalysis(
                        key = ImageAnalysisKey(picture.documentId, page.unitId, sha256, "revision-1", 1, "swe", false),
                        outcome = AnalysisOutcome.DONE,
                        description = "En bil.",
                        tags = emptyList(),
                        errorCode = null,
                    ),
                )
                val service = context.imageTagService
                val version = service.tags(collectionId, picture.documentId, page.unitId).tagsVersion
                runBlocking {
                    service.edit(
                        collectionId, picture.documentId, page.unitId, "first", TagEditAction.ADD,
                        ImageTag(TagCategory.OBJECT, "bil", null, TagSource.MANUAL), version,
                    )
                }

                assertFailsWith<EditConflictException> {
                    runBlocking {
                        service.edit(
                            collectionId, picture.documentId, page.unitId, "second", TagEditAction.ADD,
                            ImageTag(TagCategory.PERSON, "man", null, TagSource.MANUAL), version,
                        )
                    }
                }
            }
        }
        directory.toFile().deleteRecursively()
    }
}
