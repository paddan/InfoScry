package infoscry.storage

import infoscry.llm.LlmCapabilityProbe
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmPromptRole.ASK
import infoscry.llm.LlmPromptRole.INVESTIGATE
import infoscry.llm.LlmProvider
import infoscry.llm.ValidEnvironmentVariableName
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/** A profile name is already used (compared case-insensitively). */
class DuplicateLlmProfileNameException(val name: String) :
    IllegalStateException("an LLM profile named '$name' already exists")

/** The prompt override cache keeps one row per role: the compiler resolves the role by enum name. */
private val PROMPT_ROLE_COLUMN = "role"

/**
 * Persistence for LLM profiles, per-role defaults, and the user-editable prompt bodies.
 *
 * The shipped prompt defaults are resources, not rows: an override row in [prompt_overrides]
 * replaces the shipped body, and deleting the row restores it. The immutable core prompt rules are
 * code in [infoscry.llm.PromptService] and cannot be replaced here at all.
 *
 * Profiles carry the name of an environment variable, never a value, and the capability probe
 * (measured by Task 20's adapters) is stored separately from the profile's declared switch so a
 * checkbox can never masquerade as a measurement.
 */
class LlmStore(private val database: Database) {

    // ---- Profiles ----

    fun list(): List<LlmProfile> = database.read { connection ->
        connection.prepareStatement(
            "SELECT id, name, provider, endpoint, model, api_key_environment_variable, " +
                "context_window, max_output_tokens, supports_tool_calling, " +
                "input_price_per_million, output_price_per_million, cache_read_price_per_million, " +
                "enabled, tool_calling_measured, capability_checked_at " +
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
                "enabled, tool_calling_measured, capability_checked_at " +
                "FROM llm_profiles WHERE name = ? COLLATE NOCASE",
        ).use { statement ->
            statement.setString(1, name.trim())
            statement.executeQuery().use { results ->
                if (results.next()) readProfile(results) else null
            }
        }
    }

    fun create(profile: LlmProfile): LlmProfile {
        require(profile.id.isNotBlank()) { "an LLM profile needs an id" }
        ValidEnvironmentVariableName.requireValid(profile.apiKeyEnvironmentVariable)
        require(!profile.model.isBlank()) { "LLM profiles need a model" }
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO llm_profiles (id, name, provider, endpoint, model, " +
                    "api_key_environment_variable, context_window, max_output_tokens, " +
                    "supports_tool_calling, input_price_per_million, output_price_per_million, " +
                    "cache_read_price_per_million, enabled, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
        return profile
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

    // ---- Prompt bodies (layer 2) ----

    /** The effective body for a role: the user's override, or the shipped default resource. */
    fun effectiveBody(role: LlmPromptRole): String = database.read { connection ->
        val override = connection.prepareStatement(
            "SELECT body FROM prompt_overrides WHERE role = ?",
        ).use { statement ->
            statement.setString(1, role.name)
            statement.executeQuery().use { results ->
                if (results.next()) results.getString("body") else null
            }
        }
        override ?: shippedPromptBody(role)
    }

    fun savePromptBody(role: LlmPromptRole, body: String): String {
        require(body.isNotBlank()) { "a prompt body must not be blank" }
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO prompt_overrides (role, body, version, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(role) DO UPDATE SET body = excluded.body, version = prompt_overrides.version + 1, " +
                    "updated_at = excluded.updated_at",
            ).use { statement ->
                statement.setString(1, role.name)
                statement.setString(2, body)
                statement.setInt(3, 1)
                statement.setString(4, Instants.now())
                statement.executeUpdate()
            }
        }
        return body
    }

    fun resetPromptBody(role: LlmPromptRole) {
        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM prompt_overrides WHERE role = ?").use { statement ->
                statement.setString(1, role.name)
                statement.executeUpdate()
            }
        }
    }

    /** The shipped default body for a role, read from the classpath. */
    private fun shippedPromptBody(role: LlmPromptRole): String {
        val resource = "prompts/" + role.name.lowercase() + ".md"
        return javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() }
            ?: error("prompt resource $resource is missing from the classpath")
    }

    private val javaClass: Class<LlmStore> get() = LlmStore::class.java

    private fun readProfile(results: ResultSet): LlmProfile = LlmProfile(
        id = results.getString("id"),
        name = results.getString("name"),
        provider = LlmProvider.valueOf(results.getString("provider")),
        endpoint = results.getString("endpoint") ?: "",
        model = results.getString("model"),
        apiKeyEnvironmentVariable = results.getString("api_key_environment_variable"),
        contextWindow = results.getInt("context_window"),
        maxOutputTokens = results.getInt("max_output_tokens"),
        inputPricePerMillion = results.getDouble("input_price_per_million"),
        outputPricePerMillion = results.getDouble("output_price_per_million"),
        cacheReadPricePerMillion = results.getDouble("cache_read_price_per_million"),
        enabled = results.getInt("enabled") != 0,
        toolCallingMeasured = if (results.wasNull()) null else results.getInt("tool_calling_measured") != 0,
        capabilityCheckedAt = results.getString("capability_checked_at"),
    )

    private fun Boolean.asInt(): Int = if (this) 1 else 0
}