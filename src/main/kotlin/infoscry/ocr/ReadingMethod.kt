package infoscry.ocr

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
    data class Llm(val profileId: String) : ReadingMethod {
        init { require(profileId.isNotBlank()) { "an LLM reading method needs a profile id" } }
        override val id: String get() = "llm:$profileId"
    }

    companion object {
        /** Parse the stable API id, refusing unknown methods and incomplete LLM ids. */
        fun parse(id: String): ReadingMethod = when {
            id == Tesseract.id -> Tesseract
            id == Surya.id -> Surya
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

class ReadingMethodUnavailableException(val reason: String) : IllegalStateException(reason)

/** The methods a collection may choose, shared by previews and admitted starts. */
interface ReadingMethodCatalog {
    fun availability(collectionId: infoscry.domain.CollectionId): List<MethodAvailability>

    fun require(collectionId: infoscry.domain.CollectionId, method: ReadingMethod): MethodAvailability {
        val option = availability(collectionId).firstOrNull { it.method == method }
            ?: throw ReadingMethodUnavailableException("that reading method is not available")
        if (!option.available) throw ReadingMethodUnavailableException(checkNotNull(option.unavailableReason))
        return option
    }
}
