-- LLM configuration and conversation persistence.
--
-- Version 4 adds the LLM layer on top of versions 1-3. Nothing here changes the tables
-- earlier migrations created, so it can be applied in the ordinary way (foreign keys on).
--
-- A profile stores the NAME of the environment variable holding its API key, never a value.
-- The same rule must hold for every table here: the conversations, messages and model-call
-- snapshots record provider, endpoint, model, profile name, prompt version and retrieval
-- settings, but no secret. The env-var name column uses a CHECK that matches the constraint
-- the store validates in Kotlin, so an invalid profile can never be written around it.

CREATE TABLE IF NOT EXISTS llm_profiles (
    id                               TEXT    NOT NULL PRIMARY KEY,
    name                             TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    provider                         TEXT    NOT NULL CHECK (provider IN ('OPENAI_COMPATIBLE', 'ANTHROPIC')),
    endpoint                         TEXT,
    model                            TEXT    NOT NULL,
    api_key_environment_variable     TEXT,
    context_window                   INTEGER NOT NULL CHECK (context_window > 0),
    max_output_tokens                INTEGER NOT NULL CHECK (max_output_tokens > 0),
    supports_tool_calling            INTEGER NOT NULL CHECK (supports_tool_calling IN (0, 1)),
    input_price_per_million          REAL    NOT NULL CHECK (input_price_per_million >= 0),
    output_price_per_million         REAL    NOT NULL CHECK (output_price_per_million >= 0),
    cache_read_price_per_million     REAL    NOT NULL CHECK (cache_read_price_per_million >= 0),
    enabled                          INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    -- The measured result of the last capability test (Task 20 provides the transport).
    tool_calling_measured            INTEGER,
    capability_checked_at            TEXT,
    created_at                       TEXT    NOT NULL,
    updated_at                       TEXT    NOT NULL
);

-- Which profile answers Ask and which answers Investigate. A row holds the role, so future
-- roles only add rows, and the absence of a row means "no default selected yet".
CREATE TABLE IF NOT EXISTS app_defaults (
    id           TEXT    NOT NULL PRIMARY KEY,
    role         TEXT    NOT NULL UNIQUE CHECK (role IN ('ASK', 'INVESTIGATE')),
    profile_id   TEXT    NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES llm_profiles (id) ON DELETE CASCADE
);

-- The editable, versioned Ask/Investigate prompt bodies. The immutable core rules live in
-- code/resources and cannot be replaced from here; this table only ever holds the
-- user-editable bodies (layer 2), so a row's replacement never touches layer 1.
CREATE TABLE IF NOT EXISTS prompt_overrides (
    role             TEXT    NOT NULL PRIMARY KEY CHECK (role IN ('ASK', 'INVESTIGATE')),
    body             TEXT    NOT NULL,
    version          INTEGER NOT NULL,
    updated_at       TEXT    NOT NULL
);

-- Conversations are fixed to one collection and one profile snapshot when created.
CREATE TABLE IF NOT EXISTS conversations (
    id                          TEXT    NOT NULL PRIMARY KEY,
    collection_id               TEXT    NOT NULL,
    mode                        TEXT    NOT NULL CHECK (mode IN ('ASK', 'INVESTIGATE')),
    -- Non-secret snapshot of the profile at creation; a later edit never rewrites history.
    profile_provider            TEXT    NOT NULL,
    profile_endpoint            TEXT,
    profile_model               TEXT    NOT NULL,
    profile_name                TEXT    NOT NULL,
    prompt_version              INTEGER NOT NULL,
    retrieval_snapshot          TEXT    NOT NULL DEFAULT '{}',
    created_at                  TEXT    NOT NULL,
    FOREIGN KEY (collection_id) REFERENCES collections (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS messages (
    id                TEXT    NOT NULL PRIMARY KEY,
    conversation_id   TEXT    NOT NULL,
    seq               INTEGER NOT NULL,
    role              TEXT    NOT NULL CHECK (role IN ('user', 'assistant', 'tool', 'system')),
    content           TEXT    NOT NULL,
    created_at        TEXT    NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS model_calls (
    id                 TEXT    NOT NULL PRIMARY KEY,
    conversation_id    TEXT    NOT NULL,
    provider           TEXT    NOT NULL,
    endpoint           TEXT,
    model              TEXT    NOT NULL,
    profile_name       TEXT    NOT NULL,
    prompt_version     INTEGER NOT NULL,
    requested_at       TEXT    NOT NULL,
    response_at        TEXT    NOT NULL,
    status             TEXT    NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    input_tokens       INTEGER,
    output_tokens      INTEGER,
    cache_read_tokens  INTEGER,
    cost_usd           REAL    NOT NULL DEFAULT 0,
    error_code         TEXT,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS citations (
    id               TEXT    NOT NULL PRIMARY KEY,
    model_call_id    TEXT    NOT NULL,
    conversation_id  TEXT    NOT NULL,
    source_unit_id   TEXT    NOT NULL,
    locator_json     TEXT    NOT NULL,
    snippet          TEXT    NOT NULL,
    validated        INTEGER NOT NULL CHECK (validated IN (0, 1)),
    FOREIGN KEY (model_call_id) REFERENCES model_calls (id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

-- Accumulated usage per profile, so the cost/usage counters have one home.
CREATE TABLE IF NOT EXISTS usage_totals (
    profile_id          TEXT    NOT NULL PRIMARY KEY,
    calls               INTEGER NOT NULL DEFAULT 0,
    input_tokens        INTEGER NOT NULL DEFAULT 0,
    output_tokens       INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens   INTEGER NOT NULL DEFAULT 0,
    cost_usd            REAL    NOT NULL DEFAULT 0,
    FOREIGN KEY (profile_id) REFERENCES llm_profiles (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages (conversation_id, seq);
CREATE INDEX IF NOT EXISTS idx_citations_model_call ON citations (model_call_id);
CREATE INDEX IF NOT EXISTS idx_model_calls_conversation ON model_calls (conversation_id);