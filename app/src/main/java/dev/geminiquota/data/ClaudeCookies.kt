package dev.geminiquota.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulls the claude.ai `sessionKey` cookie out of whatever the user managed to copy.
 *
 * claude.ai does not block embedded browsers the way Google does, but the same
 * one-tap import path has to keep working, so this accepts a bare token, a
 * `Cookie:` header, a cURL command, or a Cookie-Editor JSON export.
 */
object ClaudeCookies {

    const val COOKIE_NAME = "sessionKey"

    private const val TOKEN_PREFIX = "sk-ant-"

    private val SESSION_KEY = Regex(
        """(?:^|[;,\s'"])sessionKey=([^;,\s'"]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** A ready-to-send `Cookie` header, or null when no session key is present. */
    fun normalize(raw: String?): String? = sessionKey(raw)?.let { "$COOKIE_NAME=$it" }

    fun looksLikeClaudeSession(raw: String?): Boolean = sessionKey(raw) != null

    private fun sessionKey(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        jsonSessionKey(text)?.let { return it }

        // Netscape / cookies.txt export: tab separated, name in column 7.
        netscapeRows(text)?.let { rows ->
            rows.firstOrNull { it[5].equals(COOKIE_NAME, ignoreCase = true) }
                ?.get(6)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        SESSION_KEY.find(text)?.groupValues?.get(1)?.trimEnd('"', '\'')?.let { return it }

        // The bare value: cookie tables let people copy just the value cell.
        if (!text.contains(COOKIE_NAME, ignoreCase = true)) {
            val candidate = text.lineSequence()
                .map { it.trim().trim('"', '\'', ';') }
                .firstOrNull { it.startsWith(TOKEN_PREFIX, ignoreCase = true) }
            if (candidate != null) return candidate
        }
        return null
    }

    /**
     * Rows of a Netscape `cookies.txt` export:
     * `#HttpOnly_.claude.ai\tTRUE\t/\tTRUE\t1790000000\tsessionKey\tsk-ant-…`
     *
     * Returns null unless every data line has the full seven columns, so ordinary
     * text pastes are not mistaken for this format.
     */
    private fun netscapeRows(text: String): List<List<String>>? {
        if (!text.contains('\t')) return null
        val rows = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("# ") }
            .map { it.split('\t') }
            .toList()
        return rows.takeIf { lines -> lines.isNotEmpty() && lines.all { it.size >= 7 } }
    }

    private fun jsonSessionKey(text: String): String? =
        jsonArray(text)
            ?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { it.field("name").equals(COOKIE_NAME, ignoreCase = true) }
                    ?.field("value")
                    ?.takeIf { it.isNotBlank() }
            }

    /**
     * Cookie names present in the paste, for the dialog's diagnostics. Values are
     * never returned, so this is safe to show on screen.
     */
    fun cookieNames(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()

        jsonArray(text)?.let { array ->
            return (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.field("name") }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_DIAGNOSTIC_NAMES)
        }
        netscapeRows(text)?.let { rows ->
            return rows.map { it[5].trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_DIAGNOSTIC_NAMES)
        }

        val marker = text.indexOf("cookie:", ignoreCase = true)
        val body = if (marker >= 0) text.substring(marker + "cookie:".length).trim() else text
        return body.split(';', '\n', '\r')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "").trim().trim('"', '\'', ' ')
                name.takeIf { it.isNotEmpty() && PLAIN_NAME.matches(it) }
            }
            .distinct()
            .take(MAX_DIAGNOSTIC_NAMES)
    }

    /** Accepts a bare array, a single cookie object, or a {"cookies": [...]} wrapper. */
    private fun jsonArray(text: String): JSONArray? {
        if (!text.startsWith("[") && !text.startsWith("{")) return null
        return runCatching {
            if (text.startsWith("[")) {
                JSONArray(text)
            } else {
                val obj = JSONObject(text)
                obj.optJSONArray("cookies") ?: JSONArray().put(obj)
            }
        }.getOrNull()
    }

    /**
     * The site a JSON export came from, taken from its `domain`/`host` field.
     * Used to tell the user "you exported from the wrong tab" — names only.
     */
    fun exportDomain(raw: String?): String? {
        val text = raw?.trim().orEmpty()

        netscapeRows(text)?.firstOrNull()?.let { row ->
            return row[0].removePrefix("#HttpOnly_").trimStart('.').takeIf { it.isNotBlank() }
        }

        val array = jsonArray(text) ?: return null
        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .mapNotNull { entry ->
                val domain = entry.field("domain").ifBlank { entry.field("host") }
                domain.trim().trimStart('.').takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    /**
     * A one-line, value-free description of a paste, meant to be copied and sent
     * by the user when a cookie is not recognised. Contains the site, the cookie
     * names, whether the shapes matched, and the length — never a cookie value.
     */
    fun diagnostics(raw: String?): String {
        val text = raw?.trim().orEmpty()
        val names = cookieNames(text)
        val looksJson = text.startsWith("[") || text.startsWith("{")
        return buildString {
            append("gemini-quota diag v1")
            append(" | site=").append(exportDomain(text) ?: "-")
            append(" | names=").append(names.joinToString(",").ifEmpty { "-" })
            append(" | sessionKey=").append(looksLikeClaudeSession(text))
            append(" | shape=").append(
                when {
                    looksJson -> "json"
                    netscapeRows(text) != null -> "netscape"
                    text.contains('	') -> "tabbed"
                    else -> "text"
                },
            )
            append(" | google=").append(CookieUtils.summary(text).hasSession)
            append(" | chars=").append(text.length)
        }
    }

    /** JSON exports use `name`/`value`; some tools capitalise them. */
    private fun JSONObject.field(key: String): String =
        optString(key).ifBlank { optString(key.replaceFirstChar { it.uppercase() }) }

    private const val MAX_DIAGNOSTIC_NAMES = 8

    private val PLAIN_NAME = Regex("^[A-Za-z0-9_.-]+$")

}
