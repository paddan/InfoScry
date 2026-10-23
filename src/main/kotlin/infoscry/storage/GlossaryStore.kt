package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.glossary.Glossary
import infoscry.glossary.GlossaryGroup
import infoscry.glossary.GlossaryIdTakenException
import infoscry.glossary.GlossaryRules
import infoscry.glossary.GlossaryTerm
import infoscry.glossary.GlossaryTermInput
import infoscry.glossary.GlossaryTermTakenException
import java.sql.Connection

/**
 * A collection's glossary groups. Every write is one transaction and is safe to repeat: putting the same group under
 * the same id again changes nothing, and deleting a group that is gone answers that it is gone. Terms are user
 * content and never appear in exception messages.
 */
class GlossaryStore(private val database: Database) {

    /** The collection's groups, oldest first, each with its terms in the order typed. */
    fun list(collectionId: CollectionId): Glossary = database.read { connection ->
        Glossary(collectionId, connection.selectGroups(collectionId, null))
    }

    fun group(collectionId: CollectionId, id: String): GlossaryGroup? =
        database.read { connection -> connection.selectGroups(collectionId, id).singleOrNull() }

    /**
     * Creates group [id], or replaces it, with [terms] normalized by [GlossaryRules.normalize]. A replacement keeps the
     * group's creation time; one that changes nothing keeps its update time too.
     *
     * @throws infoscry.glossary.InvalidGlossaryException when the group cannot be stored as written.
     * @throws GlossaryTermTakenException when another group of the collection already has one of the terms.
     * @throws GlossaryIdTakenException when another collection's group has the id.
     */
    fun put(collectionId: CollectionId, id: String, terms: List<GlossaryTermInput>): GlossaryGroup {
        require(id.isNotBlank()) { "a glossary group needs an id" }
        val normalized = GlossaryRules.normalize(terms)
        val submittedKeys = terms.map { term -> GlossaryRules.key(term.text) }
        return database.transaction { connection ->
            val owner = connection.prepareStatement("SELECT collection_id FROM collection_glossary_groups WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (owner != null && owner != collectionId.value) throw GlossaryIdTakenException()
            connection.prepareStatement(
                "SELECT match_key, group_id FROM collection_glossary_terms WHERE collection_id = ? AND group_id <> ?",
            ).use { statement ->
                statement.setString(1, collectionId.value)
                statement.setString(2, id)
                val taken = statement.executeQuery().use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                }
                submittedKeys.forEachIndexed { index, key -> taken[key]?.let { throw GlossaryTermTakenException(index, it) } }
            }
            val existing = connection.selectGroups(collectionId, id).singleOrNull()
            if (existing != null && existing.terms == normalized) return@transaction existing
            val now = Instants.now()
            connection.prepareStatement(
                "INSERT INTO collection_glossary_groups (id, collection_id, created_at, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET updated_at = excluded.updated_at",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, collectionId.value)
                statement.setString(3, now)
                statement.setString(4, now)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM collection_glossary_terms WHERE group_id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO collection_glossary_terms (group_id, collection_id, ordinal, text, match_key, prefix) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                normalized.forEachIndexed { ordinal, term ->
                    statement.setString(1, id)
                    statement.setString(2, collectionId.value)
                    statement.setInt(3, ordinal)
                    statement.setString(4, term.text)
                    statement.setString(5, term.matchKey)
                    statement.setInt(6, if (term.prefix) 1 else 0)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            checkNotNull(connection.selectGroups(collectionId, id).singleOrNull())
        }
    }

    /** Removes group [id] and answers whether it was there. */
    fun delete(collectionId: CollectionId, id: String): Boolean = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM collection_glossary_groups WHERE collection_id = ? AND id = ?").use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, id)
            statement.executeUpdate() > 0
        }
    }

    private fun Connection.selectGroups(collectionId: CollectionId, id: String?): List<GlossaryGroup> {
        val sql = "SELECT g.id, g.created_at, g.updated_at, t.text, t.match_key, t.prefix " +
            "FROM collection_glossary_groups g JOIN collection_glossary_terms t ON t.group_id = g.id " +
            "WHERE g.collection_id = ?" + (if (id != null) " AND g.id = ?" else "") +
            " ORDER BY g.created_at, g.id, t.ordinal"
        return prepareStatement(sql).use { statement ->
            statement.setString(1, collectionId.value)
            if (id != null) statement.setString(2, id)
            statement.executeQuery().use { rows ->
                val groups = LinkedHashMap<String, GlossaryGroup>()
                while (rows.next()) {
                    val groupId = rows.getString(1)
                    val term = GlossaryTerm(rows.getString(4), rows.getString(5), rows.getInt(6) == 1)
                    val group = groups[groupId]
                    groups[groupId] = group?.copy(terms = group.terms + term)
                        ?: GlossaryGroup(groupId, collectionId, listOf(term), rows.getString(2), rows.getString(3))
                }
                groups.values.toList()
            }
        }
    }
}
