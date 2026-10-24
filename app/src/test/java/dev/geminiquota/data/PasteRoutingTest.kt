package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasteRoutingTest {

    private val token = "sk-ant-sid01-abcDEF123456"

    private val claudeHeader = "sessionKey=$token; __cf_bm=xyz; lastActiveOrg=org-1"

    private val googleHeader = "SID=abc; HSID=def; __Secure-1PSID=ghi"

    @Test
    fun `a claude export is routed to claude`() {
        assertEquals(ProviderId.CLAUDE, PasteRouting.target(claudeHeader))
    }

    @Test
    fun `a google export is routed to gemini`() {
        assertEquals(ProviderId.GEMINI, PasteRouting.target(googleHeader))
    }

    /**
     * Google's sign-in widget leaves `g_state` on third-party sites, so a claude.ai
     * export can carry a Google-looking marker. Sending it to the Gemini endpoint
     * produced "That session could not be verified" with no explanation.
     */
    @Test
    fun `a paste with both sessions goes to claude`() {
        assertEquals(ProviderId.CLAUDE, PasteRouting.target("$googleHeader; $claudeHeader"))
    }

    @Test
    fun `a claude json export is routed to claude`() {
        val raw = """[{"domain":".claude.ai","name":"sessionKey","value":"$token"}]"""
        assertEquals(ProviderId.CLAUDE, PasteRouting.target(raw))
    }

    @Test
    fun `junk is routed nowhere`() {
        assertNull(PasteRouting.target("hello world"))
        assertNull(PasteRouting.target(""))
        assertNull(PasteRouting.target(null))
    }
}
