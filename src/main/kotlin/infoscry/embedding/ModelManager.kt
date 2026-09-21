package infoscry.embedding

import infoscry.config.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * One file the embedding model is made of, with the checksum it must have.
 *
 * [sha256Source] records where the expected checksum came from, because the value's trustworthiness is the
 * whole point of pinning it: `lfs-oid` is the digest the model host itself reported for the pinned
 * revision's large file, and `pinned-revision-bytes` is the digest of the bytes that revision serves.
 * Neither is a digest of whatever a download happened to contain.
 */
@Serializable
data class ModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val sha256Source: String,
) {
    init {
        require(name.isNotBlank()) { "a model file needs a name" }
        require(bytes > 0) { "ModelFile.bytes must be positive for '$name', was $bytes" }
        require(sha256.length == SHA256_HEX_LENGTH) { "'$name' needs a full SHA-256, was '${sha256}'" }
    }

    internal companion object {
        const val SHA256_HEX_LENGTH: Int = 64
    }
}

/** Which native runtime the model files were validated against. */
@Serializable
data class NativeRuntime(val artifact: String, val version: String)

/** The execution provider the model must run on, with the options it was validated with. */
@Serializable
data class ExecutionProvider(val name: String, val options: Map<String, String>)

/**
 * The pinned embedding model: what it is, where it comes from, which files it needs, and how it runs.
 *
 * The manifest is the single place the model's identity lives. The checksums pin the weights, the tokenizer
 * and the pooling convention travel together because a vector built from a different tokenizer is a
 * different vector, and [prefixVersion] names the query/passage prefixes for the same reason.
 */
@Serializable
data class ModelManifest(
    val model: String,
    val revision: String,
    val baseUrl: String,
    val dimension: Int,
    val maxSequenceTokens: Int,
    val pooling: String,
    val prefixVersion: String,
    val passagePrefix: String,
    val queryPrefix: String,
    val nativeRuntime: NativeRuntime,
    val executionProvider: ExecutionProvider,
    val files: List<ModelFile>,
) {
    init {
        require(model.isNotBlank()) { "a manifest names its model" }
        require(revision.length == REVISION_LENGTH) {
            "a manifest pins a full revision, but '${revision}' is not a ${REVISION_LENGTH}-character commit"
        }
        require(dimension > 0) { "ModelManifest.dimension must be positive, was $dimension" }
        require(maxSequenceTokens > 0) {
            "ModelManifest.maxSequenceTokens must be positive, was $maxSequenceTokens"
        }
        require(files.isNotEmpty()) { "a manifest lists the files the model is made of" }
        require(files.map { it.name }.toSet().size == files.size) { "a manifest lists each file once" }
    }

    fun file(name: String): ModelFile =
        files.firstOrNull { it.name == name }
            ?: error("the manifest for '$model' does not list '$name'")

    val onnxFile: ModelFile get() = file(ONNX_FILE)
    val tokenizerFile: ModelFile get() = file(TOKENIZER_FILE)

    /**
     * What produced a vector: the export, the tokenizer, and the convention that turned text into a passage.
     *
     * Two installations that differ anywhere in here produce vectors that cannot be compared, so the value
     * is what an index records to know whether its vectors are still the ones this model would build.
     */
    fun fingerprint(): String {
        val canonical = buildList {
            add("model=$model")
            add("revision=$revision")
            add("dimension=$dimension")
            add("pooling=$pooling")
            add("prefix_version=$prefixVersion")
            add("provider=${executionProvider.name}")
            files.sortedBy { it.name }.forEach { add("file=${it.name}=${it.sha256}") }
        }.joinToString("\n")
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)),
        )
    }

    companion object {
        /** Where the manifest is read from: the repository's `models/` directory, copied onto the classpath. */
        const val RESOURCE_PATH: String = "/models/embedding-model.json"

        const val ONNX_FILE: String = "model.onnx"
        const val TOKENIZER_FILE: String = "tokenizer.json"

        private const val REVISION_LENGTH: Int = 40

        fun load(resource: String = RESOURCE_PATH): ModelManifest {
            val stream = ModelManifest::class.java.getResourceAsStream(resource)
                ?: error("the model manifest $resource is not on the classpath")
            return stream.use { parse(it.readBytes().decodeToString()) }
        }

        fun parse(json: String): ModelManifest = MANIFEST_JSON.decodeFromString(json)

        private val MANIFEST_JSON = Json { ignoreUnknownKeys = false }
    }
}

