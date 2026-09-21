package infoscry.extract

import infoscry.domain.DocumentId
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Reading a page's text and word boxes with Tesseract, and what happens when the tool is not there.
 *
 * The tool is a script this test writes, so what is asserted is this project's half of the contract: the
 * exact command line, that the language codes travel in the order the collection listed them, how the TSV
 * becomes lines of text and one confidence number, where the artifact goes, and that a page whose artifact
 * an earlier attempt committed is never rewritten or deleted.
 *
 * The language order is not cosmetic. Tesseract takes the character set from the first language it is
 * given, so `eng+swe` genuinely reads Swedish `ä` as `a` where `swe+eng` does not; a tool that sorted the
 * codes would silently lose the letters the collection asked for, which is why the order is pinned here.
 */
class TesseractOcrTest {

    private lateinit var directory: Path
    private lateinit var artifactRoot: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-tesseract-test")
        artifactRoot = directory.resolve("artifacts")
        Files.createDirectories(artifactRoot)
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    // ---- The command line ---------------------------------------------------------------------------

    @Test
    fun `it reads the image to stdout as tsv in the collection's languages`() = runBlocking {
        val argumentsFile = directory.resolve("arguments.txt")
        val tool = tesseractEmitting(TSV_TWO_LINES, argumentsFile)

        TesseractOcr(executable = tool.toString()).recognize(pageFor(page = 3))

        assertEquals(
            listOf(imagePath.toString(), "stdout", "-l", "swe+eng", "tsv"),
            Files.readString(argumentsFile).trimEnd('\n').split("\n"),
        )
    }

    @Test
    fun `the language codes keep the order the collection listed them in`() = runBlocking {
        val argumentsFile = directory.resolve("arguments.txt")
        val tool = tesseractEmitting(TSV_TWO_LINES, argumentsFile)

        TesseractOcr(executable = tool.toString()).recognize(pageFor(languages = "swe+eng"))

        val languages = Files.readString(argumentsFile).trimEnd('\n').split("\n")
            .let { arguments -> arguments[arguments.indexOf("-l") + 1] }
        assertEquals("swe+eng", languages, "the codes were reordered, so the character set changed")
    }

    @Test
    fun `the tool runs in a private directory of its own rather than beside the document`() = runBlocking {
        val where = directory.resolve("where.txt")
        val tool = writeFakeExecutable(
            directory,
            "tesseract",
            "pwd > '$where'\ncat <<'TSV'\n$TSV_TWO_LINES\nTSV",
        )
        val ocr = TesseractOcr(executable = tool.toString())

        ocr.recognize(pageFor())

        val workingDirectory = Path.of(Files.readString(where).trim())
        assertTrue(
            workingDirectory.fileName.toString().startsWith(TesseractOcr.WORK_DIRECTORY_PREFIX),
            "the tool ran in $workingDirectory",
        )
        assertFalse(
            Files.exists(artifactRoot.resolve(workingDirectory.fileName.toString())),
            "the private directory was created inside the artifact root",
        )
    }

    @Test
    fun `the private directory the tool ran in does not survive the reading`() = runBlocking {
        val before = privateDirectories()
        val tool = tesseractEmitting(TSV_TWO_LINES)

        TesseractOcr(executable = tool.toString()).recognize(pageFor())

        assertEquals(before, privateDirectories(), "a working directory was left behind")
    }

    // ---- The reading itself -------------------------------------------------------------------------

    @Test
    fun `words come back as lines in the order the tool read them`() = runBlocking {
        val tool = tesseractEmitting(TSV_TWO_LINES)

        val reading = TesseractOcr(executable = tool.toString()).recognize(pageFor())

        assertEquals("första raden här\nandra raden\nnytt stycke", reading.text)
    }

    @Test
    fun `rows that arrive out of order still come back in reading order`() = runBlocking {
        // The tool's rows are its reading order, but nothing in the format promises it: the last block is
        // emitted first, and each line's words follow. A parser that trusted arrival order would return the
        // page inside out, which is why the grouping is sorted rather than assumed.
        val tool = tesseractEmitting(
            tsv(
                wordRow(block = 2, par = 1, line = 1, word = 1, conf = "90", text = "sist"),
                wordRow(block = 1, par = 2, line = 1, word = 1, conf = "90", text = "mitten"),
                wordRow(block = 1, par = 1, line = 2, word = 1, conf = "90", text = "ett"),
                wordRow(block = 1, par = 1, line = 2, word = 2, conf = "90", text = "två"),
                wordRow(block = 1, par = 1, line = 1, word = 1, conf = "90", text = "a"),
                wordRow(block = 1, par = 1, line = 1, word = 2, conf = "90", text = "b"),
            ),
        )

        val reading = TesseractOcr(executable = tool.toString()).recognize(pageFor())

        assertEquals("a b\nett två\nmitten\nsist", reading.text)
    }

