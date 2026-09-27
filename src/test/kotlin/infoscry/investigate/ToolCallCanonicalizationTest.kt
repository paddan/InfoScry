package infoscry.investigate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit coverage for the repeated-tool-call equivalence key: formatting and object-key order are
 * ignored recursively, while array order, value types, and distinct values are preserved.
 */
class ToolCallCanonicalizationTest {

    @Test
    fun `object-key order and whitespace are ignored`() {
        assertEquals(
            canonicalToolArguments("""{"a": 1, "b": {"y": 2, "x": 3}}"""),
            canonicalToolArguments("""{ "b": { "x": 3, "y": 2 }, "a": 1 }"""),
        )
    }

    @Test
    fun `nested key order is ignored at every depth`() {
        assertEquals(
            canonicalToolArguments("""{"outer":{"z":[{"b":1,"a":2},{"d":4,"c":3}]}}"""),
            canonicalToolArguments("""{"outer":{"z":[{"a":2,"b":1},{"c":3,"d":4}]}}"""),
        )
    }

    @Test
    fun `array order is preserved`() {
        assertNotEquals(
            canonicalToolArguments("""{"ids":[1,2,3]}"""),
            canonicalToolArguments("""{"ids":[3,2,1]}"""),
        )
    }

    @Test
    fun `distinct values and types stay distinct`() {
        assertNotEquals(canonicalToolArguments("""{"a":1}"""), canonicalToolArguments("""{"a":2}"""))
        assertNotEquals(canonicalToolArguments("""{"a":1}"""), canonicalToolArguments("""{"a":"1"}"""))
    }

    @Test
    fun `malformed arguments are returned unchanged`() {
        assertEquals("not json", canonicalToolArguments("not json"))
    }
}
