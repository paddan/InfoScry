package infoscry.cli

import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.server.ApiJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `infoscry llm` over the real process boundary with a local fake endpoint: the two-request capability
 * probe must persist its measured result, never fall back to a fake "not wired" answer, and refuse a
 * profile that does not exist.
 */
class LlmCommandTest {

    private val textOnly =
        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"

    private val toolCalling =
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"1\",\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"

    private lateinit var directory: Path
    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        directory = Files.createTempDirectory("infoscry-llm-cli")
        dataDir = Files.createDirectories(directory.resolve("data"))
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a profile whose text check succeeds but tool check is rejected persists text-supported tool-unsupported`() {
        val server = FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(stream = true, body = textOnly),
                // The tool probe gets a 401 from the scripted endpoint: the profile answers text, not tools.
                FakeOpenAiResponse(statusCode = 401, body = """{"error":{"message":"not allowed"}}"""),
            ),
        )
        try {
            addProfile(server.url)
            val probe = runTest("probe")
            assertTrue(probe.textRequestSupported, "the text probe succeeded: $probe")
            assertFalse(probe.toolCallingSupported ?: true, "the tool probe was rejected: $probe")
            val stored = ApiJson.decodeFromString<LlmProfilesJson>(runList()).profiles.first { it.name == "probe" }
            assertEquals(false, stored.toolCallingMeasured, "the measured result was persisted as false")
            assertNotNull(stored.capabilityCheckedAt, "the capability check time was persisted")
        } finally {
            server.close()
        }
    }

    @Test
    fun `a profile that answers both checks persists both as supported`() {
        val server = FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(stream = true, body = textOnly),
                FakeOpenAiResponse(stream = true, body = toolCalling),
            ),
        )
        try {
            addProfile(server.url)
            val probe = runTest("probe")
            assertTrue(probe.textRequestSupported, "the text probe succeeded: $probe")
            assertTrue(probe.toolCallingSupported ?: false, "the tool probe returned a call: $probe")
            val stored = ApiJson.decodeFromString<LlmProfilesJson>(runList()).profiles.first { it.name == "probe" }
            assertEquals(true, stored.toolCallingMeasured, "the measured result was persisted as true")
            assertNotNull(stored.capabilityCheckedAt, "the capability check time was persisted")
        } finally {
            server.close()
        }
    }

    @Test
    fun `a nonexistent profile name is an error and persists nothing`() {
        val result = CliProcess.run("llm", "test", "nobody", "--data-dir", dataDir.toString())

        assertNotEquals(0, result.exitCode, "a nonexistent profile reported success")
        assertTrue(
            (result.stdout + result.stderr).contains("no LLM profile named"),
            "the refusal names the missing profile: ${result.stdout + result.stderr}",
        )
        val none = ApiJson.decodeFromString<LlmProfilesJson>(runList())
        assertTrue(none.profiles.isEmpty(), "no profile exists, so nothing was persisted")
    }

    // ---- helpers ----

    private fun addProfile(endpoint: String) {
        val added = CliProcess.run(
            "llm", "add",
            "--name", "probe",
            "--provider", "openai-compatible",
            "--model", "test-model",
            "--endpoint", endpoint,
            "--data-dir", dataDir.toString(),
        )
        assertEquals(0, added.exitCode, "adding the probe failed: stderr=${added.stderr}")
    }

    private fun runTest(profile: String): LlmTestJson {
        val result = CliProcess.run("llm", "test", profile, "--data-dir", dataDir.toString())
        assertEquals(0, result.exitCode, "llm test failed: stderr=${result.stderr}")
        return ApiJson.decodeFromString<LlmTestJson>(result.stdout.trim())
    }

    private fun runList(): String {
        val list = CliProcess.run("llm", "list", "--json", "--data-dir", dataDir.toString())
        assertEquals(0, list.exitCode, "llm list --json failed: stderr=${list.stderr}")
        return list.stdout.trim()
    }
}