-- Preserve the provider-neutral tool exchange when an Investigate turn is continued.
-- Existing rows remain readable; old tool rows have no call id and cannot be replayed.
ALTER TABLE messages ADD COLUMN tool_calls_json TEXT;
ALTER TABLE messages ADD COLUMN tool_call_id TEXT;
