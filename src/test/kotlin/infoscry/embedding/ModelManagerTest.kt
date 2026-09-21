package infoscry.embedding

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The installer, against a host this test controls.
 *
 * Nothing here reaches the model host: the pinned revision is 1.1 GB away, and the properties that matter —
 * a partial transfer never becomes a model, a wrong digest is deleted rather than trusted, and a verified
 * installation needs no network at all — are properties of the downloader, so a local server proves them
 * exactly as well and in a second.
 */
class ModelManagerTest {

    private val files = mapOf(
        "model.onnx" to "the weights".toByteArray(),
        "tokenizer.json" to "the tokenizer".toByteArray(),
    )

    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun stopServers() {
        servers.forEach { it.stop(0) }
    }

    /** A host that serves [bodies], counting how many times it was asked for something. */
    private fun host(
        bodies: Map<String, ByteArray> = mapOf("model.onnx" to files.getValue("model.onnx"), "tokenizer.json" to files.getValue("tokenizer.json")),
        truncate: Set<String> = emptySet(),
        corrupt: Set<String> = emptySet(),
        status: Int = 200,
    ): Pair<String, AtomicInteger> {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/onnx/") { exchange: HttpExchange ->
            requests.incrementAndGet()
            val name = exchange.requestURI.path.substringAfterLast('/')
            val body = bodies[name]
            when {
                status != 200 -> exchange.respondStatus(status)
                body == null -> exchange.respondStatus(404)
                name in truncate -> {
                    // Promises the whole file and sends nothing: what an interrupted transfer looks like.
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.close()
                }

                name in corrupt -> exchange.respond(ByteArray(body.size) { 'x'.code.toByte() })
                else -> exchange.respond(body)
            }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/onnx" to requests
    }

    private fun HttpExchange.respond(body: ByteArray) {
        sendResponseHeaders(200, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun HttpExchange.respondStatus(code: Int) {
        sendResponseHeaders(code, -1)
        responseBody.close()
    }

    @Test
    fun `installs every pinned file and leaves a verified installation`() {
        val (baseUrl, _) = host()
        val modelsDir = createTempDirectory("models")
        val manager = ModelManager(manifestForFiles(files, baseUrl))

        val installation = manager.ensureInstalled(modelsDir)

        assertEquals(modelsDir.resolve(manager.manifest.revision), installation.directory)
        files.forEach { (name, bytes) ->
            assertEquals(bytes.toList(), Files.readAllBytes(installation.path(name)).toList(), name)
        }
        assertEquals(emptyList(), manager.verifyInstalled(modelsDir))
    }

    @Test
    fun `a verified installation is reused without contacting the host`() {
        val (baseUrl, requests) = host()
        val modelsDir = createTempDirectory("models")
        ModelManager(manifestForFiles(files, baseUrl)).ensureInstalled(modelsDir)
        val afterFirstInstall = requests.get()
        servers.forEach { it.stop(0) }

        // A new manager, as a later process would have: no cached answer, and a host that is now gone.
        val installation = ModelManager(manifestForFiles(files, baseUrl)).ensureInstalled(modelsDir)

        assertEquals(afterFirstInstall, requests.get(), "the second install asked the host nothing")
        assertEquals(files.getValue("model.onnx").toList(), Files.readAllBytes(installation.path("model.onnx")).toList())
    }

    @Test
    fun `an installed file that no longer matches its digest is fetched again`() {
        val (baseUrl, _) = host()
        val modelsDir = createTempDirectory("models")
        val manager = ModelManager(manifestForFiles(files, baseUrl))
        val installation = manager.ensureInstalled(modelsDir)
        Files.write(installation.path("tokenizer.json"), ByteArray(files.getValue("tokenizer.json").size))

        val repaired = ModelManager(manifestForFiles(files, baseUrl)).ensureInstalled(modelsDir)

        assertEquals(files.getValue("tokenizer.json").toList(), Files.readAllBytes(repaired.path("tokenizer.json")).toList())
        assertEquals(emptyList(), ModelManager(manifestForFiles(files, baseUrl)).verifyInstalled(modelsDir))
    }

    @Test
    fun `a download whose digest is wrong fails and leaves nothing behind`() {
        val (baseUrl, _) = host(corrupt = setOf("tokenizer.json"))
        val modelsDir = createTempDirectory("models")

        val failure = assertFailsWith<ModelInstallException> {
            ModelManager(manifestForFiles(files, baseUrl)).ensureInstalled(modelsDir)
        }

        assertEquals(ModelManager.MODEL_CHECKSUM_MISMATCH_CODE, failure.code)
        assertTrue(failure.message!!.contains("tokenizer.json"), failure.message)
        assertTrue(failure.message!!.contains("pinned SHA-256"), failure.message)
        assertNoPartialDownload(modelsDir)
        assertTrue(
            !Files.exists(modelsDir.resolve(ModelManifest.load().revision).resolve("tokenizer.json")),
            "the file whose digest was wrong is not left in place",
        )
    }

    @Test
    fun `a truncated download fails and leaves nothing behind`() {
        val (baseUrl, _) = host(truncate = setOf("model.onnx"))
        val modelsDir = createTempDirectory("models")

        val failure = assertFailsWith<ModelInstallException> {
            ModelManager(manifestForFiles(files, baseUrl)).ensureInstalled(modelsDir)
        }

        assertEquals(ModelManager.MODEL_DOWNLOAD_FAILED_CODE, failure.code)
        assertNoPartialDownload(modelsDir)
        assertTrue(
            !Files.exists(modelsDir.resolve(ModelManifest.load().revision).resolve("model.onnx")),
            "a truncated transfer never becomes the model file",
        )
    }

    @Test
    fun `a host that answers with an error names the status and the file`() {
        val (baseUrl, _) = host(status = 503)
        val modelsDir = createTempDirectory("models")

        val failure = assertFailsWith<ModelInstallException> {
            ModelManager(manifestForFiles(files, baseUrl)).ensureInstalled(modelsDir)
        }

        assertEquals(ModelManager.MODEL_DOWNLOAD_FAILED_CODE, failure.code)
        assertTrue(failure.message!!.contains("HTTP 503"), failure.message)
        assertNoPartialDownload(modelsDir)
    }

    @Test
    fun `a host that cannot be reached says so rather than failing silently`() {
        val modelsDir = createTempDirectory("models")
        val manager = ModelManager(manifestForFiles(files, "http://127.0.0.1:1/onnx"))

        val failure = assertFailsWith<ModelInstallException> { manager.ensureInstalled(modelsDir) }

        assertEquals(ModelManager.MODEL_DOWNLOAD_FAILED_CODE, failure.code)
        assertTrue(failure.message!!.contains("Check the network"), failure.message)
        assertNoPartialDownload(modelsDir)
    }

    @Test
    fun `what is missing is reported without downloading anything`() {
        val (baseUrl, requests) = host()
        val modelsDir = createTempDirectory("models")
        val manager = ModelManager(manifestForFiles(files, baseUrl))

        val problems = manager.verifyInstalled(modelsDir)

        assertEquals(2, problems.size)
        assertTrue(problems.all { it.contains("is missing") }, problems.toString())
        assertEquals(0, requests.get(), "reporting never downloads")
    }

    @Test
    fun `a partially installed model names the files that are wrong`() {
        val (baseUrl, _) = host()
        val modelsDir = createTempDirectory("models")
        val manager = ModelManager(manifestForFiles(files, baseUrl))
        val installation = manager.ensureInstalled(modelsDir)
        Files.write(installation.path("model.onnx"), ByteArray(3))

        val problems = ModelManager(manifestForFiles(files, baseUrl)).verifyInstalled(modelsDir)

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("model.onnx"), problems.single())
        assertTrue(problems.single().contains("bytes, expected"), problems.single())
    }

    @Test
    fun `the remedy names the command that installs the model`() {
        val remedy = ModelManager.installRemedy()

        assertTrue(remedy.contains("./gradlew embeddingModel"), remedy)
        assertTrue(remedy.contains("1.1 GB"), "the size is part of the expectation: $remedy")
    }

    private fun assertNoPartialDownload(modelsDir: Path) {
        val partial = Files.walk(modelsDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith("$PARTIAL_SUFFIX") }.toList()
        }
        assertEquals(emptyList(), partial, "a failed install leaves no partial download behind")
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
    }

