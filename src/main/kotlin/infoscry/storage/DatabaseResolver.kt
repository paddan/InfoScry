package infoscry.storage

import infoscry.config.AppPaths

/**
 * How a data directory finds its database.
 *
 * A running InfoScry reads the database from the environment ([DatabaseConfig.fromEnvironment]); the data
 * directory only holds managed files, models, logs and scratch space. The test suite replaces [resolve] so
 * every temporary data directory gets a database of its own, the way each one used to get its own Postgres
 * file.
 */
object DatabaseResolver {
    @Volatile
    var resolve: (AppPaths) -> DatabaseConfig = { DatabaseConfig.fromEnvironment() }
}
