package infoscry.investigate

/**
 * The per-turn research limits a reader sets for one Investigate question. The bounds are deliberate
 * bounded v1 choices; the first limit reached ends research. Validated here so HTTP and non-HTTP
 * callers obey the same contract. A round is one model response containing one or more tool calls.
 */
data class InvestigationLimits(
    val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val maxTurnSeconds: Int = DEFAULT_MAX_TURN_SECONDS,
) {
    fun validate() {
        require(maxToolRounds in MIN_TOOL_ROUNDS..MAX_TOOL_ROUNDS) {
            "maxToolRounds must be between $MIN_TOOL_ROUNDS and $MAX_TOOL_ROUNDS"
        }
        require(maxToolCalls in MIN_TOOL_CALLS..MAX_TOOL_CALLS) {
            "maxToolCalls must be between $MIN_TOOL_CALLS and $MAX_TOOL_CALLS"
        }
        require(maxTurnSeconds in MIN_TURN_SECONDS..MAX_TURN_SECONDS) {
            "maxTurnSeconds must be between $MIN_TURN_SECONDS and $MAX_TURN_SECONDS"
        }
    }

    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 50
        const val DEFAULT_MAX_TOOL_CALLS = 50
        const val DEFAULT_MAX_TURN_SECONDS = 600

        const val MIN_TOOL_ROUNDS = 1
        const val MAX_TOOL_ROUNDS = 50
        const val MIN_TOOL_CALLS = 1
        const val MAX_TOOL_CALLS = 100
        const val MIN_TURN_SECONDS = 10
        const val MAX_TURN_SECONDS = 1800
    }
}
