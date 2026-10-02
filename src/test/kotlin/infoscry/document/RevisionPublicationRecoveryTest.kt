package infoscry.document

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.embedding.TestDocumentEmbedder
import infoscry.jobs.seedCollectionWithId
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Publication across a real process death, at every boundary the contract names.
 *
 * The kill is a kill: a real child JVM runs the production wiring over this data directory, reports the
 * instant it reached, and is force-terminated there. The state asserted afterwards is therefore the
 * state production would leave behind — which is the only way to test that SQLite's write-ahead log and
 * Lucene's commit do what the protocol assumes, because a process that closes politely can never leave
 * an uncommitted segment or an uncommitted transaction.
 *
 * Each case asserts the same thing from a different side of the handoff: exactly one coherent version of
 * the document is served afterwards, the database and the index agree on which one, and a publication
 * whose authority is already durable is completed rather than reported as failed.
 */
class RevisionPublicationRecoveryTest {

    private lateinit var dataDir: Path
    private var documentId: String = ""
    private lateinit var baselineRevisionId: String
    private lateinit var replacementRevisionId: String

    @BeforeTest
    fun prepareArchiveWithAStagedReplacement() {
        dataDir = Files.createTempDirectory("infoscry-revision-recovery")
        AppContext.open(dataDir).use { context ->
            context.seedCollectionWithId("default", "Default")
            val collectionId = CollectionId("default")
            val source = Files.createDirectories(dataDir.resolve("sources")).resolve("doc.txt")
            Files.writeString(source, BASELINE_TEXT)
            val imported = runBlocking { context.library.importFile(collectionId, source) }
            documentId = imported.document.id.value
            baselineRevisionId = stageRevision(context, DocumentId(documentId), null, BASELINE_TEXT)
            runBlocking {
                context.revisionPublication.publish(DocumentId(documentId), null, baselineRevisionId)
            }
            // The replacement is staged and approved and nothing else: the child is the one that publishes.
            replacementRevisionId =
                stageRevision(context, DocumentId(documentId), baselineRevisionId, REPLACEMENT_TEXT)
        }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun aKillBeforeTheIntentIsPersistedLeavesTheOldRevisionServing() {
        assertRecoveryOf(PublicationStep.INTENT_PERSISTED, authorityMoved = false)
    }

    @Test
    fun aKillBeforeTheStagedRowsAreCommittedLeavesTheOldRevisionServing() {
        assertRecoveryOf(PublicationStep.ROWS_STAGED, authorityMoved = false)
    }

    @Test
    fun aKillAfterTheStagedCommitAndBeforeTheAuthorityLeavesTheOldRevisionServing() {
        assertRecoveryOf(PublicationStep.STAGED_COMMITTED, authorityMoved = false)
    }

    @Test
    fun aKillAfterTheAuthorityMovesCompletesTheNewRevision() {
        assertRecoveryOf(PublicationStep.AUTHORITATIVE, authorityMoved = true)
    }

    @Test
    fun aKillBeforeTheSnapshotIsPublishedCompletesTheNewRevision() {
        assertRecoveryOf(PublicationStep.SNAPSHOT_PUBLISHED, authorityMoved = true)
    }

    @Test
    fun aKillAfterTheAttemptIsPublishedStillRemovesTheReplacedReading() {
        assertRecoveryOf(PublicationStep.PUBLISHED, authorityMoved = true)
    }

    @Test
    fun aKillAfterTheCleanupLeavesTheReplacedReadingGone() {
        assertRecoveryOf(PublicationStep.CLEANED, authorityMoved = true)
    }

    /**
     * Kills a real publication at [step] and asserts what the reopened archive serves.
     *
     * [authorityMoved] is the contract's one decision: before the SQLite transaction commits, the
     * reading the document already published wins; after it commits, the database says the replacement
     * is published and the recovery has to make the index agree with it.
     */
    private fun assertRecoveryOf(step: PublicationStep, authorityMoved: Boolean) {
        terminatePublicationAt(step)

        AppContext.open(dataDir).use { context ->
            assertTrue(
                context.revisions.unfinishedIntents().isEmpty(),
                "an unfinished publication must be resolved before the archive is served",
            )
            val document = DocumentId(documentId)
            val active = assertNotNull(context.revisions.activeRevisionId(document))
            assertEquals(
                if (authorityMoved) replacementRevisionId else baselineRevisionId,
                active,
                "the published revision after a kill at $step",
            )
            val targetState = assertNotNull(context.revisions.revision(replacementRevisionId)).state
            assertEquals(
                if (authorityMoved) RevisionState.PUBLISHED else RevisionState.CANDIDATE,
                targetState,
                "a publication whose authority committed is published, never abandoned",
            )

            val baseline = searchTexts(context, "baseline")
            val replacement = searchTexts(context, "replacement")
            val served = (baseline + replacement)
            assertEquals(
                1,
                served.size,
                "exactly one coherent reading is served after a kill at $step, was $served",
            )
            assertEquals(
                if (authorityMoved) REPLACEMENT_TEXT else BASELINE_TEXT,
                served.single(),
                "the reading served agrees with the revision the database publishes",
            )

            // The source the reader opens says the same thing as the hit, which is the mismatch the
            // ticket's deliverable is about.
            val unitId = assertNotNull(context.revisions.page(active, 0)).unitId
            assertEquals(served.single(), assertNotNull(context.content.readUnit(unitId)).extractedText)

            // And the replaced reading is still readable by its own revision, which is what a saved
            // excerpt opens against.
            val kept = if (authorityMoved) baselineRevisionId else replacementRevisionId
            val keptText = if (authorityMoved) BASELINE_TEXT else REPLACEMENT_TEXT
            val keptUnit = if (authorityMoved) unitId else candidateUnitId(context, replacementRevisionId)
            assertEquals(keptText, assertNotNull(context.revisions.pageForUnit(kept, keptUnit)).extractedText)
        }
    }

    private fun candidateUnitId(context: AppContext, revisionId: String): ContentUnitId =
        assertNotNull(context.revisions.page(revisionId, 0)).unitId

    private fun searchTexts(context: AppContext, query: String): List<String> =
        context.search.search(
            query,
            SearchMode.KEYWORD,
            SearchFilters(collectionId = CollectionId("default")),
        ).hits.map { it.text }

    private fun stageRevision(
        context: AppContext,
        documentId: DocumentId,
        parentRevisionId: String?,
        text: String,
    ): String {
        val unitId = parentRevisionId?.let { parent -> context.revisions.page(parent, 0)?.unitId }
            ?: ContentUnitId.new()
        val revisionId = context.revisions.openCandidate(documentId, parentRevisionId, "TEST_REPLACEMENT")
        context.revisions.appendPage(
            revisionId,
            RevisionPageDraft(
                ordinal = 0,
                unitId = unitId,
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                extractionMethod = ExtractionMethod.OCR,
                approval = PageApproval.APPROVED,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0,
                        text = text,
                        startOffset = 0,
                        endOffset = text.length,
                        tokenCount = 5,
                        tokenStart = 0,
                        tokenEnd = 4,
                        embedding = EMBEDDER.embedDocuments(listOf(text)).single(),
                    ),
                ),
            ),
        )
        return revisionId
    }

    /** Runs the real publication in a child JVM and force-terminates it at [step]. */
    private fun terminatePublicationAt(step: PublicationStep) {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            RevisionPublicationHarness::class.java.name,
            "--data-dir",
            dataDir.toString(),
            "--document-id",
            documentId,
            "--base",
            baselineRevisionId,
            "--candidate",
            replacementRevisionId,
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
                "the publication harness never reached $step; output was:\n${output.joinToString("\n")}",
            )
        }
        process.destroyForcibly()
        process.waitFor()
    }

    private companion object {
        const val BASELINE_TEXT = "the baseline reading"
        const val REPLACEMENT_TEXT = "the replacement reading"

        val EMBEDDER = TestDocumentEmbedder()

        const val HARNESS_TIMEOUT_NANOS: Long = 120_000_000_000L
        const val POLL_MILLIS: Long = 100L
    }
}

/**
 * A child JVM that runs one real publication and stops it through the service's own observation seam.
 *
 * It prints `HARNESS READY <step>` at the instant the test asked for and then holds the process open —
 * the data-directory lock included, exactly as a running server would — until the test terminates it.
 */
object RevisionPublicationHarness {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList()
        val dataDir = Path.of(value(options, "--data-dir"))
        val documentId = DocumentId(value(options, "--document-id"))
        val base = value(options, "--base")
        val candidate = value(options, "--candidate")
        val stopAfter = runCatching { PublicationStep.valueOf(value(options, "--stop-after")) }.getOrElse {
            System.err.println("--stop-after must be one of ${PublicationStep.entries.joinToString()}")
            exitProcess(2)
        }

        AppContext.open(dataDir).use { context ->
            runBlocking {
                context.revisionPublication.publish(documentId, base, candidate) { step ->
                    if (step == stopAfter) waitForKill(stopAfter)
                }
            }
        }
    }

    /** Reports the instant and then holds this JVM open; the test always terminates the process. */
    private fun waitForKill(stopAfter: PublicationStep) {
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