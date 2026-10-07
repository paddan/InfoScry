package infoscry.document

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.JobType
import infoscry.jobs.seedCollectionWithId
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.PageApproval
import infoscry.storage.RestorePhase
import infoscry.storage.RevisionState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Explicit restoration of a historical revision, against a real SQLite file and a real Lucene index.
 *
 * What these tests defend is the ticket's deliverable: a person can bring an earlier reading back without
 * rerunning OCR and without rewriting anything that was ever published. A restore is therefore asserted from
 * four sides — what it adds (a new revision), what it keeps (every old one, every page identity), what it
 * refuses (a stale tab, a deletion, a conflicting operation, a revision it may not name), and what it leaves
 * alone when it fails (the revision that was active, and the text that is searchable).
 */
class RevisionRestoreTest {

    private lateinit var dataDir: Path
    private val embedder = SwitchableEmbedder()
    private val collection = CollectionId("default")

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-revision-restore")
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    private fun open(): AppContext = AppContext.open(dataDir, restoreEmbedder = embedder.provider)

    private fun AppContext.restore(
        fixture: RestoreDocument,
        requestId: String = "request-1",
        expected: String = fixture.revisionIds.last(),
        restoring: String = fixture.revisionIds.first(),
        observe: (RestoreStep) -> Unit = {},
    ) = runBlocking {
        revisionRestore.restore(collection, fixture.documentId, requestId, expected, restoring, observe)
    }

    // ---- what a restore adds and what it keeps ----

    @Test
    fun threePublicationsThenARestoreRetainAllHistoryAndStablePageIdsAndRunNoOcr() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val (first, second, third) = fixture.revisionIds
            val artifactsBefore = managedFiles(context, fixture)
            val jobsBefore = context.jobs.list(limit = 100).size
            val firstPagesBefore = context.revisions.pages(first)

            val restore = context.restore(fixture)

