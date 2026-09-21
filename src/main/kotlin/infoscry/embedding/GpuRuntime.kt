package infoscry.embedding

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Whether the embedding model can run, and on what.
 *
 * The archive stays usable without this being ready — extraction, keyword search and source viewing do not
 * need a GPU — but anything that has to build a vector does, so the answer carries both the reason and the
 * remedy instead of a bare boolean.
 */
data class GpuReadiness(
    val ready: Boolean,
    val provider: String,
    val device: String,
    val runtimeVersion: String,
    val modelFingerprint: String,
    val reasons: List<String> = emptyList(),
) {
    fun describe(): String = if (ready) {
        "$provider on $device (onnxruntime $runtimeVersion)"
    } else {
        "embeddings are unavailable: ${reasons.joinToString("; ")}"
    }
}

/** The embedding model could not be given its required accelerator. */
class GpuUnavailableException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * What one profiling run says about where the model actually executed.
 *
 * The counts are kernel events per provider and the durations are ORT's own kernel times. They exist because
 * registering a provider proves nothing: ONNX Runtime happily accepts CoreML and then runs the graph on the
 * CPU when the provider cannot take any node, and the only way to tell the two apart from outside is to look
 * at what ran.
 */
data class GpuProfile(
    val coreMlKernelEvents: Int,
    val coreMlKernelMicros: Double,
    val cpuKernelEvents: Int,
    val cpuKernelMicros: Double,
    /**
     * The largest activation buffer CoreML reported for one kernel, in bytes.
     *
     * It is the only memory figure the provider exposes through the Java API: the CoreML execution provider
     * does not report device-memory use, so recording the largest activation is an observation about the
     * graph's working set rather than a claim about the GPU's memory.
     */
    val largestActivationBytes: Long = 0,
) {
    val totalKernelEvents: Int get() = coreMlKernelEvents + cpuKernelEvents
    val totalKernelMicros: Double get() = coreMlKernelMicros + cpuKernelMicros

    /** How much of the measured kernel time CoreML accounted for, from 0 to 1. */
    val coreMlShare: Double
        get() = if (totalKernelMicros <= 0.0) 0.0 else coreMlKernelMicros / totalKernelMicros

    fun describe(): String = String.format(
        Locale.ROOT,
        "CoreML executed %d kernels (%.1f ms, %.1f%% of kernel time); the CPU ran %d kernels (%.2f ms)",
        coreMlKernelEvents,
        coreMlKernelMicros / 1000.0,
        coreMlShare * 100.0,
        cpuKernelEvents,
        cpuKernelMicros / 1000.0,
    )
}

/**
 * Reads the execution-provider evidence out of an ONNX Runtime profile.
 *
 * ORT writes a Chrome-trace array whose kernel events name the provider that ran them and how long each
 * took, which makes the profile the only trustworthy answer to "did this run on the GPU". The parse is
 * deliberately tolerant: events without a provider or without a duration are not evidence of anything and
 * are skipped rather than defaulted, so an unreadable profile reads as "no CoreML kernels" and is refused.
 */
internal object OnnxProfile {

    private const val KERNEL_EVENT_SUFFIX = "_kernel_time"
    private const val CORE_ML_PROVIDER = "CoreMLExecutionProvider"
    private const val CPU_PROVIDER = "CPUExecutionProvider"

    fun summarise(json: String): GpuProfile {
        val events: JsonArray = runCatching { Json.parseToJsonElement(json) as JsonArray }
            .getOrElse { error("the ONNX Runtime profile is not a trace array: ${it.message}") }
        var coreMlEvents = 0
        var coreMlMicros = 0.0
        var cpuEvents = 0
        var cpuMicros = 0.0
        var largestActivation = 0L
        events.forEach { element ->
            val event = element.jsonObject
            val name = event["name"]?.jsonPrimitive?.content ?: return@forEach
            if (!name.endsWith(KERNEL_EVENT_SUFFIX)) return@forEach
            val provider = event["args"]?.jsonObject?.get("provider")?.jsonPrimitive?.content ?: return@forEach
            // ORT reports durations in microseconds; a missing duration is not a measurement.
            val micros = (event["dur"]?.jsonPrimitive?.longOrNull ?: return@forEach).toDouble()
            when (provider) {
                CORE_ML_PROVIDER -> {
                    coreMlEvents++
                    coreMlMicros += micros
                    val activation = event["args"]?.jsonObject?.get("activation_size")
                        ?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    if (activation > largestActivation) largestActivation = activation
                }

                CPU_PROVIDER -> {
                    cpuEvents++
                    cpuMicros += micros
                }
            }
        }
        return GpuProfile(coreMlEvents, coreMlMicros, cpuEvents, cpuMicros, largestActivation)
    }
}

