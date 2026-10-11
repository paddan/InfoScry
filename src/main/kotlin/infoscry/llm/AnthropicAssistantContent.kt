package infoscry.llm

import kotlinx.serialization.json.*

/** Request-local protocol data. Never persist or expose this envelope as answer text. */
class AnthropicAssistantContent(val blocks: List<JsonObject>) {
    override fun toString(): String = "AnthropicAssistantContent([opaque])"
}

/** Reassembles exact content blocks independently of the public text/tool stream. */
internal class AnthropicContentAccumulator(private val required: Boolean) {
    private val blocks = sortedMapOf<Int, JsonObject>()
    private val open = mutableSetOf<Int>()
    private val arguments = mutableMapOf<Int, StringBuilder>()
    private var hasThinking = false
    private var stopped = false

    fun accept(payload: String) {
        try { acceptEvent(payload) } catch (_: Exception) { throw invalid() }
    }

    private fun acceptEvent(payload: String) {
        val event = try { LlmJson.parseToJsonElement(payload).jsonObject }
        catch (_: Exception) { throw invalid() }
        val type = event["type"]?.jsonPrimitive?.content
        val index = event["index"]?.jsonPrimitive?.intOrNull
        when (type) {
            "content_block_start" -> {
                val i = index ?: throw invalid()
                val block = event["content_block"] as? JsonObject ?: throw invalid()
                if (blocks.putIfAbsent(i, block) != null) throw invalid()
                open.add(i)
                val kind = block["type"]?.jsonPrimitive?.content
                if (kind == "thinking" || kind == "redacted_thinking") hasThinking = true
            }
            "content_block_delta" -> {
                val i = index ?: throw invalid()
                val block = blocks[i]
                // Existing non-thinking providers sometimes stream text without a start block.
                if (block == null) { if (required || hasThinking) throw invalid() else return }
                if (i !in open) throw invalid()
                val delta = event["delta"] as? JsonObject ?: throw invalid()
                val field = when (delta["type"]?.jsonPrimitive?.content) {
                    "text_delta" -> "text"
                    "thinking_delta" -> "thinking"
                    "signature_delta" -> "signature"
                    "input_json_delta" -> {
                        arguments.getOrPut(i) { StringBuilder() }.append(delta["partial_json"]?.jsonPrimitive?.content.orEmpty())
                        null
                    }
                    else -> null
                }
                if (field != null) blocks[i] = JsonObject(block + (field to JsonPrimitive(block[field]?.jsonPrimitive?.content.orEmpty() + delta[field]?.jsonPrimitive?.content.orEmpty())))
            }
            "content_block_stop" -> {
                val i = index ?: throw invalid()
                if (!open.remove(i)) { if (required || hasThinking) throw invalid() else return }
                arguments[i]?.takeIf { it.isNotEmpty() }?.let { raw ->
                    val input = try { LlmJson.parseToJsonElement(raw.toString()).jsonObject } catch (_: Exception) { throw invalid() }
                    blocks[i] = JsonObject(blocks.getValue(i) + ("input" to input))
                }
            }
            "message_stop" -> stopped = true
        }
    }

    fun finish(): AnthropicAssistantContent? = try { finishContent() } catch (_: Exception) { throw invalid() }

    private fun finishContent(): AnthropicAssistantContent? {
        if (!required && !hasThinking) return null
        if (!stopped || open.isNotEmpty() || blocks.isEmpty()) throw invalid()
        blocks.values.forEach { block ->
            when (block["type"]?.jsonPrimitive?.content) {
                "thinking" -> if (block["signature"]?.jsonPrimitive?.content.isNullOrBlank()) throw invalid()
                "redacted_thinking" -> if (block["data"]?.jsonPrimitive?.content.isNullOrBlank()) throw invalid()
            }
        }
        return AnthropicAssistantContent(blocks.values.toList())
    }

    private fun invalid() = LlmError.MalformedResponseError("the provider returned incomplete assistant content")
}
