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
import infoscry.ask.Evidence
import infoscry.ask.CitationValidation
import infoscry.ask.CorrectionSnapshot
import infoscry.domain.CollectionId
import infoscry.investigate.InvestigateHistory
import infoscry.llm.LlmMessage
import infoscry.llm.ToolCall
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A profile name is already used (compared case-insensitively). */
class DuplicateLlmProfileNameException(val name: String) :
    IllegalStateException("an LLM profile named '$name' already exists")

/** The prompt override cache keeps one row per role: the compiler resolves the role by enum name. */
private val PROMPT_ROLE_COLUMN = "role"

/** The shipped default body is version 1; user overrides increment from there. */
private const val SHIPPED_PROMPT_VERSION = 1

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

    /** Durable Ask snapshot; payloads contain source snippets but never credentials. */
    fun persistAsk(
        collectionId: CollectionId,
        profile: LlmProfile,
        question: String,
        answer: String,
        evidence: List<Evidence>,
        initialUsage: infoscry.llm.TokenUsage,
        initialCitations: CitationValidation,
        retrievalSnapshot: String = "{}",
        correction: CorrectionSnapshot? = null,
    ) {
        val conversationId = UUID.randomUUID().toString()
        val callId = UUID.randomUUID().toString()
        val persistedAnswer = correction?.answer ?: answer
        val promptVersion = effectivePromptVersion(LlmPromptRole.ASK)
        database.transaction { connection ->
            connection.prepareStatement("INSERT INTO conversations (id,collection_id,mode,profile_provider,profile_endpoint,profile_model,profile_name,prompt_version,retrieval_snapshot,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)").use { s ->
                listOf(conversationId, collectionId.value, "ASK", profile.provider.name, profile.endpoint, profile.model, profile.name, promptVersion.toString(), retrievalSnapshot, Instants.now()).forEachIndexed { i, v -> s.setString(i + 1, v) }; s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO messages (id,conversation_id,seq,role,content,created_at) VALUES (?,?,?,?,?,?)").use { s ->
                listOf(UUID.randomUUID().toString() to question, UUID.randomUUID().toString() to persistedAnswer).forEachIndexed { i, pair -> s.setString(1,pair.first); s.setString(2,conversationId); s.setInt(3,i); s.setString(4,if(i==0) "user" else "assistant"); s.setString(5,pair.second); s.setString(6,Instants.now()); s.addBatch() }; s.executeBatch()
            }
            val initialCost = cost(profile, initialUsage)
            connection.prepareStatement("INSERT INTO model_calls (id,conversation_id,provider,endpoint,model,profile_name,prompt_version,requested_at,response_at,status,input_tokens,output_tokens,cache_read_tokens,cost_usd) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                listOf(callId,conversationId,profile.provider.name,profile.endpoint,profile.model,profile.name,promptVersion.toString(),Instants.now(),Instants.now(),"SUCCEEDED").forEachIndexed { i,v -> s.setString(i+1,v) }; s.setLong(11,initialUsage.inputTokens); s.setLong(12,initialUsage.outputTokens); s.setLong(13,initialUsage.cacheReadTokens); s.setDouble(14,initialCost); s.executeUpdate()
            }
            persistCitationAudit(connection, conversationId, callId, evidence, initialCitations)
            val correctionUsage = correction?.usage
            correction?.let { c ->
                val correctionId = UUID.randomUUID().toString(); val correctionCost = cost(profile, c.usage)
                connection.prepareStatement("INSERT INTO model_calls (id,conversation_id,provider,endpoint,model,profile_name,prompt_version,requested_at,response_at,status,input_tokens,output_tokens,cache_read_tokens,cost_usd,correction_of) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                    listOf(correctionId,conversationId,profile.provider.name,profile.endpoint,profile.model,profile.name,promptVersion.toString(),Instants.now(),Instants.now(),"SUCCEEDED").forEachIndexed { i,v -> s.setString(i+1,v) }; s.setLong(11,c.usage.inputTokens); s.setLong(12,c.usage.outputTokens); s.setLong(13,c.usage.cacheReadTokens); s.setDouble(14,correctionCost); s.setString(15,callId); s.executeUpdate()
                }
                persistCitationAudit(connection, conversationId, correctionId, evidence, c.citations)
            }
            val totalUsage = initialUsage + (correctionUsage ?: infoscry.llm.TokenUsage(0, 0))
            connection.prepareStatement("INSERT INTO usage_totals (profile_id,calls,input_tokens,output_tokens,cache_read_tokens,cost_usd) VALUES (?,?,?,?,?,?) ON CONFLICT(profile_id) DO UPDATE SET calls=usage_totals.calls+excluded.calls,input_tokens=usage_totals.input_tokens+excluded.input_tokens,output_tokens=usage_totals.output_tokens+excluded.output_tokens,cache_read_tokens=usage_totals.cache_read_tokens+excluded.cache_read_tokens,cost_usd=usage_totals.cost_usd+excluded.cost_usd").use { s -> s.setString(1,profile.id); s.setInt(2,if(correction == null) 1 else 2); s.setLong(3,totalUsage.inputTokens); s.setLong(4,totalUsage.outputTokens); s.setLong(5,totalUsage.cacheReadTokens); s.setDouble(6,initialCost + (correctionUsage?.let { cost(profile, it) } ?: 0.0)); s.executeUpdate() }
        }
    }

    private fun persistCitationAudit(
        connection: Connection,
        conversationId: String,
        callId: String,
        evidence: List<Evidence>,
        citations: CitationValidation,
    ) {
        connection.prepareStatement("INSERT INTO citations (id,model_call_id,conversation_id,source_unit_id,locator_json,snippet,validated,evidence_id,returned_id,supplied,invalid_marker) VALUES (?,?,?,?,?,?,?,?,?,?,?)").use { s ->
            evidence.forEach { e ->
                citationRow(s, conversationId, callId, e.unitId, Json.encodeToString(e.locator), e.text, false, e.id, null, true, false)
            }
            citations.valid.forEach { evidenceId ->
                val e = evidence.first { it.id == evidenceId }
                citationRow(s, conversationId, callId, e.unitId, Json.encodeToString(e.locator), e.text, true, evidenceId, evidenceId, false, false)
            }
            citations.invalid.forEach { id ->
                citationRow(s, conversationId, callId, "invalid:$id", "{}", "", false, null, id, false, true)
            }
            s.executeBatch()
        }
    }

    private fun citationRow(
        statement: java.sql.PreparedStatement,
        conversationId: String,
        callId: String,
        sourceUnitId: String,
        locator: String,
        snippet: String,
        validated: Boolean,
        evidenceId: String?,
        returnedId: String?,
        supplied: Boolean,
        invalidMarker: Boolean,
    ) {
        statement.setString(1, UUID.randomUUID().toString())
        statement.setString(2, callId)
        statement.setString(3, conversationId)
        statement.setString(4, sourceUnitId)
        statement.setString(5, locator)
        statement.setString(6, snippet)
        statement.setInt(7, if (validated) 1 else 0)
        statement.setString(8, evidenceId)
        statement.setString(9, returnedId)
        statement.setInt(10, if (supplied) 1 else 0)
        statement.setInt(11, if (invalidMarker) 1 else 0)
        statement.addBatch()
    }

    private fun cost(profile: LlmProfile, usage: infoscry.llm.TokenUsage): Double =
        usage.inputTokens / 1_000_000.0 * profile.inputPricePerMillion +
            usage.outputTokens / 1_000_000.0 * profile.outputPricePerMillion +
            usage.cacheReadTokens / 1_000_000.0 * profile.cacheReadPricePerMillion

    private operator fun infoscry.llm.TokenUsage.plus(other: infoscry.llm.TokenUsage) =
        infoscry.llm.TokenUsage(
            inputTokens + other.inputTokens,
            outputTokens + other.outputTokens,
            cacheReadTokens + other.cacheReadTokens,
        )

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

    fun findById(id: String): LlmProfile? = database.read { connection ->
        connection.prepareStatement(
            "SELECT id, name, provider, endpoint, model, api_key_environment_variable, " +
                "context_window, max_output_tokens, supports_tool_calling, " +
                "input_price_per_million, output_price_per_million, cache_read_price_per_million, " +
                "enabled, tool_calling_measured, capability_checked_at FROM llm_profiles WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { results -> if (results.next()) readProfile(results) else null }
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
        return profile.copy(name = profile.name.trim())
    }

    /** Updates editable profile fields while retaining the row identity and invalidating stale measurements. */
    fun update(id: String, profile: LlmProfile): LlmProfile? {
        require(id.isNotBlank()) { "an LLM profile needs an id" }
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
                        "updated_at = ? WHERE id = ?",
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
                    statement.setString(19, id)
                    statement.executeUpdate() > 0
                }
            }
        } catch (failure: SQLException) {
            if (failure.message?.contains("UNIQUE") == true) throw DuplicateLlmProfileNameException(profile.name)
            throw failure
        }.let { changed -> if (changed) findById(id) else null }
    }

    fun deleteById(id: String): Boolean = database.transaction { connection ->
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
                "enabled, tool_calling_measured, capability_checked_at FROM llm_profiles WHERE id = ?",
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

    /** The effective prompt version: the override's version, or 1 for the shipped default body. */
    fun effectivePromptVersion(role: LlmPromptRole): Int = database.read { connection ->
        connection.prepareStatement(
            "SELECT version FROM prompt_overrides WHERE role = ?",
        ).use { statement ->
            statement.setString(1, role.name)
            statement.executeQuery().use { results ->
                if (results.next()) results.getInt("version") else null
            }
        }
    } ?: SHIPPED_PROMPT_VERSION

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
                statement.setInt(3, SHIPPED_PROMPT_VERSION + 1)
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

    private fun readProfile(results: ResultSet): LlmProfile {
        val enabled = results.getInt("enabled") != 0
        val measuredValue = results.getInt("tool_calling_measured")
        val measured = if (results.wasNull()) null else measuredValue != 0
        return LlmProfile(
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
            enabled = enabled,
            toolCallingMeasured = measured,
            capabilityCheckedAt = results.getString("capability_checked_at"),
        )
    }

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    // ---- Investigate persistence (Task 4b-2) ----

    fun persistInvestigateConversation(
        collectionId: CollectionId,
        profile: LlmProfile,
        mode: String = "INVESTIGATE",
        promptVersion: Int,
        retrievalSnapshot: String,
    ): String {
        val conversationId = UUID.randomUUID().toString()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO conversations (id,collection_id,mode,profile_provider,profile_endpoint,profile_model,profile_name,prompt_version,retrieval_snapshot,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            ).use { s ->
                s.setString(1, conversationId); s.setString(2, collectionId.value)
                s.setString(3, mode); s.setString(4, profile.provider.name)
                s.setString(5, profile.endpoint.takeIf { it.isNotBlank() })
                s.setString(6, profile.model); s.setString(7, profile.name)
                s.setString(8, promptVersion.toString()); s.setString(9, retrievalSnapshot)
                s.setString(10, Instants.now()); s.executeUpdate()
            }
        }
        return conversationId
    }

    fun persistInvestigateMessage(
        conversationId: String,
        seq: Int,
        role: String,
        content: String,
    ): String = persistInvestigateMessage(conversationId, seq, LlmMessage(role, content))

    fun persistInvestigateMessage(conversationId: String, seq: Int, message: LlmMessage): String {
        val id = UUID.randomUUID().toString()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO messages (id,conversation_id,seq,role,content,tool_calls_json,tool_call_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
            ).use { s ->
                s.setString(1, id); s.setString(2, conversationId); s.setInt(3, seq)
                s.setString(4, message.role); s.setString(5, message.content)
                s.setString(6, message.toolCalls.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) })
                s.setString(7, message.toolCallId); s.setString(8, Instants.now())
                s.executeUpdate()
            }
        }
        return id
    }

    fun persistInvestigateModelCall(
        conversationId: String,
        provider: String,
        endpoint: String?,
        model: String,
        profileName: String,
        promptVersion: Int,
        status: String,
        inputTokens: Long?,
        outputTokens: Long?,
        cacheReadTokens: Long?,
        costUsd: Double,
        errorCode: String? = null,
        correctionOf: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO model_calls (id,conversation_id,provider,endpoint,model,profile_name,prompt_version,requested_at,response_at,status,input_tokens,output_tokens,cache_read_tokens,cost_usd,error_code,correction_of) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { s ->
                s.setString(1, id); s.setString(2, conversationId)
                s.setString(3, provider); s.setString(4, endpoint)
                s.setString(5, model); s.setString(6, profileName)
                s.setString(7, promptVersion.toString()); s.setString(8, Instants.now())
                s.setString(9, Instants.now()); s.setString(10, status)
                setLongOrNull(s, 11, inputTokens); setLongOrNull(s, 12, outputTokens)
                setLongOrNull(s, 13, cacheReadTokens); s.setDouble(14, costUsd)
                s.setString(15, errorCode); s.setString(16, correctionOf)
                s.executeUpdate()
            }
        }
        return id
    }

    fun persistInvestigateToolCall(
        conversationId: String,
        modelCallId: String,
        toolName: String,
        argumentsJson: String,
        resultCode: String,
        durationMs: Long,
    ) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO tool_calls (id,conversation_id,model_call_id,tool_name,arguments_json,result_code,duration_ms) VALUES (?,?,?,?,?,?,?)",
            ).use { s ->
                s.setString(1, UUID.randomUUID().toString()); s.setString(2, conversationId)
                s.setString(3, modelCallId); s.setString(4, toolName)
                s.setString(5, argumentsJson); s.setString(6, resultCode)
                s.setLong(7, durationMs); s.executeUpdate()
            }
        }
    }

    fun persistEvidenceLedgerEntry(
        conversationId: String,
        evidenceId: String,
        sourceUnitId: String,
        locatorJson: String,
        excerpt: String,
    ) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO evidence_ledger (conversation_id,evidence_id,source_unit_id,locator_json,excerpt) VALUES (?,?,?,?,?)",
            ).use { s ->
                s.setString(1, conversationId); s.setString(2, evidenceId)
                s.setString(3, sourceUnitId); s.setString(4, locatorJson)
                s.setString(5, excerpt); s.executeUpdate()
            }
        }
    }

    fun persistRequestEligibility(
        modelCallId: String,
        conversationId: String,
        evidenceIds: List<String>,
    ) {
        if (evidenceIds.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO request_eligibility (model_call_id,conversation_id,evidence_id) VALUES (?,?,?)",
            ).use { s ->
                evidenceIds.forEach { evidenceId ->
                    s.setString(1, modelCallId); s.setString(2, conversationId)
                    s.setString(3, evidenceId); s.addBatch()
                }
                s.executeBatch()
            }
        }
    }

    fun persistRequestOmissions(
        modelCallId: String,
        conversationId: String,
        groupLabels: List<String>,
    ) {
        if (groupLabels.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO request_omissions (model_call_id,conversation_id,group_label) VALUES (?,?,?)",
            ).use { s ->
                groupLabels.forEach { label ->
                    s.setString(1, modelCallId); s.setString(2, conversationId)
                    s.setString(3, label); s.addBatch()
                }
                s.executeBatch()
            }
        }
    }

    fun persistLimitEvent(
        conversationId: String,
        eventType: String,
        message: String,
    ) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO limit_events (id,conversation_id,event_type,message,created_at) VALUES (?,?,?,?,?)",
            ).use { s ->
                s.setString(1, UUID.randomUUID().toString()); s.setString(2, conversationId)
                s.setString(3, eventType); s.setString(4, message)
                s.setString(5, Instants.now()); s.executeUpdate()
            }
        }
    }

    /**
     * Rebuilds a conversation's [InvestigateHistory] so a `continue` can replay it: the locked
     * snapshot from its `conversations` row, the `messages` in `seq` order, and every evidence id
     * allocated in the `evidence_ledger`. Null for an unknown id and for any row whose `mode` is not
     * `INVESTIGATE`.
     *
     * The row keeps only the non-secret provider/endpoint/model/name snapshot, so the remaining
     * profile fields come from the live profile row looked up by name; the snapshot's own four
     * fields win when the live profile disagrees. A conversation whose profile no longer exists
     * cannot be continued and reads back as null.
     */
    fun loadInvestigateHistory(conversationId: String): InvestigateHistory? = database.read { connection ->
        data class ConversationRow(
            val collectionId: String,
            val mode: String,
            val profileProvider: String,
            val profileEndpoint: String?,
            val profileModel: String,
            val profileName: String,
            val promptVersion: Int,
            val retrievalSnapshot: String,
        )

        val conversation = connection.prepareStatement(
            "SELECT collection_id, mode, profile_provider, profile_endpoint, profile_model, profile_name, prompt_version, retrieval_snapshot FROM conversations WHERE id = ?",
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.executeQuery().use { results ->
                if (!results.next()) return@read null
                ConversationRow(
                    collectionId = results.getString("collection_id"),
                    mode = results.getString("mode"),
                    profileProvider = results.getString("profile_provider"),
                    profileEndpoint = results.getString("profile_endpoint"),
                    profileModel = results.getString("profile_model"),
                    profileName = results.getString("profile_name"),
                    promptVersion = results.getInt("prompt_version"),
                    retrievalSnapshot = results.getString("retrieval_snapshot"),
                )
            }
        }
        if (conversation.mode != "INVESTIGATE") return@read null

        val profile = connection.prepareStatement(
            "SELECT id, name, provider, endpoint, model, api_key_environment_variable, " +
                "context_window, max_output_tokens, supports_tool_calling, " +
                "input_price_per_million, output_price_per_million, cache_read_price_per_million, " +
                "enabled, tool_calling_measured, capability_checked_at " +
                "FROM llm_profiles WHERE name = ? COLLATE NOCASE",
        ).use { statement ->
            statement.setString(1, conversation.profileName)
            statement.executeQuery().use { results ->
                if (results.next()) readProfile(results) else null
            }
        } ?: return@read null

        val storedMessages = connection.prepareStatement(
            "SELECT role, content, tool_calls_json, tool_call_id FROM messages WHERE conversation_id = ? ORDER BY seq",
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        val calls = results.getString("tool_calls_json")?.let { Json.decodeFromString<List<ToolCall>>(it) }
                            ?: emptyList()
                        add(LlmMessage(
                            results.getString("role"), results.getString("content"), calls,
                            results.getString("tool_call_id"),
                        ))
                    }
                }
            }
        }
        // Version 6 stored only role/content. Its tool exchange has no call IDs and cannot be
        // sent to either provider. Preserve the surrounding user and assistant turns instead.
        val messages = storedMessages.filterIndexed { index, message ->
            val legacyTool = message.role == "tool" && message.toolCallId == null
            val legacyCall = message.role == "assistant" && message.content.isEmpty() &&
                message.toolCalls.isEmpty() && storedMessages.getOrNull(index + 1)?.let {
                    it.role == "tool" && it.toolCallId == null
                } == true
            !legacyTool && !legacyCall
        }
        val nextMessageSeq = connection.prepareStatement(
            "SELECT COALESCE(MAX(seq), -1) + 1 FROM messages WHERE conversation_id = ?",
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.executeQuery().use { results -> results.next(); results.getInt(1) }
        }

        val evidenceIds = connection.prepareStatement(
            "SELECT evidence_id FROM evidence_ledger WHERE conversation_id = ? ORDER BY evidence_id",
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(results.getString("evidence_id")) }
            }
        }

        InvestigateHistory(
            collectionId = CollectionId(conversation.collectionId),
            profile = profile.copy(
                provider = LlmProvider.valueOf(conversation.profileProvider),
                endpoint = conversation.profileEndpoint ?: "",
                model = conversation.profileModel,
                name = conversation.profileName,
            ),
            promptVersion = conversation.promptVersion,
            retrievalSnapshot = conversation.retrievalSnapshot,
            messages = messages,
            evidenceIds = evidenceIds,
            nextMessageSeq = nextMessageSeq,
        )
    }

    fun updateUsageTotals(
        profileId: String,
        calls: Int,
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long,
        costUsd: Double,
    ) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO usage_totals (profile_id,calls,input_tokens,output_tokens,cache_read_tokens,cost_usd) VALUES (?,?,?,?,?,?) ON CONFLICT(profile_id) DO UPDATE SET calls=usage_totals.calls+excluded.calls,input_tokens=usage_totals.input_tokens+excluded.input_tokens,output_tokens=usage_totals.output_tokens+excluded.output_tokens,cache_read_tokens=usage_totals.cache_read_tokens+excluded.cache_read_tokens,cost_usd=usage_totals.cost_usd+excluded.cost_usd",
            ).use { s ->
                s.setString(1, profileId); s.setInt(2, calls)
                s.setLong(3, inputTokens); s.setLong(4, outputTokens)
                s.setLong(5, cacheReadTokens); s.setDouble(6, costUsd)
                s.executeUpdate()
            }
        }
    }

    private fun setLongOrNull(s: java.sql.PreparedStatement, idx: Int, value: Long?) {
        if (value != null) s.setLong(idx, value) else s.setNull(idx, java.sql.Types.BIGINT)
    }
}