    @Test
    fun `a reading that spans two pages keeps the pages apart and in order`() = runBlocking {
        val tool = tesseractEmitting(
            tsv(
                wordRow(page = 2, block = 1, par = 1, line = 1, word = 1, conf = "90", text = "andra"),
                wordRow(page = 1, block = 1, par = 1, line = 1, word = 1, conf = "80", text = "första"),
            ),
        )

        val reading = TesseractOcr(executable = tool.toString()).recognize(pageFor())

        assertEquals("första\nandra", reading.text)
        assertEquals(0.85, reading.meanConfidence, "both pages' words belong to one reading")
    }

    @Test
    fun `the mean confidence is a fraction of the words the tool was sure about`() = runBlocking {
        val tool = tesseractEmitting(
            tsv(
                pageRow(),
                wordRow(block = 1, par = 1, line = 1, word = 1, conf = "90", text = "säker"),
                wordRow(block = 1, par = 1, line = 1, word = 2, conf = "100", text = "också"),
                // The structural rows carry no confidence at all: -1 means "this is not a word", and
                // including it would drag the average towards zero.
                lineRow(block = 1, par = 1, line = 2),
                wordRow(block = 1, par = 1, line = 2, word = 1, conf = "80", text = "läst"),
            ),
        )

        val reading = TesseractOcr(executable = tool.toString()).recognize(pageFor())

        // 90 + 100 + 80 over three words, as a fraction of one.
        assertEquals(0.9, reading.meanConfidence)
    }

    @Test
    fun `a page the tool found no words on is empty and has no confidence`() = runBlocking {
        val tool = tesseractEmitting(tsv(pageRow(), lineRow(block = 1, par = 1, line = 1)))

        val reading = TesseractOcr(executable = tool.toString()).recognize(pageFor())

        assertEquals("", reading.text)
        assertNull(reading.meanConfidence)
    }

    @Test
    fun `a header line without a level is not mistaken for a word`() = runBlocking {
        val tool = tesseractEmitting(
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n" +
                TSV_TWO_LINES,
        )

        val reading = TesseractOcr(executable = tool.toString()).recognize(pageFor())

        assertEquals("första raden här\nandra raden\nnytt stycke", reading.text)
    }

    // ---- The artifact -------------------------------------------------------------------------------

    @Test
    fun `the reading is written under the fingerprint and named after the page`() = runBlocking {
        val tool = tesseractEmitting(TSV_TWO_LINES)
        val fingerprint = fingerprint()

        val reading = TesseractOcr(executable = tool.toString()).recognize(pageFor(page = 42, fingerprint = fingerprint))

        assertEquals("${fingerprint.value}/ocr/page-000042.tsv.gz", reading.artifactRelativePath)
        assertTrue(Files.isRegularFile(artifactRoot.resolve(reading.artifactRelativePath!!)))
    }

    @Test
    fun `the artifact holds the tool's own words and boxes, and its checksum is the file's`() = runBlocking {
        val tool = tesseractEmitting(TSV_TWO_LINES)

        val reading = TesseractOcr(executable = tool.toString()).recognize(pageFor())
        val artifact = artifactRoot.resolve(reading.artifactRelativePath!!)

        val unpacked = GZIPInputStream(ByteArrayInputStream(Files.readAllBytes(artifact)))
            .readBytes().toString(Charsets.UTF_8)
        assertEquals("$TSV_TWO_LINES\n", unpacked, "the artifact is not the reading the tool produced")
        assertContains(unpacked, "44\t52\t214\t41\t91.460739\tförsta")
        assertEquals(sha256Of(artifact), reading.artifactSha256)
    }

    @Test
    fun `an artifact another fingerprint owns is left alone while this page's own is replaced`() = runBlocking {
        val other = ExtractionFingerprint.of("c".repeat(64), ExtractionSettings(ocrLanguages = "eng"))
        val theirs = artifactRoot.resolve("${other.value}/ocr/page-000001.tsv.gz")
        val mine = artifactRoot.resolve("${fingerprint().value}/ocr/page-000001.tsv.gz")
        val committed = "the artifact an earlier attempt committed".toByteArray()
        writeCommitted(theirs, committed)
        writeCommitted(mine, committed)

        TesseractOcr(executable = tesseractEmitting(TSV_TWO_LINES).toString())
            .recognize(pageFor(page = 1))

        assertTrue(
            Files.readAllBytes(theirs).contentEquals(committed),
            "an artifact another fingerprint owns was rewritten",
        )
        assertFalse(
            Files.readAllBytes(mine).contentEquals(committed),
            "the page that was read kept the bytes of an attempt that did not finish",
        )
    }

