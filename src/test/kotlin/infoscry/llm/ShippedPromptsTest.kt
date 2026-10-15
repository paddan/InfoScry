package infoscry.llm

import infoscry.domain.CollectionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShippedPromptsTest {

    @Test
    fun `the shipped default prompt has id default, name Default, version 1 and builtIn true`() {
        val prompt = ShippedPrompts.prompt(CollectionId("collection-1"), LlmPromptRole.ASK)

        assertEquals("default", prompt.id)
        assertEquals("Default", prompt.name)
        assertEquals(1, prompt.version)
        assertTrue(prompt.builtIn)
        assertEquals(CollectionId("collection-1"), prompt.collectionId)
        assertEquals(LlmPromptRole.ASK, prompt.role)
        assertEquals(ShippedPrompts.body(LlmPromptRole.ASK), prompt.body)
    }

    @Test
    fun `the ask body starts with the source excerpt tagging sentence`() {
        assertTrue(ShippedPrompts.body(LlmPromptRole.ASK).startsWith("The source excerpts are tagged"))
    }

    @Test
    fun `the investigate body starts with the tool instruction`() {
        assertTrue(
            ShippedPrompts.body(LlmPromptRole.INVESTIGATE)
                .startsWith("Investigate the question with the provided tools"),
        )
    }
}
