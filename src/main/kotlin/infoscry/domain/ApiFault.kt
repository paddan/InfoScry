package infoscry.domain

/**
 * The HTTP status family a refusal maps to, named without depending on an HTTP framework: domain and
 * storage modules that throw an [ApiFaultException] stay free of any `io.ktor` import. `Routes.kt` is
 * the one place that translates a status into an `HttpStatusCode`.
 */
enum class ApiStatus {
    BAD_REQUEST,
    CONFLICT,
    LOCKED,
    SERVICE_UNAVAILABLE,
}

/**
 * A refusal that carries its own HTTP-facing answer: the status family, a stable code a caller can
 * act on, and the sentence a client may see. A domain or storage exception that refuses a request
 * extends this instead of `IllegalStateException` directly, so `Routes.kt` needs no per-type catch
 * clause to answer it.
 *
 * [clientMessage] defaults to this exception's own [message]; override it only where the client's
 * sentence must differ from the one a log reads (for example, to drop an id the client has no use
 * for, or to word a generic refusal without repeating a name back).
 */
abstract class ApiFaultException(
    val apiStatus: ApiStatus,
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    open fun clientMessage(): String = message.orEmpty()

    /** Structured facts a client may act on beside [code] (an index, an id); never user content. */
    open fun details(): Map<String, String>? = null
}
