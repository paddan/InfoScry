package infoscry.document

import infoscry.AppContext
import infoscry.chunk.ChunkDraft
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.jobs.seedCollectionWithId
import infoscry.search.DocumentRow
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.MaintenanceInProgressException
import infoscry.storage.PageApproval
import infoscry.storage.PublicationPhase
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Revisions, staging and publication over a real data directory.
 *
 * The claim these tests defend is the one the ticket exists for: a replacement can be staged, published
 * and read without any reader — a search, a source read or a saved excerpt — ever seeing two readings of
 * one document. They run against a real SQLite file and a real Lucene index, because the properties being
 * asserted are about what those two stores say after an ordered sequence of writes.
 */
class RevisionPublicationTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-revision-publication")
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    // ---- M1: the isolated candidate sink ----

    @Test
    fun stagingACandidateCannotTouchPublishedContent() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val unit = assertNotNull(context.content.readUnit(fixture.unitId))
            assertEquals(BASELINE_TEXT, unit.extractedText, "staging left the published text alone")
            assertEquals(listOf(BASELINE_TEXT), publishedChunkTexts(context, fixture.unitId))

            assertEquals(RevisionState.CANDIDATE, context.revisions.revision(candidate)?.state)
            assertEquals(BASELINE_TEXT, searchTexts(context).single())
        }
    }

    @Test
    fun aStagedCandidateIsNotInTheIndexOfTheReadingTheDocumentPublishes() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            // Staging is SQLite's: the candidate's rows are written only inside the publication boundary,
            // so there is no instant at which a staged row could be read, let alone displace a live hit.
            assertEquals(
                listOf(BASELINE_TEXT),
                searchTexts(context),
                "a staged candidate must not displace the reading the document publishes",
            )
            assertEquals(emptyList(), searchFor(context, "replacement").map { it.text })
            assertEquals(RevisionState.CANDIDATE, context.revisions.revision(candidate)?.state)
        }
    }


    // ---- Ticket 03b: provenance persistence boundary ----

    /**
     * A reference that would leave the root its provenance names is refused where the provenance is
     * built — before any draft, page row or revision could carry it. An absolute reference, one that
     * climbs out with `..`, one that cancels its way out (`attempt/../../elsewhere.png`) and one that
     * names the root itself are all the same refusal: the pair of root and reference is what confines an
     * image, and a reference that widens it names pixels outside the archive's own area.
     */
    @Test
    fun aProvenanceReferenceThatEscapesItsNamedRootIsRejectedBeforePersistence() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            listOf("/etc/passwd", "../elsewhere.png", "attempt/../../elsewhere.png", ".", "a/..").forEach { reference ->
                assertFailsWith<IllegalArgumentException>("'$reference' was accepted as a source image reference") {
                    SourceImageProvenance(
                        root = SourceImageRoot.ARTIFACTS,
                        relativePath = reference,
                        sha256 = "b".repeat(64),
                        width = 1200,
                        height = 1600,
                        renderVersion = 1,
                    )
                }
            }

            // And nothing of a refused reference reached persistence: the candidate still holds exactly the
            // page the fixture staged, and no page of it names an image.
            assertEquals(
                listOf(CANDIDATE_TEXT),
                context.revisions.pages(candidate).map { it.extractedText },
                "a provenance refused at construction still reached the store",
            )
            assertTrue(context.revisions.pages(candidate).all { page -> page.sourceImage == null })
        }
    }

    /**
     * The two roots stay distinct through a round trip: a page read from a bounded copy this pipeline
     * wrote names the document's artifacts, and a picture that *is* the document names the directory
     * holding the managed copy — each with its own reference, hash and (where a reader measured one)
     * dimensions, read back exactly as staged. A measurement is a pair: an unmeasured artifact measures
     * nothing in either axis.
     */
    @Test
    fun aStagedPagesProvenanceReadsBackAsItWasWrittenUnderItsOwnRoot() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            context.revisions.appendPage(
                candidate,
                infoscry.storage.RevisionPageDraft(
                    ordinal = 1,
                    unitId = ContentUnitId.new(),
                    locator = SourceLocation.PdfPage(2),
                    extractedText = "read from a bounded copy",
                    searchText = "read from a bounded copy",
                    extractionMethod = ExtractionMethod.OCR,
                    approval = PageApproval.APPROVED,
                    sourceImage = SourceImageProvenance(
                        root = SourceImageRoot.ARTIFACTS,
                        relativePath = "attempt/pages/page-000002.png",
                        sha256 = "c".repeat(64),
                        width = 1200,
                        height = 1600,
                        renderVersion = 2,
                    ),
                ),
            )
            val derived = assertNotNull(context.revisions.page(candidate, 1)).sourceImage
            assertEquals(SourceImageRoot.ARTIFACTS, assertNotNull(derived).root)
            assertEquals("attempt/pages/page-000002.png", derived.relativePath)
            assertEquals("c".repeat(64), derived.sha256)
            assertEquals(1200, derived.width)
            assertEquals(1600, derived.height)
            assertEquals(2, derived.renderVersion)

            context.revisions.appendPage(
                candidate,
                infoscry.storage.RevisionPageDraft(
                    ordinal = 2,
                    unitId = ContentUnitId.new(),
                    locator = SourceLocation.PdfPage(3),
                    extractedText = "read from the managed copy",
                    searchText = "read from the managed copy",
                    extractionMethod = ExtractionMethod.OCR,
                    approval = PageApproval.APPROVED,
                    sourceImage = SourceImageProvenance(
                        root = SourceImageRoot.MANAGED_COPY,
                        relativePath = "scan.png",
                        sha256 = "d".repeat(64),
                        width = null,
                        height = null,
                        renderVersion = 1,
                    ),
                ),
            )
            val asIs = assertNotNull(context.revisions.page(candidate, 2)).sourceImage
            assertEquals(SourceImageRoot.MANAGED_COPY, assertNotNull(asIs).root)
            assertEquals("scan.png", asIs.relativePath)
            assertEquals("d".repeat(64), asIs.sha256)
            assertNull(asIs.width, "an artifact nobody measured has no width")
            assertNull(asIs.height, "an artifact nobody measured has no height")
            assertEquals(1, asIs.renderVersion)
        }
    }

    // ---- M2: publication ----

    @Test
    fun publishingReplacesThePublishedTextAndKeepsTheOldReadingReadable() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
            }

            val intent = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.PUBLISHED, intent.phase)
            assertNotNull(intent.authoritativeAt)
            assertNotNull(intent.cleanedAt)

            assertEquals(candidate, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(RevisionState.SUPERSEDED, context.revisions.revision(fixture.revisionId)?.state)
            assertEquals(CANDIDATE_TEXT, assertNotNull(context.content.readUnit(fixture.unitId)).extractedText)
            assertEquals(
                listOf(CANDIDATE_TEXT),
                searchTexts(context),
                "the search index holds exactly one reading of the document",
            )

            // The replaced reading is still SQLite's, which is what a saved excerpt opens against.
            val old = assertNotNull(context.revisions.pageForUnit(fixture.revisionId, fixture.unitId))
            assertEquals(BASELINE_TEXT, old.extractedText)
        }
    }

    @Test
    fun aPictureOnlyCandidateWithNoPassagesPublishesAndClearsTheOldRows() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            assertEquals(1, context.index().chunkCount(CollectionId("default"), fixture.documentId))
            val candidate = context.revisions.openCandidate(fixture.documentId, fixture.revisionId, "TEST_REPLACEMENT")
            context.revisions.appendPage(
                candidate,
                infoscry.storage.RevisionPageDraft(
                    ordinal = 0,
                    unitId = fixture.unitId,
                    locator = SourceLocation.Image("photo.png"),
                    extractedText = "",
                    searchText = "",
                    extractionMethod = ExtractionMethod.DIRECT_TEXT,
                    approval = PageApproval.APPROVED,
                ),
            )
            context.revisions.appendPage(
                candidate,
                infoscry.storage.RevisionPageDraft(
                    ordinal = infoscry.image.ImageIdentity.ORDINAL_BASE,
                    unitId = ContentUnitId.new(),
                    locator = SourceLocation.ImageRegion(SourceLocation.Image("photo.png"), 1, null),
                    extractedText = "",
                    searchText = "",
                    extractionMethod = ExtractionMethod.DIRECT_TEXT,
                    approval = PageApproval.APPROVED,
                    sourceImage = SourceImageProvenance(
                        root = SourceImageRoot.MANAGED_COPY, relativePath = "photo.png", sha256 = "e".repeat(64),
                        width = 10, height = 10, renderVersion = 1,
                    ),
                ),
            )

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
            }

            val intent = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.PUBLISHED, intent.phase, "${intent.errorCode}: ${intent.errorMessage}")
            assertEquals(candidate, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(0, context.index().chunkCount(CollectionId("default"), fixture.documentId))
        }
    }

    @Test
    fun aCandidateWithNoPassagesAndWordlessTextWithoutAPictureIsStillRefused() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = context.revisions.openCandidate(fixture.documentId, fixture.revisionId, "TEST_REPLACEMENT")
            context.revisions.appendPage(
                candidate,
                infoscry.storage.RevisionPageDraft(
                    ordinal = 0,
                    unitId = fixture.unitId,
                    locator = SourceLocation.TextLines(1, 1),
                    extractedText = "",
                    searchText = "",
                    extractionMethod = ExtractionMethod.OCR,
                    approval = PageApproval.APPROVED,
                ),
            )

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
            }

            assertEquals(PublicationPhase.REFUSED, assertNotNull(context.revisions.intent(operation)).phase)
            assertEquals(fixture.revisionId, context.revisions.activeRevisionId(fixture.documentId))
        }
    }

    @Test
    fun aHitNamesTheRevisionItsTextCameFrom() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)
            runBlocking { context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) }

            val hit = searchFor(context, CANDIDATE_TEXT).single()
            assertEquals(candidate, hit.revisionId, "new evidence records the revision it was taken from")
        }
    }

    @Test
    fun aTargetWithNoApprovedTextIsAPendingReviewRatherThanASuccess() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(
                context,
                fixture.documentId,
                fixture.revisionId,
                CANDIDATE_TEXT,
                approval = PageApproval.PENDING,
            )

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
            }

            val intent = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.REFUSED, intent.phase)
            assertEquals(RevisionPublicationService.AWAITING_REVIEW_CODE, intent.errorCode)
            assertEquals(fixture.revisionId, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(BASELINE_TEXT), searchTexts(context))
        }
    }

    @Test
    fun aTargetWhosePassagesAreNotAllEmbeddedIsRefused() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(
                context,
                fixture.documentId,
                fixture.revisionId,
                CANDIDATE_TEXT,
                embed = false,
            )

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
            }

            val intent = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.REFUSED, intent.phase)
            assertEquals(RevisionPublicationService.ARTIFACTS_INCOMPLETE_CODE, intent.errorCode)
            assertEquals(
                listOf(BASELINE_TEXT),
                searchTexts(context),
                "a candidate whose embedding failed leaves the baseline searchable",
            )
        }
    }

    @Test
    fun aPassageLongerThanTheModelWindowIsRefusedRatherThanTruncated() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(
                context,
                fixture.documentId,
                fixture.revisionId,
                CANDIDATE_TEXT,
                tokenCount = 513,
            )

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
            }

            val intent = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.REFUSED, intent.phase)
            assertEquals(RevisionPublicationService.PASSAGE_TOO_LONG_CODE, intent.errorCode)
        }
    }

    /** A document deleted while its candidate waited for publication is not resurrected by it. */
    @Test
    fun aDeletionThatLandsBeforeThePublicationWins() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)
            runBlocking { context.documentService.deleteConfirmed(fixture.collectionId, listOf(fixture.documentId)) }

            assertFailsWith<Throwable> {
                runBlocking { context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) }
            }
            assertNull(context.documents.get(fixture.documentId))
            assertEquals(emptyList(), searchTexts(context), "neither reading is resurrected by the publication")
        }
    }

    @Test
    fun aRebuildHoldingMaintenanceLeavesTheBaselineServingAndAbandonsThePublication() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val held = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val failure = runBlocking {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
                val rebuilding = scope.launch {
                    context.mutations.withExclusiveMaintenance("test rebuild") {
                        held.countDown()
                        release.await(60, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                assertTrue(held.await(20, java.util.concurrent.TimeUnit.SECONDS), "the rebuild holds maintenance")
                val thrown = assertFailsWith<Throwable> {
                    context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
                }
                release.countDown()
                rebuilding.join()
                thrown
            }

            assertTrue(
                failure is infoscry.storage.MaintenanceInProgressException,
                "a publication that cannot enter the boundary must refuse rather than publish, was $failure",
            )
            assertEquals(fixture.revisionId, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(BASELINE_TEXT), searchTexts(context))
            assertTrue(
                context.revisions.unfinishedIntents().isEmpty(),
                "a refused publication leaves no attempt for a startup to resolve",
            )
            assertEquals(RevisionState.CANDIDATE, context.revisions.revision(candidate)?.state)
        }
    }

    // ---- The invariants a publication is only correct if it never breaks ----

    /**
     * A reading the import path wrote is replaced in the same transaction that publishes its replacement:
     * search answers with the previous reading until the publication commits and with the new one after
     * it, never with both, and the replaced reading stays readable from its own revision.
     */
    @Test
    fun publishingOverAnImportReadingNeverServesBothReadings() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishedByImport(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)
            assertEquals(listOf(BASELINE_TEXT), searchTexts(context))

            runBlocking { context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) }

            assertEquals(candidate, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(CANDIDATE_TEXT), searchTexts(context))
            assertEquals(1, context.index().rowCount(fixture.documentId), "the index holds the rows of one reading")
            assertEquals(emptyList(), searchFor(context, "baseline").map { it.text }, "the replaced reading's rows are gone")
            assertEquals(
                BASELINE_TEXT,
                assertNotNull(context.revisions.pageForUnit(fixture.revisionId, fixture.unitId)).extractedText,
            )
        }
    }

    /**
     * A publication record a process left behind without finishing is resolved at the next start: one that
     * never took effect is abandoned and the document keeps its reading.
     */
    @Test
    fun recoveryAbandonsAPublicationRecordThatNeverTookEffect() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)
            val record = context.revisions.prepare(fixture.documentId, fixture.collectionId, fixture.revisionId, candidate)

            val report = runBlocking { context.revisionPublication.recoverUnfinished() }

            assertEquals(listOf(record.id), report.discarded)
            assertEquals(PublicationPhase.ABANDONED, context.revisions.intent(record.id)?.phase)
            assertEquals(fixture.revisionId, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(BASELINE_TEXT), searchTexts(context))
            assertEquals(emptyList(), runBlocking { context.revisionPublication.recoverUnfinished() }.discarded, "recovery is idempotent")
        }
    }


    // ---- T4: what a discard leaves behind ----

    /**
     * T4a: a publication refused by exclusive maintenance before it staged anything is durably
     * abandoned, and the index is untouched — there is no row to clean up.
     */
    @Test
    fun aPublicationRefusedByMaintenanceBeforeStagingIsAbandonedAndLeavesTheIndexAlone() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishedByImport(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)
            val rowsBefore = context.index().rowCount(fixture.documentId)

            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = runBlocking {
                val rebuilding = CoroutineScope(Dispatchers.Default).launch {
                    context.mutations.withExclusiveMaintenance("test rebuild") {
                        held.countDown()
                        release.await(60, TimeUnit.SECONDS)
                    }
                }
                assertTrue(held.await(20, TimeUnit.SECONDS), "the rebuild holds maintenance")
                try {
                    context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
                    null
                } catch (thrown: Throwable) {
                    thrown
                } finally {
                    release.countDown()
                    rebuilding.join()
                }
            }

            assertTrue(
                failure is MaintenanceInProgressException,
                "a publication that cannot enter the boundary must refuse rather than publish, was $failure",
            )
            val recorded = context.database.read { connection ->
                connection.prepareStatement("SELECT id FROM revision_publications WHERE target_revision_id = ?").use {
                    it.setString(1, candidate)
                    it.executeQuery().use { rows -> rows.next(); rows.getString(1) }
                }
            }
            val intent = assertNotNull(context.revisions.intent(recorded))
            assertEquals(PublicationPhase.ABANDONED, intent.phase)
            assertEquals(RevisionPublicationService.PUBLICATION_FAILED, intent.errorCode)
            assertNull(intent.authoritativeAt)
            assertEquals(
                rowsBefore,
                context.index().rowCount(fixture.documentId),
                "an attempt refused before staging has no row to write and none to remove",
            )
            assertTrue(context.revisions.unfinishedIntents().isEmpty())
            assertEquals(fixture.revisionId, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(BASELINE_TEXT), searchTexts(context))
        }
    }







    // ---- Fixture ----

    private data class Fixture(
        val collectionId: CollectionId,
        val documentId: DocumentId,
        val unitId: ContentUnitId,
        val revisionId: String,
    )

    /** A document that publishes [text] through a real publication, so its rows and revision agree. */
    private fun published(context: AppContext, text: String): Fixture {
        val collectionId = CollectionId("default")
        val source = Files.createDirectories(dataDir.resolve("sources"))
            .resolve("doc-${SOURCE_SEQUENCE++}.txt")
        Files.writeString(source, text)
        val imported = runBlocking { context.library.importFile(collectionId, source) }
        val documentId = imported.document.id
        val candidate = stageCandidate(context, documentId, null, text)
        runBlocking { context.revisionPublication.publish(documentId, null, candidate) }
        val unitId = assertNotNull(context.content.listUnits(documentId, afterOrdinal = -1, limit = 1).firstOrNull()).id
        return Fixture(collectionId, documentId, unitId, candidate)
    }

    /** A candidate revision staged through the store's isolated sink: text, chunks, and nothing published. */
    private fun stageCandidate(
        context: AppContext,
        documentId: DocumentId,
        parentRevisionId: String?,
        text: String,
        approval: PageApproval = PageApproval.APPROVED,
        embed: Boolean = true,
        tokenCount: Int = 5,
    ): String {
        // A replacement keeps the page's identity when it replaces a page of the reading it descends
        // from, which is what makes a citation into that page survive; only a page the parent did not
        // have gets a new identity.
        val unitId = parentRevisionId?.let { parent -> context.revisions.page(parent, 0)?.unitId }
            ?: ContentUnitId.new()
        val revisionId = context.revisions.openCandidate(documentId, parentRevisionId, "TEST_REPLACEMENT")
        context.revisions.appendPage(
            revisionId,
            infoscry.storage.RevisionPageDraft(
                ordinal = 0,
                unitId = unitId,
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                extractionMethod = ExtractionMethod.OCR,
                approval = approval,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0,
                        text = text,
                        startOffset = 0,
                        endOffset = text.length,
                        tokenCount = tokenCount,
                        tokenStart = 0,
                        tokenEnd = tokenCount - 1,
                        embedding = if (embed) EMBEDDER.embedDocuments(listOf(text)).single() else null,
                    ),
                ),
            ),
        )
        return revisionId
    }

    /**
     * A document that publishes [text] the way the import path does: content units and chunks in SQLite,
     * index rows tagged with no revision at all, and the reading recorded as a published revision
     * afterwards. This is the state every archive that has not been rebuilt is in, and it is the only
     * state in which "remove the rows of the reading being replaced" has rows to remove that no revision
     * id can name.
     */
    private fun publishedByImport(context: AppContext, text: String): Fixture {
        val collectionId = CollectionId("default")
        val source = Files.createDirectories(dataDir.resolve("sources"))
            .resolve("import-${SOURCE_SEQUENCE++}.txt")
        Files.writeString(source, text)
        val documentId = runBlocking { context.library.importFile(collectionId, source) }.document.id
        val unit = context.content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = ExtractionFingerprint.of("import-${documentId.value}", ExtractionSettings(ocrLanguages = "eng")),
            key = "page-1",
            ordinal = 0,
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                method = ExtractionMethod.DIRECT_TEXT,
            ),
            artifactRoot = dataDir.resolve("artifacts"),
        ).unit
        context.content.replaceUnitChunks(
            unitId = unit.id,
            drafts = listOf(chunkDraft(0, text)),
            chunkerVersion = "test",
            tokenizerId = "test",
            maxSequenceTokens = 512,
            overlapTokens = 0,
        )
        runBlocking {
            context.index().replaceDocument(
                listOf(
                    DocumentRow(
                        collectionId = collectionId,
                        documentId = documentId,
                        unitId = unit.id,
                        locator = SourceLocation.TextLines(1, 1),
                        locatorLabel = "line 1",
                        chunk = Chunk(
                            id = ChunkId.new(),
                            contentUnitId = unit.id,
                            ordinal = 0,
                            text = text,
                            startOffset = 0,
                            endOffset = text.length,
                            tokenCount = 5,
                            tokenStart = 0,
                            tokenEnd = 4,
                        ),
                        vector = EMBEDDER.embedDocuments(listOf(text)).single(),
                    ),
                ),
            )
        }
        val revisionId = assertNotNull(context.revisions.recordPublishedContent(documentId, "IMPORT"))
        return Fixture(collectionId, documentId, unit.id, revisionId)
    }

    /** Waits, bounded, for the rebuild to ask for exclusive maintenance so a refusal is deterministic. */
    private fun awaitMaintenanceRequest(context: AppContext) {
        val deadline = System.nanoTime() + MAINTENANCE_REQUEST_TIMEOUT_NANOS
        while (context.mutations.maintenanceInProgress == null) {
            assertTrue(
                System.nanoTime() < deadline,
                "the rebuild never asked for exclusive maintenance",
            )
            Thread.sleep(5)
        }
    }

    private fun publishedChunkTexts(context: AppContext, unitId: ContentUnitId): List<String> =
        context.content.chunksOf(unitId).map { it.text }

    private fun searchTexts(context: AppContext): List<String> =
        searchFor(context, "reading").map { it.text }

    private fun searchFor(context: AppContext, query: String) =
        context.search.search(
            query,
            SearchMode.KEYWORD,
            SearchFilters(collectionId = CollectionId("default")),
        ).hits

    private fun chunkDraft(ordinal: Int, text: String) = ChunkDraft(
        ordinal = ordinal,
        text = text,
        startOffset = 0,
        endOffset = text.length,
        tokenCount = 5,
        tokenStart = 0,
        tokenEnd = 4,
    )

    private companion object {
        const val BASELINE_TEXT = "the baseline reading"
        const val CANDIDATE_TEXT = "the replacement reading"

        val EMBEDDER = TestDocumentEmbedder()

        var SOURCE_SEQUENCE = 0

        /** How long a test lets a publication settle before asserting it has not progressed. */
        const val HANDOFF_SETTLE_MILLIS: Long = 500L

        /** How long a test waits for another writer to ask for exclusive maintenance. */
        const val MAINTENANCE_REQUEST_TIMEOUT_NANOS: Long = 20_000_000_000L

    }
}
