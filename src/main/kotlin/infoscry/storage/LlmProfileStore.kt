package infoscry.storage

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.investigate.EffortLevel
import infoscry.investigate.EffortPreset
import infoscry.llm.LlmCapabilityProbe
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import infoscry.llm.ValidEnvironmentVariableName
import infoscry.llm.endpointCarriesUserInfo
import infoscry.llm.sanitizedEndpoint
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A profile name is already used (compared case-insensitively). */
class DuplicateLlmProfileNameException(val name: String) : ApiFaultException(
    ApiStatus.CONFLICT,
    "DUPLICATE_LLM_PROFILE_NAME",
    "an LLM profile named '$name' already exists",
) {
    // The client is told a profile name collided, not which name it tried — the name is already its own
    // request, so echoing it back here would add nothing a log doesn't already have.
    override fun clientMessage(): String = "an LLM profile with that name already exists"
}

/** The final profile cannot be deleted: Ask and Investigate need at least one to run against. */
class LastLlmProfileException : ApiFaultException(
    ApiStatus.CONFLICT,
    "LAST_LLM_PROFILE",
    "the last LLM profile cannot be deleted",
)

/**
 * Persistence for LLM profiles and per-role profile defaults. Prompt bodies are not stored here:
 * the shipped Default comes from [infoscry.llm.ShippedPrompts], and collection prompts are in
 * [CollectionPromptStore]. The immutable core prompt rules are code in [infoscry.llm.PromptService].
 *
 * Profiles carry the name of an environment variable, never a value, and the capability probe
 * (measured by Task 20's adapters) is stored separately from the profile's declared switch so a
 * checkbox can never masquerade as a measurement.
 */
class LlmProfileStore(private val database: Database) {

    // ---- Profiles ----

    fun list(): List<LlmProfile> = database.read { connection ->
        connection.prepareStatement(
            "SELECT id, name, provider, endpoint, model, api_key_environment_variable, " +
                "context_window, max_output_tokens, supports_tool_calling, " +
                "input_price_per_million, output_price_per_million, cache_read_price_per_million, " +
                "enabled, tool_calling_measured, capability_checked_at, effort_overrides_json " +
                "FROM llm_profiles ORDER BY name",
        ).use { statement ->
            statement.executeQuery().use { results ->
                mutableListOf<LlmProfile>().let { profiles ->
                    while (results.next()) profiles.add(readProfile(results))
                    profiles
                }
            }
        }
    }

    fun findByName(name: String): LlmProfile? = database.read { connection ->
        require(name.isNotBlank()) { "an LLM profile lookup needs a name" }
        connection.prepareStatement(
            "SELECT id, name, provider, endpoint, model, api_key_environment_variable, " +
                "context_window, max_output_tokens, supports_tool_calling, " +
                "input_price_per_million, output_price_per_million, cache_read_price_per_million, " +
                "enabled, tool_calling_measured, capability_checked_at, effort_overrides_json " +
                "FROM llm_profiles WHERE name = ? COLLATE NOCASE",
        ).use { statement ->
            statement.setString(1, name.trim())
            statement.executeQuery().use { results ->
                if (results.next()) readProfile(results) else null
            }
        }
    }

    fun findById(id: String): LlmProfile? = database.read { connection ->
        connection.prepareStatement(
            "SELECT id, name, provider, endpoint, model, api_key_environment_variable, " +
                "context_window, max_output_tokens, supports_tool_calling, " +
                "input_price_per_million, output_price_per_million, cache_read_price_per_million, " +
                "enabled, tool_calling_measured, capability_checked_at, effort_overrides_json FROM llm_profiles WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { results -> if (results.next()) readProfile(results) else null }
        }
    }