/** A verified model installation: the files are on disk and match the manifest. */
data class ModelInstallation(
    val manifest: ModelManifest,
    val directory: Path,
) {
    val fingerprint: String get() = manifest.fingerprint()

    fun path(name: String): Path = directory.resolve(name)

    val onnxPath: Path get() = path(ModelManifest.ONNX_FILE)
    val tokenizerPath: Path get() = path(ModelManifest.TOKENIZER_FILE)
}

/**
 * The install failed in a way the person has to fix.
 *
 * [code] is the durable reason (a log line, a job item, a doctor row) and [message] is the remedy in words.
 */
class ModelInstallException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Installs and verifies the pinned embedding model.
 *
 * The rules the download obeys are the ones an interrupted 1.1 GB transfer makes unavoidable: bytes land in
 * a temporary sibling and only become the model file after their length *and* digest match the manifest, so
 * a killed download can never be mistaken for a model. A file that no longer matches its digest is deleted
 * and fetched again rather than trusted, because a corrupted weight file would otherwise produce vectors
 * that are silently wrong rather than absent.
 */
class ModelManager(
    val manifest: ModelManifest,
    private val http: HttpClient = defaultHttpClient(),
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {

    /** A verified installation, remembered so a second call in one process does not re-hash 1.1 GB. */
    private var verified: ModelInstallation? = null

    /**
     * Returns the installed model, downloading whatever is missing.
     *
     * A complete verified installation is reused without contacting the host at all, which is what makes the
     * archive usable offline once it has been installed.
     */
    fun ensureInstalled(modelsDir: Path): ModelInstallation {
        verified?.let { return it }
        val directory = modelsDir.resolve(manifest.revision)
        Files.createDirectories(directory)
        manifest.files.forEach { file -> ensureFile(file, directory) }
        val installation = ModelInstallation(manifest, directory)
        verified = installation
        return installation
    }

    /**
     * Whether the model's files are present with the sizes the manifest pins.
     *
     * This is the cheap question a caller asks when it has to choose a code path — reading the tokenizer or
     * not — and it is deliberately not [verifyInstalled]: hashing 1.1 GB on every startup would put seconds
     * between the process starting and the first document. Digest verification belongs to installation and to
     * diagnostics, which run when someone is waiting for an answer about the model.
     */
    fun isInstalled(modelsDir: Path): Boolean {
        val directory = modelsDir.resolve(manifest.revision)
        return manifest.files.all { file ->
            val target = directory.resolve(file.name)
            Files.isRegularFile(target) && Files.size(target) == file.bytes
        }
    }

    /**
     * Reports every way the installed files fail to match the manifest, without downloading anything.
     *
     * Diagnostics need to answer "is this installation usable" rather than "please fetch it again", so this
     * hashes what is there and returns sentences instead of throwing.
     */
    fun verifyInstalled(modelsDir: Path): List<String> {
        val directory = modelsDir.resolve(manifest.revision)
        return manifest.files.mapNotNull { file ->
            val target = directory.resolve(file.name)
            when {
                !Files.isRegularFile(target) -> "${file.name} is missing from $directory"
                Files.size(target) != file.bytes ->
                    "${file.name} is ${Files.size(target)} bytes, expected ${file.bytes}"
                sha256Of(target) != file.sha256 -> "${file.name} does not match its pinned SHA-256"
                else -> null
            }
        }
    }

    private fun ensureFile(file: ModelFile, directory: Path) {
        val target = directory.resolve(file.name)
        if (matches(target, file)) return
        if (Files.exists(target)) {
            // Present but wrong: a leftover from an interrupted or corrupted install is deleted rather than
            // trusted, so the checksum it fails is a failed download and not a permanent install failure.
            Files.delete(target)
        }
        download(file, target)
    }

    private fun matches(target: Path, file: ModelFile): Boolean =
        Files.isRegularFile(target) && Files.size(target) == file.bytes && sha256Of(target) == file.sha256

    private fun download(file: ModelFile, target: Path) {
        val temporary = target.resolveSibling("${target.fileName}.part")
        try {
            Files.deleteIfExists(temporary)
            val request = HttpRequest.newBuilder(URI.create("${manifest.baseUrl}/${file.name}"))
                .timeout(timeout)
                .GET()
                .build()
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (failure: IOException) {
                throw ModelInstallException(
                    code = MODEL_DOWNLOAD_FAILED_CODE,
                    message = "could not download ${file.name} from ${manifest.baseUrl}: " +
                        "${failure.message}. Check the network and run the install again.",
                    cause = failure,
                )
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ModelInstallException(
                    code = MODEL_DOWNLOAD_FAILED_CODE,
                    message = "the download of ${file.name} was interrupted",
                    cause = failure,
                )
            }
            if (response.statusCode() != HTTP_OK) {
                response.body().close()
                throw ModelInstallException(
                    code = MODEL_DOWNLOAD_FAILED_CODE,
                    message = "${manifest.baseUrl}/${file.name} answered HTTP ${response.statusCode()}; " +
                        "the pinned revision may no longer be published",
                )
            }
            response.body().use { body -> copyAndHash(body, temporary) }
            verifyDownloaded(temporary, file)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            Files.deleteIfExists(temporary)
            throw failure
        }
    }

    private fun verifyDownloaded(temporary: Path, file: ModelFile) {
        val bytes = Files.size(temporary)
        if (bytes != file.bytes) {
            throw ModelInstallException(
                code = MODEL_DOWNLOAD_FAILED_CODE,
                message = "${file.name} arrived truncated: $bytes bytes, expected ${file.bytes}",
            )
        }
        val digest = sha256Of(temporary)
        if (digest != file.sha256) {
            throw ModelInstallException(
                code = MODEL_CHECKSUM_MISMATCH_CODE,
                message = "${file.name} does not match its pinned SHA-256 " +
                    "(${file.sha256Source} ${file.sha256.take(12)}…, got ${digest.take(12)}…)",
            )
        }
    }

    private fun copyAndHash(body: InputStream, target: Path) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = body.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
    }

    companion object {
        /** The model files are not installed yet. */
        const val MODEL_NOT_INSTALLED_CODE: String = "MODEL_NOT_INSTALLED"

        /** What arrived does not match the pinned digest. */
        const val MODEL_CHECKSUM_MISMATCH_CODE: String = "MODEL_CHECKSUM_MISMATCH"

        /** The host could not be reached, or answered with something other than the file. */
        const val MODEL_DOWNLOAD_FAILED_CODE: String = "MODEL_DOWNLOAD_FAILED"

        /** What a person has to do about any of the codes above. */
        fun installRemedy(): String =
            "install the pinned embedding model with './gradlew embeddingModel' " +
                "(it downloads about 1.1 GB into the data directory)"

        private const val HTTP_OK: Int = 200
        private const val COPY_BUFFER_BYTES: Int = 1 shl 20

        /** A model file is large, so a slow host must not be mistaken for a dead one. */
        private val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(30)

        internal fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        /** The SHA-256 of a file, read in bounded chunks so a 1.1 GB weight file never lands in the heap. */
        internal fun sha256Of(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        /** The manager the application uses, reading the committed manifest. */
        fun production(manifest: ModelManifest = ModelManifest.load()): ModelManager = ModelManager(manifest)

        /** Where the application looks for an installation. */
        fun installDirectory(paths: AppPaths): Path = paths.modelsDir
    }
}
