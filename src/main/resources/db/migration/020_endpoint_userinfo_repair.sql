-- Endpoints that a legacy archive stored with a credential in the URL are repaired, and the profiles
-- that owned them are switched off until a person reviews them.
--
-- An address is not where a key belongs: a profile names the *environment variable* a key is read from,
-- so `https://user:secret@host/v1` is only ever the accident of a copied configuration. Both profile
-- types now refuse such a URL instead of storing one (`endpointCarriesUserInfo`), and a row written
-- before that rule existed is not merely untidy: it is read back through the same type, whose
-- constructor refuses a URL carrying `userinfo`, so the statement that lists profiles fails and every
-- screen and CLI command that lists them is an error while the credential sits in the file.
--
-- Four columns can hold such an address: `llm_profiles.endpoint` is the one a person edits,
-- `conversations.profile_endpoint` and `model_calls.endpoint` are the non-secret snapshots a
-- conversation and a call keep of where they ran, and `ocr_profile_revisions.endpoint` is the address
-- one immutable revision of an OCR profile read through. All four are repaired, because all four are
-- read, returned by an API or copied into a support bundle, and a credential is no less published for
-- having been snapshotted into a call record.
--
-- The credential is *removed*, not kept as history. Everywhere else this schema preserves what really
-- happened — a call keeps the address it dispatched to after the profile has moved on — but a secret is
-- the one thing that must not outlive the cleanup that found it: an archive is a file people copy, back
-- up and mail to a colleague when something goes wrong, and a credential kept "for history" would be
-- republished by exactly those habits. What the row keeps is everything that was never secret: the
-- scheme, host, port, and the path, query and fragment the address pointed at.
--
-- Removing the credential also removes the evidence that there was one: the read path finds a repaired
-- snapshot by the `userinfo` left in the stored address, and a repaired address no longer has any. The repair
-- is therefore recorded as a fact of its own, in `conversations.profile_endpoint_repaired`, so a cleaned
-- snapshot still reads back as repaired — otherwise a conversation this migration cleaned would resume
-- dispatching to it as soon as somebody repaired and enabled the live profile. The column is added by this
-- migration rather than by a later one because migration 020 has never been applied to an archive: every row
-- it is about to repair is still unrepaired, so the marker and its values are written together and no row this
-- migration cleaned is left unmarked.
--
-- A profile whose address had to be repaired is switched off (`enabled = 0`) rather than left
-- selectable. The address such a profile holds after this migration is not the one it was written with,
-- and this migration is not entitled to choose a destination on a person's behalf: nothing dispatches to
-- a repaired address until somebody reads the profile and confirms that the repaired address is what
-- they meant. An OCR profile is stopped when it owns a repaired revision at all, not only when it
-- currently points at one, because a job or a collection that snapshotted an older revision would still
-- dispatch through that address.
--
-- What "the credential" means here, and how it is found without a regex engine: an absolute URL's
-- authority is the part after `://` up to the earliest `/`, `?` or `#`, and `userinfo` is what the
-- authority holds up to its *last* `@`. The last one, not the first: Java's `URI` — which is what these
-- rows are read back through — takes the last `@` before the end of the authority as the delimiter, so
-- stripping at the first would turn `https://a@b@host/v1` into `https://b@host/v1`, which still carries
-- `userinfo` and is still refused. A `@` outside the authority is not a credential (`https://host/a@b/v1`
-- keeps it, and so does an address in a query string), which is why the search for it stops at the
-- authority's own end, and a value with no `://` has no authority and is left exactly as it is.
--
-- The rule is written once, as a table holding the repair plan, rather than four times as four look-alike
-- UPDATE expressions: four copies of a rule is how one of them ends up subtly different, and the plan is
-- what the two disabling statements read. `CREATE TABLE ... AS` over a recursive CTE is the whole of it:
-- for each stored address it walks the authority's own length to find the last `@` inside it, and no
-- offset is guessed, so a value of any length is examined completely. The table is dropped again at the
-- end of this migration, so the finished schema does not have it.
CREATE TABLE endpoint_userinfo_repair AS
WITH RECURSIVE
stored(source, row_id, endpoint) AS (
    SELECT 'llm_profiles', id, endpoint FROM llm_profiles WHERE endpoint IS NOT NULL
    UNION ALL
    SELECT 'conversations', id, profile_endpoint FROM conversations WHERE profile_endpoint IS NOT NULL
    UNION ALL
    SELECT 'model_calls', id, endpoint FROM model_calls WHERE endpoint IS NOT NULL
    UNION ALL
    SELECT 'ocr_profile_revisions', revision_id, endpoint FROM ocr_profile_revisions
        WHERE endpoint IS NOT NULL
),
split(source, row_id, endpoint, scheme_end, remainder) AS (
    SELECT source, row_id, endpoint, instr(endpoint, '://'), substr(endpoint, instr(endpoint, '://') + 3)
    FROM stored
),
authority(source, row_id, endpoint, scheme_end, remainder, authority_length) AS (
    SELECT source, row_id, endpoint, scheme_end, remainder,
        -- The authority ends at the *earliest* delimiter, not at the first kind of delimiter the value
        -- happens to contain: `https://host?email=a@b/c` has a `?` before its `/`, and taking the slash
        -- would swallow the query into the authority and strip an `@` that was never a credential —
        -- rewriting the host itself. Each candidate falls back to one past the end, and the smallest wins.
        min(
            coalesce(nullif(instr(remainder, '/'), 0), length(remainder) + 1),
            coalesce(nullif(instr(remainder, '?'), 0), length(remainder) + 1),
            coalesce(nullif(instr(remainder, '#'), 0), length(remainder) + 1)
        ) - 1
    FROM split
),
offset_position(source, row_id, endpoint, scheme_end, remainder, authority_length, offset) AS (
    SELECT source, row_id, endpoint, scheme_end, remainder, authority_length, 1 FROM authority
    UNION ALL
    SELECT source, row_id, endpoint, scheme_end, remainder, authority_length, offset + 1
    FROM offset_position WHERE offset < authority_length
),
credential_end(source, row_id, credential_end) AS (
    SELECT source, row_id, max(offset) FROM offset_position
    WHERE substr(remainder, offset, 1) = '@'
    GROUP BY source, row_id
)
SELECT a.source, a.row_id,
    substr(a.endpoint, 1, a.scheme_end + 2) || substr(a.remainder, c.credential_end + 1)
        AS repaired_endpoint
