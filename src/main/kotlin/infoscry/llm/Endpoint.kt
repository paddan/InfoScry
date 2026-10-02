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

/**
 * Whether [endpoint] carries `userinfo` credentials, as in `https://user:secret@host/v1`.
 *
 * A profile never holds a key: the credential lives in the environment variable the profile names, so a
 * URL carrying one is always an accident of a copied config rather than a supported way to authenticate.
 * It must therefore never be stored, logged or echoed back — a stored endpoint is returned verbatim by
 * the profile API, and a log line or an error that quotes it publishes the credential to everyone who can
 * read either. The component is read from the parsed URI rather than from the text before an `@`, so a
 * path or query that merely contains one (`https://host/a@b/c`) is not mistaken for credentials.
 */
fun endpointCarriesUserInfo(endpoint: String): Boolean = try {
    URI(endpoint).userInfo != null
} catch (_: java.net.URISyntaxException) {
    // Unparsable is not the same fact as credentialed, and the validators reject an unparsable value
    // themselves rather than through this answer.
    false
}