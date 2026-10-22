package infoscry.embedding

import org.slf4j.LoggerFactory

/** An embedder that can build what its first vector would otherwise pay for, without embedding anything. */
fun interface WarmableEmbedder {
    fun warmUp()
}

internal const val EMBEDDER_WARM_UP_LOGGER = "infoscry.embedding.warmup"

private val LOGGER = LoggerFactory.getLogger(EMBEDDER_WARM_UP_LOGGER)

/**
 * Builds and verifies the pinned model's session on a background thread, so the first semantic search does
 * not wait seconds for CoreML to load and compile the model.
 *
 * It is a convenience, never a requirement: no installed model means nothing to warm, and an accelerator that
 * cannot start is logged and left for the search or import that needs a vector to report with its remedy.
 * Keyword search and source reads never wait on it. The thread is a daemon so shutdown is not held up.
 */
fun startEmbedderWarmUp(embedder: () -> WarmableEmbedder?): Thread =
    Thread.ofPlatform().daemon().name("embedder-warm-up").start {
        try {
            embedder()?.warmUp()
        } catch (failure: Exception) {
            LOGGER.atWarn()
                .addKeyValue("component", "embedding")
                .addKeyValue("code", (failure as? GpuUnavailableException)?.code ?: failure.javaClass.simpleName)
                .log("the embedding model could not be warmed up; semantic search will report why when used")
        }
    }
