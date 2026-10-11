package infoscry.investigate

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class EffortDefaultsTest {
    @Test
    fun `all levels use their exact caps and no native reasoning`() {
        val expected = listOf(
            InvestigationLimits(10, 15, 120), InvestigationLimits(50, 50, 600),
            InvestigationLimits(50, 75, 1200), InvestigationLimits(50, 100, 1800),
        )
        EffortLevel.entries.zip(expected).forEach { (level, limits) ->
            val preset = EffortDefaults.forLevel(level)
            assertEquals(limits, preset.limits)
            preset.limits.validate()
            assertEquals(NativeReasoning.None, preset.reasoning)
        }
        assertEquals(InvestigationLimits(), EffortDefaults.forLevel(EffortLevel.MEDIUM).limits)
    }

    @Test
    fun `presets round trip with nested limits and stable reasoning discriminators`() {
        val variants = listOf(
            NativeReasoning.None to "None",
            NativeReasoning.AnthropicThinkingBudget(2048) to "AnthropicThinkingBudget",
            NativeReasoning.OpenAiReasoningEffort("high") to "OpenAiReasoningEffort",
        )
        val json = Json { encodeDefaults = true }
        variants.forEach { (reasoning, discriminator) ->
            val preset = EffortPreset(InvestigationLimits(3, 7, 42), reasoning)
            val encoded = json.encodeToString(preset)
            assertEquals(discriminator, json.parseToJsonElement(encoded).jsonObject.getValue("reasoning").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(preset, json.decodeFromString<EffortPreset>(encoded))
        }
    }
}
