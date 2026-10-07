package infoscry.server

import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking

class LlmProfileRoutesTest {
    private lateinit var dataDir: java.nio.file.Path
    private lateinit var harness: ApiTestServer

    @BeforeTest fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-llm-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private val profileBody = """{"name":"Test profile","provider":"OPENAI_COMPATIBLE","endpoint":"https://api.example/v1","model":"test-model","contextWindow":8192,"maxOutputTokens":1024,"inputPricePerMillion":0.0,"outputPricePerMillion":0.0,"cacheReadPricePerMillion":0.0,"enabled":true,"apiKeyEnvironmentVariable":"PATH"}"""

    private suspend fun create(body: String = profileBody) = harness.request(
        HttpMethod.Post, "/api/llm/profiles", body, Credential.CSRF,
    )

    @Test fun `profile CRUD exposes key variable presence but never its value`() = runBlocking {
        val created = create()
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val body = created.bodyAsText()
        assertContains(body, "PATH")
        assertContains(body, "\"keyAvailable\":true")
        assertFalse(body.contains(System.getenv("PATH")))
        val id = Regex(""""id":"([^"]+)"""").find(body)!!.groupValues[1]

        val updated = harness.request(HttpMethod.Put, "/api/llm/profiles/$id", profileBody.replace("Test profile", "Renamed"), Credential.CSRF)
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        assertContains(updated.bodyAsText(), id)
        assertContains(updated.bodyAsText(), "Renamed")

        // The last profile can no longer be deleted; deletion of a non-final profile is covered by the
        // dedicated test below.
        val refused = harness.request(HttpMethod.Delete, "/api/llm/profiles/$id", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        assertContains(harness.get("/api/llm/profiles").bodyAsText(), id)
    }

    @Test fun `the last profile cannot be deleted`() = runBlocking {
        val created = create()
        val id = Regex(""""id":"([^"]+)"""").find(created.bodyAsText())!!.groupValues[1]

        val refused = harness.request(HttpMethod.Delete, "/api/llm/profiles/$id", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "LAST_LLM_PROFILE")
        assertEquals(1, harness.context.llm.list().size)

        val second = create(profileBody.replace("Test profile", "Second profile"))
        val secondId = Regex(""""id":"([^"]+)"""").find(second.bodyAsText())!!.groupValues[1]
        val deleted = harness.request(HttpMethod.Delete, "/api/llm/profiles/$secondId", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(1, harness.context.llm.list().size)
    }

    @Test fun `profile name conflicts ignore case and unknown ids return not found`() = runBlocking {
        create()
        val conflict = create(profileBody.replace("Test profile", "test PROFILE"))
        assertEquals(HttpStatusCode.Conflict, conflict.status)

        val missing = harness.request(HttpMethod.Put, "/api/llm/profiles/missing", profileBody, Credential.CSRF)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        val deleteMissing = harness.request(HttpMethod.Delete, "/api/llm/profiles/missing", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.NotFound, deleteMissing.status)
    }

    @Test fun `defaults are independently set and returned`() = runBlocking {
        val created = create()
        val id = Regex(""""id":"([^"]+)"""").find(created.bodyAsText())!!.groupValues[1]
        val ask = harness.request(HttpMethod.Put, "/api/llm/defaults/ASK", """{"profileId":"$id"}""", Credential.CSRF)
        assertEquals(HttpStatusCode.OK, ask.status, ask.bodyAsText())
        val investigate = harness.request(HttpMethod.Put, "/api/llm/defaults/INVESTIGATE", """{"profileId":"$id"}""", Credential.CSRF)
        assertEquals(HttpStatusCode.OK, investigate.status, investigate.bodyAsText())
        val missing = harness.request(HttpMethod.Put, "/api/llm/defaults/INVESTIGATE", """{"profileId":"missing"}""", Credential.CSRF)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        // A second profile makes the defaulted first profile deletable; its dangling default then reads back null.
        create(profileBody.replace("Test profile", "Second profile"))
        val deleted = harness.request(HttpMethod.Delete, "/api/llm/profiles/$id", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        val profiles = harness.get("/api/llm/profiles").bodyAsText()
        assertTrue(profiles.contains("\"ASK\":null"))
        assertTrue(profiles.contains("\"INVESTIGATE\":null"))
    }

    @Test fun `profile requests cannot forge measured capability and invalid profiles are rejected`() = runBlocking {
        val forged = create(profileBody.dropLast(1) + ",\"toolCallingMeasured\":true,\"capabilityCheckedAt\":\"yesterday\"}")
        assertEquals(HttpStatusCode.Created, forged.status, forged.bodyAsText())
        assertFalse(forged.bodyAsText().contains("toolCallingMeasured"))

        val invalid = create(profileBody.replace("PATH", "sk-secret-value"))
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertFalse(invalid.bodyAsText().contains("sk-secret-value"))
    }

    @Test fun `profile writes are refused during exclusive maintenance`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val maintenance = async(Dispatchers.Default) {
            harness.context.mutations.withExclusiveMaintenance("reindex") {
                started.complete(Unit)
                release.await()
            }
        }
        withTimeout(5_000) { started.await() }
        try {
            val refused = create()
            assertEquals(HttpStatusCode.Locked, refused.status, refused.bodyAsText())
            assertContains(refused.bodyAsText(), "MAINTENANCE_IN_PROGRESS")
            assertTrue(harness.context.llm.list().isEmpty())
        } finally {
            release.complete(Unit)
            withTimeout(5_000) { maintenance.await() }
        }
    }

    // ---- Tool-calling measurement from Admin (ticket 01) ----

    private val textOnly =
        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"

    private val toolCalling =
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"1\",\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"

    private fun profileAt(endpoint: String, enabled: Boolean = true) =
        """{"name":"Probed profile","provider":"OPENAI_COMPATIBLE","endpoint":"$endpoint","model":"test-model","contextWindow":8192,"maxOutputTokens":1024,"inputPricePerMillion":0.0,"outputPricePerMillion":0.0,"cacheReadPricePerMillion":0.0,"enabled":$enabled,"apiKeyEnvironmentVariable":null}"""

    private fun idOf(body: String): String = Regex(""""id":"([^"]+)"""").find(body)!!.groupValues[1]

    @Test fun `the probe route measures tool calling through the provider and persists it`() = runBlocking {
        val provider = FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(stream = true, body = textOnly),
                FakeOpenAiResponse(stream = true, body = toolCalling),
            ),
        )
        try {
            val id = idOf(create(profileAt(provider.url)).bodyAsText())

            val probed = harness.request(HttpMethod.Post, "/api/llm/profiles/$id/probe", credential = Credential.CSRF)

            assertEquals(HttpStatusCode.OK, probed.status, probed.bodyAsText())
            val answer = probed.bodyAsText()
            assertContains(answer, "\"textRequestSupported\":true")
            assertContains(answer, "\"toolCallingSupported\":true")
            assertContains(answer, "\"toolCallingMeasured\":true")
            assertEquals(2, provider.requestBodies.size, "the probe is the two-request check, once")
            assertContains(provider.requestBodies[1], "\"ping\"")
            assertEquals(true, harness.context.llm.findById(id)?.toolCallingMeasured)
            assertContains(harness.get("/api/llm/profiles").bodyAsText(), "\"toolCallingMeasured\":true")
        } finally {
            provider.close()
        }
    }

    @Test fun `a provider that rejects the tool request is measured unsupported and its body is never echoed`() = runBlocking {
        val leaked = "provider-echoed-secret-value"
        val provider = FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(statusCode = 401, body = """{"error":{"message":"$leaked"}}"""),
                FakeOpenAiResponse(statusCode = 401, body = """{"error":{"message":"$leaked"}}"""),
            ),
        )
        try {
            val id = idOf(create(profileAt(provider.url)).bodyAsText())

            val probed = harness.request(HttpMethod.Post, "/api/llm/profiles/$id/probe", credential = Credential.CSRF)

            assertEquals(HttpStatusCode.OK, probed.status, probed.bodyAsText())
            assertContains(probed.bodyAsText(), "\"toolCallingSupported\":false")
            assertFalse(probed.bodyAsText().contains(leaked), "the provider body is not echoed")
            assertEquals(false, harness.context.llm.findById(id)?.toolCallingMeasured)
        } finally {
            provider.close()
        }
    }

    @Test fun `a switched-off profile is refused before any provider call and nothing is persisted`() = runBlocking {
        val provider = FakeOpenAiServer(emptyList())
        try {
            val id = idOf(create(profileAt(provider.url, enabled = false)).bodyAsText())

            val refused = harness.request(HttpMethod.Post, "/api/llm/profiles/$id/probe", credential = Credential.CSRF)

            assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
            assertContains(refused.bodyAsText(), "LLM_PROFILE_DISABLED")
            assertTrue(provider.requestBodies.isEmpty(), "no request reached the provider")
            assertEquals(null, harness.context.llm.findById(id)?.toolCallingMeasured)
        } finally {
            provider.close()
        }
    }

    @Test fun `probing an unknown profile is not found`() = runBlocking {
        val missing = harness.request(HttpMethod.Post, "/api/llm/profiles/missing/probe", credential = Credential.CSRF)

        assertEquals(HttpStatusCode.NotFound, missing.status, missing.bodyAsText())
    }
}
