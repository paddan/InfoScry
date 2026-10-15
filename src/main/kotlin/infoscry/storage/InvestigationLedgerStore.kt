package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.investigate.EvidenceLedgerSnapshot
import infoscry.investigate.InvestigateHistory
import infoscry.llm.LlmMessage
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.llm.ResolvedPrompt
import infoscry.llm.ToolCall
import infoscry.llm.endpointCarriesUserInfo
import infoscry.llm.sanitizedEndpoint
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Persistence for Investigate's turn-by-turn ledger: conversations, messages, model calls, tool
 * calls, the evidence ledger, request eligibility and omissions, and limit events (Task 4b-2).
 *
 * Profile lookups go through [profiles] rather than a second copy of its query, so there is one
 * place that decodes an `llm_profiles` row.
 */
class InvestigationLedgerStore(private val database: Database, private val profiles: LlmProfileStore) {

    fun persistInvestigateConversation(
        collectionId: CollectionId,
        profile: LlmProfile,
        mode: String = "INVESTIGATE",
        prompt: ResolvedPrompt,
        retrievalSnapshot: String,
    ): String {
        val conversationId = UUID.randomUUID().toString()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO conversations (id,collection_id,mode,profile_provider,profile_endpoint,profile_model,profile_name,prompt_version,prompt_id,prompt_name,prompt_body,retrieval_snapshot,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { s ->
                s.setString(1, conversationId); s.setString(2, collectionId.value)
                s.setString(3, mode); s.setString(4, profile.provider.name)
                s.setString(5, profile.endpoint.takeIf { it.isNotBlank() })
                s.setString(6, profile.model); s.setString(7, profile.name)
                s.setString(8, prompt.version.toString()); s.setString(9, prompt.id)
                s.setString(10, prompt.name); s.setString(11, prompt.body)
                s.setString(12, retrievalSnapshot)
                s.setString(13, Instants.now()); s.executeUpdate()
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

    fun persistInvestigateMessage(
        conversationId: String,
        seq: Int,
        message: LlmMessage,
        /** True for model output superseded by a corrected adopted answer; excluded from conversation views. */
        superseded: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO messages (id,conversation_id,seq,role,content,tool_calls_json,tool_call_id,superseded,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            ).use { s ->
                s.setString(1, id); s.setString(2, conversationId); s.setInt(3, seq)
                s.setString(4, message.role); s.setString(5, message.content)
                s.setString(6, message.toolCalls.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) })
                s.setString(7, message.toolCallId); s.setInt(8, if (superseded) 1 else 0); s.setString(9, Instants.now())
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
        /** The seq of the assistant tool-call exchange that introduced this evidence; null for legacy rows. */
        messageSeq: Int? = null,
        /** The revision the excerpt was read from; null when that cannot be proven ("revision unknown"). */
        revisionId: String? = null,
    ) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO evidence_ledger (conversation_id,evidence_id,source_unit_id,locator_json,excerpt,message_seq,revision_id) VALUES (?,?,?,?,?,?,?) ON CONFLICT(conversation_id,evidence_id) DO NOTHING",
            ).use { s ->
                s.setString(1, conversationId); s.setString(2, evidenceId)
                s.setString(3, sourceUnitId); s.setString(4, locatorJson)
                s.setString(5, excerpt); setIntOrNull(s, 6, messageSeq)
                s.setString(7, revisionId); s.executeUpdate()
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
     * cannot be continued and reads back as null. A snapshot that had to be repaired reads back as a
     * switched-off profile, because the address this conversation locked is not one anything may
     * dispatch to until somebody reviews it — whoever fixed the profile row in the meantime.
     *
     * "Had to be repaired" means the stored address carries `userinfo`, which no write path stores, so it
     * is a row that something wrote around the application.
     */
    fun loadInvestigateHistory(conversationId: String): InvestigateHistory? = database.read { connection ->
        data class ConversationRow(
            val collectionId: String,
            val mode: String,
            val profileProvider: String,
            val profileEndpoint: String?,
            val profileModel: String,
            val profileName: String,
            val promptId: String?,
            val promptName: String,
            val promptVersion: Int,
            val promptBody: String,
            val retrievalSnapshot: String,
        )

        val conversation = connection.prepareStatement(
            "SELECT collection_id, mode, profile_provider, profile_endpoint, profile_model, profile_name, prompt_id, prompt_name, prompt_version, prompt_body, retrieval_snapshot FROM conversations WHERE id = ?",
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
                    promptId = results.getString("prompt_id"),
                    promptName = results.getString("prompt_name"),
                    promptVersion = results.getInt("prompt_version"),
                    promptBody = results.getString("prompt_body"),
                    retrievalSnapshot = results.getString("retrieval_snapshot"),
                )
            }
        }
        if (conversation.mode != "INVESTIGATE") return@read null

        val profile = profiles.findByName(conversation.profileName) ?: return@read null

        data class StoredMessage(val seq: Int, val message: LlmMessage, val superseded: Boolean)

        val storedRows = connection.prepareStatement(
            "SELECT seq, role, content, tool_calls_json, tool_call_id, superseded FROM messages WHERE conversation_id = ? ORDER BY seq",
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        val calls = results.getString("tool_calls_json")?.let { Json.decodeFromString<List<ToolCall>>(it) }
                            ?: emptyList()
                        add(StoredMessage(
                            seq = results.getInt("seq"),
                            message = LlmMessage(
                                results.getString("role"), results.getString("content"), calls,
                                results.getString("tool_call_id"),
                            ),
                            superseded = results.getInt("superseded") != 0,
                        ))
                    }
                }
            }
        }
        // Version 6 stored only role/content. Its tool exchange has no call IDs and cannot be
        // sent to either provider. Preserve the surrounding user and assistant turns instead.
        // A superseded draft answer is excluded from the provider view: only the adopted answer of
        // each completed turn is replayed. Legacy rows default to not-superseded, so an ambiguous
        // old duplicate pair stays visible rather than being silently collapsed.
        val visibleMessages = storedRows.filterIndexed { index, row ->
            val message = row.message
            val legacyTool = message.role == "tool" && message.toolCallId == null
            val legacyCall = message.role == "assistant" && message.content.isEmpty() &&
                message.toolCalls.isEmpty() && storedRows.getOrNull(index + 1)?.message?.let {
                    it.role == "tool" && it.toolCallId == null
                } == true
            !legacyTool && !legacyCall && !row.superseded
        }
        val messages = visibleMessages.map { it.message }
        val messageSeqs = visibleMessages.map { it.seq }
        // Rows written without an introducing seq (`message_seq` NULL) carry none. Recover it from the
        // visible tool result that names the evidence id, so a reopened conversation without it
        // keeps the same citation eligibility as one that recorded it. An entry whose
        // tool exchange was dropped from provider history stays unassociated and ineligible.
        val legacyEvidenceSeqs = visibleMessages
            .filter { it.message.role == "tool" && it.message.toolCallId != null }
            .flatMap { row -> evidenceIdsInPayload(row.message.content).map { it to row.seq } }
            .toMap()
        val nextMessageSeq = connection.prepareStatement(
            "SELECT COALESCE(MAX(seq), -1) + 1 FROM messages WHERE conversation_id = ?",
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.executeQuery().use { results -> results.next(); results.getInt(1) }
        }

        val evidence = connection.prepareStatement(
            "SELECT e.evidence_id, e.source_unit_id, e.locator_json, e.excerpt, e.message_seq, e.revision_id, " +
                "COALESCE(historical_document.id, current_document.id, unversioned_document.id) AS document_id " +
                "FROM evidence_ledger e JOIN conversations c ON c.id = e.conversation_id " +
                "LEFT JOIN content_units u ON u.id = e.source_unit_id " +
                "LEFT JOIN documents current_document ON current_document.id = u.document_id " +
                "AND current_document.collection_id = c.collection_id " +
                "LEFT JOIN page_text_revisions p ON p.revision_id = e.revision_id AND p.unit_id = e.source_unit_id " +
                "LEFT JOIN document_revisions r ON r.id = p.revision_id " +
                "LEFT JOIN documents historical_document ON historical_document.id = r.document_id " +
                "AND historical_document.collection_id = c.collection_id " +
                "LEFT JOIN (SELECT p.unit_id, MIN(r.document_id) AS document_id " +
                "FROM page_text_revisions p JOIN document_revisions r ON r.id = p.revision_id " +
                "GROUP BY p.unit_id HAVING COUNT(DISTINCT r.document_id) = 1) unique_historical_unit " +
                "ON unique_historical_unit.unit_id = e.source_unit_id AND e.revision_id IS NULL " +
                "LEFT JOIN documents unversioned_document ON unversioned_document.id = unique_historical_unit.document_id " +
                "AND unversioned_document.collection_id = c.collection_id " +
                "WHERE e.conversation_id = ? ORDER BY e.evidence_id",
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        val rawMessageSeq = results.getInt("message_seq")
                        val messageSeq = if (results.wasNull()) null else rawMessageSeq
                        add(
                            EvidenceLedgerSnapshot(
                                evidenceId = results.getString("evidence_id"),
                                sourceUnitId = results.getString("source_unit_id"),
                                locatorJson = results.getString("locator_json"),
                                excerpt = results.getString("excerpt"),
                                messageSeq = messageSeq,
                                revisionId = results.getString("revision_id"),
                                documentId = results.getString("document_id") ?: "",
                            ),
                        )
                    }
                }
            }
        }

        val resolvedEvidence = evidence.map { entry ->
            if (entry.messageSeq != null) entry else entry.copy(messageSeq = legacyEvidenceSeqs[entry.evidenceId])
        }

        // The snapshot is what the next turn dispatches to, so a snapshot that had to be repaired leaves the
        // conversation's profile switched off exactly as a repaired `llm_profiles` row is. Computed from the
        // snapshot rather than from the row the profile was looked up through, because the two disagree in the
        // case that matters: a person may have fixed the profile after the conversation was created, which
        // leaves the row clean and enabled while the address this conversation locked is still the repaired
        // one. Nothing dispatches to a repaired address until somebody reviews it, so the gate has to see this
        // whatever the current row says.
        val repairedSnapshot = endpointCarriesUserInfo(conversation.profileEndpoint ?: "")

        InvestigateHistory(
            collectionId = CollectionId(conversation.collectionId),
            profile = profile.copy(
                provider = LlmProvider.valueOf(conversation.profileProvider),
                // The snapshot goes back through the profile type, which refuses a URL carrying credentials, so
                // a row that somehow still holds one is repaired rather than allowed to fail the reopen. A clean
                // address is returned exactly as it was stored, which is what sanitising does when there is
                // nothing to remove.
                endpoint = sanitizedEndpoint(conversation.profileEndpoint ?: ""),
                model = conversation.profileModel,
                name = conversation.profileName,
                // Off for a repaired snapshot, and reported that way to `requireDispatchable`, whose refusal
                // then names the address this conversation stored.
                enabled = profile.enabled && !repairedSnapshot,
            ),
            prompt = ResolvedPrompt(conversation.promptId, conversation.promptName, conversation.promptVersion, conversation.promptBody),
            retrievalSnapshot = conversation.retrievalSnapshot,
            messages = messages,
            evidenceIds = resolvedEvidence.map { it.evidenceId },
            evidence = resolvedEvidence,
            messageSeqs = messageSeqs,
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

    private fun setIntOrNull(s: java.sql.PreparedStatement, idx: Int, value: Int?) {
        if (value != null) s.setInt(idx, value) else s.setNull(idx, java.sql.Types.INTEGER)
    }

    /**
     * Every `evidenceId` string value in a tool payload, at any depth. Used only to recover the
     * introducing exchange of legacy evidence rows that predate the durable `message_seq` column.
     */
    private fun evidenceIdsInPayload(content: String): List<String> = try {
        collectEvidenceIds(Json.parseToJsonElement(content))
    } catch (_: Exception) {
        emptyList()
    }

    private fun collectEvidenceIds(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            if (key == "evidenceId" && value is JsonPrimitive && value.isString) {
                listOf(value.content)
            } else {
                collectEvidenceIds(value)
            }
        }
        is JsonArray -> element.flatMap(::collectEvidenceIds)
        else -> emptyList()
    }
}
