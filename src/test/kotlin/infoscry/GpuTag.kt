package infoscry

/**
 * The JUnit tag for the tests that need the pinned model and the required accelerator.
 *
 * It exists for the same reason as [EXTERNAL_TAG]: a test that fails wherever a GPU is absent, or that
 * silently runs nothing where one is expected, is worse than no test at all. The default suite excludes this
 * tag, and `gpuIntegrationTest` includes it and must fail rather than skip.
 *
 * The build script checks both spellings, because a tag that drifts would turn the hardware gate into a job
 * that passes while running zero tests.
 */
const val GPU_TAG: String = "gpu"

/** The JUnit tag for the tests that need the pinned model files installed. */
const val MODEL_TAG: String = "model"
