package dev.geminiquota.data

/**
 * Errors the UI and widget can distinguish without parsing messages.
 *
 * Provider neutral on purpose: Gemini and Claude fail in the same few ways, and
 * the dashboard, the widget and the scheduler should not care which service
 * produced the failure.
 */
sealed class UsageException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The stored session is missing, expired or rejected by the service. */
    class AuthRequired(message: String = "The saved session is missing or expired") :
        UsageException(message)

    /** The service asked us to slow down. */
    class RateLimited(val retryAfterSeconds: Long? = null) :
        UsageException("The service is rate limiting usage requests")

    /** The service responded, but not with the shape we know. */
    class ProtocolChanged(message: String, val detail: String? = null) :
        UsageException(message)

    /** The account belongs to no organization we can read usage from. */
    class OrgNotFound(message: String = "No organization was reported for this account") :
        UsageException(message)

    /** Non-2xx response that is not auth or rate limiting. */
    class Server(val statusCode: Int) :
        UsageException("The service returned HTTP $statusCode")

    /** Socket/DNS/TLS failure. */
    class Network(cause: Throwable) :
        UsageException("Network error: ${cause.message}", cause)
}

/** Short, local-only echo of a response used by the in-app diagnostics dialog. */
internal fun responseSnippet(body: String, maxLength: Int = 320): String {
    val compact = body.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength) + "…"
}
