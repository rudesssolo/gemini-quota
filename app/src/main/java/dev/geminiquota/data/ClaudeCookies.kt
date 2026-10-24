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

    private fun jsonSessionKey(text: String): String? {
        if (!text.startsWith("[") && !text.startsWith("{")) return null
        return runCatching {
            val array = if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .firstOrNull {
                    it.optString("name").equals(COOKIE_NAME, ignoreCase = true)
                }
                ?.optString("value")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

}
