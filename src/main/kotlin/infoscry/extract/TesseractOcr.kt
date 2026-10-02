package infoscry.extract

import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.OcrWordBox
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Tesseract ran, and did not produce a reading this page can be cited from.
 *
 * The tool's own output is deliberately **not** in the message. A tool prints paths, file names, and
 * sometimes fragments of the page it was reading, and a message travels into logs and into the queue where
 * the redactor cannot see it — the redactor works on named fields. What a reader needs from a failure is
 * that the tool failed and with what status; what it printed is diagnostic detail, and it is offered as
 * [stderrExcerpt] for a caller that knows to treat it as a field rather than as prose.
 */
class OcrToolFailedException(
    val exitCode: Int,
    val stderrExcerpt: String,
    message: String,
) : IOException(message) {

    companion object {

        /** The tool ran and reported failure. */
        fun exited(exitCode: Int, stderr: String): OcrToolFailedException = OcrToolFailedException(
            exitCode = exitCode,
            stderrExcerpt = stderr.trim().take(MAX_STDERR_EXCERPT_CHARACTERS),
            message = "tesseract exited with status $exitCode without a reading",
        )

        /** The tool produced more than this process is willing to hold, so the reading is incomplete. */
        fun truncated(exitCode: Int, stderr: String, boundBytes: Int): OcrToolFailedException =
            OcrToolFailedException(
                exitCode = exitCode,
                stderrExcerpt = stderr.trim().take(MAX_STDERR_EXCERPT_CHARACTERS),
                message = "tesseract produced more than $boundBytes bytes of output, so the reading " +
                    "was truncated and is not this page's evidence",
            )

        private const val MAX_STDERR_EXCERPT_CHARACTERS: Int = 512
    }
}

/**
 * Reads one page with Tesseract, and writes down what it read.
 *
 * Tesseract is called with the page's image, the output mode, the languages the collection asked for, and
 * the TSV configuration, and its answer is parsed into the two things a unit needs: the page's text as
 * lines, and how sure the tool was.
 *
 * Three properties of the tool shape this class:
 *
 * - **The language order decides the character set.** Tesseract takes the alphabet from the first language
 *   it is given, so `eng+swe` reads a Swedish `ä` as `a` where `swe+eng` does not. The codes are therefore
 *   passed exactly as the collection lists them, never sorted: the order is part of the job's settings and
 *   part of its fingerprint, and reordering them would quietly change what the page says.
 * - **Its output is the evidence, not a summary of it.** The TSV holds every word with its box and its
 *   confidence, and that is what a reader needs to see a match on the page. It is written, gzipped, under
 *   the document's artifact root, atomically, before the unit that describes it is delivered.
 * - **It can be slow, and it can be absent.** The call is bounded by a timeout and runs in a private
 *   directory of its own. A tool that is not installed is not a broken document: it is reported as
 *   [OcrUnavailableException] with the code that says what has to be installed, so the pipeline can fail
 *   the document once instead of failing every page of it.
 *
 * The artifact lives under the *fingerprint* directory, so two extractions of the same document under
 * different settings cannot overwrite each other's evidence. Nothing in this class deletes an artifact:
 * what an attempt did not finish is replaced by the next attempt at the same path, and what a committed
 * page owns is under a name that this attempt never writes.
 */
