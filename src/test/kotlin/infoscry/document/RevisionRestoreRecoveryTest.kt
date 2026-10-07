package infoscry.document

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.embedding.TestDocumentEmbedder
import infoscry.jobs.seedCollectionWithId
import infoscry.storage.RestorePhase
import infoscry.storage.RevisionState
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.system.exitProcess
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A restore across a real process death, at every instant it has.
 *
 * A restore is staged, embedded and then published through the publication protocol, so its recovery is that
 * protocol's recovery plus one question the protocol cannot answer for it: what the restore request itself
 * was left as. The child JVM runs the real restore and is force-terminated at the chosen instant, so the
 * state asserted afterwards is the state production would leave. Each case asserts the same three things:
 * exactly one reading is served and it is the one the database publishes, the restore request says what
 * happened to it (never "in flight" once the archive is open), and no history was lost either way.
 */
class RevisionRestoreRecoveryTest {

    private lateinit var dataDir: Path
    private lateinit var fixture: RestoreDocument
    private val collection = CollectionId("default")

    @BeforeTest
    fun prepareAnArchiveWithThreePublishedReadings() {
        dataDir = Files.createTempDirectory("infoscry-restore-recovery")
        AppContext.open(dataDir).use { context ->
            context.seedCollectionWithId("default", "Default")
            fixture = RestoreFixtures.publishGenerations(context, dataDir, collection)
        }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun aKillAfterTheCandidateIsStagedLeavesTheActiveRevisionAndFailsTheRequest() {
        assertRecoveryOf(RestoreStep.STAGED, authorityMoved = false)
    }

    @Test
    fun aKillAfterTheEmbeddingLeavesTheActiveRevisionAndFailsTheRequest() {
        assertRecoveryOf(RestoreStep.EMBEDDED, authorityMoved = false)
    }

    @Test
    fun aKillAfterTheStagedIndexCommitLeavesTheActiveRevisionAndFailsTheRequest() {
        assertRecoveryOf(RestoreStep.STAGED_COMMITTED, authorityMoved = false)
    }

    @Test
    fun aKillAfterTheAuthorityMovesRollsTheRestoreForward() {
        assertRecoveryOf(RestoreStep.AUTHORITATIVE, authorityMoved = true)
    }

    @Test
    fun aKillBeforeTheSnapshotIsPublishedRollsTheRestoreForward() {
        assertRecoveryOf(RestoreStep.SNAPSHOT_PUBLISHED, authorityMoved = true)
    }

    @Test
    fun aKillAfterThePublicationIsMarkedPublishedStillRecordsTheRestoreAsPublished() {
        assertRecoveryOf(RestoreStep.PUBLISHED, authorityMoved = true)
    }

    private fun assertRecoveryOf(step: RestoreStep, authorityMoved: Boolean) {
        terminateRestoreAt(step)

        AppContext.open(dataDir, restoreEmbedder = { TestDocumentEmbedder() }).use { context ->
            assertTrue(
                context.revisions.unfinishedIntents().isEmpty(),
                "an unfinished publication must be resolved before the archive is served",
            )
            assertTrue(
                context.revisions.unfinishedRestores().isEmpty(),
                "a restore request may not be left in flight once the archive is open",
            )
            val record = assertNotNull(context.revisions.restoreForRequest(collection, fixture.documentId, REQUEST))
            val active = assertNotNull(context.revisions.activeRevisionId(fixture.documentId))
            val history = context.revisions.revisions(fixture.documentId)

            if (authorityMoved) {
                assertEquals(record.newRevisionId, active, "after a kill at $step the restore is the active reading")
                assertEquals(RestorePhase.PUBLISHED, record.phase)
                assertNull(record.errorCode)
                assertEquals(RevisionState.PUBLISHED, context.revisions.revision(record.newRevisionId)?.state)
                assertEquals(listOf("market first"), searchTexts(context, "market"))
                assertEquals(RestoreFixtures.STABLE_PAGE, searchTexts(context, "harbour").single())
                assertEquals("market first", assertNotNull(context.content.readUnit(fixture.unitIds[1])).extractedText)
                assertEquals(4, history.size)
            } else {
                assertEquals(fixture.revisionIds.last(), active, "after a kill at $step the old reading wins")
                assertEquals(RestorePhase.FAILED, record.phase)
                assertEquals(RestoreRefusalException.INTERRUPTED, record.errorCode)
                assertEquals(RevisionState.WITHDRAWN, context.revisions.revision(record.newRevisionId)?.state)
                assertEquals(listOf("market third"), searchTexts(context, "market"))
                assertEquals("market third", assertNotNull(context.content.readUnit(fixture.unitIds[1])).extractedText)
            }

            // No history was lost either way: the three published readings are still readable by their own
            // revision, and the restore never replaced or edited any of them.
            fixture.revisionIds.zip(RestoreFixtures.MARKET_READINGS).forEach { (revisionId, reading) ->
                assertEquals(reading, assertNotNull(context.revisions.pageForUnit(revisionId, fixture.unitIds[1])).extractedText)
            }
            assertTrue(history.map { it.id }.containsAll(fixture.revisionIds))
            assertEquals(1, searchTexts(context, "market").size, "exactly one coherent reading is served")
        }
    }

    private fun searchTexts(context: AppContext, query: String): List<String> =
        RestoreFixtures.searchTexts(context, query, collection)

    /** Runs the real restore in a child JVM and force-terminates it at [step]. */
    private fun terminateRestoreAt(step: RestoreStep) {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            RevisionRestoreHarness::class.java.name,
            "--data-dir",
            dataDir.toString(),
            "--document-id",
            fixture.documentId.value,
            "--request-id",
            REQUEST,
            "--expected",
            fixture.revisionIds.last(),
            "--restore",
            fixture.revisionIds.first(),
            "--stop-after",
            step.name,
        ).redirectErrorStream(true).start()

        val output = Collections.synchronizedList(mutableListOf<String>())
        val pump = Thread {
            process.inputReader().useLines { lines -> lines.forEach { output += it } }
        }
        pump.isDaemon = true
        pump.start()

        val ready = "HARNESS READY ${step.name}"
        val deadline = System.nanoTime() + HARNESS_TIMEOUT_NANOS
        var reached = output.contains(ready)
        while (!reached && System.nanoTime() < deadline && process.isAlive) {
            Thread.sleep(POLL_MILLIS)
            reached = output.contains(ready)
        }
        if (!reached) {
            process.destroyForcibly()
            process.waitFor()
            throw AssertionError(
                "the restore harness never reached $step; output was:\n${output.joinToString("\n")}",
            )
        }
        process.destroyForcibly()
        process.waitFor()
    }

