package infoscry.storage

import infoscry.correction.CorrectionFindTakenException
import infoscry.correction.CorrectionRule
import infoscry.correction.CorrectionRuleInput
import infoscry.correction.CorrectionRules
import infoscry.domain.CollectionId
import java.sql.Connection
import java.sql.ResultSet

/**
 * A collection's standing correction rules. Every write is one transaction and is safe to repeat: putting the same
 * rule under the same id again changes nothing, and deleting a rule that is gone answers that it is gone. Rules are
 * user content and never appear in exception messages.
 */
class CorrectionStore(private val database: Database) {

    /** The collection's rules, oldest first. */
    fun list(collectionId: CollectionId): List<CorrectionRule> = database.read { connection ->
        connection.prepareStatement("$SELECT WHERE collection_id = ? ORDER BY created_at, id").use { statement ->
            statement.setString(1, collectionId.value)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toRule()) } }
        }
    }

    fun rule(collectionId: CollectionId, id: String): CorrectionRule? =
        database.read { connection -> connection.selectRule(collectionId, id) }

    /**
     * Creates rule [id], or replaces it, with [input] normalized by [CorrectionRules.normalize]. A replacement keeps
     * the rule's creation time; one that changes nothing keeps its update time too.
     *
     * @throws infoscry.correction.InvalidCorrectionException when the rule cannot be stored as written.
     * @throws CorrectionFindTakenException when another rule of the collection already finds the same word.
     */
    fun put(collectionId: CollectionId, id: String, input: CorrectionRuleInput): CorrectionRule {
        require(id.isNotBlank()) { "a correction needs an id" }
        val rule = CorrectionRules.normalize(input)
        val key = CorrectionRules.findKey(rule)
        return database.transaction { connection ->
            val existing = connection.selectRule(collectionId, id)
            val taken = connection.prepareStatement(
                "SELECT id FROM collection_corrections WHERE collection_id = ? AND find_key = ? AND id <> ?",
            ).use { statement ->
                statement.setString(1, collectionId.value)
                statement.setString(2, key)
                statement.setString(3, id)
                statement.executeQuery().use { rows -> rows.next() }
            }
            if (taken) throw CorrectionFindTakenException()
            if (existing != null &&
                existing.find == rule.find && existing.replace == rule.replace && existing.exactCase == rule.exactCase
            ) {
                return@transaction existing
            }
            val now = Instants.now()
            connection.prepareStatement(
                "INSERT INTO collection_corrections (id, collection_id, find, find_key, replace, exact_case, " +
                    "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET find = excluded.find, find_key = excluded.find_key, " +
                    "replace = excluded.replace, exact_case = excluded.exact_case, updated_at = excluded.updated_at " +
                    "WHERE collection_corrections.collection_id = excluded.collection_id",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, collectionId.value)
                statement.setString(3, rule.find)
                statement.setString(4, key)
                statement.setString(5, rule.replace)
                statement.setInt(6, if (rule.exactCase) 1 else 0)
                statement.setString(7, now)
                statement.setString(8, now)
                // A rule id another collection owns updates nothing here, which is the same answer as a stale id.
                check(statement.executeUpdate() == 1) { "a correction with that id belongs to another collection" }
            }
            checkNotNull(connection.selectRule(collectionId, id))
        }
    }

    /** Removes rule [id] and answers whether it was there. */
    fun delete(collectionId: CollectionId, id: String): Boolean = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM collection_corrections WHERE collection_id = ? AND id = ?").use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, id)
            statement.executeUpdate() > 0
        }
    }

    private fun Connection.selectRule(collectionId: CollectionId, id: String): CorrectionRule? =
        prepareStatement("$SELECT WHERE collection_id = ? AND id = ?").use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRule() else null }
        }

    private fun ResultSet.toRule(): CorrectionRule = CorrectionRule(
        id = getString("id"),
        collectionId = CollectionId(getString("collection_id")),
        find = getString("find"),
        replace = getString("replace"),
        exactCase = getInt("exact_case") == 1,
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

    private companion object {
        const val SELECT =
            "SELECT id, collection_id, find, replace, exact_case, created_at, updated_at FROM collection_corrections"
    }
}
