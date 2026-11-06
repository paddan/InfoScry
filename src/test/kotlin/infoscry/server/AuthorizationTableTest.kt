package infoscry.server

import io.ktor.http.HttpMethod
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.routing
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every API route has an entry in [ACCESS_RULES], and every entry names a route that exists. */
class AuthorizationTableTest {

    @Test
    fun `every API route in the routing tree has an access rule and every rule has a route`() {
        val dataDir = Files.createTempDirectory("infoscry-authorization-table")
        try {
            ApiTestServer(dataDir).use { harness ->
                val routes = harness.server.application.routing { }.getAllRoutes().mapNotNull { route ->
                    val text = route.toString()
                    val method = Regex("""\(method:([A-Z]+)\)""").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                    val path = "/" + text.split('/').filter { it.isNotEmpty() && !it.startsWith("(") }.joinToString("/")
                    if (!path.startsWith("/api/")) return@mapNotNull null
                    HttpMethod.parse(method) to path
                }.toSet()

                assertTrue(routes.size > 50, "the routing tree was not read: $routes")
                val unclassified = routes - ACCESS_RULES.keys
                val stale = ACCESS_RULES.keys - routes
                assertEquals(emptySet(), unclassified, "routes with no access rule")
                assertEquals(emptySet(), stale, "access rules for routes that do not exist")
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a literal segment wins over a parameter`() {
        assertEquals(AccessRule.InCollection(infoscry.account.CollectionRole.READER), ruleFor(HttpMethod.Get, "/api/collections/c1/documents/summary")?.first)
        assertEquals(AccessRule.Administrator, ruleFor(HttpMethod.Post, "/api/ocr/profiles/from-llm")?.first)
        assertEquals("c1", ruleFor(HttpMethod.Get, "/api/collections/c1/documents/d1")?.second?.get("id"))
    }
}
