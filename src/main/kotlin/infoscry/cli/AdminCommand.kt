package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import infoscry.account.AccountService
import infoscry.account.User
import infoscry.account.UserRole
import infoscry.config.AppPaths
import infoscry.server.ApiJson
import infoscry.storage.AccountStore
import infoscry.storage.Database
import infoscry.storage.DatabaseResolver
import infoscry.storage.SchemaMigrator
import kotlinx.serialization.Serializable

/**
 * `infoscry admin` — account administration on the server's own machine.
 *
 * These commands connect to the database directly with the `INFOSCRY_DATABASE_*` variables, not to the
 * server, so the first administrator can be created before the server has ever run and an operator who has
 * lost every administrator password can get back in. They change account rows only, which a running server
 * reads on every request, so they take no process ownership and may run beside it.
 */
class AdminCommand : CliktCommand(name = "admin") {

    init {
        subcommands(CreateUserCommand(), ResetPasswordCommand(), ListUsersCommand())
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) echoFormattedHelp()
    }
}

@Serializable
data class AdminCreatedUser(val user: User, val created: Boolean, val oneTimePassword: String? = null) {
    override fun toString(): String = "AdminCreatedUser(user=${user.username}, created=$created)"
}

@Serializable
data class AdminPasswordReset(val user: User, val oneTimePassword: String) {
    override fun toString(): String = "AdminPasswordReset(user=${user.username})"
}

@Serializable
data class AdminUsers(val users: List<User>)

/** Opens the database the data directory resolves to, applies the schema, and runs [block] with the accounts. */
private fun <T> withAccounts(options: CliOptions, block: (AccountService) -> T): T {
    val paths = AppPaths.of(options.dataDir)
    infoscry.logging.LoggingBootstrap.useLogsDirectory(paths)
    val config = DatabaseResolver.resolve(paths)
    val database = try {
        Database(config, maximumPoolSize = 2)
    } catch (failure: Exception) {
        throw CliFailure("could not connect to the database at ${config.url}: ${failure.message}", failure)
    }
    return database.use {
        SchemaMigrator(it).migrate()
        block(AccountService(AccountStore(it)))
    }
}

/** `infoscry admin create-user [--admin] [--display-name <name>] <username>`. */
class CreateUserCommand : CliktCommand(name = "create-user") {
    private val username by argument("username")
    private val admin by option("--admin", help = "Make the user an administrator").flag()
    private val displayName by option("--display-name", help = "The name shown in the interface (default: the username)")
    private val jsonFlag by option("--json", help = JSON_HELP).flag()
    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val created = try {
            withAccounts(options) { it.createUser(username, displayName, if (admin) UserRole.ADMIN else UserRole.USER, actor = null) }
        } catch (invalid: IllegalArgumentException) {
            throw CliFailure(invalid.message.orEmpty(), invalid)
        }
        if (options.json) {
            echo(ApiJson.encodeToString(AdminCreatedUser(created.user, created.created, created.oneTimePassword)))
            return
        }
        if (!created.created) {
            echo("User '${created.user.username}' already exists; nothing was changed.")
            return
        }
        echo("Created ${created.user.role.name.lowercase()} '${created.user.username}'.")
        echo("One-time password: ${created.oneTimePassword}")
        echo("It is shown only now; the user chooses a new password at first sign-in.", err = true)
    }
}

/** `infoscry admin reset-password <username>`. */
class ResetPasswordCommand : CliktCommand(name = "reset-password") {
    private val username by argument("username")
    private val jsonFlag by option("--json", help = JSON_HELP).flag()
    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val (user, password) = withAccounts(options) { accounts ->
            val user = accounts.findByUsername(username) ?: throw CliFailure("no user named '$username' exists")
            user to accounts.resetPassword(user.id, actor = null)
        }
        if (options.json) {
            echo(ApiJson.encodeToString(AdminPasswordReset(user, password)))
            return
        }
        echo("Reset the password of '${user.username}' and ended their sessions.")
        echo("One-time password: $password")
    }
}

/** `infoscry admin list-users`. */
class ListUsersCommand : CliktCommand(name = "list-users") {
    private val jsonFlag by option("--json", help = JSON_HELP).flag()
    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val users = withAccounts(options) { it.listUsers() }
        if (options.json) {
            echo(ApiJson.encodeToString(AdminUsers(users)))
        } else if (users.isEmpty()) {
            echo("No users.")
        } else {
            users.forEach { user ->
                echo("${user.username}  ${user.role.name.lowercase()}  ${if (user.active) "active" else "inactive"}  ${user.displayName}")
            }
        }
    }
}
