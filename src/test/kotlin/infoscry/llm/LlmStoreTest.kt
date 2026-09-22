package infoscry.llm

import infoscry.storage.Database
import infoscry.storage.DuplicateLlmProfileNameException
import infoscry.storage.LlmStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Database-level behavior: secrets never reach the database, defaults and capabilities persist. */
class LlmStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: LlmStore

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-llm")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = LlmStore(database)
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun profile(
        name: String,
        apiKeyEnvironmentVariable: String? = "MY_SECRET_KEY",
    ) = LlmProfile(
        id = UUID.randomUUID().toString(),
        name = name,
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "model",
        contextWindow = 128_000,
        maxOutputTokens = 4_096,
        inputPricePerMillion = 0.0,
        outputPricePerMillion = 0.0,
        cacheReadPricePerMillion = 0.0,
        enabled = true,
        apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
    )

    @Test
    fun `profiles round trip`() {
        store.create(profile("local"))

        val loaded = store.findByName("local")
        assertNotNull(loaded)
        assertEquals("local", loaded.name)
        assertEquals("MY_SECRET_KEY", loaded.apiKeyEnvironmentVariable)
    }

    @Test
    fun `duplicate profile names are refused case-insensitively`() {
        store.create(profile("Claude"))
        assertFailsWith<DuplicateLlmProfileNameException> { store.create(profile("claude")) }
    }

    @Test
    fun `the stored row holds the variable name, not a secret value`() {
        store.create(profile("key-holder", apiKeyEnvironmentVariable = "ANTHROPIC_API_KEY"))

        val row = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT api_key_environment_variable FROM llm_profiles WHERE name = 'key-holder'",
                ).use { it.getString(1) }
            }
        }
        assertEquals("ANTHROPIC_API_KEY", row)
    }

    @Test
    fun `a credential string is rejected as an environment variable name`() {
        // Passing a token as the variable name must be refused by validation, never persisted.
        assertFailsWith<IllegalArgumentException> {
            store.create(profile("bad", apiKeyEnvironmentVariable = "sk-ant-this-is-not-a-name"))
        }
    }

    @Test
    fun `defaults are selected and read back independently per role`() {
        store.create(profile("ask-model"))
        store.create(profile("investigate-model"))

        assertTrue(store.setDefault(LlmPromptRole.ASK, "ask-model"))
        assertTrue(store.setDefault(LlmPromptRole.INVESTIGATE, "investigate-model"))

        assertEquals("ask-model", store.defaultProfileName(LlmPromptRole.ASK))
        assertEquals("investigate-model", store.defaultProfileName(LlmPromptRole.INVESTIGATE))

        // Re-pointing Investigate must not move Ask.
        store.setDefault(LlmPromptRole.INVESTIGATE, "ask-model")
        assertEquals("ask-model", store.defaultProfileName(LlmPromptRole.ASK))
        assertEquals("ask-model", store.defaultProfileName(LlmPromptRole.INVESTIGATE))
    }

    @Test
    fun `setting a default for an unknown profile is refused`() {
        assertFalse(store.setDefault(LlmPromptRole.ASK, "missing"))
    }

    @Test
    fun `a capability probe persists as a measurement`() {
        store.create(profile("probed"))
        store.recordCapability("probed", LlmCapabilityProbe(toolCallingSupported = true, checkedAt = "2026-09-21T10:00:00Z"))

        val loaded = store.findByName("probed")!!
        assertTrue(loaded.toolCallingSupported)
        assertEquals("2026-09-21T10:00:00Z", loaded.capabilityCheckedAt)
    }

    @Test
    fun `prompt overrides are editable and resettable to the shipped default`() {
        val shipped = store.effectiveBody(LlmPromptRole.ASK)
        assertTrue(shipped.isNotBlank(), "a shipped Ask body exists")

        store.savePromptBody(LlmPromptRole.ASK, "custom ask body")
        assertEquals("custom ask body", store.effectiveBody(LlmPromptRole.ASK))

        store.resetPromptBody(LlmPromptRole.ASK)
        assertEquals(shipped, store.effectiveBody(LlmPromptRole.ASK), "resetting restores the shipped default")
    }
}
