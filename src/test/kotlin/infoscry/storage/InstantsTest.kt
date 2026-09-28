package infoscry.storage

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one property every ordering query relies on: an instant written by [Instants] sorts as text in
 * the same order it occurred. A whole second is the interesting case, because the default
 * `Instant.toString()` prints no fraction for it and `Z` then sorts above the `.` of its own
 * fractions, which put `.000` after `.123` in a listing.
 */
class InstantsTest {

    @Test
    fun `a whole second keeps its fraction so text order matches time order`() {
        assertEquals("2026-01-01T00:00:00.000Z", Instants.format(Instant.parse("2026-01-01T00:00:00Z")))
        assertEquals("2026-01-01T00:00:00.123Z", Instants.format(Instant.parse("2026-01-01T00:00:00.123Z")))
        assertTrue(
            Instants.format(Instant.parse("2026-01-01T00:00:00Z")) <
                Instants.format(Instant.parse("2026-01-01T00:00:00.123Z")),
            "a whole second must sort before a fraction of the same second",
        )
    }

    @Test
    fun `now is a parseable instant`() {
        val written = Instants.now()
        assertEquals("Z", written.takeLast(1))
        assertEquals(24, written.length, "a fixed width is what makes the text comparison sound")
        assertEquals(written, Instants.format(Instant.parse(written)))
    }
}