/** A session whose accelerator use has been proven, with the evidence that proved it. */
class VerifiedSession(
    val session: OrtSession,
    val profile: GpuProfile,
    val readiness: GpuReadiness,
) : AutoCloseable {
    override fun close() = session.close()
}

/**
 * The one place that decides whether the embedding model may run, and on what.
 *
 * v1 has a single validated target: macOS arm64, through ONNX Runtime's CoreML execution provider with
 * GPU-enabled compute units. There is deliberately no second path — no CUDA, no CPU fallback — because an
 * unvalidated provider that quietly takes over is exactly the failure the requirement exists to prevent.
 *
 * A registered provider is not proof of use, so [createSession] profiles its own warm-up and refuses a
 * session whose graph ran entirely on the CPU. The distinction between "refused" and "slow" matters: a
 * silent CPU fallback would make every ingest slower and every vector identical in kind to a GPU one, so
 * nothing downstream could notice.
 */
class GpuRuntime(
    private val manifest: ModelManifest,
    private val providers: () -> Set<String> = { availableProviders() },
    private val platform: Platform = Platform.current(),
    private val runtimeVersion: String = OrtEnvironment.getEnvironment().version,
) {

    /** Whether an accelerated session can be created, without loading the model. */
    fun probe(): GpuReadiness {
        val offered = providers()
        val required = manifest.executionProvider.name
        val reasons = buildList {
            if (!platform.isSupported()) {
                add(
                    "this machine is ${platform.describe()}; v1 validates macOS arm64 with an Apple GPU only",
                )
            }
            if (offered.none { sameProvider(it, required) }) {
                // The manifest spells the provider the way its documentation does and the runtime reports the
                // enum name, so the comparison is on a normalised form and the message shows both.
                add(
                    "ONNX Runtime ${manifest.nativeRuntime.version} offers ${offered.sorted()}, so the " +
                        "$required provider the model needs is missing",
                )
            }
        }
        return GpuReadiness(
            ready = reasons.isEmpty(),
            provider = manifest.executionProvider.name,
            device = platform.device(),
            runtimeVersion = runtimeVersion,
            modelFingerprint = manifest.fingerprint(),
            reasons = reasons,
        )
    }

    /**
     * Creates a session verified to execute on the required provider.
     *
     * [warmUp] runs one inference with synthetic public text: it is what forces the provider to compile and
     * execute the graph, so the profile can show whether it did. The profile is written into
     * [profileDirectory] and left there for the caller's evidence; the session comes back with profiling
     * stopped, so steady-state inference does not pay for it.
     */
    fun createSession(
        modelPath: Path,
        profileDirectory: Path,
        warmUp: (OrtSession) -> Unit,
    ): VerifiedSession {
        val readiness = probe()
        if (!readiness.ready) {
            throw GpuUnavailableException(
                code = GPU_UNAVAILABLE_CODE,
                message = "${readiness.describe()}. ${remedy()}",
            )
        }

        Files.createDirectories(profileDirectory)
        val profilePrefix = profileDirectory.resolve(PROFILE_PREFIX)
        val options = OrtSession.SessionOptions().apply {
            enableProfiling(profilePrefix.toString())
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addCoreML(manifest.executionProvider.options)
        }
        val environment = OrtEnvironment.getEnvironment()
        val session = try {
            environment.createSession(modelPath.toString(), options)
        } catch (failure: Throwable) {
            options.close()
            throw GpuUnavailableException(
                code = GPU_UNAVAILABLE_CODE,
                message = "the ${readiness.provider} session could not be created for " +
                    "${modelPath.fileName}: ${failure.message}. ${remedy()}",
                cause = failure,
            )
        }

        val profileFile = try {
            warmUp(session)
            val path = session.endProfiling()
            profileFileOf(path)
        } catch (failure: Throwable) {
            session.close()
            options.close()
            throw failure
        }

        val profile = try {
            OnnxProfile.summarise(Files.readString(profileFile))
        } catch (failure: Throwable) {
            session.close()
            options.close()
            // An unreadable profile is a refusal with a remedy, not a crash: the KDoc on OnnxProfile says an
            // unreadable profile is refused, and the operator needs the same remedy either way.
            throw GpuUnavailableException(
                code = GPU_UNAVAILABLE_CODE,
                message = "the model ran, but its profile could not be read: ${failure.message}. ${remedy()}",
                cause = failure,
            )
        }
        options.close()
        return VerifiedSession(
            session = session,
            profile = requireCoreMlExecution(profile, readiness, modelPath.fileName.toString()),
            readiness = readiness,
        )
    }

    /** The profile file ORT actually wrote: the prefix it was given, with a timestamp appended. */
    private fun profileFileOf(endedPath: String): Path {
        val path = Path.of(endedPath)
        if (Files.isRegularFile(path)) return path
        val directory = path.parent ?: Path.of(".")
        val prefix = path.fileName.toString()
        return Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().startsWith(prefix) }.findFirst().orElseThrow {
                GpuUnavailableException(
                    code = GPU_UNAVAILABLE_CODE,
                    message = "ONNX Runtime reported the profile at $path but no profile file was written, " +
                        "so ${manifest.executionProvider.name} execution could not be verified",
                )
            }
        }
    }

    companion object {
        /** The graph did not run on the required accelerator. */
        const val GPU_UNAVAILABLE_CODE: String = "GPU_UNAVAILABLE"

        private const val PROFILE_PREFIX: String = "onnx-profile"

        /** What a person has to do about a refusal. */
        fun remedy(): String =
            "Embeddings require macOS arm64 with an Apple GPU and the CoreML execution provider; " +
                "keyword search, extraction and source viewing keep working without them."

        internal fun availableProviders(): Set<String> =
            OrtEnvironment.getAvailableProviders().map { it.name }.toSet()

        /**
         * Whether an offered provider is the one the manifest asks for.
         *
         * The manifest spells it `CoreML` and ONNX Runtime reports `CORE_ML`, so the comparison ignores case
         * and separators: a string that does not match would refuse every machine, and a runtime that offered
         * a genuinely different provider would then be accepted by a looser rule.
         */
        internal fun sameProvider(offered: String, required: String): Boolean =
            offered.filter { it.isLetterOrDigit() }.lowercase() ==
                required.filter { it.isLetterOrDigit() }.lowercase()

        /**
         * Refuses a run whose accelerator did not carry the compute.
         *
         * This is the decision the whole GPU requirement rests on, so it is a function of what the profile
         * says rather than of whether a provider was registered: ONNX Runtime accepts CoreML happily and then
         * runs everything on the CPU when the provider cannot take a node, and only the profile can tell the
         * two apart. Both refusals exist for the same reason — a graph that ran entirely on the CPU, and a
         * graph where the CPU carried most of the kernel time while a token node stayed behind — and the
         * second one is the degradation that is otherwise invisible.
         */
        internal fun requireCoreMlExecution(
            profile: GpuProfile,
            readiness: GpuReadiness,
            modelName: String,
        ): GpuProfile {
            if (profile.coreMlKernelEvents == 0) {
                throw GpuUnavailableException(
                    code = GPU_UNAVAILABLE_CODE,
                    message = "$modelName loaded with ${readiness.provider} registered but ran entirely on " +
                        "the CPU (${profile.describe()}). ${remedy()}",
                )
            }
            if (profile.coreMlShare <= REQUIRED_CORE_ML_SHARE) {
                throw GpuUnavailableException(
                    code = GPU_UNAVAILABLE_CODE,
                    message = "$modelName loaded with ${readiness.provider} registered, but the CPU carried " +
                        "most of the kernel time (${profile.describe()}). ${remedy()}",
                )
            }
            return profile
        }

        /**
         * The share of measured kernel time CoreML must carry before a session is accepted.
         *
         * One place on purpose: the validation run asserts this same bound, so a session production accepts
         * is exactly a session the hardware gate would accept. Shape and control operators may stay on the
         * CPU, but the transformer compute itself must be the accelerator's.
         */
        internal const val REQUIRED_CORE_ML_SHARE: Double = 0.5

        /** The provider enum name v1 requires, so the matrix and the runtime agree on one spelling. */
        internal val requiredProvider: String = OrtProvider.CORE_ML.name
    }
}

