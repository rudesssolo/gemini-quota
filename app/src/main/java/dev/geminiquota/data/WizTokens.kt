package dev.geminiquota.data

/** Bootstrap values embedded in the Gemini web page and required by batchexecute. */
data class WizTokens(
    val requestToken: String,
    val buildLabel: String?,
    val sessionId: String?,
    val locale: String?,
)

object WizTokenExtractor {

    private val REQUEST_TOKEN = Regex("\"SNlM0e\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val BUILD_LABEL = Regex("\"cfb2h\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val SESSION_ID = Regex("\"FdrFJe\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val LOCALE = Regex("\"GGcqce\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    fun extract(html: String): WizTokens? {
        val token = REQUEST_TOKEN.find(html)?.groupValues?.getOrNull(1)?.let(::unescape)
        if (token.isNullOrBlank()) return null
        return WizTokens(
            requestToken = token,
            buildLabel = BUILD_LABEL.find(html)?.groupValues?.getOrNull(1)?.let(::unescape),
            sessionId = SESSION_ID.find(html)?.groupValues?.getOrNull(1)?.let(::unescape),
            locale = LOCALE.find(html)?.groupValues?.getOrNull(1)?.let(::unescape),
        )
    }

    /** Minimal JSON-string unescape for the WIZ_global_data literals. */
    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '\\' || index + 1 >= raw.length) {
                out.append(char)
                index++
                continue
            }
            when (val escaped = raw[index + 1]) {
                'u' -> {
                    val end = minOf(index + 6, raw.length)
                    val hex = raw.substring(index + 2, end)
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 4 && code != null) {
                        out.append(code.toChar())
                        index += 6
                    } else {
                        out.append(escaped)
                        index += 2
                    }
                }

                'n' -> {
                    out.append('\n')
                    index += 2
                }

                'r' -> {
                    out.append('\r')
                    index += 2
                }

                't' -> {
                    out.append('\t')
                    index += 2
                }

                '"', '\\', '/' -> {
                    out.append(escaped)
                    index += 2
                }

                else -> {
                    out.append(escaped)
                    index += 2
                }
            }
        }
        return out.toString()
    }
}
