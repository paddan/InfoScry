package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: CollectionStore

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-collections")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = CollectionStore(database)
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a new archive holds no collection for the store to list`() {
        assertEquals(emptyList(), store.list().map { it.name })
        assertNull(store.get(CollectionStore.DEFAULT_ID), "the legacy seed is retired, not recreated")
    }

    @Test
    fun `create trims the name and stores english ocr languages by default`() {
        val created = store.create("  Project Nightfall  ")

        assertEquals("Project Nightfall", created.name)
        assertEquals("eng", created.ocrLanguages)
        assertEquals(CollectionLifecycle.ACTIVE, created.lifecycle)
        assertEquals(created, store.get(created.id))
    }

    @Test
    fun `create keeps the requested ocr languages and description`() {
        val created = store.create("Acme", description = "Contracts and invoices", ocrLanguages = "swe+eng+deu")

        assertEquals("Contracts and invoices", created.description)
        assertEquals("swe+eng+deu", created.ocrLanguages)
        assertEquals("Contracts and invoices", store.get(created.id)?.description)
    }

    @Test
    fun `create rejects blank names and blank ocr languages`() {
        assertFailsWith<IllegalArgumentException> { store.create("   ") }
        assertFailsWith<IllegalArgumentException> { store.create("") }
        assertFailsWith<IllegalArgumentException> { store.create("Acme", ocrLanguages = " ") }

        assertEquals(emptyList(), store.list().map { it.name })
    }

    @Test
    fun `create rejects a name that differs only in case`() {
        store.create("Nightfall")

        assertFailsWith<DuplicateCollectionNameException> { store.create("nightfall") }
        assertFailsWith<DuplicateCollectionNameException> { store.create("NIGHTFALL") }

        assertEquals(1, store.list().size)
    }

    @Test
    fun `list orders collections by name so the order does not depend on timestamp ties`() {
        val alpha = store.create("Alpha")
        val beta = store.create("Beta")

        assertEquals(listOf("Alpha", "Beta"), store.list().map { it.name })
        assertEquals(listOf(alpha.id, beta.id), store.list().map { it.id })
    }

    @Test
    fun `get returns null for an unknown collection`() {
        assertNull(store.get(CollectionId("missing")))
    }

    @Test
    fun `rename updates the name and timestamp and keeps the collection's identity`() {
        val original = store.create("Case")
        Thread.sleep(2) // timestamps are millisecond-resolution; let the clock tick so a rewrite is visible

        val renamed = store.rename(original.id, "  Everything  ")

        assertEquals("Everything", renamed.name)
        assertEquals(original.id, renamed.id)
        assertEquals(original.createdAt, renamed.createdAt)
        assertNotEquals(original.updatedAt, renamed.updatedAt)
        assertEquals("Everything", store.get(original.id)?.name)
    }

    @Test
    fun `rename rejects duplicates blanks and unknown collections`() {
        store.create("Alpha")
        val beta = store.create("Beta")

        assertFailsWith<DuplicateCollectionNameException> { store.rename(beta.id, "alpha") }
        assertFailsWith<IllegalArgumentException> { store.rename(beta.id, "   ") }
        assertFailsWith<NoSuchElementException> { store.rename(CollectionId("missing"), "Gamma") }

        assertEquals("Beta", store.get(beta.id)?.name)
    }

    @Test
    fun `delete requires the exact name and reports whether the collection existed`() {
        val alpha = store.create("Alpha")

        assertFalse(store.delete(CollectionId("missing"), "Missing"))
        assertFailsWith<CollectionConfirmationMismatchException> { store.delete(alpha.id, "Beta") }
        assertTrue(store.delete(alpha.id, "Alpha"))
        assertNull(store.get(alpha.id))
        assertFalse(store.delete(alpha.id, "Alpha"))
    }
}
