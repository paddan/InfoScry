package infoscry.server

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.SourceLocation
import infoscry.investigate.InvestigateEvent
import infoscry.investigate.InvestigatePersistence
import infoscry.investigate.InvestigateRequest
import infoscry.investigate.InvestigateTurnSnapshot
import infoscry.investigate.InvestigationService
import infoscry.investigate.InvestigationTools
import infoscry.llm.AnthropicClient
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import infoscry.llm.OpenAiCompatibleClient
import infoscry.llm.PromptService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** The request both create and continue accept; continue ignores collection/profile in favour of the locked snapshot. */
@Serializable
data class InvestigationApiRequest(val collection: String, val question: String, val profile: String)

@Serializable
data class CancelInvestigationResponse(val cancelled: Boolean = true)

@Serializable
data class InvestigationSummaryResponse(val id: String, val createdAt: String, val question: String)

@Serializable
data class InvestigationMessageResponse(val role: String, val text: String)

@Serializable
data class InvestigationActivityResponse(val name: String, val resultCode: String, val durationMs: Long)

@Serializable
data class InvestigationHistoryResponse(
    val id: String,
    val messages: List<InvestigationMessageResponse>,
    val evidence: List<EvidenceWire>,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val activity: List<InvestigationActivityResponse>,
)

fun Routing.configureInvestigationRoutes(context: AppContext) {
    // Running turns by conversation id, so POST .../cancel can stop the collecting coroutine. A turn
    // registers itself when Started carries its id and unregisters when the stream ends.
    val runningTurns = ConcurrentHashMap<String, Job>()
    val activeTurnIds = ConcurrentHashMap.newKeySet<String>()

    get("/api/collections/{id}/investigations") {
        call.handle {
            val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
            val investigations = context.database.read { connection ->
                connection.prepareStatement(
                    "SELECT c.id, c.created_at, COALESCE((SELECT m.content FROM messages m WHERE m.conversation_id = c.id AND m.role = 'user' ORDER BY m.seq LIMIT 1), '') AS question " +
                        "FROM conversations c WHERE c.collection_id = ? AND c.mode = 'INVESTIGATE' ORDER BY c.created_at DESC LIMIT 50",
                ).use { statement ->
                    statement.setString(1, collection.id.value)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(
                                InvestigationSummaryResponse(
                                    id = rows.getString("id"),
                                    createdAt = rows.getString("created_at"),
                                    question = rows.getString("question").take(180),
                                ),
                            )
                        }
                    }
                }
            }
            call.respondJson(HttpStatusCode.OK, mapOf("investigations" to investigations))
        }
    }

    get("/api/collections/{id}/investigations/{conversationId}") {
        call.handle {
            val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
            val conversationId = call.parameters["conversationId"]?.takeIf(String::isNotBlank)
                ?: throw BadRequestException("a conversation id is required in the path")
            val belongsToCollection = context.database.read { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM conversations WHERE id = ? AND collection_id = ? AND mode = 'INVESTIGATE'",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.setString(2, collection.id.value)
                    statement.executeQuery().use { it.next() }
                }
            }
            if (!belongsToCollection) throw NoSuchElementException("no investigation with id $conversationId exists in this collection")

            val messages = context.database.read { connection ->
                connection.prepareStatement(
                    "SELECT role, content FROM messages WHERE conversation_id = ? AND role IN ('user', 'assistant') AND (role = 'user' OR content <> '') ORDER BY seq",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(InvestigationMessageResponse(rows.getString("role"), rows.getString("content")))
                        }
                    }
                }
            }
            val evidence = context.database.read { connection ->
                connection.prepareStatement(
                    "SELECT e.evidence_id, e.source_unit_id, e.locator_json, u.document_id " +
                        "FROM evidence_ledger e JOIN content_units u ON u.id = e.source_unit_id " +
                        "JOIN documents d ON d.id = u.document_id " +
                        "WHERE e.conversation_id = ? AND d.collection_id = ? ORDER BY e.evidence_id",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.setString(2, collection.id.value)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                val locator = ApiJson.decodeFromString<SourceLocation>(rows.getString("locator_json"))
                                add(
                                    EvidenceWire(
                                        id = rows.getString("evidence_id"),
                                        documentId = rows.getString("document_id"),
                                        unitId = rows.getString("source_unit_id"),
                                        locator = locator,
                                        locatorLabel = locator.describe(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            val usageAndCost = context.database.read { connection ->
                connection.prepareStatement(
                    "SELECT COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0), COALESCE(SUM(cost_usd), 0) FROM model_calls WHERE conversation_id = ?",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        Triple(rows.getLong(1), rows.getLong(2), rows.getDouble(3))
                    }
                }
            }
            val activity = context.database.read { connection ->
                connection.prepareStatement(
                    "SELECT tool_name, result_code, duration_ms FROM tool_calls WHERE conversation_id = ? ORDER BY rowid LIMIT 200",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(
                                InvestigationActivityResponse(rows.getString("tool_name"), rows.getString("result_code"), rows.getLong("duration_ms")),
                            )
                        }
                    }
                }
            }
            val history = InvestigationHistoryResponse(
                id = conversationId,
                messages = messages,
                evidence = evidence,
                inputTokens = usageAndCost.first,
                outputTokens = usageAndCost.second,
                costUsd = usageAndCost.third,
                activity = activity,
            )
            call.respondJson(HttpStatusCode.OK, mapOf("investigation" to history))
        }
    }

    post("/api/investigations") {
        call.handle {
            val body = call.receiveJson<InvestigationApiRequest>()
            val collection = context.collectionService.requireActiveByNameOrId(body.collection)
            val profile = context.llm.findByName(body.profile) ?: throw NoSuchElementException("no such LLM profile")
            if (!profile.toolCallingSupported) {
                // The gate fires before the service is built, so no provider call and no conversation
                // row can exist for a profile whose tool capability was never measured as true.
                call.respondJson(
                    HttpStatusCode.UnprocessableEntity,
                    ApiErrorResponse(
                        ApiError(
                            code = "TOOL_CALLING_UNSUPPORTED",
                            message = "the profile '${profile.name}' has not been measured for tool calling",
                        ),
                    ),
                )
            } else {
                val collectionId = CollectionId(collection.id.value)
                call.streamInvestigation(
                    investigationService(context, collectionId, profile),
                    InvestigateRequest(collectionId, body.question, profile),
                    runningTurns,
                    activeTurnIds,
                )
            }
        }
    }

    post("/api/investigations/{id}/continue") {
        call.handle {
            val conversationId = call.conversationId()
            val body = call.receiveJson<InvestigationApiRequest>()
            val collection = context.collectionService.requireActiveByNameOrId(body.collection)
            val profile = context.llm.findByName(body.profile) ?: throw NoSuchElementException("no such LLM profile")
            // Tools stay scoped to the conversation's locked collection even when the request names a
            // different one; an unknown conversation still streams the service's CONVERSATION_NOT_FOUND.
            val lockedCollectionId = context.llm.loadInvestigateHistory(conversationId)?.collectionId
                ?: CollectionId(collection.id.value)
            if (!activeTurnIds.add(conversationId)) {
                call.respondJson(
                    HttpStatusCode.Conflict,
                    ApiErrorResponse(ApiError(code = "INVESTIGATION_ALREADY_RUNNING", message = "an investigation turn is already running")),
                )
                return@handle
            }
            try {
                call.streamInvestigation(
                    investigationService(context, lockedCollectionId, profile),
                    InvestigateRequest(CollectionId(collection.id.value), body.question, profile, conversationId = conversationId),
                    runningTurns,
                    activeTurnIds,
                    reservedId = conversationId,
                )
            } catch (failure: Exception) {
                activeTurnIds.remove(conversationId)
                throw failure
            }
        }
    }

    post("/api/investigations/{id}/cancel") {
        call.handle {
            val conversationId = call.conversationId()
            val job = runningTurns[conversationId]
            if (job == null) {
                call.respondJson(
                    HttpStatusCode.NotFound,
                    ApiErrorResponse(ApiError(code = "NOT_FOUND", message = "no running investigation turn with id $conversationId")),
                )
            } else {
                job.cancel()
                call.respondJson(HttpStatusCode.OK, CancelInvestigationResponse())
            }
        }
    }
}