/** The platform the archive is running on, as a value a test can substitute. */
data class Platform(private val os: String, private val arch: String) {

    fun isSupported(): Boolean = os == MAC_OS && arch in APPLE_ARCHITECTURES

    fun describe(): String = "$os-$arch"

    fun device(): String = when {
        isSupported() -> "Apple GPU"
        else -> "$os-$arch (outside the validated matrix)"
    }

    companion object {
        private const val MAC_OS = "macos"
        private const val LINUX = "linux"
        private const val WINDOWS = "windows"
        private const val APPLE_ARCHITECTURES = "aarch64"

        fun current(): Platform = Platform(
            os = normaliseOs(System.getProperty("os.name")),
            arch = System.getProperty("os.arch").lowercase(Locale.ROOT),
        )

        /**
         * The JVM spells macOS as "Mac OS X", so the name is normalised into the matrix's vocabulary.
         *
         * This matters more than it looks: the unnormalised name makes the probe refuse every Mac, which
         * turns the hardware gate into a failure on the one platform it exists to validate.
         */
        internal fun normaliseOs(name: String): String {
            val lower = name.lowercase(Locale.ROOT)
            return when {
                lower.startsWith("mac") || lower.contains("darwin") -> MAC_OS
                lower.startsWith(LINUX) -> LINUX
                lower.startsWith(WINDOWS) -> WINDOWS
                else -> lower
            }
        }
    }
}
