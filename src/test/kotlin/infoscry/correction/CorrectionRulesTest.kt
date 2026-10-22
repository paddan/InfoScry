package infoscry.correction

import infoscry.domain.CollectionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CorrectionRulesTest {

    private fun input(find: String, replace: String = "Spinnars", exactCase: Boolean = false) =
        CorrectionRuleInput(find, replace, exactCase)

    private fun rule(id: String, find: String, replace: String, exactCase: Boolean = false) =
        CorrectionRule(id, CollectionId("c"), find, replace, exactCase, "t", "t")

    @Test
    fun `values are trimmed, collapsed and composed`() {
        assertEquals(input("Karl Johan", "Carl Johan"), CorrectionRules.normalize(input("  Karl \n Johan ", " Carl  Johan")))
        assertEquals("åsa", CorrectionRules.normalize(input("åsa")).find)
    }

    @Test
    fun `blank values are refused`() {
        assertFailsWith<InvalidCorrectionException> { CorrectionRules.normalize(input("  ")) }
        assertFailsWith<InvalidCorrectionException> { CorrectionRules.normalize(input("spinners", " ")) }
    }

    @Test
    fun `a rule that changes nothing is refused`() {
        assertFailsWith<InvalidCorrectionException> { CorrectionRules.normalize(input("Spinnars", "Spinnars")) }
    }

    @Test
    fun `values longer than 200 characters are refused`() {
        CorrectionRules.normalize(input("a".repeat(200)))
        assertFailsWith<InvalidCorrectionException> { CorrectionRules.normalize(input("a".repeat(201))) }
        assertFailsWith<InvalidCorrectionException> { CorrectionRules.normalize(input("spinners", "a".repeat(201))) }
    }

    @Test
    fun `find begins and ends with a letter, mark or digit`() {
        assertFailsWith<InvalidCorrectionException> { CorrectionRules.normalize(input("-spinners")) }
        assertFailsWith<InvalidCorrectionException> { CorrectionRules.normalize(input("spinners.")) }
        assertEquals("St. Erik", CorrectionRules.normalize(input("St. Erik", "S:t Erik")).find)
        assertEquals("1890", CorrectionRules.normalize(input("1890", "1899")).find)
    }

    @Test
    fun `the find key folds case unless the rule is exact`() {
        assertEquals(CorrectionRules.findKey(input("Spinners")), CorrectionRules.findKey(input("spinners")))
        assertNotEquals(CorrectionRules.findKey(input("AB", exactCase = true)), CorrectionRules.findKey(input("ab", exactCase = true)))
        assertNotEquals(CorrectionRules.findKey(input("ab")), CorrectionRules.findKey(input("ab", exactCase = true)))
    }

    @Test
    fun `the fingerprint describes the list, not its order or ids`() {
        val a = rule("1", "spinners", "Spinnars")
        val b = rule("2", "malmo", "malmö")
        assertEquals(CorrectionRules.fingerprint(listOf(a, b)), CorrectionRules.fingerprint(listOf(b, a.copy(id = "9"))))
        assertNotEquals(CorrectionRules.fingerprint(listOf(a, b)), CorrectionRules.fingerprint(listOf(a, b.copy(exactCase = true))))
        assertNotEquals(CorrectionRules.fingerprint(listOf(a)), CorrectionRules.fingerprint(emptyList()))
        assertEquals(64, CorrectionRules.fingerprint(emptyList()).length)
    }
}
