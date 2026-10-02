package infoscry.ocr

import infoscry.extract.OcrUnavailableException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The Surya worker answered something that is not this page's reading, or stopped answering.
 *
 * The code is this project's own vocabulary rather than the worker's text: a worker's message may carry a
 * model path, a prompt fragment or a line of a page, and a message travels into logs and into the queue
 * where nothing redacts a field nobody named. What a caller gets is which condition stopped the reading;
 * what the worker printed on its own stderr stays the child's.
 */
class SuryaWorkerException(val code: String, message: String) : IOException(message) {

    companion object {

        /** The worker ended without answering for the page it was handed. */
        const val EXITED: String = "SURYA_WORKER_EXITED"

        /** The line was not this protocol: not JSON, or a result for another page, or a field this build refuses. */
        const val MALFORMED_OUTPUT: String = "SURYA_MALFORMED_OUTPUT"

        /** One page's result was larger than the bound this engine holds. */
        const val OUTPUT_TOO_LARGE: String = "SURYA_OUTPUT_TOO_LARGE"

        /** The page did not come back inside the time this engine gave it. */
        const val TIMEOUT: String = "SURYA_TIMEOUT"
    }
}

/**
 * Reads one page with the local Surya model, through a worker process this engine owns.
 *
 * Surya is a vision-language model served by `llama-server`, so a page is not one command with an answer on
 * stdout: it is a Python runtime that loads a model once and stays up. That is why this engine spawns
 * `scripts/ocr/surya_worker.py` instead of running a command per page — the runtime costs about eleven
 * seconds of imports and a model load before the first page and about three seconds per page after it — and
 * why the child's whole lifecycle is this engine's to bound:
 *
 * - **The child is a configured pair, never a command line.** [interpreter] and [workerScript] are paths,
 *   with no shell anywhere: a page's name cannot become an argument to something else, and nothing an
 *   attempt carries can pick a different program.
 * - **One page, one request line, one result line.** The protocol is versioned JSON, the engine writes the
 *   page's identity and the *managed image path* it validated, and it refuses a result that is not for the
 *   page it asked about. A reading is attributable because both ends name the same page.
 * - **Bounded in both directions.** A request line, a result line, the process's own time and what is read
 *   back from its stderr each have a bound, so a worker that hangs, dies, floods or misbehaves is this
 *   page's failure rather than this process's.
 * - **The process tree is stopped, not abandoned.** A timeout, a cancellation or a worker that answered
 *   something impossible kills the worker *and* the `llama-server` it started: a helper that outlived its
 *   attempt would keep reading pages nobody asked for while holding a model's memory.
 * - **There is no fallback.** A missing interpreter, a missing `llama-server` and missing weights are three
 *   different things to install, each reported as [OcrUnavailableException] with the code and the install
 *   line that names it. Tesseract is never consulted for a page whose settings select Surya, because that
 *   would attribute one engine's reading to another's settings.
 *
 * The engine answers for one page at a time: a page's exchange holds a lock, so the worker's pipes are
 * never at an unknown point, and the model is loaded once per engine rather than once per page. A reading
 * carries the model identity the worker reported — the runtime version, the backend and the cached weights'
 * revision and size — so the attempt that ran under it can be told from one that ran under other weights.
 * The identity an *attempt* is keyed by is asked for before a page is read, in the mode that starts nothing
 * ([runtimeIdentity]), because whether an earlier attempt's page may be reused is decided by the attempt's
 * fingerprint rather than by a reading's own report.
 *
 * **What this engine does not do:** it writes no artifact of its own. A reading's evidence is the page
 * image it names plus the boxes it carries, and Surya's own output for a page is the model's answer rather
 * than a file this project owns. Blankness is likewise not decided here: an empty reading is delivered as an
 * empty reading under [OcrPageResult.EMPTY_READING_CODE] rather than as a success, and only the caller that
 * holds the raster may ask whether the paper is blank.
 */
