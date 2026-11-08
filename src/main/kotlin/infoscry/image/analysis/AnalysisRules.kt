package infoscry.image.analysis

import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The result of validating one model answer: either an analysis to store, or the code that says why not. */
sealed interface ParsedAnalysis {
    data class Valid(val description: String, val tags: List<ImageTag>) : ParsedAnalysis
    data class Invalid(val code: String) : ParsedAnalysis
}

/**
 * The rules an image model's answer must satisfy before anything is stored.
 *
 * The answer is untrusted data with a fixed shape: `{"description": string, "tags": [{category, label, name?,
 * source}]}`. A tag that names a category outside the five, or has no usable label, is dropped rather than
 * stored as stated fact; a tag whose specific name the source text does not state is demoted to an
 * appearance-derived one, and without the run's *Identify from appearance* choice such a name is removed
 * entirely while the generic label stays. Nothing here trusts the model about provenance: only the caption and
 * page text decide whether a name is `TEXT`.
 *
 * [answer] is parsed as strict JSON. A malformed answer is [INVALID]; a provider refusal and a timeout
 * have their own codes, mapped by the caller that met them.
 */
object AnalysisRules {

    /** The most tags one image may carry; the model's later tags are dropped. */
    const val MAX_TAGS: Int = ImageAnalysis.MAX_TAGS

    /** The longest description one image may carry; a longer one is cut at a word. */
    const val MAX_DESCRIPTION: Int = 400

    /** The answer is not the JSON shape this build asked for. */
    const val INVALID: String = "IMAGE_ANALYSIS_INVALID"

    /** The provider refused to describe the picture. */
    const val REFUSED: String = "IMAGE_ANALYSIS_REFUSED"

    /** The provider did not answer inside the attempt's bound. */
    const val TIMEOUT: String = "IMAGE_ANALYSIS_TIMEOUT"

    /**
     * Validates one answer.
     *
     * [context] is every piece of source text the model was given about the picture — its caption and its page
     * text — and is the only authority on whether a `TEXT` name is stated. It is compared case- and
     * accent-insensitively, because the model may spell a name in another case or form than the page did.
     */
    fun parse(answer: String, context: String?, identifyAppearance: Boolean): ParsedAnalysis {
        val root = parseObject(answer) ?: return ParsedAnalysis.Invalid(INVALID)
        val description = (root["description"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ParsedAnalysis.Invalid(INVALID)
        val tags = root["tags"] as? JsonArray ?: return ParsedAnalysis.Invalid(INVALID)
        val contextText = normalized(context.orEmpty())
        val kept = buildList {
            for (element in tags) {
                if (size >= MAX_TAGS) break
                tagOf(element, contextText, identifyAppearance)?.let(::add)
            }
        }
        return ParsedAnalysis.Valid(description = cutAtWord(description, MAX_DESCRIPTION), tags = kept)
    }

    private fun parseObject(answer: String): JsonObject? = try {
        Json.parseToJsonElement(unwrapFence(answer)) as? JsonObject
    } catch (_: Throwable) {
        null
    }

    /**
     * [answer] with one surrounding Markdown code fence removed.
     *
     * The prompt asks for the object alone and the shipped models comply, but a local vision model often wraps
     * the same object in a ```` ```json ```` block. The block's content is still the one JSON object the schema
     * describes, so it is read as that object rather than refused; nothing else about the answer is relaxed.
     */
    private fun unwrapFence(answer: String): String {
        val trimmed = answer.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0) return trimmed
        val closing = trimmed.lastIndexOf("```")
        if (closing <= firstLineEnd) return trimmed
        return trimmed.substring(firstLineEnd + 1, closing).trim()
    }

    /**
     * One tag, or null when it is not a usable statement.
     *
     * The label is the tag's reason to exist, so a missing, blank or over-long one drops the tag. A missing or
     * over-long name drops only the name: "man" is still what the picture shows. Unknown categories and
     * sources drop the tag, because storing one under a category this archive does not have would invent a
     * vocabulary.
     */
    private fun tagOf(element: JsonElement, context: String, identifyAppearance: Boolean): ImageTag? {
        val tag = element as? JsonObject ?: return null
        val category = tag.string("category")?.let { name ->
            TagCategory.entries.firstOrNull { it.name == name.uppercase() }
        } ?: return null
        val label = tag.string("label")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (label.length > ImageTag.MAX_FIELD_LENGTH) return null
        val rawSource = tag.string("source")?.let { name ->
            TagSource.entries.firstOrNull { it.name == name.uppercase() }
        } ?: return null
        val name = tag.string("name")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { it.length <= ImageTag.MAX_FIELD_LENGTH }
        // The model may propose a tag as manual, but only a person makes a manual one: a model's claim is at
        // best what it recognised, which is exactly what APPEARANCE means.
        var source = if (rawSource == TagSource.MANUAL) TagSource.APPEARANCE else rawSource
        // A specific name is TEXT only when the caption or page text states it. Anything else the model
        // recognised from the pixels is uncertain, and is stored — and later shown — as such.
        if (source == TagSource.TEXT && name != null && !context.contains(normalized(name))) {
            source = TagSource.APPEARANCE
        }
        if (source == TagSource.APPEARANCE && !identifyAppearance) {
            return ImageTag(category, label, null, source)
        }
        return ImageTag(category, label, name, source)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * [text] cut to at most [limit] characters at a word boundary, so a description never ends mid-word.
     *
     * A word longer than the whole limit is cut at the limit: there is no boundary to prefer.
     */
    internal fun cutAtWord(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = text.substring(0, limit)
        val boundary = head.lastIndexOfAny(charArrayOf(' ', '\t', '\n', '\r'))
        return (if (boundary > 0) head.substring(0, boundary) else head).trimEnd()
    }

    /** [text] with case and combining marks removed, so `Uppsala` finds `Uppsala` and `Uppsalå`. */
    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
