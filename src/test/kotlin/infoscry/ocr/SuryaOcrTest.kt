package infoscry.ocr

import infoscry.AppContext
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.extract.ContentUnitDraft
import infoscry.extract.EXTRACTOR_SCHEMA_VERSION
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.ExtractorRegistry
import infoscry.extract.ImageExtractor
import infoscry.extract.OcrUnavailableException
import infoscry.extract.PermitProbeBoundary
import infoscry.extract.awaitCondition
import infoscry.extract.writeFakeExecutable
import infoscry.jobs.Harness
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.DocumentStore
import infoscry.storage.ImportItemOutcome
import infoscry.storage.Instants
import infoscry.storage.SchemaMigrator
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * The local Surya engine: the process it owns, the protocol it speaks, and what a page's reading may claim.
 *
 * The runtime itself is not needed here. A worker is a `/bin/sh` script this test writes, so every failure
 * mode is the test's own — a worker that exits non-zero is one whose last line says `exit 3`, and a worker
 * that holds its stderr open is one that started a helper holding it — and none of them is the
 * environment's. What is proved is this project's half of the contract: which page and which image the
 * worker is handed, what a result must look like before it is a reading, what a missing runtime says, that
 * a stopped page stops the process tree, and that an engine which cannot read is never quietly replaced by
 * Tesseract.
 *
 * The two properties about *reuse* are asserted against a real temporary SQLite file, because an answer
 * about committed output is a store's answer rather than a value compared in memory.
 */
class SuryaOcrTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-surya")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    // ---- The runtime the engine needs is named, never substituted ------------------------------------

    @Test
    fun `a runtime that is not installed is named rather than read with another engine`() = runBlocking {
        val tesseract = RecordingTesseract()
        val surya = SuryaOcr(
            interpreter = directory.resolve("no-such-python"),
            workerScript = directory.resolve("surya_worker.py"),
        )
        val engines = PageOcrEngines(listOf(tesseract, surya))
        val image = writtenPageImage()

        val failure = assertFailsWith<OcrUnavailableException> {
            engines.transcribe(OcrEngine.SURYA, image, suryaSettings())
        }

        assertEquals(SuryaOcr.NEEDS_SURYA_CODE, failure.code)
        assertContains(failure.message.orEmpty(), "surya-ocr==0.22.1")
        assertContains(failure.message.orEmpty(), SuryaOcr.INTERPRETER_ENV)
        assertEquals(setOf(OcrEngine.TESSERACT, OcrEngine.SURYA), engines.configuredKinds)
        assertTrue(tesseract.pages.isEmpty(), "the installed tool read a page that selects another engine")
    }

    @Test
    fun `a configured runtime is found through the interpreter and worker script it is told`() {
        val interpreter = writeFakeExecutable(directory, "python", "exit 0")
        val script = writeFakeExecutable(directory, "surya_worker.py", "exit 0")
        val environment = mapOf(
            SuryaOcr.INTERPRETER_ENV to interpreter.toString(),
            SuryaOcr.WORKER_SCRIPT_ENV to script.toString(),
        )

        val configured = SuryaOcr.configured { variable -> environment[variable] }

        assertNotNull(configured, "a runtime whose interpreter and script both exist was not configured")
        configured.close()
        assertNull(
            SuryaOcr.configured { variable ->
                when (variable) {
                    SuryaOcr.INTERPRETER_ENV -> directory.resolve("no-such-python").toString()
                    else -> environment[variable]
                }
            },
            "an interpreter that does not exist was treated as a configured runtime",
        )
        assertNull(
            SuryaOcr.configured { variable ->
                when (variable) {
                    SuryaOcr.INTERPRETER_ENV -> interpreter.toString()
                    SuryaOcr.WORKER_SCRIPT_ENV -> directory.resolve("no-such-worker.py").toString()
                    else -> null
                }
            },
            "a runtime whose worker script is not there was treated as a configured runtime",
        )
    }

    @Test
    fun `a worker that reports no llama dot cpp names the install`() = runBlocking {
        val surya = engineWith(
            "no-llamacpp",
            mirrorIdentity() + "exit 0\n",
            firstLine = "printf '%s\\n' '{\"protocol\":1,\"type\":\"page\",\"status\":\"error\"," +
                "\"code\":\"NEEDS_LLAMA_CPP\",\"message\":\"llama-server binary not found\"}'\n",
        )
        val engines = PageOcrEngines(listOf(RecordingTesseract(), surya))

        val failure = assertFailsWith<OcrUnavailableException> {
            engines.transcribe(OcrEngine.SURYA, writtenPageImage(), suryaSettings())
        }

        assertEquals(SuryaOcr.NEEDS_LLAMA_CPP_CODE, failure.code)
        assertContains(failure.message.orEmpty(), "brew install llama.cpp")
        assertContains(failure.message.orEmpty(), "LLAMA_CPP_BINARY")
    }

    @Test
    fun `a worker that cannot obtain the model names what has to be fetched`() = runBlocking {
        val surya = engineWith(
            "no-model",
            mirrorIdentity() + "exit 0\n",
            firstLine = "printf '%s\\n' '{\"protocol\":1,\"type\":\"page\",\"status\":\"error\"," +
                "\"code\":\"NEEDS_SURYA_MODEL\",\"message\":\"download failed\"}'\n",
        )

        val failure = assertFailsWith<OcrUnavailableException> {
            surya.transcribe(writtenPageImage(), suryaSettings())
        }

        assertEquals(SuryaOcr.NEEDS_SURYA_MODEL_CODE, failure.code)
        assertContains(failure.message.orEmpty(), "datalab-to/surya-ocr-2-gguf")
        assertContains(failure.message.orEmpty(), "1.36 GiB")
    }

    @Test
    fun `the worker is handed the page's managed image path and its identity`() = runBlocking {
        val request = directory.resolve("request.json")
        val surya = engineWith(
            "records-request",
            mirrorIdentity() + "printf '%s\n' \"\$line\" > '$request'\n" + pageResult(),
        )
        val image = writtenPageImage()

        val reading = surya.transcribe(image, suryaSettings())
        surya.close()

        assertEquals(READING, reading.text)
        val sent = Files.readString(request)
        assertContains(sent, "\"protocol\":${SuryaOcr.PROTOCOL_VERSION}")
        assertContains(sent, "\"unitId\":\"${image.unitId}\"")
        assertContains(sent, "\"ordinal\":${image.ordinal}")
        assertContains(sent, "\"page\":${image.ordinal + 1}")
        assertContains(sent, "\"imagePath\":\"${image.imagePath}\"")
        assertEquals(image.sha256, reading.imageSha256)
        assertEquals(OcrEngine.SURYA, reading.engine)
        assertEquals(A_BLOCK_TEXT, reading.boxes.single().text)
        assertEquals(0.9, assertNotNull(reading.meanConfidence, "the reading reported no confidence"))
        assertEquals(0.9, assertNotNull(reading.boxes.single().confidence, "the box reported no confidence"))
        assertEquals(1, reading.boxes.single().left)
        assertEquals(2, reading.boxes.single().top)
        assertEquals(2, reading.boxes.single().width)
        assertEquals(2, reading.boxes.single().height)
    }

    // ---- What a result has to be before it is a reading ----------------------------------------------

    @Test
    fun `a reading whose text is empty is not a successful blank page`() = runBlocking {
        val surya = engineWith(
            "empty",
            mirrorIdentity() + pageResult(status = "empty", text = "   ", blocks = ""),
        )
        val image = writtenPageImage()

        val reading = surya.transcribe(image, suryaSettings())

        assertEquals("", reading.text, "whitespace was kept as a page's text")
        assertEquals(
            OcrPageResult.EMPTY_READING_CODE,
            reading.errorCode,
            "an empty reading was answered as a successful extraction rather than under the code that says " +
                "it is not one",
        )
        assertFalse(reading.verifiedBlank, "the engine claimed the page is blank, which only the raster may")
        assertNull(reading.meanConfidence, "an engine that reported no confidence was given one")
        // And the caller that holds the raster is what answers the blankness question: this page carries
        // ink, so an empty reading of it is a reading that missed something rather than blank paper.
        assertFalse(
            reading.verifiedAgainst(image).verifiedBlank,
            "a page with ink on it was called blank paper",
        )
    }

    @Test
    fun `a box that lies outside the page is refused rather than kept as where a match is`() = runBlocking {
        // The page the engine is handed is 120x80. A block claiming a region past its edge, or one starting
        // before its origin, is not a region of this page: a citation drawn from it would point somewhere
        // this reading has no evidence for, and the whole reading's boxes are what a citation is made of.
        val outside = listOf(
            """{"text":"$A_BLOCK_TEXT","bbox":[1,2,301,4],"confidence":0.9}""",
            """{"text":"$A_BLOCK_TEXT","bbox":[-5,2,3,4]}""",
            """{"text":"$A_BLOCK_TEXT","bbox":[1,2,3,81]}""",
        )
        outside.forEach { block ->
            val surya = engineWith("out-of-page", mirrorIdentity() + pageResult(blocks = block))

            val failure = assertFailsWith<SuryaWorkerException> {
                surya.transcribe(writtenPageImage(), suryaSettings())
            }

            assertEquals(SuryaWorkerException.MALFORMED_OUTPUT, failure.code, "for the block $block")
            assertContains(failure.message.orEmpty(), "outside the page", message = "for the block $block")
        }
    }

    @Test
    fun `output that is not the protocol is refused rather than parsed loosely`() = runBlocking {
        val surya = engineWith("garbage", mirrorIdentity() + "printf '%s\\n' 'this is not a reading'\nexit 0\n")

        val failure = assertFailsWith<SuryaWorkerException> {
            surya.transcribe(writtenPageImage(), suryaSettings())
        }

        assertEquals(SuryaWorkerException.MALFORMED_OUTPUT, failure.code)
        assertContains(failure.message.orEmpty(), "protocol")
    }

    @Test
    fun `a reading that answers for another page is refused`() = runBlocking {
        // The identity is what makes a reading attributable: a worker that answers with another page's
        // ordinal would otherwise have this page's image recorded against another page's text.
        val surya = engineWith(
            "wrong-identity",
            mirrorIdentity() + pageResult(ordinalExpression = "\$((ordinal + 1))"),
        )

        val failure = assertFailsWith<SuryaWorkerException> {
            surya.transcribe(writtenPageImage(), suryaSettings())
        }

        assertEquals(SuryaWorkerException.MALFORMED_OUTPUT, failure.code)
        assertContains(failure.message.orEmpty(), "page")
    }

    @Test
    fun `a result line over the bound is refused rather than held in memory`() = runBlocking {
        val surya = engineWith(
            "oversized",
            mirrorIdentity() + "head -c 200000 /dev/zero | tr '\\0' 'x'; printf '\\n'; exit 0\n",
            maxResultBytes = 64 * 1024,
        )

        val failure = assertFailsWith<SuryaWorkerException> {
            surya.transcribe(writtenPageImage(), suryaSettings())
        }

        assertEquals(SuryaWorkerException.OUTPUT_TOO_LARGE, failure.code)
        assertContains(failure.message.orEmpty(), "65536")
    }

    @Test
    fun `a helper that holds the pipes open and ignores SIGTERM is stopped with the worker`() = runBlocking {
        val helper = directory.resolve("stubborn-helper.pid")
        val surya = engineWith(
            "stubborn-helper",
            // The helper holds the worker's stdout and stderr open and ignores the signal a graceful stop
            // sends, so only killing it can end this engine's tree. The worker stays up the way the real one
            // does — it reads the next page until its stdin closes — which is what keeps the helper a process
            // of that tree instead of one already re-parented away from it, where no teardown could find it.
            mirrorIdentity() +
                "sh -c 'trap \"\" TERM; exec sleep 300' &\n" +
                "printf '%s' \"\$!\" > '$helper'\n" +
                pageResult() +
                "sleep 300\n",
        )
        val image = writtenPageImage()

        val started = System.nanoTime()
        val reading = surya.transcribe(image, suryaSettings())
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(READING, reading.text, "the reading never came back while the pipes stayed open")
        assertTrue(
            elapsedMillis < PROMPT_RETURN_MILLIS,
            "the reading took ${elapsedMillis}ms, so the parent waited for a stream a helper still holds",
        )
        val helperPid = Files.readString(helper).trim()
        assertTrue(isRunning(helperPid), "the helper was already gone before teardown, so nothing was proved")

        val closed = System.nanoTime()
        surya.close()
        val closeMillis = (System.nanoTime() - closed) / 1_000_000

        // Asserted rather than cleaned up by this test: a teardown that ends the engine while a helper that
        // ignores SIGTERM is still reading pages is exactly the process this fix is about.
        assertFalse(isRunning(helperPid), "the helper outlived the engine that started it")
        assertTrue(closeMillis < PROMPT_RETURN_MILLIS, "teardown took ${closeMillis}ms")
    }

    // ---- A build without the runtime says what to install --------------------------------------------

    @Test
    fun `a build that left Surya out names the install remedy for a page that selects it`() = runBlocking {
        // What a machine without the runtime is: `SuryaOcr.configured()` answers null, production leaves the
        // engine out of the set, and a document that selects Surya reaches this refusal instead of a reading.
        // The words are the engine's own pinned install line, because "install or configure SURYA" is not
        // something a person can act on, and the code is the one the queue and the CLI already show.
        val engines = PageOcrEngines(listOf(RecordingTesseract()))

        val failure = assertFailsWith<OcrUnavailableException> {
            engines.transcribe(OcrEngine.SURYA, writtenPageImage(), suryaSettings())
        }

        assertEquals(SuryaOcr.NEEDS_SURYA_CODE, failure.code)
        assertEquals(setOf(OcrEngine.TESSERACT), engines.configuredKinds)
        assertContains(failure.message.orEmpty(), "uv venv --python 3.12")
        assertContains(failure.message.orEmpty(), "uv pip install surya-ocr==0.22.1")
        assertContains(failure.message.orEmpty(), SuryaOcr.INTERPRETER_ENV)
        assertContains(failure.message.orEmpty(), SuryaOcr.WORKER_SCRIPT_ENV)
    }

    // ---- A worker that failed is a page failure, and the next page gets a worker ----------------------

    @Test
    fun `a page the worker failed on is that page's failure and the next page gets a fresh worker`() =
        runBlocking {
            val marker = directory.resolve("failed-once")
            val surya = engineWith(
                "fails-once",
                mirrorIdentity() +
                    "if [ -f '$marker' ]; then\n" +
                    pageResult().prependIndent("  ") +
                    "else\n  touch '$marker'\n  exit 3\nfi\n",
            )
            val image = writtenPageImage()

            val failure = assertFailsWith<SuryaWorkerException> {
                surya.transcribe(image, suryaSettings())
            }
            assertEquals(SuryaWorkerException.EXITED, failure.code)
            assertContains(failure.message.orEmpty(), "3")

            // A worker that has failed is replaced rather than reused: the reading of the page after it is
            // not this project's failure.
            assertEquals(READING, surya.transcribe(image, suryaSettings()).text)
        }

    @Test
    fun `cancelling a page kills the worker and what it started`() = runBlocking {
        val pidFile = directory.resolve("cancelled.pid")
        val surya = engineWith(
            "patient",
            "sleep 60 &\nprintf '%s %s' \"\$!\" \"\$\$\" > '$pidFile'\nread -r line\nwait\n",
            timeout = Duration.ofMinutes(5),
        )

        val failure = runCatching {
            coroutineScope {
                val page = async(Dispatchers.Default) {
                    surya.transcribe(writtenPageImage(), suryaSettings())
                }
                awaitCondition { recordedProcessIds(pidFile).size == WORKER_AND_HELPER }
                delay(50)
                page.cancel()
                page.await()
            }
        }.exceptionOrNull()
        surya.close()

        assertTrue(failure is CancellationException, "expected a cancellation, got $failure")
        assertEquals(
            emptyList(),
            recordedProcessIds(pidFile).filter(::isRunning),
            "the cancelled page left processes behind",
        )
    }

    @Test
    fun `the production registry reads a picture with the surya engine it was configured with`() =
        runBlocking {
            // The wiring, end to end: a build whose runtime is configured puts the Surya engine in the page
            // readers' set, so an attempt that selects Surya is read by it — and a build without the runtime
            // would refuse the same attempt with `NEEDS_SURYA` instead of reading it with what it has.
            val surya = engineWith("answers", mirrorIdentity() + pageResult())
            val registry = ExtractorRegistry.production(surya = surya)
            val managed = directory.resolve("managed").resolve("original.png")
            Files.createDirectories(managed.parent)
            val fixture = requireNotNull(javaClass.getResourceAsStream("/fixtures/ocr/$PICTURE_FIXTURE")) {
                "the fixture /fixtures/ocr/$PICTURE_FIXTURE is missing"
            }
            fixture.use { stream -> Files.copy(stream, managed, REPLACE_EXISTING) }
            val settings = ExtractionSettings(
                ocrLanguages = "eng",
                ocrMode = OcrImportMode.CHECK_AND_IMPROVE,
                ocrAttempt = OcrAttemptIdentity(
                    engine = OcrEngine.SURYA,
                    language = "eng",
                    transcriptionPromptVersion = OCR_TRANSCRIPTION_PROMPT_VERSION,
                    extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
                    toolVersion = "surya-ocr 0.22.1",
                ),
            )
            val probe = PermitProbeBoundary()
            val input = ExtractionInput(
                documentId = DocumentId("doc-surya"),
                managedPath = managed,
                artifactRoot = directory.resolve("artifacts"),
                settings = settings,
                fingerprint = ExtractionFingerprint.of("e".repeat(64), settings),
                committedUnitKeys = emptySet(),
                boundary = probe,
            )

            val units = registry.extract(input, "image/png")
                .onEach { event -> probe.observed(event) }
                .toList()
                .filterIsInstance<ExtractionEvent.UnitReady>()

            assertEquals(1, units.size, "the picture was not delivered as one page")
            assertContains(
                units.single().unit.extractedText,
                READING,
                message = "the picture was not read by the surya engine the registry was configured with",
            )
            assertEquals(ExtractionMethod.OCR, units.single().unit.method)
        }

    // ---- Committed output, and what a model change means for it --------------------------------------

    @Test
    fun `an identity probe answers what the runtime is without reading a page`() = runBlocking {
        // What the probe is for is a decision made *before* a page is read, so what it must not do is read
        // one: the fake worker here answers `--identity` and would answer a page with `requested`, which
        // the probe never asks for.
        val surya = engineWith(
            "identifies",
            body = "printf '%s\\n' 'requested a page instead of an identity'\n",
            firstLine = identityBranch(IDENTITY_A),
        )
        try {
            assertEquals(IDENTITY_A, surya.runtimeIdentity())
        } finally {
            surya.close()
        }

        // An interpreter that cannot be run, and a worker that answers something which is not this
        // protocol, are both "no identity" rather than a failure: the reading is what reports a runtime
        // that has to be installed, and an identity nobody could discover may not compare equal to one that
        // was discovered.
        val missing = SuryaOcr(
            interpreter = directory.resolve("no-such-python"),
            workerScript = writeFakeExecutable(directory, "never-run.sh", "exit 0"),
        )
        try {
            assertNull(missing.runtimeIdentity(), "a runtime that cannot be run reported an identity")
        } finally {
            missing.close()
        }

        val notAnIdentity = engineWith("mumbles", body = "printf 'not an identity\\n'\n")
        try {
            assertNull(notAnIdentity.runtimeIdentity(), "a line that is not an identity was read as one")
        } finally {
            notAnIdentity.close()
        }

        val silent = engineWith("silent", body = "exit 0\n")
        try {
            assertNull(silent.runtimeIdentity(), "a worker that said nothing reported an identity")
        } finally {
            silent.close()
        }
    }

    @Test
    fun `a page committed by a reading is reused after a restart and a model change is a new reading`() =
        runBlocking {
            val surya = engineWith("answers", mirrorIdentity() + pageResult())
            val reading = surya.transcribe(writtenPageImage(), suryaSettings())
            surya.close()
            val modelVersion = requireNotNull(reading.modelVersion) { "the reading named no model" }
            assertContains(modelVersion, SuryaOcr.MODEL_CHECKPOINT)

            val archive = PageArchive(directory)
            try {
                val document = archive.document()
                val settings = attemptSettings(modelVersion)
                val fingerprint = ExtractionFingerprint.of(document.sha256, settings)
                val otherModel = ExtractionFingerprint.of(
                    document.sha256,
                    settings.copy(ocrAttempt = settings.ocrAttempt?.copy(modelVersion = "$modelVersion-v2")),
                )
                assertNotEquals(fingerprint, otherModel, "a model change does not change the fingerprint")

                archive.commit(document.id, fingerprint, PAGE_KEY, 0, reading.text)
                archive.reopen()

                assertContains(
                    archive.committedKeys(document.id, fingerprint),
                    PAGE_KEY,
                    "a page committed before the restart was not reused after it",
                )
                assertEquals(
                    emptySet(),
                    archive.committedKeys(document.id, otherModel),
                    "a reading of another model reused a page it did not read",
                )
            } finally {
                archive.close()
            }
        }

    // ---- A runtime that changed, through the pipeline that decides what may be reused -----------------

    @Test
    fun `a page committed under one runtime is read again when the probe reports another`() {
        // The whole path a real import takes, because a fingerprint compared in memory would prove nothing
        // about reuse: the import commits the page through the real durable store, the retry re-enters
        // extraction through the real handler, and the *only* thing that differs between the two attempts is
        // what the runtime identity probe answers. Nothing here supplies an identity itself.
        val harness = Harness(Files.createDirectories(directory.resolve("archive")))
        try {
            val source = writtenPicture(harness.sourcesDir.resolve("page.png"))
            val reads = directory.resolve("page-reads")
            val first = identityEngine("identity-a", IDENTITY_A, "Identity A reading", reads)
            val second = identityEngine("identity-b", IDENTITY_B, "Identity B reading", reads)
            try {
                val imported = harness.importDurably(
                    sources = listOf(source),
                    extractor = ImageExtractor(PageOcrEngines(listOf(first))),
                    settings = suryaAttemptSettings(),
                )
                val documentId = imported.items.single().documentId!!
                assertEquals(ImportItemOutcome.IMPORTED, imported.items.single().outcome)
                assertEquals(1, pageReads(reads), "the first attempt did not read the picture")
                assertContains(pageText(harness, documentId), "Identity A")

                // The same document, the same settings, and a runtime that says it is another one: the
                // checkpoint the first attempt committed is not this attempt's to reuse, so the page is read
                // again and the reading that lands is the new one.
                val reread = harness.retry(
                    documentIds = listOf(documentId),
                    extractor = ImageExtractor(PageOcrEngines(listOf(second))),
                    settings = suryaAttemptSettings(),
                )

                assertEquals(
                    DocumentStatus.COMPLETE,
                    reread.documents.getValue(documentId).status,
                    "the attempt that re-read the page did not finish, so nothing about reuse was observed",
                )
                assertEquals(
                    2,
                    pageReads(reads),
                    "a page committed under one runtime was reused by an attempt that reported another",
                )
                assertContains(
                    pageText(harness, documentId),
                    "Identity B",
                    message = "the attempt that reported another runtime kept the older attempt's reading",
                )

                // And the identity is not a one-way door: an attempt whose runtime is the first one again
                // finds its own checkpoint and pays nothing for the page it already read.
                val reused = harness.retry(
                    documentIds = listOf(documentId),
                    extractor = ImageExtractor(PageOcrEngines(listOf(first))),
                    settings = suryaAttemptSettings(),
                )

                assertEquals(
                    DocumentStatus.COMPLETE,
                    reused.documents.getValue(documentId).status,
                    "the attempt whose runtime matched did not finish, so the page may not have been reused",
                )
                assertEquals(2, pageReads(reads), "a page committed under this runtime was read again")
            } finally {
                first.close()
                second.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a missing surya runtime reaches the queue as a tool that has to be installed`() {
        // What a person actually sees: the item's code, the sentence beside it and the document's status.
        // Every code is produced the way production produces it — a real engine, a real page reader, the real
        // import and retry handlers and the real store — rather than by writing a status by hand.
        val harness = Harness(Files.createDirectories(directory.resolve("archive")))
        try {
            val interpreters = listOf(
                NEEDS_SURYA to SuryaOcr(
                    interpreter = directory.resolve("no-such-python"),
                    workerScript = writeFakeExecutable(directory, "surya_worker.py", "exit 0"),
                ),
                NEEDS_LLAMA_CPP to refusingEngine("refuses-llama"),
                NEEDS_SURYA_MODEL to refusingEngine("refuses-model"),
                SURYA_START_FAILED to refusingEngine("refuses-start"),
            )
            try {
                interpreters.forEachIndexed { index, (code, engine) ->
                    val source = writtenPicture(harness.sourcesDir.resolve("page-$index.png"), ink = index)
                    val run = harness.importDurably(
                        sources = listOf(source),
                        extractor = ImageExtractor(PageOcrEngines(listOf(engine))),
                        settings = suryaAttemptSettings(),
                    )

                    val item = run.items.single()
                    val document = run.documents.getValue(requireNotNull(item.documentId))
                    assertEquals(ImportItemOutcome.FAILED, item.outcome, "$code: the item was not failed")
                    assertEquals(code, item.errorCode, "$code: the item's code is what the person reads")
                    assertContains(
                        item.errorMessage.orEmpty(),
                        remedyFragment(code),
                        message = "$code: the message does not name what has to be installed",
                    )
                    assertEquals(
                        DocumentStatus.NEEDS_TOOL,
                        document.status,
                        "$code: a missing tool left the document looking broken rather than waiting",
                    )
                    assertEquals(code, document.errorCode)
                }
            } finally {
                interpreters.forEach { (_, engine) -> engine.close() }
            }
        } finally {
            harness.close()
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    /** The attempt an import or a rescan makes when a collection selects Surya. */
    private fun suryaAttemptSettings(): ExtractionSettings = ExtractionSettings(
        ocrLanguages = "eng",
        ocrMode = OcrImportMode.CHECK_AND_IMPROVE,
        ocrAttempt = OcrAttemptIdentity(
            engine = OcrEngine.SURYA,
            language = "eng",
            transcriptionPromptVersion = OCR_TRANSCRIPTION_PROMPT_VERSION,
            extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
            toolVersion = "surya-ocr 0.22.1",
        ),
    )

    /** The line a fake worker answers `--identity` with, in the shape the real worker writes it. */
    private fun identityBranch(identity: String): String =
        "if [ \"\$1\" = \"--identity\" ]; then\n" +
            "  printf '%s\\n' '{\"protocol\":$PROTOCOL,\"type\":\"identity\",\"status\":\"ok\"," +
            "\"identity\":\"$identity\"}'\n" +
            "  exit 0\n" +
            "fi\n"

    /**
     * One fake worker that answers both halves of the protocol, counting every page it is asked for.
     *
     * The count is the point: whether a committed page was reused is observable as whether the runtime was
     * asked to read it again, and the worker is the only thing that knows.
     */
    private fun identityEngine(name: String, identity: String, text: String, reads: Path): SuryaOcr {
        val script = writeFakeExecutable(
            directory,
            name,
            identityBranch(identity) + "printf 'page\\n' >> '$reads'\n" + mirrorIdentity() +
                pageResult(text = text),
        )
        return SuryaOcr(
            interpreter = Path.of("/bin/sh"),
            workerScript = script,
            timeout = Duration.ofSeconds(30),
        )
    }

    /** One fake worker whose runtime answers this page with a code rather than with a reading. */
    /**
     * A worker that answers every page with the refusal [name] stands for.
     *
     * It reads its request before it answers, as the real worker does. One that answered and exited without
     * reading would race the engine's write: when the write came second the pipe was already closed, the engine
     * saw a worker that had ended rather than the answer it had sent, and the code under test was lost.
     */
    private fun refusingEngine(name: String): SuryaOcr {
        val code = when (name) {
            "refuses-llama" -> NEEDS_LLAMA_CPP
            "refuses-model" -> NEEDS_SURYA_MODEL
            else -> SURYA_START_FAILED
        }
        val script = writeFakeExecutable(
            directory,
            name,
            identityBranch(IDENTITY_A) + "read -r request\n" +
                "printf '{\"protocol\":$PROTOCOL,\"type\":\"page\",\"status\":\"error\"," +
                "\"code\":\"$code\",\"message\":\"a stand-in runtime\"}\\n'\n",
        )
        return SuryaOcr(
            interpreter = Path.of("/bin/sh"),
            workerScript = script,
            timeout = Duration.ofSeconds(30),
        )
    }

    /** How many pages a worker was asked for: one line per request, written by the worker itself. */
    private fun pageReads(reads: Path): Int =
        if (Files.exists(reads)) Files.readAllLines(reads).count { line -> line.isNotBlank() } else 0

    /** The text a document's one page is committed with, read back through a fresh process. */
    private fun pageText(harness: Harness, documentId: DocumentId): String =
        AppContext.open(harness.dataDir).use { context ->
            context.content.listUnits(documentId, afterOrdinal = -1, limit = 10)
                .single { unit -> unit.locator.unitKind == UnitKind.IMAGE }
                .extractedText
        }

    /** A picture with ink on it, written where an import reads its sources from. */
    private fun writtenPicture(target: Path, ink: Int = 0): Path {
        val raster = BufferedImage(140, 90, BufferedImage.TYPE_INT_RGB)
        val graphics = raster.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, raster.width, raster.height)
            graphics.color = Color.BLACK
            graphics.fillRect(10, 10 + ink * 12, 60, 8)
        } finally {
            graphics.dispose()
        }
        Files.createDirectories(target.parent)
        ImageIO.write(raster, "png", target.toFile())
        return target
    }

    /** What has to be installed, in the words the message for one runtime code has to carry. */
    private fun remedyFragment(code: String): String = when (code) {
        NEEDS_SURYA, SURYA_START_FAILED -> "uv pip install surya-ocr==0.22.1"
        NEEDS_LLAMA_CPP -> "brew install llama.cpp"
        else -> SuryaOcr.MODEL_REPOSITORY
    }

    /** One fake worker, spawned through `/bin/sh` exactly as the configured interpreter would be. */
    private fun engineWith(
        name: String,
        body: String,
        firstLine: String? = null,
        timeout: Duration = Duration.ofSeconds(30),
        maxResultBytes: Int = SuryaOcr.MAX_RESULT_BYTES,
    ): SuryaOcr {
        val script = writeFakeExecutable(directory, name, firstLine.orEmpty() + body)
        return SuryaOcr(
            interpreter = Path.of("/bin/sh"),
            workerScript = script,
            timeout = timeout,
            maxResultBytes = maxResultBytes,
        )
    }

    /** The lines every fake worker runs to mirror the page identity back out of the request it is handed. */
    private fun mirrorIdentity(): String = """
        read -r line
        unit=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"unitId":"\([^"]*\)".*/\1/p')
        ordinal=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"ordinal":\([0-9]*\).*/\1/p')
        page=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"page":\([0-9]*\).*/\1/p')

    """.trimIndent()

    /** One page result line, written the way the worker writes it: `%s` is the request's own identity. */
    private fun pageResult(
        status: String = "read",
        text: String = READING,
        blocks: String = """{"text":"$A_BLOCK_TEXT","bbox":[1,2,3,4],"confidence":0.9,"label":"Text","readingOrder":0}""",
        ordinalExpression: String = "\$ordinal",
    ): String = "printf '{\"protocol\":$PROTOCOL,\"type\":\"page\",\"unitId\":\"%s\"," +
        "\"ordinal\":%s,\"page\":%s,\"status\":\"$status\",\"text\":\"$text\"," +
        "\"model\":\"$MODEL\",\"blocks\":[$blocks]}\\n' \"\$unit\" \"$ordinalExpression\" \"\$page\"\n"

    private fun suryaSettings(): OcrSettingsSnapshot = OcrSettingsSnapshot(
        engine = OcrEngine.SURYA,
        mode = OcrImportMode.CHECK_AND_IMPROVE,
        language = "eng",
        extractorVersion = EXTRACTOR_SCHEMA_VERSION,
        toolVersion = "surya-ocr 0.22.1",
        renderDpi = 300,
    )

    private fun attemptSettings(modelVersion: String): ExtractionSettings =
        ExtractionSettings(
            ocrLanguages = "eng",
            ocrTool = "surya-ocr 0.22.1",
            renderDpi = 300,
            ocrMode = OcrImportMode.CHECK_AND_IMPROVE,
            ocrAttempt = OcrAttemptIdentity(
                engine = OcrEngine.SURYA,
                language = "eng",
                transcriptionPromptVersion = OCR_TRANSCRIPTION_PROMPT_VERSION,
                extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
                toolVersion = "surya-ocr 0.22.1",
                modelVersion = modelVersion,
                renderDpi = 300,
            ),
        )

    /** A page image over white paper with a black bar on it, so a blankness claim about it can fail. */
    private fun writtenPageImage(): PageImage {
        val root = directory.resolve("pages")
        Files.createDirectories(root)
        val reference = "page-000001.png"
        val raster = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        val graphics = raster.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, raster.width, raster.height)
            graphics.color = Color.BLACK
            graphics.fillRect(10, 10, 40, 8)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(raster, "png", root.resolve(reference).toFile())
        return PageImage.ofFile(
            documentId = DocumentId("doc-surya"),
            unitId = PAGE_KEY,
            ordinal = 0,
            imageRoot = root,
            imageReference = reference,
            artifactRoot = directory.resolve("artifacts"),
            renderDpi = 300,
            rotationDegrees = 0,
        )
    }

    private fun recordedProcessIds(pidFile: Path): List<String> {
        if (!Files.exists(pidFile)) return emptyList()
        return Files.readString(pidFile).trim().split(" ").filter { pid -> pid.isNotBlank() }
    }

    /** Whether a process is still running, asked of the kernel rather than of this JVM. */
    private fun isRunning(pid: String): Boolean {
        val probe = ProcessBuilder("kill", "-0", pid).redirectErrorStream(true).start()
        probe.inputStream.use { stream -> stream.readBytes() }
        return probe.waitFor() == 0
    }

    private companion object {

        /** The protocol version these fakes speak, spelled out so a change to it is visible here. */
        const val PROTOCOL: Int = 1

        const val PAGE_KEY: String = "page:1"

        /** The two runtime identities the reuse test tells apart, in the shape the real worker reports. */
        const val IDENTITY_A: String =
            "surya-ocr 0.22.1 backend llamacpp model datalab-to/surya-ocr-2 surya-2.gguf:1 surya-2-mmproj.gguf:2 " +
                "llama-server version: 0.5.0 (build 11146, commit 7fe450e19)"
        const val IDENTITY_B: String =
            "surya-ocr 0.22.1 backend llamacpp model datalab-to/surya-ocr-2 surya-2.gguf:1 surya-2-mmproj.gguf:2 " +
                "llama-server version: 0.5.1 (build 12000, commit 0badc0de)"

        /** The codes a runtime failure is reported under, named as the engine names them. */
        const val NEEDS_SURYA: String = SuryaOcr.NEEDS_SURYA_CODE
        const val NEEDS_LLAMA_CPP: String = SuryaOcr.NEEDS_LLAMA_CPP_CODE
        const val NEEDS_SURYA_MODEL: String = SuryaOcr.NEEDS_SURYA_MODEL_CODE
        const val SURYA_START_FAILED: String = SuryaOcr.SURYA_START_FAILED_CODE

        /** The committed picture the wiring test reads, which the stand-in worker never opens. */
        const val PICTURE_FIXTURE: String = "typed-render.png"

        /** What the fake reading says, and the block it says it in. */
        const val READING: String = "Invoice 2026-0042"
        const val A_BLOCK_TEXT: String = "Invoice 2026-0042"

        /** The model identity a fake worker reports, in the shape the real one reports it. */
        const val MODEL: String =
            "surya-ocr 0.22.1 llamacpp datalab-to/surya-ocr-2 surya-2.gguf:1266400864 surya-2-mmproj.gguf:204986688"

        /** The shell and the helper it started: both are recorded, because both have to be gone. */
        const val WORKER_AND_HELPER: Int = 2

        /** How long a reading may take when a helper is holding one of the worker's pipes open. */
        const val PROMPT_RETURN_MILLIS: Long = 20_000
    }
}

/**
 * A Tesseract stand-in that can only fail.
 *
 * "Never fall back to Tesseract" is only worth asserting if the substitute would be *visible* when it was
 * consulted: this one answers a page by recording it and refusing, so a test that passes cannot have read
 * the page with the wrong engine.
 */
private class RecordingTesseract : PageOcrEngine {

    override val engine: OcrEngine = OcrEngine.TESSERACT

    val pages: MutableList<PageImage> = mutableListOf()

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        pages += page
        throw AssertionError("Tesseract was asked to read a page that selects another engine")
    }
}

/**
 * The smallest real archive that can hold a committed page.
 *
 * Reuse is a store's answer about committed output — which key satisfies which fingerprint, and which
 * artifact still verifies — so a test that compared values in memory would assert nothing about it. It
 * reopens its database on purpose: "after a restart" is the moment the answer stops being about anything
 * this process remembers.
 */
private class PageArchive(private val directory: Path) : AutoCloseable {

    private var database: Database = Database(directory.resolve("infoscry.db"))

    private var collections: CollectionStore = CollectionStore(database)

    private var documents: DocumentStore = DocumentStore(database)

    private var content: ContentStore = ContentStore(database)

    init {
        SchemaMigrator(database).migrate()
    }

    /** A document of this archive, whose pages a test can commit. */
    fun document(name: String = "scan.png"): Document {
        val collection = collections.create("Rescan")
        return documents.insert(
            Document(
                id = DocumentId("doc-$name"),
                collectionId = collection.id,
                sha256 = "c".repeat(64),
                mediaType = "image/png",
                originalFilename = name,
                sourcePath = "/tmp/$name",
                sizeBytes = 42,
                status = DocumentStatus.COMPLETE,
                createdAt = Instants.now(),
                updatedAt = Instants.now(),
            ),
        )
    }

    /** One page's text, committed the way an extraction commits it. */
    fun commit(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        key: String,
        ordinal: Int,
        text: String,
    ) {
        content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = fingerprint,
            key = key,
            ordinal = ordinal,
            draft = ContentUnitDraft(
                locator = SourceLocation.PdfPage(ordinal + 1),
                extractedText = text,
                searchText = text,
                method = ExtractionMethod.OCR,
            ),
            artifactRoot = directory.resolve("artifacts"),
        )
    }

    /** The pages of this fingerprint whose committed reading may be reused. */
    fun committedKeys(documentId: DocumentId, fingerprint: ExtractionFingerprint): Set<String> =
        content.reusableCheckpoints(documentId, fingerprint, directory.resolve("artifacts")).skipKeys

    /** Closes and reopens the database: what a test asks about afterwards it no longer remembers. */
    fun reopen() {
        database.close()
        database = Database(directory.resolve("infoscry.db"))
        collections = CollectionStore(database)
        documents = DocumentStore(database)
        content = ContentStore(database)
    }

    override fun close() {
        database.close()
    }
}