private fun ApplicationCall.conversationId(): String =
    parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("a conversation id is required in the path")

private fun investigationService(context: AppContext, collectionId: CollectionId, profile: LlmProfile): InvestigationService {
    val persistence = object : InvestigatePersistence {
        override fun createConversation(
            collectionId: CollectionId,
            profile: LlmProfile,
            promptVersion: Int,
            retrievalSnapshot: String,
        ): String = context.llm.persistInvestigateConversation(
            collectionId = collectionId,
            profile = profile,
            promptVersion = promptVersion,
            retrievalSnapshot = retrievalSnapshot,
        )

        override fun appendTurn(conversationId: String, snapshot: InvestigateTurnSnapshot) {
            snapshot.messages.forEach { (seq, message) ->
                context.llm.persistInvestigateMessage(conversationId, seq, message)
            }
            snapshot.modelCalls.forEach { call ->
                val modelCallId = context.llm.persistInvestigateModelCall(
                    conversationId = conversationId,
                    provider = call.provider,
                    endpoint = call.endpoint,
                    model = call.model,
                    profileName = call.profileName,
                    promptVersion = call.promptVersion,
                    status = call.status,
                    inputTokens = call.inputTokens,
                    outputTokens = call.outputTokens,
                    cacheReadTokens = call.cacheReadTokens,
                    costUsd = call.costUsd,
                    errorCode = call.errorCode,
                    correctionOf = call.correctionOf,
                )
                call.toolCalls.forEach { tool ->
                    context.llm.persistInvestigateToolCall(
                        conversationId = conversationId,
                        modelCallId = modelCallId,
                        toolName = tool.toolName,
                        argumentsJson = tool.argumentsJson,
                        resultCode = tool.resultCode,
                        durationMs = tool.durationMs,
                    )
                }
                context.llm.persistRequestEligibility(modelCallId, conversationId, call.eligibilityEvidenceIds)
                context.llm.persistRequestOmissions(modelCallId, conversationId, call.omissionGroupLabels)
            }
            snapshot.evidenceEntries.forEach { entry ->
                context.llm.persistEvidenceLedgerEntry(conversationId, entry.evidenceId, entry.sourceUnitId, entry.locatorJson, entry.excerpt)
            }
            snapshot.limitEvents.forEach { event ->
                context.llm.persistLimitEvent(conversationId, event.eventType, event.message)
            }
            // The turn's calls and cost land in the profile's accumulated counters exactly as
            // persistAsk does, so a price change cannot rewrite history: totals only grow.
            context.llm.updateUsageTotals(
                profileId = snapshot.profile.id,
                calls = snapshot.modelCalls.size,
                inputTokens = snapshot.modelCalls.sumOf { it.inputTokens ?: 0L },
                outputTokens = snapshot.modelCalls.sumOf { it.outputTokens ?: 0L },
                cacheReadTokens = snapshot.modelCalls.sumOf { it.cacheReadTokens ?: 0L },
                costUsd = snapshot.modelCalls.sumOf { it.costUsd },
            )
        }

        override fun loadHistory(conversationId: String) = context.llm.loadInvestigateHistory(conversationId)
    }
    return InvestigationService(
        tools = InvestigationTools(collectionId, context.search, context.content, context.documents),
        prompt = PromptService(context.llm),
        promptVersion = context.llm.effectivePromptVersion(LlmPromptRole.INVESTIGATE),
        streamingClient = { selected ->
            when (selected.provider) {
                LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(selected, System::getenv)
                LlmProvider.ANTHROPIC -> AnthropicClient(selected, System::getenv)
            }
        },
        persistence = persistence,
    )
}