class TesseractOcr(
    private val executable: String = DEFAULT_EXECUTABLE,
    private val timeout: Duration = PAGE_TIMEOUT,
    private val maxCapturedStdoutBytes: Int = ExternalProcess.MAX_CAPTURED_STDOUT_BYTES,
) : PageOcrEngine {

    /**
     * Tesseract is the installed tool that reads a rendered page, and it says so itself.
     *
     * An attempt that selects another engine is refused by [PageOcrEngines] rather than read here, which is
     * what makes "which engine read this page" an answer a snapshot decided instead of a wiring accident.
     */
    override val engine: OcrEngine = OcrEngine.TESSERACT

    /**
     * Reads one page image and returns its text, its word boxes, its mean confidence, and the artifact the
     * reading was written to.
     *
     * This is the page-image seam: the languages come from the snapshot the attempt was admitted with, the
     * tool's own output is written beside the attempt's other artifacts, and nothing here can dispatch
     * anywhere but to the tool this instance was configured with. A tool that is missing is a document-level
     * refusal with [NEEDS_TESSERACT_CODE], and a page this tool could not read is that page's failure —
     * either way no other engine is tried. A page the tool read as nothing is not a success either: it comes
     * back under [OcrPageResult.EMPTY_READING_CODE], so that whether the page is blank is answered by the
     * caller that holds the raster rather than by an empty string.
     */
    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        val workingDirectory = Files.createTempDirectory(WORK_DIRECTORY_PREFIX)
        try {
            val run = run(page.imagePath, settings.language, workingDirectory)
            val relativePath = boxesArtifactPath(page.ordinal)
            val artifact = page.artifactRoot.resolve(relativePath)
            writeArtifact(artifact, run.tsv)
            return OcrPageResult(
                text = run.reading.text,
                engine = engine,
                imageSha256 = page.sha256,
                boxes = run.reading.boxes,
                meanConfidence = run.reading.meanConfidence,
                artifactRelativePath = relativePath,
                artifactSha256 = sha256Of(artifact),
                // A tool that recognised no word is not a page this pipeline established says nothing: the
                // paper may be blank or the tool may have missed what is on it, and only the raster can
                // tell the two apart. The reading is therefore this code rather than an unqualified
                // success, exactly as the Surya engine answers an empty page.
                errorCode = if (run.reading.text.isBlank()) OcrPageResult.EMPTY_READING_CODE else null,
            )
        } finally {
            workingDirectory.toFile().deleteRecursively()
        }
    }

    /**
     * Reads one page of a container whose pictures are working material rather than managed page images.
     *
     * A chapter of a book archive is drawn into an image for the length of one extraction and deleted
     * afterwards, so it has no artifact root of its own and no stable page identity of its own, which is why
     * it keeps its own entry point instead of being described as a page image it is not. The reading itself
     * is the same reading: one command, one parser, one artifact writer, and the same refusal when the tool
     * is missing.
     *
     * Called by an extractor from inside its unit boundary, which is what puts the artifact write inside the
     * same permit as the checkpoint that will describe it.
     */
    suspend fun recognize(page: RenderedPage): OcrResult {
        val workingDirectory = Files.createTempDirectory(WORK_DIRECTORY_PREFIX)
        try {
            val run = run(page.imagePath, page.ocrLanguages, workingDirectory)
            val relativePath = relativeArtifactPath(page)
            val artifact = page.artifactRoot.resolve(relativePath)
            writeArtifact(artifact, run.tsv)
            return OcrResult(
                text = run.reading.text,
                meanConfidence = run.reading.meanConfidence,
                artifactRelativePath = relativePath,
                artifactSha256 = sha256Of(artifact),
            )
        } finally {
            workingDirectory.toFile().deleteRecursively()
        }
    }

    /**
     * Runs the tool on one image and parses what it printed.
     *
     * The two entry points differ only in where the reading is written down, so everything that is about the
     * *tool* — the command line, its language order, its output bound, the two ways it fails, and the parse —
     * happens once here.
     */
    private suspend fun run(image: Path, languages: String, workingDirectory: Path): TesseractRun {
        val outcome = try {
            ExternalProcess.run(
                command = listOf(image.toString(), STDOUT_OUTPUT, LANGUAGE_FLAG, languages, TSV_OUTPUT).let {
                    listOf(executable) + it
                },
                timeout = timeout,
                cwd = workingDirectory,
                maxCapturedStdoutBytes = maxCapturedStdoutBytes,
            )
        } catch (notInstalled: ExternalToolMissingException) {
            // Not a page failure but a build failure: every page of every scan would fail the same way, so it
            // is reported once against the document with the code that names what is missing.
            throw OcrUnavailableException(
                NEEDS_TESSERACT_CODE,
                "'$executable' is not installed or cannot be run, so a page without its own text cannot be " +
                    "read: ${installRemedy()}",
            )
        }
        if (outcome.stdoutTruncated) {
            throw OcrToolFailedException.truncated(
                exitCode = outcome.exitCode,
                stderr = outcome.stderr,
                boundBytes = maxCapturedStdoutBytes,
            )
        }
        if (!outcome.succeeded) {
            throw OcrToolFailedException.exited(outcome.exitCode, outcome.stderr)
        }
        return TesseractRun(tsv = outcome.stdout, reading = TesseractTsv.parse(outcome.stdout))
    }

    /**
     * Writes the reading under a temporary name and moves it into place.
     *
     * A half-written gzip stream is not evidence, and the unit about to describe this artifact claims it is
     * complete: the move is what makes the file a commit rather than a hope. The temporary name is fixed
     * rather than random so that an attempt killed mid-write leaves exactly one file the next attempt
     * overwrites and removes, instead of one more candidate per interruption.
     */
    private fun writeArtifact(artifact: Path, tsv: String) {
        Files.createDirectories(artifact.parent)
        val partial = artifact.resolveSibling("${artifact.fileName}.part")
        try {
            Files.newOutputStream(partial).use { file ->
                GZIPOutputStream(file).use { gzip -> gzip.write(tsv.toByteArray(Charsets.UTF_8)) }
            }
            moveIntoPlace(partial, artifact)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun moveIntoPlace(partial: Path, artifact: Path) {
        try {
            Files.move(partial, artifact, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
            // A filesystem that cannot replace atomically still must not publish a partial file, and a
            // replace that is not atomic is the best it can do.
            Files.move(partial, artifact, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256Of(path: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    companion object {

        /** The tool's name as it is found on the PATH. */
        const val DEFAULT_EXECUTABLE: String = "tesseract"

        /**
         * The code a missing translation tool fails under.
         *
         * It is the word the spec uses, and it reaches the item's outcome, the queue, and the CLI, so the
         * operator sees the same word everywhere and knows which thing to install.
         */
        const val NEEDS_TESSERACT_CODE: String = "NEEDS_TESSERACT"

        /** One page is given this long before the tool is stopped. */
        val PAGE_TIMEOUT: Duration = Duration.ofMinutes(10)

        /** Where the OCR artifacts live under the fingerprint directory of a document's artifact root. */
        internal const val ARTIFACT_DIRECTORY: String = "ocr"

        /** A page's artifact is named after the page, so a reader can find it without a lookup. */
        internal const val ARTIFACT_NAME_FORMAT: String = "page-%06d.tsv.gz"

        /** The prefix of the private directory a reading runs in. */
        internal const val WORK_DIRECTORY_PREFIX: String = "infoscry-tesseract-"

        /** How Tesseract is asked for its output on stdout. */
        private const val STDOUT_OUTPUT: String = "stdout"

        /** The flag that names the languages to read with. */
        private const val LANGUAGE_FLAG: String = "-l"

        /** The output configuration: the boxed words, which is what a citation needs to show. */
        private const val TSV_OUTPUT: String = "tsv"

        /** The path of one page's artifact, relative to the document's artifact root. */
        internal fun relativeArtifactPath(page: RenderedPage): String = listOf(
            page.fingerprint.value,
            ARTIFACT_DIRECTORY,
            String.format(Locale.ROOT, ARTIFACT_NAME_FORMAT, page.page),
        ).joinToString("/")

        /**
         * The path of one page image's word boxes, relative to the attempt's artifact root.
         *
         * A page image is read under the attempt directory the extractor built from its fingerprint, so the
         * path here is inside that directory; the page number is the page's ordinal plus one, which is the
         * name every committed artifact of this tool has always had. Keeping the name is what keeps a
         * citation's word boxes findable: the rows of documents read before this seam existed name the same
         * files.
         */
        internal fun boxesArtifactPath(ordinal: Int): String = listOf(
            ARTIFACT_DIRECTORY,
            String.format(Locale.ROOT, ARTIFACT_NAME_FORMAT, ordinal + 1),
        ).joinToString("/")

        /**
         * What to do about a missing tool, in the words of the platform's own package manager.
         *
         * The message a user sees names the command that fixes it, because "Tesseract is missing" is not
         * something a person can act on and the install line is.
         */
        fun installRemedy(): String {
            val platform = System.getProperty("os.name").lowercase(Locale.ROOT)
            return when {
                platform.contains("mac") -> "install it with 'brew install tesseract tesseract-lang'"
                platform.contains("linux") ->
                    "install it with your package manager, for example " +
                        "'apt-get install tesseract-ocr tesseract-ocr-swe'"
                else -> "install tesseract and the language packs the collection reads with"
            }
        }
    }
}

/**
 * What one run of the tool produced: the TSV it printed, and the reading parsed from it.
 *
 * Both travel together because the artifact is the tool's own output and the reading is this project's
 * interpretation of it: what a citation opens is the first, what a page says is the second, and neither is
 * derived from the other a second time.
 */
private class TesseractRun(val tsv: String, val reading: TesseractReading)

/**
 * What one reading of a page says: the lines, the words with their boxes, and how sure the tool was.
 *
 * [text] is the page as lines of words, in the order the tool read them. [meanConfidence] is the mean of
 * the word confidences as a fraction of one, and is absent when the tool recognised no words at all —
 * which is a different thing from being unsure about them.
 */
internal data class TesseractReading(
    val text: String,
    val meanConfidence: Double?,
    val boxes: List<OcrWordBox> = emptyList(),
)

/**
 * Turns Tesseract's TSV into lines of text and one confidence.
 *
 * The TSV is a flat table of boxes at four levels — page, block, paragraph, line, word — and it is the
 * tool's own reading order: rows arrive grouped by block, then paragraph, then line, which is what makes
 * "the order they arrive in" the same thing as "the order a person reads them in". The rows are sorted by
 * that grouping anyway, so a shuffled stream still produces the page's lines rather than a tangle of them.
 *
 * Only word rows are text: a page, block, paragraph, or line row describes the words inside it and has no
 * words of its own. Their confidence column is `-1`, meaning "not applicable", which is why confidences
 * are collected only from word rows with a real value — an average that included the structural rows would
 * be a number about nothing.
 */
internal object TesseractTsv {

    /** The level a row carrying a word has. */
    private const val WORD_LEVEL: Int = 5

    /** The column count every row has, header included. */
    private const val COLUMN_COUNT: Int = 12

    fun parse(tsv: String): TesseractReading {
        val lines = linkedMapOf<LineKey, MutableList<String>>()
        val confidences = mutableListOf<Double>()
        val boxes = mutableListOf<OcrWordBox>()
        for (row in tsv.lineSequence()) {
            val fields = row.split('\t')
            if (fields.size < COLUMN_COUNT) continue
            if (fields[LEVEL_COLUMN].trim().toIntOrNull() != WORD_LEVEL) continue
            val text = fields[TEXT_COLUMN].trim()
            if (text.isEmpty()) continue
            val key = LineKey(
                page = fields[PAGE_COLUMN].trim().toIntOrNull() ?: 0,
                block = fields[BLOCK_COLUMN].trim().toIntOrNull() ?: 0,
                paragraph = fields[PARAGRAPH_COLUMN].trim().toIntOrNull() ?: 0,
                line = fields[LINE_COLUMN].trim().toIntOrNull() ?: 0,
            )
            lines.getOrPut(key) { mutableListOf() }.add(text)
            val confidence = fields[CONFIDENCE_COLUMN].trim().toDoubleOrNull()?.takeIf { value -> value >= 0 }
            confidence?.let { value -> confidences.add(value) }
            boxes += OcrWordBox(
                text = text,
                left = fields[LEFT_COLUMN].trim().toIntOrNull() ?: 0,
                top = fields[TOP_COLUMN].trim().toIntOrNull() ?: 0,
                width = fields[WIDTH_COLUMN].trim().toIntOrNull() ?: 0,
                height = fields[HEIGHT_COLUMN].trim().toIntOrNull() ?: 0,
                // The same fraction the mean is in, so one confidence can be compared with the other.
                confidence = confidence?.div(PERCENT),
            )
        }
        val ordered = lines.entries.sortedWith(
            compareBy({ it.key.page }, { it.key.block }, { it.key.paragraph }, { it.key.line }),
        )
        return TesseractReading(
            text = ordered.joinToString("\n") { (_, words) -> words.joinToString(" ") },
            meanConfidence = if (confidences.isEmpty()) null else confidences.average() / PERCENT,
            boxes = boxes,
        )
    }

    /** Where a word belongs: the page, block, paragraph, and line coordinates Tesseract gave it. */
    private data class LineKey(val page: Int, val block: Int, val paragraph: Int, val line: Int)

    private const val LEVEL_COLUMN: Int = 0
    private const val PAGE_COLUMN: Int = 1
    private const val BLOCK_COLUMN: Int = 2
    private const val PARAGRAPH_COLUMN: Int = 3
    private const val LINE_COLUMN: Int = 4
    private const val LEFT_COLUMN: Int = 6
    private const val TOP_COLUMN: Int = 7
    private const val WIDTH_COLUMN: Int = 8
    private const val HEIGHT_COLUMN: Int = 9
    private const val CONFIDENCE_COLUMN: Int = 10
    private const val TEXT_COLUMN: Int = 11

    /** Tesseract reports confidence out of a hundred; a fraction is what a reader can compare. */
    private const val PERCENT: Double = 100.0
}