class SuryaOcr(
    interpreter: Path = DEFAULT_INTERPRETER,
    workerScript: Path = DEFAULT_WORKER_SCRIPT,
    private val timeout: Duration = PAGE_TIMEOUT,
    private val maxResultBytes: Int = MAX_RESULT_BYTES,
) : PageOcrEngine, AutoCloseable {

    /**
     * The Python interpreter that has `surya-ocr` installed: a venv, not something found on `PATH`.
     *
     * Resolved where it was configured rather than where the child runs. A worker is started in a private
     * temporary directory of its own, so a relative path would name a different file there — or none.
     */
    val interpreter: Path = interpreter.toAbsolutePath().normalize()

    /** The worker this engine spawns. Configured, never supplied by a caller of an extraction. */
    val workerScript: Path = workerScript.toAbsolutePath().normalize()

    init {
        require(!timeout.isZero && !timeout.isNegative) {
            "a Surya page needs a positive timeout, was $timeout"
        }
        require(maxResultBytes >= MIN_RESULT_BYTES) {
            "a Surya result line needs a bound of at least $MIN_RESULT_BYTES bytes, was $maxResultBytes"
        }
    }

    override val engine: OcrEngine = OcrEngine.SURYA

    /** One page's exchange at a time: the worker's two pipes are one conversation, not a pool. */
    private val exchanges: Mutex = Mutex()

    /**
     * Guards the publication of a worker against [close].
     *
     * `exchanges` alone is not enough: a call that passed a closed check while another call held the exchange
     * lock could still start a worker after [close] had returned, and nothing would own that worker or the
     * server behind it. A worker is therefore started and published only while holding this monitor, and
     * [close] takes the same monitor to detach whatever is published, so the two cannot interleave.
     */
    private val starting: Any = Any()

    @Volatile
    private var worker: Worker? = null

    @Volatile
    private var closed: Boolean = false

    /**
     * Reads one page, or reports which part of the local runtime has to be installed.
     *
     * The attempt's settings are accepted and deliberately not consulted: a local engine dispatches nowhere,
     * and this model reads whatever the page's pixels say regardless of the language Tesseract would take its
     * alphabet from. Which engine answers is decided by [PageOcrEngines] from the settings, and it refuses a
     * reading this engine attributes to anything else.
     */
    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        val request = WIRE.encodeToString(
            SuryaPageRequest(
                unitId = page.unitId,
                ordinal = page.ordinal,
                page = page.ordinal + 1,
                imagePath = page.imagePath.toString(),
            ),
        )
        return exchanges.withLock {
            // Closed is asked again here, under the monitor [close] uses to detach a worker: a call that
            // passed an earlier check while another call held the exchange lock must not start a worker after
            // close() returned, and a worker started just before it has to be the one it discards.
            val worker = synchronized(starting) {
                check(!closed) { "this Surya engine was closed, so it can no longer read a page" }
                workerForPage()
            }
            try {
                readingOf(page, worker.exchange(request, timeout))
            } catch (stopping: Throwable) {
                // Everything that gets here leaves the worker at a point this engine cannot trust: a
                // timeout, a cancellation, a dead worker, a line that is not the protocol, a result for
                // another page, or a runtime that has to be installed. Its pipes are abandoned and the
                // process tree behind them is stopped; the next page starts a worker of its own.
                try {
                    discard(worker)
                } catch (leaked: IllegalStateException) {
                    // Why this page stopped is what a caller has to act on, so a teardown that could not
                    // account for something it started is reported *beside* that reason rather than
                    // instead of it.
                    stopping.addSuppressed(leaked)
                }
                throw stopping
            }
        }
    }

    /** Stops the worker and everything it started. A closed engine reads no further page. */
    override fun close() {
        val current = synchronized(starting) {
            closed = true
            worker.also { worker = null }
        }
        // Discarded outside the monitor: a teardown waits for a process tree, and holding the monitor that
        // exists to serialize it would make a page that is starting wait for that wait.
        current?.let(::discard)
    }

    /**
     * What this machine's Surya runtime is, asked of the worker without reading a page.
     *
     * The reading's own identity is only discovered while a page is being read ([OcrPageResult.modelVersion]),
     * and by then the decision that identity exists for has already been made: whether a page an earlier
     * attempt committed may be reused is decided by the attempt's fingerprint, which is computed *before*
     * any page is read. So the runtime is asked here what it would read with — the interpreter's
     * `surya-ocr` version, the backend, the checkpoint and the cached weights' revision and size, and the
     * `llama-server` build — through the same worker script and the same interpreter, in a mode that starts
     * no model and no server.
     *
     * An identity that cannot be discovered is answered as **no identity** rather than as a failure: the
     * probe is an optimization's key, not a reading, and a runtime that is missing is reported by the page
     * that cannot be read (with [NEEDS_SURYA_CODE] and the install line). Null is a different value from
     * every discovered identity, so an attempt that could not discover one never compares equal to an
     * attempt that did — the safe direction, because it repeats a reading rather than reusing one that may
     * have been made by other weights.
     *
     * The cost is one interpreter start and its metadata imports: measured at about 1.4 seconds on the
     * development machine, against a cold page's 3.6 seconds, and it is paid once per attempt that selects
     * Surya rather than once per page. It is bounded in every direction: a whole-probe timeout, a bound on
     * the line the probe may answer, and the child is destroyed if it outlives the bound.
     */
    override suspend fun runtimeIdentity(): String? =
        runInterruptible(Dispatchers.IO) { probeIdentity() }

    /**
     * One identity probe: a worker asked what the runtime is, and stopped whatever it answered.
     *
     * The child is short-lived by construction — the identity mode reads no page and starts nothing that
     * could outlive it — so it is started, waited for inside its bound, read as a bounded line, and killed
     * if the bound passed. Its stderr is discarded rather than piped: nothing of the worker's own words is
     * this project's message, and a pipe nobody reads is a child that can block on its own diagnostics.
     */
    private fun probeIdentity(): String? {
        if (!Files.isRegularFile(workerScript)) return null
        val process = try {
            ProcessBuilder(interpreter.toString(), workerScript.toString(), IDENTITY_FLAG)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (missing: IOException) {
            // The interpreter is not there: this machine has no runtime to describe, which the page that is
            // read next reports as the install it needs.
            return null
        }
        try {
            if (!process.waitFor(IDENTITY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) return null
            return identityOf(readLine(process.inputStream, IDENTITY_MAX_BYTES))
        } catch (interrupted: InterruptedException) {
            // A cancelled attempt stops waiting here; the child is killed below whatever the caller asked.
            Thread.currentThread().interrupt()
            return null
        } catch (unreadable: IOException) {
            return null
        } finally {
            // Forcibly, and without waiting: this mode starts no helper of its own, so there is no tree to be
            // graceful about, and a probe may not outlive the attempt that asked it. An interrupt can land
            // inside the wait above, which is the other way a live child could be left behind.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * One bounded line as this runtime's identity, or nothing when the line is not one.
     *
     * Every field is checked before the identity becomes part of an attempt: the protocol and the type,
     * because a page's result is not an identity, and the status, because a runtime that answered with a
     * code has nothing to say about what it is. A value that is blank, or that carries a line break, is not
     * accepted either — an identity becomes a field of a line-delimited fingerprint, and a value that could
     * compose the fields around it would let two different runtimes share one digest.
     */
    private fun identityOf(line: Line?): String? {
        val text = (line as? Line.Text)?.value ?: return null
        val reply = try {
            IDENTITY_WIRE.decodeFromString<SuryaIdentityReply>(text)
        } catch (refused: SerializationException) {
            return null
        }
        if (reply.protocol != PROTOCOL_VERSION || reply.type != IDENTITY_TYPE || reply.status != IDENTITY_STATUS) {
            return null
        }
        return reply.identity?.takeIf { identity ->
            identity.isNotBlank() && identity.none { character -> character == '\n' || character == '\r' }
        }
    }

    private fun workerForPage(): Worker {
        val current = worker
        if (current != null && current.isUsable) return current
        if (current != null) discard(current)
        return startWorker().also { started -> worker = started }
    }

    /**
     * Starts one worker.
     *
     * The pair that cannot be started is the interpreter that is not there — an unconfigured machine, or one
     * whose venv was removed — and it is reported as the install this project's instructions describe
     * rather than as a page that failed to read.
     */
    private fun startWorker(): Worker {
        if (!Files.isRegularFile(workerScript)) {
            throw OcrUnavailableException(
                NEEDS_SURYA_CODE,
                "'${workerScript.fileName}' is not the worker script this build spawns, so no page can be " +
                    "read with Surya. ${installRemedy()}",
            )
        }
        val directory = Files.createTempDirectory(WORK_DIRECTORY_PREFIX)
        val process = try {
            ProcessBuilder(listOf(interpreter.toString(), workerScript.toString()))
                .directory(directory.toFile())
                .start()
        } catch (missing: IOException) {
            directory.toFile().deleteRecursively()
            throw OcrUnavailableException(
                NEEDS_SURYA_CODE,
                "'${interpreter.fileName}' could not be run, so no page can be read with Surya. " +
                    installRemedy(),
            )
        }
        return Worker(process, directory, maxResultBytes)
    }

    private fun discard(worker: Worker) {
        if (this.worker === worker) this.worker = null
        worker.close()
    }

    /**
     * One result line as a reading, or a refusal that says what the line was instead.
     *
     * Every field is checked before it becomes evidence: a result for another page is not this page's
     * reading however plausible its text, a box that is not a rectangle is not where a match is, a box that
     * lies outside the page's own pixels is not a place this page can show, and a confidence that is not a
     * fraction is not a confidence. The distinction between a refusal and a *missing runtime* is deliberate
     * — the second is a document-level answer that names what to install, and the first is this page's
     * failure.
     */
    private fun readingOf(page: PageImage, line: String): OcrPageResult {
        val reply = try {
            WIRE.decodeFromString<SuryaPageReply>(line)
        } catch (refused: SerializationException) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "the Surya worker answered with a line that is not this protocol's result " +
                    "(${refused::class.simpleName}), so no page was read from it",
            )
        }
        if (reply.protocol != PROTOCOL_VERSION) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "the Surya worker speaks protocol ${reply.protocol} and this build speaks $PROTOCOL_VERSION",
            )
        }
        if (reply.type != PAGE_TYPE) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "the Surya worker answered with a '${reply.type}' where this page's reading belongs",
            )
        }
        if (reply.status == STATUS_ERROR) {
            throw unavailable(reply.code.orEmpty())
        }
        if (reply.status != STATUS_READ && reply.status != STATUS_EMPTY) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "the Surya worker answered with an unknown status '${reply.status}'",
            )
        }
        if (reply.unitId != page.unitId || reply.ordinal != page.ordinal || reply.page != page.ordinal + 1) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "the Surya worker answered for another page ('${reply.unitId}' page ${reply.page}, ordinal " +
                    "${reply.ordinal}) than the one it was asked about ('${page.unitId}' page " +
                    "${page.ordinal + 1}, ordinal ${page.ordinal})",
            )
        }
        val boxes = reply.blocks.map { block -> wordBoxOf(page, block) }
        val confidences = boxes.mapNotNull { box -> box.confidence }
        // Whitespace is not a page's text: an empty reading is what the caller's blankness question is asked
        // about, and a reading of nothing but spaces must not look like a reading with text. It is not an
        // unqualified success either: this engine cannot tell blank paper from ink it missed, so an empty
        // reading carries OCR_EMPTY and the blankness question stays with the caller that holds the raster.
        val text = if (reply.text.isBlank()) "" else reply.text
        return OcrPageResult(
            text = text,
            engine = engine,
            imageSha256 = page.sha256,
            boxes = boxes,
            // Absent when the engine reported none, which is not a confidence of zero: a mean of nothing is
            // not evidence that the model was sure of nothing.
            meanConfidence = if (confidences.isEmpty()) null else confidences.average(),
            modelVersion = reply.model?.takeIf { version -> version.isNotBlank() },
            errorCode = if (text.isEmpty()) OcrPageResult.EMPTY_READING_CODE else null,
        )
    }

    /**
     * One block of the model's answer as the box the seam carries, or a refusal that names the field.
     *
     * The box has to lie inside the page the reading is about. A box outside those pixels is not a region of
     * this page: a comparison that highlighted it, or a reviewer sent to it, would be sent somewhere the
     * reading has no evidence for, and the whole reading's boxes are what a citation is drawn from. The
     * page's own dimensions are what the model was handed, so the check is against measured pixels rather
     * than against a bound this engine chose. A page image whose dimensions could not be measured has none
     * to check against, and is read as before rather than refused for being unmeasurable.
     */
    private fun wordBoxOf(page: PageImage, block: SuryaBlock): OcrWordBox {
        if (block.bbox.size != BBOX_FIELDS) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "a Surya block came back with ${block.bbox.size} box coordinates instead of $BBOX_FIELDS",
            )
        }
        val left = block.bbox[0]
        val top = block.bbox[1]
        val right = block.bbox[2]
        val bottom = block.bbox[3]
        if (right < left || bottom < top) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "a Surya block came back with a box no page can hold",
            )
        }
        if (left < 0 || top < 0 || (page.width != null && right > page.width) ||
            (page.height != null && bottom > page.height)
        ) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "a Surya block came back with a box ($left,$top)-($right,$bottom) outside the page it was " +
                    "read from (${page.width ?: "unmeasured"}x${page.height ?: "unmeasured"}), so it is not " +
                    "a region this reading has evidence for",
            )
        }
        if (block.text.isBlank()) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "a Surya block came back with no text, so it is not a box a match can be shown in",
            )
        }
        val confidence = block.confidence
        if (confidence != null && (!confidence.isFinite() || confidence !in 0.0..1.0)) {
            throw SuryaWorkerException(
                SuryaWorkerException.MALFORMED_OUTPUT,
                "a Surya block came back with a confidence that is not a fraction",
            )
        }
        return OcrWordBox(
            text = block.text,
            left = left,
            top = top,
            width = right - left,
            height = bottom - top,
            confidence = confidence,
        )
    }

    /**
     * What a worker's error code means for the document, in the words of what has to be installed.
     *
     * The three runtime failures are one document-level answer each rather than one page's failure: every
     * page of every document would fail the same way, and a per-page report would bury the fact that the
     * machine is missing a piece the operator can install once.
     */
    private fun unavailable(code: String): Throwable {
        val remedy = remedyFor(code)
        return if (remedy != null) OcrUnavailableException(code, remedy) else SuryaWorkerException(
            code.ifBlank { SuryaWorkerException.MALFORMED_OUTPUT },
            "the Surya worker reported '${code.ifBlank { "nothing" }}' for this page without a reading",
        )
    }

    /**
     * One worker process: the child, its two pipes, and the only conversation this engine has with it.
     *
     * The reader is its own coroutine and the stderr drain is its own daemon thread, because both pipes have
     * to be consumed while the process runs: a child whose stderr fills up and is never read blocks forever,
     * and one that holds stderr open after its last answer must not hold the *answer*. The stderr reader is
     * therefore not joined while a page is being read, which is exactly why a helper inheriting that pipe
     * cannot keep a reading waiting; stopping the worker joins it, bounded, once the tree is gone.
     */
    private class Worker(
        private val process: Process,
        private val directory: Path,
        private val maxResultBytes: Int,
    ) : AutoCloseable {

        private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val answers: Channel<Answer> = Channel(capacity = 1)

        @Volatile
        private var ended: Boolean = false

        /** The stderr reader, kept so that stopping the worker can prove it ended. */
        private val errorDrain: Thread =
            Thread({ drainErrors() }, ERROR_DRAIN_THREAD_NAME).apply { isDaemon = true }

        init {
            scope.launch { readAnswers() }
            errorDrain.start()
        }

        /** Whether this worker is still the process it was, with its conversation at a known point. */
        val isUsable: Boolean get() = !ended && process.isAlive

        /**
         * Hands the worker one page and waits for its one answer, inside the engine's bound.
         *
         * The wait is cancellable, so a cancelled attempt stops waiting at once and its cancellation kills
         * the process tree below instead of leaving a model loaded for a rescan that is over.
         */
        suspend fun exchange(request: String, timeout: Duration): String {
            handPage(request)
            val answer = try {
                withTimeout(timeout.toMillis()) { answers.receive() }
            } catch (timedOut: TimeoutCancellationException) {
                throw SuryaWorkerException(
                    SuryaWorkerException.TIMEOUT,
                    "the Surya worker did not answer within $timeout and it, and the inference server it " +
                        "started, were stopped",
                )
            }
            return when (answer) {
                is Answer.Page -> answer.line
                is Answer.TooLarge -> throw SuryaWorkerException(
                    SuryaWorkerException.OUTPUT_TOO_LARGE,
                    "the Surya worker produced a result line longer than ${answer.bound} bytes for one " +
                        "page, so it was refused rather than held as that page's evidence",
                )
                Answer.Ended -> throw SuryaWorkerException(
                    SuryaWorkerException.EXITED,
                    "the Surya worker ended (${exitDescription()}) without answering for this page",
                )
            }
        }

        /**
         * Writes one request line.
         *
         * The line is small and one is outstanding at a time, so it fits a pipe's buffer: nothing here waits
         * for the child to read, and a child that never reads is caught by the answer's bound rather than by
         * a write that blocks forever.
         */
        private fun handPage(request: String) {
            try {
                process.outputStream.write((request + "\n").toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            } catch (refused: IOException) {
                throw SuryaWorkerException(
                    SuryaWorkerException.EXITED,
                    "the Surya worker could not be handed this page (${refused::class.simpleName}), so it " +
                        "had already ended",
                )
            }
        }

        private suspend fun readAnswers() {
            val stream = process.inputStream
            while (true) {
                val line = try {
                    readLine(stream, maxResultBytes)
                } catch (unreadable: IOException) {
                    break
                } ?: break
                answers.send(
                    when (line) {
                        is Line.Text -> Answer.Page(line.value)
                        Line.TooLong -> Answer.TooLarge(maxResultBytes)
                    },
                )
            }
            ended = true
            answers.send(Answer.Ended)
        }

        /**
         * Reads the worker's stderr and keeps none of it.
         *
         * The pipe has to be read for the child's sake, not for ours: a worker that logs a model load and is
         * never read blocks on its own error stream. What it says is not this project's message — it names
         * paths and model files — so nothing of it is kept, and this runs off the reading path so that a
         * helper holding the pipe open cannot hold an answer. The thread is daemon and its handle is kept
         * with the tree's, so [close] can wait for it rather than leave it running.
         */
        private fun drainErrors() {
            val buffer = ByteArray(ERROR_DRAIN_BYTES)
            try {
                process.errorStream.use { errors ->
                    while (errors.read(buffer) >= 0) {
                        // Read and dropped: the bound on this pipe is that nothing is kept.
                    }
                }
            } catch (unreadable: IOException) {
                // The worker ended and its pipe went with it: nothing to drain.
            }
        }

        /**
         * Stops the worker and everything it started, and does not return before saying what is left running.
         *
         * The stderr reader goes with the tree, and the directory the worker ran in after both: what this
         * engine started is all of it, and a caller closing the engine is entitled to know that it ended.
         */
        override fun close() {
            scope.cancel()
            val leftRunning = stopProcessTree()
            val drainEnded = awaitErrorDrain()
            directory.toFile().deleteRecursively()
            check(leftRunning.isEmpty()) {
                "the Surya worker's process tree still had ${leftRunning.size} process(es) running after " +
                    "being killed, so this engine's teardown did not end what it started"
            }
            check(drainEnded) {
                "the Surya worker's stderr reader was still running ${KILL_GRACE.seconds} seconds " +
                    "after the worker's process tree was stopped"
            }
        }

        /**
         * Stops the worker and everything it started, and answers with whatever survived being killed.
         *
         * `llama-server` is a grandchild of this process, and it is stopped as well as the worker: a killed
         * worker whose server kept running would hold a gigabyte of weights and a port for a rescan that is
         * over. The graceful stop comes first so a worker between pages can end its model, and the whole
         * tree is killed when the grace period passes.
         *
         * Every handle seen is kept and waited for *by handle*, rather than waited for through the worker:
         * the Python process exits first on a SIGTERM — that is what its own cleanup does — and a wait that
         * ended there would return while the server behind it was still winding down. A descendant that has
         * been re-parented after its parent exited is not reachable through the worker at all, which is why
         * it is captured and kept before anything is signalled.
         */
        private fun stopProcessTree(): List<ProcessHandle> {
            val root = process.toHandle()
            val tree = mutableListOf(root)
            var forcibly = false
            while (true) {
                // Scanned *before* anything is signalled: a worker that exits takes its children out of
                // `descendants()` with it, and a helper already re-parented to the system is not findable
                // from here at all. What was seen is what gets stopped and waited for.
                tree += undiscovered(root, tree)
                // Deepest first, so the helper the worker started is signalled before the worker itself.
                tree.filter { handle -> handle.isAlive }.asReversed().forEach { handle ->
                    if (forcibly) handle.destroyForcibly() else handle.destroy()
                }
                // And once more after signalling: a worker still running in that instant may have started the
                // server in the meantime, and its handle is what the wait below needs.
                tree += undiscovered(root, tree)
                if (awaitTreeExit(tree)) return emptyList()
                if (forcibly) return tree.filter { handle -> handle.isAlive }
                forcibly = true
            }
        }

        /** The processes of [root]'s tree that [seen] does not already name, compared by pid. */
        private fun undiscovered(root: ProcessHandle, seen: List<ProcessHandle>): List<ProcessHandle> =
            root.descendants().toList().filter { child -> seen.none { handle -> handle.pid() == child.pid() } }

        /**
         * Waits, inside one grace period, for every process this engine has seen to end.
         *
         * The wait is about the whole tree rather than about the Python process, and the grace period is
         * shared rather than spent per process, because the tree is one thing to stop. Each handle is waited
         * for through the kernel's own exit notification, so the wait is neither a sleep nor a spin.
         */
        private fun awaitTreeExit(tree: List<ProcessHandle>): Boolean {
            val deadline = System.nanoTime() + KILL_GRACE.toNanos()
            for (handle in tree) {
                if (!handle.isAlive) continue
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                try {
                    handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
                } catch (unaskable: ExecutionException) {
                    // Whether it ended is asked below, twice, about every handle: an answer that is not a
                    // number is not the answer to that question.
                } catch (timedOut: TimeoutException) {
                    // Out of grace: the caller kills what is left and asks again.
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return tree.none { handle -> handle.isAlive }
        }

        /**
         * Waits, bounded, for the stderr reader to end, and says whether it did.
         *
         * The reader is not joined while a page is being read, because a helper holding that pipe open must
         * not be able to hold an answer. It is joined *after* the tree is stopped, because the pipe reaches
         * its end when the last process holding it is gone — so a reader still running here is one more
         * thing this engine started that its teardown has to answer for.
         */
        private fun awaitErrorDrain(): Boolean {
            try {
                errorDrain.join(KILL_GRACE.toMillis())
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return !errorDrain.isAlive
        }

        /** What the worker's own exit status was, for the one message that has to say why it is gone. */
        private fun exitDescription(): String = try {
            "exit code ${process.exitValue()}"
        } catch (running: IllegalThreadStateException) {
            "then still running"
        }
    }

    companion object {

        /**
         * The code a missing Surya runtime fails under, alongside the `NEEDS_TESSERACT` this build already has.
         *
         * It reaches the item's outcome, the queue and the CLI, so an operator sees the same word everywhere
         * and knows which thing to install.
         */
        const val NEEDS_SURYA_CODE: String = "NEEDS_SURYA"

        /**
         * The code a missing `llama-server` fails under.
         *
         * Separate from [NEEDS_SURYA_CODE] because the fix is different: the Python runtime is installed and
         * the inference server is not, and `brew install llama.cpp` is not the same instruction as setting up
         * the venv.
         */
        const val NEEDS_LLAMA_CPP_CODE: String = "NEEDS_LLAMA_CPP"

        /** The code missing or unfetchable Surya weights fail under. */
        const val NEEDS_SURYA_MODEL_CODE: String = "NEEDS_SURYA_MODEL"

        /** The code an inference server that will not come up fails under. */
        const val SURYA_START_FAILED_CODE: String = "SURYA_START_FAILED"

        /**
         * The codes a Surya runtime that cannot read pages fails a document under.
         *
         * The four are the one answer an operator has to act on — something has to be installed or fixed —
         * so the import path turns each of them into the same `NEEDS_TOOL` status as a missing Tesseract,
         * and [remedyFor] is the one place the words for them are written.
         */
        val UNAVAILABLE_CODES: Set<String> = setOf(
            NEEDS_SURYA_CODE,
            NEEDS_LLAMA_CPP_CODE,
            NEEDS_SURYA_MODEL_CODE,
            SURYA_START_FAILED_CODE,
        )

        /**
         * What a person can do about one of [UNAVAILABLE_CODES], in this project's own words.
         *
         * The sentence is defined here rather than twice, because it travels two ways: a page that cannot be
         * read fails with it, and the item, the queue and the CLI serve it as the remedy beside the code.
         * Every word is InfoScry's, which is what makes it safe to serve over the API — a worker's own
         * message may name a model path, and those never leave the child. A code that is not a Surya runtime
         * failure has no remedy here, and its caller keeps its own answer.
         */
        fun remedyFor(code: String): String? = when (code) {
            NEEDS_SURYA_CODE ->
                "the Surya runtime is not usable: ${installRemedy()}"
            NEEDS_LLAMA_CPP_CODE ->
                "Surya reads pages through llama.cpp and its 'llama-server' was not found: install it with " +
                    "'brew install llama.cpp', or name the binary with the LLAMA_CPP_BINARY environment " +
                    "variable."
            NEEDS_SURYA_MODEL_CODE ->
                "Surya's weights could not be obtained: the model '$MODEL_CHECKPOINT' and its projector are " +
                    "the '$MODEL_REPOSITORY' files (about $MODEL_SIZE), cached by the runtime on first use " +
                    "in the Hugging Face cache. Check that the cache is reachable, or fetch them with the " +
                    "'surya_ocr' command before reading a document."
            SURYA_START_FAILED_CODE ->
                "Surya's inference server did not start, so no page can be read with it: ${installRemedy()}"
            else -> null
        }

        /** The environment variable that names the interpreter, when the pinned default is not where it is. */
        const val INTERPRETER_ENV: String = "INFOSCRY_SURYA_PYTHON"

        /** The environment variable that names the worker script, for a build that is not run from its tree. */
        const val WORKER_SCRIPT_ENV: String = "INFOSCRY_SURYA_WORKER"

        /**
         * Where the instructions install the runtime's interpreter.
         *
         * It is pinned rather than searched for: a venv is not on `PATH`, and a build that guessed at one
         * could pick an environment with another Surya version in it. The environment variable above
         * overrides it, and [configured] refuses an interpreter that is not executable.
         */
        val DEFAULT_INTERPRETER: Path = Path.of(
            System.getProperty("user.home"),
            ".local",
            "share",
            "infoscry",
            "surya-venv",
            "bin",
            "python",
        )

        /** The worker this project ships, relative to the directory the application is started in. */
        val DEFAULT_WORKER_SCRIPT: Path = Path.of("scripts", "ocr", "surya_worker.py")

        /**
         * The version of the JSON this engine and its worker exchange.
         *
         * It travels in every request and every result, so a worker of another version is refused instead of
         * being read as though the fields it left out had said nothing.
         */
        const val PROTOCOL_VERSION: Int = 1

        /** How much of one page's result line is kept. A page's text is far below it. */
        const val MAX_RESULT_BYTES: Int = 4 * 1024 * 1024

        /** A result bound below this is smaller than the page a reading is about. */
        const val MIN_RESULT_BYTES: Int = 64 * 1024

        /** The model this build's instructions pin, and what fetching it costs. */
        const val MODEL_CHECKPOINT: String = "datalab-to/surya-ocr-2"
        const val MODEL_REPOSITORY: String = "datalab-to/surya-ocr-2-gguf"
        const val MODEL_SIZE: String = "1.36 GiB"

        /**
         * One page is given this long before its worker is stopped.
         *
         * It is much longer than a page takes — a measured page is about three seconds warm, and the worker's
         * first page adds the runtime's imports and the model load — because the bound exists to stop a
         * worker that has hung, not to time a slow machine's first page out of its own model.
         */
        val PAGE_TIMEOUT: Duration = Duration.ofMinutes(20)

        /**
         * The runtime this machine has, when it has one.
         *
         * Configured means both halves are there: an interpreter that can be executed and the worker script
         * this build spawns. Anything less is not a runtime that can read a page, and a build that reported
         * it as present would fail on the first page instead of at selection. What is *not* checked here is
         * `llama-server` and the weights: those are the worker's own startup, and they are reported when a
         * page is read rather than by a probe on every selection.
         */
        fun configured(environment: (String) -> String? = { name -> System.getenv(name) }): SuryaOcr? {
            val interpreter = environment(INTERPRETER_ENV)?.takeIf { value -> value.isNotBlank() }
                ?.let(Path::of)
                ?: DEFAULT_INTERPRETER
            if (!Files.isExecutable(interpreter)) return null
            val script = environment(WORKER_SCRIPT_ENV)?.takeIf { value -> value.isNotBlank() }
                ?.let(Path::of)
                ?: DEFAULT_WORKER_SCRIPT
            if (!Files.isRegularFile(script)) return null
            return SuryaOcr(interpreter = interpreter, workerScript = script)
        }

        /**
         * What to do about a runtime that is not there, in the instructions' own words.
         *
         * The commands are the pinned ones from `docs/installation.md`: the environment is created with the
         * Python version the package has wheels for, the package is pinned, and both are named because a
         * person who sees `NEEDS_SURYA` has to be able to run the fix.
         */
        fun installRemedy(): String = "create it with 'uv venv --python 3.12' and " +
            "'uv pip install surya-ocr==0.22.1' in that environment, then name its python with " +
            "$INTERPRETER_ENV (and the worker with $WORKER_SCRIPT_ENV when this build is not run from its " +
            "own tree)"

        /** The type of the request and result this engine and its worker exchange. */
        private const val PAGE_TYPE: String = "page"

        /**
         * The type of the answer to `--identity`, and the flag that asks for it.
         *
         * The probe shares the protocol with a page's result so that one reader, one bound and one set of
         * refusals cover both, and it is a type of its own so that a result for a page is never read as the
         * runtime's identity.
         */
        private const val IDENTITY_TYPE: String = "identity"
        private const val IDENTITY_FLAG: String = "--identity"
        private const val IDENTITY_STATUS: String = "ok"

        /**
         * How long the identity probe's child is given before it is killed.
         *
         * The probe is one interpreter start and its metadata imports — about 1.4 seconds measured — and the
         * bound is what keeps a hung interpreter (a cold cache, a network-mounted Python, a wedged loader)
         * from holding a document's fingerprint for a page's whole timeout. Ten seconds is several times the
         * measured cost and far below the twenty minutes a page gets.
         */
        private val IDENTITY_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** How much of one identity line is kept. The identity the worker writes is a few hundred bytes. */
        private const val IDENTITY_MAX_BYTES: Int = 4096
        private const val STATUS_READ: String = "read"
        private const val STATUS_EMPTY: String = "empty"
        private const val STATUS_ERROR: String = "error"

        /** The four coordinates of a block's box, in the page's own pixels. */
        private const val BBOX_FIELDS: Int = 4

        /** The prefix of the private directory a worker runs in. */
        private const val WORK_DIRECTORY_PREFIX: String = "infoscry-surya-"

        /** How long a stopped worker has to end before it is killed. */
        private val KILL_GRACE: Duration = Duration.ofSeconds(5)

        /** How much of the child's error stream is read at a time, and dropped. */
        private const val ERROR_DRAIN_BYTES: Int = 64 * 1024
        private const val ERROR_DRAIN_THREAD_NAME: String = "infoscry-surya-stderr"

        /**
         * The protocol as it is written on the wire.
         *
         * Defaults are written rather than left out: the protocol version and the request's type are what a
         * worker checks before it reads a page, and a field that is only absent by default would be a field
         * the other end has to guess.
         */
        private val WIRE: Json = Json { encodeDefaults = true }

        /** The identity probe's line, which carries no page and so needs no page's fields. */
        private val IDENTITY_WIRE: Json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * One identity probe's answer: what the runtime an attempt would read with is, or why it could not be read.
 *
 * [identity] is absent for anything but [OcrEngine]'s own `ok` status — a runtime that answered with a code
 * has nothing to say about what it is — and the engine answers such a probe with no identity at all rather
 * than with the code, because the reading is what reports a runtime that has to be installed.
 */
@Serializable
private class SuryaIdentityReply(
    val protocol: Int,
    val type: String,
    val status: String,
    val identity: String? = null,
    val code: String? = null,
)

/** One page, as the worker is told about it: which page, and which image of the managed area it is. */
@Serializable
private class SuryaPageRequest(
    val protocol: Int = SuryaOcr.PROTOCOL_VERSION,
    val type: String = "page",
    val unitId: String,
    val ordinal: Int,
    val page: Int,
    val imagePath: String,
)

/**
 * One page's answer.
 *
 * [unitId], [ordinal] and [page] are absent for a result that carries no reading — a runtime the worker
 * could not start is not about this page — and are required to be this page's when it does. [code] and
 * [message] are the worker's own failure vocabulary, which the engine translates into this project's codes
 * rather than repeating: a worker's prose can name a model path or a line of a page.
 */
@Serializable
private class SuryaPageReply(
    val protocol: Int,
    val type: String,
    val status: String,
    val unitId: String? = null,
    val ordinal: Int? = null,
    val page: Int? = null,
    val text: String = "",
    val blocks: List<SuryaBlock> = emptyList(),
    val code: String? = null,
    val message: String? = null,
    val model: String? = null,
)

/**
 * One block of a reading: the model's text for a region of the page, where the region is, and how sure it was.
 *
 * Surya reports a page's reading as blocks rather than as words, with one box per block, and one
 * confidence for the page's single full-page call: the confidence here is that call's mean token
 * probability, which is the same for every block of one page. A block with no text is not sent at all,
 * so a box in a reading is always a region a match can be shown in.
 */
@Serializable
private class SuryaBlock(
    val text: String,
    val bbox: List<Int>,
    val confidence: Double? = null,
    val label: String? = null,
    val readingOrder: Int = 0,
)

/** What one line read from the worker is: this protocol's line, or one too long to be held. */
private sealed interface Line {

    class Text(val value: String) : Line

    object TooLong : Line
}

/** What one worker produced: a result line, a line past the bound, or nothing more at all. */
private sealed interface Answer {

    class Page(val line: String) : Answer

    class TooLarge(val bound: Int) : Answer

    object Ended : Answer
}

/**
 * Reads one line from a child's stdout, keeping at most [bound] bytes of it.
 *
 * A line is a child's promise, and a child that writes without ever ending a line could decide how much
 * memory this process holds — which is why the bound exists and why the *rest* of an over-long line is
 * read and dropped rather than refused: a full pipe would block the child, and a child blocked on its own
 * output cannot be stopped by anything but a kill. The caller is told the line was too long, so a
 * runaway result is that page's failure rather than a page's text.
 */
private fun readLine(stream: InputStream, bound: Int): Line? {
    val kept = ByteArrayOutputStream(minOf(bound, READ_BUFFER))
    val buffer = ByteArray(READ_BUFFER)
    var over = false
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) {
            return when {
                over -> Line.TooLong
                kept.size() == 0 -> null
                else -> Line.Text(kept.text())
            }
        }
        var newline = -1
        var index = 0
        while (index < read) {
            if (buffer[index] == NEWLINE_BYTE) {
                newline = index
                break
            }
            index++
        }
        // Everything up to the newline is this line; without one, the whole chunk is.
        val content = if (newline >= 0) newline else read
        val room = bound - kept.size()
        when {
            room <= 0 -> over = true
            content <= room -> kept.write(buffer, 0, content)
            else -> {
                kept.write(buffer, 0, room)
                over = true
            }
        }
        if (newline >= 0) return if (over) Line.TooLong else Line.Text(kept.text())
    }
}

/** The kept bytes of a line as text: a result line is UTF-8, as the protocol writes it. */
private fun ByteArrayOutputStream.text(): String = toString(Charsets.UTF_8.name()).trimEnd('\r')

private const val READ_BUFFER: Int = 8 * 1024
private val NEWLINE_BYTE: Byte = '\n'.code.toByte()
