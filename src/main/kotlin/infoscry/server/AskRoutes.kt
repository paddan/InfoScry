package infoscry.server

import infoscry.AppContext
import infoscry.ask.AskEvent
import infoscry.ask.AskRequest
import infoscry.ask.AskService
import infoscry.domain.CollectionId
import infoscry.domain.SourceLocation
import infoscry.llm.AnthropicClient
import infoscry.llm.LlmProfile
import infoscry.llm.OpenAiCompatibleClient
import infoscry.llm.PromptService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import java.nio.charset.StandardCharsets
import java.sql.Connection
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class AskApiRequest(val collection: String, val question: String, val profile: String)

fun Routing.configureAskRoutes(context: AppContext) {
    post("/api/ask") {
        call.handle {
            val body = call.receiveJson<AskApiRequest>()
            val collection = context.collectionService.requireActiveByNameOrId(body.collection)
            val profile = context.llm.findByName(body.profile) ?: throw NoSuchElementException("no such LLM profile")
            val service = AskService(
                context.search,
                PromptService(context.llm),
                client = { selected ->
                    when (selected.provider) {
                        infoscry.llm.LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(selected, System::getenv)
                        infoscry.llm.LlmProvider.ANTHROPIC -> AnthropicClient(selected, System::getenv)
                    }
                },
                persistence = infoscry.ask.AskPersistence { ask, answer, evidence, initialUsage, initialCitations, retrievalSnapshot, correction ->
                    context.llm.persistAsk(ask.collectionId, ask.profile, ask.question, answer, evidence, initialUsage, initialCitations, retrievalSnapshot, correction)
                },
            )
            call.respondOutputStream(ContentType.Text.EventStream, HttpStatusCode.OK) {
                service.ask(AskRequest(CollectionId(collection.id.value), body.question, profile)).collect { event ->
                    write("data: ${ApiJson.encodeToString(event.toWire())}\n\n".toByteArray(StandardCharsets.UTF_8)); flush()
                }
            }
        }
    }

    get("/api/collections/{id}/asks") {
        call.handle {
            val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
            val asks = context.database.read { connection -> loadAskHistory(connection, collection.id.value) }
            call.respondJson(HttpStatusCode.OK, mapOf("asks" to asks))
        }
    }
}

/**
 * One stored Ask, as the reader sees it: the question, the answer it produced, and the sources that
 * answer cited.
 *
 * An Ask is one question and one answer by construction, so a list entry and a detail are the same
 * object and the panel needs no second request. The evidence is read back from the `citations` rows the
 * Ask recorded (`supplied = 1`): an Ask keeps no evidence ledger, because the ledger belongs to
 * Investigate's multi-turn id allocation.
 */
@Serializable
internal data class AskSummaryWire(
    val id: String,
    val createdAt: String,
    val question: String,
    val answer: String,
    val evidence: List<EvidenceWire>,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
)

/** How much of a stored question the history selector is given, matching Investigate's list labels. */
private const val QUESTION_LABEL_CHARS = 180

private class AskHistoryRow(
    val id: String,
    val createdAt: String,
    val question: String,
    val answer: String,
)

/**
 * The newest Ask conversations of one collection, with their evidence and their cost.
 *
 * The question is bounded the way Investigate bounds its list labels: a stored question can be paragraphs
 * long, and the selector only ever shows one line of it. The answer is not bounded, because the panel
 * replays a stored answer from this same object rather than asking for it again.
 *
 * The question is the first user message and the answer is the last assistant message, so a corrected
 * answer is the one that shows: `persistAsk` stores the correction as the conversation's answer and
 * keeps both model calls for the audit.
 */
