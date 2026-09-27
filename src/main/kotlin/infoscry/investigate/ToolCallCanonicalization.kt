package infoscry.investigate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Canonical form of a tool call's raw argument JSON, used to decide whether a repeated call is
 * equivalent. Object keys are sorted recursively and the compact encoding drops formatting
 * whitespace, so `{"a": 1, "b": {"y": 2, "x": 3}}` and `{ "b": { "x": 3, "y": 2 }, "a": 1 }` compare
 * equal. Array order, value types, and distinct values are preserved, so genuinely different
 * arguments and reordered arrays stay distinct.
 *
 * Malformed arguments are returned unchanged: the typed tool failure still runs and consumes its
 * call allowance, and repeated-call admission keeps working. Deliberately no fuzzy matching or
 * tool-default inference.
 */
internal fun canonicalToolArguments(raw: String): String = try {
    Json.encodeToString(JsonElement.serializer(), canonicalize(Json.parseToJsonElement(raw)))
} catch (_: Exception) {
    raw
}

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries
            .sortedBy { it.key }
            .associateTo(LinkedHashMap()) { it.key to canonicalize(it.value) },
    )
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}
