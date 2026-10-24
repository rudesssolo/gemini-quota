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
    fun `accepts a cookies wrapper export`() {
        val raw = """{"cookies":[{"name":"sessionKey","value":"$token"}]}"""
        assertEquals("sessionKey=$token", ClaudeCookies.normalize(raw))
    }

    @Test
    fun `lists the cookie names it found, never the values`() {
        val raw = """
            [
              {"name":"__cf_bm","value":"secret-1"},
              {"name":"intercom-device-id","value":"secret-2"},
              {"name":"lastActiveOrg","value":"secret-3"}
            ]
        """.trimIndent()
        assertEquals(
            listOf("__cf_bm", "intercom-device-id", "lastActiveOrg"),
            ClaudeCookies.cookieNames(raw),
        )
        assertFalse(ClaudeCookies.cookieNames(raw).any { it.contains("secret") })
    }

    @Test
    fun `lists names from a plain header string`() {
        val raw = "Cookie: __cf_bm=1; lastActiveOrg=xyz; intercom-device-id=2"
        assertEquals(
            listOf("__cf_bm", "lastActiveOrg", "intercom-device-id"),
            ClaudeCookies.cookieNames(raw),
        )
    }

    @Test
    fun `accepts a netscape cookies file`() {
        val raw = listOf(
            "# Netscape HTTP Cookie File",
            ".claude.ai\tTRUE\t/\tTRUE\t1790000000\t__cf_bm\tcf-value",
            "#HttpOnly_.claude.ai\tTRUE\t/\tTRUE\t1790000000\tsessionKey\t$token",
        ).joinToString("\n")

        assertEquals("sessionKey=$token", ClaudeCookies.normalize(raw))
        assertTrue(ClaudeCookies.looksLikeClaudeSession(raw))
        assertEquals("claude.ai", ClaudeCookies.exportDomain(raw))
        assertEquals(listOf("__cf_bm", "sessionKey"), ClaudeCookies.cookieNames(raw))
        assertTrue(ClaudeCookies.diagnostics(raw).contains("shape=netscape"))
    }

    @Test
    fun `a tab in ordinary text is not mistaken for a netscape file`() {
        assertNull(ClaudeCookies.normalize("some\ttext\twith\ttabs"))
    }

    @Test
    fun `cookie header keeps every claude cookie`() {
        val raw = "sessionKey=$token; __cf_bm=cf; lastActiveOrg=org-1"
        assertEquals(raw, ClaudeCookies.cookieHeader(raw))
    }

    @Test
    fun `cookie header drops cookies from other sites in a json export`() {
        val raw = """[
            {"domain":".claude.ai","name":"sessionKey","value":"$token"},
            {"domain":".claude.ai","name":"__cf_bm","value":"cf"},
            {"domain":".chatgpt.com","name":"oai-did","value":"nope"}
        ]"""
        assertEquals("sessionKey=$token; __cf_bm=cf", ClaudeCookies.cookieHeader(raw))
    }

    @Test
    fun `cookie header falls back to the bare token`() {
        assertEquals("sessionKey=$token", ClaudeCookies.cookieHeader(token))
    }

    @Test
    fun `cookie header ignores attribute keys`() {
        val raw = "sessionKey=$token; path=/; domain=.claude.ai; secure"
        assertEquals("sessionKey=$token", ClaudeCookies.cookieHeader(raw))
    }

    @Test
    fun `reports which site a json export came from`() {
        val chatgpt = """[{"domain":".chatgpt.com","name":"oai-did","value":"x"}]"""
        val claude = """[{"domain":".claude.ai","name":"sessionKey","value":"$token"}]"""
        val wrapped = """{"cookies":[{"host":"example.org","name":"a","value":"b"}]}"""

        assertEquals("chatgpt.com", ClaudeCookies.exportDomain(chatgpt))
        assertEquals("claude.ai", ClaudeCookies.exportDomain(claude))
        assertEquals("example.org", ClaudeCookies.exportDomain(wrapped))
        assertNull(ClaudeCookies.exportDomain("Cookie: a=b"))
        assertNull(ClaudeCookies.exportDomain(null))
    }

    @Test
    fun `diagnostics describe a paste without leaking values`() {
        val raw = """
            [{"domain":".claude.ai","name":"sessionKey","value":"$token"},
             {"domain":".claude.ai","name":"__cf_bm","value":"cf-secret"}]
        """.trimIndent()

        val report = ClaudeCookies.diagnostics(raw)

        assertTrue(report.startsWith("gemini-quota diag v1"))
        assertTrue(report.contains("site=claude.ai"))
        assertTrue(report.contains("names=sessionKey,__cf_bm"))
        assertTrue(report.contains("sessionKey=true"))
        assertTrue(report.contains("shape=json"))
        assertFalse(report.contains(token))
        assertFalse(report.contains("cf-secret"))
    }

    @Test
    fun `diagnostics point at the wrong site`() {
        val report = ClaudeCookies.diagnostics(
            """[{"domain":".chatgpt.com","name":"oai-did","value":"x"}]""",
        )
        assertTrue(report.contains("site=chatgpt.com"))
        assertTrue(report.contains("sessionKey=false"))
        assertTrue(report.contains("names=oai-did"))
    }

    @Test
    fun `a google cookie paste is not mistaken for a claude session`() {
        assertFalse(ClaudeCookies.looksLikeClaudeSession("SID=abc; __Secure-1PSID=xyz"))
        assertTrue(ClaudeCookies.looksLikeClaudeSession(token))
    }
}