private fun loadAskHistory(connection: Connection, collectionId: String): List<AskSummaryWire> {
    val conversations = connection.prepareStatement(
        "SELECT c.id, c.created_at, " +
            "COALESCE((SELECT m.content FROM messages m WHERE m.conversation_id = c.id AND m.role = 'user' ORDER BY m.seq LIMIT 1), '') AS question, " +
            "COALESCE((SELECT m.content FROM messages m WHERE m.conversation_id = c.id AND m.role = 'assistant' ORDER BY m.seq DESC LIMIT 1), '') AS answer " +
            "FROM conversations c WHERE c.collection_id = ? AND c.mode = 'ASK' ORDER BY c.created_at DESC LIMIT 50",
    ).use { statement ->
        statement.setString(1, collectionId)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        AskHistoryRow(
                            id = rows.getString("id"),
                            createdAt = rows.getString("created_at"),
                            question = rows.getString("question").take(QUESTION_LABEL_CHARS),
                            answer = rows.getString("answer"),
                        ),
                    )
                }
            }
        }
    }
    if (conversations.isEmpty()) return emptyList()
    val ids = conversations.map { it.id }
    val evidence = loadAskEvidence(connection, ids, collectionId)
    val usage = loadAskUsage(connection, ids)
    return conversations.map { conversation ->
        AskSummaryWire(
            id = conversation.id,
            createdAt = conversation.createdAt,
            question = conversation.question,
            answer = conversation.answer,
            evidence = evidence[conversation.id].orEmpty(),
            inputTokens = usage[conversation.id]?.first ?: 0L,
            outputTokens = usage[conversation.id]?.second ?: 0L,
            costUsd = usage[conversation.id]?.third ?: 0.0,
        )
    }
}

/** The supplied evidence of each conversation, keyed by conversation id. */
private fun loadAskEvidence(
    connection: Connection,
    conversationIds: List<String>,
    collectionId: String,
): Map<String, List<EvidenceWire>> {
    val placeholders = conversationIds.joinToString(",") { "?" }
    return connection.prepareStatement(
        "SELECT c.conversation_id, c.evidence_id, c.source_unit_id, c.locator_json, u.document_id " +
            "FROM citations c JOIN content_units u ON u.id = c.source_unit_id " +
            "JOIN documents d ON d.id = u.document_id " +
            "WHERE c.conversation_id IN ($placeholders) AND d.collection_id = ? AND c.supplied = 1 " +
            "ORDER BY c.conversation_id, c.evidence_id",
    ).use { statement ->
        conversationIds.forEachIndexed { index, id -> statement.setString(index + 1, id) }
        statement.setString(conversationIds.size + 1, collectionId)
        statement.executeQuery().use { rows ->
            buildMap<String, MutableList<EvidenceWire>> {
                while (rows.next()) {
                    val evidenceId = rows.getString("evidence_id") ?: continue
                    val locator = ApiJson.decodeFromString<SourceLocation>(rows.getString("locator_json"))
                    getOrPut(rows.getString("conversation_id")) { mutableListOf() }.add(
                        EvidenceWire(
                            id = evidenceId,
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

/** Input tokens, output tokens and cost of each conversation, summed over its model calls. */
private fun loadAskUsage(
    connection: Connection,
    conversationIds: List<String>,
): Map<String, Triple<Long, Long, Double>> {
    val placeholders = conversationIds.joinToString(",") { "?" }
    return connection.prepareStatement(
        "SELECT conversation_id, COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0), COALESCE(SUM(cost_usd), 0) " +
            "FROM model_calls WHERE conversation_id IN ($placeholders) GROUP BY conversation_id",
    ).use { statement ->
        conversationIds.forEachIndexed { index, id -> statement.setString(index + 1, id) }
        statement.executeQuery().use { rows ->
            buildMap {
                while (rows.next()) {
                    put(rows.getString("conversation_id"), Triple(rows.getLong(2), rows.getLong(3), rows.getDouble(4)))
                }
            }
        }
    }
}

@Serializable
internal data class AskWire(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val valid: Boolean? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val code: String? = null,
    val message: String? = null,
    // Optional and empty by default so delta/usage/citation/error keep their exact shapes; NEVER
    // keeps even `done` unchanged while no evidence exists.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val evidence: List<EvidenceWire> = emptyList(),
)

internal fun AskEvent.toWire() = when (this) {
    is AskEvent.Delta -> AskWire("delta", text = text)
    is AskEvent.Usage -> AskWire("usage", inputTokens = inputTokens, outputTokens = outputTokens)
    is AskEvent.Citation -> AskWire("citation", id = evidenceId, valid = valid)
    is AskEvent.Done -> AskWire("done", text = answer, evidence = evidence.map {
        EvidenceWire(
            id = it.id,
            documentId = it.documentId,
            unitId = it.unitId,
            locator = it.locator,
            locatorLabel = it.locatorLabel,
        )
    })
    is AskEvent.Error -> AskWire("error", code = code, message = message)
}
