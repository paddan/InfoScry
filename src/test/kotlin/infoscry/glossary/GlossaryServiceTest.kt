package infoscry.glossary

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.jobs.seedCollectionWithId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GlossaryServiceTest {

    private lateinit var dataDir: Path
    private val collection = CollectionId("default")

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-glossary-service")
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    private val stockholm = listOf(GlossaryTermInput("Stockholm"), GlossaryTermInput("Sthlm"))

    @Test
    fun `a saved group is in the next snapshot and gone after it is removed`() {
        AppContext.open(dataDir).use { context ->
            assertTrue(context.glossaryService.snapshot(collection).groups.isEmpty())

            runBlocking { context.glossaryService.put(collection, "g1", stockholm) }
            assertEquals(listOf("g1"), context.glossaryService.snapshot(collection).groups.map { it.id })

            runBlocking { context.glossaryService.put(collection, "g1", stockholm + GlossaryTermInput("Sthlms stad")) }
            assertEquals(3, context.glossaryService.snapshot(collection).groups.single().terms.size)

            runBlocking { context.glossaryService.delete(collection, "g1") }
            runBlocking { context.glossaryService.delete(collection, "g1") }
            assertTrue(context.glossaryService.snapshot(collection).groups.isEmpty())
        }
    }

    @Test
    fun `a collection that does not exist is refused`() {
        AppContext.open(dataDir).use { context ->
            assertFails { runBlocking { context.glossaryService.put(CollectionId("missing"), "g1", stockholm) } }
            assertFails { context.glossaryService.list(CollectionId("missing")) }
        }
    }
}
