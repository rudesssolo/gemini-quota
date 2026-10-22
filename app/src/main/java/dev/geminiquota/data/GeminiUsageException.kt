package dev.geminiquota.data

/** Errors the UI and widget can distinguish without parsing messages. */
sealed class GeminiUsageException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The stored session is missing, expired or rejected by Google. */
    class AuthRequired(message: String = "Gemini session is missing or expired") :
        GeminiUsageException(message)

    /** Google asked us to slow down. */
    class RateLimited(val retryAfterSeconds: Long? = null) :
        GeminiUsageException("Gemini is rate limiting usage requests")

    /** Gemini responded, but not with the shape we know. */
    class ProtocolChanged(message: String, val detail: String? = null) :
        GeminiUsageException(message)

    /** Non-2xx response that is not auth or rate limiting. */
    class Server(val statusCode: Int) :
        GeminiUsageException("Gemini returned HTTP $statusCode")

    /** Socket/DNS/TLS failure. */
    class Network(cause: Throwable) :
        GeminiUsageException("Network error: ${cause.message}", cause)
}

/** Short, local-only echo of a response used by the in-app diagnostics dialog. */
internal fun responseSnippet(body: String, maxLength: Int = 320): String {
    val compact = body.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength) + "…"
}
