package infoscry.llm

import kotlinx.serialization.json.Json

/**
 * A compact kotlinx.serialization JSON codec for provider payloads.
 *
 * The two adapters share it so that provider JSON — which is not ours and may carry extra fields —
 * is decoded once with the same tolerant settings: unknown keys are ignored, members whose value is
 * null are omitted when encoding requests, and default-valued members are still written so a thin
 * compatible endpoint sees the fields it expects rather than a surprise gap.
 */
internal val LlmJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}