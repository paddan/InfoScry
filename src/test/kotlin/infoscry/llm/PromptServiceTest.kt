package infoscry.llm

import infoscry.storage.Database
import infoscry.storage.LlmStore
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
    private lateinit var store: LlmStore
    private lateinit var prompts: PromptService

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-prompts")
        val database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = LlmStore(database)
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
        val body = with.indexOf("Answer the question using ONLY")
        val context = with.indexOf("Always cite the page number")
        assertTrue(body >= 0 && context > body, "the default body precedes the collection instruction")
    }

    @Test
    fun `editing one role's default does not change the other`() {
        store.savePromptBody(LlmPromptRole.ASK, "custom ask body")

        assertTrue(store.effectiveBody(LlmPromptRole.ASK).contains("custom ask body"))
        // The Investigate body is untouched, and it is a distinct shipped default.
        assertFalse(store.effectiveBody(LlmPromptRole.INVESTIGATE).contains("custom ask body"))
    }

    @Test
    fun `resetting a role restores its shipped default`() {
        store.savePromptBody(LlmPromptRole.INVESTIGATE, "custom investigate body")
        store.resetPromptBody(LlmPromptRole.INVESTIGATE)

        assertFalse(store.effectiveBody(LlmPromptRole.INVESTIGATE).contains("custom investigate body"))
        assertTrue(store.effectiveBody(LlmPromptRole.INVESTIGATE).isNotBlank())
    }
}
