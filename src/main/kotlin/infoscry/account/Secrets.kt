package infoscry.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Random credentials and their stored digests.
 *
 * A session ID or access token is stored only as its SHA-256: the value is 256 random bits, so a fast hash
 * is enough (nothing to brute-force), and a copy of the database cannot be replayed as a credential.
 * Passwords are different and go through [Passwords].
 */
internal object Secrets {

    private val random = SecureRandom()

    /** 256 random bits, URL-safe and unpadded. */
    fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** [length] characters from an alphabet without look-alikes (no 0/O, 1/l/I), for passwords a person types. */
    fun readablePassword(length: Int = ONE_TIME_PASSWORD_LENGTH): String =
        buildString { repeat(length) { append(READABLE[random.nextInt(READABLE.length)]) } }

    /** The hex SHA-256 of [value], the form stored for session IDs and access tokens. */
    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    /** Compares two strings without leaking, through timing, how many leading bytes matched. */
    fun constantTimeEquals(candidate: String, expected: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    fun bytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val TOKEN_BYTES = 32
    const val ONE_TIME_PASSWORD_LENGTH = 20
    private const val READABLE = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
}
