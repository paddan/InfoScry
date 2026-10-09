package infoscry.ocr

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadingMethodTest {
    @Test
    fun `method ids parse and serialize as strings`() {
        val methods = listOf(ReadingMethod.Tesseract, ReadingMethod.Surya, ReadingMethod.Llm("profile-1"))
        methods.forEach { method ->
            assertEquals(method, ReadingMethod.parse(method.id))
            assertEquals("\"${method.id}\"", Json.encodeToString(ReadingMethod.serializer(), method))
            assertEquals(method, Json.decodeFromString(ReadingMethod.serializer(), "\"${method.id}\""))
        }
    }

    @Test
    fun `invalid method ids are rejected`() {
        listOf("", "llm:", "LLM", "unknown").forEach { id ->
            assertFailsWith<IllegalArgumentException> { ReadingMethod.parse(id) }
        }
    }
}
