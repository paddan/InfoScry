package infoscry.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * How one osascript pick run is read: a choice, a cancel, or no dialog at all.
 *
 * The route's injected fake covers the route contract; this covers the classification the route never
 * sees, because a real dialog cannot be opened in a test. The headless case is the one that matters most:
 * a nonzero exit that names no cancel is not the user saying no, it is the dialog never being shown — and
 * the caller answers those two differently.
 */
class PickOutcomeTest {

    @Test
    fun `a zero exit with paths is the choice, kept as absolute paths`() {
        val paths = pickOutcomeOf(0, "/tmp/a.pdf\n/tmp/b.txt\n", "")

        assertEquals(listOf("/tmp/a.pdf", "/tmp/b.txt"), paths)
    }

    @Test
    fun `osascript's cancel wording is a cancel`() {
        assertFailsWith<PickerCancelledException> {
            pickOutcomeOf(1, "", "execution error: User canceled. (-128)")
        }
    }

    @Test
    fun `a nonzero exit with any other message is no dialog, not a cancel`() {
        assertFailsWith<PickerUnavailableException> {
            pickOutcomeOf(1, "", "execution error: No user interaction allowed. (-1713)")
        }
    }

    @Test
    fun `a silent nonzero exit with no output is no dialog`() {
        assertFailsWith<PickerUnavailableException> {
            pickOutcomeOf(1, "", "")
        }
    }
}
