package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.glossary.GlossaryIdTakenException
import infoscry.glossary.GlossaryTermInput
import infoscry.glossary.GlossaryTermTakenException
import infoscry.glossary.InvalidGlossaryException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlossaryStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: GlossaryStore
    private var collectionId = CollectionId("unset")

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-glossary")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = GlossaryStore(database)
        collectionId = CollectionStore(database).create("Acme").id
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun terms(vararg texts: String) = texts.map { GlossaryTermInput(it) }

    @Test
    fun `groups list in creation order with terms in typed order`() {
        store.put(collectionId, "g1", terms("Stockholm", "Sthlm"))
        store.put(collectionId, "g2", listOf(GlossaryTermInput("KTH"), GlossaryTermInput("Kungliga Tekniska högskolan")))

        val glossary = store.list(collectionId)

        assertEquals(listOf("g1", "g2"), glossary.groups.map { it.id })
        assertEquals(listOf("Stockholm", "Sthlm"), glossary.groups[0].terms.map { it.text })
        assertEquals(listOf("kungliga tekniska hogskolan"), glossary.groups[1].terms.drop(1).map { it.matchKey })
    }

    @Test
    fun `putting the same group twice keeps its timestamps`() {
        val first = store.put(collectionId, "g1", terms("Stockholm", "Sthlm"))
        val second = store.put(collectionId, "g1", terms("Stockholm", "Sthlm"))

        assertEquals(first, second)
        assertEquals(listOf(first), store.list(collectionId).groups)
    }

    @Test
    fun `replacing a group keeps when it was created and its new terms`() {
        val first = store.put(collectionId, "g1", terms("Stockholm", "Sthlm"))
        val changed = store.put(collectionId, "g1", listOf(GlossaryTermInput("Stockholm"), GlossaryTermInput("Sthlm", prefix = true)))

        assertEquals(first.createdAt, changed.createdAt)
        assertTrue(store.group(collectionId, "g1")!!.terms[1].prefix)
    }

    @Test
    fun `a term another group holds is refused with its index and that group`() {
        store.put(collectionId, "g1", terms("Stockholm", "Sthlm"))

        val refused = assertFailsWith<GlossaryTermTakenException> { store.put(collectionId, "g2", terms("Huvudstaden", "STHLM")) }

        assertEquals(1, refused.term)
        assertEquals("g1", refused.groupId)
        assertNull(store.group(collectionId, "g2"))
    }

    @Test
    fun `a decomposed term conflicts with its composed writing`() {
        store.put(collectionId, "g1", terms("Malmö", "Malmoe"))

        val refused = assertFailsWith<GlossaryTermTakenException> { store.put(collectionId, "g2", terms("Malmö", "Malmö kommun")) }

        assertEquals(0, refused.term)
    }

    @Test
    fun `a term moves to another group once it has left the first`() {
        store.put(collectionId, "g1", terms("Stockholm", "Sthlm", "Huvudstaden"))
        assertFailsWith<GlossaryTermTakenException> { store.put(collectionId, "g2", terms("Huvudstaden", "Capital")) }

        store.put(collectionId, "g1", terms("Stockholm", "Sthlm"))
        store.put(collectionId, "g2", terms("Huvudstaden", "Capital"))

        assertEquals(listOf("Stockholm", "Sthlm"), store.group(collectionId, "g1")!!.terms.map { it.text })
        assertEquals(listOf("Huvudstaden", "Capital"), store.group(collectionId, "g2")!!.terms.map { it.text })
    }

    @Test
    fun `an id another collection owns is refused`() {
        val other = CollectionStore(database).create("Other").id
        store.put(other, "g1", terms("Stockholm", "Sthlm"))

        assertFailsWith<GlossaryIdTakenException> { store.put(collectionId, "g1", terms("Göteborg", "Gbg")) }
        assertEquals(listOf("Stockholm", "Sthlm"), store.group(other, "g1")!!.terms.map { it.text })
    }

    @Test
    fun `an invalid group stores nothing`() {
        assertFailsWith<InvalidGlossaryException> { store.put(collectionId, "g1", terms("Stockholm")) }
        assertTrue(store.list(collectionId).groups.isEmpty())
    }

    @Test
    fun `deleting twice answers removed then gone`() {
        store.put(collectionId, "g1", terms("Stockholm", "Sthlm"))

        assertTrue(store.delete(collectionId, "g1"))
        assertFalse(store.delete(collectionId, "g1"))
        assertTrue(store.list(collectionId).groups.isEmpty())
    }

    @Test
    fun `deleting the collection removes its groups and terms`() {
        store.put(collectionId, "g1", terms("Stockholm", "Sthlm"))

        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM collections WHERE id = ?").use {
                it.setString(1, collectionId.value)
                it.executeUpdate()
            }
        }

        val remaining = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT (SELECT count(*) FROM collection_glossary_groups) + (SELECT count(*) FROM collection_glossary_terms)",
                ).use { rows -> rows.next(); rows.getInt(1) }
            }
        }
        assertEquals(0, remaining)
    }
}
