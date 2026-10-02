package infoscry.storage

import infoscry.llm.LlmProvider
import infoscry.llm.endpointCarriesUserInfo
import infoscry.llm.sanitizedEndpoint
import infoscry.ocr.OcrProfile
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrProfileRevisionDraft
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/** An OCR profile name is already used (compared case-insensitively). */
class DuplicateOcrProfileNameException(val name: String) :
    IllegalStateException("an OCR profile named '$name' already exists")

/**
 * Persistence for OCR profiles and their immutable revisions.
 *
 * A profile is a name, an enabled flag and a pointer to the revision it currently reads through. Editing
 * adds a revision row and repoints the pointer in one transaction, so a job that snapshotted revision A
 * keeps reading A after the profile is edited to B, and disabling a profile removes it from selection
 * without touching the history that references it: nothing is ever hard-deleted here.
 *
 * The rows hold the *name* of an environment variable, never a key value, which is also what the migration's
 * CHECK enforces so nothing can be written around the record's own validation.
 */
class OcrProfileStore(private val database: Database) {

    /** Every profile, enabled or not, in name order, each joined to the revision it current reads. */
    fun list(): List<OcrProfile> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("$SELECT_PROFILE ORDER BY p.name COLLATE NOCASE").use { rows ->
                buildList { while (rows.next()) add(rows.toProfile()) }
            }
        }
    }

    /** One profile by its identifier, or null when no such profile exists. */
    fun findById(id: String): OcrProfile? = database.read { connection ->
        selectProfile(connection, id)
    }

    /**
     * Several profiles by their identifiers, read in one transaction.
     *
     * A snapshot that names two profiles has to freeze one moment: two separate lookups could straddle an
     * edit, and the snapshot would then describe a state the archive was never in. Identifiers with no row
     * are simply absent from the result, so a caller states the missing ones itself instead of reading a null
     * out of a map it has to interpret.
     */
    fun findByIds(ids: Collection<String>): Map<String, OcrProfile> = database.read { connection ->
        ids.distinct().mapNotNull { id -> selectProfile(connection, id) }.associateBy { profile -> profile.id }
    }

    /**
     * One revision by its identifier, whichever profile owns it and whether or not it is current.
     *
     * This is the read a resume needs: an attempt snapshot names a revision, and the endpoint, model and
     * limits it dispatched to have to be readable after the profile moved on.
     */
    fun findRevision(revisionId: String): OcrProfileRevision? = database.read { connection ->
        connection.prepareStatement("$SELECT_REVISION WHERE revision_id = ?").use { statement ->
            statement.setString(1, revisionId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRevision() else null }
        }
    }

    /**
     * Creates a profile with its first revision: sequence 1, since a profile is never without a revision.
     *
     * The two rows reference each other — the profile points at the revision it owns — so the profile is
     * inserted first with the identifier of the revision this call is about to write, and the deferred
     * foreign key is checked when the transaction commits. Both rows therefore exist or neither does.
     */
    fun create(name: String, draft: OcrProfileRevisionDraft, enabled: Boolean): OcrProfile {
        val trimmedName = requireName(name)
        val profileId = UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val now = Instants.now()
        database.transaction { connection ->
            try {
                connection.prepareStatement(
                    "INSERT INTO ocr_profiles (id, name, enabled, current_revision_id, created_at, " +
                        "updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, profileId)
                    statement.setString(2, trimmedName)
                    statement.setInt(3, enabled.asInt())
                    statement.setString(4, revisionId)
                    statement.setString(5, now)
                    statement.setString(6, now)
                    statement.executeUpdate()
                }
            } catch (failure: SQLException) {
                failure.rethrowAsNameConflict(trimmedName)
            }
            insertRevision(connection, revisionId, profileId, sequence = 1, draft = draft, createdAt = now)
        }
        return findById(profileId)!!
    }

    /**
     * Edits a profile: a new immutable revision, then the profile's name, enabled flag and pointer.
     *
     * Null when no profile has that id, so a route can answer not-found without touching anything. The
     * revision the profile pointed at before is left exactly as it was, because an attempt that already
     * snapshotted it still describes that attempt.
     */
    fun update(id: String, name: String, draft: OcrProfileRevisionDraft, enabled: Boolean): OcrProfile? {
        require(id.isNotBlank()) { "an OCR profile needs an id" }
        val trimmedName = requireName(name)
        val updated = database.transaction { connection ->
            val existing = selectProfile(connection, id) ?: return@transaction null
            val revisionId = UUID.randomUUID().toString()
            val now = Instants.now()
            insertRevision(
                connection,
                revisionId,
                id,
                sequence = existing.revision.sequence + 1,
                draft = draft,
                createdAt = now,
            )
            try {
                connection.prepareStatement(
                    "UPDATE ocr_profiles SET name = ?, enabled = ?, current_revision_id = ?, updated_at = ? " +
                        "WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, trimmedName)
                    statement.setInt(2, enabled.asInt())
                    statement.setString(3, revisionId)
                    statement.setString(4, now)
                    statement.setString(5, id)
                    statement.executeUpdate()
                }
            } catch (failure: SQLException) {
                failure.rethrowAsNameConflict(trimmedName)
            }
            true
        }
        return if (updated == true) findById(id) else null
    }

    /**
     * Disables a profile so no new collection may select it, keeping every revision.
     *
     * Returns whether the profile exists. Disabling is idempotent: a profile already disabled stays
     * disabled, and the answer is still that it exists.
     */
    fun disable(id: String): Boolean = database.transaction { connection ->
        connection.prepareStatement(
            "UPDATE ocr_profiles SET enabled = 0, updated_at = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, Instants.now())
            statement.setString(2, id)
            statement.executeUpdate() > 0
        }
    }

    /**
     * Records what a synthetic-image capability check measured for one revision.
     *
     * The measurement belongs to the revision rather than to the profile: a check of revision A says nothing
     * about revision B, so an edit that repoints the profile leaves A's measurement describing A. False when
     * no such revision exists.
     */
    fun recordImageCapability(revisionId: String, supported: Boolean, checkedAt: String): Boolean =
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE ocr_profile_revisions SET image_capability_measured = ?, " +
                    "image_capability_checked_at = ? WHERE revision_id = ?",
            ).use { statement ->
                statement.setInt(1, supported.asInt())
                statement.setString(2, checkedAt)
                statement.setString(3, revisionId)
                statement.executeUpdate() > 0
            }
        }

    private fun insertRevision(
        connection: Connection,
        revisionId: String,
        profileId: String,
        sequence: Int,
        draft: OcrProfileRevisionDraft,
        createdAt: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, endpoint, " +
                "model, api_key_environment_variable, context_window, max_output_tokens, " +
                "input_price_per_million, output_price_per_million, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.setString(2, profileId)
            statement.setInt(3, sequence)
            statement.setString(4, draft.provider.name)
            statement.setString(5, draft.endpoint.takeIf { it.isNotBlank() })
            statement.setString(6, draft.model)
            statement.setString(7, draft.apiKeyEnvironmentVariable)
            statement.setInt(8, draft.contextWindow)
            statement.setInt(9, draft.maxOutputTokens)
            statement.setDouble(10, draft.inputPricePerMillion)
            statement.setDouble(11, draft.outputPricePerMillion)
            statement.setString(12, createdAt)
            statement.executeUpdate()
        }
    }

    private fun selectProfile(connection: Connection, id: String): OcrProfile? =
        connection.prepareStatement("$SELECT_PROFILE WHERE p.id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toProfile() else null }
        }

    private fun requireName(name: String): String {
        require(name.isNotBlank()) { "an OCR profile needs a name" }
        return name.trim()
    }

    /**
     * The schema's unique index on `name COLLATE NOCASE` is the authority on duplicate names, so the store
     * translates its violation instead of racing a `SELECT` before every insert or update.
     */
    private fun SQLException.rethrowAsNameConflict(name: String): Nothing =
        if (message?.contains("UNIQUE", ignoreCase = true) == true) {
            throw DuplicateOcrProfileNameException(name)
        } else {
            throw this
        }

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private fun ResultSet.toProfile(): OcrProfile = OcrProfile(
        id = getString("id"),
        name = getString("name"),
        // A credential in the revision's URL is not a state to read back as one: the credential is dropped
        // and the profile is shown disabled, exactly as migration 020 leaves such a row, so a row written
        // before the endpoint rule existed cannot fail the whole listing on the way out.
        enabled = getInt("enabled") != 0 && !credentialInEndpoint(),
        revision = toRevision(),
    )

    private fun ResultSet.toRevision(): OcrProfileRevision {
        val measuredValue = getInt("image_capability_measured")
        val measured = if (wasNull()) null else measuredValue != 0
        return OcrProfileRevision(
            revisionId = getString("revision_id"),
            profileId = getString("profile_id"),
            sequence = getInt("sequence"),
            provider = LlmProvider.valueOf(getString("provider")),
            endpoint = storedEndpointWithoutCredential(),
            model = getString("model"),
            contextWindow = getInt("context_window"),
            maxOutputTokens = getInt("max_output_tokens"),
            inputPricePerMillion = getDouble("input_price_per_million"),
            outputPricePerMillion = getDouble("output_price_per_million"),
            apiKeyEnvironmentVariable = getString("api_key_environment_variable"),
            imageCapabilityMeasured = measured,
            imageCapabilityCheckedAt = getString("image_capability_checked_at"),
            createdAt = getString("created_at"),
        )
    }

    /**
     * The endpoint column as it may be read: a stored credential is dropped rather than returned.
     *
     * Migration 020 repairs what a legacy archive already holds, and this is the second line of defence for
     * a row that arrived some other way — written by hand, or by a future version with its own bug. Only the
     * RFC 3986 `userinfo` component is removed, so an endpoint whose path or query merely contains an `@`
     * keeps it.
     */
    private fun ResultSet.storedEndpointWithoutCredential(): String {
        val stored = getString("endpoint") ?: ""
        return if (credentialInEndpoint()) sanitizedEndpoint(stored) else stored
    }

    /**
     * Whether the endpoint this row holds carries userinfo credentials.
     *
     * Read from the value rather than from a flag, so the answer is about this row and not about what some
     * earlier statement decided.
     */
    private fun ResultSet.credentialInEndpoint(): Boolean = endpointCarriesUserInfo(getString("endpoint") ?: "")

    private companion object {

        const val REVISION_COLUMNS =
            "revision_id, profile_id, sequence, provider, endpoint, model, " +
                "api_key_environment_variable, context_window, max_output_tokens, " +
                "input_price_per_million, output_price_per_million, image_capability_measured, " +
                "image_capability_checked_at, created_at"

        const val SELECT_REVISION = "SELECT $REVISION_COLUMNS FROM ocr_profile_revisions"

        const val SELECT_PROFILE =
            "SELECT p.id, p.name, p.enabled, r.revision_id, r.profile_id, r.sequence, r.provider, " +
                "r.endpoint, r.model, r.api_key_environment_variable, r.context_window, " +
                "r.max_output_tokens, r.input_price_per_million, r.output_price_per_million, " +
                "r.image_capability_measured, r.image_capability_checked_at, r.created_at " +
                "FROM ocr_profiles p JOIN ocr_profile_revisions r ON r.revision_id = p.current_revision_id"
    }
}
