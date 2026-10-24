package dev.geminiquota.data

/**
 * Decides which provider a pasted export is meant for.
 *
 * A claude.ai `sessionKey` wins over a Google marker: a paste can carry both
 * (Google's sign-in widget leaves `g_state` behind on other sites), and sending
 * Claude cookies to the Gemini client produced a confusing dead end. The Gemini
 * session is stored separately, so preferring Claude here breaks nothing.
 */
object PasteRouting {

    fun target(raw: String?): ProviderId? = when {
        ClaudeCookies.cookieHeader(raw) != null -> ProviderId.CLAUDE
        CookieUtils.looksLikeGeminiSession(raw) -> ProviderId.GEMINI
        else -> null
    }
}
