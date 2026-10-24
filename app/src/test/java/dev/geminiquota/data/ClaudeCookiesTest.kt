package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCookiesTest {

    private val token = "sk-ant-sid01-abcDEF123456"

    @Test
    fun `accepts the bare cookie value`() {
        assertEquals("sessionKey=$token", ClaudeCookies.normalize(token))
        assertEquals("sessionKey=$token", ClaudeCookies.normalize("  $token\n"))
    }

    @Test
    fun `accepts a cookie header`() {
        val raw = "Cookie: intercom-device-id=1; sessionKey=$token; lastActiveOrg=xyz"
        assertEquals("sessionKey=$token", ClaudeCookies.normalize(raw))
    }

    @Test
    fun `accepts a copy as curl command`() {
        val raw = "curl 'https://claude.ai/api/organizations' \\\n" +
            "  -H 'cookie: sessionKey=$token' \\\n" +
            "  --compressed"
        assertEquals("sessionKey=$token", ClaudeCookies.normalize(raw))
    }

    @Test
    fun `accepts a cookie editor json export`() {
        val raw = """
            [
              {"domain": ".claude.ai", "name": "intercom-device-id", "value": "zzz"},
              {"domain": ".claude.ai", "name": "sessionKey", "value": "$token"}
            ]
        """.trimIndent()
        assertEquals("sessionKey=$token", ClaudeCookies.normalize(raw))
    }

    @Test
    fun `rejects a copy without the session key`() {
        assertNull(ClaudeCookies.normalize("intercom-device-id=1; lastActiveOrg=xyz"))
        assertNull(ClaudeCookies.normalize(""))
        assertNull(ClaudeCookies.normalize(null))
        assertFalse(ClaudeCookies.looksLikeClaudeSession("NID=abc; HSID=def"))
    }

    @Test
    fun `a google cookie paste is not mistaken for a claude session`() {
        assertFalse(ClaudeCookies.looksLikeClaudeSession("SID=abc; __Secure-1PSID=xyz"))
        assertTrue(ClaudeCookies.looksLikeClaudeSession(token))
    }
}
