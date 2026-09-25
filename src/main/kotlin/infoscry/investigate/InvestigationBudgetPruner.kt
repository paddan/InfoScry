package infoscry.investigate

import infoscry.llm.LlmMessage
import infoscry.llm.LlmRequest
import infoscry.llm.RequestBudget
import infoscry.llm.LlmProfile

/**
 * Result of one pruning pass: the reduced [messages] to send, plus which group ids were omitted
 * so the caller can record them in [request_omissions].
 */
data class PrunedRequest(
    val messages: List<LlmMessage>,
    val omittedGroupIds: List<String>,
)

/**
 * Prunes the oldest completed history groups from [allMessages] until the candidate request fits
 * within [budget] for [profile]. Groups 0 (core prompt) and 1 (current question) are never pruned.
 *
 * [groups] is a list of message index ranges [first, last+1) into [allMessages]. Groups 0 and 1
 * are the core and the current question; groups 2.. are completed prior turns and tool exchanges.
 * The current (unfinished) tool-exchange group is not in the list yet, so never pruned.
 *
 * A tool-exchange group is one assistant message carrying `toolCalls` plus all tool result messages
 * whose `toolCallId` matches one of those calls. The caller guarantees these are never split.
 *
 * The pruner walks groups from oldest (lowest index) to newest, accumulating each removal and
 * re-measuring after every one, stopping as soon as the request fits. Every removal is kept even
 * when it does not fit on its own, so the oldest groups go first without a newer group being
 * dropped in their place. Only the outbound view is reduced; the full [allMessages] list remains
 * available for persistence.
 */
fun pruneToFit(
    profile: LlmProfile,
    budget: RequestBudget,
    allMessages: List<LlmMessage>,
    groups: List<Pair<Int, Int>>,
    tools: List<infoscry.llm.ToolDefinition>,
    maxOutputTokens: Int,
): PrunedRequest {
    val omitted = mutableListOf<String>()
    val initialMeasurement = budget.measure(profile, LlmRequest(allMessages, tools, maxOutputTokens = maxOutputTokens), stream = true)

    if (initialMeasurement.fits) return PrunedRequest(allMessages, emptyList())

    // Walk groups from oldest (gi = 2) to newest, accumulating removals. Groups 0 (core) and 1
    // (current question) are never pruned. Accumulate the removed index ranges and filter once by
    // original index: removing an early group shifts every later range, so they cannot be applied
    // one by one to a shrinking list.
    val removed = mutableListOf<Pair<Int, Int>>()
    var messages = allMessages
    var measurement = initialMeasurement
    for (gi in 2 until groups.size) {
        if (measurement.fits) break
        removed.add(groups[gi])
        messages = allMessages.filterIndexed { index, _ ->
            removed.none { index >= it.first && index < it.second }
        }
        measurement = budget.measure(profile, LlmRequest(messages, tools, maxOutputTokens = maxOutputTokens), stream = true)
        omitted.add("grp$gi")
    }

    return PrunedRequest(messages, omitted)
}