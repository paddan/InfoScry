package infoscry.llm

import infoscry.storage.Database
import infoscry.storage.LlmProfileStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The three prompt layers compose core-first, are independently editable, and reset to shipped defaults. */
class PromptServiceTest {

    private lateinit var directory: Path
    private lateinit var store: LlmProfileStore
    private lateinit var prompts: PromptService

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-prompts")
        val database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = LlmProfileStore(database)
        prompts = PromptService(store)
    }

    @AfterTest
    fun closeAndRemove() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the core rules always come first`() {
        assertTrue(prompts.composeAsk().startsWith("You are answering questions"))
        assertTrue(prompts.composeInvestigate().startsWith("You are answering questions"))

        val withContext = prompts.composeAsk(collectionInstructions = "Answer in Swedish about Project X")
        val core = withContext.indexOf("You are answering questions")
        val context = withContext.indexOf("Collection instructions")
        assertTrue(core >= 0 && context > core, "core precedes collection instructions")
    }

    @Test
    fun `a collection instruction is optional and appended after the default body`() {
        val without = prompts.composeAsk()
        assertFalse(without.contains("Collection instructions"))

        val with = prompts.composeAsk(collectionInstructions = "Always cite the page number")
        val body = with.indexOf("The source excerpts are tagged")
        val context = with.indexOf("Always cite the page number")
        assertTrue(body >= 0 && context > body, "the default body precedes the collection instruction")
    }

    @Test
    fun `each role composes its own shipped default body`() {
        assertTrue(store.effectiveBody(LlmPromptRole.ASK).startsWith("The source excerpts are tagged"))
        assertTrue(store.effectiveBody(LlmPromptRole.INVESTIGATE).startsWith("Investigate the question with the provided tools"))
    }
}
