package infoscry.server

import infoscry.account.AccountService
import infoscry.account.CredentialKind
import infoscry.account.Principal
import io.ktor.http.HttpHeaders
import io.ktor.http.decodeURLPart
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import java.net.URI
import java.security.MessageDigest

/** The header a browser sends its session's CSRF token in. Distinct from `Authorization`, which the CLI uses. */
const val CSRF_HEADER = "X-InfoScry-Csrf"

/** The cookie that carries a browser's session ID. */
const val SESSION_COOKIE = "infoscry_session"

/**
 * Where the server listens and which origin browsers reach it at.
 *
 * [publicOrigin] is `INFOSCRY_PUBLIC_ORIGIN` when it is set (for example `https://archive.example.org` behind
 * a reverse proxy that terminates TLS); it decides the `Host` and `Origin` headers that are accepted besides
 * loopback ones, and whether cookies are `Secure`.
 */
data class ServerSettings(
    val host: String = LOOPBACK_HOST,
    val port: Int = DEFAULT_PORT,
    val publicOrigin: String? = null,
) {
    init {
        require(port in 0..MAX_PORT) { "port must be between 0 and $MAX_PORT, was $port" }
        publicOrigin?.let(::requireOrigin)
    }

    /** Whether cookies need the `Secure` attribute: the browser reaches the server over HTTPS. */
    val secureCookies: Boolean get() = publicOrigin?.startsWith("https://") == true

    /** The host name of [publicOrigin], lower case. */
    internal val publicHost: String? get() = publicOrigin?.let { URI(it).host?.lowercase() }

    companion object {

        /** `INFOSCRY_BIND` (`host:port`) and `INFOSCRY_PUBLIC_ORIGIN`; [portOverride] wins over the bound port. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv(), portOverride: Int? = null): ServerSettings {
            val bind = environment["INFOSCRY_BIND"]?.trim()?.takeIf { it.isNotEmpty() }
            val (host, port) = if (bind == null) {
                LOOPBACK_HOST to DEFAULT_PORT
            } else {
                val separator = bind.lastIndexOf(':')
                require(separator > 0) { "INFOSCRY_BIND must be host:port, was '$bind'" }
                val parsedPort = bind.substring(separator + 1).toIntOrNull()
                require(parsedPort != null) { "INFOSCRY_BIND must end with a port number, was '$bind'" }
                bind.substring(0, separator).removePrefix("[").removeSuffix("]") to parsedPort
            }
            val origin = environment["INFOSCRY_PUBLIC_ORIGIN"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            return ServerSettings(host = host, port = portOverride ?: port, publicOrigin = origin)
        }

        private fun requireOrigin(origin: String) {
            val uri = runCatching { URI(origin) }.getOrNull()
            require(
                uri != null && (uri.scheme == "http" || uri.scheme == "https") && uri.host != null &&
                    (uri.path.isNullOrEmpty()) && uri.query == null,
            ) { "INFOSCRY_PUBLIC_ORIGIN must be an origin such as https://archive.example.org, was '$origin'" }
        }

        private const val MAX_PORT = 65_535
    }
}

/** The loopback host names, which are always accepted: the server is reachable as itself on its own machine. */
object LoopbackHosts {

    private val allowed = setOf("127.0.0.1", "localhost", "::1")

    /** The host name in [hostHeader], without its port and without IPv6 brackets. */
    internal fun nameOf(hostHeader: String?): String? {
        val header = hostHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (header.startsWith("[")) return header.substringAfter("[").substringBefore("]")
        // A single colon separates the port; IPv6 literals without brackets have more than one.
        return if (header.count { it == ':' } == 1) header.substringBefore(':') else header
    }

    fun isAllowed(hostHeader: String?): Boolean = nameOf(hostHeader)?.lowercase() in allowed
}

/** Whether a method changes state. */
fun HttpMethod.isMutation(): Boolean =
    this == HttpMethod.Post || this == HttpMethod.Put || this == HttpMethod.Patch || this == HttpMethod.Delete

private val PRINCIPAL_KEY = AttributeKey<Principal>("infoscry.principal")

/** The caller the request guard authenticated. Every `/api/` route except the public ones has one. */
val ApplicationCall.principal: Principal
    get() = attributes.getOrNull(PRINCIPAL_KEY) ?: throw IllegalStateException("the request has no authenticated caller")

/** The caller, if the request carried a valid credential; only the public routes need to ask. */
val ApplicationCall.principalOrNull: Principal? get() = attributes.getOrNull(PRINCIPAL_KEY)

/** Routes that answer without a signed-in caller. */
private val PUBLIC_ROUTES = setOf(
    HttpMethod.Get to "/api/session",
    HttpMethod.Post to "/api/auth/sign-in",
    HttpMethod.Post to "/api/auth/sign-out",
)

/** Routes a user whose password must be changed may still use. */
private val PASSWORD_CHANGE_ROUTES = PUBLIC_ROUTES + setOf(
    HttpMethod.Get to "/api/me",
    HttpMethod.Post to "/api/me/password",
)

/**
 * The boundary every request crosses before any route runs, installed once so no route can forget it.
 *
 * In order: the `Host` header must name this server (a loopback name or the public origin's host), which
 * defeats DNS rebinding; a mutation whose `Origin` is present must come from this server's own origin; an
 * `/api/` request must carry a valid session cookie or personal access token; a mutation authenticated by
 * the cookie must carry the session's CSRF token; and a user whose password was reset may only change it.
 * The static frontend is served to anyone, since it holds no data and shows the sign-in form.
 */
fun Application.installRequestGuard(accounts: AccountService, settings: ServerSettings, access: CollectionAccess? = null) {
    intercept(ApplicationCallPipeline.Plugins) {
        val host = call.request.headers[HttpHeaders.Host]
        val hostName = LoopbackHosts.nameOf(host)?.lowercase()
        if (!LoopbackHosts.isAllowed(host) && (hostName == null || hostName != settings.publicHost)) {
            call.refuse(HttpStatusCode.Forbidden, "UNKNOWN_HOST", "InfoScry does not answer to the host '$host'")
            finish()
            return@intercept
        }

        val method = call.request.httpMethod
        if (method.isMutation()) {
            val origin = call.request.headers[HttpHeaders.Origin]
            if (origin != null && !isOwnOrigin(origin, host, settings)) {
                call.refuse(HttpStatusCode.Forbidden, "CROSS_ORIGIN_REQUEST", "a change may only come from InfoScry's own pages")
                finish()
                return@intercept
            }
        }

        // Decoded as routing decodes it: `/%61pi/collections` reaches the same handler as `/api/collections`,
        // so the boundary has to see the path the router will match, not the bytes on the wire.
        val path = runCatching { call.request.path().decodeURLPart() }.getOrElse {
            call.refuse(HttpStatusCode.BadRequest, "INVALID_REQUEST", "the request path is not valid")
            finish()
            return@intercept
        }
        if (!isApiPath(path)) return@intercept
        access?.let(call::attachAccess)

        val principal = try {
            authenticate(call, accounts)
        } catch (failure: Exception) {
            // The credential lookup is a database read like any other; its failure is answered the way a
            // route's is, with a code and no internal detail.
            org.slf4j.LoggerFactory.getLogger("infoscry.server").atError()
                .addKeyValue("error_detail", failure.message ?: failure::class.simpleName ?: "unknown failure")
                .setCause(failure)
                .log("a request could not be authenticated")
            call.refuse(
                HttpStatusCode.InternalServerError,
                "INTERNAL_ERROR",
                "the request could not be completed; see the InfoScry log for the detail",
            )
            finish()
            return@intercept
        }
        if (principal == null && call.request.headers[HttpHeaders.Authorization] != null) {
            call.refuse(HttpStatusCode.Unauthorized, "INVALID_ACCESS_TOKEN", "the access token is unknown, revoked or expired")
            finish()
            return@intercept
        }
        principal?.let { call.attributes.put(PRINCIPAL_KEY, it) }

        val route = method to path
        if (route in PUBLIC_ROUTES) return@intercept
        if (principal == null) {
            call.refuse(HttpStatusCode.Unauthorized, "AUTHENTICATION_REQUIRED", "sign in to use InfoScry")
            finish()
            return@intercept
        }
        if (method.isMutation()) {
            if (!call.provesIntent()) {
                call.refuse(HttpStatusCode.Forbidden, "CSRF_TOKEN_INVALID", "the request did not carry this session's CSRF token")
                finish()
                return@intercept
            }
        }
        if (principal.user.mustChangePassword && route !in PASSWORD_CHANGE_ROUTES) {
            call.refuse(HttpStatusCode.Forbidden, "PASSWORD_CHANGE_REQUIRED", "choose a new password before going on")
            finish()
            return@intercept
        }
        if (access == null) return@intercept

        // Who may use this route, from the one table in Authorization.kt. A route the table does not name is
        // refused unless the caller is an administrator; the routing-tree test keeps that from happening.
        val matched = ruleFor(method, path)
        if (matched == null) {
            if (!principal.isAdmin) {
                call.refuse(HttpStatusCode.NotFound, "NOT_FOUND", "no such API route")
                finish()
            }
            return@intercept
        }
        try {
            access.enforce(principal, matched.first, matched.second)
        } catch (fault: infoscry.domain.ApiFaultException) {
            call.refuse(fault.apiStatus.toHttpStatusCode(), fault.code, fault.clientMessage())
            finish()
            return@intercept
        } catch (missing: NoSuchElementException) {
            call.refuse(HttpStatusCode.NotFound, "NOT_FOUND", missing.message.orEmpty())
            finish()
            return@intercept
        }
    }
}

/**
 * Whether the request proves it was sent on purpose by InfoScry's own client, as a mutation must: a
 * personal access token, or a session cookie together with that session's CSRF token. A read that has a
 * side effect elsewhere (sending the server's API key to an endpoint) asks this itself.
 */
fun ApplicationCall.provesIntent(): Boolean {
    val caller = principalOrNull ?: return false
    if (caller.kind == CredentialKind.ACCESS_TOKEN) return true
    val presented = request.headers[CSRF_HEADER] ?: return false
    return constantTimeEquals(presented, AccountService.csrfTokenFor(checkNotNull(caller.sessionId)))
}

/** Whether [decodedPath] is an API path: anything under `/api`, however the separators are repeated. */
private fun isApiPath(decodedPath: String): Boolean {
    val segments = decodedPath.split('/').filter { it.isNotEmpty() }
    return segments.firstOrNull()?.equals("api", ignoreCase = true) == true
}

/** The bearer token if one is sent, otherwise the session cookie. */
private fun authenticate(call: ApplicationCall, accounts: AccountService): Principal? {
    val authorization = call.request.headers[HttpHeaders.Authorization]
    if (authorization != null) {
        if (!authorization.startsWith(BEARER_PREFIX)) return null
        return accounts.authenticateToken(authorization.removePrefix(BEARER_PREFIX).trim())
    }
    val sessionId = call.request.cookies[SESSION_COOKIE]?.takeIf { it.isNotBlank() } ?: return null
    return accounts.authenticateSession(sessionId)
}

/**
 * Whether [origin] is this server's own: the configured public origin, or the loopback origin the request
 * itself addressed (same-origin, so `Origin` is the scheme plus the `Host` header).
 */
private fun isOwnOrigin(origin: String, hostHeader: String?, settings: ServerSettings): Boolean {
    val normalized = origin.trim().trimEnd('/').lowercase()
    if (settings.publicOrigin != null && normalized == settings.publicOrigin.lowercase()) return true
    if (hostHeader == null || !LoopbackHosts.isAllowed(hostHeader)) return false
    return normalized == "http://${hostHeader.trim().lowercase()}"
}

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, code: String, message: String) {
    respondJson(status, ApiErrorResponse(ApiError(code = code, message = message)))
}

internal const val BEARER_PREFIX = "Bearer "

/** Compares two tokens without leaking, through timing, how many leading bytes matched. */
internal fun constantTimeEquals(candidate: String, expected: String): Boolean =
    MessageDigest.isEqual(
        candidate.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8),
    )
