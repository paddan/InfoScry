package infoscry.storage

import infoscry.ask.CitationValidation
import infoscry.ask.CorrectionSnapshot
import infoscry.ask.Evidence
import infoscry.domain.CollectionId
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistence for one persistent conversation row and its Ask audit trail.
 *
 * `setConversationTitle` and `deleteConversation` are generic: an Ask answer and an Investigate
 * conversation are the same `conversations` row, so both routes delete and title through here.
 * [persistAsk] is Ask-specific — Investigate's own turn-by-turn writes are
 * [InvestigationLedgerStore]'s.
 */
class ConversationStore(private val database: Database, private val profiles: LlmProfileStore) {

    /**
     * Durable Ask snapshot; payloads contain source snippets but never credentials. Returns the
     * conversation id it generates, so the request path can act on the conversation it just created.
     */
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
    ): String {
        val conversationId = UUID.randomUUID().toString()
        val callId = UUID.randomUUID().toString()
        val persistedAnswer = correction?.answer ?: answer
        val promptVersion = profiles.effectivePromptVersion(LlmPromptRole.ASK)
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
        return conversationId
    }

    /**
     * Writes the one title field of a conversation whose first turn already completed. Best-effort
     * by design: it records no model call and touches no usage counter, so what the panel reports
     * stays the cost of the answer.
     */
    fun setConversationTitle(conversationId: String, title: String) {
        require(title.isNotBlank()) { "a conversation title must not be blank" }
        database.transaction { connection ->
            connection.prepareStatement("UPDATE conversations SET title = ? WHERE id = ?").use { statement ->
                statement.setString(1, title)
                statement.setString(2, conversationId)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Deletes one conversation, scoped by its collection. False when the id is unknown or belongs to
     * another collection, so the route can answer not-found without touching anything else.
     *
     * The schema's existing `ON DELETE CASCADE` removes the conversation's messages, model calls,
     * citations, tool calls, evidence ledger entries, request eligibility and omission rows, and
     * limit events with the conversation row.
     */
    fun deleteConversation(collectionId: CollectionId, conversationId: String): Boolean =
        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM conversations WHERE id = ? AND collection_id = ?").use { statement ->
                statement.setString(1, conversationId)
                statement.setString(2, collectionId.value)
                statement.executeUpdate() > 0
            }
        }

    private fun persistCitationAudit(
        connection: Connection,
        conversationId: String,
        callId: String,
        evidence: List<Evidence>,
        citations: CitationValidation,
    ) {
        connection.prepareStatement("INSERT INTO citations (id,model_call_id,conversation_id,source_unit_id,locator_json,snippet,validated,evidence_id,returned_id,supplied,invalid_marker,revision_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
            evidence.forEach { e ->
                citationRow(s, conversationId, callId, e.unitId, Json.encodeToString(e.locator), e.text, false, e.id, null, true, false, e.revisionId)
            }
            citations.valid.forEach { evidenceId ->
                val e = evidence.first { it.id == evidenceId }
                citationRow(s, conversationId, callId, e.unitId, Json.encodeToString(e.locator), e.text, true, evidenceId, evidenceId, false, false, e.revisionId)
            }
            // An invalid marker names no source at all, so it names no revision either.
            citations.invalid.forEach { id ->
                citationRow(s, conversationId, callId, "invalid:$id", "{}", "", false, null, id, false, true, null)
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
        revisionId: String?,
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
        statement.setString(12, revisionId)
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
}
