package infoscry.server

import io.ktor.client.statement.bodyAsText
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.ocr.ImageLlmClient
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The OCR profile API over a real socket: what each request does, what it refuses, and what it never says.
 *
 * An OCR profile is a credential-adjacent record — it names the environment variable a key comes from —
 * so the responses are asserted as carefully as the behavior: the variable's *name* and whether it is set
 * is all a caller learns, and a payload that carries something key-shaped is rejected without being
 * echoed back or stored.
 */
class OcrProfileRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-ocr-profile-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private val profileBody = """{"name":"Local vision","provider":"OPENAI_COMPATIBLE","endpoint":"http://127.0.0.1:11434/v1","model":"vision-model","contextWindow":32000,"maxOutputTokens":4096,"inputPricePerMillion":0.0,"outputPricePerMillion":0.0,"enabled":true,"apiKeyEnvironmentVariable":"PATH"}"""

    private suspend fun create(body: String = profileBody) =
        harness.request(HttpMethod.Post, "/api/ocr/profiles", body, Credential.CSRF)

    private suspend fun profileIdOf(response: io.ktor.client.statement.HttpResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.bodyAsText())!!.groupValues[1]

    private suspend fun revisionIdOf(response: io.ktor.client.statement.HttpResponse): String =
        Regex(""""revisionId":"([^"]+)"""").find(response.bodyAsText())!!.groupValues[1]

    @Test
    fun `creating a profile stores a revision and reports key presence without its value`() = runBlocking {
        val created = create()

        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val body = created.bodyAsText()
        assertContains(body, "\"sequence\":1")
        assertContains(body, "\"name\":\"Local vision\"")
        assertContains(body, "\"scope\":\"LOCAL\"", message = "a loopback endpoint is local processing")
        assertContains(body, "\"apiKeyEnvironmentVariable\":\"PATH\"")
        assertContains(body, "\"keyAvailable\":true")
        assertFalse(body.contains(System.getenv("PATH")!!), "a key value must never be returned")
        assertFalse(body.contains("imageCapabilityMeasured"), "nothing has measured the image capability yet")

        val listed = harness.get("/api/ocr/profiles").bodyAsText()
        assertContains(listed, revisionIdOf(created))
    }

    @Test
    fun `an endpoint carrying userinfo credentials is refused without being stored or echoed`() = runBlocking {
        val secret = "hunter2-not-a-real-credential"
        val credentialed = profileBody.replace(
            "http://127.0.0.1:11434/v1",
            "https://local-vision:$secret@vision.example.com/v1",
        )

        val refused = create(credentialed)

        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "INVALID_REQUEST")
        assertFalse(
            refused.bodyAsText().contains(secret),
            "a credential carried in a URL is never echoed back",
        )
        assertEquals(0, profileCount(), "a credentialed endpoint is not stored")
        assertFalse(
            harness.get("/api/ocr/profiles").bodyAsText().contains(secret),
            "and it cannot come back in a profile response either",
        )
    }

    @Test
    fun `a profile with no endpoint is external and a remote one is external too`() = runBlocking {
        val providerDefault = create(profileBody.replace("""http://127.0.0.1:11434/v1""", ""))
        val remote = create(
            profileBody
                .replace("""http://127.0.0.1:11434/v1""", "https://api.example.com/v1")
                .replace("Local vision", "Remote vision"),
        )

        // A blank endpoint means the provider's own public destination; it is never implicitly local.
        assertContains(providerDefault.bodyAsText(), "\"scope\":\"EXTERNAL\"")
        assertContains(remote.bodyAsText(), "\"scope\":\"EXTERNAL\"")
    }

    @Test
    fun `editing a profile creates a new revision and disabling keeps both`() = runBlocking {
        val created = create()
        val id = profileIdOf(created)
        val firstRevision = revisionIdOf(created)

        val edited = harness.request(
            HttpMethod.Patch,
            "/api/ocr/profiles/$id",
            profileBody.replace("vision-model", "vision-model-2"),
            Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        assertEquals(id, profileIdOf(edited), "an edit never changes a profile's identity")
        val secondRevision = revisionIdOf(edited)
        assertNotEquals(firstRevision, secondRevision, "an edit is a new immutable revision")
        assertContains(edited.bodyAsText(), "\"sequence\":2")
        assertContains(edited.bodyAsText(), "vision-model-2")

        val disabled = harness.request(HttpMethod.Delete, "/api/ocr/profiles/$id", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.NoContent, disabled.status, disabled.bodyAsText())
        val listed = harness.get("/api/ocr/profiles").bodyAsText()
        assertContains(listed, "\"enabled\":false")
        assertContains(listed, secondRevision, message = "disabling keeps the revisions history references")
    }

    private suspend fun patch(id: String, body: String) =
        harness.request(HttpMethod.Patch, "/api/ocr/profiles/$id", body, Credential.CSRF)

    private fun withExpected(body: String, revisionId: String): String =
        body.trimEnd().removeSuffix("}") + ""","expectedRevisionId":"$revisionId"}"""

    @Test
    fun `an edit that names the current revision is applied and the profile reports its revision id`() = runBlocking {
        val created = create()
        val id = profileIdOf(created)
        val first = revisionIdOf(created)

        val edited = patch(id, withExpected(profileBody.replace("vision-model", "vision-model-2"), first))

        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        assertNotEquals(first, revisionIdOf(edited), "the answer names the revision the edit created")
        assertContains(edited.bodyAsText(), "\"sequence\":2")
    }

    @Test
    fun `an edit taken against an older revision is a distinct conflict and changes nothing`() = runBlocking {
        val created = create()
        val id = profileIdOf(created)
        val first = revisionIdOf(created)
        val second = revisionIdOf(patch(id, profileBody.replace("vision-model", "vision-model-2")))

        val stale = patch(id, withExpected(profileBody.replace("vision-model", "vision-model-3"), first))

        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
        assertContains(stale.bodyAsText(), "STALE_OCR_PROFILE_REVISION")
        assertFalse(stale.bodyAsText().contains("vision-model-3"), "a refused edit's fields are not echoed")
        val after = harness.context.ocr.require(id)
        assertEquals(second, after.revision.revisionId, "the profile still reads through the newer revision")
        assertEquals("vision-model-2", after.revision.model)
        assertEquals(2, after.revision.sequence, "a refused edit added no revision")
    }

    @Test
    fun `an edit that names no expected revision keeps the last-writer behaviour`() = runBlocking {
        val created = create()
        val id = profileIdOf(created)

        val first = patch(id, profileBody.replace("vision-model", "vision-model-2"))
        val second = patch(id, profileBody.replace("vision-model", "vision-model-3"))

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        assertContains(second.bodyAsText(), "\"sequence\":3")
    }

    @Test
    fun `a blank expected revision is a bad request rather than an unguarded edit`() = runBlocking {
        val id = profileIdOf(create())

        val blank = patch(id, withExpected(profileBody, ""))

        assertEquals(HttpStatusCode.BadRequest, blank.status, blank.bodyAsText())
    }

    @Test
    fun `an unknown profile with an expected revision is still not found`() = runBlocking {
        create()

        val missing = patch("missing", withExpected(profileBody, "whatever"))

        assertEquals(HttpStatusCode.NotFound, missing.status, missing.bodyAsText())
    }

    @Test
    fun `two edits from the same base revision are not both applied`() = runBlocking {
        val created = create()
        val id = profileIdOf(created)
        val base = revisionIdOf(created)

        val statuses = withTimeout(TIMEOUT_MILLIS) {
            listOf("vision-model-a", "vision-model-b").map { model ->
                async(Dispatchers.IO) {
                    patch(id, withExpected(profileBody.replace("vision-model", model), base)).status
                }
            }.map { it.await() }
        }

        assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "exactly one edit wins: $statuses")
        assertEquals(1, statuses.count { it == HttpStatusCode.Conflict }, "the other is refused: $statuses")
        assertEquals(2, harness.context.ocr.require(id).revision.sequence, "exactly one revision was added")
    }

    @Test
    fun `the store applies one of two simultaneous edits from the same base revision`() {
        val created = harness.context.ocr.create(
            "Race",
            infoscry.ocr.OcrProfileRevisionDraft(
                provider = infoscry.llm.LlmProvider.OPENAI_COMPATIBLE,
                model = "m",
                contextWindow = 32_000,
                maxOutputTokens = 1_024,
                endpoint = "http://127.0.0.1:11434/v1",
            ),
            enabled = true,
        )
        val base = created.revision.revisionId
        val gate = java.util.concurrent.CyclicBarrier(2)
        val outcomes = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val threads = listOf("m-a", "m-b").map { model ->
            Thread {
                gate.await()
                try {
                    harness.context.ocr.update(
                        created.id,
                        "Race",
                        infoscry.ocr.OcrProfileRevisionDraft(
                            provider = infoscry.llm.LlmProvider.OPENAI_COMPATIBLE,
                            model = model,
                            contextWindow = 32_000,
                            maxOutputTokens = 1_024,
                            endpoint = "http://127.0.0.1:11434/v1",
                        ),
                        enabled = true,
                        expectedRevisionId = base,
                    )
                    outcomes += "applied"
                } catch (stale: infoscry.storage.StaleOcrProfileRevisionException) {
                    outcomes += "stale"
                }
            }.also(Thread::start)
        }
        threads.forEach(Thread::join)

        assertEquals(listOf("applied", "stale"), outcomes.sorted())
        assertEquals(2, harness.context.ocr.require(created.id).revision.sequence)
    }

    @Test
    fun `an unknown profile is not found and a duplicate name is a conflict`() = runBlocking {
        create()

        val missingPatch = harness.request(
            HttpMethod.Patch,
            "/api/ocr/profiles/missing",
            profileBody,
            Credential.CSRF,
        )
        assertEquals(HttpStatusCode.NotFound, missingPatch.status, missingPatch.bodyAsText())

        val missingDelete = harness.request(
            HttpMethod.Delete,
            "/api/ocr/profiles/missing",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.NotFound, missingDelete.status)

        val duplicate = create(profileBody.replace("Local vision", "local VISION"))
        assertEquals(HttpStatusCode.Conflict, duplicate.status, duplicate.bodyAsText())
        assertContains(duplicate.bodyAsText(), "DUPLICATE_OCR_PROFILE_NAME")
        assertEquals(1, profileCount())
    }

    @Test
    fun `invalid or secret-bearing profile fields are refused without echoing them`() = runBlocking {
        val keyLike = create(profileBody.replace("PATH", "sk-live-not-an-environment-name"))
        val badUrl = create(profileBody.replace("""http://127.0.0.1:11434/v1""", "not a url"))
        val negativePrice = create(profileBody.replace("\"outputPricePerMillion\":0.0", "\"outputPricePerMillion\":-1.0"))
        val zeroContext = create(profileBody.replace("\"contextWindow\":32000", "\"contextWindow\":0"))
        val blankModel = create(profileBody.replace("vision-model", "   "))

        for (refused in listOf(keyLike, badUrl, negativePrice, zeroContext, blankModel)) {
            assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
            assertContains(refused.bodyAsText(), "INVALID_REQUEST")
        }
        assertFalse(keyLike.bodyAsText().contains("sk-live-not-an-environment-name"), "a payload is never echoed")
        assertEquals(0, profileCount(), "a refused profile is not stored")
    }

    @Test
    fun `a body carrying a field the contract does not have is refused rather than dropped`() = runBlocking {
        // The profile body has no slot a key *value* may occupy, so an unknown field is the shape a secret
        // pasted into this endpoint takes. Accepting the body while ignoring the field would tell the caller
        // the value had been stored.
        val secretBearing = profileBody.replace(
            "\"enabled\":true",
            "\"enabled\":true,\"apiKey\":\"sk-live-not-a-field\"",
        )

        val refused = create(secretBearing)

        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "INVALID_REQUEST")
        assertFalse(
            refused.bodyAsText().contains("sk-live-not-a-field"),
            "a body that carried a key-shaped value is never echoed back",
        )
        assertEquals(0, profileCount(), "a refused body is not stored")
    }

    @Test
    fun `a profile mutation needs a credential`() = runBlocking {
        val unauthenticated = harness.request(HttpMethod.Post, "/api/ocr/profiles", profileBody)
        val created = create()
        val wrongCredential = harness.request(
            HttpMethod.Delete,
            "/api/ocr/profiles/${profileIdOf(created)}",
            credential = Credential.WRONG_CSRF,
        )

        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status, unauthenticated.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, wrongCredential.status, wrongCredential.bodyAsText())
        // Only the rightly credentialed create happened, and the refused delete disabled nothing.
        assertEquals(1, profileCount())
        assertTrue(harness.context.ocr.require(profileIdOf(created)).enabled)
    }

    @Test
    fun `an OCR profile write during exclusive maintenance is refused with 423`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val maintenance = async(Dispatchers.Default) {
            harness.context.mutations.withExclusiveMaintenance("reindex") {
                started.complete(Unit)
                release.await()
            }
        }
        withTimeout(TIMEOUT_MILLIS) { started.await() }
        try {
            val refused = create()

            assertEquals(HttpStatusCode.Locked, refused.status, refused.bodyAsText())
            assertContains(refused.bodyAsText(), "MAINTENANCE_IN_PROGRESS")
            assertEquals(0, profileCount())
        } finally {
            release.complete(Unit)
            withTimeout(TIMEOUT_MILLIS) { maintenance.await() }
        }
    }

    @Test
    fun `OCR profiles do not touch the Ask and Investigate defaults`() = runBlocking {
        create()

        val llmProfiles = harness.get("/api/llm/profiles").bodyAsText()

        assertContains(llmProfiles, "\"ASK\":null")
        assertContains(llmProfiles, "\"INVESTIGATE\":null")
        assertTrue(harness.context.llm.list().isEmpty(), "an OCR profile is not an Ask/Investigate profile")
    }

    @Test
    fun `a collection names a profile, and a disabled one can no longer be selected`() = runBlocking {
        val transcriber = create()
        val transcriberId = profileIdOf(transcriber)
        val reviewer = create(profileBody.replace("Local vision", "Local reviewer"))
        val reviewerId = profileIdOf(reviewer)
        harness.createCollection("Rescans", Credential.BEARER)
        val collectionId = harness.collectionIdOf("Rescans")

        val selected = harness.request(
            HttpMethod.Patch,
            "/api/collections/$collectionId/ocr-languages",
            body = """{"ocrEngine":"LLM","ocrImportMode":"CHECK_AND_IMPROVE","ocrTranscriptionProfileId":"$transcriberId","ocrReviewProfileId":"$reviewerId","ocrExternalPageLimit":25}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, selected.status, selected.bodyAsText())
        assertContains(selected.bodyAsText(), "\"ocrEngine\":\"LLM\"")
        assertContains(selected.bodyAsText(), "\"ocrExternalPageLimit\":25")

        harness.request(HttpMethod.Delete, "/api/ocr/profiles/$reviewerId", credential = Credential.CSRF)
        val namingDisabled = harness.request(
            HttpMethod.Patch,
            "/api/collections/$collectionId/ocr-languages",
            body = """{"ocrEngine":"LLM","ocrTranscriptionProfileId":"$transcriberId","ocrReviewProfileId":"$reviewerId"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, namingDisabled.status, namingDisabled.bodyAsText())
        assertContains(namingDisabled.bodyAsText(), "INVALID_REQUEST")
        // The refused edit changed nothing: the collection still carries the reviewer it selected.
        val unchanged = harness.get("/api/collections").bodyAsText()
        assertContains(unchanged, "\"ocrReviewProfileId\":\"$reviewerId\"")
    }

    @Test
    fun `a probe sends only the synthetic image and records the measurement`() = runBlocking {
        FakeOpenAiServer(listOf(FakeOpenAiResponse(body = probeAnswer("InfoScry image capability probe")))).use { vision ->
            val created = create(profileBody.replace("http://127.0.0.1:11434/v1", vision.url))
            val id = profileIdOf(created)
            assertFalse(
                created.bodyAsText().contains("imageCapabilityMeasured"),
                "nothing has measured the image capability yet",
            )

            val probed = harness.request(HttpMethod.Post, "/api/ocr/profiles/$id/probe", credential = Credential.CSRF)

            assertEquals(HttpStatusCode.OK, probed.status, probed.bodyAsText())
            assertContains(probed.bodyAsText(), "\"supported\":true")
            assertContains(probed.bodyAsText(), "\"imageCapabilityMeasured\":true")
            assertContains(probed.bodyAsText(), "\"imageCapabilityCheckedAt\":")
            assertContains(probed.bodyAsText(), "\"modelVersion\":\"vision-model-2026-02-01\"")
            assertEquals(1, vision.handledRequests, "one synthetic check, not one per page")
            val sent = vision.requestBody!!
            val image = Base64.getDecoder().decode(
                Regex("\"url\":\"data:image/png;base64,([^\"]+)\"").find(sent)!!.groupValues[1],
            )
            assertTrue(
                image.contentEquals(ImageLlmClient.syntheticProbeImage()),
                "a probe sends the built-in synthetic image and no document page",
            )
            assertFalse(sent.contains("documentId"), "a probe names no document")

            // And the measurement is what the list endpoint shows from now on.
            assertContains(harness.get("/api/ocr/profiles").bodyAsText(), "\"imageCapabilityMeasured\":true")
        }
    }

    @Test
    fun `a probe the model cannot answer records the measurement as unsupported`() = runBlocking {
        FakeOpenAiServer(
            listOf(FakeOpenAiResponse(statusCode = 400, body = """{"error":{"message":"image input is not supported"}}""")),
        ).use { vision ->
            val created = create(profileBody.replace("http://127.0.0.1:11434/v1", vision.url))
            val id = profileIdOf(created)

            val probed = harness.request(HttpMethod.Post, "/api/ocr/profiles/$id/probe", credential = Credential.CSRF)

            // A check that ran and did not prove image transport is a measurement, not a server error.
            assertEquals(HttpStatusCode.OK, probed.status, probed.bodyAsText())
            assertContains(probed.bodyAsText(), "\"supported\":false")
            assertContains(probed.bodyAsText(), "\"errorCode\":\"OCR_IMAGE_NOT_SUPPORTED\"")
            assertContains(probed.bodyAsText(), "\"imageCapabilityMeasured\":false")
            assertContains(probed.bodyAsText(), "\"imageCapabilityCheckedAt\":")
        }
    }

    @Test
    fun `a probe of an external profile is refused before anything leaves this machine`() = runBlocking {
        val created = create(
            profileBody
                .replace("http://127.0.0.1:11434/v1", "https://vision.example.invalid/v1")
                .replace("\"apiKeyEnvironmentVariable\":\"PATH\"", "\"apiKeyEnvironmentVariable\":\"FAKE_OCR_KEY\""),
        )
        val id = profileIdOf(created)

        val probed = harness.request(HttpMethod.Post, "/api/ocr/profiles/$id/probe", credential = Credential.CSRF)

        // No dispatch permit is integrated yet (ticket 07), so production external dispatch is unavailable.
        assertEquals(HttpStatusCode.Conflict, probed.status, probed.bodyAsText())
        assertContains(probed.bodyAsText(), "OCR_EXTERNAL_DISPATCH_NOT_PERMITTED")
        val listed = harness.get("/api/ocr/profiles").bodyAsText()
        assertFalse(
            listed.contains("imageCapabilityMeasured"),
            "a check that never ran is no measurement",
        )
    }

    @Test
    fun `a probe takes no body and needs a credential`() = runBlocking {
        val created = create()
        val id = profileIdOf(created)

        val unauthenticated = harness.request(HttpMethod.Post, "/api/ocr/profiles/$id/probe")
        val withBody = harness.request(
            HttpMethod.Post,
            "/api/ocr/profiles/$id/probe",
            body = """{"documentId":"doc-1"}""",
            credential = Credential.CSRF,
        )

        val probeWithWhitespace = harness.request(
            HttpMethod.Post,
            "/api/ocr/profiles/$id/probe",
            body = "   ",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status, unauthenticated.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, withBody.status, withBody.bodyAsText())
        assertContains(withBody.bodyAsText(), "INVALID_REQUEST")
        // Whitespace is a caller sending a body, and the endpoint takes none: reading it as "blank" would
        // accept a request whose body was silently ignored.
        assertEquals(HttpStatusCode.BadRequest, probeWithWhitespace.status, probeWithWhitespace.bodyAsText())
        assertFalse(harness.get("/api/ocr/profiles").bodyAsText().contains("imageCapabilityMeasured"))
    }

    @Test
    fun `a probe refuses a body whose bytes decode to nothing`() = runBlocking {
        val created = create()
        val id = profileIdOf(created)

        // Two bytes and nothing else: a byte order mark is the whole body, and a decoder consumes it, so this
        // request's text is empty while the request itself carries a body. Asking the text answered such a
        // caller as though they had sent nothing at all.
        val bomOnly = harness.requestBytes(
            HttpMethod.Post,
            "/api/ocr/profiles/$id/probe",
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
            ContentType.Text.Plain.withCharset(Charsets.UTF_16),
            Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, bomOnly.status, bomOnly.bodyAsText())
        assertContains(bomOnly.bodyAsText(), "INVALID_REQUEST")
        assertFalse(harness.get("/api/ocr/profiles").bodyAsText().contains("imageCapabilityMeasured"))
    }

    @Test
    fun `a probe whose announced body never arrives is refused instead of holding the handler open`() = runBlocking {
        FakeOpenAiServer(listOf(FakeOpenAiResponse(body = probeAnswer("InfoScry image capability probe")))).use { vision ->
            val created = create(profileBody.replace("http://127.0.0.1:11434/v1", vision.url))
            val id = profileIdOf(created)
            val port = URI(harness.url).port

            // A caller that announces a body and then sends none: the request line and headers go out, the
            // announced bytes never do, and the connection is held open. Reading the body waits for a byte or
            // for its end, and neither arrives, so the endpoint has to bound that wait and refuse rather than
            // hold the handler open. The socket timeout here is the test's own ceiling on the answer — it is
            // deliberately far longer than the bound under test, so a handler that waits for the missing body
            // fails this instead of hanging the suite.
            val statusLine = Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                socket.soTimeout = STALLED_RESPONSE_TIMEOUT_MILLIS
                val out = socket.getOutputStream()
                out.write(
                    (
                        "POST /api/ocr/profiles/$id/probe HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:$port\r\n" +
                            "$CSRF_HEADER: ${harness.csrfToken}\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: 12\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                out.flush()
                socket.getInputStream().bufferedReader().readLine()
            }

            assertContains(
                statusLine.orEmpty(),
                "400",
                message = "a body whose emptiness cannot be established is refused rather than waited for",
            )
            assertEquals(0, vision.handledRequests, "a refused probe dispatched nothing")
            assertFalse(harness.get("/api/ocr/profiles").bodyAsText().contains("imageCapabilityMeasured"))
        }
    }

    private suspend fun profileCount(): Int = harness.context.ocr.list().size

    // --- LLM profiles offered as OCR profiles (local-testing-feedback 04) -------------------------------------

    private fun llmProfile(name: String, model: String, endpoint: String = "http://127.0.0.1:11434/v1") =
        infoscry.llm.LlmProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            provider = infoscry.llm.LlmProvider.OPENAI_COMPATIBLE,
            model = model,
            contextWindow = 64_000,
            maxOutputTokens = 2_048,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
            cacheReadPricePerMillion = 0.5,
            enabled = true,
            endpoint = endpoint,
            apiKeyEnvironmentVariable = "PATH",
        ).also { harness.context.llm.create(it) }

    private suspend fun fromLlm(id: String, credential: Credential = Credential.CSRF) = harness.request(
        HttpMethod.Post,
        "/api/ocr/profiles/from-llm",
        """{"llmProfileId":"$id"}""",
        credential,
    )

    @Test
    fun `LLM profiles are offered with their image support stated, unknown stays unknown, and no key value leaks`() = runBlocking {
        llmProfile("Reader", "gpt-4o")
        llmProfile("Chatter", "deepseek-chat")
        llmProfile("Mystery", "gemma3:12b")

        val response = harness.get("/api/ocr/llm-profiles")

        assertEquals(HttpStatusCode.OK, response.status)
        val listed = kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["profiles"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["name"]!!.jsonPrimitive.content }
        assertEquals("true", listed.getValue("Reader")["imageInput"]!!.jsonPrimitive.content)
        assertEquals("false", listed.getValue("Chatter")["imageInput"]!!.jsonPrimitive.content)
        assertTrue(
            listed.getValue("Mystery")["imageInput"].let { it == null || it is kotlinx.serialization.json.JsonNull },
            "an uncatalogued model's image support is unknown, never assumed",
        )
        assertContains(response.bodyAsText(), "\"keyAvailable\":true")
        assertFalse(response.bodyAsText().contains(System.getenv("PATH")!!), "a key value is never returned")
    }

    @Test
    fun `selecting an image-capable LLM profile copies it into an OCR profile that a collection can select`() = runBlocking {
        val llm = llmProfile("Reader", "gpt-4o")

        val first = fromLlm(llm.id)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val body = first.bodyAsText()
        assertContains(body, "\"name\":\"Reader (from LLM profile)\"")
        assertContains(body, "\"model\":\"gpt-4o\"")
        assertContains(body, "\"endpoint\":\"http://127.0.0.1:11434/v1\"")
        assertContains(body, "\"apiKeyEnvironmentVariable\":\"PATH\"")
        assertContains(body, "\"scope\":\"LOCAL\"")
        assertFalse(body.contains("imageCapabilityMeasured"), "a copy is unmeasured until its own check runs")
        assertFalse(body.contains(System.getenv("PATH")!!))

        val again = fromLlm(llm.id)
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
        assertEquals(profileIdOf(first), profileIdOf(again), "choosing the same LLM profile again reuses its copy")
        assertEquals(revisionIdOf(first), revisionIdOf(again), "and adds no revision when nothing changed")
        assertEquals(1, profileCount())

        harness.createCollection("Rescans", Credential.BEARER)
        val collectionId = harness.collectionIdOf("Rescans")
        val selected = harness.request(
            HttpMethod.Patch,
            "/api/collections/$collectionId/ocr-languages",
            body = """{"ocrEngine":"LLM","ocrTranscriptionProfileId":"${profileIdOf(first)}","ocrReviewProfileId":"${profileIdOf(first)}"}""",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.OK, selected.status, selected.bodyAsText())
    }

    @Test
    fun `a text-only LLM profile cannot be selected for transcription or review and nothing is created`() = runBlocking {
        val llm = llmProfile("Chatter", "deepseek-chat")

        val refused = fromLlm(llm.id)

        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "LLM_PROFILE_TEXT_ONLY")
        assertEquals(0, profileCount())
    }

    @Test
    fun `an unknown or disabled LLM profile is refused and the copy needs a credential`() = runBlocking {
        assertEquals(HttpStatusCode.NotFound, fromLlm("no-such-profile").status)
        val llm = llmProfile("Reader", "gpt-4o")
        harness.context.llm.update(llm.id, llm.copy(enabled = false))
        assertEquals(HttpStatusCode.BadRequest, fromLlm(llm.id).status)
        assertEquals(HttpStatusCode.Unauthorized, fromLlm(llm.id, Credential.NONE).status)
        assertEquals(0, profileCount())
    }

    @Test
    fun `editing the LLM profile after admission leaves the admitted attempt on the revision it pinned`() = runBlocking {
        val llm = llmProfile("Reader", "gpt-4o")
        val copy = fromLlm(llm.id)
        val profileId = profileIdOf(copy)
        val pinnedRevision = revisionIdOf(copy)
        val settings = infoscry.ocr.CollectionOcrSettings(
            language = "eng",
            engine = infoscry.ocr.OcrEngine.LLM,
            transcriptionProfileId = profileId,
        )
        val admitted = harness.context.ocr.snapshotFor(settings, extractorVersion = "test")
        assertEquals(pinnedRevision, admitted.transcriptionProfileRevisionId)

        harness.context.llm.update(llm.id, llm.copy(model = "gpt-4o-mini", endpoint = "https://api.openai.com/v1"))
        val refreshed = fromLlm(llm.id)

        assertEquals(HttpStatusCode.OK, refreshed.status, refreshed.bodyAsText())
        assertEquals(profileId, profileIdOf(refreshed))
        assertNotEquals(pinnedRevision, revisionIdOf(refreshed), "an edit of the LLM profile is a new OCR revision")
        assertContains(refreshed.bodyAsText(), "gpt-4o-mini")
        val stillPinned = harness.context.ocrProfiles.findRevision(admitted.transcriptionProfileRevisionId!!)!!
        assertEquals("gpt-4o", stillPinned.model, "the admitted attempt still describes what it was admitted with")
        assertEquals("http://127.0.0.1:11434/v1", stillPinned.endpoint)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L

        /** How long the stalled-body test waits for an answer before it calls the handler stuck. */
        const val STALLED_RESPONSE_TIMEOUT_MILLIS = 5_000

        /** The one answer a capability probe accepts: the synthetic page, named and complete. */
        fun probeAnswer(text: String): String = buildJsonObject {
            put("model", "vision-model-2026-02-01")
            putJsonArray("choices") {
                addJsonObject {
                    put("finish_reason", "stop")
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", transcriptionOf(text))
                    }
                }
            }
        }.toString()

        private fun transcriptionOf(text: String): String = buildJsonObject {
            put("unitId", ImageLlmClient.CAPABILITY_PROBE_UNIT_ID)
            put("ordinal", 0)
            put("text", text)
            putJsonArray("unreadable") { }
        }.toString()
    }
}
