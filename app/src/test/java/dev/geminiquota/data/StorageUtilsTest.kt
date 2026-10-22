package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

class SnapshotCodecTest {

    @Test
    fun `round trips a snapshot`() {
        val snapshot = UsageSnapshot(
            fiveHour = UsageWindow(0.42, 1_761_260_400_000L),
            weekly = UsageWindow(0.10, null),
            tier = "TIER1",
            fetchedAtEpochMs = 1_790_000_000_000L,
            source = UsageSource.GRAPHQL,
        )

        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round trips without a weekly window or tier`() {
        val snapshot = UsageSnapshot(
            fiveHour = UsageWindow(0.0, null),
            weekly = null,
            tier = null,
            fetchedAtEpochMs = 1_790_000_000_000L,
            source = UsageSource.BATCHEXECUTE,
        )

        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `rejects missing or malformed payloads`() {
        assertNull(SnapshotCodec.decode(null))
        assertNull(SnapshotCodec.decode(""))
        assertNull(SnapshotCodec.decode("{}"))
        assertNull(SnapshotCodec.decode("not json"))
        assertNull(
            SnapshotCodec.decode(
                """{"version":1,"fetchedAt":1,"source":"GRAPHQL"}""",
            ),
        )
    }

    @Test
    fun `treats a zero reset as unknown`() {
        val snapshot = UsageSnapshot(
            fiveHour = UsageWindow(0.5, 0L),
            weekly = null,
            tier = null,
            fetchedAtEpochMs = 1_790_000_000_000L,
            source = UsageSource.GRAPHQL,
        )
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertNull(decoded?.fiveHour?.resetAtEpochMs)
    }
}
