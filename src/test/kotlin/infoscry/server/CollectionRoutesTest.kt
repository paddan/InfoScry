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
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrProfileRevisionDraft
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
    fun `OCR languages can be updated through the guarded collection route`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrLanguages":"eng+ swe "}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "eng+ swe")
        assertEquals("eng+ swe", harness.context.collectionService.get(infoscry.domain.CollectionId(id))!!.ocrLanguages)
    }

    @Test
    fun `changing collection OCR languages does not change an already queued import snapshot`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = CollectionId(harness.collectionIdOf("OCR settings"))
        val queued = harness.context.jobs.enqueue(
            type = JobType.IMPORT,
            collectionId = id,
            payload = ImportJobPayload.of(
                id,
                listOf(dataDir.resolve("queued.txt").toString()),
                ExtractionSettings(ocrLanguages = "eng"),
            ).encode(),
        )

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/${id.value}/ocr-languages",
            body = """{"ocrLanguages":"swe"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("eng", ImportJobPayload.decode(harness.context.jobs.get(queued.id)!!.payload).settings.ocrLanguages)
        assertEquals("swe", harness.context.collectionService.get(id)!!.ocrLanguages)
    }

    @Test
    fun `blank OCR languages are rejected and leave settings unchanged`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrLanguages":"   "}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("eng", harness.context.collectionService.get(infoscry.domain.CollectionId(id))!!.ocrLanguages)
    }

    @Test
    fun `multiline OCR languages are rejected at the settings boundary`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")

        // A newline in the value is accepted by JSON and means nothing to Tesseract, but the fingerprint of
        // every reading this collection produces is line-delimited: rejecting it here names the field,
        // instead of failing every later import in a place nobody reading the queue can attribute.
        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrLanguages":"eng\nswe"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "line break")
        assertEquals(
            "eng",
            harness.context.collectionService.get(CollectionId(id))!!.ocrLanguages,
            "a refused edit changed the collection",
        )
    }

    @Test
    fun `OCR language update for an unknown or deleting collection is not found`() = runBlocking {
        val missing = harness.request(
            HttpMethod.Patch,
            "/api/collections/missing/ocr-languages",
            body = """{"ocrLanguages":"swe"}""",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.NotFound, missing.status)

        harness.createCollection("Deleting", Credential.BEARER)
        val id = harness.collectionIdOf("Deleting")
        harness.context.collectionService.beginDeletion(infoscry.domain.CollectionId(id), "Deleting")
        val deleting = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrLanguages":"swe"}""",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.NotFound, deleting.status)
    }

    @Test
    fun `a collection created without OCR settings reports the legacy defaults`() = runBlocking {
        harness.createCollection("OCR defaults", Credential.BEARER)

        val body = harness.get("/api/collections").bodyAsText()
        val collection = listed().single { it.name == "OCR defaults" }

        // Old clients and old archives see exactly the behavior they had: Tesseract, the languages the
        // collection already carried, fill-missing mode and no external pages.
        assertContains(body, "\"ocrEngine\":\"TESSERACT\"")
        assertContains(body, "\"ocrImportMode\":\"FILL_MISSING\"")
        assertEquals("eng", collection.ocrLanguages)
        assertNull(collection.ocrTranscriptionProfileId)
        assertNull(collection.ocrReviewProfileId, "review is unavailable until a profile is chosen")
        assertEquals(0, collection.ocrExternalPageLimit)
    }

    @Test
    fun `a language-only settings patch keeps the engine and profiles a newer client chose`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")
        val selected = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrEngine":"SURYA","ocrImportMode":"CHECK_AND_IMPROVE","ocrExternalPageLimit":10}""",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.OK, selected.status, selected.bodyAsText())

        val languages = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrLanguages":"swe"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, languages.status, languages.bodyAsText())
        assertContains(languages.bodyAsText(), "\"ocrEngine\":\"SURYA\"", message = "an old request is not a reset")
        val saved = harness.context.collectionService.get(CollectionId(id))!!
        assertEquals("swe", saved.ocrLanguages)
        assertEquals(OcrImportMode.CHECK_AND_IMPROVE, saved.ocrImportMode)
        assertEquals(10, saved.ocrExternalPageLimit)
    }

    @Test
    fun `the OCR settings patch stores the engine, profiles and external page allowance`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")
        val transcriber = harness.context.ocr.create("Transcriber", draft(model = "vision-1"), enabled = true)
        val reviewer = harness.context.ocr.create("Reviewer", draft(model = "review-1"), enabled = true)

        val response = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrLanguages":"deu+eng","ocrEngine":"LLM","ocrImportMode":"CHECK_AND_IMPROVE","ocrTranscriptionProfileId":"${transcriber.id}","ocrReviewProfileId":"${reviewer.id}","ocrExternalPageLimit":50}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val saved = harness.context.collectionService.get(CollectionId(id))!!
        assertEquals(OcrEngine.LLM, saved.ocrEngine)
        assertEquals(OcrImportMode.CHECK_AND_IMPROVE, saved.ocrImportMode)
        assertEquals(transcriber.id, saved.ocrTranscriptionProfileId)
        assertEquals(reviewer.id, saved.ocrReviewProfileId)
        assertEquals(50, saved.ocrExternalPageLimit)
        assertEquals("deu+eng", saved.ocrLanguages)

        // An external transcription profile is an explicit choice, and a blank reviewer id clears it.
        val cleared = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrEngine":"LLM","ocrTranscriptionProfileId":"${transcriber.id}","ocrReviewProfileId":""}""",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        assertNull(harness.context.collectionService.get(CollectionId(id))!!.ocrReviewProfileId)
    }

    @Test
    fun `an engine that disagrees with its profiles is refused and changes nothing`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")
        val transcriber = harness.context.ocr.create("Transcriber", draft(), enabled = true)

        val llmWithoutProfile = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrEngine":"LLM"}""",
            credential = Credential.CSRF,
        )
        val profileWithLocalEngine = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrEngine":"TESSERACT","ocrTranscriptionProfileId":"${transcriber.id}"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, llmWithoutProfile.status, llmWithoutProfile.bodyAsText())
        assertContains(llmWithoutProfile.bodyAsText(), "INVALID_REQUEST")
        assertEquals(HttpStatusCode.BadRequest, profileWithLocalEngine.status, profileWithLocalEngine.bodyAsText())
        val saved = harness.context.collectionService.get(CollectionId(id))!!
        assertEquals(OcrEngine.TESSERACT, saved.ocrEngine)
        assertNull(saved.ocrTranscriptionProfileId)
    }

    @Test
    fun `an unknown or disabled profile id is refused without naming it back`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")
        val retired = harness.context.ocr.create("Retired", draft(), enabled = true)
        harness.context.ocr.disable(retired.id)

        val unknown = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrEngine":"LLM","ocrTranscriptionProfileId":"sk-live-not-a-profile-id"}""",
            credential = Credential.CSRF,
        )
        val disabled = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrEngine":"LLM","ocrTranscriptionProfileId":"${retired.id}"}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, unknown.status, unknown.bodyAsText())
        assertFalse(unknown.bodyAsText().contains("sk-live-not-a-profile-id"), "the id is not echoed back")
        assertEquals(HttpStatusCode.BadRequest, disabled.status, disabled.bodyAsText())
        assertNull(harness.context.collectionService.get(CollectionId(id))!!.ocrTranscriptionProfileId)
    }

    @Test
    fun `a negative or malformed external page allowance is refused and changes nothing`() = runBlocking {
        harness.createCollection("OCR settings", Credential.BEARER)
        val id = harness.collectionIdOf("OCR settings")

        val negative = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrExternalPageLimit":-1}""",
            credential = Credential.CSRF,
        )
        // A count that is not a whole number is not a smaller allowance or a larger one; it is a body the
        // caller has to fix, which is what makes it the same refusal as a negative count.
        val fractional = harness.request(
            HttpMethod.Patch,
            "/api/collections/$id/ocr-languages",
            body = """{"ocrExternalPageLimit":2.5}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, negative.status, negative.bodyAsText())
        assertContains(negative.bodyAsText(), "INVALID_REQUEST")
        assertEquals(HttpStatusCode.BadRequest, fractional.status, fractional.bodyAsText())
        assertEquals(0, harness.context.collectionService.get(CollectionId(id))!!.ocrExternalPageLimit)
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
            body = """{"ocrLanguages":"swe"}""",
            credential = Credential.CSRF,
        )
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())

        val accepted = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"OCR settings","paths":["$source"]}""",
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
            body = """{"ocrLanguages":"swe"}""",
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
        val accepted = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"$id","paths":["$source"]}""",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.Accepted, accepted.status, accepted.bodyAsText())
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
                    body = """{"ocrEngine":"SURYA"}""",
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
            body = """{"collection":"Default","paths":["$source"]}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.Locked, refused.status, refused.bodyAsText())
        assertContains(refused.bodyAsText(), "MAINTENANCE_IN_PROGRESS")
        assertEquals(0, harness.context.jobs.list(100, 0).size, "refused import must not create a job row")

        release.complete(Unit)
        withTimeout(TIMEOUT_MILLIS) { maintenance.await() }
        val accepted = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"Default","paths":["$source"]}""",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.Accepted, accepted.status, accepted.bodyAsText())
        assertEquals(1, harness.context.jobs.list(100, 0).size)
    }

    @Test
    fun `an import naming both an include and an exclude list is refused before any job is created`() = runBlocking {
        val source = dataDir.resolve("both-lists.txt")
        Files.writeString(source, "A report.")
        harness.createCollection("Default", Credential.BEARER)

        val refused = harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"Default","paths":["$source"],"include":["pdf"],"exclude":["log"]}""",
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

    private fun draft(model: String = "vision-model") = OcrProfileRevisionDraft(
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = model,
        contextWindow = 32_000,
        maxOutputTokens = 4_096,
        inputPricePerMillion = 0.0,
        outputPricePerMillion = 0.0,
    )

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
