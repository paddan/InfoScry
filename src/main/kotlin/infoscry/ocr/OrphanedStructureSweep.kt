package infoscry.ocr

import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.storage.Database
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Removes page structure artifacts that no committed row names.
 *
 * A structure file is written (content-addressed) before the page's checkpoint commits. A crash in between
 * normally converges on rerun, because the same bytes get the same name. A file is orphaned only when the
 * rerun produced different text, and then nothing names the earlier file any more.
 *
 * What is referenced is every `structure_relative_path` of the document's units and of all of its revisions,
 * history included, so a previous version that is kept in history keeps its structure. The sweep is safe to
 * repeat and removes nothing that a row names. It runs only at startup, before any worker is attached, so no
 * import can be between writing a file and committing its row. Nothing but regular `*.structure.json.gz`
 * files directly under the `structure` directory is touched, and original files are never reachable from here.
 *
 * Like the rest of [StaleOcrStateCleanup] it assumes a single process owns the data directory (the process
 * lock guarantees it) and that no worker is attached yet.
 */
class OrphanedStructureSweep(private val database: Database, private val paths: AppPaths) {

    /** The number of artifacts removed. */
    fun run(): Int = documents().sumOf { (collectionId, documentId) -> sweep(collectionId, documentId) }

    private fun documents(): List<Pair<CollectionId, DocumentId>> = database.read { connection ->
        connection.prepareStatement("SELECT collection_id, id FROM documents").use { statement ->
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(CollectionId(rows.getString(1)) to DocumentId(rows.getString(2))) }
            }
        }
    }

    private fun sweep(collectionId: CollectionId, documentId: DocumentId): Int {
        val root = paths.artifactsDir(collectionId, documentId)
        val directory = root.resolve(DIRECTORY)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return 0
        val named = referenced(documentId)
        var removed = 0
        try {
            Files.list(directory).use { entries ->
                entries.filter { isStructureFile(it) }.forEach { file ->
                    if ("$DIRECTORY/${file.fileName}" !in named) {
                        try {
                            if (Files.deleteIfExists(file)) removed++
                        } catch (unremovable: IOException) {
                            // Left for the next startup; an orphan costs only disk.
                        }
                    }
                }
            }
        } catch (unreadable: IOException) {
            // The same: nothing is removed from a directory that cannot be listed.
        }
        return removed
    }

    private fun isStructureFile(file: Path): Boolean =
        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.fileName.toString().endsWith(SUFFIX)

    private fun referenced(documentId: DocumentId): Set<String> = database.read { connection ->
        connection.prepareStatement(
            "SELECT structure_relative_path FROM content_units " +
                "WHERE document_id = ? AND structure_relative_path IS NOT NULL " +
                "UNION SELECT p.structure_relative_path FROM page_text_revisions p " +
                "JOIN document_revisions r ON r.id = p.revision_id " +
                "WHERE r.document_id = ? AND p.structure_relative_path IS NOT NULL",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, documentId.value)
            statement.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private companion object {
        const val DIRECTORY = "structure"
        const val SUFFIX = ".structure.json.gz"
    }
}
