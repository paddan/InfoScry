package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Which extractor a detected type goes to, and what happens when none of them claim it.
 *
 * The registry is what lets a format be added without touching the import path, so the property under
 * test is that selection is exact: an extractor's own type wins, the fallback is a genuine last resort,
 * and a container nobody can read is refused by name instead of being handed to the wrong reader.
 */
class ExtractorRegistryTest {

    @Test
    fun `an exact media type wins over the fallback`() {
        val specific = RecordingExtractor(setOf("text/plain"))
        val fallback = RecordingExtractor(setOf("text/plain", "application/json"))
        val registry = ExtractorRegistry(listOf(specific), fallback)

        assertSame(specific, registry.select("text/plain"))
    }

    @Test
    fun `the fallback handles a type no extractor claims`() {
        val specific = RecordingExtractor(setOf("application/pdf"))
        val fallback = RecordingExtractor(setOf("text/plain"))
        val registry = ExtractorRegistry(listOf(specific), fallback)

        assertSame(fallback, registry.select("text/plain"))
    }

    @Test
    fun `a type nobody claims is refused with an actionable code`() {
        val registry = ExtractorRegistry(emptyList(), RecordingExtractor(setOf("text/plain")))

        val failure = assertFailsWith<UnsupportedMediaTypeException> { registry.select("image/png") }

        assertEquals("UNSUPPORTED_MEDIA_TYPE", failure.code)
        assertContains(failure.message.orEmpty(), "image/png")
    }

    @Test
    fun `a registry without a fallback claims only its exact types`() {
        val registry = ExtractorRegistry(listOf(RecordingExtractor(setOf("text/plain"))), fallback = null)

        assertEquals(setOf("text/plain"), registry.claimedMediaTypes())
        assertFailsWith<UnsupportedMediaTypeException> { registry.select("application/zip") }
    }

    @Test
    fun `the production registry routes every type it can read to a reader that claims it`() {
        val registry = ExtractorRegistry.production()

        // The types the pipeline can read, one per format family it ships. A type listed here that no
        // reader claims is a lie in the doctor's output; a type a reader claims and this list omits is a
        // format nobody noticed had become reachable.
        val readable = listOf(
            "text/plain",
            "text/csv",
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/tiff",
        )
        readable.forEach { mediaType ->
            assertTrue(
                mediaType in registry.claimedMediaTypes(),
                "the production registry cannot read $mediaType",
            )
            registry.select(mediaType)
        }
        // A container nobody handles is refused by name rather than handed to a reader that would cite
        // its bytes as text.
        assertFailsWith<UnsupportedMediaTypeException> { registry.select("application/x-msdownload") }
    }

    @Test
    fun `two extractors claiming one type is a wiring mistake, not a coin toss`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ExtractorRegistry(
                listOf(RecordingExtractor(setOf("text/plain")), RecordingExtractor(setOf("text/plain"))),
                fallback = null,
            )
        }

        assertContains(failure.message.orEmpty(), "text/plain")
    }

    @Test
    fun `extract selects by detected type and streams the extractor's events`() = runBlocking {
        val specific = RecordingExtractor(setOf("text/plain"), units = 2)
        val registry = ExtractorRegistry(listOf(specific), fallback = null)
        val boundary = RecordingBoundary()
        val extraction = input(boundary)

        val events = registry.extract(extraction, "text/plain").toList()

        assertSame(extraction, specific.asked.single())
        assertEquals(3, events.size)
        assertEquals(events[0], specific.emitted[0])
        assertEquals(events[1], specific.emitted[1])
        assertEquals(3, boundary.permits, "one permit per unit, and one for the finished event")
        assertEquals(2, (events[2] as ExtractionEvent.Finished).totalUnits)
    }

    private fun input(boundary: UnitBoundary): ExtractionInput = ExtractionInput(
        documentId = DocumentId("doc-1"),
        managedPath = Path.of("/tmp/managed/original.txt"),
        artifactRoot = Files.createTempDirectory("infoscry-registry-artifacts"),
        settings = ExtractionSettings(ocrLanguages = "eng"),
        fingerprint = ExtractionFingerprint.of("a".repeat(64), ExtractionSettings(ocrLanguages = "eng")),
        committedUnitKeys = emptySet(),
        boundary = boundary,
    )
}

/** An extractor that records what it was asked to do and produces a fixed number of units. */
internal class RecordingExtractor(
    override val supportedMediaTypes: Set<String>,
    private val units: Int = 0,
) : DocumentExtractor {

    init {
        require(units >= 0) { "an extractor cannot produce a negative number of units" }
    }

    val asked = mutableListOf<ExtractionInput>()

    val emitted = mutableListOf<ExtractionEvent.UnitReady>()

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        asked += input
        repeat(units) { index ->
            val ready = ExtractionEvent.UnitReady(
                key = "unit-$index",
                ordinal = index,
                unit = ContentUnitDraft(
                    locator = SourceLocation.TextLines(start = index + 1, end = index + 1),
                    extractedText = "text $index",
                    searchText = "text $index",
                ),
            )
            emitted += ready
            input.boundary.unit { emit(ready) }
        }
        val finished = ExtractionEvent.Finished(
            metadata = mapOf("extractor" to "recording"),
            totalUnits = units,
        )
        input.boundary.unit { emit(finished) }
    }
}

/** A unit boundary that runs its block inline and counts how often it was opened. */
internal class RecordingBoundary : UnitBoundary {

    var permits: Int = 0
        private set

    override suspend fun <T> unit(block: suspend () -> T): T {
        permits++
        return block()
    }
}
