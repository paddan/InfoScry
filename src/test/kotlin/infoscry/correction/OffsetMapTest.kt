package infoscry.correction

import kotlin.test.Test
import kotlin.test.assertEquals

class OffsetMapTest {

    private val map = OffsetMap(listOf(TextEdit(10, 18, "Spinnars", "r1"), TextEdit(30, 35, "Stockholm", "r2")))

    @Test
    fun `offsets before and between edits move by the edits before them`() {
        assertEquals(5, map.mapStart(5))
        assertEquals(20, map.mapStart(20))
        assertEquals(44, map.mapStart(40))
        assertEquals(44, map.mapEnd(40))
    }

    @Test
    fun `an offset inside an edit maps to the edit's new start or end`() {
        assertEquals(30, map.mapStart(31))
        assertEquals(39, map.mapEnd(31))
    }

    @Test
    fun `an edit's own bounds map to its new bounds`() {
        assertEquals(30, map.mapStart(30))
        assertEquals(39, map.mapEnd(35))
        assertEquals(30, map.mapEnd(30))
    }
}
