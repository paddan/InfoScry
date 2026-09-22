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
import com.github.ajalt.clikt.parameters.types.path
import infoscry.config.AppPaths
import infoscry.llm.AnthropicClient
import infoscry.llm.LlmCapabilityProbe
import infoscry.llm.LlmError
import infoscry.llm.LlmEvent
import infoscry.llm.LlmMessage
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmPromptRole.ASK
import infoscry.llm.LlmPromptRole.INVESTIGATE
import infoscry.llm.LlmProvider
import infoscry.llm.LlmRequest
import infoscry.llm.LlmStreamingClient
import infoscry.llm.OpenAiCompatibleClient
import infoscry.llm.RetryPolicy
import infoscry.llm.ToolDefinition
import infoscry.server.ApiJson
import infoscry.storage.Instants
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable

/**
 * `infoscry llm` — configure the secret-free LLM profiles and the per-role defaults.
 *
 * Each subcommand opens the local data directory directly and holds its process lock, so it cannot
 * run alongside a running server. That is the Task 19 boundary: remote, server-backed profile
 * management (and a real capability call over the wire) belongs to Task 23's settings routes. Until
 * then `test` reports a typed "adapter not wired" result rather than throwing, per the plan.
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
    val profiles: List<LlmProfile> = emptyList(),
)

/** The typed result of a profile probe: what the real two-request probe measured. */
@Serializable
data class LlmTestJson(
    val profileName: String,
    val textRequestSupported: Boolean,
    val toolCallingSupported: Boolean?,
)

/** `infoscry llm list [--json]`. */
class ListLlmProfilesCommand : CliktCommand(name = "list") {

    private val jsonFlag by option("--json", help = JSON_HELP).flag()
    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, json = jsonFlag, dataDir = dataDirOption)
        try {
            infoscry.AppContext.open(AppPaths.of(options.dataDir)).use { context ->
                val ask = context.llm.defaultProfileName(ASK)
                val investigate = context.llm.defaultProfileName(INVESTIGATE)
                val profiles = context.llm.list()
                if (options.json) {
                    echo(ApiJson.encodeToString(LlmProfilesJson(ask, investigate, profiles)))
                } else if (profiles.isEmpty()) {
                    echo("No LLM profiles configured.")
                } else {
                    profiles.forEach { echo(describe(it, ask, investigate)) }
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the LLM profiles could not be read", failure)
        }
    }

    private fun describe(profile: LlmProfile, ask: String?, investigate: String?): String = buildString {
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
    private val toolCalling by option("--tool-calling", help = "Declares tool calling (a checkbox, not a measurement)").flag()
    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, json = jsonFlag, dataDir = dataDirOption)
        val provider = parseProvider(providerText, name)
        val profile = LlmProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            provider = provider,
            model = model,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            inputPricePerMillion = inputPrice,
            outputPricePerMillion = outputPrice,
            cacheReadPricePerMillion = cacheReadPrice,
            enabled = true,
            endpoint = endpoint ?: "",
            apiKeyEnvironmentVariable = apiKeyEnv,
        )
        try {
            infoscry.AppContext.open(AppPaths.of(options.dataDir)).use { context ->
                val saved = context.llm.create(profile)
                if (options.json) {
                    echo(ApiJson.encodeToString(saved))
                } else {
                    echo("Added LLM profile '${saved.name}'.")
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the LLM profile could not be added", failure)
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
    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, json = false, dataDir = dataDirOption)
        val target = when {
            ask != null && investigate != null -> throw CliFailure("choose only one of --ask or --investigate")
            ask != null -> LlmPromptRole.ASK to ask!!
            investigate != null -> LlmPromptRole.INVESTIGATE to investigate!!
            else -> throw CliFailure("choose --ask or --investigate")
        }
        try {
            infoscry.AppContext.open(AppPaths.of(options.dataDir)).use { context ->
                val set = context.llm.setDefault(target.first, target.second)
                if (!set) {
                    throw CliFailure("no LLM profile named '${target.second}' exists")
                }
                echo("Default for ${target.first.name.lowercase()} is now '${target.second}'.")
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the LLM default could not be set", failure)
        }
    }
}

/** `infoscry llm test <name>` — the real two-request capability probe, persisted. */
class TestLlmProfileCommand : CliktCommand(name = "test") {

    private val profileArg by argument("name", help = "The profile to probe")
    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, json = false, dataDir = dataDirOption)
        infoscry.AppContext.open(AppPaths.of(options.dataDir)).use { context ->
            val profile = context.llm.findByName(profileArg)
                ?: throw CliFailure("no LLM profile named '" + profileArg + "' exists")
            val lookup: (String) -> String? = { variable -> System.getenv(variable) }
            val client = HttpClient(CIO)
            try {
                val adapter = when (profile.provider) {
                    LlmProvider.OPENAI_COMPATIBLE ->
                        OpenAiCompatibleClient(profile, lookup, client, RetryPolicy())
                    LlmProvider.ANTHROPIC ->
                        AnthropicClient(profile, lookup, client, RetryPolicy())
                }
                val textOk = probesText(adapter)
                val toolsOk = probesToolCalling(adapter)
                context.llm.recordCapability(
                    profile.name,
                    LlmCapabilityProbe(toolCallingSupported = toolsOk, checkedAt = Instants.now()),
                )
                echo(ApiJson.encodeToString(LlmTestJson(profile.name, textOk, toolsOk)))
            } finally {
                client.close()
            }
        }
    }

    /** One tiny request: does the endpoint answer and stream to completion? */
    private fun probesText(adapter: LlmStreamingClient): Boolean = try {
        runBlocking {
            adapter.stream(LlmRequest(messages = listOf(LlmMessage("user", "Say ok.")))).toList()
        }.any { event -> event is LlmEvent.TextDelta || event == LlmEvent.Completed }
    } catch (failure: LlmError) {
        false
    }

    /** One harmless tool request: does the endpoint ever request a call? */
    private fun probesToolCalling(adapter: LlmStreamingClient): Boolean = try {
        runBlocking {
            adapter.stream(
                LlmRequest(
                    messages = listOf(LlmMessage("user", "Call the ping tool.")),
                    tools = listOf(ToolDefinition(name = "ping", description = "Nothing but a reply.")),
                    requiredToolName = "ping",
                ),
            ).toList()
        }.any { event -> event is LlmEvent.ToolCallReady }
    } catch (failure: LlmError) {
        false
    }
}
