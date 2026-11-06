package infoscry.account

import infoscry.storage.AccountStore
import infoscry.storage.Database
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Users, sessions and access tokens, against a real database and a clock the test moves. */
class AccountServiceTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private var now: Instant = Instant.parse("2026-11-06T10:00:00Z")
    private lateinit var accounts: AccountService

    @BeforeTest
    fun open() {
        dataDir = Files.createTempDirectory("infoscry-accounts")
        database = Database(dataDir.resolve("infoscry.db"))
        SchemaMigrator(database).migrate()
        accounts = service()
    }

    @AfterTest
    fun close() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    private fun service(limiter: SignInRateLimiter = SignInRateLimiter(clock = { now })) = AccountService(
        AccountStore(database),
        passwords = FAST_PASSWORDS,
        sessions = SessionSettings(idle = Duration.ofHours(12), absolute = Duration.ofDays(14)),
        clock = { now },
        rateLimiter = limiter,
    )

    /** A user who has set their own password, as after a first sign-in. */
    private fun user(username: String, role: UserRole = UserRole.USER, password: String = PASSWORD): User {
        val created = accounts.createUser(username, null, role, actor = null)
        val signedIn = accounts.signIn(username, created.oneTimePassword!!, ADDRESS, null)
        accounts.changeOwnPassword(accounts.authenticateSession(signedIn.sessionId)!!, created.oneTimePassword!!, password)
        accounts.signOut(signedIn.sessionId)
        return accounts.user(created.user.id)
    }

    @Test
    fun `a new user gets a one-time password they must change`() {
        val created = accounts.createUser("anna", "Anna Berg", UserRole.ADMIN, actor = null)

        assertTrue(created.created)
        val password = assertNotNull(created.oneTimePassword)
        assertTrue(password.length >= Passwords.MIN_LENGTH)
        assertTrue(created.user.mustChangePassword)
        assertEquals("Anna Berg", created.user.displayName)
        val signedIn = accounts.signIn("ANNA", password, ADDRESS, "test")
        assertEquals(created.user.id, signedIn.user.id)
    }

    @Test
    fun `creating a user twice answers the existing user and changes nothing`() {
        val first = accounts.createUser("anna", null, UserRole.ADMIN, actor = null)
        val second = accounts.createUser("Anna", "Someone else", UserRole.USER, actor = null)

        assertFalse(second.created)
        assertNull(second.oneTimePassword)
        assertEquals(first.user, second.user)
        assertEquals(1, accounts.listUsers().size)
        // The first password still works: the repeat did not reset it.
        accounts.signIn("anna", first.oneTimePassword!!, ADDRESS, null)
    }

    @Test
    fun `sign-in refuses a wrong password, an unknown user and an inactive user alike`() {
        val anna = user("anna", UserRole.ADMIN)
        val erik = user("erik")
        accounts.updateUser(erik.id, null, null, active = false, actor = anna.id)

        val wrong = assertFailsWith<InvalidCredentialsException> { accounts.signIn("anna", "not the password", ADDRESS, null) }
        val unknown = assertFailsWith<InvalidCredentialsException> { accounts.signIn("nobody", PASSWORD, ADDRESS, null) }
        val inactive = assertFailsWith<InvalidCredentialsException> { accounts.signIn("erik", PASSWORD, ADDRESS, null) }
        assertEquals(setOf(wrong.message), setOf(unknown.message, inactive.message))
    }

    @Test
    fun `signing in twice gives two sessions and signing out twice succeeds`() {
        user("anna")
        val first = accounts.signIn("anna", PASSWORD, ADDRESS, null)
        val second = accounts.signIn("anna", PASSWORD, ADDRESS, null)

        assertNotEquals(first.sessionId, second.sessionId)
        accounts.signOut(first.sessionId)
        accounts.signOut(first.sessionId)
        assertNull(accounts.authenticateSession(first.sessionId))
        assertNotNull(accounts.authenticateSession(second.sessionId))
    }

    @Test
    fun `a session ends after twelve idle hours and after fourteen days in all`() {
        user("anna")
        val idle = accounts.signIn("anna", PASSWORD, ADDRESS, null)
        val busy = accounts.signIn("anna", PASSWORD, ADDRESS, null)

        now = now.plus(Duration.ofHours(11))
        assertNotNull(accounts.authenticateSession(busy.sessionId))
        now = now.plus(Duration.ofHours(2))
        assertNull(accounts.authenticateSession(idle.sessionId), "idle for 13 hours")
        assertNotNull(accounts.authenticateSession(busy.sessionId), "seen 2 hours ago")

        // Seen every 11 hours, so never idle, until it is older than 14 days.
        repeat(30) {
            now = now.plus(Duration.ofHours(11))
            accounts.authenticateSession(busy.sessionId)
        }
        assertNull(accounts.authenticateSession(busy.sessionId), "past the absolute expiry")
    }

    @Test
    fun `the CSRF token is bound to its session`() {
        user("anna")
        val first = accounts.signIn("anna", PASSWORD, ADDRESS, null)
        val second = accounts.signIn("anna", PASSWORD, ADDRESS, null)

        assertEquals(first.csrfToken, AccountService.csrfTokenFor(first.sessionId))
        assertNotEquals(first.csrfToken, second.csrfToken)
        assertNotEquals(first.sessionId, first.csrfToken)
    }

    @Test
    fun `deactivation ends sessions and tokens at once and reactivation does not bring tokens back`() {
        val anna = user("anna", UserRole.ADMIN)
        val erik = user("erik")
        val session = accounts.signIn("erik", PASSWORD, ADDRESS, null)
        val token = accounts.createToken(erik.id, "laptop", null)

        accounts.updateUser(erik.id, null, null, active = false, actor = anna.id)
        accounts.updateUser(erik.id, null, null, active = false, actor = anna.id)

        assertNull(accounts.authenticateSession(session.sessionId))
        assertNull(accounts.authenticateToken(token.value))
        accounts.updateUser(erik.id, null, null, active = true, actor = anna.id)
        assertNull(accounts.authenticateToken(token.value))
        assertNotNull(accounts.listTokens(erik.id).single().revokedAt)
    }

    @Test
    fun `the last active administrator cannot be deactivated or demoted`() {
        val anna = user("anna", UserRole.ADMIN)
        val erik = user("erik")

        assertFailsWith<LastAdministratorException> { accounts.updateUser(anna.id, null, UserRole.USER, null, anna.id) }
        assertFailsWith<LastAdministratorException> { accounts.updateUser(anna.id, null, null, false, anna.id) }

        accounts.updateUser(erik.id, null, UserRole.ADMIN, null, anna.id)
        accounts.updateUser(anna.id, null, UserRole.USER, null, erik.id)
        assertEquals(UserRole.USER, accounts.user(anna.id).role)
    }

    @Test
    fun `two administrators demoting each other at once leave one administrator`() {
        val anna = user("anna", UserRole.ADMIN)
        val erik = user("erik", UserRole.ADMIN)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(anna.id to erik.id, erik.id to anna.id).map { (target, actor) ->
                pool.submit<Result<User>> {
                    barrier.await()
                    runCatching { accounts.updateUser(target, null, UserRole.USER, null, actor) }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is LastAdministratorException)
            assertEquals(1, accounts.listUsers().count { it.role == UserRole.ADMIN })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a password reset ends sessions, replaces the password and requires a change`() {
        val anna = user("anna", UserRole.ADMIN)
        val erik = user("erik")
        val session = accounts.signIn("erik", PASSWORD, ADDRESS, null)

        val first = accounts.resetPassword(erik.id, anna.id)
        val second = accounts.resetPassword(erik.id, anna.id)

        assertNull(accounts.authenticateSession(session.sessionId))
        assertFailsWith<InvalidCredentialsException> { accounts.signIn("erik", PASSWORD, ADDRESS, null) }
        assertFailsWith<InvalidCredentialsException> { accounts.signIn("erik", first, ADDRESS, null) }
        val signedIn = accounts.signIn("erik", second, ADDRESS, null)
        assertTrue(signedIn.user.mustChangePassword)
    }

    @Test
    fun `changing the password keeps the asking session and ends the others`() {
        user("anna")
        val asking = accounts.signIn("anna", PASSWORD, ADDRESS, null)
        val other = accounts.signIn("anna", PASSWORD, ADDRESS, null)
        val principal = accounts.authenticateSession(asking.sessionId)!!

        assertFailsWith<WrongCurrentPasswordException> { accounts.changeOwnPassword(principal, "wrong password!", "another long password") }
        assertFailsWith<IllegalArgumentException> { accounts.changeOwnPassword(principal, PASSWORD, "short") }
        accounts.changeOwnPassword(principal, PASSWORD, "another long password")

        assertNotNull(accounts.authenticateSession(asking.sessionId))
        assertNull(accounts.authenticateSession(other.sessionId))
        accounts.signIn("anna", "another long password", ADDRESS, null)
    }

    @Test
    fun `tokens authenticate until revoked or expired, and revoking twice succeeds`() {
        val anna = user("anna")
        val forever = accounts.createToken(anna.id, "cli", null)
        val week = accounts.createToken(anna.id, "script", 7)

        assertEquals(anna.id, accounts.authenticateToken(forever.value)?.user?.id)
        assertEquals(CredentialKind.ACCESS_TOKEN, accounts.authenticateToken(week.value)?.kind)
        assertNull(accounts.authenticateToken("isc_" + "x".repeat(43)))
        assertNull(accounts.authenticateToken(forever.value.removePrefix("isc_")))

        now = now.plus(Duration.ofDays(8))
        assertNull(accounts.authenticateToken(week.value))
        accounts.revokeToken(anna.id, forever.token.id)
        accounts.revokeToken(anna.id, forever.token.id)
        assertNull(accounts.authenticateToken(forever.value))
        assertNotNull(accounts.listTokens(anna.id).single { it.id == forever.token.id }.lastUsedAt)
    }

    @Test
    fun `a user cannot revoke someone else's token`() {
        val anna = user("anna")
        val erik = user("erik")
        val token = accounts.createToken(anna.id, "cli", null)

        assertFailsWith<NoSuchElementException> { accounts.revokeToken(erik.id, token.token.id) }
        assertNotNull(accounts.authenticateToken(token.value))
    }

    @Test
    fun `failed sign-ins are limited per username until the window passes or a sign-in succeeds`() {
        user("anna")
        val limited = service(SignInRateLimiter(perUsername = 3, perAddress = 100, window = Duration.ofMinutes(15), clock = { now }))
        repeat(3) { assertFailsWith<InvalidCredentialsException> { limited.signIn("anna", "wrong password", ADDRESS, null) } }

        val refused = assertFailsWith<SignInRateLimitedException> { limited.signIn("Anna", PASSWORD, ADDRESS, null) }
        assertTrue(refused.retryAfterSeconds in 1..900)
        now = now.plus(Duration.ofMinutes(16))
        limited.signIn("anna", PASSWORD, ADDRESS, null)
    }

    @Test
    fun `failed sign-ins are limited per address across usernames`() {
        val limited = service(SignInRateLimiter(perUsername = 100, perAddress = 2, window = Duration.ofMinutes(15), clock = { now }))
        assertFailsWith<InvalidCredentialsException> { limited.signIn("a", "wrong password", ADDRESS, null) }
        assertFailsWith<InvalidCredentialsException> { limited.signIn("b", "wrong password", ADDRESS, null) }

        assertFailsWith<SignInRateLimitedException> { limited.signIn("c", "wrong password", ADDRESS, null) }
        assertFailsWith<InvalidCredentialsException> { limited.signIn("c", "wrong password", "10.0.0.9", null) }
    }

    @Test
    fun `expired sessions are swept`() {
        user("anna")
        accounts.signIn("anna", PASSWORD, ADDRESS, null)
        now = now.plus(Duration.ofHours(13))
        assertEquals(1, accounts.deleteExpiredSessions())
        assertEquals(0, accounts.deleteExpiredSessions())
    }

    @Test
    fun `password hashes carry their parameters and verify only the right password`() {
        val strong = Passwords()
        val hash = strong.hash(PASSWORD)
        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertTrue(FAST_PASSWORDS.verify(PASSWORD, hash), "verification reads the parameters from the hash")
        assertFalse(strong.verify("$PASSWORD!", hash))
        assertFalse(strong.verify(PASSWORD, "not a hash"))
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val ADDRESS = "127.0.0.1"
        val FAST_PASSWORDS = Passwords(memoryKiB = 64, iterations = 1)
    }
}