/**
 * Streams one turn as SSE: the collecting coroutine registers in [runningTurns] under the
 * conversation id once `started` arrives and unregisters when the stream ends, so `cancel` can
 * stop the turn at any point and a finished turn does not linger in the registry.
 */
private suspend fun ApplicationCall.streamInvestigation(
    service: InvestigationService,
    request: InvestigateRequest,
    runningTurns: ConcurrentHashMap<String, Job>,
    activeTurnIds: MutableSet<String>,
    reservedId: String? = null,
) {
    respondOutputStream(ContentType.Text.EventStream, HttpStatusCode.OK) {
        val job = coroutineContext[Job]
            ?: error("an investigation turn needs a coroutine Job to be cancellable")
        var registeredId: String? = reservedId
        try {
            service.investigate(request).collect { event ->
                if (event is InvestigateEvent.Started) {
                    if (registeredId == null) {
                        check(activeTurnIds.add(event.conversationId)) { "an investigation turn is already running" }
                    }
                    registeredId = event.conversationId
                    runningTurns[event.conversationId] = job
                }
                write("data: ${ApiJson.encodeToString(event.toWire())}\n\n".toByteArray(StandardCharsets.UTF_8)); flush()
            }
        } finally {
            registeredId?.let {
                runningTurns.remove(it, job)
                activeTurnIds.remove(it)
            }
        }
    }
}

/**
 * A citation a `done` event can resolve: the stable evidence id, the unit that holds the source, and
 * the location inside it. Deliberately carries no excerpt text — the viewer fetches content by unit id.
 */
@Serializable
data class EvidenceWire(
    val id: String,
    val documentId: String,
    val unitId: String,
    val locator: SourceLocation,
    val locatorLabel: String,
)

@Serializable
internal data class InvestigateWire(
    val type: String,
    val id: String? = null,
    val text: String? = null,
    val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val resultCode: String? = null,
    val durationMs: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val valid: Boolean? = null,
    val code: String? = null,
    val message: String? = null,
    // Optional and empty by default so delta/usage/citation/started/tool/error keep their exact
    // shapes; NEVER keeps even `done` unchanged while no evidence exists.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val evidence: List<EvidenceWire> = emptyList(),
)

internal fun InvestigateEvent.toWire() = when (this) {
    is InvestigateEvent.Started -> InvestigateWire("started", id = conversationId)
    is InvestigateEvent.Delta -> InvestigateWire("delta", text = text)
    is InvestigateEvent.ToolCall -> InvestigateWire("tool", callId = callId, name = name, arguments = arguments)
    is InvestigateEvent.ToolResult -> InvestigateWire("tool", callId = callId, name = name, resultCode = resultCode, durationMs = durationMs)
    is InvestigateEvent.Usage -> InvestigateWire("usage", inputTokens = inputTokens, outputTokens = outputTokens)
    is InvestigateEvent.Citation -> InvestigateWire("citation", id = evidenceId, valid = valid)
    is InvestigateEvent.Done -> InvestigateWire("done", text = answer, evidence = evidence.map {
        EvidenceWire(
            id = it.id,
            documentId = it.documentId,
            unitId = it.unitId,
            locator = it.locator,
            locatorLabel = it.locatorLabel,
        )
    })
    is InvestigateEvent.Error -> InvestigateWire("error", code = code, message = message)
}
