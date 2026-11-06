package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import infoscry.server.ApiJson
import infoscry.server.LlmProfileApiView
import infoscry.server.LlmProfileRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * `infoscry llm` — configure the secret-free LLM profiles and the per-role defaults, through the server.
 */
class LlmCommand : CliktCommand(name = "llm") {

    init {
        subcommands(
            ListLlmProfilesCommand(),
            AddLlmProfileCommand(),
            SetDefaultLlmCommand(),
            TestLlmProfileCommand(),
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echoFormattedHelp()
        }
    }
}

@Serializable
data class LlmProfilesJson(
    val askDefault: String? = null,
    val investigateDefault: String? = null,
    val profiles: List<LlmProfileApiView> = emptyList(),
)

/** The typed result of a profile probe: what the real two-request probe measured. */
@Serializable
data class LlmTestJson(
    val profileName: String,
    val textRequestSupported: Boolean,
    val toolCallingSupported: Boolean?,
)

/** Runs [block] against the server, turning a refusal into the message it carried. */
private fun <T> withServer(failure: String, block: suspend (ApiClient) -> T): T = try {
    CliSession.connect().use { session -> runBlocking { block(session.api) } }
} catch (refused: RemoteApiFailure) {
    throw CliFailure(refused.message.ifEmpty { failure }, refused)
}

/** The profile named [name], or a failure that says there is none. */
private suspend fun ApiClient.profileNamed(name: String): LlmProfileApiView =
    listLlmProfiles().profiles.firstOrNull { it.name == name }
        ?: throw CliFailure("no LLM profile named '$name' exists")

/** `infoscry llm list [--json]`. */
class ListLlmProfilesCommand : CliktCommand(name = "list") {

    private val jsonFlag by option("--json", help = JSON_HELP).flag()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, null)
        val listed = withServer("the LLM profiles could not be read") { it.listLlmProfiles() }
        val nameOf = { id: String? -> listed.profiles.firstOrNull { it.id == id }?.name }
        val ask = nameOf(listed.defaults[LlmPromptRole.ASK])
        val investigate = nameOf(listed.defaults[LlmPromptRole.INVESTIGATE])
        if (options.json) {
            echo(ApiJson.encodeToString(LlmProfilesJson(ask, investigate, listed.profiles)))
        } else if (listed.profiles.isEmpty()) {
            echo("No LLM profiles configured.")
        } else {
            listed.profiles.forEach { echo(describe(it, ask, investigate)) }
        }
    }

    private fun describe(profile: LlmProfileApiView, ask: String?, investigate: String?): String = buildString {
        append(profile.name)
        append("  [").append(profile.provider.name.lowercase()).append("]  ")
        append(profile.model)
        append("  context=").append(profile.contextWindow)
        append("  tool=").append(
            when {
                profile.capabilityCheckedAt == null -> "unknown"
                profile.toolCallingMeasured == true -> "supported"
                else -> "unsupported"
            },
        )
        append("  enabled=").append(profile.enabled)
        if (profile.name == ask) append("  (ask)")
        if (profile.name == investigate) append("  (investigate)")
    }
}

/** `infoscry llm add --name N --provider P --model M [options] [--json]`. */
class AddLlmProfileCommand : CliktCommand(name = "add") {

    private val jsonFlag by option("--json", help = JSON_HELP).flag()
    private val name by option("--name", help = "The profile's unique name").required()
    private val providerText by option("--provider", help = "openai-compatible | anthropic").required()
    private val model by option("--model", help = "The model identifier").required()
    private val endpoint by option("--endpoint", help = "OpenAI-compatible base URL (optional)")
    private val apiKeyEnv by option("--api-key-env", help = "Name of the env var holding the key")
    private val contextWindow by option("--context-window", help = "Model context window in tokens").int().default(128_000)
    private val maxOutputTokens by option("--max-output-tokens", help = "Max output tokens").int().default(4_096)
    private val inputPrice by option("--input-price", help = "USD per 1M input tokens").double().default(0.0)
    private val outputPrice by option("--output-price", help = "USD per 1M output tokens").double().default(0.0)
    private val cacheReadPrice by option("--cache-read-price", help = "USD per 1M cache-read tokens").double().default(0.0)
    private val disabled by option("--disabled", help = "Add the profile switched off").flag()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, null)
        val request = LlmProfileRequest(
            name = name,
            provider = parseProvider(providerText, name),
            endpoint = endpoint ?: "",
            model = model,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            inputPricePerMillion = inputPrice,
            outputPricePerMillion = outputPrice,
            cacheReadPricePerMillion = cacheReadPrice,
            enabled = !disabled,
            apiKeyEnvironmentVariable = apiKeyEnv,
        )
        val saved = withServer("the LLM profile could not be added") { it.createLlmProfile(request) }
        if (options.json) {
            echo(ApiJson.encodeToString(saved))
        } else {
            echo("Added LLM profile '${saved.name}'.")
        }
    }

    private fun parseProvider(text: String, profileName: String): LlmProvider = when (text.trim().lowercase()) {
        "openai", "openai-compatible" -> LlmProvider.OPENAI_COMPATIBLE
        "anthropic" -> LlmProvider.ANTHROPIC
        else -> throw CliFailure(
            "unknown LLM provider '$text' for profile '$profileName'; use openai-compatible or anthropic",
        )
    }
}

/** `infoscry llm set-default (--ask | --investigate) <name>`. */
class SetDefaultLlmCommand : CliktCommand(name = "set-default") {

    private val ask by option("--ask", help = "Profile name to use for Ask by default")
    private val investigate by option("--investigate", help = "Profile name to use for Investigate by default")

    override fun run() {
        val target = when {
            ask != null && investigate != null -> throw CliFailure("choose only one of --ask or --investigate")
            ask != null -> LlmPromptRole.ASK to ask!!
            investigate != null -> LlmPromptRole.INVESTIGATE to investigate!!
            else -> throw CliFailure("choose --ask or --investigate")
        }
        withServer("the LLM default could not be set") { api ->
            api.setLlmDefault(target.first, api.profileNamed(target.second).id)
        }
        echo("Default for ${target.first.name.lowercase()} is now '${target.second}'.")
    }
}

/** `infoscry llm test <name>` — the real two-request capability probe, recorded by the server. */
class TestLlmProfileCommand : CliktCommand(name = "test") {

    private val profileArg by argument("name", help = "The profile to probe")

    override fun run() {
        val probe = withServer("the LLM profile could not be probed") { api ->
            api.probeLlmProfile(api.profileNamed(profileArg).id)
        }
        echo(ApiJson.encodeToString(LlmTestJson(probe.profile.name, probe.textRequestSupported, probe.toolCallingSupported)))
    }
}
