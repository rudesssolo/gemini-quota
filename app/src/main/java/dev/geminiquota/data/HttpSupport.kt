package dev.geminiquota.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Chrome on Windows, which is what both web apps expect to talk to. */
internal const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

internal data class HttpResult(
    val code: Int,
    val body: String,
    val retryAfterSeconds: Long?,
    val finalHost: String?,
)

/**
 * Runs the call, translating transport failures into [UsageException.Network].
 * Shared by both providers so error handling cannot drift apart.
 */
internal fun OkHttpClient.execute(request: Request, clock: () -> Long): HttpResult = try {
    newCall(request).execute().use { response ->
        HttpResult(
            code = response.code,
            body = response.body?.string().orEmpty(),
            retryAfterSeconds = parseRetryAfterSeconds(response.header("Retry-After"), clock),
            finalHost = response.request.url.host,
        )
    }
} catch (e: IOException) {
    throw UsageException.Network(e)
}

internal fun HttpResult.requireSuccess(label: String) {
    if (code in 200..299) return
    if (code == 401 || code == 403) {
        throw UsageException.AuthRequired("$label rejected the session (HTTP $code)")
    }
    if (code == 429) throw UsageException.RateLimited(retryAfterSeconds)
    throw UsageException.Server(code)
}

/** Accepts both the seconds form and the RFC 1123 date form of `Retry-After`. */
private fun parseRetryAfterSeconds(value: String?, clock: () -> Long): Long? {
    if (value.isNullOrBlank()) return null
    value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0) }
    return try {
        val at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
        ((at - clock()) / 1_000L).coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}
