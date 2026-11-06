package infoscry.storage

import java.util.UUID

/** One stored import root, with the name of whoever added it (`null` when nobody did in person). */
data class StoredImportRoot(val id: String, val path: String, val addedAt: String, val addedBy: String?)

/** Persistence for `import_roots`. Which paths are acceptable is decided by `infoscry.jobs.ImportRoots`. */
class ImportRootStore(private val database: Database) {

    fun list(): List<StoredImportRoot> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT r.id, r.path, r.created_at, u.display_name FROM import_roots r " +
                    "LEFT JOIN users u ON u.id = r.created_by ORDER BY r.created_at, r.path",
            ).use { rows ->
                buildList { while (rows.next()) add(StoredImportRoot(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))) }
            }
        }
    }

    /** Adds [path] unless it is already a root, and answers the root with whether this call added it. */
    fun add(path: String): Pair<StoredImportRoot, Boolean> = database.transaction { connection ->
        val now = Instants.now()
        val inserted = connection.prepareStatement(
            "INSERT INTO import_roots (id, path, created_at, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT (path) DO NOTHING",
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, path)
            statement.setString(3, now)
            statement.setString(4, now)
            statement.executeUpdate() == 1
        }
        val root = connection.prepareStatement(
            "SELECT r.id, r.path, r.created_at, u.display_name FROM import_roots r LEFT JOIN users u ON u.id = r.created_by WHERE r.path = ?",
        ).use { statement ->
            statement.setString(1, path)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "the import root just written is missing" }
                StoredImportRoot(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))
            }
        }
        root to inserted
    }

    /** Removes the root [id]; removing one that is not there changes nothing. */
    fun remove(id: String): Boolean = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM import_roots WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeUpdate() == 1
        }
    }
}
