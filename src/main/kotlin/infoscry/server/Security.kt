package infoscry.server

import infoscry.config.BearerToken
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The header a browser must send with a mutation. Distinct from `Authorization`, which the CLI uses. */
const val CSRF_HEADER = "X-InfoScry-Csrf"

/**
 * The credentials a caller must present, generated fresh for each server launch.
 *
 * Two callers exist and they are authenticated differently. The CLI holds [bearer], which it reads from
 * the private `runtime.json`; the browser cannot read that file, so it gets [csrfToken] from a
 * same-origin bootstrap call instead. Neither token is ever configured by hand, and neither survives a
 * restart, so a value leaked from an old log is worthless.
 *
 * Comparison is constant-time: an attacker who can measure response latencies should not be able to
 * recover a token one byte at a time.
 */
class ApiCredentials private constructor(val bearer: BearerToken, val csrfToken: String) {

    fun acceptsBearer(candidate: String?): Boolean =
        candidate != null && constantTimeEquals(candidate, bearer.value)

    fun acceptsCsrf(candidate: String?): Boolean =
        candidate != null && constantTimeEquals(candidate, csrfToken)

    companion object {

        fun new(): ApiCredentials = ApiCredentials(BearerToken.new(), newToken())

        private fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private const val TOKEN_BYTES = 32
    }
}

/**
 * The host names this server answers to.
 *
 * Binding to the loopback interface keeps the socket off the network, but a page in a browser on the
 * same machine can still reach `127.0.0.1` — and a public hostname can be pointed at it. Requiring a
 * loopback `Host` header closes that hole: only a caller that actually addressed it as loopback gets an
 * answer.
 */
object LoopbackHosts {

    private val allowed = setOf("127.0.0.1", "localhost", "::1")

    /** The host name in [hostHeader], without its port and without IPv6 brackets. */
    private fun nameOf(hostHeader: String?): String? {
        val header = hostHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (header.startsWith("[")) return header.substringAfter("[").substringBefore("]")
        // A single colon separates the port; IPv6 literals without brackets have more than one.
        return if (header.count { it == ':' } == 1) header.substringBefore(':') else header
    }

    fun isAllowed(hostHeader: String?): Boolean = nameOf(hostHeader)?.lowercase() in allowed
}

/** Whether a method changes state, and therefore needs a credential. */
fun HttpMethod.isMutation(): Boolean =
    this == HttpMethod.Post || this == HttpMethod.Put || this == HttpMethod.Patch || this == HttpMethod.Delete

/**
 * Rejects non-loopback callers and unauthenticated mutations before any route runs.
 *
 * The guard is installed as a pipeline interceptor rather than repeated in every handler, because a
 * forgotten check in one route is exactly the mistake this boundary exists to prevent. Read-only
 * requests need no credential: they are same-origin, loopback-only, and expose nothing the machine's
 * own user could not read from the data directory anyway.
 */
fun Application.installRequestGuard(credentials: ApiCredentials) {
    intercept(ApplicationCallPipeline.Plugins) {
        val host = call.request.headers[HttpHeaders.Host]
        if (!LoopbackHosts.isAllowed(host)) {
            call.respondJson(
                io.ktor.http.HttpStatusCode.Forbidden,
                ApiErrorResponse(
                    ApiError(
                        code = "NON_LOOPBACK_HOST",
                        message = "InfoScry answers only loopback callers; the Host header was '$host'",
                    ),
                ),
            )
            finish()
            return@intercept
        }

        if (!call.request.httpMethod.isMutation()) return@intercept

        val bearer = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix(BEARER_PREFIX)
            ?.trim()
        val csrf = call.request.headers[CSRF_HEADER]
        if (credentials.acceptsBearer(bearer) || credentials.acceptsCsrf(csrf)) return@intercept

        call.respondJson(
            io.ktor.http.HttpStatusCode.Unauthorized,
            ApiErrorResponse(
                ApiError(
                    code = "MUTATION_REQUIRES_CREDENTIALS",
                    message = "a mutation needs either the runtime bearer token (CLI) or the session " +
                        "CSRF token (browser); neither was accepted",
                ),
            ),
        )
        finish()
    }
}

private const val BEARER_PREFIX = "Bearer "

/** Compares two tokens without leaking, through timing, how many leading bytes matched. */
internal fun constantTimeEquals(candidate: String, expected: String): Boolean =
    MessageDigest.isEqual(
        candidate.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8),
    )