    fun create(profile: LlmProfile): LlmProfile {
        profile.validateEffortOverrides()
        require(profile.id.isNotBlank()) { "an LLM profile needs an id" }
        require(profile.maxOutputTokens < profile.contextWindow) {
            "LLM profile output token limit must be below its context window to leave room for input"
        }
        ValidEnvironmentVariableName.requireValid(profile.apiKeyEnvironmentVariable)
        require(!profile.model.isBlank()) { "LLM profiles need a model" }
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO llm_profiles (id, name, provider, endpoint, model, " +
                    "api_key_environment_variable, context_window, max_output_tokens, " +
                    "supports_tool_calling, input_price_per_million, output_price_per_million, " +
                    "cache_read_price_per_million, enabled, created_at, updated_at, effort_overrides_json) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, profile.id)
                statement.setString(2, profile.name.trim())
                statement.setString(3, profile.provider.name)
                statement.setString(4, profile.endpoint.takeIf { it.isNotBlank() })
                statement.setString(5, profile.model)
                statement.setString(6, profile.apiKeyEnvironmentVariable)
                statement.setInt(7, profile.contextWindow)
                statement.setInt(8, profile.maxOutputTokens)
                statement.setInt(9, profile.toolCallingSupported.asInt())
                statement.setDouble(10, profile.inputPricePerMillion)
                statement.setDouble(11, profile.outputPricePerMillion)
                statement.setDouble(12, profile.cacheReadPricePerMillion)
                statement.setInt(13, profile.enabled.asInt())
                statement.setString(14, Instants.now())
                statement.setString(15, Instants.now())
                statement.setString(16, profile.effortOverrides?.let { Json.encodeToString(it) })
                try {
                    statement.executeUpdate()
                } catch (failure: SQLException) {
                    if (failure.message?.contains("UNIQUE") == true) {
                        throw DuplicateLlmProfileNameException(profile.name)
                    }
                    throw failure
                }
            }
        }
        return profile.copy(name = profile.name.trim())
    }

    /** Updates editable profile fields while retaining the row identity and invalidating stale measurements. */
    fun update(id: String, profile: LlmProfile): LlmProfile? {
        profile.validateEffortOverrides()
        require(id.isNotBlank()) { "an LLM profile needs an id" }
        require(profile.maxOutputTokens < profile.contextWindow) {
            "LLM profile output token limit must be below its context window to leave room for input"
        }
        ValidEnvironmentVariableName.requireValid(profile.apiKeyEnvironmentVariable)
        require(profile.name.isNotBlank()) { "LLM profiles need a name" }
        require(profile.model.isNotBlank()) { "LLM profiles need a model" }
        return try {
            database.transaction { connection ->
                connection.prepareStatement(
                    "UPDATE llm_profiles SET name = ?, provider = ?, endpoint = ?, model = ?, " +
                        "api_key_environment_variable = ?, context_window = ?, max_output_tokens = ?, " +
                        "input_price_per_million = ?, output_price_per_million = ?, " +
                        "cache_read_price_per_million = ?, enabled = ?, " +
                        "tool_calling_measured = CASE WHEN provider IS NOT ? OR endpoint IS NOT ? OR model IS NOT ? THEN NULL ELSE tool_calling_measured END, " +
                        "capability_checked_at = CASE WHEN provider IS NOT ? OR endpoint IS NOT ? OR model IS NOT ? THEN NULL ELSE capability_checked_at END, " +
                        "updated_at = ?, effort_overrides_json = ? WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, profile.name.trim())
                    statement.setString(2, profile.provider.name)
                    statement.setString(3, profile.endpoint.takeIf { it.isNotBlank() })
                    statement.setString(4, profile.model)
                    statement.setString(5, profile.apiKeyEnvironmentVariable)
                    statement.setInt(6, profile.contextWindow)
                    statement.setInt(7, profile.maxOutputTokens)
                    statement.setDouble(8, profile.inputPricePerMillion)
                    statement.setDouble(9, profile.outputPricePerMillion)
                    statement.setDouble(10, profile.cacheReadPricePerMillion)
                    statement.setInt(11, profile.enabled.asInt())
                    statement.setString(12, profile.provider.name)
                    statement.setString(13, profile.endpoint.takeIf { it.isNotBlank() })
                    statement.setString(14, profile.model)
                    statement.setString(15, profile.provider.name)
                    statement.setString(16, profile.endpoint.takeIf { it.isNotBlank() })
                    statement.setString(17, profile.model)
                    statement.setString(18, Instants.now())
                    statement.setString(19, profile.effortOverrides?.let { Json.encodeToString(it) })
                    statement.setString(20, id)
                    statement.executeUpdate() > 0
                }
            }
        } catch (failure: SQLException) {
            if (failure.message?.contains("UNIQUE") == true) throw DuplicateLlmProfileNameException(profile.name)
            throw failure
        }.let { changed -> if (changed) findById(id) else null }
    }

    fun deleteById(id: String): Boolean = database.transaction { connection ->
        val lastRemaining = connection.prepareStatement("SELECT COUNT(*) FROM llm_profiles").use { statement ->
            statement.executeQuery().use { results -> results.next() && results.getInt(1) <= 1 }
        }
        if (lastRemaining) {
            // Only refuse when the id names that single profile; a delete of a non-existent id on a
            // one-profile store still reports "not found" below instead of "last profile".
            val targetExists = connection.prepareStatement("SELECT 1 FROM llm_profiles WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { results -> results.next() }
            }
            if (targetExists) throw LastLlmProfileException()
        }
        connection.prepareStatement("DELETE FROM llm_profiles WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeUpdate() > 0
        }
    }

    fun delete(name: String): Boolean = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM llm_profiles WHERE name = ? COLLATE NOCASE").use { statement ->
            statement.setString(1, name.trim())
            statement.executeUpdate() > 0
        }
    }

    /**
     * Records the measured capability for a profile. This is the result of Task 20's real probe, not
     * the profile's own declared switch, so an unchecked claim can never be stored as a measurement.
     */
    fun recordCapability(profileName: String, probe: LlmCapabilityProbe): Boolean =
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE llm_profiles SET tool_calling_measured = ?, capability_checked_at = ?, " +
                    "updated_at = ? WHERE name = ? COLLATE NOCASE",
            ).use { statement ->
                statement.setInt(1, probe.toolCallingSupported.asInt())
                statement.setString(2, probe.checkedAt)
                statement.setString(3, Instants.now())
                statement.setString(4, profileName.trim())
                statement.executeUpdate() > 0
            }
        }

    // ---- Per-role defaults ----

    fun defaultProfileName(role: LlmPromptRole): String? = database.read { connection ->
        connection.prepareStatement("SELECT p.name FROM app_defaults d JOIN llm_profiles p ON p.id = d.profile_id WHERE d.role = ?").use { statement ->
            statement.setString(1, role.name)
            statement.executeQuery().use { results ->
                if (results.next()) results.getString("name") else null
            }
        }
    }

    fun setDefault(role: LlmPromptRole, profileName: String): Boolean {
        require(profileName.isNotBlank()) { "a default needs a profile name" }
        val profile = findByName(profileName) ?: return false
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO app_defaults (id, role, profile_id) VALUES (?, ?, ?) " +
                    "ON CONFLICT(role) DO UPDATE SET profile_id = excluded.profile_id",
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setString(2, role.name)
                statement.setString(3, profile.id)
                statement.executeUpdate()
            }
        }
        return true
    }

    /** Sets a default by stable ID so a concurrent rename cannot redirect it to another profile. */
    fun setDefaultById(role: LlmPromptRole, profileId: String): LlmProfile? = database.transaction { connection ->
        val profile = connection.prepareStatement(
            "SELECT id, name, provider, endpoint, model, api_key_environment_variable, " +
                "context_window, max_output_tokens, supports_tool_calling, " +
                "input_price_per_million, output_price_per_million, cache_read_price_per_million, " +
                "enabled, tool_calling_measured, capability_checked_at, effort_overrides_json FROM llm_profiles WHERE id = ?",
        ).use { statement ->
            statement.setString(1, profileId)
            statement.executeQuery().use { results -> if (results.next()) readProfile(results) else null }
        } ?: return@transaction null
        connection.prepareStatement(
            "INSERT INTO app_defaults (id, role, profile_id) VALUES (?, ?, ?) " +
                "ON CONFLICT(role) DO UPDATE SET profile_id = excluded.profile_id",
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, role.name)
            statement.setString(3, profileId)
            statement.executeUpdate()
        }
        profile
    }

    private fun readProfile(results: ResultSet): LlmProfile {
        val storedEndpoint = results.getString("endpoint") ?: ""
        // A row written before the endpoint rules existed can still carry a credential in its URL, and the
        // profile type refuses such a value. Reading it through anyway would fail the whole listing — and any
        // screen or CLI command that lists profiles — while the credential stayed in the file, so the value
        // is read back with the credential removed, and the profile disabled until a person
        // reviews the address. No endpoint is ever returned with its userinfo component still on it.
        val credentialRemoved = endpointCarriesUserInfo(storedEndpoint)
        val enabled = results.getInt("enabled") != 0 && !credentialRemoved
        val measuredValue = results.getInt("tool_calling_measured")
        val measured = if (results.wasNull()) null else measuredValue != 0
        return LlmProfile(
            id = results.getString("id"),
            name = results.getString("name"),
            provider = LlmProvider.valueOf(results.getString("provider")),
            endpoint = if (credentialRemoved) sanitizedEndpoint(storedEndpoint) else storedEndpoint,
            model = results.getString("model"),
            apiKeyEnvironmentVariable = results.getString("api_key_environment_variable"),
            contextWindow = results.getInt("context_window"),
            maxOutputTokens = results.getInt("max_output_tokens"),
            inputPricePerMillion = results.getDouble("input_price_per_million"),
            outputPricePerMillion = results.getDouble("output_price_per_million"),
            cacheReadPricePerMillion = results.getDouble("cache_read_price_per_million"),
            enabled = enabled,
            toolCallingMeasured = measured,
            capabilityCheckedAt = results.getString("capability_checked_at"),
            effortOverrides = results.getString("effort_overrides_json")?.let { Json.decodeFromString<Map<EffortLevel, EffortPreset>>(it) },
        )
    }

    private fun Boolean.asInt(): Int = if (this) 1 else 0
}