            // A new revision, published, that says what it is a restore of.
            val restored = assertNotNull(context.revisions.revision(restore.newRevisionId))
            assertEquals(RestorePhase.PUBLISHED, restore.phase)
            assertEquals(first, restore.restoredFromRevisionId)
            assertEquals(restore.newRevisionId, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(RevisionState.PUBLISHED, restored.state)
            assertEquals(third, restored.parentRevisionId, "the restore descends from the reading it replaced")
            assertEquals("RESTORE", restored.provenance)
            assertNotEquals(first, restore.newRevisionId, "a restore never reactivates a revision in place")

            // All history is retained, none of it edited, and its states say the order it happened in.
            val history = context.revisions.revisions(fixture.documentId)
            assertEquals(4, history.size)
            assertEquals(
                listOf(RevisionState.SUPERSEDED, RevisionState.SUPERSEDED, RevisionState.SUPERSEDED, RevisionState.PUBLISHED),
                history.map { it.state },
            )
            assertEquals(firstPagesBefore, context.revisions.pages(first), "a historical revision was edited")
            assertEquals(
                RestoreFixtures.MARKET_READINGS[1],
                assertNotNull(context.revisions.pageForUnit(second, fixture.unitIds[1])).extractedText,
            )

            // Stable page identity: the restored revision names the same pages, in the same order.
            val restoredPages = context.revisions.pages(restore.newRevisionId)
            assertEquals(fixture.unitIds, restoredPages.map { it.unitId })
            assertEquals(firstPagesBefore.map { it.extractedText }, restoredPages.map { it.extractedText })
            assertEquals(fixture.unitIds, context.content.listUnits(fixture.documentId, -1, 10).map { it.id })
            assertTrue(restoredPages.all { it.approval == PageApproval.APPROVED })

            // No OCR: nothing was queued, no operation exists, no external provider was called, and no page
            // image or any other managed file was created or removed.
            assertEquals(jobsBefore, context.jobs.list(limit = 100).size)
            assertTrue(context.jobs.list(limit = 100).none { it.type == JobType.RESCAN })
            assertTrue(context.ocrOperations.operations(fixture.documentId).isEmpty())
            assertEquals(0, RestoreFixtures.rows(context, "ocr_external_calls", fixture.documentId, "owner_id"))
            assertEquals(artifactsBefore, managedFiles(context, fixture))
            // The only model call is the embedder re-embedding the restored passages, once per passage.
            assertEquals(restoredPages.size, embedder.calls.get())
        }
    }

    @Test
    fun theRestoredReadingIsTheOnlyOneSearchReturnsAndOldEvidenceOpensItsOwnRevision() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val (first, second, third) = fixture.revisionIds
            assertEquals(listOf("market third"), RestoreFixtures.searchTexts(context, "market", collection))

            val restore = context.restore(fixture)

            assertEquals(listOf("market first"), RestoreFixtures.searchTexts(context, "market", collection))
            assertEquals(emptyList(), RestoreFixtures.searchTexts(context, "second", collection))
            assertEquals(emptyList(), RestoreFixtures.searchTexts(context, "third", collection))
            assertEquals(listOf("harbour notes"), RestoreFixtures.searchTexts(context, "harbour", collection))
            val hit = context.search.search(
                "market",
                infoscry.search.SearchMode.KEYWORD,
                infoscry.search.SearchFilters(collectionId = collection),
            ).hits.single()
            assertEquals(restore.newRevisionId, hit.revisionId, "a new hit names the revision that was restored")

            // The live unit and the restored revision say the restored text; the replaced readings still say
            // their own, because a saved excerpt opens against the revision it was taken from.
            assertEquals("market first", assertNotNull(context.content.readUnit(fixture.unitIds[1])).extractedText)
            assertEquals(
                "market third",
                assertNotNull(context.revisions.pageForUnit(third, fixture.unitIds[1])).extractedText,
            )
            assertEquals(
                "market second",
                assertNotNull(context.revisions.pageForUnit(second, fixture.unitIds[1])).extractedText,
            )
            assertEquals(
                "market first",
                assertNotNull(context.revisions.pageForUnit(first, fixture.unitIds[1])).extractedText,
            )
        }
    }

    @Test
    fun aRevisionAnImportPublishedWithoutStoredVectorsIsRestoredByReEmbeddingItsPassages() {
        open().use { context ->
            // The reading an import publishes carries no stored vectors (they only ever lived in the index),
            // which is why a restore re-embeds rather than trusting whatever a history row holds.
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "an import's text")
            val unit = context.content.commitExtractedUnit(
                documentId = documentId,
                fingerprint = infoscry.extract.ExtractionFingerprint.of(
                    "import-${documentId.value}",
                    infoscry.extract.ExtractionSettings(ocrLanguages = "eng"),
                ),
                key = "page-1",
                ordinal = 0,
                draft = infoscry.extract.ContentUnitDraft(
                    locator = infoscry.domain.SourceLocation.TextLines(1, 1),
                    extractedText = "import reading",
                    searchText = "import reading",
                    method = infoscry.domain.ExtractionMethod.DIRECT_TEXT,
                ),
                artifactRoot = dataDir.resolve("artifacts"),
            ).unit
            context.content.replaceUnitChunks(
                unitId = unit.id,
                drafts = listOf(
                    infoscry.chunk.ChunkDraft(0, "import reading", 0, "import reading".length, 5, 0, 4),
                ),
                chunkerVersion = "test",
                tokenizerId = "test",
                maxSequenceTokens = 512,
                overlapTokens = 0,
            )
            val imported = assertNotNull(context.revisions.recordPublishedContent(documentId, "IMPORT"))
            assertTrue(context.revisions.chunks(imported).none { it.isStaged }, "an import stores no vectors")
            val replacement = RestoreFixtures.stage(
                context, documentId, imported, listOf("replaced reading"), listOf(unit.id),
            )
            runBlocking { context.revisionPublication.publish(documentId, imported, replacement) }

            val restore = runBlocking {
                context.revisionRestore.restore(collection, documentId, "r1", replacement, imported)
            }

            assertEquals(RestorePhase.PUBLISHED, restore.phase)
            assertTrue(context.revisions.chunks(restore.newRevisionId).all { it.isStaged })
            assertEquals(1, embedder.calls.get())
            assertEquals(listOf("import reading"), RestoreFixtures.searchTexts(context, "import", collection))
            assertEquals(emptyList(), RestoreFixtures.searchTexts(context, "replaced", collection))
        }
    }

    // ---- what a restore refuses, leaving everything as it was ----

    @Test
    fun aRestoreFromAStaleExpectedRevisionRefusesAndChangesNothing() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val before = snapshotOf(context, fixture)

            assertFailsWith<StaleActiveRevisionException> {
                // The tab was rendered when the second reading was active; the third has been published since.
                context.restore(fixture, expected = fixture.revisionIds[1])
            }

            assertEquals(before, snapshotOf(context, fixture))
            assertEquals(0, embedder.calls.get(), "a stale request must be refused before any model call")
        }
    }

    @Test
    fun restoringTheAlreadyActiveRevisionIsRefused() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val before = snapshotOf(context, fixture)

            val refusal = assertFailsWith<RestoreRefusalException> {
                context.restore(fixture, restoring = fixture.revisionIds.last())
            }

            assertEquals(RestoreRefusalException.ALREADY_ACTIVE, refusal.code)
            assertEquals(before, snapshotOf(context, fixture))
        }
    }

    @Test
    fun aRevisionOfAnotherDocumentOrCollectionOrAnUnpublishedCandidateIsNotFoundAndNothingLeaks() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            context.collections.create("Elsewhere").let { other ->
                val foreign = RestoreFixtures.publishGenerations(context, dataDir, other.id)
                val sameCollectionOtherDocument = RestoreFixtures.publishGenerations(context, dataDir, collection)
                val candidate = RestoreFixtures.stage(
                    context, fixture.documentId, fixture.revisionIds.last(), listOf("x", "y"), fixture.unitIds,
                )
                val before = snapshotOf(context, fixture)

                val answers = listOf(
                    foreign.revisionIds.first(),
                    sameCollectionOtherDocument.revisionIds.first(),
                    candidate,
                    "no-such-revision",
                ).map { restoring ->
                    assertFailsWith<NoSuchElementException>(restoring) {
                        context.restore(fixture, restoring = restoring)
                    }.message.orEmpty()
                }

                // One answer for all four: the response confirms nothing about which of them exist elsewhere.
                answers.zip(listOf(foreign.revisionIds.first(), sameCollectionOtherDocument.revisionIds.first(), candidate, "no-such-revision"))
                    .forEach { (message, id) -> assertEquals("no revision with id $id exists for this document", message) }
                assertEquals(before, snapshotOf(context, fixture))
            }
        }
    }

    @Test
    fun aDocumentOfAnotherCollectionIsNotFound() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val other = context.collections.create("Elsewhere")

            assertFailsWith<NoSuchElementException> {
                runBlocking {
                    context.revisionRestore.restore(
                        other.id, fixture.documentId, "r1", fixture.revisionIds.last(), fixture.revisionIds.first(),
                    )
                }
            }
            assertEquals(fixture.revisionIds.last(), context.revisions.activeRevisionId(fixture.documentId))
        }
    }

    @Test
    fun aRestoreDuringADocumentDeletionRefusesSafely() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val before = snapshotOf(context, fixture)
            // The deletion's tombstone is durable: this is the state between its admission and its row removal.
            context.documentService.beginDeletion(collection, listOf(fixture.documentId))

            assertFailsWith<DocumentBeingDeletedException> { context.restore(fixture) }

            assertEquals(before, snapshotOf(context, fixture))
            assertEquals(0, embedder.calls.get())
        }
    }

    @Test
    fun aRestoreDuringACollectionDeletionRefusesSafely() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val before = snapshotOf(context, fixture)
            context.collectionService.beginDeletion(collection, "Default")

            val refusal = assertFailsWith<RestoreRefusalException> { context.restore(fixture) }

            assertEquals(RestoreRefusalException.COLLECTION_NOT_ACTIVE, refusal.code)
            // A collection that is being deleted is not searchable at all, so that is the one thing that
            // differs; the revisions, the live text and the restore records are exactly as they were.
            assertEquals(before.copy(searchable = emptyList()), snapshotOf(context, fixture))
        }
    }

    @Test
    fun aDeletionThatLandsBetweenStagingAndPublishingWinsAndNothingIsResurrected() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            var deletion: infoscry.storage.DeletionOperation? = null

            val failure = assertFailsWith<DocumentBeingDeletedException> {
                context.restore(fixture) { step ->
                    // The candidate exists and is staged; the deletion is admitted before the boundary.
                    if (step == RestoreStep.STAGED) {
                        deletion = context.documentService.beginDeletion(collection, listOf(fixture.documentId))
                    }
                }
            }
            assertNotNull(failure)
            assertEquals(
                fixture.revisionIds.last(),
                context.revisions.activeRevisionId(fixture.documentId),
                "the publication boundary rechecked the deletion and left the active revision alone",
            )
            val record = assertNotNull(context.revisions.restoreForRequest(collection, fixture.documentId, "request-1"))
            assertEquals(RestorePhase.FAILED, record.phase)
            assertEquals(RevisionState.WITHDRAWN, context.revisions.revision(record.newRevisionId)?.state)

            // The deletion then runs to the end, and what it removes includes the restore's own rows.
            runBlocking { context.documentService.rollForward(assertNotNull(deletion)) }
            assertNoRowsRemain(context, fixture.documentId)
            assertEquals(emptyList(), RestoreFixtures.searchTexts(context, "market", collection))
            assertNull(context.documents.get(fixture.documentId))

            // And it cannot be brought back: the document does not exist to restore into.
            assertFailsWith<NoSuchElementException> { context.restore(fixture, requestId = "again") }
            assertNoRowsRemain(context, fixture.documentId)
        }
    }

    @Test
    fun aCollectionDeletionThatLandsBetweenStagingAndPublishingWinsAndTheCurrentRevisionStays() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)

            val refusal = assertFailsWith<RestoreRefusalException> {
                context.restore(fixture) { step ->
                    if (step == RestoreStep.STAGED) context.collectionService.beginDeletion(collection, "Default")
                }
            }

            assertEquals(RestoreRefusalException.COLLECTION_NOT_ACTIVE, refusal.code)
            assertEquals(fixture.revisionIds.last(), context.revisions.activeRevisionId(fixture.documentId))
            val record = assertNotNull(context.revisions.restoreForRequest(collection, fixture.documentId, "request-1"))
            assertEquals(RestorePhase.FAILED, record.phase)
            assertEquals(RevisionState.WITHDRAWN, context.revisions.revision(record.newRevisionId)?.state)
        }
    }

    @Test
    fun aRestoreIsRefusedWhileExclusiveMaintenanceRuns() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val before = snapshotOf(context, fixture)

            runBlocking {
                context.mutations.withExclusiveMaintenance("test rebuild") {
                    assertFailsWith<infoscry.storage.MaintenanceInProgressException> {
                        context.revisionRestore.restore(
                            collection, fixture.documentId, "request-1", fixture.revisionIds.last(),
                            fixture.revisionIds.first(),
                        )
                    }
                }
            }

            assertEquals(before, snapshotOf(context, fixture))
        }
    }

    @Test
    fun twoCallersRepeatingOneRequestStageOneRestore() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)

            val answers = runBlocking {
                (1..4).map {
                    async(Dispatchers.Default) {
                        context.revisionRestore.restore(
                            collection, fixture.documentId, "same", fixture.revisionIds.last(),
                            fixture.revisionIds.first(),
                        )
                    }
                }.map { it.await() }
            }

            assertEquals(1, answers.map { it.restoreId }.toSet().size, "one request restored more than once")
            assertEquals(4, context.revisions.revisions(fixture.documentId).size)
            assertEquals(listOf("market first"), RestoreFixtures.searchTexts(context, "market", collection))
        }
    }

    @Test
    fun aRestoreIsRefusedWhileARescanOwnsTheDocument() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            context.ocrOperations.admit(
                collectionId = collection.value,
                documentId = fixture.documentId,
                baseRevisionId = fixture.revisionIds.last(),
                snapshot = OcrSettingsSnapshot(
                    engine = OcrEngine.TESSERACT,
                    mode = OcrImportMode.FILL_MISSING,
                    language = "eng",
                    extractorVersion = "test",
                ),
                requestId = "rescan-1",
                requestHash = "a".repeat(64),
                pageTotal = 2,
                jobId = null,
            )
            val before = snapshotOf(context, fixture)

            assertFailsWith<infoscry.storage.OcrOperationConflictException> { context.restore(fixture) }

            assertEquals(before, snapshotOf(context, fixture))
        }
    }

    @Test
    fun aRevisionWithNoApprovedPageHasNothingToRestore() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            // A superseded reading none of whose pages anyone approved publishes no text, so restoring it
            // would publish nothing.
            val unapproved = context.database.transaction { connection ->
                connection.prepareStatement(
                    "UPDATE page_text_revisions SET approval = 'PENDING' WHERE revision_id = ?",
                ).use { statement ->
                    statement.setString(1, fixture.revisionIds.first())
                    statement.executeUpdate()
                }
            }
            assertEquals(2, unapproved)
            val before = snapshotOf(context, fixture)

            val refusal = assertFailsWith<RestoreRefusalException> { context.restore(fixture) }

            assertEquals(RestoreRefusalException.NOTHING_TO_RESTORE, refusal.code)
            assertEquals(before, snapshotOf(context, fixture))
        }
    }

    // ---- what a failure leaves alone ----

    @Test
    fun anEmbeddingFailureLeavesTheCurrentRevisionActiveAndSearchable() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val before = snapshotOf(context, fixture)
            embedder.failure = IllegalStateException("the accelerator stopped answering")

            val refusal = assertFailsWith<RestoreRefusalException> { context.restore(fixture) }

            assertEquals(RestoreRefusalException.EMBEDDING_FAILED, refusal.code)
            assertFalse(
                refusal.message.orEmpty().contains("accelerator"),
                "a provider's own words are not repeated to the caller",
            )
            assertEquals(fixture.revisionIds.last(), context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf("market third"), RestoreFixtures.searchTexts(context, "market", collection))
            assertEquals(
                before.copy(
                    revisionStates = before.revisionStates + RevisionState.WITHDRAWN,
                    restoreRows = before.restoreRows + 1,
                ),
                snapshotOf(context, fixture),
                "the staged candidate is withdrawn; nothing else moved",
            )
            val record = assertNotNull(context.revisions.restoreForRequest(collection, fixture.documentId, "request-1"))
            assertEquals(RestorePhase.FAILED, record.phase)
            assertEquals(RestoreRefusalException.EMBEDDING_FAILED, record.errorCode)

            // The failed request is not retried into success: repeating it answers with the failure it was, and a
            // new request, on a machine that embeds, is what succeeds.
            embedder.failure = null
            val replayed = assertFailsWith<RestoreRefusalException> { context.restore(fixture) }
            assertEquals(RestoreRefusalException.EMBEDDING_FAILED, replayed.code)
            assertEquals(listOf("market third"), RestoreFixtures.searchTexts(context, "market", collection))
            val retried = context.restore(fixture, requestId = "request-2")
            assertEquals(RestorePhase.PUBLISHED, retried.phase)
            assertEquals(listOf("market first"), RestoreFixtures.searchTexts(context, "market", collection))
        }
    }

    @Test
    fun aMachineWithNoEmbedderRefusesBeforeStagingAnythingAndNeverFallsBack() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val before = snapshotOf(context, fixture)
            embedder.available = false

            val refusal = assertFailsWith<RestoreRefusalException> { context.restore(fixture) }

            assertEquals(RestoreRefusalException.EMBEDDING_UNAVAILABLE, refusal.code)
            assertEquals(before, snapshotOf(context, fixture), "no candidate was staged")
            assertEquals(0, embedder.calls.get())
            // Keyword search and source reading are untouched.
            assertEquals(listOf("market third"), RestoreFixtures.searchTexts(context, "market", collection))
        }
    }

    // ---- idempotency ----

    @Test
    fun aRepeatedRequestIsTheSameOperationAndAnotherBodyUnderTheSameIdConflicts() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)

            val first = context.restore(fixture)
            val second = context.restore(fixture)

            assertEquals(first.restoreId, second.restoreId, "a repeated request restored twice")
            assertEquals(4, context.revisions.revisions(fixture.documentId).size)
            assertEquals(RestorePhase.PUBLISHED, second.phase)

            assertFailsWith<RestoreRequestConflictException> {
                // The same request id, now naming another historical revision.
                context.restore(fixture, restoring = fixture.revisionIds[1])
            }
            assertEquals(4, context.revisions.revisions(fixture.documentId).size)
        }
    }

    @Test
    fun aRestoreCanBeRestoredAgainAndEveryRevisionStaysReadable() {
        open().use { context ->
            val fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
            val toFirst = context.restore(fixture, requestId = "a")
            val backToThird = context.restore(
                fixture, requestId = "b", expected = toFirst.newRevisionId, restoring = fixture.revisionIds.last(),
            )

            assertEquals(5, context.revisions.revisions(fixture.documentId).size)
            assertEquals(listOf("market third"), RestoreFixtures.searchTexts(context, "market", collection))
            assertEquals(backToThird.newRevisionId, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(fixture.unitIds, context.revisions.pages(backToThird.newRevisionId).map { it.unitId })
        }
    }

    // ---- helpers ----

    /** Everything a refused or failed restore must leave exactly as it was. */
    private data class Snapshot(
        val activeRevisionId: String?,
        val revisionStates: List<RevisionState>,
        val liveTexts: List<String>,
        val searchable: List<String>,
        val restoreRows: Int,
    )

    private fun snapshotOf(context: AppContext, fixture: RestoreDocument) = Snapshot(
        activeRevisionId = context.revisions.activeRevisionId(fixture.documentId),
        revisionStates = context.revisions.revisions(fixture.documentId).map { it.state },
        liveTexts = context.content.listUnits(fixture.documentId, -1, 10).map { it.extractedText },
        searchable = RestoreFixtures.searchTexts(context, "market", collection) +
            RestoreFixtures.searchTexts(context, "harbour", collection),
        restoreRows = RestoreFixtures.rows(context, "revision_restores", fixture.documentId),
    )

    /** Every managed file of one document, by relative path: a restore creates none and removes none. */
    private fun managedFiles(context: AppContext, fixture: RestoreDocument): List<String> {
        val directory = context.paths.documentDir(fixture.collectionId, fixture.documentId)
        if (!Files.exists(directory)) return emptyList()
        return Files.walk(directory).use { entries ->
            entries.filter { Files.isRegularFile(it) }.map { directory.relativize(it).toString() }.sorted().toList()
        }
    }

    private fun assertNoRowsRemain(context: AppContext, documentId: DocumentId) {
        listOf(
            "document_revisions",
            "document_active_revisions",
            "revision_publications",
            "revision_restores",
            "content_units",
        ).forEach { table ->
            assertEquals(0, RestoreFixtures.rows(context, table, documentId), "$table still holds rows")
        }
        listOf("page_text_revisions", "revision_chunks").forEach { table ->
            val rows = context.database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM $table WHERE revision_id NOT IN (SELECT id FROM document_revisions)",
                    ).use { it.next(); it.getInt(1) }
                }
            }
            assertEquals(0, rows, "$table holds rows of a revision that no longer exists")
        }
    }
}
