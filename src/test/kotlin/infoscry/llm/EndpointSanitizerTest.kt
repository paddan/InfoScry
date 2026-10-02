package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `userinfo is detected so a validator can refuse the URL instead of storing it`() {
        assertTrue(endpointCarriesUserInfo("https://user:pass@host/v1"))
        assertTrue(endpointCarriesUserInfo("https://user@host/v1"))
        assertFalse(endpointCarriesUserInfo("https://host/v1"))
        assertFalse(endpointCarriesUserInfo("https://host/a@b/c?token=secret"))
        assertFalse(endpointCarriesUserInfo("not a url"), "unparsable is not the same fact as credentialed")
    }
}