package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.llm.CollectionPrompt
import infoscry.llm.LlmPromptRole
import java.sql.ResultSet
import java.util.UUID

/**
 * Plain persistence for a collection's stored Ask and Investigate prompts and for each collection's
 * selected default prompt id. It does no validation; the service layer owns the rules, the reserved
 * name and repeat handling. Prompt bodies are user content and never appear in exception messages.
 */
class CollectionPromptStore(private val database: Database) {

    /** Stored prompts for [collectionId] and [role], by name case-insensitively. The shipped Default is not included. */
    fun list(collectionId: CollectionId, role: LlmPromptRole): List<CollectionPrompt> = database.read { connection ->
        connection.prepareStatement(
            "SELECT id, collection_id, role, name, body, version FROM collection_prompts " +
                "WHERE collection_id = ? AND role = ? ORDER BY name COLLATE NOCASE",
        ).use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, role.name)
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(readPrompt(results)) }
            }
        }
    }

    fun findById(id: String): CollectionPrompt? = database.read { connection ->
        connection.prepareStatement(
            "SELECT id, collection_id, role, name, body, version FROM collection_prompts WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { results -> if (results.next()) readPrompt(results) else null }
        }
    }

    /** Inserts a prompt at version 1 and returns it. */
    fun insert(collectionId: CollectionId, role: LlmPromptRole, name: String, body: String): CollectionPrompt {
        val id = UUID.randomUUID().toString()
        val now = Instants.now()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO collection_prompts (id, collection_id, role, name, body, version, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, collectionId.value)
                statement.setString(3, role.name)
                statement.setString(4, name)
                statement.setString(5, body)
                statement.setString(6, now)
                statement.setString(7, now)
                statement.executeUpdate()
            }
        }
        return checkNotNull(findById(id))
    }

    /**
     * Sets the name and body of prompt [id]. The version grows by one only when [body] differs from the
     * stored body; `updated_at` moves on every write.
     */
    fun update(id: String, name: String, body: String): CollectionPrompt {
        database.transaction { connection ->
            val stored = connection.prepareStatement(
                "SELECT body, version FROM collection_prompts WHERE id = ?",
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { results ->
                    if (results.next()) results.getString("body") to results.getInt("version") else null
                }
            } ?: throw IllegalArgumentException("no collection prompt with id $id")
            val (storedBody, storedVersion) = stored
            val version = if (storedBody == body) storedVersion else storedVersion + 1
            connection.prepareStatement(
                "UPDATE collection_prompts SET name = ?, body = ?, version = ?, updated_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, name)
                statement.setString(2, body)
                statement.setInt(3, version)
                statement.setString(4, Instants.now())
                statement.setString(5, id)
                statement.executeUpdate()
            }
        }
        return checkNotNull(findById(id))
    }

    /** Deletes prompt [id] and returns whether a row was removed. A collection default that pointed at it becomes NULL. */
    fun delete(id: String): Boolean = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM collection_prompts WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeUpdate() > 0
        }
    }

    /** The selected default prompt id for [role] in [collectionId]; null means the shipped Default. */
    fun defaultPromptId(collectionId: CollectionId, role: LlmPromptRole): String? = database.read { connection ->
        connection.prepareStatement(
            "SELECT ${promptColumn(role)} FROM collections WHERE id = ?",
        ).use { statement ->
            statement.setString(1, collectionId.value)
            statement.executeQuery().use { results ->
                if (results.next()) results.getString(1) else null
            }
        }
    }

    /** Sets the collection's default prompt for [role]; null selects the shipped Default. */
    fun setDefaultPromptId(collectionId: CollectionId, role: LlmPromptRole, promptId: String?) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE collections SET ${promptColumn(role)} = ?, updated_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, promptId)
                statement.setString(2, Instants.now())
                statement.setString(3, collectionId.value)
                statement.executeUpdate()
            }
        }
    }

    private fun promptColumn(role: LlmPromptRole): String = when (role) {
        LlmPromptRole.ASK -> "ask_prompt_id"
        LlmPromptRole.INVESTIGATE -> "investigate_prompt_id"
    }

    private fun readPrompt(results: ResultSet): CollectionPrompt = CollectionPrompt(
        id = results.getString("id"),
        collectionId = CollectionId(results.getString("collection_id")),
        role = LlmPromptRole.valueOf(results.getString("role")),
        name = results.getString("name"),
        body = results.getString("body"),
        version = results.getInt("version"),
        builtIn = false,
    )
}
