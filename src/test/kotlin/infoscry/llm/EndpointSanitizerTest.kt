package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class EndpointSanitizerTest {

    @Test
    fun `userinfo credentials are stripped from an endpoint`() {
        assertEquals("https://host/v1", sanitizedEndpoint("https://user:pass@host/v1"))
    }

    @Test
    fun `userinfo without a password is stripped too`() {
        assertEquals("https://host/v1", sanitizedEndpoint("https://user@host/v1"))
    }

    @Test
    fun `a clean endpoint is returned unchanged`() {
        val clean = "https://host/v1"
        assertEquals(clean, sanitizedEndpoint(clean))
    }

    @Test
    fun `a credential-looking path and query are left untouched`() {
        assertEquals("https://host/a@b/c?token=secret", sanitizedEndpoint("https://host/a@b/c?token=secret"))
    }

    @Test
    fun `port and path survive the strip`() {
        assertEquals("https://host:8443/v1/chat", sanitizedEndpoint("https://u:p@host:8443/v1/chat"))
    }
}