    private companion object {
        const val REQUEST = "request-recovery"
        const val HARNESS_TIMEOUT_NANOS: Long = 120_000_000_000L
        const val POLL_MILLIS: Long = 100L
    }
}

/**
 * A child JVM that runs one real restore and stops it through the service's own observation seam.
 *
 * It prints `HARNESS READY <step>` at the instant the test asked for and then holds the process open — the
 * data-directory lock included, exactly as a running server would — until the test terminates it.
 */
object RevisionRestoreHarness {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList()
        val dataDir = Path.of(value(options, "--data-dir"))
        val documentId = DocumentId(value(options, "--document-id"))
        val stopAfter = runCatching { RestoreStep.valueOf(value(options, "--stop-after")) }.getOrElse {
            System.err.println("--stop-after must be one of ${RestoreStep.entries.joinToString()}")
            exitProcess(2)
        }

        AppContext.open(dataDir, restoreEmbedder = { TestDocumentEmbedder() }).use { context ->
            runBlocking {
                context.revisionRestore.restore(
                    collectionId = CollectionId("default"),
                    documentId = documentId,
                    requestId = value(options, "--request-id"),
                    expectedRevisionId = value(options, "--expected"),
                    restoreRevisionId = value(options, "--restore"),
                ) { step -> if (step == stopAfter) waitForKill(stopAfter) }
            }
        }
    }

    /** Reports the instant and then holds this JVM open; the test always terminates the process. */
    private fun waitForKill(stopAfter: RestoreStep) {
        println("HARNESS READY $stopAfter")
        System.out.flush()
        while (true) {
            Thread.sleep(HOLD_MILLIS)
        }
    }

    private fun value(options: List<String>, name: String): String {
        val index = options.indexOf(name)
        require(index >= 0 && index + 1 < options.size) { "$name is required" }
        return options[index + 1]
    }

    private const val HOLD_MILLIS: Long = 60_000L
}
