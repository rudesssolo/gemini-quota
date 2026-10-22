package dev.geminiquota.data

/**
 * Normalizes a Cookie header coming either from WebView's CookieManager or from
 * a manual paste. The header is a sensitive credential and is never logged.
 */
object CookieUtils {

    private val SESSION_MARKERS = listOf(
        "__Secure-1PSID=",
        "__Secure-3PSID=",
        "SAPISID=",
        "SID=",
    )

    private val COOKIE_NAME = Regex("^[^=;\\s]+=")

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var value = raw.trim()
        if (value.startsWith("cookie:", ignoreCase = true)) {
            value = value.substringAfter(':')
        }
        return value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .split(';')
            .map { it.trim() }
            .filter { part ->
                part.isNotEmpty() &&
                    COOKIE_NAME.containsMatchIn(part) &&
                    !part.contains(' ') // drop folded/garbage fragments
            }
            .joinToString("; ")
    }

    /** True when the header carries at least one Google session cookie. */
    fun looksLikeGeminiSession(raw: String?): Boolean {
        val cookie = normalize(raw)
        if (cookie.isEmpty()) return false
        return SESSION_MARKERS.any { marker -> cookie.contains(marker, ignoreCase = true) }
    }
}
