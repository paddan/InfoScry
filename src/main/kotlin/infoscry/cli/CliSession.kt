package infoscry.cli

import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.config.RuntimeInfo
import infoscry.domain.Collection

/**
 * Where a CLI command does its work: in this process, or through the server that already owns the data
 * directory.
 *
 * Only one process may write a data directory, so the choice is not a preference — if a server is
 * running, opening the database here would either fail or corrupt the state the server is holding. The
 * CLI therefore asks the server when it can, and works locally only when nothing else holds the lock.
 * Both cases go through the same operations, so a command does not care which one it got.
 */
sealed interface CliSession : AutoCloseable {

    /** Whether the work is being done by a server this process is talking to. */
    val isRemote: Boolean

    suspend fun listCollections(): List<Collection>

    suspend fun createCollection(
        name: String,
        description: String? = null,
    ): Collection

    /** Works directly on the data directory, holding the process lock for the duration. */
    class Local(val context: AppContext) : CliSession {

        override val isRemote: Boolean get() = false

        override suspend fun listCollections(): List<Collection> = context.collectionService.list()

        override suspend fun createCollection(name: String, description: String?): Collection =
            context.collectionService.create(name = name, description = description)

        override fun close() {
            context.close()
        }
    }

    /** Works through a server that owns the data directory. */
    class Remote(private val api: LoopbackApi) : CliSession {

        override val isRemote: Boolean get() = true

        override suspend fun listCollections(): List<Collection> = api.listCollections()

        override suspend fun createCollection(name: String, description: String?): Collection =
            api.createCollection(name = name, description = description)

        override fun close() {
            api.close()
        }
    }

    companion object {

        /**
         * The running server for [paths], or this process when there is none.
         *
         * A `runtime.json` whose process is gone is treated as absent rather than as a server: the file
         * outlives a killed process, and being sent to a dead port would be a confusing way to learn
         * that nothing is running.
         */
        fun connect(paths: AppPaths): CliSession {
            val runtime = RuntimeInfo.discover(paths.runtimeFile)
            return if (runtime == null) {
                Local(AppContext.open(paths))
            } else {
                Remote(LoopbackApi(runtime))
            }
        }
    }
}
