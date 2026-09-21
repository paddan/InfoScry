package infoscry.logging

/**
 * What may never reach a log sink, and how it is replaced.
 *
 * Redaction is by **field name**, not by pattern-matching text. Imported documents and user questions
 * are arbitrary strings, and a regex that tries to recognise a secret inside them is both unreliable
 * and expensive; naming the field is a decision the caller can be held to. The names below are the
 * ones the product uses for material that is private by construction: credentials, the user's
 * questions, and the documents' text.
 *
 * Matching is exact after lower-casing and trimming, so `token` is sensitive while `token_count` and
 * `tokens` are not: over-redacting numbers would make the logs useless for diagnosing the very
 * features that must not leak content.
 */
object SensitiveFields {

    /** The text that replaces a sensitive value. Kept identical in every sink and in every mode. */
    const val PLACEHOLDER = "[REDACTED]"

    private val names = setOf(
        // Credentials and provider headers.
        "api_key",
        "apikey",
        "api-key",
        "authorization",
        "authorization_header",
        "auth_header",
        "bearer",
        "bearer_token",
        "access_token",
        "refresh_token",
        "password",
        "secret",
        "client_secret",
        // The user's own words.
        "question",
        "user_question",
        "prompt",
        "answer",
        // The documents' words.
        "excerpt",
        "document_excerpt",
        "document_text",
        "content",
    )

    /** Whether [name] designates material that must be redacted. */
    fun isSensitive(name: String): Boolean = name.trim().lowercase() in names

    /** The value to log for [name]: the value itself, or the placeholder when the name is sensitive. */
    fun redact(name: String, value: String): String = if (isSensitive(name)) PLACEHOLDER else value
}
