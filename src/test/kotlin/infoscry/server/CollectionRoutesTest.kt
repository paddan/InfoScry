package infoscry.server

import infoscry.FRONTEND_TAG
import infoscry.collection.CollectionIndexRemover
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobType
import infoscry.extract.ExtractionSettings
import infoscry.jobs.ImportJobPayload
import infoscry.llm.LlmProvider
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.ReadingMethod
import infoscry.storage.Instants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag

/**
 * The collection API over a real socket: what each request does, and which status a failure produces.
 *
 * The status codes are a contract the CLI and the browser read to decide what to do next — retry later
 * for 423, choose another name for 409, re-confirm for 400 — so they are asserted as carefully as the
 * successful responses are.
 */
class CollectionRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a new archive exposes no collections`() = runBlocking {
        assertEquals(
            emptyList(),
            listed(),
            "a new archive starts without collections, so an import cannot rely on an automatic Default",
        )
    }

    @Test
    fun `the listing reports each collection's document count`() = runBlocking {
        harness.createCollection("Nightfall", Credential.BEARER)
        harness.createCollection("Empty", Credential.BEARER)
        harness.context.documents.insert(document("nightfall-1", CollectionId(harness.collectionIdOf("Nightfall"))))

        val body = harness.get("/api/collections").bodyAsText()
        val counts = ApiJson.decodeFromString<CollectionsResponse>(body)
            .collections.associate { it.name to it.documentCount }

        assertContains(body, "\"documentCount\"", message = "the browser reads the count off the wire")
        assertEquals(1, counts["Nightfall"])
        assertEquals(0, counts["Empty"])
        assertEquals(setOf("Nightfall", "Empty"), counts.keys, "a new archive lists only what was created")
    }

    @Test
    fun `creating a collection returns it and lists it`() = runBlocking {
        val response = harness.createCollection("Project Nightfall", Credential.BEARER)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "Project Nightfall")
        assertTrue(listed().any { it.name == "Project Nightfall" })
    }

    @Test
    fun `a person may create a collection named Default, and it is not the retired seed`() = runBlocking {
        val response = harness.createCollection("Default", Credential.BEARER)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val created = listed().single { it.name == "Default" }
        // The seed's identifier is retired, so this one has a generated id and is never treated as it.
        assertNotEquals("default", created.id.value)
    }

    @Test
    fun `a duplicate name is a conflict`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)

        val response = harness.createCollection("acme", Credential.BEARER)

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertContains(response.bodyAsText(), "DUPLICATE_COLLECTION_NAME")
    }

    @Test
    fun `a blank name is refused as a bad request`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/collections",
            body = """{"name":"   "}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "INVALID_REQUEST")
    }

    @Test
    fun `a malformed body is refused as a bad request, not a server error`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/collections",
            body = """{"name":""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "INVALID_REQUEST")
    }

    @Test
    fun `renaming returns the updated collection`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)
        val id = harness.collectionIdOf("Acme")

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id",
            body = """{"name":"Acme acquisition"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "Acme acquisition")
        assertFalse(listed().any { it.name == "Acme" })
    }

    @Test
    fun `collection OCR settings save language and one reading method together`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"language":"swe+eng","defaultMethod":"surya"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val saved = harness.context.collectionService.get(CollectionId(id))!!
        assertEquals("swe+eng", saved.ocrLanguages)
        assertEquals(infoscry.ocr.ReadingMethod.Surya, saved.ocrSettings().defaultMethod)
    }

    @Test
    fun `reading method list includes readiness and the collection default`() = runBlocking {
        harness.createCollection("OCR methods", Credential.BEARER)
        val id = harness.collectionIdOf("OCR methods")

        val response = harness.get("/api/collections/$id/reading-methods")
        val methods = ApiJson.decodeFromString<ReadingMethodsResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("tesseract", methods.default)
        assertEquals(listOf("tesseract", "surya"), methods.methods.take(2).map { it.method })
        assertTrue(methods.methods.all { it.available == (it.unavailableReason == null) })
        assertTrue(methods.methods.take(2).all { it.destination == "this machine" && !it.external })

        val missing = harness.get("/api/collections/missing/reading-methods")
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `an invalid method id is rejected without changing collection settings`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"language":"swe","defaultMethod":"llm:"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        val saved = harness.context.collectionService.get(CollectionId(id))!!
        assertEquals("eng", saved.ocrLanguages)
        assertEquals(infoscry.ocr.ReadingMethod.Tesseract, saved.ocrSettings().defaultMethod)
    }

    @Test
    fun `saving identical collection OCR settings is a no-op`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = CollectionId(harness.collectionIdOf("OCR settings"))
        val before = harness.context.collectionService.get(id)!!

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/${id.value}/ocr-languages",
            body = """{"language":"eng","defaultMethod":"tesseract"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(before.updatedAt, harness.context.collectionService.get(id)!!.updatedAt)
    }

    @Test
    fun `import route honors the stopped restart opt-in while default HTTP requests keep strict replay`() = runBlocking {
        harness.createCollection("Restart imports", Credential.BEARER)
        val collectionId = harness.collectionIdOf("Restart imports")
        val source = dataDir.resolve("restart.txt").also { Files.writeString(it, "read this") }
        val previewResponse = harness.request(
            HttpMethod.Post,
            "/api/imports/preview",
            ApiJson.encodeToString(
                ImportPreviewRouteRequest(
                    collection = collectionId,
                    paths = listOf(source.toString()),
                    method = "tesseract",
                ),
            ),
            Credential.BEARER,
        )
        assertEquals(HttpStatusCode.OK, previewResponse.status, previewResponse.bodyAsText())
        val preview = ApiJson.decodeFromString<infoscry.jobs.ImportPreview>(previewResponse.bodyAsText())
        val cliRequest = ImportRequest(
            collection = collectionId,
            paths = listOf(source.toString()),
            method = "tesseract",
            previewHash = preview.previewHash,
            requestId = "route-cli-restart",
            restartStopped = true,
        )
        suspend fun submit(request: ImportRequest): ImportAcceptedResponse {
            val response = harness.request(
                HttpMethod.Post,
                "/api/imports",
                ApiJson.encodeToString(request),
                Credential.BEARER,
            )
            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            return ApiJson.decodeFromString(response.bodyAsText())
        }

        val first = submit(cliRequest)
        harness.context.jobs.fail(harness.context.jobs.claimNextQueued()!!.id, "TEST_FAILURE", "test failure")
        val restarted = submit(cliRequest)
        assertNotEquals(first.job.id, restarted.job.id)
        harness.context.jobs.fail(harness.context.jobs.claimNextQueued()!!.id, "TEST_FAILURE", "test failure")

        val httpRequest = cliRequest.copy(requestId = "route-http-replay", restartStopped = false)
        val httpFirst = submit(httpRequest)
        harness.context.jobs.fail(harness.context.jobs.claimNextQueued()!!.id, "TEST_FAILURE", "test failure")
        val httpReplay = submit(httpRequest)
        assertEquals(httpFirst.job.id, httpReplay.job.id)
    }

    @Test
    fun `language updates preserve a disabled saved profile but cannot select another disabled profile`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = CollectionId(harness.collectionIdOf("OCR settings"))
        val draft = OcrProfileRevisionDraft(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            model = "vision-model",
            contextWindow = 32_000,
            maxOutputTokens = 2_048,
            endpoint = "https://example.invalid/v1",
        )
        val savedProfile = harness.context.ocrProfiles.create("Saved profile", draft, enabled = true)
        val disabledChoice = harness.context.ocrProfiles.create("Disabled choice", draft, enabled = false)
        val savedMethod = ReadingMethod.Llm(savedProfile.id)
        harness.context.collections.updateOcrSettings(
            id,
            CollectionOcrSettings(language = "eng", defaultMethod = savedMethod),
        )
        assertTrue(harness.context.ocrProfiles.disable(savedProfile.id))

        val firstLanguageUpdate = harness.request(
            HttpMethod.Patch,
            "/api/collections/${id.value}/ocr-languages",
            body = """{"language":"swe","defaultMethod":"${savedMethod.id}"}""",
            credential = Credential.CSRF,
        )
        val repeatedLanguageUpdate = harness.request(
            HttpMethod.Patch,
            "/api/collections/${id.value}/ocr-languages",
            body = """{"language":"swe","defaultMethod":"${savedMethod.id}"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, firstLanguageUpdate.status, firstLanguageUpdate.bodyAsText())
        assertEquals(HttpStatusCode.OK, repeatedLanguageUpdate.status, repeatedLanguageUpdate.bodyAsText())
        assertEquals("swe", harness.context.collectionService.get(id)!!.ocrLanguages)
        assertEquals(savedMethod, harness.context.collectionService.get(id)!!.ocrSettings().defaultMethod)

        val selectingDisabledProfile = harness.request(
            HttpMethod.Patch,
            "/api/collections/${id.value}/ocr-languages",
            body = """{"defaultMethod":"llm:${disabledChoice.id}"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, selectingDisabledProfile.status, selectingDisabledProfile.bodyAsText())
        assertEquals("swe", harness.context.collectionService.get(id)!!.ocrLanguages)
        assertEquals(savedMethod, harness.context.collectionService.get(id)!!.ocrSettings().defaultMethod)
    }

    @Test
    fun `collection OCR settings reject invalid languages and unknown collections`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")
        val blank = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"language":"  ","defaultMethod":"tesseract"}""",
            credential = Credential.CSRF,
        )
        val missing = harness.request(
            HttpMethod.Patch,
            "/api/collections/missing/ocr-languages",
            body = """{"language":"swe","defaultMethod":"tesseract"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("eng", harness.context.collectionService.get(CollectionId(id))!!.ocrLanguages)
    }

    @Test
    fun `renaming onto another collection's name is a conflict and keeps the old name`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)
        harness.createCollection("Nightfall", Credential.BEARER)
        val id = harness.collectionIdOf("Nightfall")

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id",
            body = """{"name":"acme"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "DUPLICATE_COLLECTION_NAME")
        assertEquals("Nightfall", harness.context.collectionService.get(CollectionId(id))!!.name)
    }

    @Test
    fun `a blank rename is refused as a bad request`() = runBlocking {
        harness.createCollection("Nightfall", Credential.BEARER)
        val id = harness.collectionIdOf("Nightfall")

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id",
            body = """{"name":"   "}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "INVALID_REQUEST")
        assertEquals("Nightfall", harness.context.collectionService.get(CollectionId(id))!!.name)
    }

    @Test
    fun `a future import snapshots the OCR languages a settings save just wrote`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")
        val source = dataDir.resolve("future.txt")
        Files.writeString(source, "A report.")

        val saved = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"language":"swe","defaultMethod":"tesseract"}""",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())

        val preview = harness.request(
            HttpMethod.Post,
            "/api/imports/preview",
            body = """{"collection":"$id","paths":["$source"],"method":"tesseract"}""",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        val previewHash = ApiJson.decodeFromString<infoscry.jobs.ImportPreview>(preview.bodyAsText()).previewHash
        val accepted = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"$id","paths":["$source"],"method":"tesseract","previewHash":"$previewHash","requestId":"collection-settings-future-import"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.Accepted, accepted.status, accepted.bodyAsText())
        val job = harness.context.jobs.list(100).single()
        assertEquals("swe", ImportJobPayload.decode(job.payload).settings.ocrLanguages)
    }

    @Test
    fun `a settings save queues no work and leaves a completed document untouched`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = CollectionId(harness.collectionIdOf("OCR settings"))
        harness.context.documents.insert(document("done-1", id))

        val languages = harness.request(
            HttpMethod.Patch,
            "/api/collections/${id.value}/ocr-languages",
            body = """{"language":"swe","defaultMethod":"tesseract"}""",
            credential = Credential.CSRF,
        )
        val renamed = harness.request(
            HttpMethod.Patch,
            "/api/collections/${id.value}",
            body = """{"name":"OCR settings renamed"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, languages.status, languages.bodyAsText())
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        assertEquals("swe", harness.context.collectionService.get(id)!!.ocrLanguages)
        // Changing settings never reprocesses a document that already completed.
        assertEquals(DocumentStatus.COMPLETE, harness.context.documents.get(DocumentId("done-1"))!!.status)
        assertEquals(0, harness.context.jobs.list(100).size, "a settings save must not queue extraction or embedding")
    }

    @Test
    fun `an unknown collection is not found`() = runBlocking {
        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/does-not-exist",
            body = """{"name":"Anything"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `deleting with the wrong confirmation is refused and changes nothing`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)
        val id = harness.collectionIdOf("Acme")

        val response = harness.request(
            HttpMethod.Delete,
            "/api/collections/$id",
            body = """{"confirmName":"Another collection"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "CONFIRMATION_MISMATCH")
        assertTrue(listed().any { it.name == "Acme" }, "a refused deletion must leave the collection alone")
    }

    @Test
    fun `deleting with the exact name admits the operation and removes the collection`() = runBlocking {
        harness.createCollection("Acme", Credential.BEARER)
        val id = harness.collectionIdOf("Acme")

        val admitted = harness.admitCollectionDeletion(id, "Acme")

        // Accepted, not done: the response names the operation to follow rather than claiming removal.
        assertEquals(id, admitted.collectionId)
        assertEquals("PREPARED", admitted.phase)
        val finished = harness.awaitDeletion(admitted.operationId)
        assertEquals("DONE", finished.phase)
        assertFalse(listed().any { it.name == "Acme" })
        assertFalse(Files.exists(harness.context.paths.collectionDir(infoscry.domain.CollectionId(id))))
    }

    @Test
    fun `deleting a collection with an unfinished import removes the import with it`() = runBlocking {
        harness.createCollection("Nightfall", Credential.BEARER)
        val id = harness.collectionIdOf("Nightfall")
        val source = dataDir.resolve("queued.txt")
        Files.writeString(source, "A report.")
        harness.context.jobs.enqueue(
            type = JobType.IMPORT,
            collectionId = CollectionId(id),
            payload = ImportJobPayload.of(
                CollectionId(id),
                listOf(source.toString()),
                ExtractionSettings(ocrLanguages = "eng"),
            ).encode(),
        )
        assertEquals(1, harness.context.jobs.list(100, 0).size)

        val admitted = harness.admitCollectionDeletion(id, "Nightfall")

        // The deletion does not wait for the import: it asks the collection's jobs to stop, and the
        // job rows go with the collection rather than outliving it.
        assertEquals("DONE", harness.awaitDeletion(admitted.operationId).phase)
        assertEquals(0, harness.context.jobs.list(100, 0).size)
        assertFalse(listed().any { it.name == "Nightfall" })
    }

    @Test
    fun `a mutating command during maintenance is refused with 423`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = CollectionIndexRemover {
            started.complete(Unit)
            release.await()
        }
        val otherDataDir = Files.createTempDirectory("infoscry-routes-maintenance")
        try {
            ApiTestServer(otherDataDir, gated).use { gatedServer ->
                gatedServer.createCollection("Nightfall", Credential.BEARER)
                val id = gatedServer.collectionIdOf("Nightfall")

                val deletion = async(Dispatchers.Default) {
                    gatedServer.request(
                        HttpMethod.Delete,
                        "/api/collections/$id",
                        body = """{"confirmName":"Nightfall"}""",
                        credential = Credential.BEARER,
                    )
                }
                withTimeout(TIMEOUT_MILLIS) { started.await() }

                val refused = gatedServer.createCollection("During maintenance", Credential.BEARER)

                assertEquals(HttpStatusCode.Locked, refused.status)
                assertContains(refused.bodyAsText(), "MAINTENANCE_IN_PROGRESS")

                // The settings routes stand behind the same shared admission as every other mutation.
                val refusedRename = gatedServer.request(
                    HttpMethod.Patch,
                    "/api/collections/$id",
                    body = """{"name":"Renamed during maintenance"}""",
                    credential = Credential.BEARER,
                )
                assertEquals(HttpStatusCode.Locked, refusedRename.status)
                assertContains(refusedRename.bodyAsText(), "MAINTENANCE_IN_PROGRESS")

                val refusedOcrSettings = gatedServer.request(
                    HttpMethod.Patch,
                    "/api/collections/$id/ocr-languages",
                    body = """{"language":"eng","defaultMethod":"surya"}""",
                    credential = Credential.CSRF,
                )
                assertEquals(HttpStatusCode.Locked, refusedOcrSettings.status)
                assertContains(refusedOcrSettings.bodyAsText(), "MAINTENANCE_IN_PROGRESS")

                release.complete(Unit)
                val admitted = withTimeout(TIMEOUT_MILLIS) { deletion.await() }
                assertEquals(HttpStatusCode.Accepted, admitted.status, admitted.bodyAsText())
                // The deletion is not the request's: it finishes in the server after the answer, which
                // is what the release of the gated phase lets it do.
                val operation = ApiJson.decodeFromString<DeleteCollectionResponse>(admitted.bodyAsText())
                assertEquals("DONE", gatedServer.awaitDeletion(operation.operationId).phase)
            }
        } finally {
            otherDataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an import request during maintenance is refused before creating a job`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = dataDir.resolve("during-maintenance.txt")
        Files.writeString(source, "A report.")
        // The import needs a collection that exists: a refused request is refused for maintenance, not
        // for an unknown collection.
        harness.createCollection("Default", Credential.BEARER)
        val maintenance = async(Dispatchers.Default) {
            harness.context.mutations.withExclusiveMaintenance("reindex") {
                started.complete(Unit)
                release.await()
            }
        }
        withTimeout(TIMEOUT_MILLIS) { started.await() }

        val refused = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"Default","paths":["$source"],"method":"tesseract","previewHash":"${"a".repeat(64)}","requestId":"locked-import"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.Locked, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "MAINTENANCE_IN_PROGRESS")
        assertEquals(0, harness.context.jobs.list(100, 0).size, "refused import must not create a job row")

        release.complete(Unit)
        withTimeout(TIMEOUT_MILLIS) { maintenance.await() }
        val stale = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"Default","paths":["$source"],"method":"tesseract","previewHash":"${"a".repeat(64)}","requestId":"stale-import"}""",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
        assertContains(stale.bodyAsText(), "PREVIEW_STALE")
        assertEquals(0, harness.context.jobs.list(100, 0).size)
    }

    @Test
    fun `an import naming both an include and an exclude list is refused before any job is created`() = runBlocking {
        val source = dataDir.resolve("both-lists.txt")
        Files.writeString(source, "A report.")
        harness.createCollection("Default", Credential.BEARER)

        val refused = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"Default","paths":["$source"],"method":"tesseract","previewHash":"${"a".repeat(64)}","requestId":"bad-filters","include":["pdf"],"exclude":["log"]}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "INVALID_REQUEST")
        assertEquals(0, harness.context.jobs.list(100, 0).size, "a refused request must not create a job row")
    }

    @Test
    @Tag(FRONTEND_TAG)
    fun `the compiled web application is served and client-side routes fall back to it`() = runBlocking {
        val root = harness.get("/")

        assertEquals(HttpStatusCode.OK, root.status, "the built frontend has to be packaged and served")
        assertContains(
            root.bodyAsText(),
            "/_app/",
            message = "the root has to serve the real application bundle, not a placeholder",
        )
        val scriptPath = Regex("/_app/immutable/entry/start\\.[A-Za-z0-9_-]+\\.js")
            .find(root.bodyAsText())?.value ?: error("frontend shell lacks its start script")
        val script = harness.get(scriptPath)
        assertEquals(HttpStatusCode.OK, script.status)
        assertContains(script.headers["Content-Type"].orEmpty(), "javascript")
        assertContains(script.bodyAsText(), "import")

        // The frontend is rendered in the browser, so a deep link has to reach the shell too.
        assertEquals(HttpStatusCode.OK, harness.get("/collections/anything").status)

        // An unknown API route stays an API answer: HTML here would hide a client's typo.
        val unknownApi = harness.get("/api/not-a-route")
        assertEquals(HttpStatusCode.NotFound, unknownApi.status)
        assertContains(unknownApi.bodyAsText(), "NOT_FOUND")
    }

    private fun document(id: String, collectionId: CollectionId): Document {
        val now = Instants.now()
        return Document(
            id = DocumentId(id),
            collectionId = collectionId,
            sha256 = "sha256-of-$id",
            mediaType = "application/pdf",
            originalFilename = "$id.pdf",
            sourcePath = "/private/evidence/$id.pdf",
            sizeBytes = 1024,
            status = DocumentStatus.COMPLETE,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun listed(): List<infoscry.domain.Collection> =
        ApiJson.decodeFromString<CollectionsResponse>(harness.get("/api/collections").bodyAsText()).collections

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
