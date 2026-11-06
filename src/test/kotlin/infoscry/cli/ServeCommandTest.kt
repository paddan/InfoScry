package infoscry.cli

import infoscry.server.ApiJson
import infoscry.server.CollectionsResponse
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

/**
 * `infoscry serve` as a real process: it publishes how to reach it, it refuses to start twice, and a killed
 * server can be started again at once.
 *
 * The second server is the case worth pinning. Two processes writing one database is the failure the
 * single-writer design exists to prevent, and the operator has to be told that clearly instead of seeing a
 * crash or, worse, a second process that seems to work.
 */
class ServeCommandTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-serve")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `serve publishes a reachable URL on stdout and nothing else`() {
        val cli = CliProcess.startRunning(*serveArgs("--json", "serve", "--port", "0"))
        try {
            val published = ApiJson.decodeFromString<ServeResponse>(cli.awaitStdoutLine("{"))

            assertEquals(cli.pid, published.pid)
            assertTrue(published.url.startsWith("http://127.0.0.1:"))
            assertEquals(HttpStatusCode.OK, runBlocking { get("${published.url}/api/session").status })
            assertEquals(
                1,
                cli.stdout().count { it.startsWith("{") },
                "machine-readable output has to be one line, so it can be piped into a parser",
            )
        } finally {
            cli.kill()
        }
    }

    @Test
    fun `serve flag starts the same local server from the compiled CLI entry point`() {
        val cli = CliProcess.startRunning("--serve", "--data-dir", dataDir.toString(), "--port", "0", "--json")
        try {
            val published = ApiJson.decodeFromString<ServeResponse>(cli.awaitStdoutLine("{"))
            assertEquals(HttpStatusCode.OK, runBlocking { get("${published.url}/api/session").status })
        } finally {
            cli.kill()
        }
    }

    @Test
    fun `interactive serve opens the listening URL in the default browser`() {
        val browserRecord = dataDir.resolve("opened-url")
        val fakeOpen = Files.createDirectories(dataDir.resolve("browser-bin")).resolve("open")
        Files.writeString(fakeOpen, "#!/bin/sh\nprintf '%s' \"\$1\" > \"\$INFOSCRY_BROWSER_RECORD\"\n")
        assertTrue(fakeOpen.toFile().setExecutable(true))
        val environment = mapOf(
            "PATH" to "${fakeOpen.parent}:${System.getenv("PATH")}",
            "INFOSCRY_BROWSER_RECORD" to browserRecord.toString(),
        )

        val cli = CliProcess.startRunning("--serve", "--data-dir", dataDir.toString(), "--port", "0", environment = environment)
        try {
            val line = cli.awaitStdoutLine("InfoScry is listening on ")
            val url = line.removePrefix("InfoScry is listening on ")
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!Files.exists(browserRecord) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(browserRecord), "the server did not ask the OS to open a browser")
            assertEquals(url, Files.readString(browserRecord))
            assertEquals(HttpStatusCode.OK, runBlocking { get("$url/api/session").status })
        } finally {
            cli.kill()
        }
    }

    @Test
    fun `browser launch failure leaves the server available and prints its URL`() {
        val fakeOpen = Files.createDirectories(dataDir.resolve("browser-bin")).resolve("open")
        Files.writeString(fakeOpen, "#!/bin/sh\nexit 1\n")
        assertTrue(fakeOpen.toFile().setExecutable(true))
        val environment = mapOf("PATH" to "${fakeOpen.parent}:${System.getenv("PATH")}")

        val cli = CliProcess.startRunning("serve", "--data-dir", dataDir.toString(), "--port", "0", environment = environment)
        try {
            val url = cli.awaitStdoutLine("InfoScry is listening on ").removePrefix("InfoScry is listening on ")
            val deadline = System.nanoTime() + 3_000_000_000L
            while (cli.stderr().none { it.contains("Could not open the browser") } && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(cli.stderr().any { it.contains("Could not open the browser; use $url instead.") })
            assertEquals(HttpStatusCode.OK, runBlocking { get("$url/api/session").status })
        } finally {
            cli.kill()
        }
    }

    @Test
    fun `serve reports an occupied port without a stack trace`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupiedPort ->
            val result = CliProcess.run(*serveArgs("serve", "--port", occupiedPort.localPort.toString()))

            assertNotEquals(0, result.exitCode)
            assertContains(result.stderr, "InfoScry could not bind 127.0.0.1:${occupiedPort.localPort}")
            assertContains(result.stderr, "Address already in use")
            assertTrue(!result.stderr.contains("Exception in thread"), result.stderr)
        }
    }

    @Test
    fun `a second serve on the same data directory fails with an actionable message`() {
        val first = CliProcess.startRunning(*serveArgs("--json", "serve", "--port", "0"))
        try {
            first.awaitStdoutLine("{")

            val second = CliProcess.run(*serveArgs("serve", "--port", "0"))

            assertNotEquals(0, second.exitCode, "a second server must not report success")
            assertContains(second.stderr, "already running")
            assertContains(second.stderr, "the CLI talks to the running server")
            assertTrue(
                !second.stderr.contains("Exception in thread"),
                "the operator gets an explanation, not a stack trace: ${second.stderr}",
            )
        } finally {
            first.kill()
        }
    }

    @Test
    fun `a collection created through the CLI while a server runs lands in the server`() {
        val cli = CliProcess.startRunning(*serveArgs("--json", "serve", "--port", "0"))
        try {
            val published = ApiJson.decodeFromString<ServeResponse>(cli.awaitStdoutLine("{"))
            val token = adminToken()

            val created = CliProcess.run(
                "collection", "create", "Nightfall", "--json",
                environment = mapOf("INFOSCRY_URL" to published.url, "INFOSCRY_TOKEN" to token),
            )

            assertEquals(0, created.exitCode, created.stderr)
            val listed = runBlocking {
                HttpClient(CIO).use { client ->
                    ApiJson.decodeFromString<CollectionsResponse>(
                        client.get("${published.url}/api/collections") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText(),
                    )
                }
            }
            assertTrue(listed.collections.any { it.name == "Nightfall" })
        } finally {
            cli.kill()
        }
    }

    @Test
    fun `serve without any user says how to create the first administrator`() {
        val cli = CliProcess.startRunning(*serveArgs("--json", "serve", "--port", "0"))
        try {
            cli.awaitStdoutLine("{")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (cli.stderr().none { it.contains("admin create-user --admin") } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(cli.stderr().any { it.contains("infoscry admin create-user --admin") }, cli.stderr().joinToString("\n"))
        } finally {
            cli.kill()
        }
    }

    @Test
    fun `a killed server can be started again at once`() {
        val cli = CliProcess.startRunning(*serveArgs("--json", "serve", "--port", "0"))
        cli.awaitStdoutLine("{")

        cli.kill()

        val restarted = CliProcess.startRunning(*serveArgs("--json", "serve", "--port", "0"))
        try {
            val published = ApiJson.decodeFromString<ServeResponse>(restarted.awaitStdoutLine("{"))
            assertEquals(HttpStatusCode.OK, runBlocking { get("${published.url}/api/session").status })
        } finally {
            restarted.kill()
        }
    }

    /** An administrator's personal access token, made in the database the server uses. */
    private fun adminToken(): String = infoscry.storage.Database(dataDir).use { database ->
        val store = infoscry.storage.AccountStore(database)
        val accounts = infoscry.account.AccountService(store)
        val admin = accounts.createUser("operator", null, infoscry.account.UserRole.ADMIN, actor = null).user
        // As after the first sign-in: a user who still has to choose a password can do nothing else.
        store.updatePassword(admin.id, infoscry.account.Passwords().hash("operator's own password"), false, infoscry.storage.Instants.now(), null)
        accounts.createToken(admin.id, "cli", null).value
    }

    private fun serveArgs(vararg extra: String): Array<String> =
        (listOf("--data-dir", dataDir.toString()) + extra).toTypedArray()

    private suspend fun get(url: String): HttpResponse = HttpClient(CIO).use { client -> client.get(url) }
}
