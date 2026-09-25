-- Investigate tool-call activity, evidence ledger, and limit events.
--
-- Version 6 adds durable persistence for bounded Investigate conversations on top of
-- the v4 LLM tables and v5 Ask audit columns. Nothing here changes earlier tables,
-- so it applies with foreign keys on.

-- One tool call the service executed on the model's behalf: which conversation and
-- which assistant model call requested it, the tool name and arguments, the typed
-- result code (including the InvestigationTools failure codes), and its duration.
CREATE TABLE IF NOT EXISTS tool_calls (
    id                TEXT    NOT NULL PRIMARY KEY,
    conversation_id   TEXT    NOT NULL,
    model_call_id     TEXT    NOT NULL,
    tool_name         TEXT    NOT NULL,
    arguments_json    TEXT    NOT NULL,
    result_code       TEXT    NOT NULL,
    duration_ms       INTEGER NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    FOREIGN KEY (model_call_id) REFERENCES model_calls (id) ON DELETE CASCADE
);

-- The evidence ledger: monotonic S1, S2, … per conversation, never reused or
-- renumbered after eviction. Each row records the source unit, locator, and the
-- excerpt that was supplied, so every cited source can be reopened after restart.
CREATE TABLE IF NOT EXISTS evidence_ledger (
    conversation_id  TEXT NOT NULL,
    evidence_id      TEXT NOT NULL,
    source_unit_id   TEXT NOT NULL,
    locator_json     TEXT NOT NULL,
    excerpt          TEXT NOT NULL,
    PRIMARY KEY (conversation_id, evidence_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Which evidence ids were supplied to a particular model request.
CREATE TABLE IF NOT EXISTS request_eligibility (
    model_call_id    TEXT NOT NULL,
    conversation_id  TEXT NOT NULL,
    evidence_id      TEXT NOT NULL,
    PRIMARY KEY (model_call_id, evidence_id),
    FOREIGN KEY (model_call_id) REFERENCES model_calls (id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Which history groups were omitted from a model request's outbound view, so the
-- full local history stays recoverable while the reduced view is recorded.
CREATE TABLE IF NOT EXISTS request_omissions (
    model_call_id    TEXT NOT NULL,
    conversation_id  TEXT NOT NULL,
    group_label      TEXT NOT NULL,
    PRIMARY KEY (model_call_id, group_label),
    FOREIGN KEY (model_call_id) REFERENCES model_calls (id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Typed limit, cancellation, and budget-overflow events, durable so operators
-- can see why a conversation stopped.
CREATE TABLE IF NOT EXISTS limit_events (
    id                TEXT NOT NULL PRIMARY KEY,
    conversation_id   TEXT NOT NULL,
    event_type        TEXT NOT NULL,
    message           TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tool_calls_conversation ON tool_calls (conversation_id);
CREATE INDEX IF NOT EXISTS idx_evidence_ledger_conversation ON evidence_ledger (conversation_id);
CREATE INDEX IF NOT EXISTS idx_limit_events_conversation ON limit_events (conversation_id);