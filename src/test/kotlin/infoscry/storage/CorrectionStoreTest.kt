package infoscry.storage

import infoscry.correction.CorrectionFindTakenException
import infoscry.correction.CorrectionRuleInput
import infoscry.domain.CollectionId
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

class CorrectionStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: CorrectionStore
    private var collectionId = CollectionId("unset")

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-corrections")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = CorrectionStore(database)
        collectionId = CollectionStore(database).create("Acme").id
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private val spinners = CorrectionRuleInput("spinners", "Spinnars", exactCase = false)

    @Test
    fun `putting the same rule twice keeps one row and its timestamps`() {
        val first = store.put(collectionId, "r1", spinners)
        val second = store.put(collectionId, "r1", spinners)

        assertEquals(first, second)
        assertEquals(listOf(first), store.list(collectionId))
    }

    @Test
    fun `replacing a rule keeps when it was created`() {
        val first = store.put(collectionId, "r1", spinners)
        val changed = store.put(collectionId, "r1", spinners.copy(replace = "Spinnarö"))

        assertEquals(first.createdAt, changed.createdAt)
        assertEquals("Spinnarö", store.rule(collectionId, "r1")?.replace)
    }

    @Test
    fun `values are stored normalized`() {
        val stored = store.put(collectionId, "r1", CorrectionRuleInput("  Karl  Johan ", "Carl Johan", false))

        assertEquals("Karl Johan", stored.find)
    }

    @Test
    fun `two rules may not find the same word`() {
        store.put(collectionId, "r1", spinners)

        assertFailsWith<CorrectionFindTakenException> {
            store.put(collectionId, "r2", CorrectionRuleInput("Spinners", "Spinnarsson", false))
        }
        assertEquals(1, store.list(collectionId).size)
    }

    @Test
    fun `an exact-case rule may sit beside a rule that ignores case`() {
        store.put(collectionId, "r1", spinners)
        store.put(collectionId, "r2", CorrectionRuleInput("Spinners", "Spinnarsson", true))

        assertEquals(listOf("r1", "r2"), store.list(collectionId).map { it.id })
    }

    @Test
    fun `deleting twice answers removed then already gone`() {
        store.put(collectionId, "r1", spinners)

        assertTrue(store.delete(collectionId, "r1"))
        assertFalse(store.delete(collectionId, "r1"))
        assertNull(store.rule(collectionId, "r1"))
    }

    @Test
    fun `a rule of another collection is not reachable`() {
        val other = CollectionStore(database).create("Other").id
        store.put(other, "r1", spinners)

        assertNull(store.rule(collectionId, "r1"))
        assertFalse(store.delete(collectionId, "r1"))
        assertEquals(emptyList(), store.list(collectionId))
    }

    @Test
    fun `deleting the collection removes its rules`() {
        store.put(collectionId, "r1", spinners)

        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM collections WHERE id = ?").use { statement ->
                statement.setString(1, collectionId.value)
                statement.executeUpdate()
            }
        }

        assertEquals(emptyList(), store.list(collectionId))
    }
}
