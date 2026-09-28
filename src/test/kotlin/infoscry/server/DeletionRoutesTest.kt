package infoscry.server

import infoscry.AppContext
import infoscry.collection.CollectionIndexRemover
import infoscry.collection.CollectionService
import infoscry.domain.CollectionId
import infoscry.storage.DeletionPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The collection deletion API over a real socket: what admission answers with, and what the read-only
 * operation status says afterwards.
 *
 * The point of these routes is that a deletion is not a request: it survives the caller that asked for
 * it, it can be followed to its terminal phase, and it can be read again after the collection's own row
 * is gone. So every test here either admits and follows, or admits, disconnects, and follows.
 */
class DeletionRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-deletions")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `the operation read describes the deletion after the collection row is gone`() = runBlocking {
        val id = createCollection("Nightfall")
        val source = dataDir.resolve("nightfall.txt")
        Files.writeString(source, "A report.")
        harness.request(
            HttpMethod.Post,
            "/api/imports",
            body = """{"collection":"$id","paths":["$source"]}""",
            credential = Credential.BEARER,
        )

        val admitted = harness.admitCollectionDeletion(id, "Nightfall")
        val finished = harness.awaitDeletion(admitted.operationId)

        assertEquals("COLLECTION", finished.kind)
        assertEquals(id, finished.collectionId)
        assertEquals("Nightfall", finished.collectionName)
        assertEquals(emptyList(), finished.documentIds)
        assertEquals("DONE", finished.phase)
        assertTrue(finished.terminal)
        assertNull(finished.errorCode)
        // The collection's own row is what the read must not need: it is gone by now.
        assertFalse(harness.get("/api/collections").bodyAsText().contains("Nightfall"))
    }

    @Test
    fun `the operation read hands out no managed or trash path`() = runBlocking {
        val id = createCollection("Nightfall")
        val admitted = harness.admitCollectionDeletion(id, "Nightfall")
        harness.awaitDeletion(admitted.operationId)

        val body = harness.get("/api/deletions/${admitted.operationId}").bodyAsText()

        assertFalse(body.contains(dataDir.toString()), "the data directory is not the client's business: $body")
        assertFalse(body.contains("trash"), "the parked directory stays inside the process: $body")
        assertFalse(body.contains("lastError") || body.contains("last_error"), "the stored message may hold paths: $body")
    }

    @Test
    fun `an unfinished deletion is listed and a finished one is not, while both stay readable`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = CollectionIndexRemover {
            entered.complete(Unit)
            release.await()
        }
        Files.createTempDirectory("infoscry-deletions-gated").let { gatedDir ->
            try {
                ApiTestServer(gatedDir, gated).use { server ->
                    val id = createCollection(server, "Default")

                    val admitted = server.admitCollectionDeletion(id, "Default")
                    withTimeout(TIMEOUT_MILLIS) { entered.await() }

                    val listed = ApiJson.decodeFromString<DeletionsResponse>(
                        server.get("/api/deletions").bodyAsText(),
                    ).deletions
                    assertEquals(listOf(admitted.operationId), listed.map { it.operationId })
                    assertEquals("DB_DELETED", listed.single().phase)
                    assertFalse(listed.single().terminal, "a deletion in its phases is not done")

                    release.complete(Unit)
                    server.awaitDeletion(admitted.operationId)

                    assertTrue(
                        ApiJson.decodeFromString<DeletionsResponse>(server.get("/api/deletions").bodyAsText())
                            .deletions.isEmpty(),
                        "a reopened Admin restores what is unfinished, and this one is not",
                    )
                    assertTrue(server.readDeletion(admitted.operationId).terminal)
                }
            } finally {
                gatedDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `repeated admission answers with the same unfinished operation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = CollectionIndexRemover {
            entered.complete(Unit)
            release.await()
        }
        Files.createTempDirectory("infoscry-deletions-repeat").let { gatedDir ->
            try {
                ApiTestServer(gatedDir, gated).use { server ->
                    val id = createCollection(server, "Default")

                    val first = server.admitCollectionDeletion(id, "Default")
                    withTimeout(TIMEOUT_MILLIS) { entered.await() }
                    val second = server.admitCollectionDeletion(id, "Default")

                    assertEquals(first.operationId, second.operationId, "a repeated confirmation is not a second deletion")
                    assertEquals(1, server.context.deletions.listUnfinished().size, "one operation, not two")

                    release.complete(Unit)
                    assertEquals("DONE", server.awaitDeletion(first.operationId).phase)
                }
            } finally {
                gatedDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a deletion admitted before its caller disconnects still finishes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = CollectionIndexRemover {
            entered.complete(Unit)
            release.await()
        }
        Files.createTempDirectory("infoscry-deletions-disconnect").let { gatedDir ->
            try {
                ApiTestServer(gatedDir, gated).use { server ->
                    val id = createCollection(server, "Default")
                    // A client of its own: it goes away as soon as it has the answer, which is the
                    // disconnection this test is about.
                    val admitted = HttpClient(CIO).use { ephemeral ->
                        val response = ephemeral.request(server.url + "/api/collections/$id") {
                            method = HttpMethod.Delete
                            header(HttpHeaders.Authorization, "Bearer ${server.bearer}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"confirmName":"Default"}""")
                        }
                        ApiJson.decodeFromString<DeleteCollectionResponse>(response.bodyAsText())
                    }

                    withTimeout(TIMEOUT_MILLIS) { entered.await() }
                    assertNull(server.context.collections.get(CollectionId(id)), "the admission is durable")

                    release.complete(Unit)
                    assertEquals("DONE", server.awaitDeletion(admitted.operationId).phase)
                }
            } finally {
                gatedDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a refused deletion admits nothing`() = runBlocking {
        val id = createCollection("Nightfall")
        val source = dataDir.resolve("nightfall.txt")
        Files.writeString(source, "A report.")
        // Imported through the library rather than the queue: this test has no worker, and it needs the
        // managed directory to exist so "untouched" is something it can see.
        val imported = harness.context.library.importFile(CollectionId(id), source)

        val uncredentialed = harness.request(
            HttpMethod.Delete,
            "/api/collections/$id",
            body = """{"confirmName":"Nightfall"}""",
            credential = Credential.NONE,
        )
        val mismatched = harness.request(
            HttpMethod.Delete,
            "/api/collections/$id",
            body = """{"confirmName":"Another collection"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.Unauthorized, uncredentialed.status)
        assertEquals(HttpStatusCode.BadRequest, mismatched.status)
        assertContains(mismatched.bodyAsText(), "CONFIRMATION_MISMATCH")
        assertContains(harness.get("/api/collections").bodyAsText(), "Nightfall")
        assertTrue(
            ApiJson.decodeFromString<DeletionsResponse>(harness.get("/api/deletions").bodyAsText()).deletions.isEmpty(),
            "nothing may be admitted by a refused request",
        )
        assertTrue(Files.exists(imported.managedPath), "the managed copy is untouched")
        assertTrue(
            Files.list(harness.context.paths.libraryDir).use { entries ->
                entries.noneMatch { it.fileName.toString().startsWith(CollectionService.TRASH_PREFIX) }
            },
            "a refused deletion parks nothing",
        )
    }

    @Test
    fun `a phase that cannot be completed is reported with its code rather than as success`() = runBlocking {
        val failing = CollectionIndexRemover { throw IOException("the index is unavailable") }
        Files.createTempDirectory("infoscry-deletions-failing").let { failingDir ->
            try {
                ApiTestServer(failingDir, failing).use { server ->
                    val id = createCollection(server, "Default")

                    val admitted = server.admitCollectionDeletion(id, "Default")

                    val operation = withTimeout(TIMEOUT_MILLIS) {
                        while (true) {
                            val read = server.readDeletion(admitted.operationId)
                            if (read.errorCode != null) return@withTimeout read
                            kotlinx.coroutines.delay(25)
                        }
                        error("unreachable")
                    }

                    assertEquals(CollectionService.DELETION_FAILED_CODE, operation.errorCode)
                    assertEquals("DB_DELETED", operation.phase)
                    assertFalse(operation.terminal, "a deletion that stopped is not a deletion that finished")
                    assertFalse(operation.operationId.isBlank())
                }
            } finally {
                failingDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `an unsafe state is reported as blocked and refuses other mutations`() = runBlocking {
        val blockedDir = Files.createTempDirectory("infoscry-deletions-unsafe")
        var operationId: String? = null
        try {
            // The state an operator has to resolve: the record says the files are still in place, and a
            // parked directory for the same deletion exists too. Recovery must refuse to pick either.
            AppContext.open(blockedDir).use { context ->
                val collection = context.collectionService.create("Nightfall")
                // A healthy collection beside the blocked one, so the import refusal below cannot be the
                // tombstoned collection's own not-found answer.
                context.collectionService.create("Elsewhere")
                val source = blockedDir.resolve("nightfall.txt")
                Files.writeString(source, "A report.")
                context.library.importFile(collection.id, source)
                val operation = context.collectionService.beginDeletion(collection.id, "Nightfall")
                operationId = operation.id
                Files.createDirectories(context.paths.trashDirectory(operation.trashBasename))
            }

            val admitted = operationId ?: error("the staging operation was not recorded")
            ApiTestServer(blockedDir).use { server ->
                val read = server.readDeletion(admitted)

                assertEquals(CollectionService.UNSAFE_RECOVERY_CODE, read.errorCode)
                assertEquals("PREPARED", read.phase)
                assertFalse(read.terminal, "an unresolved deletion must not read as finished")

                val refused = server.createCollection("While blocked", Credential.BEARER)

                assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
                assertContains(refused.bodyAsText(), "DELETION_RECOVERY_BLOCKED")
                // The refusal is a sentence for a client: the recorded message, which names the managed and
                // parked directories, stays in the log and in the durable error.
                val refusalBody = refused.bodyAsText()
                assertFalse(
                    refusalBody.contains(blockedDir.toString()),
                    "the data directory is not the client's business: $refusalBody",
                )
                assertFalse(
                    refusalBody.contains(CollectionService.TRASH_PREFIX),
                    "no parked directory may cross the wire: $refusalBody",
                )
                assertFalse(
                    refusalBody.contains("managed directory"),
                    "the recorded message is not echoed to a client: $refusalBody",
                )
                assertContains(refusalBody, CollectionService.UNSAFE_RECOVERY_CODE)
                assertEquals(listOf(admitted), server.context.deletions.listUnfinished().map { it.id })
                assertEquals(
                    DeletionPhase.PREPARED,
                    server.context.deletions.get(admitted)?.phase,
                    "the files must not be discarded to make the deletion look finished",
                )

                // Admitting an import is a mutation like any other: it must not add work to an archive an
                // operator has to repair first.
                val importRefused = server.request(
                    HttpMethod.Post,
                    "/api/imports",
                    body = """{"collection":"Elsewhere","paths":["$blockedDir/nightfall.txt"]}""",
                    credential = Credential.BEARER,
                )

                assertEquals(HttpStatusCode.Conflict, importRefused.status, importRefused.bodyAsText())
                assertContains(importRefused.bodyAsText(), "DELETION_RECOVERY_BLOCKED")
                assertTrue(
                    server.context.jobs.list(limit = 100).isEmpty(),
                    "a refused import may not queue a job",
                )
            }
        } finally {
            blockedDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an unknown deletion operation is not found`() = runBlocking {
        val response = harness.get("/api/deletions/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "NOT_FOUND")
    }

    private suspend fun createCollection(name: String): String {
        harness.createCollection(name, Credential.BEARER)
        return harness.collectionIdOf(name)
    }

    /** Creates [name] explicitly on [server]: a new archive no longer holds an automatic Default. */
    private suspend fun createCollection(server: ApiTestServer, name: String): String {
        server.createCollection(name, Credential.BEARER)
        return server.collectionIdOf(name)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
