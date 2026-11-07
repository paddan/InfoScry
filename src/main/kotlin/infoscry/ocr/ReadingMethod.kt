package infoscry.ocr

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A reading choice binds an engine and its profile, when it has one, as a single value. */
@Serializable(with = ReadingMethodSerializer::class)
sealed interface ReadingMethod {
    val id: String

    data object Tesseract : ReadingMethod { override val id: String = "tesseract" }
    data object Surya : ReadingMethod { override val id: String = "surya" }

    /** No OCR: every page keeps the text the document itself carries, and nothing is rendered or read. */
    data object None : ReadingMethod { override val id: String = "none" }

    /**
     * Rescan only: no engine, no cost, and no page or text layer is read. Every page's published text is carried
     * forward unchanged and only the image step runs, so turning images on for a document never repeats OCR.
     * Imports, retries and collection defaults refuse it.
     */
    data object KeepText : ReadingMethod { override val id: String = "keep" }
    data class Llm(val profileId: String) : ReadingMethod {
        init { require(profileId.isNotBlank()) { "an LLM reading method needs a profile id" } }
        override val id: String get() = "llm:$profileId"
    }

    companion object {
        /** Parse the stable API id, refusing unknown methods and incomplete LLM ids. */
        fun parse(id: String): ReadingMethod = when {
            id == Tesseract.id -> Tesseract
            id == Surya.id -> Surya
            id == None.id -> None
            id == KeepText.id -> KeepText
            id.startsWith("llm:") && id.substringAfter(':').isNotBlank() -> Llm(id.substringAfter(':'))
            else -> throw IllegalArgumentException("unknown reading method")
        }
    }
}

object ReadingMethodSerializer : KSerializer<ReadingMethod> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ReadingMethod", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ReadingMethod) = encoder.encodeString(value.id)

    override fun deserialize(decoder: Decoder): ReadingMethod = ReadingMethod.parse(decoder.decodeString())
}

data class MethodAvailability(
    val method: ReadingMethod,
    val label: String,
    val destination: String,
    val available: Boolean,
    val unavailableReason: String?,
    val external: Boolean,
) {
    init {
        require(available == (unavailableReason == null)) {
            "unavailableReason must be present exactly when the method is unavailable"
        }
    }
}

class ReadingMethodUnavailableException(val reason: String) : ApiFaultException(ApiStatus.CONFLICT, "METHOD_UNAVAILABLE", reason)

/** The methods a collection may choose, shared by previews and admitted starts. */
interface ReadingMethodCatalog {
    fun availability(collectionId: infoscry.domain.CollectionId): List<MethodAvailability>

    /** The methods a rescan offers: those an import offers, and *Keep current text*, which only a rescan accepts. */
    fun rescanAvailability(collectionId: infoscry.domain.CollectionId): List<MethodAvailability> =
        availability(collectionId) + KEEP_CURRENT_TEXT

    /** [require] for a rescan, which also accepts [ReadingMethod.KeepText]. */
    fun requireForRescan(collectionId: infoscry.domain.CollectionId, method: ReadingMethod): MethodAvailability =
        if (method == ReadingMethod.KeepText) KEEP_CURRENT_TEXT else require(collectionId, method)

    /** The method an import, a retry or a collection default chooses; [ReadingMethod.KeepText] is never one. */
    fun require(collectionId: infoscry.domain.CollectionId, method: ReadingMethod): MethodAvailability {
        val option = availability(collectionId).firstOrNull { it.method == method }
            ?: throw ReadingMethodUnavailableException("that reading method is not available")
        if (!option.available) throw ReadingMethodUnavailableException(checkNotNull(option.unavailableReason))
        return option
    }
}

/** *Keep current text*: always available, runs nowhere and sends nothing. Whether images are on is a rescan's own check. */
val KEEP_CURRENT_TEXT = MethodAvailability(
    method = ReadingMethod.KeepText,
    label = "Keep current text",
    destination = "this machine",
    available = true,
    unavailableReason = null,
    external = false,
)
