package infoscry.ocr

import infoscry.domain.CollectionId
import infoscry.extract.TesseractOcr
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Production method list, including machine readiness and OCR profile readiness. */
class OcrReadingMethodCatalog(
    private val profiles: OcrProfileService,
    private val localUnavailableReason: (ReadingMethod) -> String? = ::localMethodUnavailableReason,
) : ReadingMethodCatalog {
    override fun availability(collectionId: CollectionId): List<MethodAvailability> {
        val local = listOf(
            local(ReadingMethod.Tesseract, "Tesseract"),
            local(ReadingMethod.Surya, "Surya"),
        )
        val llm = profiles.list().sortedBy { it.name.lowercase() }.map { profile ->
            val method = ReadingMethod.Llm(profile.id)
            val revision = profile.revision
            val host = runCatching { URI(revision.endpoint).host }.getOrNull()
                ?.takeIf(String::isNotBlank) ?: "api endpoint"
            val reason = when {
                !profile.enabled -> "This OCR profile is disabled."
                !profiles.keyAvailable(revision) -> "Key variable ${revision.apiKeyEnvironmentVariable} is not set."
                revision.imageCapabilityMeasured != true -> "This OCR profile has not passed its image check."
                else -> null
            }
            MethodAvailability(
                method = method,
                label = "LLM: ${profile.name}",
                destination = host,
                available = reason == null,
                unavailableReason = reason,
                external = revision.scope == OcrEndpointScope.EXTERNAL,
            )
        }
        return local + llm
    }

    private fun local(method: ReadingMethod, label: String): MethodAvailability {
        val reason = localUnavailableReason(method)
        return MethodAvailability(
            method = method,
            label = label,
            destination = "this machine",
            available = reason == null,
            unavailableReason = reason,
            external = false,
        )
    }
}

private fun localMethodUnavailableReason(method: ReadingMethod): String? = when (method) {
    ReadingMethod.Tesseract -> if (findExecutable(TesseractOcr.DEFAULT_EXECUTABLE) == null) {
        "Tesseract is not installed. ${TesseractOcr.installRemedy()}"
    } else {
        null
    }
    ReadingMethod.Surya -> if (SuryaOcr.configured() == null) {
        "The Surya model is not installed or configured. ${SuryaOcr.installRemedy()}"
    } else {
        null
    }
    is ReadingMethod.Llm -> null
}

private fun findExecutable(name: String): Path? = System.getenv("PATH")
    ?.split(java.io.File.pathSeparatorChar)
    ?.asSequence()
    ?.map { Path.of(it).resolve(name) }
    ?.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
