package dev.geminiquota.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalizes a session cookie obtained from WebView's CookieManager, a pasted
 * `Cookie:` request header, a browser's "Copy as cURL", or a Cookie-Editor /
 * DevTools JSON export. The result is a sensitive credential and is never logged.
 */
object CookieUtils {

    private val SESSION_MARKERS = listOf(
        "__Secure-1PSID=",
        "__Secure-3PSID=",
        "SAPISID=",
        "SID=",
    )

    /** Fields that appear in exports but must never be sent as cookies. */
    private val ATTRIBUTE_NAMES = setOf(
        "path", "domain", "expires", "expirationdate", "max-age", "secure",
        "httponly", "samesite", "hostonly", "host-only", "storeid", "partitionkey",
    )

    /** Cookies worth reporting back to the user in the paste dialog. */
    private val REPORTED_NAMES = listOf(
        "__Secure-1PSID", "__Secure-3PSID", "SID", "HSID", "SSID", "APISID", "SAPISID",
    )

    private val NAME_VALUE = Regex("^([^=;\\s]+)=(.*)$")

    data class CookieSummary(
        val count: Int,
        val hasSession: Boolean,
        val names: List<String>,
    ) {
        val isEmpty: Boolean get() = count == 0
    }

    fun normalize(raw: String?): String = pairs(raw)
        .entries
        .joinToString("; ") { (name, value) -> "$name=$value" }

    /** True when the input carries at least one Google session cookie. */
    fun looksLikeGeminiSession(raw: String?): Boolean = summary(raw).hasSession

    /** What the paste dialog shows while the user types. */
    fun summary(raw: String?): CookieSummary {
        val parsed = pairs(raw)
        val header = parsed.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        return CookieSummary(
            count = parsed.size,
            hasSession = parsed.isNotEmpty() &&
                SESSION_MARKERS.any { header.contains(it, ignoreCase = true) },
            names = parsed.keys.filter { key ->
                REPORTED_NAMES.any { it.equals(key, ignoreCase = true) }
            },
        )
    }

    private fun pairs(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for ((name, value) in parse(raw)) {
            if (name.lowercase() in ATTRIBUTE_NAMES) continue
            map[name] = value // the last occurrence wins
        }
        return map
    }

    private fun parse(raw: String): List<Pair<String, String>> =
        jsonPairs(raw) ?: textPairs(raw)

    /** Accepts the JSON array exported by Cookie-Editor and Chrome DevTools. */
    private fun jsonPairs(raw: String): List<Pair<String, String>>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return null
        return try {
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONArray().put(JSONObject(trimmed))
            }
            val pairs = mutableListOf<Pair<String, String>>()
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val name = entry.text("name", "Name")
                val domain = entry.text("domain", "host", "Host")
                if (name.isBlank()) continue
                // Exports can hold every cookie of every site; keep Google's only.
                if (domain.isNotBlank() && !domain.contains("google", ignoreCase = true)) continue
                pairs += name to entry.text("value", "Value")
            }
            pairs.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun textPairs(raw: String): List<Pair<String, String>> =
        stripCookiePrefix(raw)
            .split(';', '\n', '\r')
            .mapNotNull { part ->
                val cleaned = part.trim().trim('"', '\'')
                val match = NAME_VALUE.matchEntire(cleaned) ?: return@mapNotNull null
                val name = match.groupValues[1]
                // Trim quotes and any trailing cURL shell fragments after the value.
                val value = match.groupValues[2]
                    .trimStart()
                    .substringBefore(' ')
                    .trimEnd('"', '\'', '`', ',')
                // "$Version"-style attributes are not cookies.
                if (name.startsWith("$")) return@mapNotNull null
                name to value
            }

    /** Cuts a leading "Cookie:" or one embedded in a "Copy as cURL" command. */
    private fun stripCookiePrefix(raw: String): String {
        val text = raw.trim()
        val marker = text.indexOf("cookie:", ignoreCase = true)
        if (marker < 0) return text
        return text.substring(marker + "cookie:".length)
            .trim()
            .trimEnd('"', '\'')
    }

    private fun JSONObject.text(vararg keys: String): String =
        keys.firstOrNull { has(it) && !isNull(it) }
            ?.let { optString(it) }
            .orEmpty()
}
