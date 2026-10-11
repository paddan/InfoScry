package infoscry.server

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.JobType
import infoscry.domain.SourceLocation
import infoscry.jobs.JobStage
import infoscry.jobs.SelectablePdfJobHandler
import infoscry.jobs.seedCollectionWithId
import infoscry.pdf.PdfArtifactState
import infoscry.pdf.PdfArtifactStatus
import infoscry.pdf.PdfPageCoverage
import infoscry.pdf.SourceContent
import infoscry.pdf.SourcePage
import infoscry.pdf.SelectablePdfService
import infoscry.pdf.SelectablePdfTestFixtures
import infoscry.pdf.SelectablePdfTestFixtures.Fixture
import infoscry.pdf.TextRun
import infoscry.pdf.WriterFixtures
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The selectable-PDF HTTP surface (ticket 07): status, explicit create/retry, and the verified file.
 *
 * Every fixture is a real published revision over a real SQLite data directory, and every request goes over a
 * real loopback socket through [ApiTestServer], so the guarantees here are about the wire. No job runs unless a
 * test runs it, so a QUEUED, BUILDING or READY state is always one the test put there on purpose.
 */
class SelectablePdfRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer
    private val context: AppContext get() = harness.context

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-selectable-pdf-routes")
        harness = ApiTestServer(dataDir)
        context.seedCollectionWithId("default", "Default")
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    // ---- Vector 1: status is exact, typed and free of side effects -----------------------------------------------

    @Test
    fun `status of a document that never needed a PDF answers NOT_CREATED with its exact revision`() {
        val plain = SelectablePdfTestFixtures.publishPlainTextDocument(context, dataDir)
        http {
            val response = getStatus(plain.documentId, plain.revisionId)
            assertEquals(HttpStatusCode.OK, response.status)
            val status = response.parsedStatus()
            assertEquals(plain.revisionId, status.revisionId)
            assertEquals(PdfArtifactState.NOT_CREATED, status.state)
            assertNull(status.key)
            assertNull(status.totalPages)
            assertEquals(0, status.selectablePages)
            assertEquals(emptyList(), status.unavailablePages)
            assertNull(status.reasonCode)
        }
    }

    @Test
    fun `status of a freshly published PDF answers QUEUED with a key and no counts yet`() {
        val fixture = publishNative()
        http {
            val response = getStatus(fixture.documentId, fixture.revisionId)
            assertEquals(HttpStatusCode.OK, response.status)
            val status = response.parsedStatus()
            assertEquals(fixture.revisionId, status.revisionId)
            assertEquals(PdfArtifactState.QUEUED, status.state)
            assertNotNull(status.key)
            assertNull(status.totalPages)
            assertEquals(0, status.selectablePages)
        }
    }

    @Test
    fun `status of a generation that has started answers BUILDING under the key it was started with`() {
        val fixture = publishNative()
        val started = context.selectablePdfService.prepareBuild(fixture.documentId, fixture.revisionId)
        val ready = assertNotNull(started as? infoscry.pdf.SelectablePdfService.PreparedBuild.Ready)
        http {
            val status = getStatus(fixture.documentId, fixture.revisionId).parsedStatus()
            assertEquals(PdfArtifactState.BUILDING, status.state)
            assertEquals(ready.key, status.key)
        }
    }

    @Test
    fun `status of a built PDF answers READY with every physical page counted`() {
        val fixture = publishNative()
        runJob()
        http {
            val status = getStatus(fixture.documentId, fixture.revisionId).parsedStatus()
            assertEquals(PdfArtifactState.READY, status.state)
            assertEquals(1, status.selectablePages)
            assertEquals(1, status.totalPages)
            assertEquals(0, status.blankPages)
            assertEquals(emptyList(), status.unavailablePages)
            assertNull(status.reasonCode)
        }
    }

    @Test
    fun `status of a partly positioned revision answers PARTIAL and names the unavailable page`() {
        val fixture = SelectablePdfTestFixtures.publishOcrPageWithMissingLayout(context, dataDir)
        runJob()
        http {
            val status = getStatus(fixture.documentId, fixture.revisionId).parsedStatus()
            assertEquals(PdfArtifactState.PARTIAL, status.state)
            assertEquals(2, status.totalPages)
            assertEquals(1, status.selectablePages)
            assertEquals(1, status.unavailablePages.size)
            assertEquals(1, status.unavailablePages.single().ordinal)
            assertEquals(PdfPageCoverage.UNAVAILABLE, status.unavailablePages.single().coverage)
            assertNotNull(status.unavailablePages.single().reasonCode)
        }
    }

    @Test
    fun `status of a READY artifact whose file was corrupted answers FAILED with a safe reason and no path`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        Files.writeString(artifactPdf(fixture, key), "tampered")
        http {
            val response = getStatus(fixture.documentId, fixture.revisionId)
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            val status = ApiJson.decodeFromString<PdfArtifactStatus>(text)
            assertEquals(PdfArtifactState.FAILED, status.state)
            assertEquals(SelectablePdfService.VERIFICATION_FAILED_CODE, status.reasonCode)
            assertNoLeak(text, fixture)
        }
    }

    @Test
    fun `status of a READY artifact whose file is missing answers FAILED`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        Files.delete(artifactPdf(fixture, key))
        http {
            val status = getStatus(fixture.documentId, fixture.revisionId).parsedStatus()
            assertEquals(PdfArtifactState.FAILED, status.state)
            assertEquals(SelectablePdfService.VERIFICATION_FAILED_CODE, status.reasonCode)
        }
    }

    @Test
    fun `repeated status and file reads cause no jobs, no files and no state change`() {
        val fixture = publishNative()
        val queuedBefore = footprint(fixture)
        http {
            repeat(5) {
                getStatus(fixture.documentId, fixture.revisionId)
                getFile(fixture, keyOf(fixture), inline = true)
            }
        }
        assertEquals(queuedBefore, footprint(fixture), "reading a QUEUED revision must not enqueue, build or write")

        runJob()
        val readyBefore = footprint(fixture)
        val statusBefore = http { getStatus(fixture.documentId, fixture.revisionId).bodyAsText() }
        http {
            repeat(5) {
                getStatus(fixture.documentId, fixture.revisionId)
                getFile(fixture, keyOf(fixture), inline = true)
            }
        }
        assertEquals(readyBefore, footprint(fixture), "reading a READY revision must not enqueue, build or write")
        assertEquals(statusBefore, http { getStatus(fixture.documentId, fixture.revisionId).bodyAsText() })
    }

    @Test
    fun `status with a missing, blank or malformed revision is a bad request`() {
        val fixture = publishNative()
        http {
            assertEquals(HttpStatusCode.BadRequest, getRaw(statusPath(fixture.documentId) ).status)
            assertEquals(HttpStatusCode.BadRequest, getRaw(statusPath(fixture.documentId) + "?revision=").status)
            assertEquals(HttpStatusCode.BadRequest, getRaw(statusPath(fixture.documentId) + "?revision=bad%20rev").status)
            assertEquals(HttpStatusCode.BadRequest, getRaw(statusPath(fixture.documentId) + "?revision=" + "x".repeat(200)).status)
        }
    }

    // ---- Vector 2: POST uses the shared admission, validates, and is idempotent ----------------------------------

    @Test
    fun `a create without a credential is refused by the existing mutation guard`() {
        val fixture = publishNative()
        val before = footprint(fixture)
        http {
            val body = createBody(fixture.revisionId)
            assertEquals(HttpStatusCode.Unauthorized, postCreate(fixture, body, Credential.NONE).status)
            assertEquals(HttpStatusCode.Unauthorized, postCreate(fixture, body, Credential.WRONG_CSRF).status)
            assertEquals(HttpStatusCode.Unauthorized, postCreate(fixture, body, Credential.WRONG_BEARER).status)
        }
        assertEquals(before, footprint(fixture), "a refused create enqueues nothing")
    }

    @Test
    fun `a create with the session CSRF token or the CLI bearer is admitted and returns the typed status`() {
        val fixture = publishNative()
        http {
            val byCsrf = postCreate(fixture, createBody(fixture.revisionId), Credential.CSRF)
            assertEquals(HttpStatusCode.OK, byCsrf.status)
            assertEquals(PdfArtifactState.QUEUED, byCsrf.parsedStatus().state)
            val byBearer = postCreate(fixture, createBody(fixture.revisionId), Credential.BEARER)
            assertEquals(HttpStatusCode.OK, byBearer.status)
            assertEquals(byCsrf.parsedStatus(), byBearer.parsedStatus())
        }
    }

    @Test
    fun `repeated creates return the same identity and never enqueue a second job`() {
        val fixture = publishNative()
        val jobsBefore = selectableJobCount()
        http {
            val first = postCreate(fixture, createBody(fixture.revisionId, retry = false)).parsedStatus()
            val second = postCreate(fixture, createBody(fixture.revisionId, retry = false)).parsedStatus()
            val third = postCreate(fixture, createBody(fixture.revisionId, retry = true)).parsedStatus()
            assertEquals(first, second)
            assertEquals(first, third, "retry has no effect on a QUEUED artifact")
        }
        assertEquals(jobsBefore, selectableJobCount())
    }

    @Test
    fun `a create on a READY artifact returns it unchanged and enqueues nothing`() {
        val fixture = publishNative()
        runJob()
        val before = footprint(fixture)
        http {
            val status = postCreate(fixture, createBody(fixture.revisionId, retry = true)).parsedStatus()
            assertEquals(PdfArtifactState.READY, status.state)
        }
        assertEquals(before, footprint(fixture))
    }

    @Test
    fun `a retry of a FAILED artifact is admitted once and a plain create does not repeat it`() {
        val fixture = publishNative()
        runJob()
        Files.writeString(artifactPdf(fixture, keyOf(fixture)), "tampered")
        http { getStatus(fixture.documentId, fixture.revisionId) }
        val jobsBefore = selectableJobCount()
        http {
            val plain = postCreate(fixture, createBody(fixture.revisionId, retry = false)).parsedStatus()
            assertEquals(PdfArtifactState.FAILED, plain.state)
            assertEquals(jobsBefore, selectableJobCount(), "a plain create does not retry a failed artifact")

            val retried = postCreate(fixture, createBody(fixture.revisionId, retry = true)).parsedStatus()
            assertEquals(PdfArtifactState.QUEUED, retried.state)
            assertEquals(jobsBefore + 1, selectableJobCount())
        }
        runJob()
        assertEquals(PdfArtifactState.READY, http { getStatus(fixture.documentId, fixture.revisionId).parsedStatus() }.state)
    }

    @Test
    fun `a create with a missing, mistyped or malformed body is a bad request and enqueues nothing`() {
        val fixture = publishNative()
        val before = footprint(fixture)
        http {
            val bodies = listOf(
                """{"revisionId":"${fixture.revisionId}"}""",
                """{"retry":false}""",
                """{"revisionId":"${fixture.revisionId}","retry":"yes"}""",
                """{"revisionId":"","retry":false}""",
                """{"revisionId":"${fixture.revisionId}","retry":"true"}""",
                "not json",
            )
            for (body in bodies) {
                val response = postCreate(fixture, body)
                assertEquals(HttpStatusCode.BadRequest, response.status, "body $body")
                assertEquals("INVALID_REQUEST", response.errorCode(), "body $body")
            }
        }
        assertEquals(before, footprint(fixture))
    }

    @Test
    fun `a create naming an unknown, foreign or unpublished revision is not found and enqueues nothing`() {
        val fixture = publishNative()
        val other = publishNative(text = "another document entirely")
        val candidate = context.revisions.openCandidate(fixture.documentId, fixture.revisionId, "TEST_IMPORT")
        val before = selectableJobCount()
        http {
            assertEquals(HttpStatusCode.NotFound, postCreate(fixture, createBody(UNKNOWN_REVISION)).status)
            assertEquals(HttpStatusCode.NotFound, postCreate(fixture, createBody(other.revisionId)).status)
            assertEquals(HttpStatusCode.NotFound, postCreate(fixture, createBody(candidate)).status)
        }
        assertEquals(before, selectableJobCount())
    }

    @Test
    fun `a create for a document that is being deleted is refused with a conflict and enqueues nothing`() {
        val fixture = publishNative()
        val before = selectableJobCount()
        context.documentService.beginDeletion(fixture.collectionId, listOf(fixture.documentId))
        http {
            val response = postCreate(fixture, createBody(fixture.revisionId, retry = true))
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("DOCUMENT_BEING_DELETED", response.errorCode())
        }
        assertEquals(before, selectableJobCount())
    }

    // ---- Vector 3: the file is served only for the exact key, and only when verified ------------------------------

    @Test
    fun `the file of a READY revision is served as verified application pdf bytes`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        val expected = Files.readAllBytes(artifactPdf(fixture, key))
        http {
            val response = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(expected.toList(), response.readBytes().toList())
        }
    }

    @Test
    fun `the file of a PARTIAL revision is served too, since its unavailable pages are listed in its status`() {
        val fixture = SelectablePdfTestFixtures.publishOcrPageWithMissingLayout(context, dataDir)
        runJob()
        val key = keyOf(fixture)
        http {
            val response = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("application/pdf", response.contentTypeBase())
        }
    }

    @Test
    fun `a file request without a key, with a malformed key or with a malformed inline flag is a bad request`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        http {
            val base = filePath(fixture, "revision=${fixture.revisionId}")
            assertEquals(HttpStatusCode.BadRequest, getRaw(base).status, "no key")
            assertEquals(HttpStatusCode.BadRequest, getRaw(base + "&key=not-a-digest").status, "short key")
            assertEquals(HttpStatusCode.BadRequest, getRaw(base + "&key=" + key.uppercase()).status, "uppercase key")
            assertEquals(HttpStatusCode.BadRequest, getRaw(base + "&key=$key&inline=maybe").status, "inline word")
            assertEquals(HttpStatusCode.BadRequest, getRaw(base + "&key=$key&inline=1").status, "inline digit")
            assertEquals(HttpStatusCode.BadRequest, getRaw("${fixture.documentPath()}/file?key=$key").status, "no revision")
        }
    }

    @Test
    fun `a file request that names no such revision, a foreign key or a never-built document is not found`() {
        val fixture = publishNative()
        val plain = SelectablePdfTestFixtures.publishPlainTextDocument(context, dataDir)
        val candidate = context.revisions.openCandidate(fixture.documentId, fixture.revisionId, "TEST_IMPORT")
        runJob()
        val key = keyOf(fixture)
        http {
            assertEquals(HttpStatusCode.NotFound, getFile(fixture, key, revision = UNKNOWN_REVISION).status, "unknown revision")
            assertEquals(HttpStatusCode.NotFound, getFile(fixture, key, revision = candidate).status, "unpublished candidate")
            assertEquals(HttpStatusCode.NotFound, getFile(fixture, ZERO_DIGEST, inline = true).status, "absent key")
            assertEquals(HttpStatusCode.NotFound, getFile(plain, ZERO_DIGEST, inline = true).status, "never built")
        }
    }

    @Test
    fun `a file request for a revision of another collection or document is not found and leaks nothing`() {
        val fixture = publishNative(text = "private reading of the first document")
        runJob()
        val key = keyOf(fixture)
        val crossDocument = publishNative(text = "another document entirely")
        http {
            assertEquals(HttpStatusCode.Created, harness.createCollection("other").status)
            val foreignCollection = getRaw(
                "/api/collections/other/documents/${fixture.documentId.value}/selectable-pdf/file" +
                    "?revision=${fixture.revisionId}&key=$key&inline=true",
            )
            assertEquals(HttpStatusCode.NotFound, foreignCollection.status)
            assertNoLeak(foreignCollection.bodyAsText(), fixture)
            assertFalse(foreignCollection.bodyAsText().contains("private reading"))

            val wrongDocument = getRaw(
                "${crossDocument.documentPath()}/file?revision=${fixture.revisionId}&key=$key&inline=true",
            )
            assertEquals(HttpStatusCode.NotFound, wrongDocument.status)
        }
    }

    @Test
    fun `a file request for a revision whose artifact is not ready is a conflict with a safe code`() {
        val fixture = publishNative()
        val key = keyOf(fixture)
        http {
            val queued = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.Conflict, queued.status)
            assertEquals("SELECTABLE_PDF_NOT_READY", queued.errorCode())
            assertNoLeak(queued.bodyAsText(), fixture)
        }
        context.selectablePdfService.prepareBuild(fixture.documentId, fixture.revisionId)
        http {
            val building = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.Conflict, building.status)
            assertEquals("SELECTABLE_PDF_NOT_READY", building.errorCode())
        }
    }

    @Test
    fun `a file request for a corrupted artifact is a conflict with a safe code and no bytes`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        Files.writeString(artifactPdf(fixture, key), "tampered")
        http {
            val response = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(SelectablePdfService.VERIFICATION_FAILED_CODE, response.errorCode())
            assertNoLeak(response.bodyAsText(), fixture)
        }
    }

    @Test
    fun `a file request for a missing artifact is a conflict with a safe code`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        Files.delete(artifactPdf(fixture, key))
        http {
            val response = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(SelectablePdfService.VERIFICATION_FAILED_CODE, response.errorCode())
        }
    }

    // ---- Vector 4: headers, confinement and safe encoding ---------------------------------------------------------

    @Test
    fun `the file response carries application pdf, exact length, a hash ETag, a private cache policy and inline disposition`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        val file = artifactPdf(fixture, key)
        val digest = sha256Hex(file)
        http {
            val response = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("application/pdf", response.contentTypeBase())
            assertEquals(Files.size(file).toString(), response.headers[HttpHeaders.ContentLength])
            assertEquals("\"$digest\"", response.headers[HttpHeaders.ETag])
            assertContains(response.headers[HttpHeaders.CacheControl].orEmpty(), "private")
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("inline;"))
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals(Files.size(file), response.readBytes().size.toLong())
        }
    }

    @Test
    fun `the file is an attachment unless inline is asked for`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        http {
            val defaulted = getFile(fixture, key, inline = null)
            assertTrue(defaulted.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment;"))
            val explicitFalse = getRaw(filePath(fixture, "revision=${fixture.revisionId}&key=$key&inline=false"))
            assertTrue(explicitFalse.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment;"))
        }
    }

    @Test
    fun `a crafted document name cannot inject a header or a second cookie into the file response`() {
        val source = Files.createDirectories(dataDir.resolve("sources"))
            .resolve("evil\r\nSet-Cookie: pwned=1 \"quoted\"; x.pdf")
        val fixture = publishSource(source, "a reading under a hostile file name")
        runJob()
        val key = keyOf(fixture)
        http {
            val response = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(response.headers["Set-Cookie"], "the crafted name must not become a response header")
            val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
            assertFalse(disposition.contains('\r') || disposition.contains('\n'))
            assertEquals(2, disposition.count { it == '"' }, "only the filename delimiters are quoted: $disposition")
            assertContains(disposition, "filename*=UTF-8''")
        }
    }

    @Test
    fun `a file that is a symbolic link is refused even when its bytes match`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        val real = artifactPdf(fixture, key).resolveSibling("real-copy.pdf")
        Files.move(artifactPdf(fixture, key), real)
        Files.createSymbolicLink(artifactPdf(fixture, key), real.fileName)
        http {
            val response = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(SelectablePdfService.VERIFICATION_FAILED_CODE, response.errorCode())
        }
    }

    @Test
    fun `an artifact directory reached through a symbolic link is refused even when its bytes match`() {
        val fixture = publishNative()
        runJob()
        val key = keyOf(fixture)
        val directory = artifactPdf(fixture, key).parent
        val moved = dataDir.resolve("moved-away-$key")
        Files.move(directory, moved)
        Files.createSymbolicLink(directory, moved)
        http {
            val response = getFile(fixture, key, inline = true)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(SelectablePdfService.VERIFICATION_FAILED_CODE, response.errorCode())
        }
        assertTrue(Files.isSymbolicLink(directory), "the refusal must leave the link alone")
        assertFalse(Files.isSymbolicLink(moved))
    }

    // ---- Vector 5: a historical revision stays itself, and a deleted document stays undownloadable --------------

    @Test
    fun `a historical revision keeps returning its own artifact after a newer revision is published`() {
        val first = publishNative(text = "the first reading of the document")
        runJob()
        val firstKey = keyOf(first)
        val firstBytes = Files.readAllBytes(artifactPdf(first, firstKey))

        val second = publishNewerRevision(first, "the second reading of the document")
        val secondKey = keyOf(second)
        assertNotEquals(firstKey, secondKey)
        http {
            val historical = getStatus(first.documentId, first.revisionId).parsedStatus()
            assertEquals(PdfArtifactState.READY, historical.state)
            assertEquals(firstKey, historical.key)
            val historicalFile = getFile(first, firstKey, inline = true)
            assertEquals(HttpStatusCode.OK, historicalFile.status)
            assertEquals(firstBytes.toList(), historicalFile.readBytes().toList())

            assertEquals(PdfArtifactState.QUEUED, getStatus(second.documentId, second.revisionId).parsedStatus().state)
            assertEquals(HttpStatusCode.Conflict, getFile(second, secondKey, inline = true).status)
            assertEquals(HttpStatusCode.NotFound, getFile(first, secondKey, inline = true).status, "a key names one revision only")
        }

        runJob()
        http {
            assertEquals(HttpStatusCode.OK, getFile(second, secondKey, inline = true).status)
            assertEquals(firstBytes.toList(), getFile(first, firstKey, inline = true).readBytes().toList())
        }
    }

    @Test
    fun `a document deleted while its PDF was queued never yields a downloadable file`() {
        val fixture = publishNative()
        val key = keyOf(fixture)
        val deletion = http {
            val response = harness.request(
                HttpMethod.Post,
                "/api/collections/default/documents/delete",
                """{"documentIds":["${fixture.documentId.value}"],"confirmed":true}""",
                Credential.CSRF,
            )
            assertEquals(HttpStatusCode.Accepted, response.status)
            val operationId = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("operationId").jsonPrimitive.content
            harness.awaitDeletion(operationId)
        }
        assertNull(deletion.errorCode, "the deletion finished without error")
        runLeftoverJobIfAny()
        http {
            assertEquals(HttpStatusCode.NotFound, getStatus(fixture.documentId, fixture.revisionId).status)
            assertEquals(HttpStatusCode.NotFound, getFile(fixture, key, inline = true).status)
        }
        assertFalse(Files.exists(artifactPdf(fixture, key)), "no late file may be written for a deleted document")
    }

    @Test
    fun `a job that finishes while its document is being deleted writes nothing and serves nothing`() {
        val fixture = publishNative()
        val key = keyOf(fixture)
        context.documentService.beginDeletion(fixture.collectionId, listOf(fixture.documentId))
        runLeftoverJobIfAny()
        assertFalse(Files.exists(artifactPdf(fixture, key)), "a build for a document under deletion writes no file")
        http {
            assertEquals(HttpStatusCode.Conflict, getFile(fixture, key, inline = true).status)
        }
    }

    // ---- Harness -----------------------------------------------------------------------------------------------

    private fun <T> http(block: suspend () -> T): T = runBlocking { block() }

    private fun statusPath(documentId: DocumentId): String =
        "/api/collections/default/documents/${documentId.value}/selectable-pdf"

    private fun Fixture.documentPath(): String = statusPath(documentId)

    private fun filePath(fixture: Fixture, query: String): String = "${statusPath(fixture.documentId)}/file?$query"

    private suspend fun getStatus(documentId: DocumentId, revisionId: String): HttpResponse =
        harness.get("${statusPath(documentId)}?revision=$revisionId")

    private suspend fun getRaw(path: String): HttpResponse = harness.get(path)

    private suspend fun getFile(
        fixture: Fixture,
        key: String,
        inline: Boolean? = true,
        revision: String = fixture.revisionId,
    ): HttpResponse {
        val inlineQuery = inline?.let { "&inline=$it" }.orEmpty()
        return harness.get(filePath(fixture, "revision=$revision&key=$key$inlineQuery"))
    }

    private suspend fun postCreate(
        fixture: Fixture,
        body: String,
        credential: Credential = Credential.CSRF,
    ): HttpResponse = harness.request(HttpMethod.Post, statusPath(fixture.documentId), body, credential)

    private fun createBody(revisionId: String, retry: Boolean = false): String =
        """{"revisionId":"$revisionId","retry":$retry}"""

    private suspend fun HttpResponse.parsedStatus(): PdfArtifactStatus =
        ApiJson.decodeFromString(bodyAsText())

    private suspend fun HttpResponse.errorCode(): String =
        ApiJson.decodeFromString<ApiErrorResponse>(bodyAsText()).error.code

    private fun HttpResponse.contentTypeBase(): String? =
        headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()

    /** The artifact's own PDF file, the way the service lays it out. */
    private fun artifactPdf(fixture: Fixture, key: String): Path =
        context.paths.selectablePdfDir(fixture.collectionId, fixture.documentId, fixture.revisionId, key)
            .resolve(SelectablePdfService.PDF_FILE_NAME)

    private fun keyOf(fixture: Fixture): String =
        assertNotNull(context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId).key)

    private fun runJob() {
        SelectablePdfTestFixtures.runNextSelectablePdfJob(context)
    }

    /** Runs the one queued job if the deletion left one behind; a job that finds nothing to do writes nothing. */
    private fun runLeftoverJobIfAny() {
        val job = context.jobs.claimNextQueued() ?: return
        assertEquals(JobType.SELECTABLE_PDF, job.type)
        val handler = SelectablePdfJobHandler(context.selectablePdfService)
        val stage = JobStage(job.id, context.jobs, context.collections, context.mutations)
        runBlocking { handler.handle(job, stage) }
        context.jobs.complete(job.id)
    }

    private fun selectableJobCount(): Int =
        context.jobs.list(limit = 1000).count { it.type == JobType.SELECTABLE_PDF }

    /** Every job and every file under the document's artifact directory, to prove a read changed neither. */
    private fun footprint(fixture: Fixture): Footprint {
        val jobs = context.jobs.list(limit = 1000)
            .filter { it.type == JobType.SELECTABLE_PDF }
            .map { "${it.id.value}|${it.state}|${it.updatedAt}" }
        val directory = context.paths.artifactsDir(fixture.collectionId, fixture.documentId)
        val files = if (Files.exists(directory)) {
            Files.walk(directory).use { stream ->
                stream.map { path ->
                    directory.relativize(path).toString() + "|" +
                        (if (Files.isRegularFile(path)) Files.size(path).toString() else "dir")
                }.sorted().toList()
            }
        } else {
            emptyList()
        }
        return Footprint(jobs, files)
    }

    private data class Footprint(val jobs: List<String>, val artifactFiles: List<String>)

    /** Reads the whole body, then proves no filesystem path, source text or provider text appeared in it. */
    private fun assertNoLeak(body: String, fixture: Fixture) {
        assertFalse(body.contains(dataDir.toString()), "no absolute data path in: $body")
        assertFalse(body.contains("/artifacts/"), "no artifact path in: $body")
        assertFalse(body.contains(SelectablePdfService.PDF_FILE_NAME), "no artifact file name in: $body")
        assertFalse(body.contains("%PDF"), "no PDF bytes in: $body")
    }

    /** Publishes a native-text one-page PDF whose text is [text] as the document's first revision. */
    private fun publishNative(text: String = "Hello selectable world"): Fixture =
        SelectablePdfTestFixtures.publishNativePdf(context, dataDir, text = text)

    /** Publishes a native PDF from a source file the test chose, through the same path as a real import. */
    private fun publishSource(source: Path, text: String): Fixture {
        WriterFixtures.writeSourcePdf(
            source,
            listOf(SourcePage(SelectablePdfTestFixtures.PAGE_GEOMETRY, SourceContent.NativeText(listOf(TextRun(text, 72.0, 700.0, 14.0))))),
        )
        val documentId = runBlocking { context.library.importFile(CollectionId("default"), source) }.document.id
        val revisionId = context.revisions.openCandidate(documentId, null, "TEST_IMPORT")
        context.revisions.appendPage(revisionId, nativePage(text))
        runBlocking { context.revisionPublication.publish(documentId, null, revisionId) }
        return Fixture(CollectionId("default"), documentId, revisionId)
    }

    /** A second published revision of the same document, the way a rescan or restore publishes one. */
    private fun publishNewerRevision(base: Fixture, text: String): Fixture {
        val revisionId = context.revisions.openCandidate(base.documentId, base.revisionId, "TEST_IMPORT")
        context.revisions.appendPage(revisionId, nativePage(text))
        runBlocking { context.revisionPublication.publish(base.documentId, base.revisionId, revisionId) }
        return Fixture(base.collectionId, base.documentId, revisionId)
    }

    private fun nativePage(text: String): RevisionPageDraft = RevisionPageDraft(
        ordinal = 0,
        unitId = ContentUnitId.new(),
        locator = SourceLocation.TextLines(1, 1),
        extractedText = text,
        searchText = text.lowercase(),
        extractionMethod = ExtractionMethod.DIRECT_TEXT,
        approval = PageApproval.APPROVED,
        chunks = listOf(
            RevisionChunkDraft(
                ordinal = 0, text = text, startOffset = 0, endOffset = text.length,
                tokenCount = 5, tokenStart = 0, tokenEnd = 4,
                embedding = SelectablePdfTestFixtures.EMBEDDER.embedDocuments(listOf(text)).single(),
            ),
        ),
    )

    private fun sha256Hex(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(file))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** A well-formed revision identifier that no revision of any document carries. */
        const val UNKNOWN_REVISION = "revision-00000000-0000-0000-0000-000000000000"

        /** A well-formed digest no artifact ever has. */
        const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