FROM authority a
JOIN credential_end c ON c.source = a.source AND c.row_id = a.row_id
WHERE a.scheme_end > 0;

-- `llm_profiles.endpoint`: the address this profile dispatches to, so the profile itself is stopped.
UPDATE llm_profiles SET enabled = 0
WHERE id IN (SELECT row_id FROM endpoint_userinfo_repair WHERE source = 'llm_profiles');

-- `ocr_profile_revisions.endpoint`: the address a revision reads through. The profile that owns the
-- revision is stopped; a revision is never edited, so the repaired address is the only one left.
UPDATE ocr_profiles SET enabled = 0
WHERE id IN (
    SELECT profile_id FROM ocr_profile_revisions
    WHERE revision_id IN (
        SELECT row_id FROM endpoint_userinfo_repair WHERE source = 'ocr_profile_revisions'
    )
);

UPDATE llm_profiles SET endpoint = (
    SELECT repaired_endpoint FROM endpoint_userinfo_repair
    WHERE source = 'llm_profiles' AND row_id = llm_profiles.id
)
WHERE id IN (SELECT row_id FROM endpoint_userinfo_repair WHERE source = 'llm_profiles');

-- A conversation's snapshot of the address it ran under, and the address one call dispatched to. The value
-- in a call record is repaired for the same reason every other copy is — it is read, returned and copied into
-- support bundles — but nothing is stopped for it: a past call is not a configuration anybody can select.
--
-- Nothing is stopped for a conversation's repaired snapshot *here* either, and that is a statement about what
-- this schema lets the statement name rather than about what may dispatch. No column links a conversation to
-- the profile row it locked: there is no profile id and no foreign key, only the `profile_name` snapshot,
-- which a rename leaves pointing at no row at all and a reused name would attribute to somebody else's
-- profile, so a disabling statement here could only guess and could stop a profile that never dispatched this
-- address. The snapshot still had to be stopped, because a continue dispatches to exactly the address the
-- conversation stored: this statement marks the row it repairs (`profile_endpoint_repaired`), and the read
-- path treats a marked snapshot — or one whose stored address still carries `userinfo`, a row this migration
-- never saw — as a switched-off profile, so the continue gate refuses it the way it refuses a switched-off
-- row, which is the enforcement, not this migration. The profile a person edits is named by a row and is
-- stopped above, under `llm_profiles.endpoint`.
--
-- The marker records the repair the credential's removal would otherwise hide: the cleaned address is what the
-- read path used to find repaired rows by, and this row no longer holds a credential for it to find. It is
-- added here, in the migration that repairs the rows, because migration 020 has never been applied to an
-- archive — every row it repairs is still unrepaired when the column appears, so no already-cleaned row can be
-- left unmarked the way a later migration would leave one.
ALTER TABLE conversations ADD COLUMN profile_endpoint_repaired INTEGER NOT NULL DEFAULT 0
    CHECK (profile_endpoint_repaired IN (0, 1));

UPDATE conversations SET profile_endpoint = (
    SELECT repaired_endpoint FROM endpoint_userinfo_repair
    WHERE source = 'conversations' AND row_id = conversations.id
), profile_endpoint_repaired = 1
WHERE id IN (SELECT row_id FROM endpoint_userinfo_repair WHERE source = 'conversations');

UPDATE model_calls SET endpoint = (
    SELECT repaired_endpoint FROM endpoint_userinfo_repair
    WHERE source = 'model_calls' AND row_id = model_calls.id
)
WHERE id IN (SELECT row_id FROM endpoint_userinfo_repair WHERE source = 'model_calls');

UPDATE ocr_profile_revisions SET endpoint = (
    SELECT repaired_endpoint FROM endpoint_userinfo_repair
    WHERE source = 'ocr_profile_revisions' AND row_id = ocr_profile_revisions.revision_id
)
WHERE revision_id IN (
    SELECT row_id FROM endpoint_userinfo_repair WHERE source = 'ocr_profile_revisions'
);

DROP TABLE endpoint_userinfo_repair;
