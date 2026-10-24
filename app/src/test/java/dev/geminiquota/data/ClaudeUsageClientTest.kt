package dev.geminiquota.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeUsageClientTest {

    private lateinit var server: MockWebServer

    private val token = "sk-ant-sid01-abcDEF123456"
    private val clock = 1_790_000_000_000L

    private val orgsBody = """[{"uuid":"org-1","name":"Personal"},{"uuid":"org-2","name":"Work"}]"""

    private val usageBody = """
        {
          "five_hour": {"utilization": 42.0, "resets_at": "2026-10-23T13:10:00Z"},
          "seven_day": {"utilization": 24.0, "resets_at": "2026-10-27T10:10:00Z"},
          "seven_day_opus": null
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = ClaudeUsageClient(baseUrl = server.url("/"), clock = { clock })

    @Test
    fun `forwards every claude cookie so cloudflare sees the browser state`() {
        server.enqueue(MockResponse().setBody(orgsBody))
        server.enqueue(MockResponse().setBody(usageBody))

        client().fetchUsage("sessionKey=$token; __cf_bm=cf-value; lastActiveOrg=org-1")

        assertEquals(
            "sessionKey=$token; __cf_bm=cf-value; lastActiveOrg=org-1",
            server.takeRequest().getHeader("Cookie"),
        )
    }

    @Test
    fun `discovers the organization then reads usage`() {
        server.enqueue(MockResponse().setBody(orgsBody))
        server.enqueue(MockResponse().setBody(usageBody))

        val page = client().fetchUsage(token)

        assertEquals(ProviderId.CLAUDE, page.provider)
        assertEquals(42, page.window(QuotaWindowId.FIVE_HOUR)?.usedPercent)
        assertEquals(24, page.window(QuotaWindowId.WEEKLY)?.usedPercent)

        val discovery = server.takeRequest()
        assertEquals("/api/organizations", discovery.path)
        assertEquals("sessionKey=$token", discovery.getHeader("Cookie"))
        assertTrue(discovery.getHeader("User-Agent")!!.contains("Chrome"))

        val usage = server.takeRequest()
        assertEquals("/api/organizations/org-1/usage", usage.path)
    }

    @Test
    fun `a known organization id skips discovery`() {
        server.enqueue(MockResponse().setBody(usageBody))

        client().fetchUsage(token, organizationId = "org-9")

        assertEquals("/api/organizations/org-9/usage", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rejects a payload with no organization`() {
        server.enqueue(MockResponse().setBody("[]"))

        assertThrows(UsageException.OrgNotFound::class.java) {
            client().fetchUsage(token)
        }
    }

    @Test
    fun `an expired session is an auth error`() {
        server.enqueue(MockResponse().setResponseCode(403))

        assertThrows(UsageException.AuthRequired::class.java) {
            client().fetchUsage(token, organizationId = "org-1")
        }
    }

    @Test
    fun `rate limiting keeps the retry-after hint`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "30"),
        )

        val error = assertThrows(UsageException.RateLimited::class.java) {
            client().fetchUsage(token, organizationId = "org-1")
        }
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun `a server error keeps the status code`() {
        server.enqueue(MockResponse().setResponseCode(503))

        val error = assertThrows(UsageException.Server::class.java) {
            client().fetchUsage(token, organizationId = "org-1")
        }
        assertEquals(503, error.statusCode)
    }

    @Test
    fun `a missing cookie fails before any request`() {
        assertThrows(UsageException.AuthRequired::class.java) {
            client().fetchUsage("intercom-device-id=1")
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `transport failures surface as network errors`() {
        server.shutdown()

        assertThrows(UsageException.Network::class.java) {
            client().fetchUsage(token, organizationId = "org-1")
        }
    }
}
