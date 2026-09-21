package infoscry.embedding

import infoscry.config.AppPaths
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Installs the pinned embedding model into a data directory.
 *
 * It runs from the build (`./gradlew embeddingModel`) because that is the command the application's own
 * remedy names, and because the weights are 1.1 GB: fetching them is a deliberate act with its own progress
 * and its own failure, not something an import should discover halfway through a document.
 *
 * The data directory comes from `-Dinfoscry.dataDir=` and defaults to `~/.infoscry`, so the installer writes
 * where the application reads.
 */
object ModelInstaller {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(
            System.getProperty("infoscry.dataDir")
                ?: "${System.getProperty("user.home")}/.infoscry",
        )
        val paths = AppPaths.from(root)
        val manifest = ModelManifest.load()
        println(
            "installing ${manifest.model} at ${manifest.revision.take(12)} " +
                "(${manifest.files.size} files, ${manifest.files.sumOf { it.bytes } / 1_000_000} MB) " +
                "into ${paths.modelsDir}",
        )
        try {
            val installation = ModelManager(manifest).ensureInstalled(paths.modelsDir)
            println("installed ${installation.directory}")
            println("model fingerprint ${installation.fingerprint}")
            println("execution provider ${manifest.executionProvider.name} ${manifest.executionProvider.options}")
        } catch (failure: ModelInstallException) {
            System.err.println("${failure.code}: ${failure.message}")
            exitProcess(1)
        }
    }
}