    @Test
    fun `a torn artifact from an interrupted attempt is replaced and its part file removed`() = runBlocking {
        val target = artifactRoot.resolve("${fingerprint().value}/ocr/page-000001.tsv.gz")
        val part = target.resolveSibling("${target.fileName}.part")
        Files.createDirectories(target.parent)
        Files.writeString(target, "half a gzip stream")
        Files.writeString(part, "half a gzip stream")

        val reading = TesseractOcr(executable = tesseractEmitting(TSV_TWO_LINES).toString()).recognize(pageFor())

        assertFalse(Files.exists(part), "the partial file an interrupted attempt left is still there")
        val unpacked = GZIPInputStream(ByteArrayInputStream(Files.readAllBytes(target)))
            .readBytes().toString(Charsets.UTF_8)
        assertEquals("$TSV_TWO_LINES\n", unpacked)
        assertEquals(sha256Of(target), reading.artifactSha256)
    }

    @Test
    fun `a reading that cannot be written is an error rather than half a file`() = runBlocking {
        val tool = tesseractEmitting(TSV_TWO_LINES)
        val blocked = artifactRoot.resolve("${fingerprint().value}/ocr")
        Files.createDirectories(blocked)
        val readableOnly = PosixFilePermissions.fromString("r-x------")
        Files.setPosixFilePermissions(blocked, readableOnly)
        try {
            val failure = try {
                TesseractOcr(executable = tool.toString()).recognize(pageFor())
                error("the reading was accepted although it could not be written down")
            } catch (unwritable: IOException) {
                unwritable
            }

            assertTrue(
                failure.message.orEmpty().contains("artifacts"),
                "a failed write has to name the path it could not write, not the tool that read the " +
                    "page: ${failure.message}",
            )
            assertEquals(
                emptyList(),
                Files.list(blocked).use { entries -> entries.map { it.fileName.toString() }.toList() },
                "a reading that could not be written left something behind",
            )
        } finally {
            Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("rwx------"))
        }
    }

    // ---- A tool that is not there, or that fails ------------------------------------------------------

    @Test
    fun `a tesseract that is not installed is reported as a tool that has to be installed`() = runBlocking {
        val missing = directory.resolve("not-installed")
        val ocr = TesseractOcr(executable = missing.toString())

        val failure = try {
            ocr.recognize(pageFor())
            error("the reading succeeded although the tool does not exist")
        } catch (unavailable: OcrUnavailableException) {
            unavailable
        }

        assertEquals(TesseractOcr.NEEDS_TESSERACT_CODE, failure.code)
        assertContains(failure.message.orEmpty(), "tesseract")
        assertContains(failure.message.orEmpty(), "install")
        assertFalse(
            Files.exists(artifactRoot.resolve(fingerprint().value)),
            "a missing tool wrote an artifact",
        )
    }

    @Test
    fun `a failing tool reports its status without repeating what it printed`() = runBlocking {
        val tool = writeFakeExecutable(
            directory,
            "tesseract",
            "echo 'hemligstämplad sida 1' 1>&2\nexit 1",
        )

        val failure = try {
            TesseractOcr(executable = tool.toString()).recognize(pageFor())
            error("the reading succeeded although the tool failed")
        } catch (failed: OcrToolFailedException) {
            failed
        }

        assertEquals(1, failure.exitCode)
        assertContains(failure.message.orEmpty(), "status 1")
        assertFalse(
            failure.message.orEmpty().contains("hemligstämplad"),
            "the tool's own output was repeated into the message, where a log would carry it",
        )
        assertContains(failure.stderrExcerpt, "hemligstämplad sida 1")
    }

    @Test
    fun `a reading past the caller's bound fails instead of being written short`() = runBlocking {
        val tool = tesseractEmitting(TSV_TWO_LINES)
        val ocr = TesseractOcr(executable = tool.toString(), maxCapturedStdoutBytes = 32)

        val failure = try {
            ocr.recognize(pageFor())
            error("a truncated reading was accepted")
        } catch (failed: OcrToolFailedException) {
            failed
        }

        assertContains(failure.message.orEmpty(), "truncated")
        assertFalse(
            Files.exists(artifactRoot.resolve("${fingerprint().value}/ocr/page-000001.tsv.gz")),
            "a truncated reading was stored as this page's evidence",
        )
    }

    @Test
    fun `a tool that hangs is stopped and the page fails rather than the run hanging`() = runBlocking {
        val tool = writeFakeExecutable(directory, "tesseract", "sleep 60")
        val ocr = TesseractOcr(executable = tool.toString(), timeout = Duration.ofMillis(700))

        val started = System.nanoTime()
        val failure = try {
            ocr.recognize(pageFor())
            error("the reading finished although the tool never did")
        } catch (timedOut: ExternalProcessTimeoutException) {
            timedOut
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(Duration.ofMillis(700), failure.timeout)
        assertTrue(elapsedMillis < 30_000, "the tool was waited for rather than stopped")
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private val imagePath: Path get() = directory.resolve("page-000001.png")

    /** A fake Tesseract: it prints [tsvText] to stdout, and records its arguments when asked to. */
    private fun tesseractEmitting(tsvText: String, argumentsFile: Path? = null): Path = writeFakeExecutable(
        directory,
        "tesseract",
        buildString {
            if (argumentsFile != null) append("printf '%s\\n' \"\$@\" > '$argumentsFile'\n")
            append("cat <<'TSV'\n$tsvText\nTSV")
        },
    )

    private fun pageFor(
        page: Int = 1,
        fingerprint: ExtractionFingerprint = fingerprint(),
        languages: String = "swe+eng",
    ): RenderedPage {
        Files.createDirectories(imagePath.parent)
        if (!Files.exists(imagePath)) Files.writeString(imagePath, "not really an image")
        return RenderedPage(
            documentId = DocumentId("doc-1"),
            page = page,
            imagePath = imagePath,
            artifactRoot = artifactRoot,
            ocrLanguages = languages,
            renderDpi = 300,
            fingerprint = fingerprint,
        )
    }

    private fun fingerprint(): ExtractionFingerprint =
        ExtractionFingerprint.of("a".repeat(64), ExtractionSettings(ocrLanguages = "swe+eng"))

    private fun writeCommitted(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    private fun privateDirectories(): Set<String> {
        val temporary = Path.of(System.getProperty("java.io.tmpdir"))
        return Files.list(temporary).use { entries ->
            entries.map { it.fileName.toString() }
                .filter { it.startsWith(TesseractOcr.WORK_DIRECTORY_PREFIX) }
                .toList()
                .toSet()
        }
    }

    private fun sha256Of(path: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    private companion object {

        /** Two lines in one paragraph, then a new paragraph: the order the lines have to come back in. */
        val TSV_TWO_LINES: String = tsv(
            pageRow(),
            wordRow(block = 1, par = 1, line = 1, word = 1, conf = "91.460739", text = "första", left = 44, top = 52),
            wordRow(block = 1, par = 1, line = 1, word = 2, conf = "93.257195", text = "raden"),
            wordRow(block = 1, par = 1, line = 1, word = 3, conf = "92.050690", text = "här"),
            wordRow(block = 1, par = 1, line = 2, word = 1, conf = "92.624954", text = "andra"),
            wordRow(block = 1, par = 1, line = 2, word = 2, conf = "96.044800", text = "raden"),
            wordRow(block = 1, par = 2, line = 1, word = 1, conf = "96.573021", text = "nytt"),
            wordRow(block = 1, par = 2, line = 1, word = 2, conf = "96.558464", text = "stycke"),
        )

        /** The box a word really came back with, so the artifact is checked against the tool's output. */
        fun pageRow(): String = row(level = 1, left = 0, top = 0, width = 900, height = 240, conf = "-1")

        fun lineRow(block: Int, par: Int, line: Int): String =
            row(level = 4, block = block, par = par, line = line, left = 42, top = 50, width = 762, height = 43, conf = "-1")

        fun wordRow(
            block: Int,
            par: Int,
            line: Int,
            word: Int,
            conf: String,
            text: String,
            page: Int = 1,
            left: Int = 44,
            top: Int = 52,
            width: Int = 214,
            height: Int = 41,
        ): String = row(
            level = 5,
            page = page,
            block = block,
            par = par,
            line = line,
            word = word,
            left = left,
            top = top,
            width = width,
            height = height,
            conf = conf,
            text = text,
        )

        fun tsv(vararg rows: String): String = rows.joinToString("\n")

        private fun row(
            level: Int,
            page: Int = 1,
            block: Int = 0,
            par: Int = 0,
            line: Int = 0,
            word: Int = 0,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            conf: String,
            text: String = "",
        ): String = listOf(
            level.toString(),
            page.toString(),
            block.toString(),
            par.toString(),
            line.toString(),
            word.toString(),
            left.toString(),
            top.toString(),
            width.toString(),
            height.toString(),
            conf,
            text,
        ).joinToString("\t")
    }
}
