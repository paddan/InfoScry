package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The system prompt is the immutable core rules followed by the selected prompt body, nothing else. */
class PromptServiceTest {

    private val prompts = PromptService()

    @Test
    fun `compose puts the core rules first, then a blank line, then the body`() {
        val composed = prompts.compose("Answer in one line.")

        assertEquals(prompts.coreRules() + "\n\n" + "Answer in one line.", composed)
        assertTrue(composed.startsWith("You are answering questions"))
    }

    @Test
    fun `compose adds no collection instructions block`() {
        assertFalse(prompts.compose("Always cite the page number").contains("Collection instructions"))
    }
}
