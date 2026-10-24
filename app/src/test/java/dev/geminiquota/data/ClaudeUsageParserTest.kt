package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClaudeUsageParserTest {

    private val now = 1_790_000_000_000L

    private val body = """
        {
          "five_hour":   {"utilization": 17.0, "resets_at": "2026-02-08T18:59:59Z"},
          "seven_day":   {"utilization": 11.0, "resets_at": "2026-02-14T16:59:59Z"},
          "seven_day_opus": {"utilization": 5.0, "resets_at": "2026-02-14T16:59:59Z"},
          "seven_day_oauth_apps": null,
          "extra_usage": null
        }
    """.trimIndent()

    @Test
    fun `shape reports keys and types without values`() {
        val body = """{"five_hour":null,"seven_day":{"utilization":42.0,"resets_at":"2026-10-27T10:10:00Z"},"extra_usage":{"is_enabled":false}}"""

        assertEquals(
            "extra_usage{is_enabled}, five_hour=null, seven_day{resets_at,utilization}",
            ClaudeUsageParser.shape(body),
        )
        assertTrue(ClaudeUsageParser.shape(body).none { it.isDigit() })
    }

    @Test
    fun `shape survives a non json body`() {
        assertEquals("not-json", ClaudeUsageParser.shape("<html>nope</html>"))
    }

    @Test
    fun `parses all reported windows`() {
        val page = ClaudeUsageParser.parse(body, now)

        assertEquals(ProviderId.CLAUDE, page.provider)
        assertEquals(3, page.windows.size)
        assertEquals(17, page.window(QuotaWindowId.FIVE_HOUR)?.usedPercent)
        assertEquals(11, page.window(QuotaWindowId.WEEKLY)?.usedPercent)
        assertEquals(5, page.window(QuotaWindowId.WEEKLY_OPUS)?.usedPercent)
        assertEquals(now, page.fetchedAtEpochMs)
        assertEquals(ClaudeUsageParser.SOURCE, page.source)
    }

    @Test
    fun `converts iso timestamps to epoch millis`() {
        val page = ClaudeUsageParser.parse(body, now)
        assertEquals(
            Instant.parse("2026-02-08T18:59:59Z").toEpochMilli(),
            page.window(QuotaWindowId.FIVE_HOUR)?.resetAtEpochMs,
        )
    }

    @Test
    fun `accepts an offset instead of a zulu timestamp`() {
        val raw = """{"five_hour":{"utilization":1,"resets_at":"2026-02-08T18:59:59+02:00"}}"""
        val page = ClaudeUsageParser.parse(raw, now)
        assertEquals(
            Instant.parse("2026-02-08T16:59:59Z").toEpochMilli(),
            page.window(QuotaWindowId.FIVE_HOUR)?.resetAtEpochMs,
        )
    }

    @Test
    fun `a null reset time stays null`() {
        val raw = """{"five_hour":{"utilization":1.5,"resets_at":null}}"""
        val page = ClaudeUsageParser.parse(raw, now)
        assertNull(page.window(QuotaWindowId.FIVE_HOUR)?.resetAtEpochMs)
    }

    @Test
    fun `rounds percentages and clamps them`() {
        val raw = """
            {"five_hour":{"utilization":17.6},"seven_day":{"utilization":140},
             "seven_day_opus":{"utilization":-3}}
        """.trimIndent()
        val page = ClaudeUsageParser.parse(raw, now)
        assertEquals(18, page.window(QuotaWindowId.FIVE_HOUR)?.usedPercent)
        assertEquals(100, page.window(QuotaWindowId.WEEKLY)?.usedPercent)
        assertEquals(0, page.window(QuotaWindowId.WEEKLY_OPUS)?.usedPercent)
    }

    @Test
    fun `skips windows the plan does not have`() {
        val raw = """
            {"five_hour":{"utilization":3},"seven_day":{"utilization":4},
             "seven_day_opus":null,"seven_day_sonnet":null}
        """.trimIndent()
        val page = ClaudeUsageParser.parse(raw, now)
        assertEquals(2, page.windows.size)
        assertNull(page.window(QuotaWindowId.WEEKLY_OPUS))
        assertTrue(page.hasAnyWindow)
    }

    @Test
    fun `worst percent drives the glanceable state`() {
        val page = ClaudeUsageParser.parse(body, now)
        assertEquals(17, page.worstPercent)
        assertEquals(
            Instant.parse("2026-02-08T18:59:59Z").toEpochMilli(),
            page.nextResetAtEpochMs(),
        )
    }

    @Test
    fun `rejects a payload that shares no known field`() {
        val error = assertThrows(UsageException.ProtocolChanged::class.java) {
            ClaudeUsageParser.parse("""{"quota":{"used":1}}""", now)
        }
        assertEquals("Claude usage response had no quota windows", error.message)
    }

    @Test
    fun `a plan without quota windows is a valid answer`() {
        val raw = """
            {"five_hour":null,"seven_day":null,"seven_day_opus":null,
             "seven_day_oauth_apps":null,"extra_usage":null}
        """.trimIndent()

        val page = ClaudeUsageParser.parse(raw, now)

        assertTrue(page.windows.isEmpty())
        assertFalse(page.hasAnyWindow)
        assertEquals(0, page.worstPercent)
        assertNull(page.nextResetAtEpochMs())
    }

    @Test
    fun `rejects a window without utilization`() {
        val error = assertThrows(UsageException.ProtocolChanged::class.java) {
            ClaudeUsageParser.parse("""{"five_hour":{"resets_at":null}}""", now)
        }
        assertEquals("Claude usage field five_hour had no utilization", error.message)
    }

    @Test
    fun `rejects invalid json and keeps a snippet`() {
        val error = assertThrows(UsageException.ProtocolChanged::class.java) {
            ClaudeUsageParser.parse("<html>nope</html>", now)
        }
        assertEquals("Claude usage response was not valid JSON", error.message)
        assertEquals("<html>nope</html>", error.detail)
    }
}
