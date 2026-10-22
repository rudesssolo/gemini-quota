package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieUtilsTest {

    @Test
    fun `normalizes a pasted cookie header`() {
        val raw = "Cookie: SID=abc; HSID=def;\n__Secure-1PSID=ghi;"
        assertEquals("SID=abc; HSID=def; __Secure-1PSID=ghi", CookieUtils.normalize(raw))
    }

    @Test
    fun `drops fragments without a name value pair`() {
        val raw = "SID=abc; garbage; =nope; HSID=def"
        assertEquals("SID=abc; HSID=def", CookieUtils.normalize(raw))
    }

    @Test
    fun `recongises google session cookies`() {
        assertTrue(CookieUtils.looksLikeGeminiSession("SID=abc; HSID=def"))
        assertTrue(CookieUtils.looksLikeGeminiSession("__Secure-1PSID=abc"))
        assertTrue(CookieUtils.looksLikeGeminiSession("Cookie: SAPISID=abc; SID=def"))
        assertFalse(CookieUtils.looksLikeGeminiSession("foo=bar; baz=qux"))
        assertFalse(CookieUtils.looksLikeGeminiSession(""))
        assertFalse(CookieUtils.looksLikeGeminiSession(null))
    }

    @Test
    fun `blank input normalizes to empty`() {
        assertEquals("", CookieUtils.normalize("   "))
        assertEquals("", CookieUtils.normalize(null))
    }

    @Test
    fun `accepts a copy as curl command`() {
        val raw = "curl 'https://gemini.google.com/usage' \\\n" +
            "  -H 'cookie: SID=abc; HSID=def; SAPISID=ghi' \\\n" +
            "  -H 'x-goog-authuser: 0' --compressed"
        assertEquals("SID=abc; HSID=def; SAPISID=ghi", CookieUtils.normalize(raw))
        assertTrue(CookieUtils.looksLikeGeminiSession(raw))
    }

    @Test
    fun `accepts a cookie editor json export and skips other sites`() {
        val raw = """
            [
              {"domain": ".google.com", "name": "SID", "value": "abc", "path": "/", "secure": true},
              {"domain": ".google.com", "name": "__Secure-1PSID", "value": "def"},
              {"domain": ".example.com", "name": "tracker", "value": "zzz"}
            ]
        """.trimIndent()

        assertEquals("SID=abc; __Secure-1PSID=def", CookieUtils.normalize(raw))
        assertTrue(CookieUtils.summary(raw).hasSession)
    }

    @Test
    fun `accepts a cookie editor header string export from the phone`() {
        // Cookie-Editor's "Header String" export on Firefox for Android.
        val raw = "NID=511=abc; SID=g.a000xyz; __Secure-1PSID=g.a000abc; " +
            "__Secure-3PSID=g.a000def; HSID=QwErTy; SSID=AsDfGh; APISID=ZxCvBn; " +
            "SAPISID=PoIuYt; __Secure-1PAPISID=MnBvCx; AEC=AVQBc; SIDCC=ALmx"

        val summary = CookieUtils.summary(raw)

        assertTrue(summary.hasSession)
        assertEquals(11, summary.count)
        assertEquals(
            listOf("SID", "__Secure-1PSID", "__Secure-3PSID", "HSID", "SSID", "APISID", "SAPISID"),
            summary.names,
        )
        assertTrue(CookieUtils.normalize(raw).startsWith("NID=511=abc; SID=g.a000xyz"))
    }

    @Test
    fun `accepts a single cookie object export`() {
        val raw = """{"domain": ".google.com", "name": "SID", "value": "abc"}"""

        assertEquals("SID=abc", CookieUtils.normalize(raw))
        assertTrue(CookieUtils.looksLikeGeminiSession(raw))
    }

    @Test
    fun `keeps the last value for a duplicated name`() {
        assertEquals("SID=new; HSID=def", CookieUtils.normalize("SID=old; SID=new; HSID=def"))
    }

    @Test
    fun `summary reports parsed cookies and the session state`() {
        val summary = CookieUtils.summary("SID=a; HSID=b; NID=c")
        assertEquals(3, summary.count)
        assertTrue(summary.hasSession)
        assertEquals(listOf("SID", "HSID"), summary.names)
        assertFalse(summary.isEmpty)

        val empty = CookieUtils.summary("   ")
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.count)
        assertFalse(empty.hasSession)
    }

    @Test
    fun `flags a document cookie copy that lacks session cookies`() {
        val summary = CookieUtils.summary("NID=abc; SIDCC=def; AEC=ghi")
        assertEquals(3, summary.count)
        assertFalse(summary.hasSession)
        assertTrue(summary.names.isEmpty())
    }
}
