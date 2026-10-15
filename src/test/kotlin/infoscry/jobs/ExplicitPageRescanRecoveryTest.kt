package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.embedding.TestDocumentEmbedder
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.RescanSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/** Real child JVMs are killed at admission, page checkpoints and publication boundaries. */
class ExplicitPageRescanRecoveryTest {

    @Test
    fun `resume returns the current explicit operation after a lost response while active and complete`() {
        val root = Files.createTempDirectory("infoscry-explicit-rescan-repeat-resume")
        try {
            RescanHarness(root).use { harness ->
                val picture = harness.importTwoCleanAndScannedPdf(
                    cleanTexts = listOf(
                        "This first page has enough useful text for the automatic selector to preserve it.",
                        "This second page has enough useful text for the automatic selector to preserve it.",
                    ),
                    scannedText = "the existing scanned page",
                )
                AppContext.open(harness.archiveDir).use { context ->
                    val admitted = harness.admitOnly(context, picture, RescanSelection.explicit(listOf(0, 2)))
                    val service = harness.rescanServiceOf(context, FakePageEngine(readings = listOf("selected page")))
                    val first = runBlocking {
                        service.resume(CollectionId("default"), picture.documentId, admitted.operationId)
                    }
                    val repeatedWhileQueued = runBlocking {
                        service.resume(CollectionId("default"), picture.documentId, admitted.operationId)
                    }
                    assertEquals(admitted.operationId, first.operationId)
                    assertEquals(first.jobId, repeatedWhileQueued.jobId)
                    assertEquals(first.selection, repeatedWhileQueued.selection)

                    val result = harness.runAttempt(
                        context = context,
                        operationId = admitted.operationId,
                        engine = FakePageEngine(readings = listOf("selected page")),
                        embedder = TestDocumentEmbedder(),
                        beforePageCommit = null,
                        picture = picture,
                        useAdmittedJob = true,
                    ).operation
                    assertEquals(OcrOperationStage.COMPLETE, result.stage)
                    assertEquals(first.selection, result.selection)

                    val repeatedAfterCompletion = runBlocking {
                        service.resume(CollectionId("default"), picture.documentId, admitted.operationId)
                    }
                    assertEquals(result, repeatedAfterCompletion)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `explicit selection and committed checkpoints survive process death at durable boundaries`() {
        listOf("AFTER_ADMISSION", "AFTER_PAGE_0", "AFTER_PAGE_2", "BEFORE_PUBLICATION", "AFTER_PUBLICATION")
            .forEach { stopAt -> exerciseCrash(stopAt) }
    }

    private fun exerciseCrash(stopAt: String) {
        val root = Files.createTempDirectory("infoscry-explicit-rescan-recovery")
        try {
            RescanHarness(root).use { harness ->
                val picture = harness.importTwoCleanAndScannedPdf(
                    cleanTexts = listOf(
                        "This first page has enough useful text for the automatic selector to preserve it.",
                        "This second page has enough useful text for the automatic selector to preserve it.",
                    ),
                    scannedText = "the existing scanned page",
                )
                val selection = RescanSelection.explicit(listOf(2, 0, 2))
                val ready = root.resolve("$stopAt-ready")
                val log = root.resolve("$stopAt-child.log")
                val process = ProcessBuilder(
                    javaExecutable(), "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-cp",
                    System.getProperty("java.class.path"), ExplicitPageRescanRecoveryHarness::class.java.name,
                    root.toString(), picture.documentId.value, ready.toString(), stopAt,
                ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
                val marker = try {
                    awaitFile(ready, process, log)
                    val value = Files.readString(ready)
                    process.destroyForcibly()
                    process.waitFor()
                    value.split('|')
                } finally {
                    if (process.isAlive) {
                        process.destroyForcibly()
                        process.waitFor()
                    }
                }
                val operationId = marker[0]
                val committedAtCrash = marker[1].toInt()

                AppContext.open(harness.archiveDir).use { context ->
                    val interrupted = assertNotNull(context.ocrOperations.operation(operationId))
                    assertEquals(selection.canonicalized(3), interrupted.selection)
                    assertEquals(committedAtCrash, interrupted.pagesCommitted)
                    if (stopAt == "AFTER_PUBLICATION") {
                        assertEquals(OcrOperationStage.COMPLETE, interrupted.stage)
                        assertEquals(interrupted.candidateRevisionId, context.revisions.activeRevisionId(picture.documentId))
                    } else {
                        assertEquals(OcrOperationStage.FAILED, interrupted.stage)
                        val resumedEngine = FakePageEngine(readings = listOf("resumed selected page"))
                        val service = harness.rescanServiceOf(context, resumedEngine)
                        val resumed = runBlocking {
                            service.resume(CollectionId("default"), picture.documentId, operationId)
                        }
                        assertEquals(selection.canonicalized(3), resumed.selection)
                        val result = harness.runAttempt(
                            context = context,
                            operationId = operationId,
                            engine = resumedEngine,
                            embedder = TestDocumentEmbedder(),
                            beforePageCommit = null,
                            picture = picture,
                            useAdmittedJob = true,
                        )
                        assertEquals(OcrOperationStage.COMPLETE, result.operation.stage)
                        assertEquals(2, result.operation.pageTotal)
                        val expectedCalls = when (stopAt) {
                            "AFTER_ADMISSION" -> listOf(0, 2)
                            "AFTER_PAGE_0" -> listOf(2)
                            else -> emptyList()
                        }
                        assertEquals(expectedCalls, resumedEngine.pages.map { page -> page.ordinal }, stopAt)
                        interrupted.candidateRevisionId?.let { assertEquals(it, result.operation.candidateRevisionId) }
                    }
                    val candidateId = assertNotNull(interrupted.candidateRevisionId ?:
                        context.ocrOperations.operation(operationId)?.candidateRevisionId)
                    val candidate = context.revisions.pages(candidateId)
                    assertEquals(listOf(0, 1, 2), candidate.map { page -> page.ordinal })
                    assertEquals(
                        "This second page has enough useful text for the automatic selector to preserve it.",
                        candidate[1].extractedText,
                    )
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitFile(path: Path, process: Process, log: Path) {
        val deadline = System.nanoTime() + 90_000_000_000L
        while (!Files.exists(path) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
        if (!Files.exists(path)) {
            process.destroyForcibly()
            process.waitFor()
            error("child did not reach its requested durable boundary; log was: ${Files.readString(log)}")
        }
    }

    private fun javaExecutable(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()
}

/** Opens the archive before admission, then stops only after the requested state is durable. */
object ExplicitPageRescanRecoveryHarness {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val documentId = DocumentId(args[1])
        val ready = Path.of(args[2])
        val stopAt = args[3]
        val selection = RescanSelection.explicit(listOf(2, 0, 2))
        RescanHarness(root, seedCollection = false).use { harness ->
            AppContext.open(harness.archiveDir).use { context ->
                val revisionId = checkNotNull(context.revisions.activeRevisionId(documentId))
                val page = context.revisions.pages(revisionId).first()
                val picture = RescanHarness.Picture(documentId, revisionId, page.unitId.value, root.resolve("unused"))
                val engine = FakePageEngine(readings = listOf("committed before child death", "second committed page"))
                val operation = harness.admitOnly(context, picture, selection)
                val committed = when (stopAt) {
                    "AFTER_ADMISSION" -> 0
                    else -> -1
                }
                if (stopAt == "AFTER_ADMISSION") stopAt(ready, operation.operationId, committed)
                harness.runAttempt(
                    context = context,
                    operationId = operation.operationId,
                    engine = engine,
                    embedder = TestDocumentEmbedder(),
                    beforePageCommit = null,
                    picture = picture,
                    afterPageCommit = { _, ordinal ->
                        if (stopAt == "AFTER_PAGE_$ordinal") {
                            stopAt(ready, operation.operationId, if (ordinal == 0) 1 else 2)
                        }
                    },
                    beforeCandidatePublication = { _, _ ->
                        if (stopAt == "BEFORE_PUBLICATION") stopAt(ready, operation.operationId, 2)
                    },
                    afterPublication = { _, _ ->
                        if (stopAt == "AFTER_PUBLICATION") stopAt(ready, operation.operationId, 2)
                    },
                    useAdmittedJob = true,
                )
            }
        }
    }

    private fun stopAt(ready: Path, operationId: String, committed: Int): Nothing {
        val temporary = ready.resolveSibling("${ready.fileName}.tmp")
        Files.writeString(temporary, "$operationId|$committed")
        Files.move(temporary, ready, StandardCopyOption.ATOMIC_MOVE)
        while (true) Thread.sleep(1_000)
    }
}
