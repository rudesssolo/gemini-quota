package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchexecuteUsageParserTest {

    private val fetchedAt = 1_790_000_000_000L

    private val payload =
        """[null,[[0,0.42,1,[[1761260400,0]]],[0,0.1,2,[[1761606000,500000]]]]]"""

    private val response = """
        )]}'

        123
        [["wrb.fr","jSf9Qc","$payload",null,null,null,"generic"]]
        25
        [["di",12],["af.httprm",12,"-123",0]]
    """.trimIndent()

    @Test
    fun `parses five hour and weekly rows`() {
        val snapshot = BatchexecuteUsageParser.parse(response, fetchedAt)

        assertEquals(UsageSource.BATCHEXECUTE, snapshot.source)
        assertEquals(0.42, snapshot.fiveHour?.usedFraction ?: 0.0, 0.0001)
        assertEquals(1_761_260_400_000L, snapshot.fiveHour?.resetAtEpochMs)
        assertEquals(0.10, snapshot.weekly?.usedFraction ?: 0.0, 0.0001)
        assertEquals(1_761_606_000_500L, snapshot.weekly?.resetAtEpochMs)
        assertNull(snapshot.tier)
    }

    @Test
    fun `missing rpc frame is a protocol change`() {
        assertThrows(UsageException.ProtocolChanged::class.java) {
            BatchexecuteUsageParser.parse("""[["wrb.fr","otherRpc","[]"]]""", fetchedAt)
        }
    }

    @Test
    fun `missing rows is a protocol change`() {
        val body = """[["wrb.fr","jSf9Qc","[null]",null,null,null,"generic"]]"""
        assertThrows(UsageException.ProtocolChanged::class.java) {
            BatchexecuteUsageParser.parse(body, fetchedAt)
        }
    }

    @Test
    fun `rows without known types are a protocol change`() {
        val body = """[["wrb.fr","jSf9Qc","[null,[[0,0.5,7,[]]]]",null,null,null,"generic"]]"""
        assertThrows(UsageException.ProtocolChanged::class.java) {
            BatchexecuteUsageParser.parse(body, fetchedAt)
        }
    }

    @Test
    fun `tolerates partial frames and missing reset data`() {
        val body = """[["wrb.fr","jSf9Qc","[null,[[0,0.25,1]]]",null,null,null,"generic"]]"""
        val snapshot = BatchexecuteUsageParser.parse(body, fetchedAt)
        assertEquals(25, snapshot.fiveHour?.usedPercent)
        assertNull(snapshot.fiveHour?.resetAtEpochMs)
    }
}

class WizTokenExtractorTest {

    @Test
    fun `extracts bootstrap tokens`() {
        val html = """
            <script>window.WIZ_global_data = {
              "SNlM0e":"AK6yLxToken",
              "cfb2h":"boq_bard-web_20261022.00_p0",
              "FdrFJe":"-1234567890123456789",
              "GGcqce":"en"
            };</script>
        """.trimIndent()

        val tokens = WizTokenExtractor.extract(html)

        assertEquals("AK6yLxToken", tokens?.requestToken)
        assertEquals("boq_bard-web_20261022.00_p0", tokens?.buildLabel)
        assertEquals("-1234567890123456789", tokens?.sessionId)
        assertEquals("en", tokens?.locale)
    }

    @Test
    fun `unescapes json string literals`() {
        val html = """{"SNlM0e":"AK6yLx\u003d\u003d","cfb2h":"a\/b"}"""
        val tokens = WizTokenExtractor.extract(html)
        assertEquals("AK6yLx==", tokens?.requestToken)
        assertEquals("a/b", tokens?.buildLabel)
    }

    @Test
    fun `returns null when the page has no token`() {
        assertNull(WizTokenExtractor.extract("<html>Sign in</html>"))
    }
}
