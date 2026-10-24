package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class GraphqlUsageParserTest {

    private val fetchedAt = 1_790_000_000_000L

    private val successBody = """
        [{"data":{"viewer":{"rpcs":{"getUsageInfo":{
          "subscriptionTier":"TIER1",
          "pools":[
            {"poolType":"GXU_FIVE_HOURLY","percentageUsed":0.42,
             "gxu":{"windowRefreshesAt":"2026-10-22T23:00:00Z"}},
            {"poolType":"GXU_WEEKLY","percentageUsed":0.10,
             "gxu":{"windowRefreshesAt":"2026-10-26T00:00:00.123Z"}}
          ]}}}}}]
    """.trimIndent()

    @Test
    fun `parses both windows, tier and reset times`() {
        val snapshot = GraphqlUsageParser.parse(successBody, fetchedAt)

        assertEquals(UsageSource.GRAPHQL, snapshot.source)
        assertEquals(fetchedAt, snapshot.fetchedAtEpochMs)
        assertEquals("TIER1", snapshot.tier)
        assertEquals(42, snapshot.fiveHour?.usedPercent)
        assertEquals(
            Instant.parse("2026-10-22T23:00:00Z").toEpochMilli(),
            snapshot.fiveHour?.resetAtEpochMs,
        )
        assertEquals(10, snapshot.weekly?.usedPercent)
        assertEquals(
            Instant.parse("2026-10-26T00:00:00.123Z").toEpochMilli(),
            snapshot.weekly?.resetAtEpochMs,
        )
        assertEquals(PlanTier.AI_PRO, PlanTier.fromRaw(snapshot.tier))
    }

    @Test
    fun `strips the xssi guard prefix`() {
        val snapshot = GraphqlUsageParser.parse(")]}'\n$successBody", fetchedAt)
        assertEquals(42, snapshot.fiveHour?.usedPercent)
    }

    @Test
    fun `clamps out of range percentages`() {
        val body = """
            [{"data":{"viewer":{"rpcs":{"getUsageInfo":{"pools":[
              {"poolType":"GXU_FIVE_HOURLY","percentageUsed":1.7}
            ]}}}}}]
        """.trimIndent()

        val snapshot = GraphqlUsageParser.parse(body, fetchedAt)
        assertEquals(100, snapshot.fiveHour?.usedPercent)
        assertNull(snapshot.fiveHour?.resetAtEpochMs)
        assertNull(snapshot.weekly)
    }

    @Test
    fun `null viewer means the session expired`() {
        val body = """[{"data":{"viewer":null}}]"""
        assertThrows(UsageException.AuthRequired::class.java) {
            GraphqlUsageParser.parse(body, fetchedAt)
        }
    }

    @Test
    fun `empty body means the session expired`() {
        assertThrows(UsageException.AuthRequired::class.java) {
            GraphqlUsageParser.parse("", fetchedAt)
        }
    }

    @Test
    fun `html body means a sign-in page`() {
        assertThrows(UsageException.AuthRequired::class.java) {
            GraphqlUsageParser.parse("<!doctype html><html></html>", fetchedAt)
        }
    }

    @Test
    fun `missing usage info is a protocol change`() {
        val body = """[{"data":{"viewer":{"rpcs":{}}}}]"""
        val error = assertThrows(UsageException.ProtocolChanged::class.java) {
            GraphqlUsageParser.parse(body, fetchedAt)
        }
        assertEquals("Gemini usage response had no getUsageInfo", error.message)
    }

    @Test
    fun `unknown pools are a protocol change`() {
        val body = """
            [{"data":{"viewer":{"rpcs":{"getUsageInfo":{"pools":[
              {"poolType":"GXU_SOMETHING_NEW","percentageUsed":0.2}
            ]}}}}}]
        """.trimIndent()
        assertThrows(UsageException.ProtocolChanged::class.java) {
            GraphqlUsageParser.parse(body, fetchedAt)
        }
    }

    @Test
    fun `invalid json is a protocol change with a snippet`() {
        val error = assertThrows(UsageException.ProtocolChanged::class.java) {
            GraphqlUsageParser.parse("not json at all", fetchedAt)
        }
        assertEquals("Gemini usage response was not valid JSON", error.message)
        assertNotNull(error.detail)
    }
}
