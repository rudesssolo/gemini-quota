package dev.geminiquota.data

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaPageCodecTest {

    private val page = QuotaPage(
        provider = ProviderId.CLAUDE,
        tier = null,
        windows = listOf(
            QuotaWindow(QuotaWindowId.FIVE_HOUR, 42, 1_761_260_400_000L),
            QuotaWindow(QuotaWindowId.WEEKLY, 24, null),
            QuotaWindow(QuotaWindowId.WEEKLY_OPUS, 5, 1_761_600_000_000L),
        ),
        fetchedAtEpochMs = 1_790_000_000_000L,
        source = ClaudeUsageParser.SOURCE,
    )

    @Test
    fun `round trips a page`() {
        val decoded = QuotaPageCodec.decode(QuotaPageCodec.encode(page))
        assertEquals(page, decoded)
    }

    @Test
    fun `keeps a gemini tier`() {
        val gemini = page.copy(provider = ProviderId.GEMINI, tier = "TIER1", source = "GRAPHQL")
        val decoded = QuotaPageCodec.decode(QuotaPageCodec.encode(gemini))
        assertEquals("TIER1", decoded?.tier)
        assertEquals(ProviderId.GEMINI, decoded?.provider)
        assertEquals(3, decoded?.windows?.size)
    }

    @Test
    fun `an empty page survives, a plan may report no windows`() {
        val empty = page.copy(windows = emptyList())
        val decoded = QuotaPageCodec.decode(QuotaPageCodec.encode(empty))
        assertTrue(decoded?.windows?.isEmpty() == true)
        assertEquals(empty.fetchedAtEpochMs, decoded?.fetchedAtEpochMs)
    }

    @Test
    fun `rejects missing, unknown or malformed payloads`() {
        assertNull(QuotaPageCodec.decode(null))
        assertNull(QuotaPageCodec.decode(""))
        assertNull(QuotaPageCodec.decode("not json"))
        assertNull(QuotaPageCodec.decode("""{"version":1,"provider":"claude"}"""))
        assertNull(QuotaPageCodec.decode("""{"version":99,"provider":"claude","fetchedAt":1}"""))
        assertNull(QuotaPageCodec.decode("""{"version":1,"provider":"openai","fetchedAt":1}"""))
    }

    @Test
    fun `skips windows it does not understand`() {
        val raw = JSONObject()
            .put("version", 1)
            .put("provider", "claude")
            .put("fetchedAt", 1_790_000_000_000L)
            .put("source", "test")
            .put(
                "windows",
                org.json.JSONArray()
                    .put(JSONObject().put("id", "FIVE_HOUR").put("percent", 10))
                    .put(JSONObject().put("id", "NOT_A_WINDOW").put("percent", 20)),
            )
            .toString()

        val decoded = QuotaPageCodec.decode(raw)
        assertEquals(1, decoded?.windows?.size)
        assertEquals(QuotaWindowId.FIVE_HOUR, decoded?.windows?.first()?.id)
    }
}
