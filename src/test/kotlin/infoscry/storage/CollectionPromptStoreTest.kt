package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.llm.LlmPromptRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionPromptStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var collections: CollectionStore
    private lateinit var store: CollectionPromptStore
    private var collectionId: CollectionId = CollectionId("unset")

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-collection-prompts")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        collections = CollectionStore(database)
        store = CollectionPromptStore(database)
        collectionId = collections.create("Acme").id
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `insert then list returns the prompt at version 1`() {
        val inserted = store.insert(collectionId, LlmPromptRole.ASK, "Board minutes", "Answer briefly.")

        assertEquals(1, inserted.version)
        assertFalse(inserted.builtIn)
        assertTrue(inserted.id.isNotBlank())
        assertEquals(listOf(inserted), store.list(collectionId, LlmPromptRole.ASK))
        assertEquals(emptyList(), store.list(collectionId, LlmPromptRole.INVESTIGATE))
        assertEquals(inserted, store.findById(inserted.id))
    }

    @Test
    fun `update with a changed body increments version`() {
        val inserted = store.insert(collectionId, LlmPromptRole.ASK, "Minutes", "first body")

        val updated = store.update(inserted.id, "Minutes", "second body")

        assertEquals(2, updated.version)
        assertEquals("second body", updated.body)
        assertEquals(updated, store.findById(inserted.id))
    }

    @Test
    fun `update with only a new name keeps version`() {
        val inserted = store.insert(collectionId, LlmPromptRole.ASK, "Minutes", "same body")

        val renamed = store.update(inserted.id, "Renamed minutes", "same body")

        assertEquals(1, renamed.version)
        assertEquals("Renamed minutes", renamed.name)
    }

    @Test
    fun `delete of the collection default sets defaultPromptId to null`() {
        val inserted = store.insert(collectionId, LlmPromptRole.ASK, "Minutes", "body")
        store.setDefaultPromptId(collectionId, LlmPromptRole.ASK, inserted.id)
        assertEquals(inserted.id, store.defaultPromptId(collectionId, LlmPromptRole.ASK))

        assertTrue(store.delete(inserted.id))

        assertNull(store.findById(inserted.id))
        assertNull(store.defaultPromptId(collectionId, LlmPromptRole.ASK))
        assertFalse(store.delete(inserted.id), "deleting an absent prompt reports nothing removed")
    }

    @Test
    fun `deleting the collection removes its prompts`() {
        val inserted = store.insert(collectionId, LlmPromptRole.INVESTIGATE, "Search", "body")
        store.setDefaultPromptId(collectionId, LlmPromptRole.INVESTIGATE, inserted.id)

        assertTrue(collections.delete(collectionId, "Acme"))

        assertEquals(emptyList(), store.list(collectionId, LlmPromptRole.INVESTIGATE))
        assertNull(store.findById(inserted.id))
    }

    @Test
    fun `setDefaultPromptId round-trips per role and null clears it`() {
        val ask = store.insert(collectionId, LlmPromptRole.ASK, "Minutes", "body")
        assertNull(store.defaultPromptId(collectionId, LlmPromptRole.ASK))

        store.setDefaultPromptId(collectionId, LlmPromptRole.ASK, ask.id)
        assertEquals(ask.id, store.defaultPromptId(collectionId, LlmPromptRole.ASK))
        assertNull(store.defaultPromptId(collectionId, LlmPromptRole.INVESTIGATE), "the other role is untouched")

        store.setDefaultPromptId(collectionId, LlmPromptRole.ASK, null)
        assertNull(store.defaultPromptId(collectionId, LlmPromptRole.ASK))
    }
}
