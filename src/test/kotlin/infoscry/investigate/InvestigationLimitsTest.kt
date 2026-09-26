package infoscry.investigate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The shared per-turn limits contract: the documented defaults, the inclusive bounds, and which
 * field each rejection names. The HTTP layer maps the same validation to a 400.
 */
class InvestigationLimitsTest {

    @Test
    fun `defaults allow fifty rounds, fifty calls and six hundred seconds`() {
        val limits = InvestigationLimits()

        assertEquals(50, limits.maxToolRounds)
        assertEquals(50, limits.maxToolCalls)
        assertEquals(600, limits.maxTurnSeconds)
        limits.validate()
    }

    @Test
    fun `the lowest and highest allowed value of every field is accepted`() {
        InvestigationLimits(maxToolRounds = 1, maxToolCalls = 1, maxTurnSeconds = 10).validate()
        InvestigationLimits(maxToolRounds = 50, maxToolCalls = 100, maxTurnSeconds = 1800).validate()
    }

    @Test
    fun `each out-of-range field is rejected and names itself`() {
        assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxToolRounds = 0).validate() }
        assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxToolRounds = 51).validate() }
        assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxToolCalls = 0).validate() }
        assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxToolCalls = 101).validate() }
        assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxTurnSeconds = 9).validate() }
        assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxTurnSeconds = 1801).validate() }

        assertEquals(
            "maxToolRounds must be between 1 and 50",
            assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxToolRounds = 51).validate() }.message,
        )
        assertEquals(
            "maxTurnSeconds must be between 10 and 1800",
            assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxTurnSeconds = 9).validate() }.message,
        )
    }
}
