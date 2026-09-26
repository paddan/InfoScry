package infoscry.server

import infoscry.llm.LlmModelCatalog
import infoscry.llm.LlmProvider
import infoscry.llm.ProviderCatalog
import infoscry.llm.ProviderCatalogData
import infoscry.llm.ValidEnvironmentVariableName
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException

/** One preset as the API serves it: everything a profile form needs, minus the static model fallback. */
@Serializable
data class LlmPresetApiView(
    val id: String,
    val label: String,
    val provider: LlmProvider,
    val endpoint: String,
    val apiKeyEnvironmentVariable: String?,
)

@Serializable
data class LlmPresetsResponse(val presets: List<LlmPresetApiView>)

/**
 * The read-only model-catalog surface: the provider presets a profile form offers, and one provider's
 * live model list. GET routes only, with nothing to mutate, so no CSRF or bearer token applies.
 */
fun Routing.configureLlmCatalogRoutes(
    catalog: LlmModelCatalog = LlmModelCatalog(),
    providerCatalog: ProviderCatalogData = ProviderCatalog.load(),
) {
    route("/api/llm/presets") {
        get {
            call.handle {
                call.respondJson(
                    HttpStatusCode.OK,
                    LlmPresetsResponse(providerCatalog.presets.map { preset ->
                        LlmPresetApiView(
                            id = preset.id,
                            label = preset.label,
                            provider = preset.provider,
                            endpoint = preset.endpoint,
                            apiKeyEnvironmentVariable = preset.apiKeyEnvironmentVariable,
                        )
                    }),
                )
            }
        }
    }

    route("/api/llm/catalog") {
        get {
            call.handle {
                val parameters = call.request.queryParameters
                val provider = parameters["provider"]?.let { raw ->
                    LlmProvider.entries.firstOrNull { it.name == raw }
                } ?: throw BadRequestException(
                    "provider must be OPENAI_COMPATIBLE or ANTHROPIC",
                )
                val endpoint = parameters["endpoint"]
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf { raw ->
                        val uri = try {
                            URI(raw)
                        } catch (failure: URISyntaxException) {
                            throw BadRequestException("endpoint must be an absolute http or https URL")
                        }
                        uri.isAbsolute && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
                    }
                    ?: throw BadRequestException("endpoint must be an absolute http or https URL")
                val apiKeyEnvironmentVariable = parameters["apiKeyEnvironmentVariable"]?.let { raw ->
                    if (raw.isNotBlank() && !ValidEnvironmentVariableName.matches(raw)) {
                        throw BadRequestException(
                            "apiKeyEnvironmentVariable must be a valid environment variable name",
                        )
                    }
                    raw.takeIf(String::isNotBlank)
                }
                // The response view is CatalogResponse: live metadata plus the always-usable fallback.
                call.respondJson(HttpStatusCode.OK, catalog.fetch(provider, endpoint, apiKeyEnvironmentVariable))
            }
        }
    }
}