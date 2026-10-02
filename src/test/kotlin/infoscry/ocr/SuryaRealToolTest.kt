package infoscry.ocr

import infoscry.EXTERNAL_TAG
import infoscry.domain.DocumentId
import infoscry.extract.EXTRACTOR_SCHEMA_VERSION
import infoscry.extract.ExternalProcess
import infoscry.extract.ExternalToolMissingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/**
 * What a person actually gets from the local Surya runtime installed on this Mac.
 *
 * Everything else about this engine is tested against a stand-in worker, which pins this project's half of
 * the contract and can never tell whether the real model, the real llama.cpp server and the real page
 * agree. This test is the one that does: it reads the committed fixtures with the runtime the instructions
 * in `docs/installation.md` install, and it records what was measured — the versions it ran against, the
 * wall time of a cold page and a warm one, and the model identity the reading carries.
 *
 * It is tagged `external` and excluded from the default suite, because a machine without the runtime cannot
 * run it. It deliberately does **not** skip when the runtime is absent: the task that runs it exists to
 * prove the runtime works, and a skip would report that proof as a success. A missing runtime is an open
 * blocker, and this test fails with the install line in the message.
 *
 * Two limitations are part of this test rather than of its success: `handwriting-style-caveat.png` is a
 * *typeface render* of the Caveat font, not handwriting written by a person, so it says nothing about
 * transcription quality on real handwriting — that is measured in the pilot on user-supplied samples — and
 * one page has one full-page model call, so the numbers below are that call's, not a throughput claim.
 */
class SuryaRealToolTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-surya-real")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Tag(EXTERNAL_TAG)
    @Test
    fun `the installed runtime reads the committed fixtures and reports what it ran with`() = runBlocking {
        val engine = SuryaOcr.configured()
        assertNotNull(
            engine,
            "this test needs the local Surya runtime: install it with '${SuryaOcr.installRemedy()}' and " +
                "point ${SuryaOcr.INTERPRETER_ENV} and ${SuryaOcr.WORKER_SCRIPT_ENV} at it",
        )

        val typed = fixture(TYPED_FIXTURE)
        val handwriting = fixture(CAVEAT_FIXTURE)
        val interpreter = engine.interpreter
        val before = llamaServerProcessIds()
        val lines = mutableListOf<String>()
        try {
            // The identity probe, measured where it matters: what an attempt records about its runtime has to
            // come from a runtime that is not reading a page, and the number below is what that costs. It runs
            // before the first page on purpose, so the cold time that follows is still a cold page's.
            val identityStarted = System.nanoTime()
            val identity = engine.runtimeIdentity()
            val identityMillis = (System.nanoTime() - identityStarted) / 1_000_000

            val coldStarted = System.nanoTime()
            val typedReading = engine.transcribe(typed, settings())
            val coldMillis = (System.nanoTime() - coldStarted) / 1_000_000

            val warmStarted = System.nanoTime()
            val handwritingReading = engine.transcribe(handwriting, settings())
            val warmMillis = (System.nanoTime() - warmStarted) / 1_000_000

            lines += "interpreter ${interpreterVersion(interpreter)}"
            lines += "surya-ocr ${packageVersion(interpreter, "surya-ocr")}, " +
                "torch ${packageVersion(interpreter, "torch")}, " +
                "transformers ${packageVersion(interpreter, "transformers")}"
            lines += "llama-server ${llamaServerVersion()}"
            lines += "identity probe ${identityMillis}ms: ${identity ?: "undiscovered"}"
            lines += "model ${typedReading.modelVersion}"
            lines += "$TYPED_FIXTURE cold ${coldMillis}ms (worker start and model load included)"
            lines += "$CAVEAT_FIXTURE warm ${warmMillis}ms, one full-page model call per page"
            lines.forEach { line -> println("surya runtime: $line") }

            // What the probe answers has to be the runtime that reads the pages: the same package, backend and
            // weights — and the server build, which is the part a reading's own identity does not carry.
            val probe = assertNotNull(
                identity,
                "the installed runtime could not describe itself, so a changed runtime would not invalidate a " +
                    "committed reading",
            )
            assertContains(probe, "surya-ocr")
            assertContains(probe, SuryaOcr.MODEL_CHECKPOINT)
            assertContains(probe, "llama-server")
            assertTrue(
                probe.startsWith(assertNotNull(typedReading.modelVersion)),
                "the probe and the reading disagree about the runtime: '$probe' against '${typedReading.modelVersion}'",
            )
            assertTypedPage(typedReading)
            assertCaveatPage(handwritingReading)
            assertTrue(
                typedReading.boxes.isNotEmpty() && handwritingReading.boxes.isNotEmpty(),
                "a reading with no boxes cannot show where a match is on the page",
            )
        } finally {
            engine.close()
        }

        val leftBehind = llamaServerProcessIds() - before
        assertEquals(
            emptySet(),
            leftBehind,
            "the engine left llama-server processes behind: $leftBehind",
        )
    }

    // ---- What the two fixtures say --------------------------------------------------------------------

    private fun assertTypedPage(reading: OcrPageResult) {
        val page = normalized(reading.text)
        TYPED_LINES.forEach { line -> assertContains(page, line, message = "the typed page came back as: $page") }
        assertReading(reading)
    }

    private fun assertCaveatPage(reading: OcrPageResult) {
        val page = normalized(reading.text)
        CAVEAT_LINES.forEach { line ->
            assertContains(page, line, message = "the typeface-rendered page came back as: $page")
        }
        assertReading(reading)
    }

    /** What any reading of a page has to be, whichever page it read. */
    private fun assertReading(reading: OcrPageResult) {
        assertEquals(OcrEngine.SURYA, reading.engine)
        val confidence = assertNotNull(
            reading.meanConfidence,
            "the runtime reported no confidence at all, which this test pins as a measurement",
        )
        assertTrue(confidence in 0.0..1.0, "confidence is a fraction, was $confidence")
        reading.boxes.forEach { box ->
            assertEquals(box.text, box.text.trim(), "a box carries the word it read, not its padding")
            val boxConfidence = assertNotNull(box.confidence, "a box came back without a confidence")
            assertTrue(boxConfidence in 0.0..1.0, "a box confidence is a fraction, was $boxConfidence")
            assertTrue(box.width > 0 && box.height > 0, "a box has no area: ${box.left},${box.top}")
        }
        assertNull(reading.errorCode, "the runtime reported ${reading.errorCode}")
        assertTrue(reading.verifiedBlank.not(), "the engine claimed blankness, which only the raster may")
    }

    // ---- What the runtime is, asked of the runtime ----------------------------------------------------

    private suspend fun interpreterVersion(interpreter: Path): String = probe(interpreter, "--version")

    private fun llamaServerVersion(): String = runBlocking {
        try {
            // llama.cpp writes its version to stderr, under a line about initializing, so the answer is
            // picked by what it says rather than by where it came out.
            probe(Path.of(LLAMA_SERVER), "--version", contains = "version:")
        } catch (missing: ExternalToolMissingException) {
            "not found: install it with 'brew install llama.cpp' or set LLAMA_CPP_BINARY"
        }
    }

    /**
     * One installed package's version, asked of the interpreter that has it.
     *
     * The version is read rather than assumed: the instructions pin `surya-ocr==0.22.1`, and a machine where
     * that is not what is installed is exactly the machine whose numbers this test must not report.
     */
    private fun packageVersion(interpreter: Path, name: String): String = runBlocking {
        probe(
            interpreter,
            "-c",
            "import importlib.metadata as m; print(m.version('$name'))",
        )
    }

    private suspend fun probe(executable: Path, vararg arguments: String, contains: String? = null): String {
        val outcome = ExternalProcess.run(
            command = listOf(executable.toString()) + arguments,
            timeout = PROBE_TIMEOUT,
        )
        val answers = (outcome.stdout + "\n" + outcome.stderr).lines()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
        val answer = if (contains == null) {
            answers.firstOrNull()
        } else {
            answers.firstOrNull { line -> line.contains(contains) }
        }
        return answer ?: "unknown (exit ${outcome.exitCode})"
    }

    /**
     * Every `llama-server` process on this machine, by pid.
     *
     * Asked of the operating system rather than of this JVM, for the same reason the other process test
     * does: a server this project orphaned may not be a process this JVM knows about, and an instrument that
     * cannot see one would make "nothing was left behind" true for the wrong reason.
     */
    private fun llamaServerProcessIds(): Set<Long> = ProcessHandle.allProcesses()
        .filter { process -> process.info().command().orElse("").endsWith("/$LLAMA_SERVER") }
        .map { process -> process.pid() }
        .toList()
        .toSet()

    /** A committed fixture, copied where a real extraction would read it from. */
    private fun fixture(name: String): PageImage {
        val root = directory.resolve("pages")
        Files.createDirectories(root)
        val reference = name
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/ocr/$name")) {
            "the fixture /fixtures/ocr/$name is missing"
        }
        stream.use { input -> Files.copy(input, root.resolve(reference), StandardCopyOption.REPLACE_EXISTING) }
        return PageImage.ofFile(
            documentId = DocumentId("doc-surya-real"),
            unitId = "page:1",
            ordinal = 0,
            imageRoot = root,
            imageReference = reference,
            artifactRoot = directory.resolve("artifacts"),
            renderDpi = null,
            rotationDegrees = 0,
        )
    }

    private fun settings(): OcrSettingsSnapshot = OcrSettingsSnapshot(
        engine = OcrEngine.SURYA,
        mode = OcrImportMode.CHECK_AND_IMPROVE,
        language = "eng",
        extractorVersion = EXTRACTOR_SCHEMA_VERSION,
        renderDpi = null,
    )

    /** A page's text with runs of whitespace collapsed, so a reading is compared rather than its spacing. */
    private fun normalized(text: String): String =
        text.lines().joinToString("\n") { line -> line.trim().replace(WHITESPACE, " ") }.trim()

    private companion object {

        /** The two committed fixtures, named as the fixture README names them. */
        const val TYPED_FIXTURE: String = "typed-render.png"
        const val CAVEAT_FIXTURE: String = "handwriting-style-caveat.png"

        /**
         * What the fixtures say, read off the images themselves.
         *
         * The second one is a font render rather than handwriting: the assertions below are about an engine
         * handling irregular letterforms, and they are not evidence about handwriting a person wrote.
         */
        val TYPED_LINES: List<String> = listOf(
            "Invoice 2026-0042",
            "Name: Anna Lindqvist",
            "Total: 1 480,50 SEK",
            "Payment due 2026-10-15",
        )
        val CAVEAT_LINES: List<String> = listOf(
            "Dear Anna,",
            "the meeting moved to Friday 12/10",
            "please bring the notes.",
            "Regards, Erik Andersson",
        )

        /** The binary the runtime spawns, by the name the instructions install it under. */
        const val LLAMA_SERVER: String = "llama-server"

        /** The version question is asked of a tool that answers instantly, so it is bounded tightly. */
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(60)

        val WHITESPACE: Regex = Regex("\\s+")
    }
}
