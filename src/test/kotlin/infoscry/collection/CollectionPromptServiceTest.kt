package infoscry.collection

import infoscry.AppContext
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId
import infoscry.llm.LlmPromptRole
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
import kotlinx.coroutines.runBlocking

class CollectionPromptServiceTest {

    private lateinit var dataDir: Path
    private lateinit var context: AppContext
    private var collectionId: CollectionId = CollectionId("unset")

    @BeforeTest
    fun openContextWithCollection() {
        dataDir = Files.createTempDirectory("infoscry-collection-prompt-service")
        context = AppContext.open(dataDir)
        collectionId = runBlocking { context.collectionService.create("Acme").id }
    }

    @AfterTest
    fun closeContextAndRemoveData() {
        context.close()
        dataDir.toFile().deleteRecursively()
    }

    private val service get() = context.collectionPrompts

    private fun create(name: String, body: String = "Answer briefly.", role: LlmPromptRole = LlmPromptRole.ASK) =
        runBlocking { service.create(collectionId, role, name, body) }

    private fun updatedAt(promptId: String): String = context.database.read { connection ->
        connection.prepareStatement("SELECT updated_at FROM collection_prompts WHERE id = ?").use { statement ->
            statement.setString(1, promptId)
            statement.executeQuery().use { results ->
                results.next()
                results.getString(1)
            }
        }
    }

