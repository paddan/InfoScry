package infoscry.server

import io.ktor.client.statement.bodyAsText
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class LlmCatalogRoutesTest {
    private lateinit var dataDir: java.nio.file.Path
    private lateinit var harness: ApiTestServer

    @BeforeTest fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-llm-catalog-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test fun `presets are listed without their static fallback lists`() = runBlocking {
        val response = harness.get("/api/llm/presets")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(body, "\"id\":\"OPENAI\"")
        assertContains(body, "\"apiKeyEnvironmentVariable\":\"OPENAI_API_KEY\"")
        assertFalse(body.contains("staticModels"))
        assertEquals(5, Regex("\"id\":\"").findAll(body).count())
    }

    @Test fun `catalog returns the provider list and authenticates with the named env var`() = runBlocking {
        FakeOpenAiServer(
            listOf(FakeOpenAiResponse(statusCode = 200, body = """{"data":[{"id":"gpt-4o"}]}""")),
        ).use { fake ->
            val path = "/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=${fake.url}&apiKeyEnvironmentVariable=PATH"
            val response = harness.request(HttpMethod.Get, path, credential = Credential.CSRF)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "\"id\":\"gpt-4o\"")
            assertEquals("Bearer ${System.getenv("PATH")}", fake.authorization)
        }
    }

    @Test fun `catalog falls back when the provider is unreachable`() = runBlocking {
        val response = harness.request(
            HttpMethod.Get,
            "/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=http://127.0.0.1:1",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "\"live\":false")
    }

    @Test fun `catalog rejects an uncredentialed caller before any request or key lookup`() = runBlocking {
        FakeOpenAiServer(
            listOf(FakeOpenAiResponse(statusCode = 200, body = """{"data":[{"id":"gpt-4o"}]}""")),
        ).use { fake ->
            val path = "/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=${fake.url}&apiKeyEnvironmentVariable=PATH"
            val response = harness.request(HttpMethod.Get, path, credential = Credential.NONE)
            assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "CATALOG_REQUIRES_CREDENTIALS")
            assertEquals(0, fake.handledRequests, "the key must not be sent without a valid credential")
        }
    }

    @Test fun `invalid catalog requests are rejected without echoing input`() = runBlocking {
        assertEquals(
            HttpStatusCode.BadRequest,
            harness.request(HttpMethod.Get, "/api/llm/catalog?provider=NOPE&endpoint=https://api.openai.com/v1", credential = Credential.CSRF).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            harness.request(HttpMethod.Get, "/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=not-a-url", credential = Credential.CSRF).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            harness.request(HttpMethod.Get, "/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=https://x/v1&apiKeyEnvironmentVariable=not a name", credential = Credential.CSRF).status,
        )
    }

    @Test fun `catalog rejects endpoints without an authority or host`() = runBlocking {
        assertEquals(
            HttpStatusCode.BadRequest,
            harness.request(HttpMethod.Get, "/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=http:foo", credential = Credential.CSRF).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            harness.request(HttpMethod.Get, "/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=https://", credential = Credential.CSRF).status,
        )
    }
}