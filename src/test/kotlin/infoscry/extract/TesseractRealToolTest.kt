package infoscry.extract

import infoscry.EXTERNAL_TAG
import infoscry.diagnostics.ToolProbe
import infoscry.domain.DocumentId
import infoscry.fixtures.OcrFixtureGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/**
 * The reading a person actually gets, from the Tesseract this machine has.
 *
 * Everything else about OCR is tested with a stand-in tool, which pins this project's half of the contract
 * and can never tell whether the real tool, the real language packs, and the real image agree. This test is
 * the one that does: it reads the committed fixture with the installed Tesseract and asserts that both
 * languages come back, including the Swedish letters an English-only reading silently loses.
 *
 * It is tagged `external` and excluded from the default suite, because a machine without Tesseract cannot
 * run it and a suite that failed everywhere the tool is absent would teach people to ignore it. It is the
 * opposite of a skip when it *is* run: the language packs and the tool are required, and the test says so,
 * because the task that runs it exists to prove the tool works.
 */
class TesseractRealToolTest {

    @Tag(EXTERNAL_TAG)
    @Test
    fun `the installed tool reads both languages from the committed fixture`() = runBlocking {
        val status = ToolProbe.tesseract()
        assertTrue(
            status.available,
            "this test needs Tesseract installed and runnable: ${status.remedy ?: status.summary}",
        )
        assertTrue(
            status.languages.containsAll(listOf(SWEDISH, ENGLISH)),
            "Tesseract is installed but cannot read $SWEDISH and $ENGLISH, which this test needs: " +
                "it reports ${status.languages.size} languages",
        )
        val directory = Files.createTempDirectory("infoscry-real-ocr")
        try {
            val image = directory.resolve(OcrFixtureGenerator.IMAGE_NAME)
            val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/${OcrFixtureGenerator.IMAGE_NAME}"))
            stream.use { Files.copy(it, image, REPLACE_EXISTING) }
            val artifactRoot = directory.resolve("artifacts")

            val reading = TesseractOcr().recognize(
                RenderedPage(
                    documentId = DocumentId("doc-1"),
                    page = 1,
                    imagePath = image,
                    artifactRoot = artifactRoot,
                    // Swedish first, which is what makes the letters come back: Tesseract takes its
                    // alphabet from the leading language, and `eng+swe` reads `ä` as `a`.
                    ocrLanguages = "$SWEDISH+$ENGLISH",
                    renderDpi = null,
                    fingerprint = ExtractionFingerprint.of(
                        "e".repeat(64),
                        ExtractionSettings(ocrLanguages = "$SWEDISH+$ENGLISH"),
                    ),
                ),
            )

            assertContains(reading.text, OcrFixtureGenerator.SWEDISH_WORD)
            assertContains(reading.text, OcrFixtureGenerator.ENGLISH_WORD)
            // One line per drawn line is what a page's reading is for; a reading that came back as one
            // run-on line would still contain the words and would cite them all to the same span.
            assertTrue(
                reading.text.lines().size >= 3,
                "the reading came back as ${reading.text.lines().size} lines: ${reading.text}",
            )
            val confidence = requireNotNull(reading.meanConfidence) { "the tool read no words at all" }
            assertTrue(confidence in 0.0..1.0, "confidence is a fraction, was $confidence")
            val artifact: Path = artifactRoot.resolve(requireNotNull(reading.artifactRelativePath))
            assertTrue(
                Files.size(artifact) > 0,
                "the word boxes this reading is checked against were not written",
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** The two language codes the fixture is read with, and the words that prove both were read. */
    private companion object {

        const val SWEDISH: String = "swe"
        const val ENGLISH: String = "eng"
    }
}
