package infoscry.server

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The native picker route, with the dialog itself replaced by a fake.
 *
 * The route's contract is the seam: the picker is injected, the response is either the chosen paths or a
 * named error, and only one dialog may be open at a time. The fake picker is what makes the dialog's
 * three outcomes — a choice, a cancel, and no dialog at all — observable without a window server.
 */
class PickRoutesTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-pick-routes")
    }

    @AfterTest
    fun removeDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a successful pick returns the chosen absolute paths`() = runBlocking {
        val chosen = listOf("/Users/me/Documents/a.pdf", "/Users/me/Documents/b.txt")
        ApiTestServer(dataDir, picker = { directory ->
            assertEquals(false, directory, "file mode was asked for as a folder")
            chosen
        }).use { server ->
            val response = server.request(
                HttpMethod.Post,
                "/api/imports/pick",
                body = """{"directory":false}""",
                credential = Credential.BEARER,
            )
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertEquals(PickResponse(chosen), ApiJson.decodeFromString<PickResponse>(body))
        }
    }

    @Test
    fun `folder mode asks the picker for one directory`() = runBlocking {
        ApiTestServer(dataDir, picker = { directory ->
            assertEquals(true, directory, "folder mode was asked for as files")
            listOf("/Users/me/Archive")
        }).use { server ->
            val response = server.request(
                HttpMethod.Post,
                "/api/imports/pick",
                body = """{"directory":true}""",
                credential = Credential.BEARER,
            )
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
    }

    @Test
    fun `a cancelled pick answers PICK_CANCELLED`() = runBlocking {
        ApiTestServer(dataDir, picker = { throw PickerCancelledException("closed without choosing") }).use { server ->
            val response = server.request(
                HttpMethod.Post,
                "/api/imports/pick",
                body = """{"directory":false}""",
                credential = Credential.BEARER,
            )
            val error = ApiJson.decodeFromString<ApiErrorResponse>(response.bodyAsText()).error
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("PICK_CANCELLED", error.code)
        }
    }

    @Test
    fun `a picker that cannot open answers PICK_UNAVAILABLE`() = runBlocking {
        ApiTestServer(dataDir, picker = { throw IllegalStateException("osascript is not installed") }).use { server ->
            val response = server.request(
                HttpMethod.Post,
                "/api/imports/pick",
                body = """{"directory":false}""",
                credential = Credential.BEARER,
            )
            val error = ApiJson.decodeFromString<ApiErrorResponse>(response.bodyAsText()).error
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("PICK_UNAVAILABLE", error.code)
        }
    }

    @Test
    fun `a second concurrent pick answers PICK_BUSY while the first dialog is open`() = runBlocking {
        val dialogOpened = CompletableDeferred<Unit>()
        val dialogClosed = CompletableDeferred<Unit>()
        ApiTestServer(dataDir, picker = {
            dialogOpened.complete(Unit)
            dialogClosed.await()
            listOf("/Users/me/Chosen.pdf")
        }).use { server ->
            val first = async {
                server.request(
                    HttpMethod.Post,
                    "/api/imports/pick",
                    body = """{"directory":false}""",
                    credential = Credential.BEARER,
                )
            }
            withTimeout(WAIT_MILLIS) { dialogOpened.await() }

            val second = server.request(
                HttpMethod.Post,
                "/api/imports/pick",
                body = """{"directory":false}""",
                credential = Credential.BEARER,
            )
            val secondError = ApiJson.decodeFromString<ApiErrorResponse>(second.bodyAsText()).error
            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals("PICK_BUSY", secondError.code)

            dialogClosed.complete(Unit)
            val firstResponse = first.await()
            assertEquals(HttpStatusCode.OK, firstResponse.status, firstResponse.bodyAsText())
        }
    }

    private companion object {
        /** How long a parked dialog fake may hold the test open before it is called hung. */
        const val WAIT_MILLIS = 30_000L
    }
}