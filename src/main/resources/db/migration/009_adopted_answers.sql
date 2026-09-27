-- Adopted Investigate answers and evidence-to-exchange association.
--
-- A completed Investigate turn may replace a streamed draft answer with a corrected answer. Only
-- one of them is the adopted conversation answer; the superseded draft stays as audit data and is
-- excluded from the reader and provider conversation views. Existing rows default to 0 and are
-- therefore preserved unchanged — an old duplicated answer pair is ambiguous legacy data, not
-- silently collapsed.
ALTER TABLE messages ADD COLUMN superseded INTEGER NOT NULL DEFAULT 0 CHECK (superseded IN (0, 1));

-- The durable `messages.seq` of the assistant tool-call exchange that introduced an evidence entry.
-- A continued conversation uses it to associate retained evidence with the history group that
-- actually carried it into a provider request, so ledger membership alone never makes evidence
-- eligible. Null for entries written before this migration; those stay ineligible for citation.
ALTER TABLE evidence_ledger ADD COLUMN message_seq INTEGER;
