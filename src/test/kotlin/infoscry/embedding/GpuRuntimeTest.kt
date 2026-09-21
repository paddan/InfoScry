package infoscry.embedding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The readiness decision and the profile evidence, without a model or a GPU.
 *
 * These are the unit tests the hardware gate rests on: they pin the two refusals that matter (a machine
 * outside the matrix, and a graph that ran on the CPU even though the provider was registered) and the way
 * the profile is read, so the integration run only has to prove that the real model behaves as predicted.
 */
class GpuRuntimeTest {

    private val manifest = testManifest()

    private fun runtime(
        providers: Set<String> = setOf("CORE_ML", "CPU"),
        platform: Platform = Platform("macos", "aarch64"),
        version: String = "1.22.0",
    ): GpuRuntime = GpuRuntime(
        manifest = manifest,
        providers = { providers },
        platform = platform,
        runtimeVersion = version,
    )

    private fun kernelEvent(
        provider: String,
        micros: Long,
        name: String = "/layer/MatMul_kernel_time",
        activationBytes: Long = 0,
    ): String =
        """{"cat":"Node","name":"$name","dur":$micros,"args":{"provider":"$provider","activation_size":"$activationBytes"}}"""

    private fun profile(vararg events: String): String = "[${events.joinToString(",")}]"

    @Test
    fun `the validated platform with coreml is ready and carries the model's identity`() {
        val readiness = runtime().probe()

        assertTrue(readiness.ready, readiness.reasons.toString())
        assertEquals("CoreML", readiness.provider)
        assertEquals("Apple GPU", readiness.device)
        assertEquals("1.22.0", readiness.runtimeVersion)
        assertEquals(manifest.fingerprint(), readiness.modelFingerprint)
        assertTrue(readiness.describe().contains("Apple GPU"), readiness.describe())
    }

    @Test
    fun `a machine outside the validated matrix is refused and the reason names it`() {
        val readiness = runtime(platform = Platform("linux", "amd64")).probe()

        assertTrue(!readiness.ready)
        assertTrue(
            readiness.reasons.any { it.contains("linux-amd64") && it.contains("macOS arm64") },
            "the reason names the machine and the matrix: ${readiness.reasons}",
        )
        assertTrue(readiness.describe().contains("embeddings are unavailable"), readiness.describe())
    }

    @Test
    fun `a runtime without the coreml provider is refused and lists what it does offer`() {
        val readiness = runtime(providers = setOf("CPU", "WEBGPU")).probe()

        assertTrue(!readiness.ready)
        val reason = readiness.reasons.single()
        assertTrue(reason.contains("CoreML"), reason)
        assertTrue(reason.contains("CPU") && reason.contains("WEBGPU"), reason)
    }

    @Test
    fun `a profile that shows coreml kernels is accepted and reports its share of the work`() {
        val summary = OnnxProfile.summarise(
            profile(
                kernelEvent("CoreMLExecutionProvider", 900, "/layer/MatMul_kernel_time", activationBytes = 24736),
                kernelEvent("CoreMLExecutionProvider", 90, "/layer/Attention_kernel_time", activationBytes = 8192),
                kernelEvent("CPUExecutionProvider", 10, "/Shape_kernel_time"),
                // Not kernel events: they carry no provider or no duration and cannot be evidence.
                """{"cat":"Session","name":"model_run","dur":5000,"args":{}}""",
                """{"cat":"Node","name":"/x/Identity_kernel_time","args":{"provider":"CoreMLExecutionProvider"}}""",
            ),
        )

        assertEquals(2, summary.coreMlKernelEvents)
        assertEquals(1, summary.cpuKernelEvents)
        assertEquals(990.0, summary.coreMlKernelMicros)
        assertEquals(10.0, summary.cpuKernelMicros)
        assertEquals(0.99, summary.coreMlShare, 1e-9)
        assertEquals(24736L, summary.largestActivationBytes, "the largest activation CoreML reported")
        assertTrue(summary.describe().contains("99.0%"), summary.describe())
    }

    @Test
    fun `a graph that ran entirely on the cpu is refused`() {
        val summary = OnnxProfile.summarise(
            profile(
                kernelEvent("CPUExecutionProvider", 5000, "/layer/MatMul_kernel_time"),
                kernelEvent("CPUExecutionProvider", 4000, "/layer/Attention_kernel_time"),
            ),
        )

        val failure = assertFailsWith<GpuUnavailableException> {
            GpuRuntime.requireCoreMlExecution(summary, runtime().probe(), "model.onnx")
        }
        assertEquals(GpuRuntime.GPU_UNAVAILABLE_CODE, failure.code)
        assertTrue(failure.message!!.contains("entirely on the CPU"), failure.message)
        assertTrue(failure.message!!.contains("Apple GPU"), "the remedy is in the message: ${failure.message}")
    }

    @Test
    fun `one coreml kernel is enough to accept a run whose shape operators stayed on the cpu`() {
        val summary = OnnxProfile.summarise(
            profile(
                kernelEvent("CoreMLExecutionProvider", 700, "/layer/MatMul_kernel_time"),
                kernelEvent("CPUExecutionProvider", 2, "/Shape_kernel_time"),
                kernelEvent("CPUExecutionProvider", 1, "/Gather_kernel_time"),
            ),
        )

        assertEquals(summary, GpuRuntime.requireCoreMlExecution(summary, runtime().probe(), "model.onnx"))
    }

    @Test
    fun `a profile that is not a trace array is refused rather than read as no evidence`() {
        assertFailsWith<IllegalStateException> { OnnxProfile.summarise("""{"traceEvents":[]}""") }
    }

    @Test
    fun `an empty profile is refused`() {
        val summary = OnnxProfile.summarise("[]")

        assertEquals(0, summary.totalKernelEvents)
        assertFailsWith<GpuUnavailableException> {
            GpuRuntime.requireCoreMlExecution(summary, runtime().probe(), "model.onnx")
        }
    }

    @Test
    fun `the required provider name is the one the runtime reports`() {
        assertEquals("CORE_ML", GpuRuntime.requiredProvider)
        assertTrue(
            GpuRuntime.requiredProvider in GpuRuntime.availableProviders(),
            "this machine's ONNX Runtime must offer ${GpuRuntime.requiredProvider}, or the gate cannot pass",
        )
    }

    @Test
    fun `the model fingerprint follows the export and the tokenizer, not the directory`() {
        val first = testManifest().fingerprint()

        assertEquals(first, testManifest().fingerprint(), "the same manifest fingerprints the same way")
        assertTrue(first != testManifest(files = listOf(
            ModelFile("model.onnx", bytes = 8, sha256 = "f".repeat(64), sha256Source = "lfs-oid"),
            ModelFile("tokenizer.json", bytes = 8, sha256 = "1".repeat(64), sha256Source = "lfs-oid"),
        )).fingerprint(), "another export is another vector space")
    }
}
