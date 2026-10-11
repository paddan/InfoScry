package infoscry.llm

import infoscry.investigate.*
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DuplicateLlmProfileNameException
import infoscry.storage.LlmProfileStore
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Database-level behavior: secrets never reach the database, defaults and capabilities persist. */
class LlmProfileStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: LlmProfileStore

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-llm")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = LlmProfileStore(database)
        // A new archive has no automatic Default collection, and these tests name one: create it the way
        // a person would rather than seeding a row the product no longer creates.
        CollectionStore(database).create("Default")
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

    @Test fun `store revalidates a caller-mutated override map before writing`() {
        val overrides = mutableMapOf(EffortLevel.LOW to EffortPreset(InvestigationLimits(2, 3, 20)))
        val profile = profile("Mutable overrides").copy(effortOverrides = overrides)
        store.create(profile)
        overrides[EffortLevel.LOW] = EffortPreset(InvestigationLimits(0, 3, 20))
        assertFailsWith<IllegalArgumentException> { store.update(profile.id, profile) }
        assertFailsWith<IllegalArgumentException> { store.create(profile) }
        assertEquals(2, store.findById(profile.id)!!.effortOverrides!![EffortLevel.LOW]!!.limits.maxToolRounds)
    }

    @Test
    fun `effort overrides persist across all lookup paths and repeated updates and can be cleared`() {
        val overrides = mapOf(EffortLevel.HIGH to EffortPreset(InvestigationLimits(7, 9, 60), NativeReasoning.OpenAiReasoningEffort("high")))
        val created = store.create(profile("effort").copy(effortOverrides = overrides))
        assertEquals(overrides, store.findById(created.id)?.effortOverrides)
        assertEquals(overrides, store.findByName(created.name)?.effortOverrides)
        assertEquals(overrides, store.list().single().effortOverrides)
        repeat(2) { assertEquals(overrides, store.update(created.id, created)?.effortOverrides) }
        repeat(2) { assertNull(store.update(created.id, created.copy(effortOverrides = null))?.effortOverrides) }
        assertNull(store.findById(created.id)?.effortOverrides)
    }

    @Test
    fun `profiles round trip`() {
        val created = store.create(profile(" local "))

        val loaded = store.findByName("local")
        assertNotNull(loaded)
        assertEquals("local", loaded.name)
        assertEquals("local", created.name)
        assertEquals("MY_SECRET_KEY", loaded.apiKeyEnvironmentVariable)
    }

    @Test
    fun `create refuses output budget that leaves no room for input`() {
        assertFailsWith<IllegalArgumentException> {
            store.create(profile("no input room").copy(contextWindow = 100, maxOutputTokens = 100))
        }
    }

    @Test
    fun `update refuses output budget that leaves no room for input`() {
        val original = profile("update budget")
        store.create(original)

        assertFailsWith<IllegalArgumentException> {
            store.update(original.id, original.copy(contextWindow = 100, maxOutputTokens = 100))
        }
        assertEquals(4_096, store.findById(original.id)?.maxOutputTokens)
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
    fun `a credential a row still holds cannot break the profile listing`() {
        val secret = "hunter2-not-a-real-credential"
        // Written around the profile type that refuses such a URL, the way a hand edit or a future bug would
        // write it: the listing these tests read through is what one such row used to fail.
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO llm_profiles (id, name, provider, endpoint, model, " +
                    "api_key_environment_variable, context_window, max_output_tokens, supports_tool_calling, " +
                    "input_price_per_million, output_price_per_million, cache_read_price_per_million, " +
                    "enabled, created_at, updated_at) VALUES ('hand-edited', 'Hand edited', " +
                    "'OPENAI_COMPATIBLE', ?, 'model', 'MY_SECRET_KEY', 128000, 4096, 0, 0.0, 0.0, 0.0, 1, " +
                    "'2026-09-30T07:00:00Z', '2026-09-30T07:00:00Z')",
            ).use { statement ->
                statement.setString(1, "https://user:$secret@api.example.com/v1")
                statement.executeUpdate()
            }
        }

        val listed = store.list()

        val profile = assertNotNull(listed.singleOrNull { it.name == "Hand edited" })
        assertEquals("https://api.example.com/v1", profile.endpoint, "the credential is dropped, never returned")
        assertFalse(profile.enabled, "a repaired address is not dispatched to until a person reviews it")
        assertFalse(listed.any { it.endpoint.contains(secret) }, "no profile read carries the credential")
        assertEquals(
            "https://api.example.com/v1",
            store.findByName("Hand edited")!!.endpoint,
            "every read of the row repairs it, not only the listing",
        )
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
    fun `updating a profile preserves its id and clears capability when endpoint or model changes`() {
        val original = profile("update-me").copy(endpoint = "https://one.example", toolCallingMeasured = true, capabilityCheckedAt = "2026-09-22T10:00:00Z")
        store.create(original)
        store.recordCapability(original.name, LlmCapabilityProbe(true, "2026-09-22T10:00:00Z"))

        val updated = store.update(original.id, original.copy(name = "renamed", model = "new-model"))

        assertNotNull(updated)
        assertEquals(original.id, updated.id)
        assertEquals("renamed", updated.name)
        assertNull(updated.toolCallingMeasured)
        assertNull(updated.capabilityCheckedAt)
    }

    @Test
    fun `updating unrelated fields preserves measured capability`() {
        val original = profile("update-me").copy(toolCallingMeasured = true, capabilityCheckedAt = "2026-09-22T10:00:00Z")
        store.create(original)
        store.recordCapability(original.name, LlmCapabilityProbe(true, "2026-09-22T10:00:00Z"))

        val updated = store.update(original.id, original.copy(name = "renamed"))

        assertEquals(true, updated?.toolCallingMeasured)
        assertEquals(original.capabilityCheckedAt, updated?.capabilityCheckedAt)
    }

    @Test
    fun `changing provider also clears measured capability`() {
        val original = profile("provider-change")
        store.create(original)
        store.recordCapability(original.name, LlmCapabilityProbe(true, "2026-09-22T10:00:00Z"))

        val updated = store.update(original.id, original.copy(provider = LlmProvider.ANTHROPIC))

        assertNull(updated?.toolCallingMeasured)
        assertNull(updated?.capabilityCheckedAt)
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
