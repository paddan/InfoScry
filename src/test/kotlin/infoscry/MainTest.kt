package infoscry

import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun `product identity is stable`() {
        assertEquals("InfoScry", AppInfo.name)
    }
}