    @Test
    fun `create twice with same name and body returns created=false and the same id`() {
        val first = create("Minutes")
        val second = create("Minutes")

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.prompt.id, second.prompt.id)
        assertEquals(1, service.list(collectionId).ask.prompts.count { !it.builtIn })
    }

    @Test
    fun `create with taken name and other body throws PromptNameTaken`() {
        create("Minutes", "first body")

        val error = assertFailsWith<PromptNameTaken> { create("Minutes", "other body") }

        assertEquals(ApiStatus.CONFLICT, error.apiStatus)
        assertEquals("PROMPT_NAME_TAKEN", error.code)
    }

    @Test
    fun `names compare case-insensitively beyond ASCII`() {
        create("Ägare", "first body")

        assertFailsWith<PromptNameTaken> { create("ägare", "other body") }
    }

    @Test
    fun `the same name in the other role is allowed`() {
        create("Minutes", "ask body", LlmPromptRole.ASK)

        val investigate = create("Minutes", "investigate body", LlmPromptRole.INVESTIGATE)

        assertTrue(investigate.created)
    }

    @Test
    fun `Default is reserved`() {
        for (name in listOf("Default", " default ", "DEFAULT")) {
            assertFailsWith<InvalidPromptRequest>("name '$name'") { create(name) }
        }
    }

    @Test
    fun `name over 80 chars, blank name, blank body and body over 20000 chars are refused`() {
        assertFailsWith<InvalidPromptRequest> { create("a".repeat(81)) }
        assertFailsWith<InvalidPromptRequest> { create("   ") }
        assertFailsWith<InvalidPromptRequest> { create("Blank body", "   ") }
        assertFailsWith<InvalidPromptRequest> { create("Long body", "x".repeat(20_001)) }
    }

    @Test
    fun `name of exactly 80 chars and body of exactly 20000 chars are accepted`() {
        val write = create("a".repeat(80), "x".repeat(20_000))

        assertTrue(write.created)
    }

    @Test
    fun `update with no change returns the prompt and leaves updated_at unchanged`() {
        val created = create("Minutes", "body").prompt
        val before = updatedAt(created.id)

        val updated = runBlocking { service.update(collectionId, created.id, "Minutes", "body") }

        assertEquals(created, updated)
        assertEquals(before, updatedAt(created.id))
    }

    @Test
    fun `update with a changed body increments the version`() {
        val created = create("Minutes", "body").prompt

        val updated = runBlocking { service.update(collectionId, created.id, "Minutes", "new body") }

        assertEquals(2, updated.version)
        assertEquals("new body", updated.body)
    }

    @Test
    fun `update onto another prompt's name throws PromptNameTaken`() {
        create("Minutes", "body")
        val other = create("Notes", "body").prompt

        assertFailsWith<PromptNameTaken> {
            runBlocking { service.update(collectionId, other.id, "minutes", "different") }
        }
    }

    @Test
    fun `update or delete of default throws InvalidPromptRequest`() {
        assertFailsWith<InvalidPromptRequest> {
            runBlocking { service.update(collectionId, "default", "Renamed", "body") }
        }
        assertFailsWith<InvalidPromptRequest> { runBlocking { service.delete(collectionId, "default") } }
    }

    @Test
    fun `delete twice succeeds`() {
        val created = create("Minutes").prompt

        runBlocking {
            service.delete(collectionId, created.id)
            service.delete(collectionId, created.id)
        }

        assertEquals(emptyList(), service.list(collectionId).ask.prompts.filterNot { it.builtIn })
    }

    @Test
    fun `delete of a prompt from another collection is refused and removes nothing`() {
        val otherCollection = runBlocking { context.collectionService.create("Other").id }
        val foreign = runBlocking { service.create(otherCollection, LlmPromptRole.ASK, "Minutes", "body").prompt }

        assertFailsWith<InvalidPromptRequest> { runBlocking { service.delete(collectionId, foreign.id) } }
        assertEquals(foreign.id, service.list(otherCollection).ask.prompts.last().id)
    }

    @Test
    fun `setDefault to the value in effect is a no-op and returns its id`() {
        val created = create("Minutes").prompt

        val first = runBlocking { service.setDefault(collectionId, LlmPromptRole.ASK, created.id) }
        val second = runBlocking { service.setDefault(collectionId, LlmPromptRole.ASK, created.id) }
        val reset = runBlocking { service.setDefault(collectionId, LlmPromptRole.ASK, "default") }
        val resetAgain = runBlocking { service.setDefault(collectionId, LlmPromptRole.ASK, "default") }

        assertEquals(created.id, first)
        assertEquals(created.id, second)
        assertEquals("default", reset)
        assertEquals("default", resetAgain)
    }

    @Test
    fun `setDefault with an id from another collection or role throws InvalidPromptRequest`() {
        val askPrompt = create("Minutes", "body", LlmPromptRole.ASK).prompt
        val otherCollection = runBlocking { context.collectionService.create("Other").id }
        val foreign = runBlocking { service.create(otherCollection, LlmPromptRole.ASK, "Minutes", "body").prompt }

        assertFailsWith<InvalidPromptRequest> {
            runBlocking { service.setDefault(collectionId, LlmPromptRole.INVESTIGATE, askPrompt.id) }
        }
        assertFailsWith<InvalidPromptRequest> {
            runBlocking { service.setDefault(collectionId, LlmPromptRole.ASK, foreign.id) }
        }
    }

    @Test
    fun `resolve(null) returns the collection default, or Default when none`() {
        val resolvedDefault = service.resolve(collectionId, LlmPromptRole.ASK, null)
        assertNull(resolvedDefault.id)
        assertEquals("Default", resolvedDefault.name)
        assertEquals(1, resolvedDefault.version)

        val created = create("Minutes", "body").prompt
        runBlocking { service.setDefault(collectionId, LlmPromptRole.ASK, created.id) }

        assertEquals(created.id, service.resolve(collectionId, LlmPromptRole.ASK, null).id)
    }

    @Test
    fun `resolve by name and by id, and an unknown ref throws InvalidPromptRequest`() {
        val created = create("Minutes", "body").prompt

        val byName = service.resolve(collectionId, LlmPromptRole.ASK, "minutes")
        val byId = service.resolve(collectionId, LlmPromptRole.ASK, created.id)

        assertEquals(created.id, byName.id)
        assertEquals(created.id, byId.id)
        assertEquals("body", byId.body)
        assertFailsWith<InvalidPromptRequest> { service.resolve(collectionId, LlmPromptRole.ASK, "Unknown") }
        assertFailsWith<InvalidPromptRequest> {
            service.resolve(collectionId, LlmPromptRole.INVESTIGATE, "Minutes")
        }
    }

    @Test
    fun `list puts Default first with builtIn true and reports defaultId`() {
        val created = create("Minutes", "body").prompt

        val listed = service.list(collectionId)
        assertEquals("default", listed.ask.defaultId)
        assertEquals("Default", listed.ask.prompts.first().name)
        assertTrue(listed.ask.prompts.first().builtIn)
        assertEquals(listOf(created.id), listed.ask.prompts.drop(1).map { it.id })
        assertEquals(service.list(collectionId), listed)

        runBlocking { service.setDefault(collectionId, LlmPromptRole.ASK, created.id) }
        assertEquals(created.id, service.list(collectionId).ask.defaultId)
    }

    @Test
    fun `rename-only update keeps version 1`() {
        val created = create("Minutes", "body").prompt

        val renamed = runBlocking { service.update(collectionId, created.id, "Renamed", "body") }

        assertEquals(1, renamed.version)
        assertEquals("Renamed", renamed.name)
    }

    @Test
    fun `deleting the collection default resets the role to Default`() {
        val created = create("Minutes", "body").prompt
        runBlocking { service.setDefault(collectionId, LlmPromptRole.ASK, created.id) }

        runBlocking { service.delete(collectionId, created.id) }

        assertEquals("default", service.list(collectionId).ask.defaultId)
        assertNull(service.resolve(collectionId, LlmPromptRole.ASK, null).id)
    }

    @Test
    fun `update of an unknown id or an id from another collection throws InvalidPromptRequest`() {
        val otherCollection = runBlocking { context.collectionService.create("Other").id }
        val foreign = runBlocking { service.create(otherCollection, LlmPromptRole.ASK, "Minutes", "body").prompt }

        assertFailsWith<InvalidPromptRequest> {
            runBlocking { service.update(collectionId, "no-such-id", "Minutes", "changed") }
        }
        assertFailsWith<InvalidPromptRequest> {
            runBlocking { service.update(collectionId, foreign.id, "Minutes", "changed") }
        }
    }

    @Test
    fun `reserved Default name is refused on update and rename`() {
        val created = create("Minutes", "body").prompt

        for (name in listOf("Default", " default ", "DEFAULT")) {
            assertFailsWith<InvalidPromptRequest>("name '$name'") {
                runBlocking { service.update(collectionId, created.id, name, "body") }
            }
        }
    }

    @Test
    fun `writes on a DELETING collection are refused`() {
        context.collectionService.beginDeletion(collectionId, "Acme")

        assertFailsWith<NoSuchElementException> { create("Minutes") }
    }
}