    /** The committed manifest is what production reads, so its contents are part of the task's contract. */
    @Test
    fun `the pinned manifest names the validated model, runtime and provider`() {
        val manifest = ModelManifest.load()

        assertEquals("intfloat/multilingual-e5-base", manifest.model)
        assertEquals("d128750597153bb5987e10b1c3493a34e5a4502a", manifest.revision)
        assertEquals(768, manifest.dimension)
        assertEquals(512, manifest.maxSequenceTokens)
        assertEquals("attention-mask-mean", manifest.pooling)
        assertEquals("passage: ", manifest.passagePrefix)
        assertEquals("query: ", manifest.queryPrefix)
        assertEquals("com.microsoft.onnxruntime:onnxruntime", manifest.nativeRuntime.artifact)
        assertEquals("CoreML", manifest.executionProvider.name)
        assertEquals("CPUAndGPU", manifest.executionProvider.options["MLComputeUnits"])
        assertEquals("MLProgram", manifest.executionProvider.options["ModelFormat"])
        assertEquals(
            "https://huggingface.co/intfloat/multilingual-e5-base/resolve/" +
                "d128750597153bb5987e10b1c3493a34e5a4502a/onnx",
            manifest.baseUrl,
        )
    }

    @Test
    fun `every pinned file carries a full checksum and says where it came from`() {
        val manifest = ModelManifest.load()

        assertEquals(6, manifest.files.size, "the manifest pins the export, the tokenizer and its companions")
        manifest.files.forEach { file ->
            assertEquals(64, file.sha256.length, "${file.name} needs a full SHA-256")
            assertTrue(file.bytes > 0, "${file.name} needs a size")
            assertTrue(
                file.sha256Source in setOf("lfs-oid", "pinned-revision-bytes"),
                "${file.name} must record where its expected checksum came from, was '${file.sha256Source}'",
            )
        }
        assertTrue(manifest.files.any { it.name == "model.onnx" && it.bytes > 1_000_000_000 })
        assertTrue(manifest.files.any { it.name == "tokenizer.json" })
    }

    @Test
    fun `nothing in the manifest points at a cuda export`() {
        val manifest = ModelManifest.load()

        assertTrue(
            manifest.files.none { it.name.contains("O4") || it.name.contains("cuda", ignoreCase = true) },
            "v1 has one platform and one export: ${manifest.files.map { it.name }}",
        )
        assertTrue(
            manifest.nativeRuntime.artifact == "com.microsoft.onnxruntime:onnxruntime",
            "the CUDA runtime artifact must not be declared: ${manifest.nativeRuntime.artifact}",
        )
    }
}
