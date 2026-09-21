package infoscry.diagnostics

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.extract.PdfExtractor
import infoscry.extract.RenderedPage
import infoscry.extract.TesseractOcr
import infoscry.extract.writeFakeExecutable
import infoscry.jobs.ImportJobPayload
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

/**
 * What the machine's tool has to do with a document's checkpoints.
 *
 * A checkpoint is reused only under the same fingerprint, and the fingerprint covers the tool that produced
 * the reading: the same page read by Tesseract 5.4 and by 5.5 is not the same evidence, and a page rendered
 * at 150 dpi is not the same reading as one rendered at 300. The settings a job is created with therefore
 * have to record both — and recording them is what makes an upgrade a *new extraction* rather than a silent
 * reuse of an older version's work.
 *
 * The tool is a script here, so a version upgrade, an absent tool, and the difference between "no tool" and
 * "nobody asked" are all produced deliberately instead of being whatever this machine happens to have.
 */
class ExtractionSettingsProbeTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-extraction-settings")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the tool's own version is what a document's checkpoints are keyed by`() = runBlocking {
        val older = tesseractReporting("tesseract 5.4.0")
        val newer = tesseractReporting("tesseract 5.5.3")

        val before = ToolProbe.extractionSettings(ocrLanguages = "eng", executable = older.toString())
        val after = ToolProbe.extractionSettings(ocrLanguages = "eng", executable = newer.toString())

        assertEquals("tesseract 5.4.0", before.ocrTool)
        assertEquals("tesseract 5.5.3", after.ocrTool)
        assertNotEquals(
            ExtractionFingerprint.of(DOCUMENT, before),
            ExtractionFingerprint.of(DOCUMENT, after),
            "an upgraded tool would have reused the pages the older version read",
        )
    }

    @Test
    fun `two tool versions cannot write their readings to the same artifact`() = runBlocking {
        val older = ToolProbe.extractionSettings("eng", tesseractReporting("tesseract 5.4.0").toString())
        val newer = ToolProbe.extractionSettings("eng", tesseractReporting("tesseract 5.5.3").toString())

        assertNotEquals(
            artifactPathUnder(older),
            artifactPathUnder(newer),
            "the two versions' word boxes would have collided at one path",
        )
    }

    @Test
    fun `the render resolution is part of what a reading is`() = runBlocking {
        val settings = ToolProbe.extractionSettings("eng", tesseractReporting("tesseract 5.5.3").toString())

        assertEquals(PdfExtractor.DEFAULT_RENDER_DPI, settings.renderDpi)
        assertNotEquals(
            ExtractionFingerprint.of(DOCUMENT, settings),
            ExtractionFingerprint.of(DOCUMENT, settings.copy(renderDpi = 150)),
            "pages rendered at another resolution would have reused these readings",
        )
    }

    @Test
    fun `a job carries the tool version it was created with`() = runBlocking {
        val settings = ToolProbe.extractionSettings(
            ocrLanguages = "swe+eng",
            executable = tesseractReporting("tesseract 5.5.3").toString(),
        )

        val payload = ImportJobPayload.of(
            collectionId = CollectionId.new(),
            sources = listOf("/documents/scan.pdf"),
            settings = settings,
        )
        val reread = ImportJobPayload.decode(payload.encode())

        assertEquals("tesseract 5.5.3", reread.settings.ocrTool)
        assertEquals(PdfExtractor.DEFAULT_RENDER_DPI, reread.settings.renderDpi)
        assertEquals("swe+eng", reread.settings.ocrLanguages)
        assertEquals(
            ExtractionFingerprint.of(DOCUMENT, settings),
            ExtractionFingerprint.of(DOCUMENT, reread.settings),
            "a resumed job would compare against settings other than the ones it started with",
        )
    }

    @Test
    fun `a machine with no tool records that rather than leaving it unrecorded`() = runBlocking {
        val absent = ToolProbe.extractionSettings(
            ocrLanguages = "eng",
            executable = directory.resolve("no-such-tesseract").toString(),
        )

        assertEquals(ToolProbe.OCR_TOOL_ABSENT, absent.ocrTool)
        assertNotEquals(
            ExtractionFingerprint.of(DOCUMENT, absent),
            ExtractionFingerprint.of(DOCUMENT, ExtractionSettings(ocrLanguages = "eng")),
            "\"this run had no tool\" must not fingerprint as \"nobody asked\"",
        )
    }

    /** The artifact path a reading under [settings] would be written to. */
    private fun artifactPathUnder(settings: ExtractionSettings): String = TesseractOcr.relativeArtifactPath(
        RenderedPage(
            documentId = DocumentId.new(),
            page = 1,
            imagePath = directory.resolve("page-000001.png"),
            artifactRoot = directory.resolve("artifacts"),
            ocrLanguages = settings.ocrLanguages,
            renderDpi = null,
            fingerprint = ExtractionFingerprint.of(DOCUMENT, settings),
        ),
    )

    /**
     * A stand-in Tesseract that reports [banner] as its version.
     *
     * Each version gets its own file because these tests compare two versions: one path that both write to
     * would leave only the last one to be found, and the comparison would be between a version and itself.
     */
    private fun tesseractReporting(banner: String): Path = writeFakeExecutable(
        directory,
        banner.replace(' ', '-'),
        """
        case "${'$'}1" in
          --version) echo "$banner" ;;
        esac
        """.trimIndent(),
    )

    private companion object {

        /** A document's content hash, as the fingerprint takes it: any SHA-256 hex will do here. */
        const val DOCUMENT: String = "9f2c1d3e4b5a60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"
    }
}
