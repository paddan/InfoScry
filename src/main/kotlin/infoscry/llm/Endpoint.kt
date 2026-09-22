package infoscry.llm

import java.net.URI

/**
 * Endpoint hygiene for persisted rows.
 *
 * A profile's endpoint may legally carry userinfo credentials (`https://user:pass@host/v1`), and the
 * key itself lives in an environment variable, so nothing secret is *supposed* to ride the URL — but a
 * provider dashboard or a copied config can put one there. When Tasks 21-22 persist an endpoint into
 * `model_calls`, they must persist the stripped form; a credential surviving in a database row is a
 * leak even when the key is elsewhere. A URL whose path or query merely *looks* credentialed is not
 * touched: only the RFC 3986 `userinfo` component is removed.
 */
fun sanitizedEndpoint(endpoint: String): String = try {
    val uri = URI(endpoint)
    if (uri.userInfo == null) {
        endpoint
    } else {
        URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
    }
} catch (failure: java.net.URISyntaxException) {
    // The profile validator already rejected an unparsable endpoint, so this is defensive only; an
    // unparsable string cannot be confidently stripped, and returning it unchanged is the honest best.
    endpoint
}