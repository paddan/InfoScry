package infoscry.investigate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EffortLevel { LOW, MEDIUM, HIGH, XHIGH }

@Serializable
sealed class NativeReasoning {
    @Serializable
    @SerialName("None")
    data object None : NativeReasoning()

    @Serializable
    @SerialName("AnthropicThinkingBudget")
    data class AnthropicThinkingBudget(val tokens: Int) : NativeReasoning()

    @Serializable
    @SerialName("OpenAiReasoningEffort")
    data class OpenAiReasoningEffort(val value: String) : NativeReasoning()
}

@Serializable
data class EffortPreset(
    val limits: InvestigationLimits,
    val reasoning: NativeReasoning = NativeReasoning.None,
)

object EffortDefaults {
    fun forLevel(level: EffortLevel): EffortPreset = EffortPreset(when (level) {
        EffortLevel.LOW -> InvestigationLimits(10, 15, 120)
        EffortLevel.MEDIUM -> InvestigationLimits(50, 50, 600)
        EffortLevel.HIGH -> InvestigationLimits(50, 75, 1200)
        EffortLevel.XHIGH -> InvestigationLimits(50, 100, 1800)
    })
}

object EffortResolver {
    fun resolve(profile: infoscry.llm.LlmProfile, level: EffortLevel): EffortPreset =
        profile.effortOverrides?.get(level) ?: EffortDefaults.forLevel(level)
}
