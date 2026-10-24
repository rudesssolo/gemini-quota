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

    /**
     * The full claude.ai `Cookie` header, not only `sessionKey`.
     *
     * The usage endpoint sits behind Cloudflare, which also inspects `__cf_bm` /
     * `cf_clearance`, and `lastActiveOrg` helps the API pick the organization, so
     * forwarding every cookie of the export is more faithful to the browser than
     * sending the session key alone. Returns null when no session key is present.
     */
    fun cookieHeader(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val key = sessionKey(text) ?: return null

        val pairs = (jsonPairs(text) ?: netscapePairs(text) ?: textPairs(text))
            .filter { (name, _) -> name.isNotBlank() && name.lowercase() !in ATTRIBUTE_NAMES }
        val header = pairs.joinToString("; ") { (name, value) -> "$name=$value" }

        return when {
            header.isEmpty() -> "$COOKIE_NAME=$key"
            pairs.any { (name, _) -> name.equals(COOKIE_NAME, ignoreCase = true) } -> header
            else -> "$COOKIE_NAME=$key; $header"
        }
    }

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

    /** claude.ai cookies from a JSON export; other sites are dropped. */
    private fun jsonPairs(text: String): List<Pair<String, String>>? =
        jsonArray(text)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val name = entry.field("name")
                val domain = entry.field("domain").ifBlank { entry.field("host") }
                if (name.isBlank()) return@mapNotNull null
                if (domain.isNotBlank() && !domain.contains("claude", ignoreCase = true)) {
                    return@mapNotNull null
                }
                name to entry.field("value")
            }.ifEmpty { null }
        }

    private fun netscapePairs(text: String): List<Pair<String, String>>? =
        netscapeRows(text)?.map { row -> row[5].trim() to row[6].trim() }

    /** `name=value; name=value`, a `Cookie:` header, or a cURL command. */
    private fun textPairs(text: String): List<Pair<String, String>> {
        val marker = text.indexOf("cookie:", ignoreCase = true)
        val body = if (marker >= 0) text.substring(marker + "cookie:".length) else text
        return body.trim().trimEnd('"', '\'')
            .split(';', '\n', '\r')
            .mapNotNull { part ->
                val match = NAME_VALUE.matchEntire(part.trim().trim('"', '\''))
                    ?: return@mapNotNull null
                val name = match.groupValues[1]
                // "$Version"-style attributes are not cookies.
                if (name.startsWith("$")) return@mapNotNull null
                val value = match.groupValues[2]
                    .trimStart()
                    .substringBefore(' ')
                    .trimEnd('"', '\'', '`', ',')
                name to value
            }
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

    /** Fields that appear in exports but must never be sent as cookies. */
    private val ATTRIBUTE_NAMES = setOf(
        "path", "domain", "expires", "expirationdate", "max-age", "secure",
        "httponly", "samesite", "hostonly", "host-only", "storeid", "partitionkey",
    )

    private val NAME_VALUE = Regex("^([^=;\\s]+)=(.*)$")

}
