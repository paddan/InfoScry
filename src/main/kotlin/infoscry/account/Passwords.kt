package infoscry.account

import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id password hashes in the PHC string format (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`).
 *
 * The parameters a new hash uses are pinned here; a stored hash carries its own, so verifying never depends
 * on today's values and they can be raised later without invalidating anyone's password. The defaults are
 * OWASP's Argon2id baseline (19 MiB, two passes, one lane).
 */
class Passwords(
    private val memoryKiB: Int = DEFAULT_MEMORY_KIB,
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {

    fun hash(password: String): String {
        val salt = Secrets.bytes(SALT_BYTES)
        val digest = derive(password, salt, memoryKiB, iterations, parallelism, HASH_BYTES)
        return "\$argon2id\$v=19\$m=$memoryKiB,t=$iterations,p=$parallelism\$${encode(salt)}\$${encode(digest)}"
    }

    /** Whether [password] is the one [stored] was made from; a malformed hash matches nothing. */
    fun verify(password: String, stored: String): Boolean {
        val parsed = parse(stored) ?: return false
        val digest = derive(password, parsed.salt, parsed.memoryKiB, parsed.iterations, parsed.parallelism, parsed.hash.size)
        return java.security.MessageDigest.isEqual(digest, parsed.hash)
    }

    /**
     * Spends the time a verification takes without a stored hash, so a sign-in for an unknown username
     * answers as slowly as one for a known username with a wrong password.
     */
    fun verifyNothing(password: String) {
        verify(password, dummy)
    }

    private val dummy: String by lazy { hash(Secrets.randomToken()) }

    private class Parsed(val memoryKiB: Int, val iterations: Int, val parallelism: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(stored: String): Parsed? {
        val parts = stored.split('$')
        // "", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash
        if (parts.size != 6 || parts[0].isNotEmpty() || parts[1] != "argon2id" || parts[2] != "v=19") return null
        val parameters = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        val memory = parameters["m"] ?: return null
        val time = parameters["t"] ?: return null
        val lanes = parameters["p"] ?: return null
        if (memory <= 0 || time <= 0 || lanes <= 0) return null
        return try {
            Parsed(memory, time, lanes, decode(parts[4]), decode(parts[5]))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun derive(password: String, salt: ByteArray, memory: Int, time: Int, lanes: Int, length: Int): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memory)
                .withIterations(time)
                .withParallelism(lanes)
                .build(),
        )
        val output = ByteArray(length)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), output)
        return output
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        const val DEFAULT_MEMORY_KIB = 19_456
        const val DEFAULT_ITERATIONS = 2
        const val DEFAULT_PARALLELISM = 1
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32

        const val MIN_LENGTH = 12
        const val MAX_LENGTH = 1_024

        /** The one password rule: a length. Throws with a sentence a person can act on. */
        fun requireAcceptable(password: String) {
            require(password.length >= MIN_LENGTH) { "a password needs at least $MIN_LENGTH characters" }
            require(password.length <= MAX_LENGTH) { "a password may have at most $MAX_LENGTH characters" }
        }
    }
}
