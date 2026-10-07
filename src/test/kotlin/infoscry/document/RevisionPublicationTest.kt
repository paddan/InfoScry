package infoscry.document

import infoscry.AppContext
import infoscry.chunk.ChunkDraft
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.jobs.seedCollectionWithId
import infoscry.search.DocumentRow
import infoscry.search.RevisionSnapshotUnavailableException
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

    @Test
    fun aPublicationWaitsForTheReaderThatIsAlreadyInsideAndThenServesOneReading() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val inside = context.revisionSnapshots.acquire()
            try {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
                val publication = runBlocking {
                    val job = scope.launch {
                        context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate)
                    }
                    // The reader is still inside, so the handoff has not begun: the reading it was served
                    // is still there in full, and no candidate row has been written under it.
                    Thread.sleep(HANDOFF_SETTLE_MILLIS)
                    assertFalse(job.isCompleted, "a publication waits for the readers that are already inside")
                    assertEquals(
                        listOf(BASELINE_TEXT),
                        indexTexts(context, "baseline", inside.scope),
                        "the reading the retained reader was served is not missing while it finishes",
                    )
                    assertEquals(emptyList(), indexTexts(context, "replacement", inside.scope))
                    inside.close()
                    job
                }
                runBlocking { publication.join() }
            } finally {
                inside.close()
            }

            assertEquals(candidate, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(
                listOf(CANDIDATE_TEXT),
                searchTexts(context),
                "once the reader is out, exactly one coherent reading is served",
            )
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

    @Test
    fun aDeletionLandedWhileTheRowsAreStagedWinsOverThePublication() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            // The deletion runs on its own thread, but it is only started from the INTENT_PERSISTED hook, so
            // it cannot land before the publication has passed every earlier check: the order is fixed by
            // the hook rather than by thread scheduling. The hook runs before the boundary takes its
            // mutation permit, so the exclusive deletion can complete there, and the publication waits for
            // it — its authority has not moved, which is the exact interleaving the boundary exists for.
            val staged = java.util.concurrent.CountDownLatch(1)
            val deleted = java.util.concurrent.CountDownLatch(1)
            val deleter = Thread {
                runBlocking {
                    context.documentService.deleteConfirmed(fixture.collectionId, listOf(fixture.documentId))
                }
                deleted.countDown()
            }.apply { isDaemon = true }

            val failure = assertFailsWith<Throwable> {
                runBlocking {
                    context.revisionPublication.publish(
                        fixture.documentId,
                        fixture.revisionId,
                        candidate,
                    ) { step ->
                        // The boundary takes the mutation permit, so a deletion can only be landed before it
                        // — which is exactly the ordering the recheck exists to catch.
                        if (step == PublicationStep.INTENT_PERSISTED) {
                            staged.countDown()
                            deleter.start()
                            assertTrue(deleted.await(20, java.util.concurrent.TimeUnit.SECONDS), "the deletion finished")
                        }
                    }
                }
            }
            assertTrue(staged.count == 0L)
            assertTrue(
                failure is infoscry.storage.DocumentBeingDeletedException,
                "a deletion rechecked under the boundary wins, was $failure",
            )
            assertNull(context.documents.get(fixture.documentId))
            assertEquals(
                emptyList(),
                searchTexts(context),
                "neither reading is resurrected by the publication that lost the race",
            )
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
     * T1: a reading the import path wrote — rows with no revision tag at all — is replaced without ever
     * being served beside the reading that replaced it, and stays readable in SQLite afterwards.
     *
     * The row set is the point: an untagged reading cannot be hidden by its revision id, so the only way
     * to keep it from being served beside its replacement is to delete it before the snapshot stops
     * hiding it. Reverting the `deleteSupersededRows` call inside `publish`'s boundary makes this fail at
     * the instant right after the snapshot switch, where the document would answer with both readings.
     */
    @Test
    fun publishingOverAnImportReadingNeverServesBothReadings() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishedByImport(context, BASELINE_TEXT)
            assertNull(
                searchFor(context, "reading").single().revisionId,
                "an import's rows name no revision, which is the reading this publication has to replace",
            )
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val rowsAtStep = linkedMapOf<PublicationStep, List<String>>()
            val searchAtStep = linkedMapOf<PublicationStep, List<String>>()
            runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) { step ->
                    // The rows this document has in the index at this instant, read under the scope the gate
                    // would hand a reader: the search service runs exactly this query with the lease it took.
                    rowsAtStep[step] = indexTexts(context, "reading", context.revisionPublication.revisionScope())
                    // And the answer a user gets, wherever a reader can take a lease at all: inside the
                    // boundary the gate is barred, so there the index read above is the only reading there is.
                    if (step !in INSIDE_BOUNDARY) searchAtStep[step] = searchTexts(context)
                }
            }

            // Every instant the protocol names, including the one between the snapshot switch and the
            // removal of the replaced reading's rows: the document has one reading's rows, never two.
            rowsAtStep.forEach { (step, texts) ->
                assertFalse(
                    texts.contains(BASELINE_TEXT) && texts.contains(CANDIDATE_TEXT),
                    "at $step the index served rows of both readings: $texts",
                )
                assertEquals(
                    1,
                    texts.size,
                    "at $step the document must have exactly one reading's rows, was $texts",
                )
            }
            // The reading a search answers with changes at the snapshot switch and nowhere else: the
            // previous reading in full until then, the one that replaced it afterwards.
            assertEquals(
                listOf(BASELINE_TEXT, CANDIDATE_TEXT, CANDIDATE_TEXT, CANDIDATE_TEXT),
                searchAtStep.values.map { it.single() },
            )

            assertEquals(candidate, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(CANDIDATE_TEXT), searchTexts(context))
            assertEquals(
                1,
                context.index().rowCount(fixture.documentId),
                "the index holds the rows of one reading once the publication has finished",
            )
            assertEquals(
                emptyList(),
                searchFor(context, "baseline").map { it.text },
                "the replaced reading's rows are gone from the index",
            )
            // The replaced reading is still SQLite's, which is what a saved excerpt opens against.
            assertEquals(
                BASELINE_TEXT,
                assertNotNull(context.revisions.pageForUnit(fixture.revisionId, fixture.unitId)).extractedText,
            )
        }
    }

    /**
     * T2: a staged candidate is never searchable — not while its rows are staged, not once they are
     * committed, and not after the attempt is abandoned.
     *
     * Reverting the hiding of the candidate before its rows can exist makes this fail inside the hook at
     * the staged commit, where the committed candidate rows would be found by the read the lease would
     * have authorized.
     */
    @Test
    fun aStagedCandidateIsNeverSearchableEvenWhenTheAttemptFailsAfterItsCommit() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishedByImport(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val searched = mutableListOf<PublicationStep>()
            val attempt = AtomicReference<String>()
            val failure = assertFailsWith<IllegalStateException> {
                runBlocking {
                    context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) { step ->
                        when (step) {
                            PublicationStep.INTENT_PERSISTED ->
                                attempt.set(context.revisions.unfinishedIntents().single().id)
                            PublicationStep.ROWS_STAGED, PublicationStep.STAGED_COMMITTED -> {
                                // The scope the gate would have handed a reader hides the candidate from
                                // before a row of it exists, so nothing of the draft is findable yet.
                                assertEquals(
                                    emptyList(),
                                    indexTexts(context, "replacement", context.revisionPublication.revisionScope()),
                                    "the staged candidate was searchable at $step",
                                )
                                assertEquals(
                                    listOf(BASELINE_TEXT),
                                    indexTexts(context, "reading", context.revisionPublication.revisionScope()),
                                    "the baseline must stay the reading the document publishes at $step",
                                )
                                searched += step
                            }

                            else -> Unit
                        }
                        if (step == PublicationStep.STAGED_COMMITTED) {
                            throw IllegalStateException("the attempt died after its staged commit")
                        }
                    }
                }
            }

            assertEquals(listOf(PublicationStep.ROWS_STAGED, PublicationStep.STAGED_COMMITTED), searched)
            assertEquals("the attempt died after its staged commit", failure.message)
            val intent = assertNotNull(context.revisions.intent(assertNotNull(attempt.get())))
            assertEquals(PublicationPhase.ABANDONED, intent.phase, "a failure before authority abandons the attempt")
            assertEquals(RevisionPublicationService.PUBLICATION_FAILED, intent.errorCode)
            assertNull(intent.authoritativeAt)

            assertTrue(context.revisions.unfinishedIntents().isEmpty(), "an abandoned attempt is not work for a startup")
            assertEquals(fixture.revisionId, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(BASELINE_TEXT), searchTexts(context))
            assertEquals(BASELINE_TEXT, assertNotNull(context.content.readUnit(fixture.unitId)).extractedText)
            assertEquals(
                emptyList(),
                searchFor(context, "replacement").map { it.text },
                "the candidate's committed rows must be gone once the attempt is abandoned",
            )
            assertEquals(
                emptyList(),
                indexTexts(context, "replacement", context.revisionPublication.revisionScope()),
                "nothing may still search for a candidate the document does not publish",
            )
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
            val attempt = AtomicReference<String>()

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
                    context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) { step ->
                        if (step == PublicationStep.INTENT_PERSISTED) {
                            attempt.set(context.revisions.unfinishedIntents().single().id)
                        }
                    }
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
            val intent = assertNotNull(context.revisions.intent(assertNotNull(attempt.get())))
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

    /**
     * T4b: when a discard cannot clean up because maintenance refuses the permit, the attempt stays
     * PREPARED and the candidate stays hidden — and a startup's roll-forward then removes its rows and
     * lets go of the revision.
     */
    @Test
    fun aDiscardThatMaintenanceRefusesLeavesTheAttemptForRecovery() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishedByImport(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)
            val rowsBefore = context.index().rowCount(fixture.documentId)
            val attempt = AtomicReference<String>()

            val release = CountDownLatch(1)
            val rebuilding = AtomicReference<Job?>()
            val observed = mutableListOf<PublicationStep>()
            val failure = runBlocking {
                try {
                    context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) { step ->
                        observed += step
                        when (step) {
                            PublicationStep.INTENT_PERSISTED ->
                                attempt.set(context.revisions.unfinishedIntents().single().id)
                            PublicationStep.STAGED_COMMITTED -> {
                                // The rebuild requests exclusive maintenance while this publication still holds
                                // the permit, so it only gets it once the boundary unwinds — and from then on the
                                // discard's cleanup permit is refused for as long as the rebuild holds it.
                                rebuilding.set(
                                    CoroutineScope(Dispatchers.Default).launch {
                                        context.mutations.withExclusiveMaintenance("test rebuild") {
                                            release.await(60, TimeUnit.SECONDS)
                                        }
                                    },
                                )
                                awaitMaintenanceRequest(context)
                                throw IllegalStateException("the discard cannot clean up")
                            }

                            else -> Unit
                        }
                    }
                    null
                } catch (thrown: Throwable) {
                    thrown
                } finally {
                    release.countDown()
                    rebuilding.get()?.join()
                }
            }
            assertTrue(failure is IllegalStateException, "the publication's own failure propagates, was $failure")

            assertEquals(
                listOf(
                    PublicationStep.INTENT_PERSISTED,
                    PublicationStep.ROWS_STAGED,
                    PublicationStep.STAGED_COMMITTED,
                ),
                observed,
                "the attempt has to reach its staged commit for its discard to be refused",
            )
            assertEquals("the discard cannot clean up", failure.message)
            val id = assertNotNull(attempt.get())
            assertEquals(
                PublicationPhase.PREPARED,
                assertNotNull(context.revisions.intent(id)).phase,
                "a discard that could not remove its rows must leave the attempt for recovery",
            )
            // The committed candidate rows are still in the index, and the scope is the only thing keeping
            // them out of a search: that is what a startup has to clean before anyone is served.
            assertEquals(rowsBefore + 1, context.index().rowCount(fixture.documentId))
            assertEquals(listOf(BASELINE_TEXT), searchTexts(context))
            assertEquals(emptyList(), searchFor(context, "replacement").map { it.text })
            assertTrue(candidate in context.revisionPublication.revisionScope().hiddenRevisionIds)

            val recovery = runBlocking { context.revisionPublication.recoverUnfinished() }
            assertEquals(listOf(id), recovery.discarded)
            assertEquals(PublicationPhase.ABANDONED, assertNotNull(context.revisions.intent(id)).phase)
            assertEquals(rowsBefore, context.index().rowCount(fixture.documentId), "recovery removed the staged rows")
            assertFalse(
                candidate in context.revisionPublication.revisionScope().hiddenRevisionIds,
                "the revision is revealed once nothing of it is left to hide",
            )
            assertEquals(listOf(BASELINE_TEXT), searchTexts(context))
            assertEquals(fixture.revisionId, context.revisions.activeRevisionId(fixture.documentId))
        }
    }

    /**
     * T5: a failure after the authority commit is not a failure to report. The document already serves the
     * new reading, so the caller is answered with the operation's identifier and the publication is
     * finished inline; restoring the old rethrow makes this fail on the call itself.
     */
    @Test
    fun aFailureAfterTheAuthorityCommitIsFinishedRatherThanReported() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishedByImport(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) { step ->
                    if (step == PublicationStep.AUTHORITATIVE) {
                        throw IllegalStateException("the process died after the authority commit")
                    }
                }
            }

            val intent = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.PUBLISHED, intent.phase)
            assertNotNull(intent.authoritativeAt)
            assertNotNull(intent.cleanedAt)
            assertEquals(candidate, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(CANDIDATE_TEXT), searchTexts(context))
            assertEquals(emptyList(), searchFor(context, "baseline").map { it.text })
            assertEquals(
                BASELINE_TEXT,
                assertNotNull(context.revisions.pageForUnit(fixture.revisionId, fixture.unitId)).extractedText,
            )
            val recovery = runBlocking { context.revisionPublication.recoverUnfinished() }
            assertEquals(emptyList(), recovery.completed, "a publication finished inline is not work for a startup")
            assertEquals(emptyList(), recovery.discarded)
        }
    }

    /**
     * T6: a publication whose bookkeeping fails after its reading was switched in keeps serving that
     * reading, and recovery finishes the cleanup it owes.
     *
     * The hook throws on every PUBLISHED observation, which is the last thing the inline completion of an
     * authoritative publication does, so that completion fails too. By then the boundary had committed the
     * rows, moved the authority, removed the replaced reading's rows and switched the snapshot, so the
     * archive agrees with itself and the failure is bookkeeping alone. Reverting the `catch (failure)`
     * block's completion — letting an authoritative publication rethrow instead of finishing itself and
     * returning its operation id — makes this fail on the `publish` call.
     */
    @Test
    fun aPublicationWhoseBookkeepingFailsAfterTheSwitchKeepsServingTheNewReading() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) { step ->
                    if (step == PublicationStep.PUBLISHED) {
                        throw IllegalStateException("the publication's bookkeeping failed after the switch")
                    }
                }
            }

            val intent = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.PUBLISHED, intent.phase)
            assertNotNull(intent.authoritativeAt, "the authority moved, which is why the failure is not reported")
            // The seal is for an archive whose two readings disagree. This one does not, so a reader takes a
            // lease and is served the reading the database publishes.
            context.revisionPublication.acquire().close()
            assertEquals(candidate, context.revisions.activeRevisionId(fixture.documentId))
            assertEquals(listOf(CANDIDATE_TEXT), searchTexts(context))
            assertEquals(emptyList(), searchFor(context, "baseline").map { it.text })

            // The cleanup the failure skipped is what recovery owes, and finishing it changes nothing a
            // reader sees.
            val recovery = runBlocking { context.revisionPublication.recoverUnfinished() }
            assertEquals(emptyList(), recovery.completed, "an attempt already recorded as published is not work")
            assertNotNull(
                assertNotNull(context.revisions.intent(operation)).cleanedAt,
                "recovery finishes the publication, cleanup included",
            )
            context.revisionPublication.acquire().close()
            assertEquals(listOf(CANDIDATE_TEXT), searchTexts(context))
            assertEquals(1, context.index().rowCount(fixture.documentId))
        }
    }

    /**
     * T7: a failure between the snapshot switch and the attempt's own record refuses nothing, because the
     * switch is what makes the index, the snapshot and the database agree.
     *
     * The hook throws on every SNAPSHOT_PUBLISHED observation, so the inline completion fails at its own
     * switch step too and the attempt never reaches PUBLISHED. The scope reveals the target revision from
     * the boundary's handoff on, so a reader is served the reading that was switched in rather than a
     * refusal. Reverting the `gate.current().reveals(...)` condition — sealing after every post-authority
     * failure instead of only for a publication whose switch never happened — makes the reads below throw
     * `RevisionSnapshotUnavailableException`.
     */
    @Test
    fun aFailureBetweenTheSwitchAndTheAttemptsRecordStillServesTheSwitchedReading() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) { step ->
                    if (step == PublicationStep.SNAPSHOT_PUBLISHED) {
                        throw IllegalStateException("the process died before recording the publication")
                    }
                }
            }

            val intent = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.PREPARED, intent.phase, "the attempt's own record never ran")
            assertNotNull(intent.authoritativeAt, "the authority moved, so recovery has to finish it")
            // The switch happened, so the archive is coherent: no refusal and no mixture.
            context.revisionPublication.acquire().close()
            assertEquals(listOf(CANDIDATE_TEXT), searchTexts(context))
            assertEquals(emptyList(), searchFor(context, "baseline").map { it.text })

            // Recovery finishes what the failure skipped, and the reading a reader gets does not change.
            val recovery = runBlocking { context.revisionPublication.recoverUnfinished() }
            assertEquals(listOf(operation), recovery.completed)
            val recovered = assertNotNull(context.revisions.intent(operation))
            assertEquals(PublicationPhase.PUBLISHED, recovered.phase)
            assertNotNull(recovered.cleanedAt)
            context.revisionPublication.acquire().close()
            assertEquals(listOf(CANDIDATE_TEXT), searchTexts(context))
            assertEquals(1, context.index().rowCount(fixture.documentId))
        }
    }

    /**
     * T8: recovery completes a publication whose authority is durable and clears the seal an archive that
     * could not finish it refuses reads with.
     *
     * The state a failure between the switch and the attempt's record leaves is exactly the state recovery
     * exists for: a PREPARED attempt with its authority committed. The seal is raised over it here, the way
     * a process that could not finish the publication would have raised it, and completing the publication
     * is what lets readers in again. Reverting the `gate.unseal()` in `complete` makes this fail at the read
     * after recovery.
     */
    @Test
    fun recoveringAPublicationClearsTheSealThatRefusedReaders() {
        AppContext.open(dataDir).use { context ->
            val fixture = published(context, BASELINE_TEXT)
            val candidate = stageCandidate(context, fixture.documentId, fixture.revisionId, CANDIDATE_TEXT)

            val operation = runBlocking {
                context.revisionPublication.publish(fixture.documentId, fixture.revisionId, candidate) { step ->
                    if (step == PublicationStep.SNAPSHOT_PUBLISHED) {
                        throw IllegalStateException("the process died before recording the publication")
                    }
                }
            }
            assertEquals(PublicationPhase.PREPARED, assertNotNull(context.revisions.intent(operation)).phase)

            context.revisionSnapshots.seal("an authoritative publication could not be finished")
            assertFailsWith<RevisionSnapshotUnavailableException> { context.revisionPublication.acquire() }

            val recovery = runBlocking { context.revisionPublication.recoverUnfinished() }
            assertEquals(listOf(operation), recovery.completed)
            assertEquals(PublicationPhase.PUBLISHED, assertNotNull(context.revisions.intent(operation)).phase)
            // The archive is served again, and what it serves is the reading the database publishes.
            context.revisionPublication.acquire().close()
            assertEquals(listOf(CANDIDATE_TEXT), searchTexts(context))
            assertEquals(emptyList(), searchFor(context, "baseline").map { it.text })
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

    /** The index's own answer under one revision scope; the route above is the same read with a lease. */
    private fun indexTexts(
        context: AppContext,
        query: String,
        scope: infoscry.search.RevisionScope,
    ): List<String> = context.index()
        .searchKeyword(CollectionId("default"), query, null, 10, scope)
        .map { it.text }

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

        /**
         * The steps a publication observes while the gate's handoff is held, where a reader can only be the
         * one that already holds a lease: a real search from here would wait for the handoff it is inside.
         */
        val INSIDE_BOUNDARY = setOf(
            PublicationStep.ROWS_STAGED,
            PublicationStep.STAGED_COMMITTED,
            PublicationStep.AUTHORITATIVE,
        )
    }
}
