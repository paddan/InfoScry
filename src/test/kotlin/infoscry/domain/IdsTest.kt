package infoscry.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdsTest {

    @Test
    fun `new generates distinct non blank identifiers`() {
        val first = DocumentId.new()
        val second = DocumentId.new()
        assertNotEquals(first, second)
        assertTrue(first.value.isNotBlank())
    }

    @Test
    fun `every identifier rejects a blank value`() {
        assertFailsWith<IllegalArgumentException> { CollectionId(" ") }
        assertFailsWith<IllegalArgumentException> { DocumentId("") }
        assertFailsWith<IllegalArgumentException> { ContentUnitId("") }
        assertFailsWith<IllegalArgumentException> { ChunkId("") }
        assertFailsWith<IllegalArgumentException> { JobId("") }
    }

    @Test
    fun `identifiers are distinct types over the same text`() {
        val documentId = DocumentId("shared-text")
        val contentUnitId = ContentUnitId("shared-text")
        assertEquals("shared-text", documentId.value)
        assertEquals("shared-text", contentUnitId.value)
    }
}
