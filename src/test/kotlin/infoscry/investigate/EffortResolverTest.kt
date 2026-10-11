package infoscry.investigate

import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class EffortResolverTest {
    private val profile = LlmProfile("id", "name", LlmProvider.OPENAI_COMPATIBLE, "model", 8192, 4096, 0.0, 0.0, 0.0, true)

    @Test fun `null and empty maps inherit each levels own defaults for both providers`() {
        LlmProvider.entries.forEach { provider ->
            listOf(null, emptyMap<EffortLevel, EffortPreset>()).forEach { overrides ->
                EffortLevel.entries.forEach { level ->
                    assertEquals(EffortDefaults.forLevel(level), EffortResolver.resolve(profile.copy(provider = provider, effortOverrides = overrides), level))
                }
            }
        }
    }

    @Test fun `a sparse override affects only its selected level`() {
        val override = EffortPreset(InvestigationLimits(2, 3, 20))
        val configured = profile.copy(effortOverrides = mapOf(EffortLevel.LOW to override))
        assertEquals(override, EffortResolver.resolve(configured, EffortLevel.LOW))
        assertEquals(EffortDefaults.forLevel(EffortLevel.HIGH), EffortResolver.resolve(configured, EffortLevel.HIGH))
    }
}
